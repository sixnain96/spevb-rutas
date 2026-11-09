import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Informe de SOLO LECTURA de la base (Supabase u otra): versión, PostGIS, extensiones, historial de Flyway, tablas y tamaño.
 * Lee DB_URL, DB_USER y DB_PASSWORD de las variables de entorno del proceso; no imprime nunca la contraseña ni la URL completa.
 * Uso (lo ejecuta scripts/supabase/informe.ps1): java -cp postgresql.jar scripts/supabase/InformeSupabase.java
 */
public class InformeSupabase {
    static final double LIMITE_MB = 500.0;

    public static void main(String[] args) {
        String url = System.getenv("DB_URL"), user = System.getenv("DB_USER"), pass = System.getenv("DB_PASSWORD");
        if (url == null || user == null || pass == null || url.isBlank() || user.isBlank() || pass.isBlank()) {
            System.out.println("Faltan DB_URL, DB_USER o DB_PASSWORD en la sesion de PowerShell.");
            System.exit(2);
        }
        try (Connection c = DriverManager.getConnection(url, user, pass)) {
            c.setReadOnly(true);
            try (Statement s = c.createStatement()) {
                latencia(s);
                tabla(s, "Conexion", "select current_user as usuario, current_database() as base, left(version(), 60) as servidor, current_setting('server_version') as v, current_setting('ssl', true) as ssl");
                tabla(s, "search_path de esta conexion", "show search_path");
                tabla(s, "Extensiones (y el esquema donde viven)", "select e.extname as extension, e.extversion as version, n.nspname as esquema from pg_extension e join pg_namespace n on n.oid = e.extnamespace order by 1");
                intentar(s, "PostGIS", "select postgis_full_version() as version");
                intentar(s, "Prueba de geometria y distancia (Villavicencio)", "select ST_AsText(ST_SetSRID(ST_MakePoint(-73.62, 4.14), 4326)) as punto, round(ST_Distance(ST_SetSRID(ST_MakePoint(-73.62, 4.14), 4326)::geography, ST_SetSRID(ST_MakePoint(-73.63, 4.15), 4326)::geography)) as metros");
                intentar(s, "Historial de Flyway", "select installed_rank as n, version, left(description, 40) as descripcion, success as ok from flyway_schema_history order by installed_rank");
                tabla(s, "Tablas del esquema public", "select count(*) as tablas from information_schema.tables where table_schema = 'public' and table_type = 'BASE TABLE'");
                tabla(s, "Tablas mas grandes (estimacion de filas y tamano total con indices)",
                      "select c.relname as tabla, coalesce(t.n_live_tup, 0) as filas_aprox, pg_size_pretty(pg_total_relation_size(c.oid)) as tamano "
                    + "from pg_class c join pg_namespace n on n.oid = c.relnamespace left join pg_stat_user_tables t on t.relid = c.oid "
                    + "where n.nspname = 'public' and c.relkind = 'r' order by pg_total_relation_size(c.oid) desc limit 12");
                try (ResultSet r = s.executeQuery("select pg_database_size(current_database()) as b")) {
                    r.next();
                    double mb = r.getLong(1) / 1048576.0;
                    System.out.printf("%n== Tamano de la base%n%.1f MB de %.0f MB del plan gratis (%.1f %%)%n", mb, LIMITE_MB, 100.0 * mb / LIMITE_MB);
                    if (mb >= 0.8 * LIMITE_MB) System.out.println("AVISO: ya supera el 80 % del limite. Apaga DIRECCIONES_IMPORT_AL_ARRANCAR y OSM_IMPORT_AL_ARRANCAR.");
                    else if (mb >= 0.5 * LIMITE_MB) System.out.println("AVISO: supera la mitad del limite.");
                }
            }
        } catch (SQLException e) {
            System.out.println("ERROR de conexion o consulta: SQLState=" + e.getSQLState() + " | " + primeraLinea(e.getMessage()));
            System.exit(1);
        }
    }

    /** Mide el viaje de ida y vuelta real a la base (mediana de 15 consultas minimas): con ella se sabe cuanto cuesta cada sentencia de la aplicacion. */
    static void latencia(Statement s) throws SQLException {
        long[] ms = new long[15];
        s.execute("select 1");                                   // calentamiento (plan, TLS ya negociado)
        for (int i = 0; i < ms.length; i++) { long t0 = System.nanoTime(); s.execute("select 1"); ms[i] = (System.nanoTime() - t0) / 1_000_000; }
        java.util.Arrays.sort(ms);
        System.out.printf("%n== Latencia de ida y vuelta a la base%nmediana %d ms (minimo %d, maximo %d) con 15 consultas 'select 1'%n", ms[ms.length / 2], ms[0], ms[ms.length - 1]);
        System.out.println("La aplicacion hace entre 1 y 5 consultas por peticion y unas 20 al planificar un viaje: ese tiempo se multiplica. Menos de 10 ms = servidor cerca de la base; mas de 80 ms = lejos (ver docs/RENDIMIENTO-DB.md).");
    }

    static String primeraLinea(String m) { return m == null ? "" : m.split("\\R")[0]; }

    static void intentar(Statement s, String titulo, String sql) {
        try { tabla(s, titulo, sql); }
        catch (SQLException e) { System.out.println("\n== " + titulo + "\nFALLO: SQLState=" + e.getSQLState() + " | " + primeraLinea(e.getMessage())); }
    }

    static void tabla(Statement s, String titulo, String sql) throws SQLException {
        try (ResultSet r = s.executeQuery(sql)) {
            System.out.println("\n== " + titulo);       // el titulo se imprime solo si la consulta funciona (si falla lo imprime intentar())
            int n = r.getMetaData().getColumnCount();
            StringBuilder cab = new StringBuilder();
            for (int i = 1; i <= n; i++) cab.append(r.getMetaData().getColumnLabel(i)).append(i < n ? " | " : "");
            System.out.println(cab);
            int filas = 0;
            while (r.next()) {
                StringBuilder f = new StringBuilder();
                for (int i = 1; i <= n; i++) f.append(r.getString(i)).append(i < n ? " | " : "");
                System.out.println(f);
                filas++;
            }
            if (filas == 0) System.out.println("(sin filas)");
        }
    }
}
