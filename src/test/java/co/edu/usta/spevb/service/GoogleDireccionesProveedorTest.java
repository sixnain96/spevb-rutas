package co.edu.usta.spevb.service;

import co.edu.usta.spevb.config.CityArea;
import co.edu.usta.spevb.config.MapaConfig;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.*;

/** Los resultados de Places solo se usan sobre un mapa de Google: con Leaflet las sugerencias de Google se apagan (aunque haya clave). */
class GoogleDireccionesProveedorTest {
    private static final String CLAVE_SERVIDOR = "AIzaSyOTRA_CLAVE_DE_SERVIDOR_NO_REAL_456";
    private final CityArea area = new CityArea("Villavicencio", "Meta", "Colombia", 3.95, 4.35, -73.85, -73.45);

    private GoogleDireccionesService servicio(String clave, MapaConfig mapa) {
        return new GoogleDireccionesService(clave, "https://places.googleapis.com", JsonMapper.builder().build(), area, mapa);
    }

    @Test void conLeafletGoogleQuedaApagadoAunqueHayaClave() {
        var s = servicio(CLAVE_SERVIDOR, MapaConfig.leaflet());
        assertFalse(s.activo());
        assertTrue(s.sugerir("Carrera 22 con calle 38").isEmpty(), "no llama a Google ni devuelve nada");
        assertTrue(s.buscarTexto("Parque Los Libertadores").isEmpty());
    }

    @Test void conMapaDeGoogleYClaveEstaActivo() {
        var g = new MapaConfig("google", "AIzaSyEJEMPLO_NO_ES_UNA_CLAVE_REAL_0123", "MI_MAP_ID", "quarterly", CLAVE_SERVIDOR);
        assertTrue(servicio(CLAVE_SERVIDOR, g).activo());
        assertFalse(servicio("", g).activo(), "sin clave del servidor sigue apagado");
    }
}
