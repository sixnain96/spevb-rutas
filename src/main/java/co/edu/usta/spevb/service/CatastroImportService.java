package co.edu.usta.spevb.service;

import co.edu.usta.spevb.config.CityArea;
import co.edu.usta.spevb.repository.DireccionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Descarga las direcciones de predios del catastro municipal (ArcGIS REST, páginas de 2.000) y las guarda
 * interpretadas en la tabla {@code direcciones}. Es idempotente: volver a ejecutarla actualiza lo existente.
 * Se lanza sola al arrancar si la tabla está vacía o tiene más de {@code refresh-days} días sin actualizar.
 */
@Service
public class CatastroImportService {
    private static final Logger log = LoggerFactory.getLogger(CatastroImportService.class);
    private static final int PAGINA = 2000;

    public record Estado(String estado, int leidos, int guardados, int descartados, String mensaje, Instant inicio, Instant fin) {
        static Estado inactivo() { return new Estado("INACTIVO", 0, 0, 0, "", null, null); }
    }

    private final String url;
    private final boolean alArrancar;
    private final long diasRefresco;
    private final DireccionRepository repo;
    private final CityArea area;
    private final ObjectMapper mapper;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final AtomicReference<Estado> estado = new AtomicReference<>(Estado.inactivo());

    public CatastroImportService(@Value("${spevb.catastro.url:}") String url,
                                 @Value("${spevb.direcciones.import-on-start:true}") boolean alArrancar,
                                 @Value("${spevb.direcciones.refresh-days:30}") long diasRefresco,
                                 DireccionRepository repo, CityArea area, ObjectMapper mapper) {
        this.url = url.strip();
        this.alArrancar = alArrancar;
        this.diasRefresco = diasRefresco;
        this.repo = repo;
        this.area = area;
        this.mapper = mapper;
    }

    public boolean configurado() { return !url.isBlank() && url.startsWith("https://"); }
    public Estado estado() { return estado.get(); }

    @EventListener(ApplicationReadyEvent.class)
    void alIniciar() {
        if (!alArrancar || !configurado()) return;
        try {
            boolean vacia = repo.contar() == 0;
            boolean vieja = repo.ultimaImportacionExitosa().map(t -> t.isBefore(Instant.now().minus(Duration.ofDays(diasRefresco)))).orElse(true);
            if (vacia || vieja) iniciar();
        } catch (RuntimeException ex) {
            log.warn("No se pudo comprobar el estado de la base de direcciones: {}", ex.toString());
        }
    }

    /** Lanza la importación en segundo plano. Devuelve false si ya hay una en curso. */
    public synchronized boolean iniciar() {
        if (!configurado() || "EN_CURSO".equals(estado.get().estado())) return false;
        estado.set(new Estado("EN_CURSO", 0, 0, 0, "Descargando predios del catastro…", Instant.now(), null));
        Thread.ofVirtual().name("importar-catastro").start(this::ejecutar);
        return true;
    }

    private void ejecutar() {
        Instant inicio = estado.get().inicio();
        long idLog = 0;
        int leidos = 0, guardados = 0, descartados = 0;
        try {
            idLog = repo.iniciarImportacion("CATASTRO");
            for (int offset = 0; ; offset += PAGINA) {
                JsonNode raiz = pedirPagina(offset);
                JsonNode items = raiz.path("features");
                if (!items.isArray() || items.isEmpty()) break;
                List<DireccionRepository.Nueva> lote = new ArrayList<>(items.size());
                for (JsonNode f : items) {
                    leidos++;
                    var nueva = convertir(f);
                    if (nueva == null) descartados++; else lote.add(nueva);
                }
                guardados += repo.guardarLote(lote);
                estado.set(new Estado("EN_CURSO", leidos, guardados, descartados, "Importando… " + leidos + " predios leídos", inicio, null));
                if (!raiz.path("exceededTransferLimit").asBoolean(false) && items.size() < PAGINA) break;
            }
            repo.recalcularBarrios();
            repo.completarBarriosTruncados();
            repo.recalcularBarrios();
            repo.cerrarImportacion(idLog, "OK", leidos, guardados, descartados, null);
            estado.set(new Estado("OK", leidos, guardados, descartados, "Importación completada", inicio, Instant.now()));
            log.info("Catastro importado: {} leídos, {} guardados, {} descartados.", leidos, guardados, descartados);
        } catch (Exception ex) {
            log.error("Falló la importación del catastro", ex);
            try { if (idLog > 0) repo.cerrarImportacion(idLog, "ERROR", leidos, guardados, descartados, ex.toString()); } catch (RuntimeException ignored) { }
            estado.set(new Estado("ERROR", leidos, guardados, descartados, "La importación falló: " + ex.getMessage(), inicio, Instant.now()));
        }
    }

    private JsonNode pedirPagina(int offset) throws Exception {
        String q = "where=" + enc("DIRECCION IS NOT NULL") + "&outFields=OBJECTID,CODIGO,DIRECCION&outSR=4326&returnCentroid=true"
                + "&returnGeometry=false&orderByFields=OBJECTID&resultOffset=" + offset + "&resultRecordCount=" + PAGINA + "&f=json";
        Exception ultimo = null;
        for (int intento = 1; intento <= 4; intento++) {
            try {
                var request = HttpRequest.newBuilder(URI.create(url + (url.contains("?") ? "&" : "?") + q)).timeout(Duration.ofSeconds(90))
                        .header("User-Agent", "SPEVB-Rutas-USTA/3.6").header("Accept", "application/json").GET().build();
                var response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
                try (var body = response.body()) {
                    if (response.statusCode() != 200) throw new IllegalStateException("HTTP " + response.statusCode());
                    JsonNode raiz = mapper.readTree(body.readNBytes(20_000_000));
                    if (raiz.has("error")) throw new IllegalStateException(raiz.path("error").path("message").asText("error del servicio"));
                    return raiz;
                }
            } catch (Exception ex) {
                ultimo = ex;
                Thread.sleep(1500L * intento);
            }
        }
        throw ultimo == null ? new IllegalStateException("sin respuesta") : ultimo;
    }

    /** Convierte un predio en una fila de la base. Devuelve null si no sirve (sin texto, sin punto o fuera de la ciudad). */
    DireccionRepository.Nueva convertir(JsonNode f) {
        String texto = f.path("attributes").path("DIRECCION").asText("").strip();
        String codigo = f.path("attributes").path("CODIGO").asText("").strip();
        if (codigo.isEmpty()) codigo = "OBJ" + f.path("attributes").path("OBJECTID").asText("");
        double lng = f.path("centroid").path("x").asDouble(Double.NaN), lat = f.path("centroid").path("y").asDouble(Double.NaN);
        if (texto.length() < 4 || !area.contains(lat, lng)) return null;
        var d = DireccionParser.parsear(texto);
        if (!d.estructurada() && d.barrio().isEmpty() && d.manzana().isEmpty()) return null;
        String cuad = d.cuadrante();
        return new DireccionRepository.Nueva("CATASTRO", clip(codigo, 64), clip(texto, 220), clip(d.texto(), 220),
                clip(d.tipoVia(), 3), clip(d.via(), 14), cuad, clip(d.cruce(), 14), d.placa(), d.placa2(),
                clip(d.manzana(), 14), clip(d.casa(), 14), clip(d.barrio(), 80), lat, lng, "PREDIO_APROXIMADO", 70);
    }

    private static String clip(String s, int max) { return s.length() > max ? s.substring(0, max) : s; }
    private static String enc(String s) { return URLEncoder.encode(s, StandardCharsets.UTF_8); }
}
