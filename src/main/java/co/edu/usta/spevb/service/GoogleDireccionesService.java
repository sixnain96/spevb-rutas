package co.edu.usta.spevb.service;

import co.edu.usta.spevb.config.MapaConfig;

import co.edu.usta.spevb.config.CityArea;
import co.edu.usta.spevb.dto.LugarResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Direcciones con Google (Places API «New»): autocompletado y detalle del lugar elegido. OPCIONAL y APAGADO por defecto: solo se activa si
 * {@code GOOGLE_MAPS_API_KEY} tiene valor. La clave se envía en una cabecera desde Java (nunca va al navegador ni a la URL, así no queda en registros).
 * <p>
 * Respeta las condiciones de Google en lo que depende de este código: no se guarda nada de lo que devuelve (ni texto ni coordenadas: lo elegido
 * pasa al planificador y, si la persona lo guarda en «Mis lugares», se guarda solo su punto) y cada resultado se marca con la fuente «Google Maps».
 * Pendiente de confirmar por el equipo: Google exige mostrar sus resultados sobre un mapa de Google; ver {@code docs/GOOGLE-MAPS.md}.
 */
@Service
public class GoogleDireccionesService {
    public static final String FUENTE = "Google Maps";
    /** Prefijo del identificador que viaja por el navegador (campo {@code magicKey}) para distinguirlo del de ArcGIS. */
    public static final String PREFIJO = "g:";
    private static final java.util.regex.Pattern ID_LUGAR = java.util.regex.Pattern.compile("[A-Za-z0-9_-]{10,300}");
    private static final int MAX_BYTES = 200_000;

    public record Sugerencia(String texto, String lugarId) {}

    private final String clave;
    private final String base;
    private final ObjectMapper mapper;
    private final CityArea area;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    private final boolean sobreMapaDeGoogle;

    /** Sin información del proveedor de mapa (pruebas): solo cuenta la clave. */
    public GoogleDireccionesService(String clave, String base, ObjectMapper mapper, CityArea area) { this(clave, base, mapper, area, null); }

    @Autowired
    public GoogleDireccionesService(@Value("${spevb.google-maps.api-key:}") String clave,
                                    @Value("${spevb.google-maps.url:https://places.googleapis.com}") String base,
                                    ObjectMapper mapper, CityArea area, MapaConfig mapa) {
        this.clave = clave == null ? "" : clave.strip();
        this.base = base == null ? "" : base.strip().replaceAll("/+$", "");
        this.mapper = mapper;
        this.area = area;
        // Los términos de Google Maps Platform piden que los resultados de Places se muestren sobre un mapa de Google. Con otro proveedor de mapa
        // (Leaflet) las sugerencias de Google se APAGAN en lugar de dibujarse sobre un mapa ajeno. Ver docs/GOOGLE-MAPS.md.
        this.sobreMapaDeGoogle = mapa == null || mapa.esGoogle();
        if (!this.clave.isBlank() && !this.sobreMapaDeGoogle)
            org.slf4j.LoggerFactory.getLogger(GoogleDireccionesService.class).info("Direcciones de Google (Places) apagadas: MAP_PROVIDER no es «google» y sus resultados deben mostrarse sobre un mapa de Google.");
        if (!this.clave.isBlank() && !this.base.startsWith("https://") && !this.base.startsWith("http://localhost:") && !this.base.startsWith("http://127.0.0.1:"))
            throw new IllegalArgumentException("GOOGLE_MAPS_URL debe ser HTTPS.");
    }

    public boolean activo() { return !clave.isBlank() && sobreMapaDeGoogle; }

    /** Sugerencias al escribir (≥ 3 letras), limitadas a Colombia y a la caja de Villavicencio. Vacío si está apagado. */
    public List<Sugerencia> sugerir(String texto) {
        if (!activo() || texto == null || texto.strip().length() < 3 || texto.length() > 160) return List.of();
        Map<String, Object> cuerpo = Map.of(
                "input", area.normalizeAddress(texto),
                "languageCode", "es",
                "includedRegionCodes", List.of("co"),
                "locationRestriction", Map.of("rectangle", Map.of(
                        "low", Map.of("latitude", area.minLat(), "longitude", area.minLng()),
                        "high", Map.of("latitude", area.maxLat(), "longitude", area.maxLng()))));
        JsonNode raiz = llamar(HttpRequest.newBuilder(URI.create(base + "/v1/places:autocomplete"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(cuerpo))));
        List<Sugerencia> salida = new ArrayList<>();
        for (JsonNode s : raiz.path("suggestions")) {
            JsonNode p = s.path("placePrediction");
            String id = p.path("placeId").asText("").strip(), t = p.path("text").path("text").asText("").strip();
            if (!t.isBlank() && t.length() <= 200 && ID_LUGAR.matcher(id).matches() && salida.stream().noneMatch(x -> x.texto().equals(t)))
                salida.add(new Sugerencia(t, id));
            if (salida.size() >= 6) break;
        }
        return List.copyOf(salida);
    }

