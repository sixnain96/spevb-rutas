package co.edu.usta.spevb.service;

import co.edu.usta.spevb.config.CityArea;
import co.edu.usta.spevb.config.EditorSecurityFilter;
import co.edu.usta.spevb.controller.PasoPeatonalController;
import co.edu.usta.spevb.controller.PuenteController;
import co.edu.usta.spevb.dto.PasoPeatonalActualizarRequest;
import co.edu.usta.spevb.exception.ConflictoVersionException;
import co.edu.usta.spevb.exception.GlobalExceptionHandler;
import co.edu.usta.spevb.repository.PasoPeatonalRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Edición de puentes ya guardados: mismo id, versión optimista, red peatonal reconstruida, cachés invalidadas y nota solo para administración. */
class PuenteEdicionTest {
    private static final String KEY = "0123456789abcdef0123456789abcdef";
    private static final String TRAZO = "{\"type\":\"LineString\",\"coordinates\":[[-73.6300000,4.1400000],[-73.6290000,4.1400000]]}";
    private PasoPeatonalRepository repo;
    private RedPeatonalService red;
    private CaminataService caminata;
    private ViajeService viajes;
    private PasoPeatonalService service;

    @BeforeEach void preparar() {
        repo = mock(PasoPeatonalRepository.class);
        red = mock(RedPeatonalService.class);
        caminata = mock(CaminataService.class);
        viajes = mock(ViajeService.class);
        service = new PasoPeatonalService(repo, red, caminata, viajes, new CityArea("Villavicencio", "Meta", "Colombia", 3.95, 4.35, -73.85, -73.45), JsonMapper.builder().build());
        when(repo.buscar(5L)).thenReturn(Optional.of(new PasoPeatonalRepository.Fila(5, "PUENTE", "Puente viejo", "nota interna", TRAZO, 11, 2)));
        when(repo.actualizar(eq(5L), anyString(), any(), anyString(), eq(2L))).thenReturn(true);
        when(red.aplicarPasos()).thenReturn(Map.of(5L, 2));
    }

    private static List<double[]> puntos(double... lngLat) {
        var l = new ArrayList<double[]>();
        for (int i = 0; i < lngLat.length; i += 2) l.add(new double[]{lngLat[i], lngLat[i + 1]});
        return l;
    }

    @Test void editaConservandoIdYTipoReconstruyeLaRedPeatonalEInvalidaCaches() {
        var r = service.actualizar(5L, new PasoPeatonalActualizarRequest("Puente nuevo", " cambio ", puntos(-73.63, 4.14, -73.6295, 4.1402, -73.629, 4.14), 2L)).orElseThrow();
        assertEquals(5L, r.get("id"));
        assertEquals("PUENTE", r.get("tipo"));
        assertEquals(3L, r.get("version"));
        assertEquals(2, r.get("conectados"));
        verify(repo).actualizar(eq(5L), eq("Puente nuevo"), eq("cambio"), contains("\"LineString\""), eq(2L));
        verify(red).aplicarPasos();
        verify(caminata).invalidarCache();
        verify(viajes).invalidarCache();
        verify(repo, never()).crear(anyString(), anyString(), any(), anyString());
        verify(repo, never()).borrar(anyLong());
    }

    @Test void siOtraPersonaLoModificoDevuelveConflictoYNoTocaLaRed() {
        assertThrows(ConflictoVersionException.class, () -> service.actualizar(5L, new PasoPeatonalActualizarRequest("Puente", null, puntos(-73.63, 4.14, -73.629, 4.14), 1L)));
        verify(repo, never()).actualizar(anyLong(), anyString(), any(), anyString(), anyLong());
        verify(red, never()).aplicarPasos();
        // carrera: la fila cambió entre leer y escribir
        when(repo.actualizar(eq(5L), anyString(), any(), anyString(), eq(2L))).thenReturn(false);
        assertThrows(ConflictoVersionException.class, () -> service.actualizar(5L, new PasoPeatonalActualizarRequest("Puente", null, puntos(-73.63, 4.14, -73.629, 4.14), 2L)));
        verify(red, never()).aplicarPasos();
    }

    @Test void noExisteOTrazoInvalidoNoEscribe() {
        assertTrue(service.actualizar(99L, new PasoPeatonalActualizarRequest("Puente", null, puntos(-73.63, 4.14, -73.629, 4.14), 0L)).isEmpty());
        assertThrows(IllegalArgumentException.class, () -> service.actualizar(5L, new PasoPeatonalActualizarRequest("Puente", null, puntos(-73.63, 4.14, -73.63001, 4.14), 2L)), "menos de 3 m");
        assertThrows(IllegalArgumentException.class, () -> service.actualizar(5L, new PasoPeatonalActualizarRequest("Puente", null, puntos(-70.0, 4.14, -70.001, 4.14), 2L)), "fuera de Villavicencio");
        assertThrows(IllegalArgumentException.class, () -> service.actualizar(5L, new PasoPeatonalActualizarRequest("Puente", null, puntos(-73.63, 4.14, -73.62, 4.14), 2L)), "puente de más de 600 m");
        verify(repo, never()).actualizar(anyLong(), anyString(), any(), anyString(), anyLong());
    }

    @Test void avisaSiUnExtremoQuedoDesconectado() {
        when(red.aplicarPasos()).thenReturn(Map.of(5L, 1));
        var r = service.actualizar(5L, new PasoPeatonalActualizarRequest("Puente", null, puntos(-73.63, 4.14, -73.629, 4.14), 2L)).orElseThrow();
        assertTrue(r.get("mensaje").toString().startsWith("ATENCIÓN"));
    }

    @Test void elEndpointPutExigeClaveYElPublicoSigueSinNota() throws Exception {
        var validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new PasoPeatonalController(service), new PuenteController(service))
                .setControllerAdvice(new GlobalExceptionHandler()).setValidator(validator).addFilters(new EditorSecurityFilter(true, KEY)).build();
        String cuerpo = "{\"nombre\":\"Puente nuevo\",\"nota\":\"secreta\",\"coordenadas\":[[-73.63,4.14],[-73.629,4.14]],\"version\":2}";
        mvc.perform(put("/api/editor/pasos/5").contentType(MediaType.APPLICATION_JSON).content(cuerpo)).andExpect(status().isUnauthorized());
        mvc.perform(put("/api/editor/pasos/5").header("X-Editor-Token", KEY).contentType(MediaType.APPLICATION_JSON).content(cuerpo))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(5)).andExpect(jsonPath("$.version").value(3));
        mvc.perform(put("/api/editor/pasos/5").header("X-Editor-Token", KEY).contentType(MediaType.APPLICATION_JSON).content(cuerpo.replace("\"version\":2", "\"version\":1")))
                .andExpect(status().isConflict());
        mvc.perform(put("/api/editor/pasos/404").header("X-Editor-Token", KEY).contentType(MediaType.APPLICATION_JSON).content(cuerpo)).andExpect(status().isNotFound());
        // el endpoint público de puentes no expone la nota (ni la versión)
        when(repo.listar()).thenReturn(List.of(new PasoPeatonalRepository.Fila(5, "PUENTE", "Puente nuevo", "secreta", TRAZO, 11, 3)));
        String publico = mvc.perform(get("/api/puentes")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertFalse(publico.contains("secreta") || publico.contains("nota") || publico.contains("version"), publico);
        // y la lista administrativa sí conserva la nota y la versión
        assertEquals("secreta", service.listar().get(0).get("nota"));
        assertEquals(3L, service.listar().get(0).get("version"));
    }
}
