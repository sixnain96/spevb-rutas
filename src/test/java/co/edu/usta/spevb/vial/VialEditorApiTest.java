package co.edu.usta.spevb.vial;

import co.edu.usta.spevb.config.CityArea;
import co.edu.usta.spevb.config.EditorSecurityFilter;
import co.edu.usta.spevb.controller.VialController;
import co.edu.usta.spevb.exception.GlobalExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static co.edu.usta.spevb.vial.Sinteticas.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Endpoints del editor sobre la red vial para buses y auditoría de calidad: solo lectura/propuestas, protegidos, con red sintética. */
class VialEditorApiTest {
    private static final String KEY = "0123456789abcdef0123456789abcdef";
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private RedVialBusService vial;
    private MockMvc mvc;
    private List<CalidadRutasService.RutaPuntos> rutas;

    @BeforeEach void preparar() {
        var red = new RedVialBus(List.of(
                recta(1, 0, 0, 600, 0, 10, "highway", "residential"),
                recta(2, 0, 5, 600, 5, 10, "highway", "footway"),           // paralela peatonal: nunca cuenta
                recta(3, 600, 0, 600, 600, 10, "highway", "secondary")));
        vial = mock(RedVialBusService.class);
        when(vial.red()).thenReturn(red);
        when(vial.matcher()).thenReturn(new MapMatchingBus(red));
        when(vial.opcionesPorDefecto()).thenReturn(new MapMatchingBus.Opciones(30, 500, 10, true));
        when(vial.maxSnapMetros()).thenReturn(30.0);
        when(vial.estado()).thenReturn(new RedVialBusService.Estado(RedVialBusService.Fase.LISTA, red.nodos(), red.aristas(), RedVialBusService.AVISO, "sintética", Instant.now()));
        rutas = new ArrayList<>();
        rutas.add(new CalidadRutasService.RutaPuntos(1, "OK1", "Sobre la vía", conRuido(recorrer(10, new double[]{0, 0}, new double[]{600, 0}), new double[]{2, -2, 3, -3})));
        rutas.add(new CalidadRutasService.RutaPuntos(2, "LEJOS", "En medio de la nada", recorrer(10, new double[]{0, 300}, new double[]{600, 300})));
        mvc = construir(vial, rutas, true);
    }

    private MockMvc construir(RedVialBusService servicioVial, List<CalidadRutasService.RutaPuntos> lista, boolean editorActivo) {
        var fuente = new CalidadRutasService.FuenteRutas() {
            public List<CalidadRutasService.RutaPuntos> todas() { return lista; }
            public Optional<CalidadRutasService.RutaPuntos> porId(long id) { return lista.stream().filter((r) -> r.id() == id).findFirst(); }
        };
        var calidad = new CalidadRutasService(fuente, servicioVial::matcher, servicioVial::opcionesPorDefecto);
        var validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        return MockMvcBuilders.standaloneSetup(new VialController(servicioVial, calidad, new CityArea("Villavicencio", "Meta", "Colombia", 3.95, 4.35, -73.85, -73.45)))
                .setControllerAdvice(new GlobalExceptionHandler()).setValidator(validator).addFilters(new EditorSecurityFilter(editorActivo, KEY)).build();
    }

