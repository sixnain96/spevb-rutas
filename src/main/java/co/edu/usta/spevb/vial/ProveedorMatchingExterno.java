package co.edu.usta.spevb.vial;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.LongSupplier;

/**
 * Cliente OPCIONAL de un servidor OSRM ({@code /match/v1/driving/...}) para ajustar recorridos. Está apagado por defecto:
 * con la URL vacía ({@code BUS_MATCHING_URL}) {@link #activo()} es false y nunca se hace una petición.
 *
 * <p>Garantías: jamás envía el EDITOR_TOKEN ni cabeceras de autorización (solo Accept y User-Agent); la respuesta tiene un
 * tope de tamaño; hay un interruptor de circuito simple (se abre tras N fallos seguidos y reintenta pasado un tiempo);
 * los recorridos grandes se dividen en fragmentos con solapamiento. Cualquier problema devuelve {@link Optional#empty()}:
 * quien llama debe conservar la geometría original (nunca sustituirla por una línea recta).
 */
public class ProveedorMatchingExterno {
    private static final Logger log = LoggerFactory.getLogger(ProveedorMatchingExterno.class);

    public enum Circuito { CERRADO, ABIERTO }

    /**
     * @param url              URL base del servidor OSRM (o hasta {@code /match/v1/driving}); vacía = desactivado
     * @param timeout          tiempo máximo de cada petición
     * @param maxRespuestaBytes tope del cuerpo de la respuesta
     * @param fallosParaAbrir  fallos seguidos que abren el circuito
     * @param reintentoTras    tiempo con el circuito abierto antes de probar de nuevo
     * @param maxPuntos        puntos por petición (OSRM acepta 100 por defecto)
     * @param solape           puntos compartidos entre fragmentos consecutivos
     * @param radioMetros      radio de búsqueda por punto enviado al servidor
     */
    public record Config(String url, Duration timeout, int maxRespuestaBytes, int fallosParaAbrir, Duration reintentoTras,
                         int maxPuntos, int solape, int radioMetros) {
        public static Config para(String url) {
            return new Config(url, Duration.ofSeconds(5), 2_000_000, 3, Duration.ofSeconds(60), 100, 10, 30);
        }
    }

    private final Config cfg;
    private final String base;
    private final LongSupplier reloj;
    private final HttpClient http;
    private final JsonMapper json = JsonMapper.builder().build();
    private int fallos;
    private long abiertoHastaMs;
    private boolean abierto;

    public ProveedorMatchingExterno(String url) { this(Config.para(url)); }

    public ProveedorMatchingExterno(Config cfg) { this(cfg, System::currentTimeMillis); }

    ProveedorMatchingExterno(Config cfg, LongSupplier relojMs) {
        this.cfg = cfg;
        this.reloj = relojMs;
        this.base = normalizar(cfg.url());
        this.http = HttpClient.newBuilder().connectTimeout(cfg.timeout()).followRedirects(HttpClient.Redirect.NEVER).build();
    }

    private static String normalizar(String url) {
        if (url == null || url.isBlank()) return null;
        String u = url.strip();
        if (!u.startsWith("http://") && !u.startsWith("https://")) return null;
        while (u.endsWith("/")) u = u.substring(0, u.length() - 1);
        if (u.contains("/match/v1/")) return u;
        return u + "/match/v1/driving";
    }

    public boolean activo() { return base != null; }

    public synchronized Circuito circuito() { return abierto ? Circuito.ABIERTO : Circuito.CERRADO; }

    /**
     * Ajusta el recorrido y devuelve la polilínea [lat,lng] que sigue las vías del servidor, o vacío si está desactivado,
     * el circuito está abierto, hubo un fallo, el servidor no pudo emparejar todo el recorrido de forma continua, o los
     * fragmentos no empalman.
     */
    public Optional<List<double[]>> ajustar(List<double[]> puntosLatLng) {
        if (!activo() || puntosLatLng == null || puntosLatLng.size() < 2) return Optional.empty();
        if (!permitido()) return Optional.empty();
        try {
            List<double[]> acumulado = new ArrayList<>();
            int paso = Math.max(1, cfg.maxPuntos() - cfg.solape());
            int inicio = 0;
            while (true) {
                int fin = Math.min(puntosLatLng.size(), inicio + cfg.maxPuntos());
                List<double[]> geom = pedir(puntosLatLng.subList(inicio, fin));
                if (geom == null) { registrarExito(); return Optional.empty(); } // el servidor no pudo emparejar: no es un fallo del servicio
                if (inicio == 0) acumulado.addAll(geom);
                else if (!empalmar(acumulado, geom, puntosLatLng.get(inicio + Math.min(cfg.solape(), fin - inicio - 1) / 2))) {
                    registrarExito();
                    return Optional.empty();
                }
                if (fin >= puntosLatLng.size()) break;
                inicio += paso;
            }
            registrarExito();
            return acumulado.size() < 2 ? Optional.empty() : Optional.of(acumulado);
        } catch (Exception ex) {
            registrarFallo(ex);
            return Optional.empty();
        }
    }

