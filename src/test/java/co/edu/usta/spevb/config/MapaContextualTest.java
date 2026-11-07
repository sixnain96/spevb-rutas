package co.edu.usta.spevb.config;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/** Fase C: paraderos y puentes contextuales, GPS eficiente y bucle del bus en reposo. Comprobaciones sobre los archivos del frontend. */
class MapaContextualTest {
    private static String leer(String ruta) throws Exception {
        try (var in = MapaContextualTest.class.getResourceAsStream("/static" + ruta)) {
            assertNotNull(in, "falta " + ruta);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test void theInitialScreenHasNoPermanentStopsCheckbox() throws Exception {
        String html = leer("/index.html");
        assertFalse(html.contains("id=\"showStops\""), "la casilla pública «Paraderos» debe haber desaparecido");
        assertTrue(html.contains("data-ajuste=\"paraderosMapa\""), "la capa general queda como ajuste explícito del menú");
        assertTrue(html.contains("id=\"toggleRouteStops\""), "los paraderos de una ruta se piden en sus detalles");
        for (String js : new String[]{"app", "paraderos-publicos", "planner", "extras", "puentes-publicos"})
            assertFalse(leer("/js/" + js + ".js").contains("showStops"), js + ".js todavía usa showStops");
    }

    @Test void publicStopsAreContextualCancellableAndCached() throws Exception {
        String js = leer("/js/paraderos-publicos.js");
        assertTrue(js.contains("paraderosMapa"), "solo funciona si el ajuste está activo");
        assertTrue(js.contains("AbortController") && js.contains("controller.abort()"), "cancela la consulta anterior");
        assertTrue(js.contains("CELDA") && js.contains("TTL_MS"), "caché breve por cuadrícula");
        assertFalse(js.contains("clearLayers();\n            datos.forEach"), "actualiza por diferencias, no recrea todo");
        var m = java.util.regex.Pattern.compile("LIMITE = (\\d+)").matcher(js);
        assertTrue(m.find());
        assertTrue(Integer.parseInt(m.group(1)) <= 300, "ya no se piden 1.500 marcadores");
    }

    @Test void bridgesAreOnlyHighlightedWhenAWalkCrossesThem() throws Exception {
        String js = leer("/js/puentes-publicos.js");
        assertFalse(js.contains("setInterval"), "ya no hay consulta periódica de todos los puentes");
        assertTrue(js.contains("alRecorrer") && js.contains("usados") && js.contains("COBERTURA"));
        assertFalse(js.matches("(?s).*\\n    cargar\\(\\);\\s*\\n.*"), "no se piden ni se dibujan al abrir el mapa");
        String planner = leer("/js/planner.js");
        assertTrue(planner.contains("Spevb.Puentes.alRecorrer") && planner.contains("Cruza el puente peatonal"));
        assertFalse(planner.contains("punto('puente"), "un puente no es un punto de subir ni de bajar");
    }

    @Test void itineraryPointsHaveTheirOwnSymbols() throws Exception {
        String planner = leer("/js/planner.js"), css = leer("/css/home.css");
        for (String tipo : new String[]{"'sube'", "'baja'", "'cambio'"}) assertTrue(planner.contains(tipo));
        for (String clase : new String[]{".pt-sube", ".pt-baja", ".pt-cambio"}) assertTrue(css.contains(clase));
        assertFalse(planner.contains("fillColor: '#ffd17c'"), "los círculos amarillos genéricos fueron sustituidos");
    }

    @Test void walkingLinesFlowInTheWalkingDirectionAndStayStillWhenMotionIsReduced() throws Exception {
        String home = leer("/css/home.css"), motion = leer("/css/motion.css");
        assertTrue(motion.contains("@keyframes walk-reveal") && motion.contains(".walk-line:not(.walk-fallback)"));
        // el periodo del patrón (dashArray '1 10' en planner.js) mide 11 px y el desplazamiento negativo avanza hacia el destino
        assertTrue(leer("/js/planner.js").contains("dashArray: '1 10'"));
        assertTrue(motion.contains("@keyframes walk-flow { from { stroke-dashoffset: 0; } to { stroke-dashoffset: -11; } }"),
                "los puntos de la caminata avanzan en el sentido del trayecto, con ciclo exacto de 11 px");
        assertTrue(motion.contains("walk-flow 1.5s linear infinite"));
        assertTrue(home.contains(".walk-line.walk-fallback { animation: none; }"), "la línea recta de respaldo no se anima");
        assertTrue(motion.contains(".walk-line { animation: none !important; }"), "con movimiento reducido no hay animación");
        assertTrue(home.contains(".gps-dot.adquiriendo::after"), "el pulso del GPS solo existe mientras se adquiere la lectura");
    }

    @Test void gpsReleasesTheNetworkWatchUsesCachedReadingOnlyAsHintAndPausesWhenHidden() throws Exception {
        String gps = leer("/js/gps.js");
        assertTrue(gps.contains("soltarRed"), "la escucha por red se suelta cuando ya no hace falta");
        assertTrue(gps.contains("desdeCache") && gps.contains("LECTURA_CACHE_MAXIMA_EDAD_MS"));
        assertTrue(gps.contains("visibilitychange") && gps.contains("removeEventListener"));
        assertTrue(gps.contains("S.Gps = { obtener, seguir, permiso, calidad, dentroDeCiudad, distancia, errorGps, problema, plataforma, Filtro, AYUDA_PERMISO }"),
                "la API pública de Spevb.Gps no cambia");
    }

    @Test void theBusLoopStopsWhenPausedAndTheCameraUsesAThreshold() throws Exception {
        String bus = leer("/js/bus.js");
        assertTrue(bus.contains("function kick()") && bus.contains("st.raf = st.playing || st.wrap ? requestAnimationFrame(frame) : 0"));
        assertTrue(bus.contains("Math.abs(c.x - mid.x) + Math.abs(c.y - mid.y) >= 2"), "umbral de 2 px para recentrar el mapa");
        assertTrue(bus.contains("window.Spevb = Object.assign(window.Spevb || {}, { createBusPlayer, busModel: { build3DBus } })"));
    }
}
