package co.edu.usta.spevb.config;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** La versión se escribe una sola vez (pom.xml). Todo lo visible (estado, service worker, manifest, JAR, README, CHANGELOG) debe coincidir. */
class VersionConsistenciaTest {
    private static String clasepath(String ruta) throws Exception {
        try (InputStream in = VersionConsistenciaTest.class.getResourceAsStream(ruta)) {
            assertNotNull(in, "falta " + ruta);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static String archivo(String ruta) throws Exception {
        Path p = Path.of(ruta);
        assumeTrue(Files.exists(p), ruta + " no está en este entorno (p. ej. construcción dentro de Docker)");
        return Files.readString(p, StandardCharsets.UTF_8);
    }

    private static String versionDelPom() throws Exception {
        var m = Pattern.compile("<artifactId>spevb-rutas</artifactId>\\s*<version>([^<]+)</version>").matcher(archivo("pom.xml"));
        assertTrue(m.find(), "no se encontró la versión del proyecto en pom.xml");
        return m.group(1);
    }

    @Test void laVersionVisibleEsLaMismaEnTodosLosLugares() throws Exception {
        String v = versionDelPom();
        assertTrue(v.matches("\\d+\\.\\d+\\.\\d+"), "versión con formato X.Y.Z: " + v);

        var info = new Properties();
        info.load(new java.io.StringReader(clasepath("/META-INF/build-info.properties")));
        assertEquals(v, info.getProperty("build.version"), "BuildProperties");

        var app = new Properties();
        app.load(new java.io.StringReader(clasepath("/application.properties")));
        assertEquals(v, app.getProperty("spevb.version"), "application.properties (lo que devuelve /api/estado)");

        assertTrue(clasepath("/static/sw.js").contains("const VERSION = \"spevb-v" + v + "\";"), "service worker");
        assertEquals(v, JsonMapper.builder().build().readTree(clasepath("/static/manifest.webmanifest")).path("version").asText(), "manifest");
        assertFalse(clasepath("/static/sw.js").contains("@project.version@"), "el filtrado de Maven debe sustituir el marcador");

        var cambios = Pattern.compile("(?m)^## (\\d+\\.\\d+\\.\\d+)").matcher(archivo("CHANGELOG.md"));
        assertTrue(cambios.find());
        assertEquals(v, cambios.group(1), "primera entrada del CHANGELOG");
        assertTrue(archivo("README.md").contains(v), "README");
        assertTrue(archivo("docs/CONFIGURACION.md").contains(v), "docs/CONFIGURACION.md");
        assertTrue(archivo("design/bus/README.md").contains(v), "design/bus/README.md");
    }

    @Test void elCodigoFuenteNoEscribeLaVersionAMano() throws Exception {
        String fuente = archivo("src/main/java/co/edu/usta/spevb/controller/RutaController.java");
        assertFalse(Pattern.compile("\"version\",\\s*\"\\d").matcher(fuente).find(), "/api/estado debe leer la versión de la configuración");
        assertTrue(fuente.contains("spevb.version"));
    }

    @Test void estadoDevuelveLaVersionConfigurada() throws Exception {
        var servicio = org.mockito.Mockito.mock(co.edu.usta.spevb.service.RutaService.class);
        var lugares = org.mockito.Mockito.mock(co.edu.usta.spevb.service.LugarService.class);
        var controlador = new co.edu.usta.spevb.controller.RutaController(servicio, lugares,
                org.mockito.Mockito.mock(co.edu.usta.spevb.service.ParaderoService.class), "9.9.9");
        assertEquals("9.9.9", controlador.estado().get("version"));
    }
}
