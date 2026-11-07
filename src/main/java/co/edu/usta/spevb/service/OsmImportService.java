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
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Importa de OpenStreetMap (Overpass) los lugares con nombre de Villavicencio —colegios, universidades, parques,
 * comercios, hoteles, barrios y veredas— y las pocas direcciones con número de puerta que existan. Los datos son
 * © colaboradores de OpenStreetMap (ODbL): se muestra la fuente en cada resultado. Prueba varios servidores Overpass.
 */
@Service
public class OsmImportService {
    private static final Logger log = LoggerFactory.getLogger(OsmImportService.class);

    public record Estado(String estado, int lugares, int direcciones, String mensaje, Instant inicio, Instant fin) {
        static Estado inactivo() { return new Estado("INACTIVO", 0, 0, "", null, null); }
    }

    private final List<String> servidores;
    private final boolean alArrancar;
    private final long diasRefresco;
    private final DireccionRepository repo;
    private final CityArea area;
    private final ObjectMapper mapper;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final AtomicReference<Estado> estado = new AtomicReference<>(Estado.inactivo());
    /** Servidores que ya fallaron en esta importación: no se vuelven a probar para no perder minutos. */
    private final java.util.Set<String> caidos = java.util.concurrent.ConcurrentHashMap.newKeySet();

    public OsmImportService(@Value("${spevb.osm.overpass-urls:}") String urls,
                            @Value("${spevb.osm.import-on-start:true}") boolean alArrancar,
                            @Value("${spevb.osm.refresh-days:30}") long diasRefresco,
                            DireccionRepository repo, CityArea area, ObjectMapper mapper) {
        List<String> lista = new ArrayList<>();
        for (String u : urls.split(",")) if (u.strip().startsWith("https://")) lista.add(u.strip());
        this.servidores = List.copyOf(lista);
        this.alArrancar = alArrancar;
        this.diasRefresco = diasRefresco;
        this.repo = repo;
        this.area = area;
        this.mapper = mapper;
    }

    public boolean configurado() { return !servidores.isEmpty(); }
    public Estado estado() { return estado.get(); }

    @EventListener(ApplicationReadyEvent.class)
    void alIniciar() {
        if (!alArrancar || !configurado()) return;
        try {
            if (repo.contarPois() == 0) iniciar();
        } catch (RuntimeException ex) {
            log.warn("No se pudo comprobar los lugares de OpenStreetMap: {}", ex.toString());
        }
    }

    public synchronized boolean iniciar() {
        if (!configurado() || "EN_CURSO".equals(estado.get().estado())) return false;
        caidos.clear();
        estado.set(new Estado("EN_CURSO", 0, 0, "Consultando OpenStreetMap…", Instant.now(), null));
        Thread.ofVirtual().name("importar-osm").start(this::ejecutar);
        return true;
    }

    private String caja() {
        return String.format(Locale.ROOT, "(%.4f,%.4f,%.4f,%.4f)", area.minLat(), area.minLng(), area.maxLat(), area.maxLng());
    }

    private void ejecutar() {
        Instant inicio = estado.get().inicio();
        long idLog = 0;
        try {
            idLog = repo.iniciarImportacion("OSM");
            String b = caja();
            // Consultas pequeñas y separadas: los servidores públicos de Overpass rechazan las grandes con 429/504.
            List<JsonNode> pois = new ArrayList<>();
            for (String q : List.of(
                    "nwr[\"name\"][\"amenity\"]" + b + ";", "nwr[\"name\"][\"shop\"]" + b + ";",
                    "nwr[\"name\"][\"tourism\"]" + b + ";nwr[\"name\"][\"leisure\"]" + b + ";",
                    "nwr[\"name\"][\"office\"]" + b + ";nwr[\"name\"][\"healthcare\"]" + b + ";nwr[\"name\"][\"public_transport\"=\"station\"]" + b + ";")) {
                consultar("[out:json][timeout:90];(" + q + ");out center tags qt;").forEach(pois::add);
                Thread.sleep(2500);
            }
            List<JsonNode> lugares = new ArrayList<>();
            consultar("[out:json][timeout:60];nwr[\"name\"][\"place\"~\"suburb|neighbourhood|quarter|village|hamlet|town\"]" + b + ";out center tags qt;").forEach(lugares::add);
            Thread.sleep(2500);
            List<JsonNode> direcciones = new ArrayList<>();
            consultar("[out:json][timeout:90];nwr[\"addr:housenumber\"]" + b + ";out center tags qt;").forEach(direcciones::add);
            int[] n = guardar(pois, lugares, direcciones);
            repo.cerrarImportacion(idLog, "OK", n[0] + n[1], n[0] + n[1], 0, null);
            estado.set(new Estado("OK", n[0], n[1], "Importación completada", inicio, Instant.now()));
            log.info("OpenStreetMap importado: {} lugares y {} direcciones.", n[0], n[1]);
        } catch (Exception ex) {
            log.error("Falló la importación de OpenStreetMap: {}", ex.toString());
            String mensaje = "Overpass no respondió (" + (ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage()) + ").";
            try {
                if (repo.contarPois() == 0) {
                    int[] n = cargarInstantanea();
                    mensaje += " Se cargó la instantánea incluida (" + n[0] + " lugares, " + n[1] + " direcciones).";
                    if (idLog > 0) repo.cerrarImportacion(idLog, "OK", n[0] + n[1], n[0] + n[1], 0, "Instantánea incluida");
                    estado.set(new Estado("OK", n[0], n[1], mensaje, inicio, Instant.now()));
                    return;
                }
                if (idLog > 0) repo.cerrarImportacion(idLog, "ERROR", 0, 0, 0, ex.toString());
            } catch (Exception ignorada) {
                log.error("Tampoco se pudo cargar la instantánea", ignorada);
            }
            estado.set(new Estado("ERROR", 0, 0, mensaje + " Se conservan los datos anteriores.", inicio, Instant.now()));
        }
    }

