package co.edu.usta.spevb.service;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class AdminCuentaTest {
    private CuentaService con(String admins) { return new CuentaService(mock(JdbcTemplate.class), new ObjectMapper(), admins); }

    @Test
    void soloEsAdminQuienEstaEnLaListaYTieneElCorreoVerificado() {
        var s = con(" Admin@Gmail.com ; otro@x.com,  ");
        assertTrue(s.adminConfigurado());
        assertTrue(s.esAdmin("admin@gmail.com", true));
        assertTrue(s.esAdmin("OTRO@x.com", true), "sin importar mayúsculas");
        assertFalse(s.esAdmin("admin@gmail.com", false), "un correo sin verificar (registro con contraseña) nunca es administrador");
        assertFalse(s.esAdmin("alguien@gmail.com", true));
        assertFalse(s.esAdmin(null, true));
    }

    @Test
    void sinAdministradoresConfiguradosNadieLoEs() {
        var s = con("");
        assertFalse(s.adminConfigurado());
        assertFalse(s.esAdmin("admin@gmail.com", true));
    }
}
