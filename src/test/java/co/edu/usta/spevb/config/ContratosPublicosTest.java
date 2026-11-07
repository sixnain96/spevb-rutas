package co.edu.usta.spevb.config;

import org.junit.jupiter.api.Test;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/** Contratos públicos: ningún endpoint desaparece, la administración sigue protegida y el frontend no lleva secretos. */
class ContratosPublicosTest {
    private static final String KEY = "0123456789abcdef0123456789abcdef";

    private static Set<String> endpointsActuales() throws Exception {
        var escaner = new ClassPathScanningCandidateComponentProvider(false);
        escaner.addIncludeFilter(new AnnotationTypeFilter(RestController.class));
        Set<String> r = new TreeSet<>();
        for (var bd : escaner.findCandidateComponents("co.edu.usta.spevb.controller")) {
            Class<?> c = Class.forName(bd.getBeanClassName());
            var base = AnnotatedElementUtils.findMergedAnnotation(c, RequestMapping.class);
            String prefijo = base == null || base.path().length == 0 ? "" : base.path()[0];
            for (var m : c.getDeclaredMethods()) {
                var rm = AnnotatedElementUtils.findMergedAnnotation(m, RequestMapping.class);
                if (rm == null) continue;
                String ruta = rm.path().length == 0 ? "" : rm.path()[0];
                for (var metodo : rm.method()) r.add(metodo.name() + " " + prefijo + ruta);
            }
        }
        return r;
    }

    private static Set<String> lineaBase() throws Exception {
        try (InputStream in = ContratosPublicosTest.class.getResourceAsStream("/contratos/endpoints-4.10.0.txt")) {
            assertNotNull(in);
            Set<String> s = new TreeSet<>();
            for (String l : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\\R")) if (!l.isBlank() && !l.startsWith("#")) s.add(l.trim());
            return s;
        }
    }

    @Test void ningunEndpointPublicoDesaparece() throws Exception {
        var base = lineaBase();
        var actuales = endpointsActuales();
        assertTrue(base.size() >= 55, "línea base incompleta: " + base.size());
        var faltan = new TreeSet<>(base);
        faltan.removeAll(actuales);
        assertTrue(faltan.isEmpty(), "endpoints eliminados: " + faltan);
    }

    @Test void todosLosEndpointsDeAdministracionExigenClaveYEstanCerradosSiElEditorEstaApagado() throws Exception {
        var editor = endpointsActuales().stream().filter((e) -> e.contains(" /api/editor/")).toList();
        assertTrue(editor.size() >= 10, "se esperaban endpoints de administración: " + editor);
        for (String e : editor) {
            String metodo = e.substring(0, e.indexOf(' '));
            String ruta = e.substring(e.indexOf(' ') + 1).replaceAll("\\{[^}]+}", "1");
            for (boolean habilitado : new boolean[]{true, false}) {
                var filtro = new EditorSecurityFilter(habilitado, KEY);
                var req = new MockHttpServletRequest(metodo, ruta);
                req.setRequestURI(ruta);
                req.setContentType("application/json");
                var resp = new MockHttpServletResponse();
                var cadena = new MockFilterChain();
                filtro.doFilter(req, resp, cadena);
                assertNull(cadena.getRequest(), e + " llegó al controlador sin clave (editor " + (habilitado ? "activo" : "apagado") + ")");
                assertEquals(habilitado ? 401 : 403, resp.getStatus(), e);
            }
        }
    }

    @Test void elFrontendNoTraeTokensNiClavesEscritasEnElCodigo() throws Exception {
        Path static_ = Path.of("src/main/resources/static");
        org.junit.jupiter.api.Assumptions.assumeTrue(Files.isDirectory(static_), "sin carpeta static en este entorno");
        var secreto = Pattern.compile("(?i)(token|api[_-]?key|secret|password|clave)\\s*[:=]\\s*[\"'][A-Za-z0-9+/=_\\-]{24,}[\"']");
        var largo = Pattern.compile("\\b(AIza[0-9A-Za-z_\\-]{35}|AA(PK|PT)[A-Za-z0-9_\\-.]{30,}|ghp_[A-Za-z0-9]{30,}|eyJ[A-Za-z0-9_\\-]{20,}\\.eyJ)");
        try (Stream<Path> archivos = Files.walk(static_)) {
            for (Path p : archivos.filter(Files::isRegularFile).filter((f) -> !f.toString().contains("vendor")).filter((f) -> f.toString().matches(".*\\.(js|html|css|webmanifest)$")).toList()) {
                String t = Files.readString(p, StandardCharsets.UTF_8);
                assertFalse(secreto.matcher(t).find(), "posible secreto escrito en " + p);
                assertFalse(largo.matcher(t).find(), "posible credencial conocida en " + p);
            }
        }
    }
}
