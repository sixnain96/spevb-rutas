package co.edu.usta.spevb.controller;

import co.edu.usta.spevb.config.LimitadorSolicitudes;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class ReporteControllerTest {
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final ReporteController controller = new ReporteController(jdbc, new LimitadorSolicitudes());

    private ReporteController.ReporteRequest pedido(String categoria, String mensaje) {
        return new ReporteController.ReporteRequest(categoria, mensaje, "R001", 4.14, -73.62, 95.0, 500.0, "  yo@correo.com ");
    }

    @Test
    void guardaElReporteLimpioYDescartaCoordenadasInvalidas() {
        var res = controller.crear(pedido("caminata", "  La caminata cruza un canal.  "), new MockHttpServletRequest());
        assertEquals(200, res.getStatusCode().value());
        ArgumentCaptor<Object> a = ArgumentCaptor.forClass(Object.class);
        verify(jdbc).update(anyString(), a.capture(), a.capture(), a.capture(), a.capture(), a.capture(), a.capture(), a.capture(), a.capture(), a.capture());
        var v = a.getAllValues();
        assertEquals("CAMINATA", v.get(0));
        assertEquals("La caminata cruza un canal.", v.get(1));
        assertNull(v.get(5), "latitud 95 es inválida");
        assertNull(v.get(6), "longitud 500 es inválida");
        assertEquals("yo@correo.com", v.get(7));
    }

    @Test
    void rechazaCategoriaDesconocidaYMensajeCorto() {
        assertThrows(IllegalArgumentException.class, () -> controller.crear(pedido("XX", "mensaje largo"), new MockHttpServletRequest()));
        assertThrows(IllegalArgumentException.class, () -> controller.crear(pedido("RUTA", "  hi "), new MockHttpServletRequest()));
        verify(jdbc, never()).update(anyString(), any(Object[].class));
    }

    @Test
    void limitaCincoReportesCadaDiezMinutosPorIp() {
        var req = new MockHttpServletRequest();
        req.setRemoteAddr("9.9.9.9");
        for (int i = 0; i < 5; i++) assertEquals(200, controller.crear(pedido("RUTA", "reporte número " + i), req).getStatusCode().value());
        assertEquals(429, controller.crear(pedido("RUTA", "otro reporte más"), req).getStatusCode().value());
    }
}
