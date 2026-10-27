package co.edu.usta.spevb.service;

import co.edu.usta.spevb.config.CityArea;
import co.edu.usta.spevb.dto.LugarResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.time.Duration;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Búsqueda de direcciones urbanas para Villavicencio.
 * El catastro da un CENTROIDE DE PREDIO (no una puerta); Esri es opcional.
 * Ninguna consulta a Esri se persiste: forStorage=false.
 */
@Service
public class DireccionesHibridasService {
    private static final String CATASTRO_FUENTE = "Catastro Villavicencio · centro de predio (aproximado)";
    private static final String ESRI_FUENTE = "ArcGIS World Geocoding";
    private static final Pattern NUMERO = Pattern.compile("\\d+[A-Z]?");
    private static final Pattern DIRECCION = Pattern.compile("^(CARRERA|CALLE|AVENIDA|DIAGONAL|TRANSVERSAL)\\s+.*\\d.*", Pattern.CASE_INSENSITIVE);
    private final String catastroUrl;
    private final String arcgisUrl;
    private final String arcgisToken;
    private final CityArea area;
    private final ObjectMapper mapper;
    private final HttpClient http;
    private final Map<String, List<LugarResponse>> catastroCache = new LinkedHashMap<>();

    @Autowired
    public DireccionesHibridasService(
            @Value("${spevb.catastro.url:}") String catastroUrl,
            @Value("${spevb.arcgis.url:https://geocode-api.arcgis.com/arcgis/rest/services/World/GeocodeServer/findAddressCandidates}") String arcgisUrl,
            @Value("${spevb.arcgis.token:}") String arcgisToken,
            ObjectMapper mapper, CityArea area) {
        this(catastroUrl, arcgisUrl, arcgisToken, mapper, area,
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build());
    }

    // Inyección del cliente para pruebas reproducibles sin servicios externos.
    DireccionesHibridasService(String catastroUrl, String arcgisUrl, String arcgisToken,
                               ObjectMapper mapper, CityArea area, HttpClient http) {
        this.catastroUrl = Objects.requireNonNull(catastroUrl).strip();
        this.arcgisUrl = Objects.requireNonNull(arcgisUrl).strip();
        this.arcgisToken = Objects.requireNonNull(arcgisToken).strip();
        this.area = area;
        this.mapper = mapper;
        this.http = http;
        validateUrl(this.catastroUrl);
        if (!this.arcgisToken.isBlank()) validateUrl(this.arcgisUrl);
    }

    public boolean catastroActivo() { return !catastroUrl.isBlank(); }
    public boolean arcgisActivo() { return !arcgisToken.isBlank(); }
    public boolean tieneProveedores() { return catastroActivo() || arcgisActivo(); }

    public synchronized List<LugarResponse> buscarCatastro(String raw) {
        if (!catastroActivo()) return List.of();
        String query = area.normalizeAddress(raw);
        String normal = normalizar(query);
        if (!DIRECCION.matcher(normal).matches()) return List.of();
        List<String> numeros = numeros(normal);
        if (numeros.size() < 2) return List.of();
        String cacheKey = normal;
        if (catastroCache.containsKey(cacheKey)) return catastroCache.get(cacheKey);

        // La primera consulta exige la secuencia numérica; la segunda relaja las letras de placa.
        List<LugarResponse> resultados = new ArrayList<>();
        String primera = patron(numeros);
        consultarCatastro(primera, normal, numeros, resultados);
        if (resultados.isEmpty() && numeros.size() >= 3) {
            consultarCatastro(patron(List.of(numeros.getFirst(), numeros.getLast())), normal, numeros, resultados);
        }
        resultados.sort(Comparator.comparingInt((LugarResponse r) -> similitud(normal, normalizar(r.direccion()))).reversed());
        List<LugarResponse> respuesta = List.copyOf(resultados.stream().limit(6).toList());
        if (catastroCache.size() >= 200) catastroCache.remove(catastroCache.keySet().iterator().next());
        catastroCache.put(cacheKey, respuesta);
        return respuesta;
    }

