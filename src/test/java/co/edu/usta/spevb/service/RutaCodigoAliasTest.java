package co.edu.usta.spevb.service;

import co.edu.usta.spevb.config.CityArea;
import co.edu.usta.spevb.controller.RutaController;
import co.edu.usta.spevb.dto.ActualizarRecorridoRequest;
import co.edu.usta.spevb.exception.ConflictoVersionException;
import co.edu.usta.spevb.exception.GlobalExceptionHandler;
import co.edu.usta.spevb.repository.RutaRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Cambio de código de una ruta (RX009…), alias del código anterior, duplicados, confirmación y control de versión. */
class RutaCodigoAliasTest {
    private RutaRepository repo;
    private RutaService service;
    private final JsonMapper mapper = JsonMapper.builder().build();
    private final String linea = "{\"type\":\"LineString\",\"coordinates\":[[-73.62,4.14],[-73.63,4.15]]}";

    @BeforeEach void preparar() {
        repo = mock(RutaRepository.class);
        service = new RutaService(repo, mapper, new GeometriaValidator(), new CityArea("Villavicencio", "Meta", "Colombia", 3.95, 4.35, -73.85, -73.45));
        when(repo.findById(1L)).thenReturn(Optional.of(fila("R001", true)));
        when(repo.actualizarRecorrido(eq(1L), anyString(), anyString(), anyString(), anyString(), anyString(), any(), anyString(), any(), anyBoolean(), eq(3L), any())).thenReturn(true);
    }

    private RutaRepository.RutaDetalleRow fila(String codigo, boolean activa) {
        return new RutaRepository.RutaDetalleRow(1L, codigo, "Ruta", "Inicio", "Fin", "IDA", "", "#1565C0", BigDecimal.ONE, activa, false, false, "GPS",
                "{\"type\":\"Point\",\"coordinates\":[-73.62,4.14]}", "{\"type\":\"Point\",\"coordinates\":[-73.63,4.15]}", linea, 3, "2026-10-01T00:00:00Z", null);
    }

    private ActualizarRecorridoRequest pedido(long version, String codigo, Boolean activa, Boolean confirmar) {
        return new ActualizarRecorridoRequest(mapper.readTree(linea), "Ruta", "Inicio", "Fin", "IDA", "", "#176650", "GPS", false, version, null, codigo, activa, confirmar);
    }

    @Test void cambiaRx009ArchivaLaVersionDejaAliasYConservaElId() {
        service.actualizarRecorrido(1L, pedido(3, "rx009", null, true));
        InOrder o = inOrder(repo);
        o.verify(repo).archivar(1L, 3);
        o.verify(repo).registrarAlias("R001", 1L);
        o.verify(repo).borrarAlias("RX009", 1L);
        o.verify(repo).cambiarCodigo(1L, "RX009");
        o.verify(repo).actualizarRecorrido(eq(1L), anyString(), anyString(), anyString(), anyString(), anyString(), any(), anyString(), any(), anyBoolean(), eq(3L), any());
    }

    @Test void sinConfirmacionNoCambiaElCodigoNiEscribeNada() {
        var e = assertThrows(IllegalArgumentException.class, () -> service.actualizarRecorrido(1L, pedido(3, "RX009", null, false)));
        assertTrue(e.getMessage().contains("R001") && e.getMessage().contains("RX009"), "muestra código anterior y nuevo: " + e.getMessage());
        verify(repo, never()).archivar(anyLong(), anyLong());
        verify(repo, never()).cambiarCodigo(anyLong(), anyString());
    }

    @Test void rechazaUnCodigoDuplicado() {
        when(repo.existeCodigo("R002", 1L)).thenReturn(true);
        var e = assertThrows(IllegalArgumentException.class, () -> service.actualizarRecorrido(1L, pedido(3, "R002", null, true)));
        assertTrue(e.getMessage().contains("Ya existe"));
        verify(repo, never()).archivar(anyLong(), anyLong());
        verify(repo, never()).registrarAlias(anyString(), anyLong());
    }

