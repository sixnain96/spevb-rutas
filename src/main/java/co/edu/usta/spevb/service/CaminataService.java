package co.edu.usta.spevb.service;

import co.edu.usta.spevb.config.CityArea;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * Trayecto a pie por calles y senderos peatonales (OSRM, perfil «foot» sobre OpenStreetMap).
 *
 * Optimizaciones: caché con vencimiento, fusión de consultas idénticas simultáneas, caché negativa
 * breve y corte de circuito cuando el proveedor falla de forma repetida. La geometría que devuelve
 * el proveedor se entrega intacta: no se simplifica ni se altera ninguna coordenada.
 * Si no hay respuesta devuelve vacío y la interfaz conserva la línea recta de respaldo.
 */
@Service
public class CaminataService {
    private static final Logger log = LoggerFactory.getLogger(CaminataService.class);
    private static final int CACHE_MAX = 2000;
    private static final long TTL_OK_MS = Duration.ofHours(12).toMillis();
    private static final long TTL_FAIL_MS = Duration.ofSeconds(45).toMillis();
    private static final int BREAKER_FAILURES = 4;
    private static final long BREAKER_OPEN_MS = Duration.ofSeconds(30).toMillis();

    public record Tramo(List<double[]> coordenadas, double metros, double segundos) {}

    private record Entrada(Optional<Tramo> valor, long vence) {}

