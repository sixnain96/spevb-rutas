package co.edu.usta.spevb.config;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 4.14: la búsqueda de direcciones y lugares del pasajero es EXCLUSIVAMENTE Google. Estas pruebas fijan, sobre los archivos reales, que ni el código del flujo
 * del pasajero ni la configuración por defecto reintroducen ArcGIS, Nominatim o las importaciones automáticas de direcciones, y que el ruteo propio sigue activo.
 */
class SoloGoogleConfiguracionTest {
    private static String leer(String ruta) throws Exception {
        return Files.readString(Path.of(ruta), StandardCharsets.UTF_8);
    }

    @Test void elFlujoDelPasajeroNoReferenciaOtrosProveedoresDeBusqueda() throws Exception {
        for (String ruta : List.of("src/main/java/co/edu/usta/spevb/service/LugarService.java", "src/main/java/co/edu/usta/spevb/controller/LugarController.java")) {
            String t = leer(ruta).toLowerCase();
            for (String prohibido : List.of("arcgis", "nominatim", "hibrido", "geocodingservice", "geocoder", "buscarcatastro", "sugerirarcgis"))
                assertFalse(t.contains(prohibido), ruta + " no debe usar «" + prohibido + "» en la búsqueda del pasajero");
        }
    }

    @Test void nominatimEstaApagadoPorDefectoYLasImportacionesAutomaticasDeDireccionesTambien() throws Exception {
        String p = leer("src/main/resources/application.properties");
        assertTrue(p.contains("spevb.geocoder.url=${GEOCODER_URL:}"), "Nominatim apagado por defecto");
        assertTrue(p.contains("spevb.geocoder.reverse-url=${GEOCODER_REVERSE_URL:}"));
        assertTrue(p.contains("spevb.direcciones.import-on-start=${DIRECCIONES_IMPORT_AL_ARRANCAR:false}"), "no se importa el catastro solo");
        assertTrue(p.contains("spevb.osm.import-on-start=${OSM_IMPORT_AL_ARRANCAR:false}"), "no se importan lugares de OSM solos");
        assertTrue(p.contains("spevb.arcgis.token=${ARCGIS_API_KEY:}"), "ArcGIS sin credencial por defecto");
    }

    @Test void losArchivosDeDespliegueNoReactivanLoQueSeApago() throws Exception {
        for (String ruta : List.of("compose.app.yml", "compose.prod.yml", ".env.example")) {
            String t = leer(ruta);
            assertFalse(t.contains("GEOCODER_URL: ${GEOCODER_URL:-https://nominatim"), ruta + " no activa Nominatim por defecto");
            assertFalse(t.contains("DIRECCIONES_IMPORT_AL_ARRANCAR: ${DIRECCIONES_IMPORT_AL_ARRANCAR:-true}"), ruta);
            assertFalse(t.contains("OSM_IMPORT_AL_ARRANCAR: ${OSM_IMPORT_AL_ARRANCAR:-true}"), ruta);
        }
        assertFalse(leer("start.ps1").contains("nominatim.openstreetmap.org"), "start.ps1 no define Nominatim");
        assertTrue(leer(".env.example").contains("GOOGLE_MAPS_API_KEY="), "la clave de Google (solo servidor) está documentada");
    }

    @Test void elRuteoPropioSigueActivo() throws Exception {
        String p = leer("src/main/resources/application.properties");
        assertTrue(p.contains("spevb.red.enabled=${RED_PEATONAL_HABILITADA:true}"), "red peatonal propia activa");
        assertTrue(p.contains("spevb.vial.enabled=${RED_VIAL_HABILITADA:true}"), "red vial para buses activa");
        assertTrue(p.contains("spevb.osm.overpass-urls="), "Overpass (para las redes) sigue configurado");
    }
}
