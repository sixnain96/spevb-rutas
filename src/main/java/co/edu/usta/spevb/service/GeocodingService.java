package co.edu.usta.spevb.service;

import co.edu.usta.spevb.config.CityArea;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;

@Service
public class GeocodingService {
    private final String searchUrl;
    private final String reverseUrl;
    private final String userAgent;
    private final ObjectMapper mapper;
    private final CityArea cityArea;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final Map<String, List<Resultado>> searchCache = new LinkedHashMap<>();
    private final Map<String, Resultado> reverseCache = new LinkedHashMap<>();
    private long lastCall;

    public GeocodingService(@Value("${spevb.geocoder.url:}") String searchUrl,
                            @Value("${spevb.geocoder.reverse-url:}") String reverseUrl,
                            @Value("${spevb.geocoder.user-agent:SPEVB-Rutas/3.0}") String userAgent,
                            ObjectMapper mapper,
                            CityArea cityArea) {
        this.searchUrl = searchUrl.strip();
        this.reverseUrl = reverseUrl.strip();
        this.userAgent = userAgent.strip();
        this.mapper = mapper;
        this.cityArea = cityArea;
        validateEndpoint(this.searchUrl);
        validateEndpoint(this.reverseUrl);
        if ((!this.searchUrl.isBlank() || !this.reverseUrl.isBlank()) && this.userAgent.length() < 8) {
            throw new IllegalArgumentException("GEOCODER_USER_AGENT debe identificar la aplicación.");
        }
    }

    private void validateEndpoint(String url) {
        if (!url.isBlank() && !url.startsWith("https://") && !url.startsWith("http://localhost:")
                && !url.startsWith("http://127.0.0.1:")) {
            throw new IllegalArgumentException("Configura un proveedor geográfico HTTPS o una instancia local.");
        }
    }

    public boolean configurado() { return !searchUrl.isBlank(); }
    public boolean reversoConfigurado() { return !reverseUrl.isBlank(); }

    public synchronized List<Resultado> buscar(String rawQuery) {
        if (!configurado()) return List.of();
        String cacheKey = cityArea.normalizeAddress(rawQuery).toLowerCase(Locale.ROOT);
        if (searchCache.containsKey(cacheKey)) return searchCache.get(cacheKey);

        LinkedHashMap<String, Resultado> merged = new LinkedHashMap<>();
        for (String query : cityArea.searchQueries(rawQuery)) {
            if (!merged.isEmpty()) break;
            rateLimit();
            try {
                String separator = searchUrl.contains("?") ? "&" : "?";
                URI uri = URI.create(searchUrl + separator
                        + "format=jsonv2&addressdetails=1&limit=10&countrycodes=co&bounded=1&viewbox="
                        + URLEncoder.encode(cityArea.viewbox(), StandardCharsets.UTF_8)
                        + "&q=" + URLEncoder.encode(query, StandardCharsets.UTF_8));
                var response = client.send(request(uri), HttpResponse.BodyHandlers.ofInputStream());
                try (var body = response.body()) {
                    requireOk(response.statusCode());
                    byte[] bytes = body.readNBytes(400_001);
                    if (bytes.length > 400_000) throw badGateway("Respuesta geográfica demasiado grande.");
                    var array = mapper.readTree(bytes);
                    if (!array.isArray()) throw badGateway("Respuesta geográfica inválida.");
                    for (var item : array) {
                        double lat = parse(item.path("lat").asText());
                        double lng = parse(item.path("lon").asText());
                        if (!cityArea.contains(lat, lng)) continue;
                        String display = item.path("display_name").asText("").strip();
                        if (display.isBlank()) continue;
                        String external = externalId(item.path("osm_type").asText(""), item.path("osm_id").asText(""));
                        String key = external != null ? external : String.format(Locale.ROOT, "%.6f,%.6f", lat, lng);
                        merged.putIfAbsent(key, new Resultado(display, display, lat, lng, "OpenStreetMap / Nominatim", external));
                        if (merged.size() >= 8) break;
                    }
                }
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Búsqueda interrumpida.");
            } catch (ResponseStatusException ex) {
                throw ex;
            } catch (Exception ex) {
                throw badGateway("No se pudo consultar el buscador de direcciones.");
            }
        }
        List<Resultado> answer = List.copyOf(merged.values());
        putBounded(searchCache, cacheKey, answer, 250);
        return answer;
    }

