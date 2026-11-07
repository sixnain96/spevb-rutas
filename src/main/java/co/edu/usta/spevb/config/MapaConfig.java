package co.edu.usta.spevb.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

/**
 * Configuración del MAPA del navegador (qué proveedor dibuja el mapa y, con Google, su clave pública y su Map ID).
 * <p>
 * Es independiente de {@code spevb.google-maps.api-key} (GOOGLE_MAPS_API_KEY), que es la clave del SERVIDOR para Places y nunca sale de Java.
 * La clave del navegador (GOOGLE_MAPS_BROWSER_API_KEY) viaja en las solicitudes del SDK de Google: su protección real son las restricciones
 * que se le configuran en Google Cloud (sitios web y APIs), no ocultarla.
 */
@Component
public class MapaConfig {
    private static final Logger log = LoggerFactory.getLogger(MapaConfig.class);
    public static final String LEAFLET = "leaflet", GOOGLE = "google";
    /** Formato típico de una clave de API de Google (AIza…); se acepta cualquier clave razonable, pero nunca caracteres que rompan una URL. */
    private static final Pattern CLAVE = Pattern.compile("[A-Za-z0-9_\\-]{20,80}");
    private static final Pattern MAP_ID = Pattern.compile("[A-Za-z0-9_]{1,64}");
    private static final Pattern VERSION = Pattern.compile("weekly|quarterly|beta|alpha|\\d{1,2}(\\.\\d{1,3}){0,2}");

    private final String proveedor;
    private final String claveNavegador;
    private final String mapId;
    private final String version;

    public MapaConfig(@Value("${spevb.mapa.proveedor:leaflet}") String proveedor,
                      @Value("${spevb.mapa.google.clave-navegador:}") String claveNavegador,
                      @Value("${spevb.mapa.google.map-id:}") String mapId,
                      @Value("${spevb.mapa.google.version:quarterly}") String version,
                      @Value("${spevb.google-maps.api-key:}") String claveServidor) {
        String p = proveedor == null ? "" : proveedor.trim().toLowerCase();
        if (p.isEmpty()) p = LEAFLET;
        if (!p.equals(LEAFLET) && !p.equals(GOOGLE))
            throw new IllegalArgumentException("MAP_PROVIDER debe ser «leaflet» o «google» (valor recibido: «" + proveedor + "»).");
        this.proveedor = p;
        String k = claveNavegador == null ? "" : claveNavegador.trim();
        if (!k.isEmpty() && !CLAVE.matcher(k).matches()) {
            log.warn("GOOGLE_MAPS_BROWSER_API_KEY tiene un formato no válido: se ignora (el mapa de Google quedará sin configurar).");
            k = "";
        }
        if (!k.isEmpty() && k.equals(claveServidor == null ? "" : claveServidor.trim()))
            log.warn("GOOGLE_MAPS_BROWSER_API_KEY es igual a GOOGLE_MAPS_API_KEY: la clave del servidor quedaría visible en el navegador. Usa claves distintas.");
        this.claveNavegador = k;
        String id = mapId == null ? "" : mapId.trim();
        if (!id.isEmpty() && !MAP_ID.matcher(id).matches()) {
            log.warn("GOOGLE_MAPS_MAP_ID tiene un formato no válido: se ignora.");
            id = "";
        }
        this.mapId = id;
        String v = version == null || version.isBlank() ? "quarterly" : version.trim().toLowerCase();
        if (!VERSION.matcher(v).matches()) {
            log.warn("GOOGLE_MAPS_JS_VERSION no válida («{}»): se usa «quarterly».", v);
            v = "quarterly";
        }
        this.version = v;
        if (GOOGLE.equals(this.proveedor) && (this.claveNavegador.isEmpty() || this.mapId.isEmpty()))
            log.warn("MAP_PROVIDER=google pero falta {}: el mapa mostrará un error claro hasta que se configure (no se cambia a Leaflet en silencio).",
                    this.claveNavegador.isEmpty() ? "GOOGLE_MAPS_BROWSER_API_KEY" : "GOOGLE_MAPS_MAP_ID");
    }

    /** Solo para pruebas y para el filtro de cabeceras. */
    public static MapaConfig leaflet() { return new MapaConfig(LEAFLET, "", "", "quarterly", ""); }

    public String proveedor() { return proveedor; }
    public boolean esGoogle() { return GOOGLE.equals(proveedor); }
    public boolean googleConfigurado() { return !claveNavegador.isEmpty() && !mapId.isEmpty(); }

    /** Parte de Google de la respuesta pública. Lista EXPLÍCITA de campos: nunca se serializa el entorno ni otras propiedades. */
    public record Google(boolean configurado, String claveNavegador, String mapId, String version, String idioma, String region) {}
    /** Respuesta pública de GET /api/config/mapa. */
    public record Publica(String proveedor, Google google) {}

    public Publica publica() {
        // Con Leaflet no se envía nada de Google (ni siquiera la clave): no se necesita y no debe circular.
        if (!esGoogle()) return new Publica(proveedor, null);
        return new Publica(proveedor, new Google(googleConfigurado(), claveNavegador.isEmpty() ? null : claveNavegador,
                mapId.isEmpty() ? null : mapId, version, "es", "CO"));
    }
}
