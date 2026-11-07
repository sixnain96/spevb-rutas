package co.edu.usta.spevb.service;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.*;

class CuentaSeguridadTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void laClaveSeGuardaCifradaConSalYSoloVerificaLaCorrecta() {
        String a = ClaveHash.hacer("mi-clave-segura", 1000), b = ClaveHash.hacer("mi-clave-segura", 1000);
        assertTrue(a.startsWith("pbkdf2$1000$"));
        assertFalse(a.contains("mi-clave-segura"));
        assertNotEquals(a, b, "cada clave lleva su propia sal");
        assertTrue(ClaveHash.verificar("mi-clave-segura", a));
        assertFalse(ClaveHash.verificar("otra-clave", a));
        assertFalse(ClaveHash.verificar("x", "basura"), "un hash dañado no verifica ni lanza error");
        assertEquals(600_000, ClaveHash.ITERACIONES);
    }

    @Test
    void validaCorreoYClave() {
        assertEquals("ana@ejemplo.com", CuentaService.normalizarCorreo("  Ana@Ejemplo.COM "));
        for (String malo : new String[]{"", "ana", "ana@", "@x.com", "a b@x.com", "ana@x"}) {
            assertThrows(IllegalArgumentException.class, () -> CuentaService.normalizarCorreo(malo), malo);
        }
        assertThrows(IllegalArgumentException.class, () -> CuentaService.validarClave("corta"));
        assertThrows(IllegalArgumentException.class, () -> CuentaService.validarClave("12345678"), "solo números");
        assertThrows(IllegalArgumentException.class, () -> CuentaService.validarClave("a".repeat(129)));
        assertDoesNotThrow(() -> CuentaService.validarClave("clave-segura-1"));
    }

    @Test
    void losDatosSubidosSeLimpianYAcotan() throws Exception {
        var d = new DatosUsuario(mapper);
        var lugares = d.limpiar("lugares", mapper.readTree("""
                [{"id":"1","nombre":"Casa","lat":4.1,"lng":-73.6,"malicioso":"<script>"},
                 {"id":"2","nombre":"Fuera","lat":200,"lng":0},
                 {"nombre":"sin id","lat":4,"lng":-73},
                 "texto suelto", null]"""));
        assertEquals(1, lugares.size());
        assertFalse(lugares.get(0).has("malicioso"));
        var muchos = mapper.createArrayNode();
        for (int i = 0; i < 100; i++) muchos.addObject().put("id", "r" + i);
        assertEquals(100, d.limpiar("rutas", muchos).size());
        for (int i = 0; i < 200; i++) muchos.addObject().put("id", "s" + i);
        assertEquals(100, d.limpiar("rutas", muchos).size(), "tope por tipo");
        assertThrows(IllegalArgumentException.class, () -> d.limpiar("admin", muchos));
        assertThrows(IllegalArgumentException.class, () -> d.limpiar("lugares", mapper.readTree("{\"a\":1}")));
    }

    @Test
    void elHistorialSoloGuardaOrigenDestinoYRutas() throws Exception {
        var d = new DatosUsuario(mapper);
        var h = d.limpiar("historial", mapper.readTree("""
                [{"id":"h1","t":1700000000000,"a":{"lat":4.14,"lng":-73.6,"nombre":"A"},"b":{"lat":4.15,"lng":-73.59,"nombre":"B"},
                  "codigos":["R1","R2","R3","R4"],"gps":[[1,2],[3,4]],"data":{"enorme":true}}]"""));
        var item = h.get(0);
        assertEquals(3, item.get("codigos").size());
        assertFalse(item.has("gps") || item.has("data"));
    }
}
