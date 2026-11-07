package co.edu.usta.spevb.db;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.sql.Connection;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Migraciones contra PostgreSQL/PostGIS REAL (contenedor desechable; nunca toca la base del desarrollador).
 * Sin Docker, o con -Dsevb.sinDocker=true, estas pruebas se OMITEN (no pasan en silencio). Con Docker disponible se ejecutan solas.
 */
@org.junit.jupiter.api.condition.DisabledIfSystemProperty(named = "sevb.sinDocker", matches = "true", disabledReason = "Ejecución sin Docker solicitada con -Dsevb.sinDocker=true: se OMITE (no se da por aprobada)")
@Testcontainers(disabledWithoutDocker = true)
class MigracionesPostgisTest {
    private static final String IMAGEN = "postgis/postgis:17-3.5";
    private static final Set<String> TABLAS = Set.of("rutas", "ruta_revisiones", "paraderos", "lugares", "direcciones", "barrios",
            "direccion_confirmaciones", "busquedas_sin_resultado", "direcciones_importaciones", "pois", "pasos_peatonales", "reportes",
            "usuarios", "sesiones", "usuario_datos", "paraderos_buses", "ruta_aliases");

    @Container
    static final PostgreSQLContainer NUEVA = nueva();
    @Container
    static final PostgreSQLContainer DEMO = nueva();
    @Container
    static final PostgreSQLContainer EXISTENTE = nueva();

    private static PostgreSQLContainer nueva() {
        return new PostgreSQLContainer(DockerImageName.parse(IMAGEN).asCompatibleSubstituteFor("postgres"))
                .withDatabaseName("spevb").withUsername("spevb").withPassword("clave-de-prueba-desechable");
    }

    private static DriverManagerDataSource ds(PostgreSQLContainer c) {
        return new DriverManagerDataSource(c.getJdbcUrl(), c.getUsername(), c.getPassword());
    }

    private static Flyway flyway(PostgreSQLContainer c, String... ubicaciones) {
        return Flyway.configure().dataSource(ds(c)).locations(ubicaciones).baselineOnMigrate(true).baselineVersion("0")
                .ignoreMigrationPatterns("repeatable:missing").load();
    }

    private static Set<String> tablas(JdbcTemplate jdbc) {
        return Set.copyOf(jdbc.queryForList("SELECT table_name FROM information_schema.tables WHERE table_schema='public'", String.class));
    }

    @Test void baseNuevaCreaTodoElEsquemaSinDatosDemostrativos() {
        var f = flyway(NUEVA, "classpath:db/migration");
        assertTrue(f.migrate().success);
        var jdbc = new JdbcTemplate(ds(NUEVA));
        assertTrue(tablas(jdbc).containsAll(TABLAS), "faltan tablas: " + TABLAS);
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM rutas", Integer.class), "producción no carga rutas DEMO");
        assertEquals(11, jdbc.queryForObject("SELECT COUNT(*) FROM flyway_schema_history WHERE success AND version IS NOT NULL AND version <> '0'", Integer.class));
        // V4: el tipo PUENTE es válido y un tipo desconocido no
        jdbc.update("INSERT INTO pasos_peatonales (tipo, nombre, trazo) VALUES ('PUENTE','P',ST_GeomFromText('LINESTRING(-73.63 4.14,-73.629 4.14)',4326))");
        assertThrows(Exception.class, () -> jdbc.update("INSERT INTO pasos_peatonales (tipo, nombre, trazo) VALUES ('OTRO','P',ST_GeomFromText('LINESTRING(-73.63 4.14,-73.629 4.14)',4326))"));
        // V10: control de versión de pasos/puentes
        assertEquals(0L, jdbc.queryForObject("SELECT version FROM pasos_peatonales LIMIT 1", Long.class));
        // V11: nombre popular de la ruta (opcional)
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.columns WHERE table_name='rutas' AND column_name='nombre_popular'", Integer.class));
        f.validate();
        assertEquals(0, f.migrate().migrationsExecuted, "una segunda ejecución no cambia nada");
    }

