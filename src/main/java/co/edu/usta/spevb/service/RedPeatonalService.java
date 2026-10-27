package co.edu.usta.spevb.service;

import co.edu.usta.spevb.config.CityArea;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Trayecto a pie con red propia: descarga una vez de OpenStreetMap (Overpass) las calles, andenes y senderos
 * de Villavicencio, los guarda en disco y los mantiene en memoria. No depende de un servidor de rutas externo,
 * no tiene cuotas y responde en milisegundos. Mientras la red no esté lista, {@link CaminataService} usa el servicio
 * OSRM configurado y, en último caso, la línea recta.
 */
@Service
public class RedPeatonalService {
    private static final Logger log = LoggerFactory.getLogger(RedPeatonalService.class);
    private static final double AJUSTE_MAXIMO_METROS = 250;
    /** Hasta dónde se busca una calle para unir cada extremo de un paso propio. */
    private static final double ENLACE_PASO_METROS = 12;
    /** Calles que se prueban a la vez en el punto de partida/llegada si están casi igual de cerca. */
    private static final int CALLES_PROBADAS = 4;
    private static final double MARGEN_CALLES_METROS = 40;
    private static final double COSTO_MAXIMO_RUTA = 15_000;
    private static final double COSTO_MAXIMO_MATRIZ = 8_000;
    private static final int MAGIA = 0x53455650; // «SEVP»

    public record Estado(String estado, int nodos, int aristas, String mensaje, Instant actualizado) {}

    private final boolean habilitada;
    private final Path archivo;
    private final long diasRefresco;
    private final List<String> servidores;
    private final CityArea area;
    private final ObjectMapper mapper;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final AtomicReference<RedPeatonal> red = new AtomicReference<>();
    private final AtomicReference<Estado> estado = new AtomicReference<>(new Estado("INACTIVA", 0, 0, "", null));

    @org.springframework.beans.factory.annotation.Autowired
    public RedPeatonalService(@Value("${spevb.red.enabled:true}") boolean habilitada,
                              @Value("${spevb.red.cache-file:data/red-peatonal.bin.gz}") String archivo,
                              @Value("${spevb.red.refresh-days:60}") long diasRefresco,
                              @Value("${spevb.osm.overpass-urls:}") String urls,
                              CityArea area, ObjectMapper mapper) {
        this.habilitada = habilitada;
        this.archivo = Path.of(archivo);
        this.diasRefresco = diasRefresco;
        List<String> lista = new ArrayList<>();
        for (String u : urls.split(",")) if (u.strip().startsWith("https://")) lista.add(u.strip());
        this.servidores = List.copyOf(lista);
        this.area = area;
        this.mapper = mapper;
    }

    /** Para pruebas: una red ya construida. */
    RedPeatonalService(RedPeatonal lista, CityArea area) {
        this(false, "data/no-usado", 60, "", area, null);
        red.set(lista);
        estado.set(new Estado("LISTA", lista.nodos(), lista.aristas(), "", Instant.now()));
    }

    /** Caminos de OpenStreetMap tal como se descargaron: sobre ellos se vuelven a aplicar los pasos propios. */
    private volatile List<RedPeatonal.CaminoGuardado> base;
    private volatile co.edu.usta.spevb.repository.PasoPeatonalRepository pasos;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void usarPasos(co.edu.usta.spevb.repository.PasoPeatonalRepository pasos) { this.pasos = pasos; }

    public boolean lista() { return red.get() != null; }
    /** Tramos de la red en una caja pequeña (máx. 0,03° por lado), para dibujarlos en el mapa de administración. */
    public List<double[]> segmentos(double sur, double oeste, double norte, double este) {
        RedPeatonal r = red.get();
        if (r == null) return List.of();
        if (norte - sur > 0.03 || este - oeste > 0.03) throw new IllegalArgumentException("La zona pedida es demasiado grande: acerca el mapa.");
        return r.segmentos(sur, oeste, norte, este, 6000);
    }

    RedPeatonal getRedParaPruebas() { return red.get(); }
    public Estado estado() { return estado.get(); }
    public boolean configurada() { return habilitada; }

    // ------------------------------------------------------------------ consulta
    public Optional<CaminataService.Tramo> ruta(double latA, double lngA, double latB, double lngB) {
        RedPeatonal r = red.get();
        if (r == null) return Optional.empty();
        var a = r.ajustarVarios(latA, lngA, AJUSTE_MAXIMO_METROS, CALLES_PROBADAS, MARGEN_CALLES_METROS);
        var b = r.ajustarVarios(latB, lngB, AJUSTE_MAXIMO_METROS, CALLES_PROBADAS, MARGEN_CALLES_METROS);
        if (a.isEmpty() || b.isEmpty()) return Optional.empty();
        var camino = r.camino(a, b, COSTO_MAXIMO_RUTA);
        if (camino == null) return Optional.empty();
        // 4,8 km/h: mismo ritmo que usa la interfaz para estimar el tiempo.
        return Optional.of(new CaminataService.Tramo(camino.coordenadas(), camino.metros(), camino.metros() / 1.333));
    }