    /** Convierte y guarda: devuelve {lugares, direcciones}. */
    private int[] guardar(Iterable<JsonNode> pois, Iterable<JsonNode> lugares, Iterable<JsonNode> direcciones) {
        Instant desde = Instant.now().minusSeconds(1);
        List<DireccionRepository.NuevoPoi> lista = new ArrayList<>();
        for (var grupo : List.of(pois, lugares)) for (JsonNode e : grupo) { var p = convertirLugar(e); if (p != null) lista.add(p); }
        int l = 0;
        for (int i = 0; i < lista.size(); i += 500) l += repo.guardarPois(lista.subList(i, Math.min(lista.size(), i + 500)));
        List<DireccionRepository.Nueva> dirs = new ArrayList<>();
        for (JsonNode e : direcciones) { var d = convertirDireccion(e); if (d != null) dirs.add(d); }
        int n = repo.guardarLote(dirs);
        if (l > 0) repo.borrarPoisOsmAnteriores(desde);   // lo que ya no existe en OSM (o se filtra ahora) sale de la base
        return new int[]{l, n};
    }

    /** Semilla incluida en el proyecto (datos de OpenStreetMap con su atribución) para cuando Overpass no responde. */
    int[] cargarInstantanea() throws Exception {
        try (var in = new java.util.zip.GZIPInputStream(getClass().getResourceAsStream("/osm/villavicencio-osm.json.gz"))) {
            JsonNode raiz = mapper.readTree(in.readAllBytes());
            return guardar(raiz.path("pois"), raiz.path("lugares"), raiz.path("direcciones"));
        }
    }

    /** Prueba cada servidor Overpass hasta que uno responda con resultados válidos. */
    private JsonNode consultar(String consulta) throws Exception {
        Exception ultimo = null;
        for (String servidor : servidores) {
            if (caidos.contains(servidor)) continue;
            for (int intento = 1; intento <= 3; intento++) {
                try {
                    var request = HttpRequest.newBuilder(URI.create(servidor)).timeout(Duration.ofSeconds(120))
                            .header("User-Agent", "SPEVB-Rutas-USTA/3.6").header("Content-Type", "application/x-www-form-urlencoded")
                            .POST(HttpRequest.BodyPublishers.ofString("data=" + URLEncoder.encode(consulta, StandardCharsets.UTF_8))).build();
                    var response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
                    try (var body = response.body()) {
                        if (response.statusCode() != 200) throw new IllegalStateException("HTTP " + response.statusCode());
                        JsonNode elementos = mapper.readTree(body.readNBytes(60_000_000)).path("elements");
                        if (!elementos.isArray()) throw new IllegalStateException("respuesta sin elementos");
                        return elementos;
                    }
                } catch (Exception ex) {
                    ultimo = ex;
                    log.warn("Overpass {} (intento {}): {}", servidor, intento, ex.toString());
                    Thread.sleep(3000L * intento);
                }
            }
            caidos.add(servidor);
        }
        throw ultimo == null ? new IllegalStateException("sin servidor Overpass") : ultimo;
    }

