package co.edu.usta.spevb.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LimiteGeneralFilterTest {
    private int llamar(LimiteGeneralFilter filtro, String ruta, String ip) throws Exception {
        var req = new MockHttpServletRequest("GET", ruta);
        req.setRemoteAddr(ip);
        var res = new MockHttpServletResponse();
        filtro.doFilter(req, res, new MockFilterChain());
        return res.getStatus();
    }

    @Test
    void frenaDespuesDelLimiteYSoloALaMismaIp() throws Exception {
        var filtro = new LimiteGeneralFilter(new LimitadorSolicitudes(), true);
        for (int i = 0; i < 40; i++) assertEquals(200, llamar(filtro, "/api/viajes/planificar", "1.1.1.1"));
        assertEquals(429, llamar(filtro, "/api/viajes/planificar", "1.1.1.1"));
        assertEquals(200, llamar(filtro, "/api/viajes/planificar", "2.2.2.2"), "otra IP no se ve afectada");
        assertEquals(200, llamar(filtro, "/api/rutas", "1.1.1.1"), "otro tipo de solicitud tiene su propio cupo");
    }

    @Test
    void noTocaLosArchivosEstaticosNiSeAplicaSiEstaApagado() throws Exception {
        var filtro = new LimiteGeneralFilter(new LimitadorSolicitudes(), true);
        for (int i = 0; i < 500; i++) assertEquals(200, llamar(filtro, "/js/app.js", "3.3.3.3"));
        var apagado = new LimiteGeneralFilter(new LimitadorSolicitudes(), false);
        for (int i = 0; i < 500; i++) assertEquals(200, llamar(apagado, "/api/viajes/planificar", "4.4.4.4"));
    }
}
