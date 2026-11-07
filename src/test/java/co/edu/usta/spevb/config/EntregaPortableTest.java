package co.edu.usta.spevb.config;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** La entrega no puede depender de la cuenta de nadie: ni rutas personales en los scripts de Blender ni identificaciones del geocodificador contradictorias. */
class EntregaPortableTest {
    // Se arman por partes para que este archivo no contenga él mismo ninguna ruta personal.
    private static final List<String> PERSONALES = List.of("C:/Us" + "ers", "C:\\Us" + "ers", "One" + "Drive", "juan" + "j", "/ho" + "me/", "/Us" + "ers/");

    private static String archivo(String ruta) throws Exception {
        Path p = Path.of(ruta);
        assumeTrue(Files.exists(p), ruta + " no está en este entorno (p. ej. construcción dentro de Docker)");
        return Files.readString(p, StandardCharsets.UTF_8);
    }

    @Test void losScriptsDeBlenderNoContienenRutasPersonales() throws Exception {
        Path carpeta = Path.of("design/bus/scripts");
        assumeTrue(Files.isDirectory(carpeta), "design/bus/scripts no está en este entorno");
        try (Stream<Path> scripts = Files.list(carpeta)) {
            List<Path> py = scripts.filter(p -> p.toString().endsWith(".py")).toList();
            assertFalse(py.isEmpty(), "hay scripts de Blender");
            for (Path script : py) {
                String texto = Files.readString(script, StandardCharsets.UTF_8);
                for (String personal : PERSONALES) {
                    assertFalse(texto.contains(personal), script + " contiene la ruta personal «" + personal + "»");
                }
            }
        }
        assertFalse(archivo("design/bus/README.md").contains("juan" + "j"), "el README de Blender no menciona cuentas personales");
        for (String script : List.of("make_sheets.py", "sprites.py")) {
            String texto = archivo("design/bus/scripts/" + script);
            assertTrue(texto.contains("BUS_ROOT") && texto.contains("pathlib"), script + " calcula la raíz con pathlib y admite BUS_ROOT");
        }
    }

    @Test void elGeocodificadorSeIdentificaConUnSoloValorEnTodaLaConfiguracion() throws Exception {
        var valor = Pattern.compile("SEVB-Rutas/[0-9][0-9.]*");
        Set<String> encontrados = new TreeSet<>();
        for (String ruta : List.of(".env.example", "start.ps1", "src/main/resources/application.properties", "docs/CONFIGURACION.md", "compose.app.yml", "compose.prod.yml")) {
            String texto = archivo(ruta);
            var m = valor.matcher(texto);
            boolean hay = false;
            while (m.find()) { encontrados.add(m.group()); hay = true; }
            assertTrue(hay, ruta + " declara la identificación del geocodificador");
            assertFalse(texto.contains("SPEVB-Rutas/3.0"), ruta + " conserva la identificación antigua SPEVB-Rutas/3.0");
        }
        assertEquals(1, encontrados.size(), "una sola identificación coherente: " + encontrados);
    }
}