    private void consultarCatastro(String like, String normal, List<String> numeros, List<LugarResponse> results) {
        // No se incluye texto libre en SQL: sólo cifras y letras previamente filtradas.
        String where = "DIRECCION IS NOT NULL AND UPPER(DIRECCION) LIKE '" + like + "'";
        String base = "f=json&where=" + encode(where) + "&outFields=DIRECCION,OBJECTID"
                + "&outSR=4326&returnCentroid=true&resultRecordCount=100";
        JsonNode root = fetch(catastroUrl, base + "&returnGeometry=false");
        checkError(root);
        // Algunas capas catastrales no ofrecen centroides. En ese caso solicitamos polígonos
        // simplificados y calculamos su centroide localmente (sin presentar esto como puerta).
        boolean hayCandidatosSinCentroide = false;
        for (JsonNode item : root.path("features")) {
            if (candidatoValido(item, normal, numeros) && !item.path("centroid").isObject())
                hayCandidatosSinCentroide = true;
        }
        if (hayCandidatosSinCentroide) {
            root = fetch(catastroUrl, base.replace("resultRecordCount=100", "resultRecordCount=40")
                    + "&returnGeometry=true&maxAllowableOffset=0.00002");
            checkError(root);
        }
        for (JsonNode item : root.path("features")) {
            if (!candidatoValido(item, normal, numeros)) continue;
            String direccion = item.path("attributes").path("DIRECCION").asText("").strip();
            JsonNode point = item.path("centroid");
            double[] coords = point.isObject()
                    ? new double[] { point.path("x").asDouble(Double.NaN), point.path("y").asDouble(Double.NaN) }
                    : centroPoligono(item.path("geometry").path("rings"));
            double lng = coords[0], lat = coords[1];
            if (!area.contains(lat, lng)) continue;
            boolean repetido = results.stream().anyMatch(r -> r.lat() == lat && r.lng() == lng);
            if (!repetido) results.add(new LugarResponse(null, direccion, direccion, lat, lng,
                    CATASTRO_FUENTE, false, "PREDIO_APROXIMADO"));
        }
    }

    private boolean candidatoValido(JsonNode item, String consulta, List<String> numeros) {
        JsonNode attr = item.path("attributes");
        String direccion = attr.path("DIRECCION").asText("").strip();
        if (direccion.isBlank() || direccion.length() > 240 || attr.path("OBJECTID").asLong(-1) < 0)
            return false;
        String normal = normalizar(direccion);
        return tipoCompatible(consulta, normal) && numerosCompatibles(numeros, numeros(normal));
    }

    private static double[] centroPoligono(JsonNode rings) {
        if (!rings.isArray() || rings.isEmpty()) return new double[] { Double.NaN, Double.NaN };
        JsonNode ring = rings.get(0);
        if (!ring.isArray() || ring.size() < 3) return new double[] { Double.NaN, Double.NaN };
        // Restar el primer vértice evita la pérdida de precisión numérica con predios pequeños.
        double originX = ring.get(0).get(0).asDouble();
        double originY = ring.get(0).get(1).asDouble();
        double areaDoble = 0, cx = 0, cy = 0;
        for (int i = 0; i < ring.size(); i++) {
            JsonNode a = ring.get(i), b = ring.get((i + 1) % ring.size());
            double x1 = a.get(0).asDouble() - originX, y1 = a.get(1).asDouble() - originY;
            double x2 = b.get(0).asDouble() - originX, y2 = b.get(1).asDouble() - originY;
            double cruz = x1 * y2 - x2 * y1;
            areaDoble += cruz;
            cx += (x1 + x2) * cruz;
            cy += (y1 + y2) * cruz;
        }
        if (Math.abs(areaDoble) < 1e-12) return new double[] { Double.NaN, Double.NaN };
        return new double[] { originX + cx / (3 * areaDoble), originY + cy / (3 * areaDoble) };
    }

    /** Sugerencias profesionales de ArcGIS para búsquedas mientras se escribe.
     * No devuelve el token al cliente. No se usa Nominatim para autocompletar. */
    public record Sugerencia(String texto, String magicKey) {}

