package co.edu.usta.spevb.controller;

import co.edu.usta.spevb.config.EditorSecurityFilter;
import co.edu.usta.spevb.exception.*;
import co.edu.usta.spevb.service.LugarService;
import co.edu.usta.spevb.service.RutaService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class RutaApiTest {
    private static final String KEY="0123456789abcdef0123456789abcdef";
    private RutaService service;
    private MockMvc mvc;

    @BeforeEach void setup() {
        service=mock(RutaService.class);
        var lugares=mock(LugarService.class);
        var validator=new LocalValidatorFactoryBean(); validator.afterPropertiesSet();
        mvc=MockMvcBuilders.standaloneSetup(new RutaController(service,lugares))
                .setControllerAdvice(new GlobalExceptionHandler()).setValidator(validator)
                .addFilters(new EditorSecurityFilter(true,KEY)).build();
    }

    private String validUpdate() {
        return """
                {"recorrido":{"type":"LineString","coordinates":[[-73.62,4.14],[-73.63,4.15]]},
                 "nombre":"Ruta","origen":"Inicio","destino":"Fin","sentido":"IDA",
                 "descripcion":"Manual","color":"#176650","version":0,"validada":false}
                """;
    }

    @Test void malformedJsonReturns400() throws Exception {
        mvc.perform(put("/api/editor/rutas/1/recorrido").header("X-Editor-Token",KEY)
                .contentType("application/json").content("{"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value("El cuerpo de la solicitud debe ser JSON válido."));
        verifyNoInteractions(service);
    }

    @Test void missingVersionAndBlankNameAreRejectedBeforeService() throws Exception {
        mvc.perform(put("/api/editor/rutas/1/recorrido").header("X-Editor-Token",KEY).contentType("application/json")
                .content("{\"recorrido\":{\"type\":\"LineString\",\"coordinates\":[[-73.62,4.14],[-73.63,4.15]]},\"nombre\":\"\",\"origen\":\"Inicio\",\"destino\":\"Fin\",\"sentido\":\"IDA\",\"color\":\"#176650\"}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }

    @Test void conflictReturns409WithoutLeakingInternals() throws Exception {
        doThrow(new ConflictoVersionException()).when(service).actualizarRecorrido(eq(1L),any());
        mvc.perform(put("/api/editor/rutas/1/recorrido").header("X-Editor-Token",KEY).contentType("application/json").content(validUpdate()))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.status").value(409));
    }

    @Test void validAuthenticatedWriteReachesService() throws Exception {
        mvc.perform(put("/api/editor/rutas/1/recorrido").header("X-Editor-Token",KEY).contentType("application/json").content(validUpdate()))
                .andExpect(status().isOk());
        verify(service).actualizarRecorrido(eq(1L),any());
    }

    @Test void missingRouteReturns404() throws Exception {
        when(service.obtenerRuta(88L)).thenThrow(new RutaNoEncontradaException(88L));
        mvc.perform(get("/api/rutas/88")).andExpect(status().isNotFound()).andExpect(jsonPath("$.status").value(404));
    }

    @Test void missingAdminKeyCannotWrite() throws Exception {
        mvc.perform(put("/api/editor/rutas/1/recorrido").contentType("application/json").content("{}"))
                .andExpect(status().isUnauthorized()); verifyNoInteractions(service);
    }
}