    /**
     * Matriz de distancias a pie (metros): origen por la red, más lo que separa cada punto de su calle.
     * Puntos en [lng, lat]. Vacío si la red no está lista o un origen no tiene calle cerca.
     */
    public Optional<double[][]> distancias(List<double[]> origenes, List<double[]> destinos) {
        RedPeatonal r = red.get();
        if (r == null) return Optional.empty();
        List<RedPeatonal.Snap> d = new ArrayList<>();
        for (double[] p : destinos) d.add(r.ajustar(p[1], p[0], AJUSTE_MAXIMO_METROS));
        double[][] m = new double[origenes.size()][destinos.size()];
        for (int i = 0; i < m.length; i++) {
            var o = r.ajustarVarios(origenes.get(i)[1], origenes.get(i)[0], AJUSTE_MAXIMO_METROS, CALLES_PROBADAS, MARGEN_CALLES_METROS);
            if (o.isEmpty()) return Optional.empty();
            double[] costos = r.costosDesde(o, d, COSTO_MAXIMO_MATRIZ);
            for (int j = 0; j < costos.length; j++)
                m[i][j] = d.get(j) == null ? Double.NaN : costos[j] + d.get(j).distM();
        }
        return Optional.of(m);
    }

    // ------------------------------------------------------------------ ciclo de vida
    @EventListener(ApplicationReadyEvent.class)
    void alIniciar() {
        if (!habilitada) return;
        Thread.ofVirtual().name("red-peatonal").start(() -> {
            boolean refrescar = true;
            log.info("Red peatonal: iniciando (archivo {}).", archivo.toAbsolutePath());
            if (Files.isReadable(archivo)) {
                try {
                    cargarDeDisco();
                    refrescar = Files.getLastModifiedTime(archivo).toInstant().isBefore(Instant.now().minus(Duration.ofDays(diasRefresco)));
                } catch (Exception ex) {
                    log.warn("No se pudo leer la red peatonal guardada ({}): se descargará de nuevo.", ex.toString());
                }
            }
            if (red.get() == null) {
                // Primer arranque: la semilla incluida (casco urbano) deja la red lista al instante mientras se descarga la completa.
                try { cargarSemilla(); } catch (Exception ex) { log.warn("No se pudo cargar la red peatonal incluida: {}", ex.toString()); }
            }
            if (refrescar || red.get() == null) descargar();
        });
    }

    /** Descarga de nuevo la red (administración). */
    public synchronized boolean actualizar() {
        if (!habilitada || servidores.isEmpty() || "EN_CURSO".equals(estado.get().estado())) return false;
        Thread.ofVirtual().name("red-peatonal-actualizar").start(this::descargar);
        return true;
    }

    void cargarDeDisco() throws IOException {
        try (var in = Files.newInputStream(archivo)) { cargar(in, "Cargada de disco"); }
    }

    /** Instantánea de OpenStreetMap (© colaboradores de OpenStreetMap, ODbL) incluida en el proyecto: casco urbano de Villavicencio. */
    void cargarSemilla() throws IOException {
        try (var in = getClass().getResourceAsStream("/osm/red-peatonal-semilla.bin.gz")) {
            if (in == null) throw new IOException("la semilla no está incluida");
            cargar(in, "Semilla incluida (casco urbano)");
        }
    }

    private void cargar(InputStream bruto, String origen) throws IOException {
        List<RedPeatonal.CaminoGuardado> caminos = new ArrayList<>();
        try (var in = new DataInputStream(new java.io.BufferedInputStream(new GZIPInputStream(bruto, 1 << 16)))) {
            if (in.readInt() != MAGIA) throw new IOException("formato desconocido");
            int n = in.readInt();
            for (int i = 0; i < n; i++) {
                byte clase = in.readByte();
                int k = in.readUnsignedShort();
                long[] ids = new long[k];
                double[] lats = new double[k], lngs = new double[k];
                for (int j = 0; j < k; j++) { ids[j] = in.readLong(); lats[j] = in.readInt() / 1e7; lngs[j] = in.readInt() / 1e7; }
                caminos.add(new RedPeatonal.CaminoGuardado(ids, lats, lngs, clase));
            }
        }
        publicar(caminos, origen);
    }

    private void publicar(List<RedPeatonal.CaminoGuardado> caminos, String origen) {
        base = caminos;
        reconstruir(origen);
    }