    public List<Sugerencia> sugerirArcgis(String raw) {
        if (!arcgisActivo() || raw == null || raw.strip().length() < 3 || raw.length() > 160)
            return List.of();
        String endpoint = arcgisUrl.replaceFirst("/findAddressCandidates$", "/suggest");
        if (endpoint.equals(arcgisUrl)) throw new IllegalStateException("URL de ArcGIS sin /findAddressCandidates");
        String payload = "f=json&text=" + encode(area.normalizeAddress(raw))
                + "&countryCode=COL&maxSuggestions=8&returnCollections=false"
                + "&location=" + encode((area.minLng() + area.maxLng()) / 2.0 + "," + (area.minLat() + area.maxLat()) / 2.0)
                + "&searchExtent=" + encode(area.minLng() + "," + area.minLat() + "," + area.maxLng() + "," + area.maxLat())
                + "&token=" + encode(arcgisToken);
        JsonNode root = fetch(endpoint, payload);
        checkError(root);
        List<Sugerencia> sugerencias = new ArrayList<>();
        for (JsonNode item : root.path("suggestions")) {
            if (item.path("isCollection").asBoolean(false)) continue;
            String titulo = item.path("text").asText("").strip();
            String magic = item.path("magicKey").asText("").strip();
            if (!titulo.isBlank() && titulo.length() <= 160
                    && magic.matches("[A-Za-z0-9._~+=/\\-:]{5,1500}")
                    && sugerenciaRelevante(raw, titulo)
                    && sugerencias.stream().noneMatch(s -> normalizar(s.texto()).equals(normalizar(titulo))))
                sugerencias.add(new Sugerencia(titulo, magic));
        }
        return List.copyOf(sugerencias.stream().limit(6).toList());
    }

    /** No se almacena la respuesta ni se envía la clave al navegador. */
    public List<LugarResponse> buscarArcgis(String raw) {
        return buscarArcgis(raw, "");
    }

    public List<LugarResponse> buscarArcgis(String raw, String magicKey) {
        if (!arcgisActivo()) return List.of();
        String search = magicKey != null && !magicKey.isBlank() ? raw.strip() : area.searchQuery(raw);
        String payload = "f=json&singleLine=" + encode(search)
                + "&countryCode=COL&sourceCountry=COL&outSR=4326&maxLocations=10&outFields=Addr_type,Match_addr"
                + "&searchExtent=" + encode(area.minLng() + "," + area.minLat() + "," + area.maxLng() + "," + area.maxLat())
                + "&forStorage=false&token=" + encode(arcgisToken);
        if (magicKey != null && magicKey.matches("[A-Za-z0-9._~+=/\\-:]{5,1500}"))
            payload += "&magicKey=" + encode(magicKey);
        JsonNode root = fetch(arcgisUrl, payload);
        checkError(root);
        List<LugarResponse> answer = new ArrayList<>();
        boolean consultaNumerada = numeros(normalizar(area.normalizeAddress(raw))).size() >= 2;
        boolean busquedaEspecifica = busquedaEspecifica(raw);
        for (JsonNode item : root.path("candidates")) {
            double score = item.path("score").asDouble(0);
            if (score < 75) continue;
            JsonNode loc = item.path("location");
            double lng = loc.path("x").asDouble(Double.NaN);
            double lat = loc.path("y").asDouble(Double.NaN);
            if (!area.contains(lat, lng)) continue;
            String direccion = item.path("address").asText("").strip();
            if (direccion.isEmpty()) continue;
            String tipo = item.path("attributes").path("Addr_type").asText("");
            String precision = precisionEsri(tipo);
            // No mostrar el centro de la ciudad como si fuera una universidad o un comercio.
            // Para barrios y sectores, conservar sus resultados sólo si coinciden con el texto.
            if (!consultaNumerada && busquedaEspecifica
                    && (precision.equals("CIUDAD") || !nombreRelevante(raw, direccion))) continue;
            if (consultaNumerada && precision.equals("LUGAR")) continue;
            // Si la consulta es una nomenclatura no podemos disfrazar un resultado de barrio como una puerta.
            if (consultaNumerada && (precision.equals("SECTOR") || precision.equals("CIUDAD") || precision.equals("VIA"))) continue;
            // Un proveedor puede asignar puntuaciones altas a calles distintas. No aceptar
            // una dirección numerada si no conserva vía y secuencia de números consultados.
            if (consultaNumerada) {
                String consultada = normalizar(area.normalizeAddress(raw));
                String recibida = normalizar(direccion);
                if (!tipoCompatible(consultada, recibida)
                        || !numerosCompatibles(numeros(consultada), numeros(recibida))) continue;
            }
            answer.add(new LugarResponse(null, direccion, direccion, lat, lng,
                    ESRI_FUENTE + " · " + tipo + " (" + Math.round(score) + "/100)", false, precision));
        }
        return List.copyOf(answer.stream().limit(8).toList());
    }