    @Test void rechazaUnCodigoQueEsAliasDeOtraRuta() {
        when(repo.rutaDeAlias("R777")).thenReturn(Optional.of(9L));
        assertThrows(IllegalArgumentException.class, () -> service.actualizarRecorrido(1L, pedido(3, "R777", null, true)));
        verify(repo, never()).cambiarCodigo(anyLong(), anyString());
    }

    @Test void puedeVolverAUnCodigoQueFueAliasDeLaMismaRuta() {
        when(repo.rutaDeAlias("R000")).thenReturn(Optional.of(1L));
        service.actualizarRecorrido(1L, pedido(3, "R000", null, true));
        verify(repo).borrarAlias("R000", 1L);
        verify(repo).cambiarCodigo(1L, "R000");
    }

    @Test void conflictoDeVersionSigueDevolviendo409ConCambioDeCodigo() {
        assertThrows(ConflictoVersionException.class, () -> service.actualizarRecorrido(1L, pedido(2, "RX009", null, true)));
        verify(repo, never()).cambiarCodigo(anyLong(), anyString());
    }

    @Test void sinCodigoElComportamientoAnteriorNoCambia() {
        service.actualizarRecorrido(1L, pedido(3, null, null, null));
        verify(repo, never()).registrarAlias(anyString(), anyLong());
        verify(repo, never()).cambiarCodigo(anyLong(), anyString());
        verify(repo, never()).cambiarActiva(anyLong(), anyBoolean());
        // y el mismo código en otra capitalización tampoco cuenta como cambio
        service.actualizarRecorrido(1L, pedido(3, "r001", null, null));
        verify(repo, never()).cambiarCodigo(anyLong(), anyString());
    }

    @Test void cambiaElEstadoActivaSoloSiDifiere() {
        service.actualizarRecorrido(1L, pedido(3, null, true, null));
        verify(repo, never()).cambiarActiva(anyLong(), anyBoolean());
        service.actualizarRecorrido(1L, pedido(3, null, false, null));
        verify(repo).cambiarActiva(1L, false);
    }

    @Test void elCodigoAnteriorSeResuelveHaciaLaMismaRuta() {
        when(repo.idPorCodigo("R000")).thenReturn(Optional.empty());
        when(repo.rutaDeAlias("R000")).thenReturn(Optional.of(1L));
        when(repo.findById(1L)).thenReturn(Optional.of(fila("RX009", true)));
        var r = service.resolverCodigo("R000").orElseThrow();
        assertEquals(1L, r.id());
        assertEquals("RX009", r.codigo());
        assertTrue(r.aliasUsado());
        when(repo.idPorCodigo("RX009")).thenReturn(Optional.of(1L));
        assertFalse(service.resolverCodigo("RX009").orElseThrow().aliasUsado());
        assertTrue(service.resolverCodigo("NO-EXISTE").isEmpty());
        assertTrue(service.resolverCodigo("../etc").isEmpty(), "formato inválido");
    }

    @Test void elEndpointResuelveAliasYDa404SiNuncaExistio() throws Exception {
        var rutas = mock(RutaService.class);
        when(rutas.resolverCodigo("R000")).thenReturn(Optional.of(new co.edu.usta.spevb.dto.RutaCodigoResponse(1, "RX009", "Ruta", true, "R000")));
        when(rutas.resolverCodigo("R404")).thenReturn(Optional.empty());
        var mvc = MockMvcBuilders.standaloneSetup(new RutaController(rutas, mock(LugarService.class), mock(ParaderoService.class), "test")).setControllerAdvice(new GlobalExceptionHandler()).build();
        mvc.perform(get("/api/rutas/codigo/R000")).andExpect(status().isOk()).andExpect(jsonPath("$.codigo").value("RX009")).andExpect(jsonPath("$.aliasUsado").value(true)).andExpect(jsonPath("$.id").value(1));
        mvc.perform(get("/api/rutas/codigo/R404")).andExpect(status().isNotFound());
    }
}