    public synchronized Resultado reverso(double lat, double lng) {
        cityArea.requireInside(lat, lng);
        if (!reversoConfigurado()) return null;
        String key = String.format(Locale.ROOT, "%.5f,%.5f", lat, lng);
        if (reverseCache.containsKey(key)) return reverseCache.get(key);
        rateLimit();
        try {
            String separator = reverseUrl.contains("?") ? "&" : "?";
            URI uri = URI.create(reverseUrl + separator + "format=jsonv2&addressdetails=1&zoom=18&layer=address&lat="
                    + URLEncoder.encode(Double.toString(lat), StandardCharsets.UTF_8)
                    + "&lon=" + URLEncoder.encode(Double.toString(lng), StandardCharsets.UTF_8));
            var response = client.send(request(uri), HttpResponse.BodyHandlers.ofInputStream());
            try (var body = response.body()) {
                requireOk(response.statusCode());
                byte[] bytes = body.readNBytes(200_001);
                if (bytes.length > 200_000) throw badGateway("Respuesta geográfica demasiado grande.");
                var item = mapper.readTree(bytes);
                String display = item.path("display_name").asText("").strip();
                if (display.isBlank()) return null;
                double resultLat = parse(item.path("lat").asText(Double.toString(lat)));
                double resultLng = parse(item.path("lon").asText(Double.toString(lng)));
                if (!cityArea.contains(resultLat, resultLng)) return null;
                Resultado result = new Resultado(display, display, resultLat, resultLng,
                        "OpenStreetMap / Nominatim",
                        externalId(item.path("osm_type").asText(""), item.path("osm_id").asText("")));
                putBounded(reverseCache, key, result, 250);
                return result;
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Búsqueda interrumpida.");
        } catch (ResponseStatusException ex) {
            throw ex;
        } catch (Exception ex) {
            throw badGateway("No se pudo obtener la dirección del punto.");
        }
    }

    private HttpRequest request(URI uri) {
        return HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(8))
                .header("User-Agent", userAgent)
                .header("Accept", "application/json")
                .header("Accept-Language", "es-CO,es;q=0.9")
                .GET().build();
    }

    private void rateLimit() {
        long now = System.nanoTime();
        long minimum = 1_100_000_000L;
        if (lastCall != 0 && now - lastCall < minimum) {
            long remaining = minimum - (now - lastCall);
            try {
                Thread.sleep(remaining / 1_000_000L, (int) (remaining % 1_000_000L));
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Búsqueda interrumpida.");
            }
        }
        lastCall = System.nanoTime();
    }

    private void requireOk(int status) {
        if (status == 429) throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                "El proveedor de direcciones pidió reducir la frecuencia de consultas.");
        if (status != 200) throw badGateway("El buscador de direcciones no está disponible.");
    }

    private double parse(String value) { return Double.parseDouble(value); }

    private String externalId(String type, String id) {
        if (type.isBlank() || id.isBlank()) return null;
        return "osm:" + type + ":" + id;
    }

    private ResponseStatusException badGateway(String message) {
        return new ResponseStatusException(HttpStatus.BAD_GATEWAY, message);
    }

    private <K,V> void putBounded(Map<K,V> map, K key, V value, int max) {
        if (map.size() >= max) map.remove(map.keySet().iterator().next());
        map.put(key, value);
    }

    public record Resultado(String nombre, String direccion, double lat, double lng, String fuente, String externalId) {}
}
