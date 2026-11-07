package co.edu.usta.spevb.config;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/** Fase E: la interfaz explica por qué no se recomienda un segundo bus (variantes «baja y camina» y «una sola ruta»). */
class TransbordosPresentacionTest {
    private static String estatico(String ruta) throws Exception {
        try (var in = TransbordosPresentacionTest.class.getResourceAsStream("/static" + ruta)) {
            assertNotNull(in, "falta " + ruta);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test void theCardsLabelAndExplainTheWalkInsteadOfASecondBus() throws Exception {
        String planner = estatico("/js/planner.js");
        assertTrue(planner.contains("'BAJAR_Y_CAMINAR'") && planner.contains("'SOLO_SEGUNDA_RUTA'"));
        assertTrue(planner.contains("'Baja y camina · '") && planner.contains("'Una sola ruta · '"));
        assertTrue(planner.contains("if (caminaEnVez || soloUna) direction.textContent = option.advertencia;"),
                "el texto de la tarjeta es el que redacta el servidor, con las cifras reales");
    }

    @Test void theServiceWorkerVersionWasBumped() throws Exception {
        assertTrue(estatico("/sw.js").matches("(?s).*const VERSION = \"spevb-v4\\.(9\\.[5-9]|[1-9][0-9]\\.[0-9]+)\";.*"), "la versión del service worker debe haber subido");
    }
}
