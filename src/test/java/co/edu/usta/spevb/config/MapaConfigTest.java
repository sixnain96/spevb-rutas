package co.edu.usta.spevb.config;

import co.edu.usta.spevb.controller.MapaConfigController;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.*;

/** Configuración del mapa del navegador: proveedor, claves separadas, respuesta pública explícita y CSP según el proveedor. */
class MapaConfigTest {
    private static final String CLAVE_NAVEGADOR = "AIzaSyEJEMPLO_NO_ES_UNA_CLAVE_REAL_0123";
    private static final String CLAVE_SERVIDOR = "AIzaSyOTRA_CLAVE_DE_SERVIDOR_NO_REAL_456";

    private static MapaConfig google(String clave, String mapId) { return new MapaConfig("google", clave, mapId, "quarterly", CLAVE_SERVIDOR); }

    @Test void porDefectoEsLeafletYNoEnviaNadaDeGoogle() {
        var c = new MapaConfig("", CLAVE_NAVEGADOR, "MI_MAP_ID", "quarterly", CLAVE_SERVIDOR);
        assertEquals("leaflet", c.proveedor());
        var json = JsonMapper.builder().build().writeValueAsString(c.publica());
        assertTrue(json.contains("\"proveedor\":\"leaflet\""));
        assertFalse(json.contains(CLAVE_NAVEGADOR), "con Leaflet ni siquiera la clave del navegador debe circular");
        assertFalse(json.contains("MI_MAP_ID"));
    }

    @Test void unProveedorDesconocidoImpideArrancarEnVezDeCambiarEnSilencio() {
        var e = assertThrows(IllegalArgumentException.class, () -> new MapaConfig("mapbox", "", "", "quarterly", ""));
        assertTrue(e.getMessage().contains("MAP_PROVIDER"));
    }

    @Test void googleConfiguradoDevuelveSoloLosCamposNecesarios() throws Exception {
        var raiz = JsonMapper.builder().build().readTree(JsonMapper.builder().build().writeValueAsString(google(CLAVE_NAVEGADOR, "MI_MAP_ID").publica()));
        assertEquals(Set.of("proveedor", "google"), nombres(raiz));
        var g = raiz.get("google");
        assertEquals(Set.of("configurado", "claveNavegador", "mapId", "version", "idioma", "region"), nombres(g), "lista explícita: nada de entorno ni de otras propiedades");
        assertTrue(g.get("configurado").asBoolean());
        assertEquals(CLAVE_NAVEGADOR, g.get("claveNavegador").asText());
        assertEquals("es", g.get("idioma").asText());
        assertEquals("CO", g.get("region").asText());
        assertFalse(raiz.toString().contains(CLAVE_SERVIDOR), "la clave del servidor nunca sale de Java");
    }

    @Test void sinClaveOSinMapIdNoEstaConfiguradoYNoSeCambiaDeProveedor() {
        assertEquals("google", google("", "MI_MAP_ID").proveedor());
        assertFalse(google("", "MI_MAP_ID").googleConfigurado());
        assertFalse(google(CLAVE_NAVEGADOR, "").googleConfigurado());
        assertTrue(google(CLAVE_NAVEGADOR, "MI_MAP_ID").googleConfigurado());
    }

    @Test void valoresConFormatoRaroSeIgnoranYNuncaLlegaranATransformarseEnUnaUrl() {
        assertFalse(google("clave con espacios&callback=x", "MI_MAP_ID").googleConfigurado());
        assertFalse(google(CLAVE_NAVEGADOR, "map id raro;").googleConfigurado());
        assertEquals("quarterly", new MapaConfig("google", CLAVE_NAVEGADOR, "ID", "latest<script>", "").publica().google().version());
        assertEquals("3.66", new MapaConfig("google", CLAVE_NAVEGADOR, "ID", "3.66", "").publica().google().version());
    }

    @Test void elEndpointPublicoDevuelveLaMismaRespuesta() {
        var c = google(CLAVE_NAVEGADOR, "MI_MAP_ID");
        assertEquals(c.publica(), new MapaConfigController(c).mapa());
    }

    @Test void laPoliticaDeContenidoDependeDelProveedorYCoincideConLasReferencias() throws Exception {
        assertEquals(referencia("leaflet"), ContentSecurityPolicy.para("leaflet"));
        assertEquals(referencia("google"), ContentSecurityPolicy.para("google"));
        String l = ContentSecurityPolicy.leaflet(), g = ContentSecurityPolicy.google();
        assertTrue(l.contains("tiles.openfreemap.org") && l.contains("server.arcgisonline.com"));
        assertFalse(l.contains("maps.googleapis.com") || l.contains("googleapis.com"), "en modo Leaflet no se autoriza el SDK de Google");
        assertTrue(l.contains("https://accounts.google.com/gsi/client"), "el botón de inicio de sesión sigue funcionando");
        for (String host : new String[]{"tiles.openfreemap.org", "server.arcgisonline.com", "tile.openstreetmap.org"})
            assertFalse(g.contains(host), "en modo Google no se autoriza " + host);
        assertTrue(g.contains("https://*.googleapis.com") && g.contains("https://*.gstatic.com") && g.contains("https://fonts.gstatic.com"));
        assertTrue(g.contains("https://accounts.google.com/gsi/client"));
        for (String p : new String[]{l, g}) {
            String script = directiva(p, "script-src");
            assertFalse(script.contains("'unsafe-inline'"), "SEVB no tiene scripts en línea: no se permite 'unsafe-inline' en script-src");
            assertTrue(p.contains("object-src 'none'") && p.contains("frame-ancestors 'none'") && p.contains("base-uri 'self'") && p.contains("form-action 'self'"));
            assertTrue(p.startsWith("default-src 'self';"));
        }
        assertFalse(directiva(l, "script-src").contains("'unsafe-eval'"), "Leaflet no necesita 'unsafe-eval'");
    }

    @Test void elFiltroEnvíaLaPoliticaDelProveedorElegido() throws Exception {
        for (String proveedor : new String[]{"leaflet", "google"}) {
            var resp = new MockHttpServletResponse();
            new EditorSecurityFilter(false, "", proveedor).doFilter(new MockHttpServletRequest("GET", "/"), resp, new MockFilterChain());
            assertEquals(ContentSecurityPolicy.para(proveedor), resp.getHeader("Content-Security-Policy"));
        }
    }

    private static Set<String> nombres(tools.jackson.databind.JsonNode n) {
        Set<String> s = new TreeSet<>();
        n.propertyNames().forEach(s::add);
        return s;
    }
    private static String directiva(String politica, String nombre) {
        for (String d : politica.split(";")) if (d.trim().startsWith(nombre + " ")) return d.trim();
        return "";
    }
    private static String referencia(String nombre) throws Exception {
        try (var in = MapaConfigTest.class.getResourceAsStream("/csp/" + nombre + ".txt")) {
            assertNotNull(in, "falta csp/" + nombre + ".txt");
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