    /** Una localidad genérica NO es respuesta a la búsqueda de un comercio o universidad. */
    static boolean busquedaEspecifica(String raw) {
        String normalized = normalizar(raw);
        return !normalized.isBlank() && !normalized.matches("^(VILLAVICENCIO|VILLAVICENCIO META|META|COLOMBIA|VILLAVICENCIO META COLOMBIA)$");
    }

    static boolean nombreRelevante(String raw, String candidate) {
        String candidato = normalizar(candidate);
        // Las palabras genéricas no prueban que sea el establecimiento solicitado.
        boolean teniaEspecifico = false;
        for (String token : normalizar(raw).split(" ")) {
            if (token.length() >= 3 && !Set.of("VILLAVICENCIO", "COLOMBIA", "META", "BARRIO", "SECTOR", "CENTRO", "COMERCIAL", "UNIVERSIDAD", "HOSPITAL", "COLEGIO", "CALLE", "CARRERA", "AVENIDA", "SUR", "NORTE").contains(token)
                    ) {
                teniaEspecifico = true;
                if (candidato.contains(token)) return true;
            }
        }
        // Si el usuario escribe solamente una categoría, mantener coincidencias
        // explícitas por nombre (p. ej. "Hospital").
        return !teniaEspecifico && candidato.contains(normalizar(raw));
    }

    static boolean sugerenciaRelevante(String raw, String title) {
        String query = normalizar(raw);
        String candidato = normalizar(title);
        if (candidato.isBlank()) return false;
        if (candidato.matches("^(VILLAVICENCIO|VILLAVICENCIO META|VILLAVICENCIO META COLOMBIA)$")
                && busquedaEspecifica(raw)) return false;
        if (query.length() < 3) return false;
        // Las direcciones parciales pueden tener abreviaturas distintas. Verificar
        // la placa solamente al obtener coordenadas, no mientras se escribe.
        if (query.matches("^(CARRERA|CALLE|AVENIDA|DIAGONAL|TRANSVERSAL)\\s+.*")) {
            if (!candidato.startsWith(query.split(" ")[0] + " ")) return false;
            List<String> consultados = numeros(query);
            List<String> vistos = numeros(candidato);
            int comprobar = Math.min(2, consultados.size());
            if (vistos.size() < comprobar) return false;
            for (int i = 0; i < comprobar; i++) {
                if (!consultados.get(i).equals(vistos.get(i))) return false;
            }
            return true;
        }
        return nombreRelevante(raw, title) || candidato.contains(query);
    }

    static String precisionEsri(String tipo) {
        return switch (tipo) {
            case "PointAddress", "Subaddress" -> "DIRECCION_PUNTUAL";
            case "StreetAddress", "StreetInt", "StreetBetween", "StreetMidBlock" -> "CALLE_APROXIMADA";
            case "POI" -> "LUGAR";
            case "StreetName" -> "VIA";
            case "Locality", "Neighborhood", "District" -> "SECTOR";
            default -> "CIUDAD";
        };
    }