    @Test void losDatosDemostrativosSoloEntranConSuUbicacionYSonIdempotentes() {
        var f = flyway(DEMO, "classpath:db/migration", "classpath:db/demo");
        assertTrue(f.migrate().success);
        var jdbc = new JdbcTemplate(ds(DEMO));
        assertTrue(jdbc.queryForObject("SELECT COUNT(*) FROM rutas WHERE codigo IN ('R001','R002','R003','R004')", Integer.class) >= 4);
        assertEquals(0, flyway(DEMO, "classpath:db/migration", "classpath:db/demo").migrate().migrationsExecuted);
        // Apagar la demostración después de haberla usado no rompe el arranque (repeatable:missing) ni borra nada.
        var sinDemo = flyway(DEMO, "classpath:db/migration");
        sinDemo.validate();
        assertTrue(jdbc.queryForObject("SELECT COUNT(*) FROM rutas", Integer.class) >= 4);
    }

    @Test void baseExistenteCreadaConSchemaSqlSeAdoptaSinPerderDatos() throws Exception {
        var jdbc = new JdbcTemplate(ds(EXISTENTE));
        try (Connection c = ds(EXISTENTE).getConnection()) {
            ScriptUtils.executeSqlScript(c, new ClassPathResource("legacy/schema-4.9.6.sql"));
            ScriptUtils.executeSqlScript(c, new ClassPathResource("legacy/data-4.9.6.sql"));
        }
        // Datos reales de una instalación anterior.
        jdbc.update("INSERT INTO rutas (codigo,nombre,origen,destino,color_hex,demostrativa,recorrido) VALUES ('R900','Ruta real','A','B','#123456',FALSE,ST_GeomFromText('LINESTRING(-73.63 4.14,-73.62 4.15)',4326))");
        jdbc.update("INSERT INTO paraderos (ruta_id,secuencia,nombre,ubicacion) SELECT id,1,'Paradero real',ST_SetSRID(ST_MakePoint(-73.63,4.14),4326) FROM rutas WHERE codigo='R900'");
        jdbc.update("INSERT INTO lugares (nombre,ubicacion,confirmado) VALUES ('Lugar real',ST_SetSRID(ST_MakePoint(-73.63,4.14),4326),TRUE)");
        jdbc.update("INSERT INTO usuarios (correo,nombre,clave_hash,correo_verificado) VALUES ('real@example.org','Persona','hash',TRUE)");
        jdbc.update("INSERT INTO reportes (categoria,mensaje) VALUES ('RUTA','reporte real')");
        jdbc.update("INSERT INTO pasos_peatonales (tipo,nombre,nota,trazo) VALUES ('PUENTE','Puente real','nota real',ST_GeomFromText('LINESTRING(-73.63 4.14,-73.629 4.14)',4326))");
        jdbc.update("INSERT INTO paraderos_buses (nombre,ubicacion) VALUES ('Paradero sistema',ST_SetSRID(ST_MakePoint(-73.63,4.14),4326))");
        // El esquema histórico 4.9.6 aún no tiene las tablas que añadieron migraciones posteriores (p. ej. ruta_aliases, V9):
        // solo se cuentan antes las que ya existían; las nuevas se comprueban aparte, vacías, tras migrar.
        var existentes = tablas(jdbc);
        var antiguas = TABLAS.stream().filter(existentes::contains).collect(java.util.stream.Collectors.toCollection(java.util.TreeSet::new));
        var nuevasPorMigracion = TABLAS.stream().filter((t) -> !existentes.contains(t)).collect(java.util.stream.Collectors.toSet());
        assertEquals(Set.of("ruta_aliases"), nuevasPorMigracion, "solo ruta_aliases (V9) falta en el esquema 4.9.6; si cambia, revisa la lista");
        var antes = conteos(jdbc, antiguas);
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_name='flyway_schema_history'", Integer.class));

        var resultado = flyway(EXISTENTE, "classpath:db/migration").migrate();
        assertTrue(resultado.success);
        assertEquals(antes, conteos(jdbc, antiguas), "ninguna fila se pierde ni se duplica");
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM ruta_aliases", Integer.class), "la tabla nueva (V9) se crea vacía");
        assertEquals("nota real", jdbc.queryForObject("SELECT nota FROM pasos_peatonales WHERE nombre='Puente real'", String.class));
        assertEquals("Ruta real", jdbc.queryForObject("SELECT nombre FROM rutas WHERE codigo='R900'", String.class));
        assertTrue(tablas(jdbc).containsAll(TABLAS));
        assertEquals(0, flyway(EXISTENTE, "classpath:db/migration").migrate().migrationsExecuted);
        flyway(EXISTENTE, "classpath:db/migration").validate();
    }

    private static List<Integer> conteos(JdbcTemplate jdbc, Set<String> tablas) {
        return tablas.stream().sorted().map((t) -> jdbc.queryForObject("SELECT COUNT(*) FROM " + t, Integer.class)).toList();
    }
}