    // ------------------------------------------------------------------ conversión
    private static final Map<String, String> CATEGORIAS = Map.ofEntries(
            Map.entry("school", "Colegio"), Map.entry("kindergarten", "Jardín infantil"), Map.entry("university", "Universidad"),
            Map.entry("college", "Instituto"), Map.entry("hospital", "Hospital"), Map.entry("clinic", "Clínica"),
            Map.entry("doctors", "Consultorio"), Map.entry("pharmacy", "Droguería"), Map.entry("restaurant", "Restaurante"),
            Map.entry("fast_food", "Comida rápida"), Map.entry("cafe", "Café"), Map.entry("bank", "Banco"),
            Map.entry("fuel", "Estación de servicio"), Map.entry("police", "Policía"), Map.entry("place_of_worship", "Iglesia o templo"),
            Map.entry("bus_station", "Terminal de buses"), Map.entry("marketplace", "Plaza de mercado"), Map.entry("townhall", "Alcaldía"),
            Map.entry("library", "Biblioteca"), Map.entry("theatre", "Teatro"), Map.entry("cinema", "Cine"),
            Map.entry("park", "Parque"), Map.entry("pitch", "Cancha"), Map.entry("stadium", "Estadio"), Map.entry("sports_centre", "Centro deportivo"),
            Map.entry("hotel", "Hotel"), Map.entry("motel", "Motel"), Map.entry("hostel", "Hostal"), Map.entry("attraction", "Atractivo"),
            Map.entry("supermarket", "Supermercado"), Map.entry("mall", "Centro comercial"), Map.entry("department_store", "Almacén"),
            Map.entry("convenience", "Tienda"), Map.entry("bakery", "Panadería"), Map.entry("station", "Estación"));
    private static final java.util.Set<String> GENERICOS = java.util.Set.of("colegio", "escuela", "parque", "hotel", "tienda", "restaurante",
            "iglesia", "cancha", "banco", "farmacia", "drogueria", "panaderia", "cafe", "bar", "mirador", "supermercado", "jardin", "instituto",
            "universidad", "hospital", "clinica", "salon", "oficina", "local", "casa", "finca", "sin nombre");
    private static final Map<String, Integer> RELEVANCIA = Map.ofEntries(
            Map.entry("university", 95), Map.entry("hospital", 95), Map.entry("bus_station", 92), Map.entry("mall", 90),
            Map.entry("stadium", 85), Map.entry("townhall", 85), Map.entry("supermarket", 80), Map.entry("clinic", 75),
            Map.entry("department_store", 75), Map.entry("marketplace", 75), Map.entry("college", 70), Map.entry("school", 65),
            Map.entry("park", 60), Map.entry("hotel", 55), Map.entry("bank", 55), Map.entry("fuel", 50), Map.entry("restaurant", 45));

    DireccionRepository.NuevoPoi convertirLugar(JsonNode e) {
        JsonNode t = e.path("tags");
        String nombre = t.path("name").asText("").strip();
        double[] c = centro(e);
        if (nombre.length() < 3 || nombre.length() > 160 || c == null || !area.contains(c[0], c[1])) return null;
        String clave = "", categoria = "";
        for (String k : List.of("amenity", "shop", "tourism", "leisure", "office", "healthcare", "public_transport", "place")) {
            if (t.has(k)) { clave = t.path(k).asText(""); categoria = k.equals("place") ? "Barrio o sector" : CATEGORIAS.getOrDefault(clave, capitalizar(clave.replace('_', ' '))); break; }
        }
        if (clave.isEmpty()) return null;
        // «Colegio», «Parque», «Tienda»… sin nombre propio no sirven para buscar.
        String norm = normalizar(nombre);
        if (GENERICOS.contains(norm) || norm.equals(normalizar(categoria))) return null;
        int relevancia = clave.matches("suburb|neighbourhood|quarter") ? 70 : clave.matches("village|hamlet|town") ? 60 : RELEVANCIA.getOrDefault(clave, 40);
        String calle = t.path("addr:street").asText("").strip(), numero = t.path("addr:housenumber").asText("").strip();
        String direccion = (calle + (numero.isEmpty() ? "" : " #" + numero)).strip();
        String id = e.path("type").asText("n").substring(0, 1) + e.path("id").asText("");
        return new DireccionRepository.NuevoPoi("OSM", id, nombre, norm, categoria,
                direccion.length() > 200 ? direccion.substring(0, 200) : direccion, c[0], c[1], relevancia);
    }

    DireccionRepository.Nueva convertirDireccion(JsonNode e) {
        JsonNode t = e.path("tags");
        String calle = t.path("addr:street").asText("").strip(), numero = t.path("addr:housenumber").asText("").strip();
        double[] c = centro(e);
        if (calle.isEmpty() || numero.isEmpty() || c == null || !area.contains(c[0], c[1])) return null;
        var d = DireccionParser.parsear(calle + " " + numero);
        if (!d.estructurada()) return null;
        String id = e.path("type").asText("n").substring(0, 1) + e.path("id").asText("");
        return new DireccionRepository.Nueva("OSM", id, calle + " #" + numero, d.texto(), d.tipoVia(), d.via(), d.cuadrante(),
                d.cruce(), d.placa(), d.placa2(), "", "", "", c[0], c[1], "DIRECCION_PUNTUAL", 90);
    }

    private static double[] centro(JsonNode e) {
        double lat = e.has("lat") ? e.path("lat").asDouble(Double.NaN) : e.path("center").path("lat").asDouble(Double.NaN);
        double lng = e.has("lon") ? e.path("lon").asDouble(Double.NaN) : e.path("center").path("lon").asDouble(Double.NaN);
        return Double.isNaN(lat) || Double.isNaN(lng) ? null : new double[]{lat, lng};
    }

    static String normalizar(String s) {
        return DireccionParser.sinAcentos(s).toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9 ]", " ").replaceAll("\\s+", " ").strip();
    }

    private static String capitalizar(String s) { return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1); }
}
