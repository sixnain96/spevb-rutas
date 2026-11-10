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
import static org.mockito.ArgumentMatchers.*;
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

    @Test void returnsGooglePlaceWithStateAndProvider() throws Exception {
        when(service.buscarDetallado("Galán", "", null)).thenReturn(new LugarService.ResultadoBusqueda(
                List.of(new LugarResponse(null,"Sector Galán","Galán, Villavicencio",4.145,-73.654,"Google Maps",false,"SECTOR")),
                List.of(), LugarService.OK));
        mvc.perform(get("/api/lugares").param("q","Galán")).andExpect(status().isOk())
                .andExpect(jsonPath("$.lugares[0].nombre").value("Sector Galán"))
                .andExpect(jsonPath("$.estado").value("OK"))
                .andExpect(jsonPath("$.proveedor").value("google"));
    }

    @Test void googleFailureIsRecoverableHttp200WithState() throws Exception {
        when(service.buscarDetallado("Galán", "g:ChIJabcdefghij123", "sesion-abc-123")).thenReturn(new LugarService.ResultadoBusqueda(
                List.of(), List.of("Google no respondió."), LugarService.ERROR_SERVICIO));
        mvc.perform(get("/api/lugares").param("q","Galán").param("magicKey","g:ChIJabcdefghij123").param("sesion","sesion-abc-123"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.estado").value("ERROR_SERVICIO"))
                .andExpect(jsonPath("$.lugares").isEmpty());
    }

    @Test void suggestionsAcceptThreeLettersAndCarryStateAndGoogleOnly() throws Exception {
        when(service.sugerir("Uni", "sesion-abc-123")).thenReturn(new LugarService.ResultadoSugerencias(LugarService.OK, List.of(
                new co.edu.usta.spevb.dto.SugerenciaResponse("Unicentro Villavicencio Centro Comercial", "Villavicencio, Meta",
                        "g:ChIJabcdefghij123", null, null, null, "Google Maps", null))));
        mvc.perform(get("/api/lugares/sugerencias").param("q", "Uni").param("sesion", "sesion-abc-123"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.estado").value("OK"))
                .andExpect(jsonPath("$.proveedor").value("google"))
                .andExpect(jsonPath("$.sugerencias[0].texto").value("Unicentro Villavicencio Centro Comercial"))
                .andExpect(jsonPath("$.sugerencias[0].magicKey").value("g:ChIJabcdefghij123"))
                .andExpect(jsonPath("$.arcgisConfigurado").doesNotExist());
    }

    @Test void invalidSessionTokenIsDroppedNotForwarded() throws Exception {
        when(service.sugerir(eq("Uni"), isNull())).thenReturn(new LugarService.ResultadoSugerencias(LugarService.SIN_RESULTADOS, List.of()));
        mvc.perform(get("/api/lugares/sugerencias").param("q", "Uni").param("sesion", "../../etc"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.estado").value("SIN_RESULTADOS"));
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
