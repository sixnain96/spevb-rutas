package co.edu.usta.spevb.config;

/**
 * Content-Security-Policy de la aplicación según el proveedor del mapa. Cada modo permite SOLO lo que su mapa necesita:
 * con Leaflet los hosts de teselas anteriores (OpenStreetMap, Esri, OpenFreeMap); con Google únicamente los de la Maps JavaScript API
 * (política por lista de dominios de https://developers.google.com/maps/documentation/javascript/content-security-policy) y ya no los anteriores.
 * El inicio de sesión con Google (accounts.google.com/gsi) se mantiene en ambos modos. Los textos de referencia están en
 * src/test/resources/csp/*.txt y los usa también el servidor simulado de pruebas (e2e/stub_server.py), de modo que no puedan divergir.
 */
public final class ContentSecurityPolicy {
    private ContentSecurityPolicy() {}

    private static final String GSI_SCRIPT = "https://accounts.google.com/gsi/client";

    public static String para(String proveedor) {
        return MapaConfig.GOOGLE.equals(proveedor) ? google() : leaflet();
    }

    public static String leaflet() {
        return "default-src 'self'; script-src 'self' " + GSI_SCRIPT + "; " +
                "style-src 'self' 'unsafe-inline' https://accounts.google.com/gsi/style; img-src 'self' data: blob: https://tile.openstreetmap.org https://server.arcgisonline.com https://tiles.openfreemap.org; " +
                "connect-src 'self' https://tiles.openfreemap.org https://accounts.google.com/gsi/; worker-src 'self' blob:; child-src 'self' blob:; " +
                "frame-src 'self' https://accounts.google.com/gsi/; object-src 'none'; base-uri 'self'; frame-ancestors 'none'; form-action 'self'";
    }

    /**
     * Google Maps JavaScript API. Lo que Google documenta y por qué:
     * script-src: scripts de googleapis/gstatic (el SDK carga módulos) y blob: y 'unsafe-eval', que figuran en su política oficial
     * (SIN 'unsafe-inline': SEVB no tiene scripts en línea). style-src ya admite 'unsafe-inline' (estilos en línea propios) y añade las fuentes de Google.
     * Se retiran los hosts de teselas de Leaflet/OpenFreeMap/Esri/OSM: en modo Google no se usan.
     */
    public static String google() {
        return "default-src 'self'; " +
                "script-src 'self' " + GSI_SCRIPT + " https://*.googleapis.com https://*.gstatic.com *.google.com https://*.ggpht.com *.googleusercontent.com 'unsafe-eval' blob:; " +
                "style-src 'self' 'unsafe-inline' https://accounts.google.com/gsi/style https://fonts.googleapis.com; " +
                "img-src 'self' data: blob: https://*.googleapis.com https://*.gstatic.com *.google.com *.googleusercontent.com; " +
                "connect-src 'self' https://*.googleapis.com *.google.com https://*.gstatic.com data: blob: https://accounts.google.com/gsi/; " +
                "font-src 'self' https://fonts.gstatic.com; worker-src 'self' blob:; child-src 'self' blob:; " +
                "frame-src 'self' *.google.com https://accounts.google.com/gsi/; object-src 'none'; base-uri 'self'; frame-ancestors 'none'; form-action 'self'";
    }
}
