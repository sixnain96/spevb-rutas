package co.edu.usta.spevb.config;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * La marca visible oficial es SEVB. «SPEVB» (en mayúsculas) es histórico y solo puede aparecer en las ubicaciones internas de esta lista,
 * donde cambiarlo rompería la compatibilidad. Los identificadores en minúsculas (paquete {@code spevb}, claves {@code spevb-*}, cookie, contenedores) no se miran aquí.
 */
class MarcaVisibleTest {
    /** Ubicación (sufijo de ruta con «/») → fragmentos exactos permitidos en ella. */
    private static final Map<String, List<String>> PERMITIDO = Map.of(
            // Identificación saliente (User-Agent) ante OpenStreetMap, Overpass y el catastro: cambiarla alteraría la política de uso acordada con esos servicios.
            "service/CatastroImportService.java", List.of("SPEVB-Rutas-USTA/"),
            "service/OsmImportService.java", List.of("SPEVB-Rutas-USTA/"),
            "service/RedPeatonalService.java", List.of("SPEVB-Rutas-USTA/"),
            "vial/ProveedorMatchingExterno.java", List.of("SPEVB-Rutas-USTA/"),
            "vial/RedVialBusService.java", List.of("SPEVB-Rutas-USTA/"),
            // Nombre de variable de entorno interna (compatibilidad de despliegues existentes).
            "resources/application.properties", List.of("SPEVB_BUSQUEDA_"),
            // Migración repetible de datos demostrativos: editarla cambia su suma de verificación en Flyway.
            "resources/db/demo/R__datos_demostrativos.sql", List.of("Datos demostrativos SPEVB"));
    private static final List<String> EXTENSIONES = List.of(".java", ".html", ".js", ".css", ".webmanifest", ".json", ".properties", ".yml", ".sql", ".txt", ".svg");

    @Test
    void spevbEnMayusculasSoloApareceEnLasUbicacionesInternasCompatibles() throws Exception {
        Path raiz = Path.of("src/main");
        assumeTrue(Files.isDirectory(raiz), "src/main no está en este entorno");
        List<String> sobrantes = new ArrayList<>();
        try (Stream<Path> todo = Files.walk(raiz)) {
            for (Path p : todo.filter(Files::isRegularFile).toList()) {
                String ruta = p.toString().replace('\\', '/');
                if (EXTENSIONES.stream().noneMatch(ruta::endsWith) || ruta.contains("/static/vendor/")) continue;
                String texto = Files.readString(p, StandardCharsets.UTF_8);
                if (!texto.contains("SPEVB")) continue;
                List<String> fragmentos = PERMITIDO.entrySet().stream().filter(e -> ruta.endsWith(e.getKey())).flatMap(e -> e.getValue().stream()).toList();
                String resto = texto;
                for (String f : fragmentos) resto = resto.replace(f, "");
                if (resto.contains("SPEVB")) sobrantes.add(ruta);
            }
        }
        assertTrue(sobrantes.isEmpty(), "La marca visible es SEVB. «SPEVB» sobra en: " + sobrantes);
    }
}