    /** Vuelve a aplicar los pasos propios sobre la red base. Devuelve, por paso, cuántos extremos quedaron conectados. */
    public Map<Long, Integer> aplicarPasos() {
        return base == null ? Map.of() : reconstruir("Actualizada con pasos propios");
    }

    private synchronized Map<Long, Integer> reconstruir(String origen) {
        var c = new RedPeatonal.Constructor();
        for (var w : base) c.agregarCamino(w.ids(), w.lats(), w.lngs(), w.clase());
        Map<Long, Integer> conexiones = new java.util.HashMap<>();
        int nPasos = 0, nBloqueos = 0;
        if (pasos != null && mapper != null) {
            try {
                for (var f : pasos.listar()) {
                    var coords = mapper.readTree(f.geojson()).path("coordinates");
                    double[] lats = new double[coords.size()], lngs = new double[coords.size()];
                    for (int i = 0; i < lats.length; i++) { lngs[i] = coords.get(i).get(0).asDouble(); lats[i] = coords.get(i).get(1).asDouble(); }
                    if ("BLOQUEO".equals(f.tipo())) { c.bloquear(lats, lngs); nBloqueos++; }
                    else { conexiones.put(f.id(), c.agregarPaso(lats, lngs, ENLACE_PASO_METROS)); nPasos++; }
                }
            } catch (RuntimeException ex) {
                log.warn("No se pudieron aplicar los pasos peatonales propios: {}", ex.toString());
            }
        }
        RedPeatonal nueva = c.construir();
        if (nueva.vacia()) throw new IllegalStateException("la red quedó vacía");
        red.set(nueva);
        estado.set(new Estado("LISTA", nueva.nodos(), nueva.aristas(), origen, Instant.now()));
        log.info("Red peatonal lista: {} nodos y {} aristas ({}; {} pasos propios, {} bloqueos).",
                nueva.nodos(), nueva.aristas(), origen, nPasos, nBloqueos);
        return conexiones;
    }

