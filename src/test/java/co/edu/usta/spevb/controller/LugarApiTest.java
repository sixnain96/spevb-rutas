package co.edu.usta.spevb.controller;

import co.edu.usta.spevb.config.EditorSecurityFilter;
import co.edu.usta.spevb.dto.LugarResponse;
import co.edu.usta.spevb.exception.GlobalExceptionHandler;
import co.edu.usta.spevb.service.LugarService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

import java.util.List;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class LugarApiTest {
    private static final String KEY="0123456789abcdef0123456789abcdef";
    private LugarService service;
    private MockMvc mvc;

    @BeforeEach void setup() {
        service=mock(LugarService.class);
        var validator=new LocalValidatorFactoryBean();validator.afterPropertiesSet();
        mvc=MockMvcBuilders.standaloneSetup(new LugarController(service)).setControllerAdvice(new GlobalExceptionHandler())
                .setValidator(validator).addFilters(new EditorSecurityFilter(true,KEY)).build();
    }

    @Test void rejectsShortQuery() throws Exception {
        mvc.perform(get("/api/lugares").param("q","ab")).andExpect(status().isBadRequest());
    }

    @Test void returnsLocalPlace() throws Exception {
        when(service.buscarDetallado("Galán", "")).thenReturn(new LugarService.ResultadoBusqueda(
                List.of(new LugarResponse(1L,"Sector Galán","Galán, Villavicencio",4.145,-73.654,"Catálogo local",true)),
                List.of(), true, false));
        mvc.perform(get("/api/lugares").param("q","Galán")).andExpect(status().isOk())
                .andExpect(jsonPath("$.lugares[0].nombre").value("Sector Galán"));
    }

    @Test void suggestionsAcceptThreeLettersAndReturnSafeData() throws Exception {
        when(service.arcgisActivo()).thenReturn(true);
        when(service.sugerir("Uni")).thenReturn(List.of(
                new co.edu.usta.spevb.dto.SugerenciaResponse("Unicentro Villavicencio Centro Comercial", "ArcGIS",
                        "magicKeyPrueba", null, null, null, "ArcGIS World Geocoding", null)));
        mvc.perform(get("/api/lugares/sugerencias").param("q", "Uni"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sugerencias[0].texto")
                        .value("Unicentro Villavicencio Centro Comercial"))
                .andExpect(jsonPath("$.arcgisConfigurado").value(true));
    }

    @Test void adminWriteRequiresKey() throws Exception {
        mvc.perform(post("/api/editor/lugares").contentType("application/json")
                .content("{\"nombre\":\"Casa\",\"lat\":4.14,\"lng\":-73.62}"))
                .andExpect(status().isUnauthorized());
    }

    @Test void adminWriteWithKeyReachesService() throws Exception {
        when(service.crear(any())).thenReturn(new LugarResponse(2L,"Casa","Referencia",4.14,-73.62,"Manual",true));
        mvc.perform(post("/api/editor/lugares").header("X-Editor-Token",KEY).contentType("application/json")
                .content("{\"nombre\":\"Casa\",\"direccion\":\"Referencia\",\"lat\":4.14,\"lng\":-73.62}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.confirmado").value(true));
    }

    @Test void localSuggestionsCarryCoordinatesAndWorkWithoutArcgis() throws Exception {
        when(service.arcgisActivo()).thenReturn(false);
        when(service.direccionesLocales()).thenReturn(true);
        when(service.sugerir("Carrera 48 #19")).thenReturn(List.of(
                new co.edu.usta.spevb.dto.SugerenciaResponse("Carrera 48 #19-15", "El Barzal", null, 4.1401, -73.6302, 77L,
                        "Catastro de Villavicencio (base propia)", "PREDIO_APROXIMADO")));
        mvc.perform(get("/api/lugares/sugerencias").param("q", "Carrera 48 #19"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sugerencias[0].lat").value(4.1401))
                .andExpect(jsonPath("$.sugerencias[0].id").value(77))
                .andExpect(jsonPath("$.direccionesLocales").value(true))
                .andExpect(jsonPath("$.arcgisConfigurado").value(false));
    }

    @Test void confirmationIsRateLimitedAndValidated() throws Exception {
        when(service.confirmarDireccion(anyString(), anyDouble(), anyDouble())).thenReturn(true);
        mvc.perform(post("/api/lugares/confirmaciones").contentType("application/json")
                .content("{\"consulta\":\"Carrera 48 #19-15\",\"lat\":4.14,\"lng\":-73.63}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.ok").value(true));
        mvc.perform(post("/api/lugares/confirmaciones").contentType("application/json")
                .content("{\"consulta\":\"" + "x".repeat(200) + "\",\"lat\":4.14,\"lng\":-73.63}"))
                .andExpect(status().isBadRequest());
    }
}
