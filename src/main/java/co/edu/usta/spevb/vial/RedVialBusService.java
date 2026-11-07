package co.edu.usta.spevb.vial;

import co.edu.usta.spevb.config.CityArea;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Red vial PARA BUSES (independiente de {@code RedPeatonalService}). Descarga de OpenStreetMap (Overpass) las vías
 * vehiculares de la ciudad, las filtra con {@link ReglasBus}, las guarda en una caché propia
 * ({@code spevb.vial.cache-file}, JSON comprimido distinto del archivo de la red peatonal) y mantiene el grafo en memoria.
 *
 * <p>El arranque es diferido y tolerante a fallos: nunca bloquea ni impide iniciar la aplicación; si no hay red ni caché,
 * {@link #estado()} queda NO_DISPONIBLE y quien llama conserva la geometría original. En las pruebas no se descarga nada:
 * se usa un {@link ProveedorVias} inyectado o la propiedad {@code spevb.vial.enabled=false}.
 */
@Service
public class RedVialBusService {
    private static final Logger log = LoggerFactory.getLogger(RedVialBusService.class);

    /** Texto fijo para mostrar en la interfaz junto a un tramo ajustado. NO es una autorización oficial. */
    public static final String AVISO = "Vía transitable según la red vial disponible. Requiere revisión del equipo";

    public enum Fase { LISTA, CARGANDO, NO_DISPONIBLE }

    public record Estado(Fase estado, int nodos, int aristas, String aviso, String detalle, Instant actualizado) {}

    /** Origen de las vías (Overpass en producción; una lista fija en las pruebas). */
    @FunctionalInterface
    public interface ProveedorVias { List<ViaOsm> cargar() throws Exception; }

    private static final String FORMATO = "red-vial-bus-1";
    private static final String TIPOS = "motorway|motorway_link|trunk|trunk_link|primary|primary_link|secondary|secondary_link|"
            + "tertiary|tertiary_link|unclassified|residential|living_street|service|busway|bus_guideway|road";
    /** Solo estas etiquetas se guardan en memoria y en la caché. */
    private static final Set<String> ETIQUETAS = Set.of("highway", "name", "access", "bus", "psv", "motor_vehicle", "vehicle",
            "oneway", "oneway:bus", "oneway:psv", "junction", "construction", "proposed", "service", "busway", "bridge", "tunnel", "layer");

    private final boolean habilitada;
    private final Path archivo;
    private final long diasRefresco;
    private final double maxSnapMetros;
    private final List<String> servidores;
    private final CityArea area;
    private final ObjectMapper mapper;
    private final ProveedorVias proveedorInyectado;
    private final ProveedorMatchingExterno externo;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final AtomicReference<RedVialBus> red = new AtomicReference<>();
    private final AtomicReference<MapMatchingBus> matcher = new AtomicReference<>();
    private final AtomicReference<Estado> estado;

    @Autowired
    public RedVialBusService(@Value("${spevb.vial.enabled:true}") boolean habilitada,
                             @Value("${spevb.vial.cache-file:data/red-vial-bus.json.gz}") String archivo,
                             @Value("${spevb.vial.refresh-days:60}") long diasRefresco,
                             @Value("${spevb.vial.max-snap-metros:30}") double maxSnapMetros,
                             @Value("${spevb.vial.matching-url:}") String matchingUrl,
                             @Value("${spevb.osm.overpass-urls:}") String urls,
                             CityArea area, ObjectMapper mapper) {
        this(habilitada, Path.of(archivo), diasRefresco, maxSnapMetros, new ProveedorMatchingExterno(matchingUrl),
                servidores(urls), area, mapper, null);
    }

    /** Para pruebas: proveedor de vías inyectado (sin Internet). */
    RedVialBusService(boolean habilitada, Path archivo, long diasRefresco, double maxSnapMetros, ProveedorVias proveedor,
                      ObjectMapper mapper) {
        this(habilitada, archivo, diasRefresco, maxSnapMetros, new ProveedorMatchingExterno(""), List.of(), null, mapper, proveedor);
    }

    private RedVialBusService(boolean habilitada, Path archivo, long diasRefresco, double maxSnapMetros,
                              ProveedorMatchingExterno externo, List<String> servidores, CityArea area, ObjectMapper mapper,
                              ProveedorVias proveedor) {
        this.habilitada = habilitada;
        this.archivo = archivo;
        this.diasRefresco = diasRefresco;
        this.maxSnapMetros = maxSnapMetros > 0 ? maxSnapMetros : 30;
        this.externo = externo;
        this.servidores = servidores;
        this.area = area;
        this.mapper = mapper;
        this.proveedorInyectado = proveedor;
        this.estado = new AtomicReference<>(new Estado(Fase.NO_DISPONIBLE, 0, 0, AVISO,
                habilitada ? "Aún no se ha iniciado la carga" : "Red vial de buses deshabilitada (spevb.vial.enabled=false)", null));
    }

    private static List<String> servidores(String urls) {
        List<String> lista = new ArrayList<>();
        for (String u : urls.split(",")) if (u.strip().startsWith("https://")) lista.add(u.strip());
        return List.copyOf(lista);
    }

    // ------------------------------------------------------------------ API
    /** La red lista, o {@code null} si todavía no está disponible (use {@link #lista()}). */
    public RedVialBus red() { return red.get(); }
    public boolean lista() { return red.get() != null; }
    public Estado estado() { return estado.get(); }
    public boolean configurada() { return habilitada; }
    /** Distancia máxima (m) para ajustar un punto a una vía ({@code spevb.vial.max-snap-metros}). */
    public double maxSnapMetros() { return maxSnapMetros; }
    /** Mapa-matching propio sobre la red lista, o {@code null} si no lo está. */
    public MapMatchingBus matcher() { return matcher.get(); }
    public MapMatchingBus.Opciones opcionesPorDefecto() { return new MapMatchingBus.Opciones(maxSnapMetros, 500, 10, true); }
    /** Proveedor OSRM opcional (inactivo si {@code BUS_MATCHING_URL} está vacía). */
    public ProveedorMatchingExterno externo() { return externo; }

    // ------------------------------------------------------------------ ciclo de vida
    @EventListener(ApplicationReadyEvent.class)
    void alIniciar() {
        if (!habilitada) return;
        estado.set(new Estado(Fase.CARGANDO, 0, 0, AVISO, "Cargando la red vial de buses…", Instant.now()));
        Thread.ofVirtual().name("red-vial-bus").start(this::cargarTodo);
    }

    /** Carga síncrona (caché, o proveedor/Overpass si falta o venció). Nunca lanza excepciones. */
    void cargarTodo() {
        try {
            boolean refrescar = true;
            if (Files.isReadable(archivo)) {
                try {
                    try (InputStream in = Files.newInputStream(archivo)) { publicar(leerVias(new GZIPInputStream(in, 1 << 16)), "Cargada de la caché"); }
                    refrescar = Files.getLastModifiedTime(archivo).toInstant().isBefore(Instant.now().minus(Duration.ofDays(diasRefresco)));
                } catch (Exception ex) {
                    log.warn("No se pudo leer la caché de la red vial de buses ({}): se descargará de nuevo.", ex.toString());
                }
            }
            if (refrescar || red.get() == null) descargar();
        } catch (Throwable t) {
            log.error("Red vial de buses: error inesperado: {}", t.toString());
            if (red.get() == null) estado.set(new Estado(Fase.NO_DISPONIBLE, 0, 0, AVISO, "Error: " + t.getMessage(), Instant.now()));
        }
    }

    private void descargar() {
        if (proveedorInyectado == null && (servidores.isEmpty() || area == null || mapper == null)) {
            if (red.get() == null)
                estado.set(new Estado(Fase.NO_DISPONIBLE, 0, 0, AVISO, "No hay caché ni servidores Overpass configurados.", Instant.now()));
            return;
        }
        if (red.get() == null) estado.set(new Estado(Fase.CARGANDO, 0, 0, AVISO, "Descargando vías de OpenStreetMap…", Instant.now()));
        try {
            List<ViaOsm> vias = proveedorInyectado != null ? proveedorInyectado.cargar() : descargarOverpass();
            if (vias == null || vias.isEmpty()) throw new IllegalStateException("no se obtuvieron vías");
            publicar(vias, "Descargada de OpenStreetMap");
            guardar(vias);
        } catch (Exception ex) {
            log.error("Falló la descarga de la red vial de buses: {}", ex.toString());
            RedVialBus actual = red.get();
            estado.set(new Estado(actual == null ? Fase.NO_DISPONIBLE : Fase.LISTA, actual == null ? 0 : actual.nodos(),
                    actual == null ? 0 : actual.aristas(), AVISO,
                    "No se pudo descargar: " + ex.getMessage() + (actual == null ? "" : " Se conserva la red anterior."), Instant.now()));
        }
    }

    private void publicar(List<ViaOsm> vias, String origen) {
        RedVialBus nueva = new RedVialBus(vias);
        if (nueva.nodos() == 0) throw new IllegalStateException("la red vial quedó vacía");
        red.set(nueva);
        matcher.set(new MapMatchingBus(nueva));
        estado.set(new Estado(Fase.LISTA, nueva.nodos(), nueva.aristas(), AVISO,
                origen + " (" + nueva.viasAceptadas() + " vías, " + nueva.viasDescartadas() + " descartadas por las reglas de bus)", Instant.now()));
        log.info("Red vial de buses lista: {} nodos y {} aristas ({}).", nueva.nodos(), nueva.aristas(), origen);
    }

    // ------------------------------------------------------------------ caché propia (JSON comprimido)
    void guardar(List<ViaOsm> vias) {
        try {
            Path dir = archivo.toAbsolutePath().getParent();
            if (dir != null) Files.createDirectories(dir);
            Path temporal = Files.createTempFile(dir, "red-vial-", ".tmp");
            try (Writer w = new BufferedWriter(new OutputStreamWriter(new GZIPOutputStream(Files.newOutputStream(temporal), 1 << 16), StandardCharsets.UTF_8))) {
                w.write("{\"formato\":\"" + FORMATO + "\",\"elements\":[");
                boolean primera = true;
                for (ViaOsm v : vias) {
                    if (!primera) w.write(',');
                    primera = false;
                    w.write("{\"id\":" + v.id() + ",\"tags\":{");
                    boolean pt = true;
                    for (var e : v.tags().entrySet()) {
                        if (!pt) w.write(',');
                        pt = false;
                        w.write(json(e.getKey()) + ":" + json(e.getValue()));
                    }
                    w.write("},\"geometry\":[");
                    for (int i = 0; i < v.coordenadas().size(); i++) {
                        if (i > 0) w.write(',');
                        double[] c = v.coordenadas().get(i);
                        w.write(String.format(Locale.ROOT, "{\"lat\":%.7f,\"lon\":%.7f}", c[0], c[1]));
                    }
                    w.write("]}");
                }
                w.write("]}");
            }
            Files.move(temporal, archivo, StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception ex) {
            log.warn("No se pudo guardar la red vial de buses en {}: {} (seguirá en memoria).", archivo, ex.toString());
        }
    }

    private static String json(String s) {
        StringBuilder b = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"' -> b.append("\\\"");
                case '\\' -> b.append("\\\\");
                case '\n' -> b.append("\\n");
                case '\r' -> b.append("\\r");
                case '\t' -> b.append("\\t");
                default -> { if (c < 0x20) b.append(String.format("\\u%04x", (int) c)); else b.append(c); }
            }
        }
        return b.append('"').toString();
    }

    // ------------------------------------------------------------------ Overpass
    private List<ViaOsm> descargarOverpass() throws Exception {
        List<ViaOsm> vias = new ArrayList<>();
        Set<Long> vistos = new HashSet<>();
        double midLat = (area.minLat() + area.maxLat()) / 2, midLng = (area.minLng() + area.maxLng()) / 2;
        double[][] cajas = {
                {area.minLat(), area.minLng(), midLat, midLng}, {area.minLat(), midLng, midLat, area.maxLng()},
                {midLat, area.minLng(), area.maxLat(), midLng}, {midLat, midLng, area.maxLat(), area.maxLng()}};
        for (double[] c : cajas) {
            descargarCaja(c, vias, vistos);
            Thread.sleep(2500);
        }
        return vias;
    }

    private void descargarCaja(double[] c, List<ViaOsm> destino, Set<Long> vistos) throws Exception {
        String caja = String.format(Locale.ROOT, "(%.5f,%.5f,%.5f,%.5f)", c[0], c[1], c[2], c[3]);
        // Se pide todo lo vehicular y los track con bus=yes/designated; el filtro fino de acceso lo hace ReglasBus.
        String consulta = "[out:json][timeout:180];(way[\"highway\"~\"^(" + TIPOS + ")$\"][\"area\"!=\"yes\"]" + caja
                + ";way[\"highway\"=\"track\"][\"bus\"~\"^(yes|designated)$\"]" + caja + ";);out geom qt;";
        Exception ultimo = null;
        Set<String> caidos = new HashSet<>();
        for (String servidor : servidores) {
            if (caidos.contains(servidor)) continue;
            for (int intento = 1; intento <= 3; intento++) {
                int antes = destino.size();
                try {
                    var request = HttpRequest.newBuilder(URI.create(servidor)).timeout(Duration.ofSeconds(240))
                            .header("User-Agent", "SPEVB-Rutas-USTA/vial").header("Content-Type", "application/x-www-form-urlencoded")
                            .POST(HttpRequest.BodyPublishers.ofString("data=" + URLEncoder.encode(consulta, StandardCharsets.UTF_8))).build();
                    var response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
                    try (var body = response.body()) {
                        if (response.statusCode() != 200) throw new IllegalStateException("HTTP " + response.statusCode());
                        for (ViaOsm v : leerVias(body)) if (vistos.add(v.id())) destino.add(v);
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

    /** Lee elemento por elemento (formato Overpass «out geom» o el de la caché propia): solo uno vive en memoria a la vez. */
    List<ViaOsm> leerVias(InputStream in) {
        List<ViaOsm> r = new ArrayList<>();
        try (JsonParser p = mapper.createParser(in)) {
            while (p.nextToken() != null) {
                if (p.currentToken() == JsonToken.PROPERTY_NAME && "elements".equals(p.currentName())) break;
            }
            if (p.nextToken() != JsonToken.START_ARRAY) throw new IllegalStateException("respuesta sin elementos");
            while (p.nextToken() == JsonToken.START_OBJECT) {
                ViaOsm v = convertir(p.readValueAsTree());
                if (v != null) r.add(v);
            }
        }
        return r;
    }

    static ViaOsm convertir(JsonNode e) {
        String tipo = e.path("type").asText("way");
        JsonNode geom = e.path("geometry");
        if (!"way".equals(tipo) || !geom.isArray() || geom.size() < 2 || geom.size() > 65_000) return null;
        Map<String, String> tags = new LinkedHashMap<>();
        for (String k : ETIQUETAS) {
            JsonNode t = e.path("tags").path(k);
            if (t.isString()) tags.put(k, t.asText());
        }
        if (!tags.containsKey("highway")) return null;
        List<double[]> coords = new ArrayList<>(geom.size());
        for (JsonNode g : geom) {
            double la = g.path("lat").asDouble(Double.NaN), lo = g.path("lon").asDouble(Double.NaN);
            if (Double.isNaN(la) || Double.isNaN(lo)) return null;
            coords.add(new double[]{la, lo});
        }
        return new ViaOsm(e.path("id").asLong(), Map.copyOf(tags), coords);
    }
}