    // ------------------------------------------------------------------ petición
    /** @return geometría [lat,lng], o null si el servidor respondió sin un emparejamiento continuo. */
    private List<double[]> pedir(List<double[]> pts) throws IOException, InterruptedException {
        StringBuilder coords = new StringBuilder(), radios = new StringBuilder();
        for (int i = 0; i < pts.size(); i++) {
            if (i > 0) { coords.append(';'); radios.append(';'); }
            coords.append(String.format(Locale.ROOT, "%.6f,%.6f", pts.get(i)[1], pts.get(i)[0]));
            radios.append(cfg.radioMetros());
        }
        URI uri = URI.create(base + "/" + coords + "?overview=full&geometries=geojson&steps=false&annotations=false&tidy=false&radiuses=" + radios);
        // Solo cabeceras inocuas: nunca Authorization, Cookie ni X-Editor-Token.
        HttpRequest req = HttpRequest.newBuilder(uri).timeout(cfg.timeout()).GET()
                .header("Accept", "application/json").header("User-Agent", "SPEVB-Rutas-USTA/vial").build();
        HttpResponse<InputStream> resp = http.send(req, HttpResponse.BodyHandlers.ofInputStream());
        byte[] cuerpo;
        try (InputStream in = resp.body()) { cuerpo = leerAcotado(in); }
        int estado = resp.statusCode();
        if (estado == 400 || estado == 404) { // NoMatch / NoSegment: respuesta válida sin emparejamiento
            return null;
        }
        if (estado != 200) throw new IOException("HTTP " + estado);
        JsonNode raiz = json.readTree(cuerpo);
        if (!"Ok".equals(raiz.path("code").asString(""))) return null;
        JsonNode matchings = raiz.path("matchings");
        if (!matchings.isArray() || matchings.size() != 1) return null; // varios = recorrido partido: no se inventa el enlace
        JsonNode c = matchings.get(0).path("geometry").path("coordinates");
        if (!c.isArray() || c.size() < 2) throw new IOException("respuesta sin geometría");
        List<double[]> r = new ArrayList<>(c.size());
        for (JsonNode p : c) {
            if (!p.isArray() || p.size() < 2) throw new IOException("coordenada inválida");
            r.add(new double[]{p.get(1).asDouble(), p.get(0).asDouble()});
        }
        return r;
    }

    private byte[] leerAcotado(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int total = 0, k;
        while ((k = in.read(buf)) > 0) {
            total += k;
            if (total > cfg.maxRespuestaBytes()) throw new IOException("respuesta demasiado grande (> " + cfg.maxRespuestaBytes() + " bytes)");
            out.write(buf, 0, k);
        }
        return out.toByteArray();
    }

    /** Recorta la cola acumulada y la cabeza del fragmento nuevo en el punto más cercano a {@code medio} y los une. */
    private boolean empalmar(List<double[]> acum, List<double[]> nuevo, double[] medio) {
        int ia = masCercano(acum, medio, Math.max(0, acum.size() - 4 * cfg.maxPuntos()));
        int in = masCercano(nuevo, medio, 0);
        double gap = RedVialBus.distanciaM(acum.get(ia)[0], acum.get(ia)[1], nuevo.get(in)[0], nuevo.get(in)[1]);
        if (gap > 30) return false; // los fragmentos no coinciden: mejor no devolver nada que inventar un empalme
        while (acum.size() > ia + 1) acum.remove(acum.size() - 1);
        for (int i = in + 1; i < nuevo.size(); i++) acum.add(nuevo.get(i));
        return true;
    }

    private static int masCercano(List<double[]> l, double[] p, int desde) {
        int mejor = desde;
        double dm = Double.MAX_VALUE;
        for (int i = desde; i < l.size(); i++) {
            double d = RedVialBus.distanciaM(l.get(i)[0], l.get(i)[1], p[0], p[1]);
            if (d < dm) { dm = d; mejor = i; }
        }
        return mejor;
    }

    // ------------------------------------------------------------------ interruptor de circuito
    private synchronized boolean permitido() {
        if (!abierto) return true;
        if (reloj.getAsLong() >= abiertoHastaMs) {
            abiertoHastaMs = reloj.getAsLong() + cfg.reintentoTras().toMillis(); // medio abierto: una sola prueba a la vez
            return true;
        }
        return false;
    }

    private synchronized void registrarExito() { fallos = 0; abierto = false; }

    private synchronized void registrarFallo(Exception ex) {
        fallos++;
        if (fallos >= cfg.fallosParaAbrir()) {
            abierto = true;
            abiertoHastaMs = reloj.getAsLong() + cfg.reintentoTras().toMillis();
        }
        log.warn("Matching externo falló ({} seguidos): {}", fallos, ex.toString());
    }
}
