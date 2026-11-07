package co.edu.usta.spevb.config;

import co.edu.usta.spevb.dto.PlanificarViajeRequest;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Fase D: las rutas registradas por el equipo son utilizables; la interfaz pública ya no ofrece «Solo revisadas» ni advertencias de
 * validación oficial. La información interna (campos, columnas, filtros del backend, herramientas del editor) se conserva.
 */
class RutasPublicasTest {
    private static String leer(String ruta) throws Exception {
        try (var in = RutasPublicasTest.class.getResourceAsStream(ruta)) {
            assertNotNull(in, "falta " + ruta);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
    private static String estatico(String ruta) throws Exception { return leer("/static" + ruta); }

    @Test void thePublicScreenNoLongerOffersReviewedOnlyOrOfficialValidationWarnings() throws Exception {
        String html = estatico("/index.html");
        for (String texto : new String[]{"onlyValidatedTrips", "Solo revisadas", "validationBadge", "demoWarning", "Pendientes de validar",
                "Con revisión registrada", "validación oficial"})
            assertFalse(html.contains(texto), "index.html todavía contiene «" + texto + "»");
        assertTrue(html.contains(">Buscar rutas</button>"), "el botón queda simplemente como «Buscar rutas»");
        assertTrue(html.contains("<option value=\"all\">Todas las rutas</option>") && html.contains("value=\"favorites\""),
                "se conservan «Todas las rutas» y «Mis rutas favoritas»");
    }

    @Test void noScriptReadsRemovedElementsOrShowsValidationWarnings() throws Exception {
        for (String js : new String[]{"app", "planner", "espacio", "extras", "offline", "cuenta", "sheet", "ui"}) {
            String codigo = estatico("/js/" + js + ".js");
            assertFalse(codigo.contains("onlyValidatedTrips"), js + ".js lee la casilla que ya no existe");
            assertFalse(codigo.contains("validationBadge") || codigo.contains("demoWarning"), js + ".js usa elementos retirados");
            assertFalse(codigo.contains("Recorrido por validar") || codigo.contains("está por validar") || codigo.contains("Pendiente de validar"),
                    js + ".js muestra una advertencia de validación");
        }
    }

    @Test void requestsSendSoloValidadasFalseAndCompatibilityIsKept() throws Exception {
        String planner = estatico("/js/planner.js");
        assertTrue(planner.contains("const soloValidadas = false;"), "la planificación pública usa todas las rutas activas");
        assertTrue(planner.contains("soloValidadas: () => false"), "Planner.soloValidadas() sigue existiendo y devuelve false de forma explícita");
        assertTrue(planner.contains("'&soloValidadas=false'"), "rutas cercanas al origen: parámetro explícito en false");
        assertTrue(planner.contains("Spevb.Recientes?.guardar(start, finish, data, soloValidadas)"), "recientes conserva el formato");
        assertTrue(planner.contains("detail: { a: start, b: finish, soloValidadas, codigos }"), "el evento spevb-viaje conserva el campo");
    }

    @Test void savedTripsMadeWithTheOldFilterStillMatch() throws Exception {
        assertFalse(estatico("/js/offline.js").contains("r.soloValidadas ==="), "recientes ya no distingue viajes por el filtro antiguo");
        assertFalse(estatico("/js/espacio.js").contains("x.soloValidadas ==="), "historial y guardados tampoco");
        assertTrue(estatico("/js/offline.js").contains("soloValidadas: !!soloValidadas"), "el campo se sigue guardando");
    }

    @Test void internalReviewDataAndAdminToolsRemain() throws Exception {
        assertTrue(estatico("/editor.html").contains("validationNote"), "el editor conserva fuente, nota y revisión");
        assertTrue(estatico("/js/editor.js").contains("validada"));
        assertTrue(Arrays.stream(PlanificarViajeRequest.class.getRecordComponents()).anyMatch((c) -> c.getName().equals("soloValidadas")),
                "el DTO conserva el campo soloValidadas");
    }
}
