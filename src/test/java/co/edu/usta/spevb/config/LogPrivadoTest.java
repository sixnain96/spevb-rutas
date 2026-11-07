package co.edu.usta.spevb.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class LogPrivadoTest {
    @Test void enmascaraCorreosCoordenadasYTokens() {
        String r = LogPrivado.limpiar("fallo para persona.real@example.org en 4.145012,-73.654034 con token 0123456789abcdef0123456789abcdef0123");
        assertFalse(r.contains("persona.real"), r);
        assertTrue(r.contains("p***@example.org"), r);
        assertFalse(r.contains("4.145012"), r);
        assertTrue(r.contains("<coordenadas>"), r);
        assertFalse(r.contains("0123456789abcdef0123456789abcdef"), r);
    }

    @Test void resumenNoIncluyeLaTraza() {
        String r = LogPrivado.resumen(new IllegalStateException("clave duplicada (a@b.co)"));
        assertTrue(r.startsWith("IllegalStateException: "));
        assertFalse(r.contains("a@b.co"));
        assertEquals("", LogPrivado.limpiar(null));
    }
}
