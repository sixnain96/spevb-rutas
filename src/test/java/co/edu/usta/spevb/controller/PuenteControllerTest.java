package co.edu.usta.spevb.controller;

import co.edu.usta.spevb.config.CityArea;
import co.edu.usta.spevb.repository.PasoPeatonalRepository;
import co.edu.usta.spevb.service.*;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** GET /api/puentes es público: solo id, nombre, metros y trazo. La nota interna y cualquier campo administrativo nunca salen. */
class PuenteControllerTest {
    private static final Set<String> CAMPOS_PUBLICOS = Set.of("id", "nombre", "metros", "trazo");
    private static final String TRAZO = "{\"type\":\"LineString\",\"coordinates\":[[-73.6300000,4.1400000],[-73.6290000,4.1400000]]}";

    private MockMvc mvc(List<PasoPeatonalRepository.Fila> filas) {
        var repo = mock(PasoPeatonalRepository.class);
        when(repo.listar()).thenReturn(filas);
        var servicio = new PasoPeatonalService(repo, mock(RedPeatonalService.class), mock(CaminataService.class), mock(ViajeService.class),
                new CityArea("Villavicencio", "Meta", "Colombia", 3.95, 4.35, -73.85, -73.45), JsonMapper.builder().build());
        return MockMvcBuilders.standaloneSetup(new PuenteController(servicio)).build();
    }

    @Test void devuelveExactamenteLosCamposPublicosYNingunaNota() throws Exception {
        var mvc = mvc(List.of(
                new PasoPeatonalRepository.Fila(1, "PUENTE", "Puente de la Esperanza", "NOTA INTERNA: revisar con la alcaldía", TRAZO, 11.2),
                new PasoPeatonalRepository.Fila(2, "PASO", "Atajo", "otra nota", TRAZO, 5),
                new PasoPeatonalRepository.Fila(3, "BLOQUEO", "Canal", null, TRAZO, 7)));
        String json = mvc.perform(get("/api/puentes")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        JsonNode raiz = JsonMapper.builder().build().readTree(json);
        assertEquals(1, raiz.size(), "solo los puentes, no pasos ni bloqueos");
        var campos = new java.util.HashSet<String>();
        raiz.get(0).propertyNames().forEach(campos::add);
        assertEquals(CAMPOS_PUBLICOS, campos, "los campos públicos deben ser exactamente estos");
        assertFalse(json.contains("nota"), "la nota interna no debe filtrarse");
        assertFalse(json.contains("NOTA INTERNA"));
        assertEquals("Puente de la Esperanza", raiz.get(0).get("nombre").asText());
        assertEquals(11, raiz.get(0).get("metros").asInt());
        assertEquals("LineString", raiz.get(0).get("trazo").get("type").asText(), "el trazo conserva el formato GeoJSON del frontend");
        assertEquals(2, raiz.get(0).get("trazo").get("coordinates").size());
    }

    @Test void sinPuentesDevuelveListaVacia() throws Exception {
        String json = mvc(new ArrayList<>()).perform(get("/api/puentes")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertEquals(0, JsonMapper.builder().build().readTree(json).size());
    }

    @Test void laListaAdministrativaSigueIncluyendoLasNotas() {
        var repo = mock(PasoPeatonalRepository.class);
        when(repo.listar()).thenReturn(List.of(new PasoPeatonalRepository.Fila(1, "PUENTE", "P", "visible para el editor", TRAZO, 3)));
        var servicio = new PasoPeatonalService(repo, mock(RedPeatonalService.class), mock(CaminataService.class), mock(ViajeService.class),
                new CityArea("Villavicencio", "Meta", "Colombia", 3.95, 4.35, -73.85, -73.45), JsonMapper.builder().build());
        assertEquals("visible para el editor", servicio.listar().get(0).get("nota"));
    }
}
