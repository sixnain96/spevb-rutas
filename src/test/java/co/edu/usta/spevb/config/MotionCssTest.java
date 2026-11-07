package co.edu.usta.spevb.config;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/** Reglas del sistema de movimiento (css/motion.css): tokens, nada de `transition: all`, animaciones que no obligan a recalcular el diseño. */
class MotionCssTest {
    private static final List<String> HOJAS = List.of("motion", "styles", "home", "bus3d", "panel-ocultable");
    /** Propiedades que un @keyframes puede animar: compuestas por la GPU (transform, opacity…) o de un trazo SVG. */
    private static final Set<String> PERMITIDAS = Set.of("opacity", "transform", "translate", "scale", "rotate", "stroke-dashoffset");

    private static String leer(String ruta) throws Exception {
        try (var in = MotionCssTest.class.getResourceAsStream("/static" + ruta)) {
            assertNotNull(in, "falta " + ruta);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static String css(String nombre) throws Exception { return leer("/css/" + nombre + ".css"); }

    @Test void motionCssDefinesTheTokens() throws Exception {
        String motion = css("motion");
        for (String t : List.of("--motion-instant", "--motion-fast", "--motion-normal", "--motion-emphasized", "--motion-map", "--motion-stagger",
                "--ease-standard", "--ease-enter", "--ease-exit", "--ease-map"))
            assertTrue(motion.contains(t + ":"), "falta el token " + t);
        Matcher m = Pattern.compile("--motion-stagger:\\s*(\\d+)ms").matcher(motion);
        assertTrue(m.find());
        int escalon = Integer.parseInt(m.group(1));
        assertTrue(escalon >= 20 && escalon <= 35, "el escalonado entre tarjetas debe ser de 20–35 ms y es " + escalon);
    }

    @Test void noTransitionAll() throws Exception {
        for (String hoja : HOJAS) {
            String c = css(hoja).replaceAll("(?s)/\\*.*?\\*/", "");
            assertFalse(Pattern.compile("transition(-property)?\\s*:\\s*[^;}]*\\ball\\b").matcher(c).find(), hoja + ".css usa transition: all");
        }
    }

    @Test void keyframesOnlyAnimateCheapProperties() throws Exception {
        for (String hoja : HOJAS) {
            String c = css(hoja).replaceAll("(?s)/\\*.*?\\*/", "");
            Matcher k = Pattern.compile("@keyframes\\s+([\\w-]+)\\s*\\{").matcher(c);
            while (k.find()) {
                int fin = cuerpoHasta(c, k.end());
                String cuerpo = c.substring(k.end(), fin);
                Matcher p = Pattern.compile("([a-z-]+)\\s*:").matcher(cuerpo);
                List<String> usadas = new ArrayList<>();
                while (p.find()) usadas.add(p.group(1));
                for (String prop : usadas)
                    assertTrue(PERMITIDAS.contains(prop), hoja + ".css: @keyframes " + k.group(1) + " anima «" + prop + "» (usa transform u opacity)");
            }
        }
    }

    @Test void noPermanentWillChange() throws Exception {
        for (String hoja : HOJAS) {
            String c = css(hoja).replaceAll("(?s)/\\*.*?\\*/", "");
            assertFalse(Pattern.compile("will-change\\s*:").matcher(c).find(), hoja + ".css deja will-change permanente: lo debe poner el JS mientras algo se mueve");
        }
    }

    @Test void reducedMotionIsHandledByTheSystemAndByTheDataAttribute() throws Exception {
        String motion = css("motion");
        assertTrue(motion.contains("@media (prefers-reduced-motion: reduce)"));
        assertTrue(motion.contains("html[data-motion=\"reduce\"]"));
        // en ambos casos las duraciones pasan a 1 ms
        assertEquals(2, countOf(motion, "--motion-fast: 1ms"));
        assertTrue(leer("/js/motion.js").contains("data-motion") || leer("/js/motion.js").contains("dataset.motion"));
    }

    @Test void motionFilesAreLoadedAndCachedOffline() throws Exception {
        String html = leer("/index.html"), sw = leer("/sw.js");
        assertTrue(html.indexOf("/css/motion.css") > 0 && html.indexOf("/css/motion.css") < html.indexOf("/css/styles.css"));
        // common.js reemplaza window.Spevb: motion.js tiene que cargarse después o Spevb.Motion se pierde.
        assertTrue(html.indexOf("/js/motion.js") > html.indexOf("/js/common.js"), "motion.js debe cargarse después de common.js");
        assertTrue(sw.contains("\"/css/motion.css\"") && sw.contains("\"/js/motion.js\""));
    }

    @Test void theGlobalFocusRingKeepsAtLeast3To1ContrastAgainstWhite() throws Exception {
        var m = Pattern.compile(":focus-visible \\{[^}]*outline:\\s*\\d+px solid #([0-9a-fA-F]{6})").matcher(css("styles"));
        assertTrue(m.find(), "falta la regla global :focus-visible");
        int rgb = Integer.parseInt(m.group(1), 16);
        double[] c = {(rgb >> 16 & 255) / 255.0, (rgb >> 8 & 255) / 255.0, (rgb & 255) / 255.0};
        for (int i = 0; i < 3; i++) c[i] = c[i] <= 0.03928 ? c[i] / 12.92 : Math.pow((c[i] + 0.055) / 1.055, 2.4);
        double luminancia = 0.2126 * c[0] + 0.7152 * c[1] + 0.0722 * c[2];
        double contraste = 1.05 / (luminancia + 0.05);
        assertTrue(contraste >= 3.0, "el anillo de foco tiene " + contraste + ":1 contra el blanco (mínimo 3:1)");
        assertTrue(leer("/index.html").contains("role=\"main\""), "la página necesita un landmark principal");
    }

    private static int cuerpoHasta(String c, int desde) {
        int profundidad = 1;
        for (int i = desde; i < c.length(); i++) {
            if (c.charAt(i) == '{') profundidad++;
            else if (c.charAt(i) == '}' && --profundidad == 0) return i;
        }
        return c.length();
    }

    private static int countOf(String texto, String parte) {
        int n = 0, i = 0;
        while ((i = texto.indexOf(parte, i)) >= 0) { n++; i += parte.length(); }
        return n;
    }
}