    private JsonNode enviar(String url, String cuerpo) throws Exception {
        String s = mvc.perform(post(url).header("X-Editor-Token", KEY).contentType(MediaType.APPLICATION_JSON).content(cuerpo)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return JSON.readTree(s);
    }

    private static String punto(double x, double y, String extra) { var p = p(x, y); return "{\"lat\":" + p[0] + ",\"lng\":" + p[1] + extra + "}"; }

    @Test void candidatosMuestranDistanciaAvisoYNuncaLaViaPeatonal() throws Exception {
        var r = enviar("/api/editor/vial/candidatos", punto(300, 8, ",\"maxMetros\":30"));
        assertTrue(r.get("aviso").asText().contains("Requiere revisión del equipo"));
        var c = r.get("candidatos");
        assertEquals(1, c.size(), "solo la calle residencial: la peatonal paralela no cuenta");
        assertEquals(8.0, c.get(0).get("distanciaM").asDouble(), 0.6);
        assertEquals("residential", c.get(0).get("highway").asText());
    }

    @Test void fueraDelLimiteNoHayCandidatosPeroSeInformaLaMasCercana() throws Exception {
        var r = enviar("/api/editor/vial/candidatos", punto(300, 80, ",\"maxMetros\":30"));
        assertEquals(0, r.get("candidatos").size());
        assertEquals(80.0, r.get("masCercanaM").asDouble(), 1.0);
        // el límite se acota a 100 m aunque se pida más
        assertEquals(100.0, enviar("/api/editor/vial/candidatos", punto(300, 80, ",\"maxMetros\":5000")).get("maxMetros").asDouble());
    }

    @Test void ajustarRutaDevuelvePropuestaYMetricasSinGuardarNada() throws Exception {
        var pts = conRuido(recorrer(10, new double[]{0, 0}, new double[]{600, 0}), new double[]{8, -8});
        var sb = new StringBuilder("{\"puntos\":[");
        for (int i = 0; i < pts.size(); i++) sb.append(i > 0 ? "," : "").append("[").append(pts.get(i)[0]).append(",").append(pts.get(i)[1]).append("]");
        sb.append("],\"anclas\":[5],\"preservarExtremos\":true,\"radioMetros\":30}");
        var r = enviar("/api/editor/vial/ajustar-ruta", sb.toString());
        assertTrue(r.get("propuesta").size() >= pts.size() - 1);
        assertTrue(r.get("tramos").size() >= 1);
        assertTrue(r.get("metricas").get("pctAjustado").asDouble() > 90);
        assertTrue(r.has("problemas") && r.has("requiereRevisionManual"));
        assertTrue(r.get("aviso").asText().contains("Requiere revisión"));
    }

    @Test void sinRedLista503YSeConservaLaGeometria() throws Exception {
        when(vial.red()).thenReturn(null);
        when(vial.matcher()).thenReturn(null);
        when(vial.estado()).thenReturn(new RedVialBusService.Estado(RedVialBusService.Fase.CARGANDO, 0, 0, RedVialBusService.AVISO, "descargando", Instant.now()));
        var cuerpo = punto(10, 0, "");
        mvc.perform(post("/api/editor/vial/candidatos").header("X-Editor-Token", KEY).contentType(MediaType.APPLICATION_JSON).content(cuerpo)).andExpect(status().isServiceUnavailable());
        mvc.perform(get("/api/editor/calidad").header("X-Editor-Token", KEY)).andExpect(status().isServiceUnavailable());
    }

    @Test void exigeClaveYEstaCerradoSiElEditorEstaApagado() throws Exception {
        for (String url : List.of("/api/editor/vial/estado", "/api/editor/calidad", "/api/editor/rutas/1/calidad"))
            mvc.perform(get(url)).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/editor/vial/candidatos").contentType(MediaType.APPLICATION_JSON).content(punto(10, 0, ""))).andExpect(status().isUnauthorized());
        var apagado = construir(vial, rutas, false);
        apagado.perform(get("/api/editor/vial/estado").header("X-Editor-Token", KEY)).andExpect(status().isForbidden());
    }

    @Test void rechazaPuntosInvalidosFueraDeLaCiudadOMasDe10000() throws Exception {
        mvc.perform(post("/api/editor/vial/candidatos").header("X-Editor-Token", KEY).contentType(MediaType.APPLICATION_JSON).content("{\"lat\":10.0,\"lng\":-70.0}")).andExpect(status().isBadRequest());
        mvc.perform(post("/api/editor/vial/ajustar-ruta").header("X-Editor-Token", KEY).contentType(MediaType.APPLICATION_JSON).content("{\"puntos\":[[4.15,-73.63]]}")).andExpect(status().isBadRequest());
        var muchos = new StringBuilder("{\"puntos\":[");
        for (int i = 0; i < 10001; i++) muchos.append(i > 0 ? "," : "").append("[4.15,-73.63]");
        mvc.perform(post("/api/editor/vial/ajustar-ruta").header("X-Editor-Token", KEY).contentType(MediaType.APPLICATION_JSON).content(muchos + "]}")).andExpect(status().isBadRequest());
        mvc.perform(post("/api/editor/vial/ajustar-ruta").header("X-Editor-Token", KEY).contentType(MediaType.APPLICATION_JSON).content("{\"puntos\":[[4.15,-73.63],[10.0,-70.0]]}")).andExpect(status().isBadRequest());
    }

    @Test void auditoriaClasificaLasRutasYNoModificaNada() throws Exception {
        var antes = rutas.stream().map((r) -> r.puntos().size() + ":" + r.puntos().get(0)[0]).toList();
        String s = mvc.perform(get("/api/editor/calidad").header("X-Editor-Token", KEY)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        var j = JSON.readTree(s);
        assertEquals(2, j.get("rutas").size());
        assertEquals("LEJOS", j.get("rutas").get(0).get("codigo").asText(), "las críticas primero");
        assertEquals("CRITICA", j.get("rutas").get(0).get("estado").asText());
        assertEquals("OK1", j.get("rutas").get(1).get("codigo").asText());
        assertEquals("CORRECTA", j.get("rutas").get(1).get("estado").asText(), s);
        assertEquals(1, j.get("correctas").asInt());
        assertEquals(1, j.get("criticas").asInt());
        for (var campo : List.of("id", "codigo", "nombre", "puntos", "longitudM", "pctSobreVias", "desviacionMediaM", "desviacionMaxM", "segmentosSospechosos", "sentidoPosiblementeIncorrecto", "estado"))
            assertTrue(j.get("rutas").get(0).has(campo), "falta " + campo);
        assertTrue(j.get("rutas").get(0).get("primerProblema").has("lat"), "se puede abrir directamente en el primer problema");
        assertEquals(antes, rutas.stream().map((r) -> r.puntos().size() + ":" + r.puntos().get(0)[0]).toList(), "la auditoría no modifica los recorridos");
        mvc.perform(get("/api/editor/rutas/2/calidad").header("X-Editor-Token", KEY)).andExpect(status().isOk());
        mvc.perform(get("/api/editor/rutas/99/calidad").header("X-Editor-Token", KEY)).andExpect(status().isNotFound());
    }

    @Test void unaRutaConUnTramoSinViaRequiereRevisionYNoEsCritica() throws Exception {
        var pts = new ArrayList<>(recorrer(10, new double[]{0, 0}, new double[]{600, 0}));
        pts.addAll(recorrer(10, new double[]{600, 20}, new double[]{600, 300}));       // sobre la vía secundaria
        for (int i = 0; i < 4; i++) pts.set(30 + i, p(300, 200));                         // 4 puntos descolgados en medio de la nada
        rutas.add(new CalidadRutasService.RutaPuntos(3, "MIXTA", "Con un tramo dudoso", pts));
        var j = JSON.readTree(mvc.perform(get("/api/editor/rutas/3/calidad").header("X-Editor-Token", KEY)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertNotEquals("CORRECTA", j.get("estado").asText(), j.toString());
        assertTrue(j.get("segmentosSospechosos").asInt() >= 1);
    }
}
