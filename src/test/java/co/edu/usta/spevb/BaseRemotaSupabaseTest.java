package co.edu.usta.spevb;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** La detección de Supabase y el ajuste de validación de conexiones: con la base local no debe cambiar nada. */
class BaseRemotaSupabaseTest {
    private String anterior;

    @BeforeEach
    void guardar() { anterior = System.getProperty(SpevbRutasApplication.HIKARI_OMITIR_VALIDACION); System.clearProperty(SpevbRutasApplication.HIKARI_OMITIR_VALIDACION); }

    @AfterEach
    void restaurar() {
        if (anterior == null) System.clearProperty(SpevbRutasApplication.HIKARI_OMITIR_VALIDACION);
        else System.setProperty(SpevbRutasApplication.HIKARI_OMITIR_VALIDACION, anterior);
    }

    @Test
    void reconoceSupabaseYNadaMas() {
        assertTrue(SpevbRutasApplication.esBaseSupabase("jdbc:postgresql://aws-0-us-east-1.pooler.supabase.com:5432/postgres?sslmode=require"));
        assertTrue(SpevbRutasApplication.esBaseSupabase("jdbc:postgresql://aws-1-us-east-1.pooler.supabase.com:5432/postgres"));
        assertTrue(SpevbRutasApplication.esBaseSupabase("JDBC:POSTGRESQL://db.abcdefghij.SUPABASE.CO:5432/postgres"));
        assertFalse(SpevbRutasApplication.esBaseSupabase("jdbc:postgresql://localhost:5432/spevb"));
        assertFalse(SpevbRutasApplication.esBaseSupabase("jdbc:postgresql://127.0.0.1:55432/supa"));
        assertFalse(SpevbRutasApplication.esBaseSupabase("jdbc:postgresql://miservidor.example.com:5432/spevb?x=supabase.com"));
        assertFalse(SpevbRutasApplication.esBaseSupabase("jdbc:mysql://x.supabase.com/db"));
        assertFalse(SpevbRutasApplication.esBaseSupabase(null));
    }

    @Test
    void pordefectoNoSeTocaLaValidacionDeHikari() {
        SpevbRutasApplication.ajustarValidacionDeConexiones(null);
        assertNull(System.getProperty(SpevbRutasApplication.HIKARI_OMITIR_VALIDACION));
        SpevbRutasApplication.ajustarValidacionDeConexiones("");
        assertNull(System.getProperty(SpevbRutasApplication.HIKARI_OMITIR_VALIDACION));
    }

    @Test
    void soloConLaVariableExplicitaSeOmiteLaValidacion() {
        SpevbRutasApplication.ajustarValidacionDeConexiones("2500");
        assertEquals("2500", System.getProperty(SpevbRutasApplication.HIKARI_OMITIR_VALIDACION));
    }

    @Test
    void laPropiedadDeLaJvmTienePrioridadSobreLaVariable() {
        System.setProperty(SpevbRutasApplication.HIKARI_OMITIR_VALIDACION, "123");
        SpevbRutasApplication.ajustarValidacionDeConexiones("9999");
        assertEquals("123", System.getProperty(SpevbRutasApplication.HIKARI_OMITIR_VALIDACION));
    }

    @Test
    void unaVariableInvalidaSeIgnora() {
        SpevbRutasApplication.ajustarValidacionDeConexiones("abc; rm");
        assertNull(System.getProperty(SpevbRutasApplication.HIKARI_OMITIR_VALIDACION));
        SpevbRutasApplication.ajustarValidacionDeConexiones("12345678");
        assertNull(System.getProperty(SpevbRutasApplication.HIKARI_OMITIR_VALIDACION));
    }
}
