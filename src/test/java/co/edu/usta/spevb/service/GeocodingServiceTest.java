package co.edu.usta.spevb.service;

import co.edu.usta.spevb.config.CityArea;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.*;

class GeocodingServiceTest {
    @Test void noExternalGeocoderIsEnabledByDefault() {
        var geocoder = new GeocodingService("","","SPEVB-Rutas/3.0",JsonMapper.builder().build(),
                new CityArea("Villavicencio","Meta","Colombia",3.95,4.35,-73.85,-73.45));
        assertFalse(geocoder.configurado());
        assertFalse(geocoder.reversoConfigurado());
        assertTrue(geocoder.buscar("Dirección de prueba").isEmpty());
    }
}
