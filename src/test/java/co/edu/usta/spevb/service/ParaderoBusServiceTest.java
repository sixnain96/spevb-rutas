package co.edu.usta.spevb.service;

import co.edu.usta.spevb.config.CityArea;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ParaderoBusServiceTest {
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    @SuppressWarnings("unchecked")
    private final ObjectProvider<ViajeService> viajes = mock(ObjectProvider.class);
    private final CityArea area = new CityArea("Villavicencio", "Meta", "Colombia", 3.95, 4.35, -73.85, -73.45);
    private final ParaderoBusService servicio = new ParaderoBusService(jdbc, area, viajes);

    private String mensaje(Runnable r) { return assertThrows(IllegalArgumentException.class, r::run).getMessage(); }

    @Test
    void rechazaNombreCortoYPuntosFueraDeLaCiudadSinTocarLaBase() {
        assertTrue(mensaje(() -> servicio.crear(new ParaderoBusService.Entrada(" x ", null, 4.14, -73.62))).contains("nombre"));
        assertTrue(mensaje(() -> servicio.crear(new ParaderoBusService.Entrada("Parada", null, 10, 0))).contains("fuera"));
        verifyNoInteractions(jdbc);
    }

    @Test
    void laImportacionValidaTodoAntesDeGuardarYAcotaElTamanio() {
        List<ParaderoBusService.Entrada> mala = List.of(new ParaderoBusService.Entrada("Buena", null, 4.14, -73.62), new ParaderoBusService.Entrada("Mala", null, 50, 50));
        assertTrue(mensaje(() -> servicio.crearLote(mala)).contains("Paradero 2"));
        List<ParaderoBusService.Entrada> muchas = new ArrayList<>();
        for (int i = 0; i < 501; i++) muchas.add(new ParaderoBusService.Entrada("P" + i, null, 4.14, -73.62));
        assertTrue(mensaje(() -> servicio.crearLote(muchas)).contains("500"));
        assertTrue(mensaje(() -> servicio.crearLote(List.of())).contains("No hay"));
        verifyNoInteractions(jdbc);
    }

    @Test
    void laZonaPedidaDebeSerValidaYPequenia() {
        assertThrows(IllegalArgumentException.class, () -> servicio.enCaja(4.2, -73.6, 4.1, -73.5, 100));
        assertTrue(mensaje(() -> servicio.enCaja(3.9, -73.9, 4.3, -73.5, 100)).contains("Acerca"));
    }

    @Test
    void laDistanciaQueSirveUnaRutaEsDe30Metros() {
        assertEquals(30, ParaderoBusService.DISTANCIA_RUTA_M);
        assertEquals(30, ParaderoService.DISTANCIA_MAXIMA_M);
        assertEquals(25, AbordajeService.PARADERO_VENTAJA_METROS, "un paradero se prefiere si no obliga a caminar más de ~25 m extra");
    }
}