    private JsonNode fetch(String endpoint, String body) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint)).timeout(Duration.ofSeconds(9))
                    .header("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
                    .header("Accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build();
            HttpResponse<java.io.InputStream> response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
            try (var stream = response.body()) {
                if (response.statusCode() != 200) throw new IllegalStateException("Proveedor HTTP " + response.statusCode());
                byte[] bytes = stream.readNBytes(900_001);
                if (bytes.length > 900_000) throw new IllegalStateException("Respuesta geográfica demasiado grande");
                return mapper.readTree(bytes);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Consulta geográfica interrumpida", e);
        } catch (Exception e) {
            throw new IllegalStateException("El servicio geográfico externo no respondió", e);
        }
    }

    private void checkError(JsonNode root) {
        if (root.has("error")) throw new IllegalStateException("El proveedor devolvió un error");
    }

    private static void validateUrl(String url) {
        if (!url.isBlank() && !url.startsWith("https://")
                && !url.startsWith("http://127.0.0.1:") && !url.startsWith("http://localhost:"))
            throw new IllegalArgumentException("El proveedor debe usar HTTPS o localhost de pruebas");
    }

    private static String encode(String s) { return URLEncoder.encode(s, StandardCharsets.UTF_8); }
    private static String patron(List<String> parts) {
        StringBuilder sb = new StringBuilder("%");
        for (String part : parts) {
            sb.append(part.replaceAll("[^A-Z0-9]", "").replaceAll("([0-9])([A-Z])", "$1%$2")).append('%');
        }
        return sb.toString();
    }
    private static String normalizar(String input) {
        String s = Normalizer.normalize(input, Normalizer.Form.NFD).replaceAll("\\p{M}+", "")
                .toUpperCase(Locale.ROOT);
        s = s.replaceAll("\\b(?:CRA|KRA|CR|KR|K)\\b", "CARRERA")
                .replaceAll("\\b(?:CLL|CL|C)\\b", "CALLE")
                .replaceAll("\\b(?:AV|AVDA)\\b", "AVENIDA")
                .replaceAll("\\b(?:DG|DIAG)\\b", "DIAGONAL")
                .replaceAll("\\b(?:TV|TR|TRANSV)\\b", "TRANSVERSAL");
        // El catastro de Villavicencio publica formas como "K 48 19 15SUR".
        // K/C son abreviaturas reales y SUR puede venir pegado al número de placa.
        s = s.replaceAll("(?<=\\d)(?=NORTE\\b|SUR\\b|ESTE\\b|OESTE\\b)", " ");
        return s.replaceAll("(?<=\\d)\\s+(?=[A-Z]\\b)", "")
                .replaceAll("[^A-Z0-9]+", " ").replaceAll("\\s+", " ").strip();
    }
    private static List<String> numeros(String q) {
        Matcher m = NUMERO.matcher(q);
        List<String> parts = new ArrayList<>();
        while (m.find()) parts.add(m.group());
        return parts;
    }
    private static boolean numerosCompatibles(List<String> a, List<String> b) {
        // En las fichas catastrales aparecen números de manzana, lote y casa al final.
        // Una placa solicitada NO debe coincidir con un número secundario del registro.
        if (a.size() > b.size()) return false;
        for (int i = 0; i < a.size(); i++) if (!a.get(i).equals(b.get(i))) return false;
        return true;
    }
    private static boolean tipoCompatible(String consulta, String candidato) {
        for (String tipo : List.of("CARRERA", "CALLE", "AVENIDA", "DIAGONAL", "TRANSVERSAL")) {
            if (consulta.startsWith(tipo + " ")) {
                if (!candidato.startsWith(tipo + " ")) return false;
                for (String sentido : List.of("NORTE", "SUR", "ESTE", "OESTE")) {
                    if (List.of(consulta.split(" ")).contains(sentido) != List.of(candidato.split(" ")).contains(sentido))
                        return false;
                }
                return true;
            }
        }
        return false;
    }
    private static int similitud(String a, String b) {
        if (a.equals(b)) return 100;
        String[] aTokens = a.split(" ");
        String[] bTokens = b.split(" ");
        int coincidencias = 0;
        for (String t : aTokens) if (List.of(bTokens).contains(t)) coincidencias++;
        return coincidencias * 100 / Math.max(aTokens.length, bTokens.length);
    }
}