    /** Búsqueda por texto (Enter o botón «Buscar»): varios lugares de Villavicencio a la vez. Se usa con moderación porque es más cara que el autocompletar. */
    public List<LugarResponse> buscarTexto(String texto) {
        if (!activo() || texto == null || texto.strip().length() < 3 || texto.length() > 160) return List.of();
        Map<String, Object> cuerpo = Map.of(
                "textQuery", area.normalizeAddress(texto),
                "languageCode", "es",
                "regionCode", "co",
                "pageSize", 5,
                "locationRestriction", Map.of("rectangle", Map.of(
                        "low", Map.of("latitude", area.minLat(), "longitude", area.minLng()),
                        "high", Map.of("latitude", area.maxLat(), "longitude", area.maxLng()))));
        JsonNode raiz = llamar(HttpRequest.newBuilder(URI.create(base + "/v1/places:searchText"))
                .header("Content-Type", "application/json")
                .header("X-Goog-FieldMask", "places.id,places.displayName,places.formattedAddress,places.location,places.types")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(cuerpo))));
        List<LugarResponse> salida = new ArrayList<>();
        for (JsonNode r : raiz.path("places")) {
            LugarResponse l = aLugar(r);
            if (l != null) salida.add(l);
            if (salida.size() >= 5) break;
        }
        return List.copyOf(salida);
    }

    /** Ubica el lugar que la persona eligió en la lista. Solo devuelve puntos dentro de Villavicencio. */
    public List<LugarResponse> buscar(String lugarId) {
        if (!activo() || lugarId == null || !ID_LUGAR.matcher(lugarId).matches()) return List.of();
        JsonNode r = llamar(HttpRequest.newBuilder(URI.create(base + "/v1/places/" + URLEncoder.encode(lugarId, StandardCharsets.UTF_8) + "?languageCode=es"))
                .header("X-Goog-FieldMask", "id,displayName,formattedAddress,location,types").GET());
        LugarResponse l = aLugar(r);
        return l == null ? List.of() : List.of(l);
    }

    private LugarResponse aLugar(JsonNode r) {
        double lat = r.path("location").path("latitude").asDouble(Double.NaN), lng = r.path("location").path("longitude").asDouble(Double.NaN);
        if (!area.contains(lat, lng)) return null;
        String direccion = r.path("formattedAddress").asText("").strip();
        String nombre = r.path("displayName").path("text").asText("").strip();
        if (direccion.isBlank() && nombre.isBlank()) return null;
        if (nombre.isBlank()) nombre = direccion;
        if (direccion.isBlank()) direccion = nombre;
        return new LugarResponse(null, nombre, direccion, lat, lng, FUENTE, false, precision(r.path("types")));
    }

    /** Tipo de punto según los «types» de Google (no certifica exactitud, igual que en las demás fuentes). */
    static String precision(JsonNode tipos) {
        Set<String> t = new java.util.HashSet<>();
        for (JsonNode n : tipos) t.add(n.asText());
        if (t.contains("street_address") || t.contains("premise") || t.contains("subpremise")) return "DIRECCION_PUNTUAL";
        if (t.contains("route")) return "VIA";
        if (t.contains("neighborhood") || t.contains("sublocality") || t.contains("sublocality_level_1")) return "SECTOR";
        if (t.contains("locality") || t.contains("administrative_area_level_2")) return "CIUDAD";
        return "LUGAR";
    }

    private JsonNode llamar(HttpRequest.Builder pedido) {
        try {
            var respuesta = client.send(pedido.timeout(Duration.ofSeconds(6)).header("X-Goog-Api-Key", clave).build(), HttpResponse.BodyHandlers.ofInputStream());
            try (var cuerpo = respuesta.body()) {
                if (respuesta.statusCode() != 200) throw new IllegalStateException("Google respondió " + respuesta.statusCode());
                byte[] bytes = cuerpo.readNBytes(MAX_BYTES + 1);
                if (bytes.length > MAX_BYTES) throw new IllegalStateException("Respuesta de Google demasiado grande.");
                return mapper.readTree(bytes);
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Búsqueda interrumpida.");
        } catch (IllegalStateException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalStateException("No se pudo consultar Google.");   // el mensaje no lleva la clave ni la URL
        }
    }
}
