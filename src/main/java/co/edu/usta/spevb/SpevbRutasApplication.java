package co.edu.usta.spevb;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class SpevbRutasApplication {

    /** Propiedad de HikariCP: una conexión usada hace menos de estos milisegundos no se vuelve a validar al prestarla (por defecto 500). */
    static final String HIKARI_OMITIR_VALIDACION = "com.zaxxer.hikari.aliveBypassWindowMs";

    public static void main(String[] args) {
        ajustarValidacionDeConexiones(System.getenv("DB_VALIDACION_OMITIR_MS"));
        var aplicacion = new SpringApplication(SpevbRutasApplication.class);
        // Con una base de Supabase se activa solo el perfil «supabase» (application-supabase.properties: pool y parámetros del driver).
        // Con la base local no cambia nada. DB_PERFIL_AUTOMATICO=false lo desactiva; SPRING_PROFILES_ACTIVE sigue funcionando como siempre.
        if (esBaseSupabase(System.getenv("DB_URL")) && !"false".equalsIgnoreCase(System.getenv("DB_PERFIL_AUTOMATICO"))) {
            aplicacion.setAdditionalProfiles("supabase");
        }
        aplicacion.run(args);
    }

    /** Verdadero si la URL JDBC apunta a un servidor de Supabase (pooler o conexión directa). */
    static boolean esBaseSupabase(String urlJdbc) {
        if (urlJdbc == null) return false;
        String u = urlJdbc.toLowerCase(java.util.Locale.ROOT);
        return u.startsWith("jdbc:postgresql://") && (u.contains(".supabase.com") || u.contains(".supabase.co"));
    }

    /**
     * OPCIONAL y desactivado por defecto. Con DB_VALIDACION_OMITIR_MS=N (solo dígitos) una conexión usada hace menos de N ms se presta sin validar:
     * ahorra un viaje de ida y vuelta por petición (≈ 110 ms desde Colombia hasta Virginia; ≈ 1-2 ms si el servidor está en la misma región), pero si el
     * servidor corta las conexiones en ese lapso fallan tantas peticiones como conexiones muertas haya (medido en docs/RENDIMIENTO-DB.md).
     * Una propiedad de la JVM ya definida manda sobre la variable.
     */
    static void ajustarValidacionDeConexiones(String variable) {
        if (System.getProperty(HIKARI_OMITIR_VALIDACION) != null) return;
        if (variable != null && variable.matches("\\d{1,6}")) System.setProperty(HIKARI_OMITIR_VALIDACION, variable);
    }
}
