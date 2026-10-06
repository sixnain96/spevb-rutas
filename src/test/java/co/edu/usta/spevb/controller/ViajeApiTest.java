package co.edu.usta.spevb.controller;

import co.edu.usta.spevb.config.EditorSecurityFilter;
import co.edu.usta.spevb.dto.ViajeResponse;
import co.edu.usta.spevb.exception.GlobalExceptionHandler;
import co.edu.usta.spevb.service.ViajeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

import java.util.List;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ViajeApiTest {
    private ViajeService service;
    private MockMvc mvc;

    @BeforeEach void setup() {
        service=mock(ViajeService.class);
        var validator=new LocalValidatorFactoryBean();validator.afterPropertiesSet();
        mvc=MockMvcBuilders.standaloneSetup(new ViajeController(service)).setControllerAdvice(new GlobalExceptionHandler())
                .setValidator(validator).addFilters(new EditorSecurityFilter(false,"")).build();
    }

    @Test void publicPlannerDoesNotRequireAdminKey() throws Exception {
        when(service.planificar(any())).thenReturn(new ViajeResponse(List.of(), List.of(), "Solo rutas directas"));
        mvc.perform(post("/api/viajes/planificar").contentType("application/json")
            .content("{\"origen\":{\"lat\":4.14,\"lng\":-73.65},\"destino\":{\"lat\":4.145,\"lng\":-73.624},\"maxCaminataMetros\":1000,\"soloValidadas\":false}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.opciones").isEmpty());
        verify(service).planificar(any());
    }

    @Test void walkingLimitCanBeOmitted() throws Exception {
        when(service.planificar(any())).thenReturn(new ViajeResponse(List.of(), List.of(), "ok"));
        mvc.perform(post("/api/viajes/planificar").contentType("application/json")
            .content("{\"origen\":{\"lat\":4.14,\"lng\":-73.65},\"destino\":{\"lat\":4.145,\"lng\":-73.624},\"soloValidadas\":false}"))
            .andExpect(status().isOk());
        verify(service).planificar(any());
    }

    @Test void rejectsOutOfRangeCoordinatesBeforeService() throws Exception {
        mvc.perform(post("/api/viajes/planificar").contentType("application/json")
            .content("{\"origen\":{\"lat\":91,\"lng\":0},\"destino\":{\"lat\":4.1,\"lng\":-73.6},\"maxCaminataMetros\":1000,\"soloValidadas\":false}"))
            .andExpect(status().isBadRequest());verifyNoInteractions(service);
    }

    @Test void rejectsExcessiveWalkingRadius() throws Exception {
        mvc.perform(post("/api/viajes/planificar").contentType("application/json")
            .content("{\"origen\":{\"lat\":4.1,\"lng\":-73.6},\"destino\":{\"lat\":4.2,\"lng\":-73.6},\"maxCaminataMetros\":9999,\"soloValidadas\":false}"))
            .andExpect(status().isBadRequest());verifyNoInteractions(service);
    }
}