    private void guardarEnDisco(List<RedPeatonal.CaminoGuardado> caminos) {
        try {
            Path dir = archivo.toAbsolutePath().getParent();
            if (dir != null) Files.createDirectories(dir);
            Path temporal = Files.createTempFile(dir, "red-", ".tmp");
            try (var out = new DataOutputStream(new java.io.BufferedOutputStream(new GZIPOutputStream(Files.newOutputStream(temporal), 1 << 16)))) {
                out.writeInt(MAGIA);
                out.writeInt(caminos.size());
                for (var w : caminos) {
                    out.writeByte(w.clase());
                    out.writeShort(w.ids().length);
                    for (int j = 0; j < w.ids().length; j++) {
                        out.writeLong(w.ids()[j]);
                        out.writeInt((int) Math.round(w.lats()[j] * 1e7));
                        out.writeInt((int) Math.round(w.lngs()[j] * 1e7));
                    }
                }
            }
            Files.move(temporal, archivo, StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception ex) {
            log.warn("No se pudo guardar la red peatonal en {}: {} (seguirá en memoria).", archivo, ex.toString());
        }
    }

    // ------------------------------------------------------------------ descarga
    private void descargar() {
        if (servidores.isEmpty()) {
            log.info("Red peatonal: sin servidores Overpass configurados; se usa la red cargada ({} nodos).", nodosActuales());
            if (red.get() == null) estado.set(new Estado("ERROR", 0, 0, "No hay servidores Overpass configurados ni red guardada.", Instant.now()));
            return;
        }
        estado.set(new Estado("EN_CURSO", nodosActuales(), aristasActuales(), "Descargando calles de OpenStreetMap…", Instant.now()));
        log.info("Red peatonal: descargando las calles de OpenStreetMap (4 zonas, puede tardar varios minutos)…");
        try {
            List<RedPeatonal.CaminoGuardado> caminos = new ArrayList<>();
            Set<Long> vistos = new HashSet<>();
            // Cuatro cuadrantes: las consultas pequeñas evitan los cortes (429/504) de los Overpass públicos.
            double midLat = (area.minLat() + area.maxLat()) / 2, midLng = (area.minLng() + area.maxLng()) / 2;
            double[][] cajas = {
                    {area.minLat(), area.minLng(), midLat, midLng}, {area.minLat(), midLng, midLat, area.maxLng()},
                    {midLat, area.minLng(), area.maxLat(), midLng}, {midLat, midLng, area.maxLat(), area.maxLng()}};
            int zona = 0;
            for (double[] c : cajas) {
                descargarCaja(c, caminos, vistos);
                log.info("Red peatonal: zona {} de {} lista ({} tramos de calle).", ++zona, cajas.length, caminos.size());
                Thread.sleep(2500);
            }
            if (caminos.isEmpty()) throw new IllegalStateException("Overpass no devolvió calles");
            publicar(caminos, "Descargada de OpenStreetMap");
            guardarEnDisco(caminos);
        } catch (Exception ex) {
            log.error("Falló la descarga de la red peatonal: {}", ex.toString());
            estado.set(new Estado(red.get() == null ? "ERROR" : "LISTA", nodosActuales(), aristasActuales(),
                    "No se pudo descargar: " + ex.getMessage() + (red.get() == null ? "" : " Se conserva la red anterior."), Instant.now()));
        }
    }

    private int nodosActuales() { var r = red.get(); return r == null ? 0 : r.nodos(); }
    private int aristasActuales() { var r = red.get(); return r == null ? 0 : r.aristas(); }

    private static final String TIPOS = "footway|path|pedestrian|steps|living_street|residential|service|unclassified|tertiary|"
            + "tertiary_link|secondary|secondary_link|primary|primary_link|trunk|trunk_link|track|cycleway|corridor|road";

    private void descargarCaja(double[] c, List<RedPeatonal.CaminoGuardado> destino, Set<Long> vistos) throws Exception {
        String caja = String.format(Locale.ROOT, "(%.5f,%.5f,%.5f,%.5f)", c[0], c[1], c[2], c[3]);
        String consulta = "[out:json][timeout:180];way[\"highway\"~\"^(" + TIPOS + ")$\"][\"foot\"!~\"^(no|private)$\"]"
                + "[\"access\"!~\"^(no|private)$\"][\"area\"!=\"yes\"]" + caja + ";out geom qt;";
        Exception ultimo = null;
        Set<String> caidos = new HashSet<>();
        for (String servidor : servidores) {
            if (caidos.contains(servidor)) continue;
            for (int intento = 1; intento <= 3; intento++) {
                int antes = destino.size();
                try {
                    var request = HttpRequest.newBuilder(URI.create(servidor)).timeout(Duration.ofSeconds(240))
                            .header("User-Agent", "SPEVB-Rutas-USTA/3.4").header("Content-Type", "application/x-www-form-urlencoded")
                            .POST(HttpRequest.BodyPublishers.ofString("data=" + URLEncoder.encode(consulta, StandardCharsets.UTF_8))).build();
                    var response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
                    try (var body = response.body()) {
                        if (response.statusCode() != 200) throw new IllegalStateException("HTTP " + response.statusCode());
                        leerCaminos(body, destino, vistos);
                    }
                    return;
                } catch (Exception ex) {
                    while (destino.size() > antes) destino.remove(destino.size() - 1);
                    ultimo = ex;
                    log.warn("Overpass {} (intento {}): {}", servidor, intento, ex.toString());
                    Thread.sleep(3000L * intento);
                }
            }
            caidos.add(servidor);
        }
        throw ultimo == null ? new IllegalStateException("sin servidor Overpass") : ultimo;
    }

    /** Lee elemento por elemento (la respuesta puede pesar decenas de MB): sólo uno vive en memoria a la vez. */
    void leerCaminos(InputStream in, List<RedPeatonal.CaminoGuardado> destino, Set<Long> vistos) {
        try (JsonParser p = mapper.createParser(in)) {
            while (p.nextToken() != null) {
                if (p.currentToken() == JsonToken.PROPERTY_NAME && "elements".equals(p.currentName())) break;
            }
            if (p.nextToken() != JsonToken.START_ARRAY) throw new IllegalStateException("respuesta sin elementos");
            while (p.nextToken() == JsonToken.START_OBJECT) {
                JsonNode e = p.readValueAsTree();
                var w = convertir(e);
                if (w != null && vistos.add(e.path("id").asLong())) destino.add(w);
            }
        }
    }

    static RedPeatonal.CaminoGuardado convertir(JsonNode e) {
        if (!"way".equals(e.path("type").asText())) return null;
        byte clase = RedPeatonal.clase(e.path("tags").path("highway").asText(null));
        JsonNode nodos = e.path("nodes"), geom = e.path("geometry");
        if (clase < 0 || !nodos.isArray() || !geom.isArray() || nodos.size() != geom.size() || nodos.size() < 2
                || nodos.size() > 65_000) return null;
        long[] ids = new long[nodos.size()];
        double[] lats = new double[ids.length], lngs = new double[ids.length];
        for (int i = 0; i < ids.length; i++) {
            ids[i] = nodos.get(i).asLong();
            lats[i] = geom.get(i).path("lat").asDouble(Double.NaN);
            lngs[i] = geom.get(i).path("lon").asDouble(Double.NaN);
            if (Double.isNaN(lats[i]) || Double.isNaN(lngs[i])) return null;
        }
        return new RedPeatonal.CaminoGuardado(ids, lats, lngs, clase);
    }
}
