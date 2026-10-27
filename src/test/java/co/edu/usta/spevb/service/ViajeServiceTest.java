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

    private AbordajeService.Candidato cand(long ruta, double fraccion, double caminata) {
        return new AbordajeService.Candidato(ruta, "IDA", 10_000, fraccion, -73.63, 4.14, caminata / 2, caminata, null);
    }

    @Test void choosesBoardingPointWithShortestRealWalkNotTheNearestInStraightLine() {
        var abordaje = mock(AbordajeService.class);
        var withAbordaje = new ViajeService(repository, JsonMapper.builder().build(),
                new CityArea("Villavicencio","Meta","Colombia",3.95,4.35,-73.85,-73.45), abordaje);
        when(repository.planificar(any(), anyInt())).thenReturn(List.of(
                row("R001", 400, true, "SENTIDO_DEL_TRAZADO", "IDA", true)));
        // El punto más cercano en línea recta (fracción 0.10) exige 1.600 m a pie (cerro/caño en medio);
        // otro punto (0.30) exige sólo 500 m. Para bajar también hay dos opciones.
        when(abordaje.evaluar(any(), any(), anyList(), anyInt())).thenReturn(new AbordajeService.Evaluacion(
                List.of(cand(1, 0.10, 1600), cand(1, 0.30, 500)),
                List.of(cand(1, 0.60, 300), cand(1, 0.80, 900)), true));
        when(repository.tramoEntre(eq(1L), eq(0.30), eq(0.60))).thenReturn(new ViajeRepository.TramoRow(
                "{\"type\":\"Point\",\"coordinates\":[-73.64,4.14]}", "{\"type\":\"Point\",\"coordinates\":[-73.62,4.15]}",
                "{\"type\":\"LineString\",\"coordinates\":[[-73.64,4.14],[-73.62,4.15]]}", 3.0));
        var option = withAbordaje.planificar(request()).opciones().getFirst();
        assertEquals(500, option.acercamientoInicioMetros());
        assertEquals(300, option.acercamientoFinMetros());
        assertEquals(800, option.accesoTotalMetros());
        assertTrue(option.caminataPorCalles());
        assertTrue(option.sentidoPermitido());
        verify(repository).tramoEntre(1L, 0.30, 0.60);
    }

    @Test void prefersForwardDirectionEvenIfItNeedsALongerWalk() {
        var abordaje = mock(AbordajeService.class);
        var withAbordaje = new ViajeService(repository, JsonMapper.builder().build(),
                new CityArea("Villavicencio","Meta","Colombia",3.95,4.35,-73.85,-73.45), abordaje);
        when(repository.planificar(any(), anyInt())).thenReturn(List.of(
                row("R001", 100, true, "SENTIDO_INVERSO", "IDA", false)));
        // Bajar en 0.20 estando en 0.50 iría al revés (corto); subir en 0.10 y bajar en 0.20 va en el sentido correcto.
        when(abordaje.evaluar(any(), any(), anyList(), anyInt())).thenReturn(new AbordajeService.Evaluacion(
                List.of(cand(1, 0.50, 100), cand(1, 0.10, 700)),
                List.of(cand(1, 0.20, 400)), true));
        when(repository.tramoEntre(anyLong(), anyDouble(), anyDouble())).thenReturn(new ViajeRepository.TramoRow(
                "{\"type\":\"Point\",\"coordinates\":[-73.64,4.14]}", "{\"type\":\"Point\",\"coordinates\":[-73.62,4.15]}",
                "{\"type\":\"LineString\",\"coordinates\":[[-73.64,4.14],[-73.62,4.15]]}", 1.0));
        var response = withAbordaje.planificar(request());
        assertEquals(1, response.opciones().size());
        assertTrue(response.alternativas().isEmpty());
        verify(repository).tramoEntre(1L, 0.10, 0.20);
    }

    @Test void keepsOriginalRowsWhenNoCandidatesAreAvailable() {
        var abordaje = mock(AbordajeService.class);
        var withAbordaje = new ViajeService(repository, JsonMapper.builder().build(),
                new CityArea("Villavicencio","Meta","Colombia",3.95,4.35,-73.85,-73.45), abordaje);
        when(repository.planificar(any(), anyInt())).thenReturn(List.of(
                row("R001", 100, true, "SENTIDO_DEL_TRAZADO", "IDA", true)));
        when(abordaje.evaluar(any(), any(), anyList(), anyInt())).thenReturn(AbordajeService.Evaluacion.VACIA);
        var option = withAbordaje.planificar(request()).opciones().getFirst();
        assertEquals(100, option.acercamientoInicioMetros());
        assertFalse(option.caminataPorCalles());
    }

    @Test void fineStageMovesBoardingPointToABetterNeighbour() {
        var abordaje = mock(AbordajeService.class);
        var withAbordaje = new ViajeService(repository, JsonMapper.builder().build(),
                new CityArea("Villavicencio","Meta","Colombia",3.95,4.35,-73.85,-73.45), abordaje);
        when(repository.planificar(any(), anyInt())).thenReturn(List.of(
                row("R001", 400, true, "SENTIDO_DEL_TRAZADO", "IDA", true)));
        when(abordaje.evaluar(any(), any(), anyList(), anyInt())).thenReturn(new AbordajeService.Evaluacion(
                List.of(cand(1, 0.30, 500)), List.of(cand(1, 0.60, 300)), true));
        // Etapa fina: un punto 70 m más adelante cuesta sólo 380 m a pie.
        when(abordaje.evaluarFracciones(any(), any(), anyMap(), anyMap())).thenReturn(new AbordajeService.Evaluacion(
                List.of(cand(1, 0.307, 380)), List.of(cand(1, 0.593, 210)), true));
        when(repository.tramoEntre(anyLong(), anyDouble(), anyDouble())).thenReturn(new ViajeRepository.TramoRow(
                "{\"type\":\"Point\",\"coordinates\":[-73.64,4.14]}", "{\"type\":\"Point\",\"coordinates\":[-73.62,4.15]}",
                "{\"type\":\"LineString\",\"coordinates\":[[-73.64,4.14],[-73.62,4.15]]}", 3.0));
        var option = withAbordaje.planificar(request()).opciones().getFirst();
        assertEquals(380, option.acercamientoInicioMetros());
        assertEquals(210, option.acercamientoFinMetros());
        verify(repository).tramoEntre(1L, 0.307, 0.593);
    }

    @Test void fineStageFailureKeepsTheCoarseChoice() {
        var abordaje = mock(AbordajeService.class);
        var withAbordaje = new ViajeService(repository, JsonMapper.builder().build(),
                new CityArea("Villavicencio","Meta","Colombia",3.95,4.35,-73.85,-73.45), abordaje);
        when(repository.planificar(any(), anyInt())).thenReturn(List.of(
                row("R001", 400, true, "SENTIDO_DEL_TRAZADO", "IDA", true)));
        when(abordaje.evaluar(any(), any(), anyList(), anyInt())).thenReturn(new AbordajeService.Evaluacion(
                List.of(cand(1, 0.30, 500)), List.of(cand(1, 0.60, 300)), true));
        when(abordaje.evaluarFracciones(any(), any(), anyMap(), anyMap())).thenThrow(new IllegalStateException("fallo"));
        when(repository.tramoEntre(anyLong(), anyDouble(), anyDouble())).thenReturn(new ViajeRepository.TramoRow(
                "{\"type\":\"Point\",\"coordinates\":[-73.64,4.14]}", "{\"type\":\"Point\",\"coordinates\":[-73.62,4.15]}",
                "{\"type\":\"LineString\",\"coordinates\":[[-73.64,4.14],[-73.62,4.15]]}", 3.0));
        var option = withAbordaje.planificar(request()).opciones().getFirst();
        assertEquals(500, option.acercamientoInicioMetros());
        verify(repository).tramoEntre(1L, 0.30, 0.60);
    }

    private AbordajeService.Candidato parada(long ruta, double fraccion, double caminata, String nombre) {
        return new AbordajeService.Candidato(ruta, "IDA", 10_000, fraccion, -73.6310, 4.1405, caminata / 2, caminata, nombre);
    }

    private ViajeService conAbordaje(AbordajeService abordaje) {
        return new ViajeService(repository, JsonMapper.builder().build(),
                new CityArea("Villavicencio","Meta","Colombia",3.95,4.35,-73.85,-73.45), abordaje);
    }

    private void tramoCualquiera() {
        when(repository.tramoEntre(anyLong(), anyDouble(), anyDouble())).thenReturn(new ViajeRepository.TramoRow(
                "{\"type\":\"Point\",\"coordinates\":[-73.64,4.14]}", "{\"type\":\"Point\",\"coordinates\":[-73.62,4.15]}",
                "{\"type\":\"LineString\",\"coordinates\":[[-73.64,4.14],[-73.62,4.15]]}", 3.0));
    }

    @Test void aRegisteredStopWinsWhenItCostsLessThan25MetresMoreWalking() {
        var abordaje = mock(AbordajeService.class);
        when(repository.planificar(any(), anyInt())).thenReturn(List.of(row("R001", 400, true, "SENTIDO_DEL_TRAZADO", "IDA", true)));
        // Punto libre a 480 m, paradero «Calle 40» a 500 m (20 m más): se prefiere el paradero.
        when(abordaje.evaluar(any(), any(), anyList(), anyInt())).thenReturn(new AbordajeService.Evaluacion(
                List.of(cand(1, 0.30, 480), parada(1, 0.31, 500, "Calle 40")), List.of(cand(1, 0.60, 300)), true));
        tramoCualquiera();
        var option = conAbordaje(abordaje).planificar(request()).opciones().getFirst();
        assertEquals("Calle 40", option.paraderoSubida());
        assertNull(option.paraderoBajada());
        assertEquals(-73.6310, option.puntoSubida().path("coordinates").get(0).asDouble(), 1e-9, "el punto mostrado es el del paradero");
        verify(repository).tramoEntre(1L, 0.31, 0.60);
    }

    @Test void aStopMuchFurtherAwayDoesNotWin() {
        var abordaje = mock(AbordajeService.class);
        when(repository.planificar(any(), anyInt())).thenReturn(List.of(row("R001", 400, true, "SENTIDO_DEL_TRAZADO", "IDA", true)));
        when(abordaje.evaluar(any(), any(), anyList(), anyInt())).thenReturn(new AbordajeService.Evaluacion(
                List.of(cand(1, 0.30, 480), parada(1, 0.31, 700, "Lejos")), List.of(cand(1, 0.60, 300)), true));
        tramoCualquiera();
        assertNull(conAbordaje(abordaje).planificar(request()).opciones().getFirst().paraderoSubida());
    }

    @Test void whenWalkingIsMuchLongerThanStraightLineMoreCandidatesAreRequested() {
        var abordaje = mock(AbordajeService.class);
        when(repository.planificar(any(), anyInt())).thenReturn(List.of(row("R001", 400, true, "SENTIDO_DEL_TRAZADO", "IDA", true)));
        // recta = 800/2 = 400 m ... caminata 1.500 m (> 1,6 veces): hay una barrera
        when(abordaje.evaluar(any(), any(), anyList(), anyInt())).thenReturn(new AbordajeService.Evaluacion(
                List.of(new AbordajeService.Candidato(1, "IDA", 10_000, 0.30, -73.63, 4.14, 400, 1500, null)),
                List.of(cand(1, 0.60, 300)), true));
        when(abordaje.evaluar(any(), any(), anyList(), eq(4), eq(120))).thenReturn(new AbordajeService.Evaluacion(
                List.of(cand(1, 0.35, 520)), List.of(cand(1, 0.60, 300)), true));
        tramoCualquiera();
        var option = conAbordaje(abordaje).planificar(request()).opciones().getFirst();
        assertEquals(520, option.acercamientoInicioMetros(), "se encontró un acceso mucho mejor tras ensanchar la búsqueda");
        verify(abordaje).evaluar(any(), any(), anyList(), eq(4), eq(120));
    }

    @Test void whenWalkingIsReasonableNoExtraCandidatesAreRequested() {
        var abordaje = mock(AbordajeService.class);
        when(repository.planificar(any(), anyInt())).thenReturn(List.of(row("R001", 400, true, "SENTIDO_DEL_TRAZADO", "IDA", true)));
        // caminata 500 m con 450 m en línea recta (1,1 veces): sin barrera
        when(abordaje.evaluar(any(), any(), anyList(), anyInt())).thenReturn(new AbordajeService.Evaluacion(
                List.of(new AbordajeService.Candidato(1, "IDA", 10_000, 0.30, -73.63, 4.14, 450, 500, null)),
                List.of(new AbordajeService.Candidato(1, "IDA", 10_000, 0.60, -73.63, 4.14, 280, 300, null)), true));
        tramoCualquiera();
        conAbordaje(abordaje).planificar(request());
        verify(abordaje, never()).evaluar(any(), any(), anyList(), anyInt(), anyInt());
    }

    @Test void theSamePlanRequestedTwiceIsServedFromTheCache() {
        when(repository.planificar(any(), anyInt())).thenReturn(List.of(
                row("R001", 100, true, "SENTIDO_DEL_TRAZADO", "IDA", true)));
        service.planificar(request());
        service.planificar(request());
        verify(repository, times(1)).planificar(any(), anyInt());
        service.invalidarCache();
        service.planificar(request());
        verify(repository, times(2)).planificar(any(), anyInt());
    }

    @Test void fineStageIsSkippedWhenBothWalksAreAlreadyVeryShort() {
        var abordaje = mock(AbordajeService.class);
        when(repository.planificar(any(), anyInt())).thenReturn(List.of(row("R001", 100, true, "SENTIDO_DEL_TRAZADO", "IDA", true)));
        when(abordaje.evaluar(any(), any(), anyList(), anyInt())).thenReturn(new AbordajeService.Evaluacion(
                List.of(new AbordajeService.Candidato(1, "IDA", 10_000, 0.30, -73.63, 4.14, 90, 120, null)),
                List.of(new AbordajeService.Candidato(1, "IDA", 10_000, 0.60, -73.63, 4.14, 150, 180, null)), true));
        tramoCualquiera();
        conAbordaje(abordaje).planificar(request());
        verify(abordaje, never()).evaluarFracciones(any(), any(), anyMap(), anyMap());
    }
}