    private final String baseUrl;
    private final String userAgent;
    private final ObjectMapper mapper;
    private final CityArea cityArea;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private final Semaphore concurrent = new Semaphore(6);
    private final Map<String, CompletableFuture<Optional<Tramo>>> enCurso = new ConcurrentHashMap<>();
    private final Map<String, Entrada> cache = new LinkedHashMap<>(128, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, Entrada> eldest) { return size() > CACHE_MAX; }
    };
    private record EntradaMatriz(Optional<double[][]> valor, long vence) {}
    private final Map<String, EntradaMatriz> cacheMatriz = new LinkedHashMap<>(64, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, EntradaMatriz> eldest) { return size() > 300; }
    };
    /** Red propia de calles (preferida sobre el servicio externo); se inyecta si está disponible. */
    private volatile RedPeatonalService red;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void usarRed(RedPeatonalService red) { this.red = red; }

    private final Object ritmo = new Object();
    private int fallosSeguidos;
    private long circuitoAbiertoHasta;

    public CaminataService(@Value("${spevb.walking.url:}") String baseUrl,
                           @Value("${spevb.geocoder.user-agent:SPEVB-Rutas/3.0}") String userAgent,
                           ObjectMapper mapper, CityArea cityArea) {
        String url = baseUrl.strip();
        while (url.endsWith("/")) url = url.substring(0, url.length() - 1);
        if (!url.isBlank() && !url.startsWith("https://") && !url.startsWith("http://localhost:")
                && !url.startsWith("http://127.0.0.1:")) {
            throw new IllegalArgumentException("Configura el servicio de caminata con HTTPS o una instancia local.");
        }
        this.baseUrl = url;
        this.userAgent = userAgent.strip();
        this.mapper = mapper;
        this.cityArea = cityArea;
    }

    /** Descarta los trayectos y matrices guardados (cambió la red de calles). */
    public void invalidarCache() {
        synchronized (cache) { cache.clear(); }
        synchronized (cacheMatriz) { cacheMatriz.clear(); }
    }

    public boolean activo() { return !baseUrl.isBlank() || (red != null && red.lista()); }

    public Optional<Tramo> ruta(double latA, double lngA, double latB, double lngB) {
        if (!activo()) return Optional.empty();
        cityArea.requireInside(latA, lngA);
        cityArea.requireInside(latB, lngB);
        // 1.º red propia (sin cuotas ni red externa); 2.º servidor OSRM; 3.º la interfaz dibuja la línea de respaldo.
        if (red != null && red.lista()) {
            var propia = red.ruta(latA, lngA, latB, lngB);
            if (propia.isPresent()) return propia;
        }
        if (baseUrl.isBlank()) return Optional.empty();
        String key = String.format(Locale.ROOT, "%.5f,%.5f;%.5f,%.5f", lngA, latA, lngB, latB);
        long now = System.currentTimeMillis();

        Optional<Tramo> cached = leerCache(key, now);
        if (cached != null) return cached;
        if (circuitoAbierto(now)) return Optional.empty();

        // Consultas idénticas simultáneas comparten una sola llamada al proveedor.
        var propio = new CompletableFuture<Optional<Tramo>>();
        var existente = enCurso.putIfAbsent(key, propio);
        if (existente != null) {
            try {
                return existente.get(8, TimeUnit.SECONDS);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return Optional.empty();
            } catch (Exception ex) {
                return Optional.empty();
            }
        }
        Optional<Tramo> resultado = Optional.empty();
        try {
            resultado = consultar(key);
            return resultado;
        } finally {
            guardar(key, resultado);
            enCurso.remove(key);
            propio.complete(resultado);
        }
    }

    /**
     * Distancias a pie (metros) desde cada origen a cada destino en UNA sola consulta (servicio «table»
     * de OSRM). Cada celda suma la distancia por la red peatonal más lo que separa cada punto de la
     * calle a la que se ajusta. {@code NaN} indica que no hay camino. Puntos en [lng, lat].
     */
    public Optional<double[][]> distancias(List<double[]> origenes, List<double[]> destinos) {
        return distancias(origenes, destinos, 3);
    }

    /** Igual que {@link #distancias(List, List)} con un límite de espera propio (la etapa fina espera menos). */
    public Optional<double[][]> distancias(List<double[]> origenes, List<double[]> destinos, int segundosMaximos) {
        if (!activo() || origenes.isEmpty() || destinos.isEmpty() || origenes.size() + destinos.size() > 100) return Optional.empty();
        if (red != null && red.lista()) {
            try {
                for (double[] p : origenes) cityArea.requireInside(p[1], p[0]);
                for (double[] p : destinos) cityArea.requireInside(p[1], p[0]);
            } catch (IllegalArgumentException ex) {
                return Optional.empty();
            }
            var propia = red.distancias(origenes, destinos);
            if (propia.isPresent()) return propia;
        }
        if (!baseUrl.contains("/route/v1/")) return Optional.empty();
        StringBuilder coords = new StringBuilder();
        for (double[] p : origenes) append(coords, p);
        for (double[] p : destinos) append(coords, p);
        coords.setLength(coords.length() - 1);
        String src = java.util.stream.IntStream.range(0, origenes.size()).mapToObj(Integer::toString)
                .collect(java.util.stream.Collectors.joining(";"));
        String dst = java.util.stream.IntStream.range(origenes.size(), origenes.size() + destinos.size())
                .mapToObj(Integer::toString).collect(java.util.stream.Collectors.joining(";"));
        String key = coords + "|" + src + "|" + dst;
        long now = System.currentTimeMillis();
        synchronized (cacheMatriz) {
            EntradaMatriz e = cacheMatriz.get(key);
            if (e != null && e.vence() >= now) return e.valor();
            if (e != null) cacheMatriz.remove(key);
        }
        if (circuitoAbierto(now) || !concurrent.tryAcquire()) return Optional.empty();
        Optional<double[][]> resultado = Optional.empty();
        try {
            for (double[] p : origenes) { cityArea.requireInside(p[1], p[0]); }
            for (double[] p : destinos) { cityArea.requireInside(p[1], p[0]); }
            URI uri = URI.create(baseUrl.replace("/route/v1/", "/table/v1/") + "/" + coords
                    + "?sources=" + src + "&destinations=" + dst + "&annotations=distance");
            var request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(segundosMaximos))
                    .header("User-Agent", userAgent).header("Accept", "application/json").GET().build();
            pausar();
            var response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            try (var body = response.body()) {
                if (response.statusCode() == 429) {
                    limitado();
                } else if (response.statusCode() != 200) {
                    log.warn("Matriz peatonal respondió HTTP {}", response.statusCode());
                    registrar(false);
                } else {
                    byte[] bytes = body.readNBytes(2_000_001);
                    var root = mapper.readTree(bytes);
                    registrar(true);
                    var rows = root.path("distances");
                    if ("Ok".equals(root.path("code").asText()) && rows.isArray() && rows.size() == origenes.size()) {
                        double[][] m = new double[origenes.size()][destinos.size()];
                        for (int i = 0; i < m.length; i++) {
                            double snapO = root.path("sources").path(i).path("distance").asDouble(0);
                            for (int j = 0; j < m[i].length; j++) {
                                var cell = rows.path(i).path(j);
                                double snapD = root.path("destinations").path(j).path("distance").asDouble(0);
                                m[i][j] = cell.isNumber() ? cell.asDouble() + snapO + snapD : Double.NaN;
                            }
                        }
                        resultado = Optional.of(m);
                    }
                }
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        } catch (IllegalArgumentException ex) {
            // punto fuera del área: sin matriz
        } catch (Exception ex) {
            log.warn("No se pudo calcular la matriz peatonal: {}", ex.toString());
            registrar(false);
        } finally {
            concurrent.release();
        }
        Optional<double[][]> guardar = resultado;
        synchronized (cacheMatriz) {
            cacheMatriz.put(key, new EntradaMatriz(guardar,
                    System.currentTimeMillis() + (guardar.isPresent() ? TTL_OK_MS : TTL_FAIL_MS)));
        }
        return resultado;
    }

    private static void append(StringBuilder sb, double[] p) {
        sb.append(String.format(Locale.ROOT, "%.5f,%.5f;", p[0], p[1]));
    }

    private Optional<Tramo> leerCache(String key, long now) {
        synchronized (cache) {
            Entrada e = cache.get(key);
            if (e == null) return null;
            if (e.vence() < now) { cache.remove(key); return null; }
            return e.valor();
        }
    }

    private void guardar(String key, Optional<Tramo> valor) {
        long ttl = valor.isPresent() ? TTL_OK_MS : TTL_FAIL_MS;
        synchronized (cache) { cache.put(key, new Entrada(valor, System.currentTimeMillis() + ttl)); }
    }

    /** Cubeta de fichas: hasta 3 llamadas por segundo (ráfagas pequeñas sí; más, esperan) para no recibir 429. */
    private final java.util.ArrayDeque<Long> ritmoMarcas = new java.util.ArrayDeque<>();

    private void pausar() throws InterruptedException {
        synchronized (ritmo) {
            while (true) {
                long ahora = System.currentTimeMillis();
                while (!ritmoMarcas.isEmpty() && ritmoMarcas.peekFirst() <= ahora - 1000) ritmoMarcas.pollFirst();
                if (ritmoMarcas.size() < 3) { ritmoMarcas.addLast(ahora); return; }
                Thread.sleep(Math.max(5, ritmoMarcas.peekFirst() + 1000 - ahora));
            }
        }
    }

    private synchronized void limitado() {
        circuitoAbiertoHasta = System.currentTimeMillis() + 20_000;
        log.warn("Servicio de caminata limitó el uso (HTTP 429): se usa línea recta durante 20 s.");
    }

    private synchronized boolean circuitoAbierto(long now) { return now < circuitoAbiertoHasta; }

    private synchronized void registrar(boolean ok) {
        if (ok) { fallosSeguidos = 0; return; }
        if (++fallosSeguidos >= BREAKER_FAILURES) {
            circuitoAbiertoHasta = System.currentTimeMillis() + BREAKER_OPEN_MS;
            fallosSeguidos = 0;
            log.warn("Servicio de caminata con fallos repetidos: se usa línea recta durante {} s.", BREAKER_OPEN_MS / 1000);
        }
    }

    private Optional<Tramo> consultar(String key) {
        if (!concurrent.tryAcquire()) return Optional.empty();
        try {
            URI uri = URI.create(baseUrl + "/" + key + "?overview=full&geometries=geojson&steps=false");
            var request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(5))
                    .header("User-Agent", userAgent).header("Accept", "application/json").GET().build();
            pausar();
            var response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            try (var body = response.body()) {
                int status = response.statusCode();
                if (status == 429) {
                    limitado();
                    return Optional.empty();
                }
                if (status != 200 && status != 400) {
                    // 400 de OSRM suele ser «sin ruta» (NoRoute): no es un fallo del proveedor.
                    log.warn("Servicio de caminata respondió HTTP {}", status);
                    registrar(false);
                    return Optional.empty();
                }
                byte[] bytes = body.readNBytes(1_000_001);
                if (bytes.length > 1_000_000) return Optional.empty();
                var root = mapper.readTree(bytes);
                registrar(true);
                var route = root.path("routes").path(0);
                var coords = route.path("geometry").path("coordinates");
                if (!"Ok".equals(root.path("code").asText()) || !coords.isArray()
                        || coords.size() < 2 || coords.size() > 5000) return Optional.empty();
                List<double[]> points = new ArrayList<>(coords.size());
                for (var c : coords) {
                    double lng = c.path(0).asDouble(Double.NaN), lat = c.path(1).asDouble(Double.NaN);
                    if (!cityArea.contains(lat, lng)) return Optional.empty();
                    points.add(new double[]{lng, lat});
                }
                return Optional.of(new Tramo(List.copyOf(points), route.path("distance").asDouble(0),
                        route.path("duration").asDouble(0)));
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        } catch (Exception ex) {
            log.warn("No se pudo calcular el trayecto a pie: {}", ex.toString());
            registrar(false);
            return Optional.empty();
        } finally {
            concurrent.release();
        }
    }
}
