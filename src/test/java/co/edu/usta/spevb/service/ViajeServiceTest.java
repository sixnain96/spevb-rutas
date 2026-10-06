package co.edu.usta.spevb.service;

import co.edu.usta.spevb.config.CityArea;
import co.edu.usta.spevb.dto.*;
import co.edu.usta.spevb.repository.ViajeRepository;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ViajeServiceTest {
    private final ViajeRepository repository = mock(ViajeRepository.class);
    private final ViajeService service = new ViajeService(repository, JsonMapper.builder().build(),
            new CityArea("Villavicencio","Meta","Colombia",3.95,4.35,-73.85,-73.45));

    private PlanificarViajeRequest request() {
        return new PlanificarViajeRequest(new PlanificarViajeRequest.Punto(4.14,-73.65),
                new PlanificarViajeRequest.Punto(4.145,-73.624),1000,false);
    }

    @Test void noRegisteredConnectionReturnsEmptyListsWithClearScope() {
        when(repository.planificar(any(), anyInt())).thenReturn(List.of());
        var response = service.planificar(request());
        assertTrue(response.opciones().isEmpty());
        assertTrue(response.alternativas().isEmpty());
        assertTrue(response.alcance().contains("rutas registradas"));
    }

    private ViajeRepository.OpcionRow row(String codigo, double inicio, boolean dentroLimite, String sentidoViaje,
                                          String sentidoRuta, boolean sentidoPermitido) {
        return new ViajeRepository.OpcionRow(1, codigo, "Demo", "#176650", false,
                inicio, 150, 2.4, inicio + 150, dentroLimite, sentidoViaje, sentidoRuta, sentidoPermitido,
                "{\"type\":\"Point\",\"coordinates\":[-73.65,4.14]}",
                "{\"type\":\"Point\",\"coordinates\":[-73.624,4.145]}",
                "{\"type\":\"LineString\",\"coordinates\":[[-73.65,4.14],[-73.624,4.145]]}");
    }

    @Test void returnsDirectOptionWithBoardingAndAlightingPoints() {
        when(repository.planificar(any(), anyInt())).thenReturn(List.of(
                row("R001", 100, true, "SENTIDO_DEL_TRAZADO", "IDA", true)));
        var response = service.planificar(request());
        var option = response.opciones().getFirst();
        assertEquals("SENTIDO_DEL_TRAZADO", option.sentidoViaje());
        assertTrue(option.sentidoPermitido());
        assertEquals(100, option.acercamientoInicioMetros());
        assertTrue(option.advertencia().contains("Confirma"));
        assertEquals(2, option.tramo().path("coordinates").size());
        assertTrue(response.alternativas().isEmpty());
    }

    @Test void reverseTripOnOneWayRouteIsOnlyAnAlternative() {
        when(repository.planificar(any(), anyInt())).thenReturn(List.of(
                row("R001", 100, true, "SENTIDO_INVERSO", "IDA", false)));
        var response = service.planificar(request());
        assertTrue(response.opciones().isEmpty());
        var alternative = response.alternativas().getFirst();
        assertFalse(alternative.sentidoPermitido());
        assertEquals("IDA", alternative.sentidoRuta());
        assertTrue(alternative.advertencia().contains("sentido IDA"));
        assertTrue(alternative.advertencia().contains("Invertir sentido"));
    }

    @Test void circularRouteWrappingAroundIsADirectOption() {
        when(repository.planificar(any(), anyInt())).thenReturn(List.of(
                row("C001", 100, true, "SENTIDO_DEL_TRAZADO", "CIRCULAR", true)));
        var response = service.planificar(request());
        assertEquals(1, response.opciones().size());
        assertTrue(response.alternativas().isEmpty());
    }

    @Test void directOptionsKeepRepositoryOrderAndExcludeReverseTrips() {
        when(repository.planificar(any(), anyInt())).thenReturn(List.of(
                row("R002", 50, true, "SENTIDO_DEL_TRAZADO", "IDA", true),
                row("R003", 1200, false, "SENTIDO_DEL_TRAZADO", "REGRESO", true),
                row("R001", 10, true, "SENTIDO_INVERSO", "IDA", false)));
        var response = service.planificar(request());
        assertEquals(List.of("R002", "R003"), response.opciones().stream().map(o -> o.codigo()).toList());
        assertEquals(List.of("R001"), response.alternativas().stream().map(o -> o.codigo()).toList());
    }

    @Test void overWalkingLimitIsStillADirectOption() {
        when(repository.planificar(any(), anyInt())).thenReturn(List.of(
                row("R001", 1200, false, "SENTIDO_DEL_TRAZADO", "IDA", true)));
        var response = service.planificar(request());
        assertEquals(1, response.opciones().size());
        assertTrue(response.alternativas().isEmpty());
    }

    @Test void farAwayRouteIsStillReturnedAsTheNearestOption() {
        when(repository.planificar(any(), anyInt())).thenReturn(List.of(
                row("R001", 8500, false, "SENTIDO_DEL_TRAZADO", "IDA", true)));
        var response = service.planificar(request());
        assertEquals(1, response.opciones().size());
        assertFalse(response.opciones().getFirst().dentroLimite());
        assertTrue(response.alternativas().isEmpty());
        assertTrue(response.alcance().contains("sin importar la distancia"));
    }

    @Test void walkingLimitIsOptional() {
        when(repository.planificar(any(), anyInt())).thenReturn(List.of());
        var request = new PlanificarViajeRequest(new PlanificarViajeRequest.Punto(4.14,-73.65),
                new PlanificarViajeRequest.Punto(4.145,-73.624), null, false);
        assertEquals(PlanificarViajeRequest.CAMINATA_REFERENCIA_METROS, request.caminataReferencia());
        assertNotNull(service.planificar(request));
    }

    @Test void rejectsNonFiniteCoordinate() {
        var request = new PlanificarViajeRequest(new PlanificarViajeRequest.Punto(Double.NaN,-73.6),
                new PlanificarViajeRequest.Punto(4.14,-73.6),1000,false);
        assertThrows(IllegalArgumentException.class, () -> service.planificar(request));
        verifyNoInteractions(repository);
    }

    @Test void rejectsPointOutsideVillavicencio() {
        var request = new PlanificarViajeRequest(new PlanificarViajeRequest.Punto(4.70,-74.05),
                new PlanificarViajeRequest.Punto(4.14,-73.6),1000,false);
        assertThrows(IllegalArgumentException.class, () -> service.planificar(request));
        verifyNoInteractions(repository);
    }
}
