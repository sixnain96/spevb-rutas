package co.edu.usta.spevb.config;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/** Si un archivo de la lista del service worker no existe, su instalación falla y la app deja de abrir sin conexión. */
class PwaResourcesTest {
    private static String leer(String ruta) throws Exception {
        try (var in = PwaResourcesTest.class.getResourceAsStream("/static" + ruta)) {
            assertNotNull(in, "falta " + ruta);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test void everyFileListedByTheServiceWorkerExists() throws Exception {
        String sw = leer("/sw.js");
        var bloque = Pattern.compile("const SHELL = \\[(.*?)\\];", Pattern.DOTALL).matcher(sw);
        assertTrue(bloque.find());
        List<String> rutas = new ArrayList<>();
        var item = Pattern.compile("\"(/[^\"]*)\"").matcher(bloque.group(1));
        while (item.find()) rutas.add(item.group());
        assertTrue(rutas.size() > 10);
        for (String r : rutas) {
            String ruta = r.replace("\"", "");
            assertNotNull(PwaResourcesTest.class.getResource("/static" + ("/".equals(ruta) ? "/index.html" : ruta)), "no existe " + ruta);
        }
    }

    @Test void manifestIsValidAndItsIconsExist() throws Exception {
        var manifest = JsonMapper.builder().build().readTree(leer("/manifest.webmanifest"));
        assertEquals("standalone", manifest.path("display").asText());
        assertEquals("/", manifest.path("start_url").asText());
        assertTrue(manifest.path("icons").size() >= 3);
        for (var icono : manifest.path("icons"))
            assertNotNull(PwaResourcesTest.class.getResource("/static" + icono.path("src").asText()), "falta el icono " + icono.path("src").asText());
    }

    @Test void indexLinksTheManifestAndRegistersTheWorker() throws Exception {
        String html = leer("/index.html");
        assertTrue(html.contains("rel=\"manifest\""));
        assertTrue(html.contains("name=\"theme-color\""));
        assertTrue(html.contains("/js/pwa.js"));
        assertTrue(leer("/js/pwa.js").contains("serviceWorker.register(\"/sw.js\")"));
    }

    @Test void workerNeverInterceptsTheApi() throws Exception {
        assertTrue(leer("/sw.js").contains("url.pathname.startsWith(\"/api/\")"));
    }
}
