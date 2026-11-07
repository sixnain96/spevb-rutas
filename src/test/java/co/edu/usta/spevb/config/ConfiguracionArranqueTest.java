package co.edu.usta.spevb.config;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Compara las variables que reconoce application.properties con .env.example, start.ps1, compose.app.yml, compose.prod.yml y la
 * matriz de docs/CONFIGURACION.md. Una variable puede faltar en un modo de arranque solo si está en EXCEPCIONES con su motivo.
 */
class ConfiguracionArranqueTest {
    private static final Pattern VAR = Pattern.compile("\\$\\{([A-Z][A-Z0-9_]*)(?::([^}]*))?}");

    /** variable → motivo por el que NO se transmite desde los archivos compose. */
    private static final Map<String, String> EXCEPCIONES = Map.of(
            "SERVER_PORT", "compose: el contenedor siempre escucha en 8080 y el puerto externo se decide con «ports»/Caddy",
            "DB_URL", "compose: la URL apunta al servicio «postgres» y es fija",
            "DB_USER", "compose: el usuario del contenedor PostGIS es fijo");
    /** Variables que solo consume el arranque (no application.properties) y deben estar documentadas. */
    private static final Set<String> EXTRAS_DE_ARRANQUE = Set.of("DOMINIO", "JAVA_OPTS");

    private static String leer(String ruta) throws IOException {
        Path p = Path.of(ruta);
        assumeTrue(Files.exists(p), ruta + " no está en este entorno (p. ej. construcción dentro de Docker)");
        return Files.readString(p, StandardCharsets.UTF_8);
    }

    private static Map<String, String> reconocidas() throws IOException {
        Map<String, String> r = new TreeMap<>();
        for (String linea : leer("src/main/resources/application.properties").split("\\R")) {
            if (linea.startsWith("#")) continue;
            Matcher m = VAR.matcher(linea);
            while (m.find()) r.put(m.group(1), m.group(2));
        }
        return r;
    }

    private static Set<String> enCompose(String archivo) throws IOException {
        Set<String> s = new TreeSet<>();
        Matcher m = Pattern.compile("(?m)^\\s+([A-Z][A-Z0-9_]*):").matcher(leer(archivo));
        while (m.find()) s.add(m.group(1));
        return s;
    }

    @Test void todasLasVariablesDeLaAplicacionLlegaranPorCadaModoDeArranque() throws IOException {
        var vars = reconocidas();
        assertTrue(vars.size() > 20, "se esperaban muchas variables, hay " + vars.size());
        assertTrue(vars.containsKey("DEMO_DATA"));
        for (String archivo : List.of("compose.app.yml", "compose.prod.yml")) {
            Set<String> presentes = enCompose(archivo);
            for (String v : vars.keySet())
                if (!presentes.contains(v))
                    assertTrue(EXCEPCIONES.containsKey(v), v + " no llega a la aplicación desde " + archivo + " y no tiene excepción justificada");
        }
        for (String ex : EXCEPCIONES.keySet()) assertTrue(vars.containsKey(ex), "excepción obsoleta: " + ex);
    }

    @Test void envExampleDocumentaCadaVariableYNoTraeSecretosReales() throws IOException {
        String ejemplo = leer(".env.example");
        Set<String> documentadas = new HashSet<>();
        Matcher m = Pattern.compile("(?m)^#?\\s*([A-Z][A-Z0-9_]*)=").matcher(ejemplo);
        while (m.find()) documentadas.add(m.group(1));
        for (String v : reconocidas().keySet()) assertTrue(documentadas.contains(v), v + " falta en .env.example");
        for (String extra : EXTRAS_DE_ARRANQUE) assertTrue(documentadas.contains(extra), extra + " falta en .env.example");
        assertTrue(ejemplo.contains("\nEDITOR_ENABLED=false"), "el editor debe quedar apagado por defecto");
        assertTrue(ejemplo.contains("\nSERVER_ADDRESS=127.0.0.1"), "por defecto solo se escucha en 127.0.0.1");
        assertFalse(Pattern.compile("(?m)^(?!#)(EDITOR_TOKEN|ARCGIS_API_KEY|GOOGLE_CLIENT_ID|ADMIN_CORREOS)=\\S").matcher(ejemplo).find(),
                "las variables sensibles no llevan valor en la plantilla");
    }

    @Test void startPs1LeeLasVariablesDeApplicationPropertiesYNoPisaLaSesion() throws IOException {
        String ps = leer("start.ps1");
        assertTrue(ps.contains("application.properties"), "la lista de variables debe salir de application.properties");
        assertFalse(ps.contains("-in @('DB_PASSWORD'"), "no debe haber una lista fija que se desactualice");
        assertTrue(ps.contains("GetEnvironmentVariable($taskName, 'Process')"), "no pisa variables ya definidas");
        assertTrue(ps.contains("EDITOR_ENABLED = 'false'") && ps.contains("SERVER_ADDRESS = '127.0.0.1'"));
    }

    @Test void produccionNoCargaDatosDemostrativosNiExponeLaBaseNiElEditorPorDefecto() throws IOException {
        String prod = leer("compose.prod.yml");
        assertTrue(prod.contains("DEMO_DATA: \"false\""));
        assertTrue(prod.contains("EDITOR_ENABLED: ${EDITOR_ENABLED:-false}"));
        String bloquePostgres = prod.substring(prod.indexOf("  postgres:"));
        assertFalse(bloquePostgres.contains("ports:"), "PostgreSQL no se publica en producción");
        String app = leer("compose.app.yml");
        assertTrue(app.contains("EDITOR_ENABLED: ${EDITOR_ENABLED:-false}"));
        assertTrue(app.contains("\"127.0.0.1:8080:8080\""), "Docker local solo publica en 127.0.0.1");
        assertTrue(leer("docker-compose.yml").contains("\"127.0.0.1:5432:5432\""), "PostgreSQL local solo en 127.0.0.1");
        var propiedades = leer("src/main/resources/application.properties");
        assertTrue(propiedades.contains("${EDITOR_ENABLED:false}") && propiedades.contains("${SERVER_ADDRESS:127.0.0.1}"));
        assertTrue(propiedades.contains("${DEMO_DATA:false}"));
    }

    @Test void dockerfileUsaUsuarioSinPrivilegiosHealthcheckYVersionesFijas() throws IOException {
        String d = leer("Dockerfile");
        assertTrue(d.contains("USER spevb"), "usuario no privilegiado");
        assertTrue(d.contains("HEALTHCHECK"));
        assertFalse(d.contains("COPY .env"), "nunca se copia .env");
        for (String linea : d.split("\\R"))
            if (linea.startsWith("FROM ")) assertFalse(linea.contains(":latest") || !linea.contains(":"), linea);
        for (String linea : (leer("compose.prod.yml") + leer("docker-compose.yml")).split("\\R"))
            if (linea.trim().startsWith("image:"))
                assertTrue(linea.matches(".*:\\d+\\.\\d+.*|.*:\\d+-\\d+\\.\\d+.*"), "imagen sin versión específica: " + linea);
    }

    @Test void laMatrizDocumentadaCubreTodasLasVariables() throws IOException {
        String matriz = leer("docs/CONFIGURACION.md");
        for (String v : reconocidas().keySet()) assertTrue(matriz.contains("`" + v + "`"), v + " falta en docs/CONFIGURACION.md");
        for (String v : EXTRAS_DE_ARRANQUE) assertTrue(matriz.contains("`" + v + "`"), v + " falta en docs/CONFIGURACION.md");
    }
}
