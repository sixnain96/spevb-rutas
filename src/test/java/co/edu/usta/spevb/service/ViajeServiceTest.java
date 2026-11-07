package co.edu.usta.spevb.service;

import co.edu.usta.spevb.config.CityArea;
import co.edu.usta.spevb.dto.*;
import co.edu.usta.spevb.repository.ViajeRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ViajeServiceTest {
    private final ViajeRepository repository = mock(ViajeRepository.class);
    private final ViajeService service = new ViajeService(repository, JsonMapper.builder().build(),
            new CityArea("Villavicencio","Meta","Colombia",3.95,4.35,-73.85,-73.45));

    /** El servicio pide los tramos de varias rutas juntos ({@code tramosEntre}); aquí cada uno se resuelve con el {@code tramoEntre} que cada prueba prepara. */
    @BeforeEach void tramosAgrupadosSobreTramoEntre() {
        when(repository.tramosEntre(anyList())).thenAnswer(inv -> ((List<ViajeRepository.TramoPedido>) inv.getArgument(0)).stream()
                .map(p -> repository.tramoEntre(p.rutaId(), p.a(), p.b())).toList());
    }

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

    // ---- Fase D: las rutas registradas por el equipo cuentan igual, estén o no «validadas» (dato interno)
    private ViajeRepository.OpcionRow fila(String codigo, boolean validada, double acceso, double km) {
        return new ViajeRepository.OpcionRow(1, codigo, "Demo", "#176650", validada, acceso / 2, acceso / 2, km, acceso, true,
                "SENTIDO_DEL_TRAZADO", "IDA", true, "{\"type\":\"Point\",\"coordinates\":[-73.65,4.14]}",
                "{\"type\":\"Point\",\"coordinates\":[-73.624,4.145]}", "{\"type\":\"LineString\",\"coordinates\":[[-73.65,4.14],[-73.624,4.145]]}");
    }

    @Test void theRegistrationReviewNoLongerBreaksTiesBetweenRoutes() {
        var sinRevisar = fila("R002", false, 250, 5);
        var revisada = fila("R001", true, 250, 8);
        var ordenadas = new java.util.ArrayList<>(List.of(revisada, sinRevisar));
        ordenadas.sort(ViajeService.ORDEN_OPCIONES);
        assertEquals(List.of("R002", "R001"), ordenadas.stream().map(ViajeRepository.OpcionRow::codigo).toList(),
                "con la misma caminata gana la que va menos tiempo en bus, no la «validada»");
        var mismaDistancia = new java.util.ArrayList<>(List.of(fila("R009", true, 250, 5), fila("R003", false, 250, 5)));
        mismaDistancia.sort(ViajeService.ORDEN_OPCIONES);
        assertEquals("R003", mismaDistancia.getFirst().codigo(), "con todo igual decide el código");
        var lejosPeroRevisada = new java.util.ArrayList<>(List.of(fila("R001", true, 900, 2), fila("R002", false, 300, 9)));
        lejosPeroRevisada.sort(ViajeService.ORDEN_OPCIONES);
        assertEquals("R002", lejosPeroRevisada.getFirst().codigo(), "lo que manda sigue siendo caminar menos");
    }

    @Test void walkInsteadOfSecondBusAlternativesAreAppendedToTheDirectOptions() throws Exception {
        var vacio = JsonMapper.builder().build().readTree("{}");
        var caminar = new ViajeResponse.Opcion(7, "R7", "Demo", "#176650", false, 120, 700, 3.0, 820, true, "SENTIDO_DEL_TRAZADO", "IDA", true,
                vacio, vacio, vacio, "Baja en el punto marcado y camina aproximadamente 700 m hasta tu destino: según los recorridos registrados, "
                + "tomar otro bus no compensa la espera ni el segundo pasaje.", true, null, null, null, null, "BAJAR_Y_CAMINAR");
        var transbordos = mock(TransbordoService.class);
        when(transbordos.calcularConAlternativas(any(), any())).thenReturn(new TransbordoService.Resultado(List.of(), List.of(caminar)));
        service.usarTransbordos(transbordos);
        when(repository.planificar(any(), anyInt())).thenReturn(List.of(fila("R001", false, 200, 4)));
        var response = service.planificar(request());
        assertEquals(List.of("R001", "R7"), response.opciones().stream().map(ViajeResponse.Opcion::codigo).toList(), "la alternativa va después de las directas");
        assertEquals("BAJAR_Y_CAMINAR", response.opciones().get(1).variante());
        assertTrue(response.transbordos().isEmpty());
        assertTrue(response.alcance().contains("bajarse y caminar"));
    }

    @Test void activeRoutesThatAreNotValidatedAreOfferedAndTheFilterIsNotApplied() {
        when(repository.planificar(any(), anyInt())).thenReturn(List.of(fila("R001", false, 200, 4), fila("R002", false, 400, 6)));
        var response = service.planificar(request());
        assertEquals(List.of("R001", "R002"), response.opciones().stream().map(ViajeResponse.Opcion::codigo).toList());
        assertTrue(response.opciones().stream().noneMatch(ViajeResponse.Opcion::validada), "las rutas sin revisión interna participan igual");
        verify(repository).planificar(argThat((PlanificarViajeRequest r) -> !r.soloValidadas()), anyInt());
        assertFalse(response.opciones().getFirst().advertencia().toLowerCase().contains("valid"), "ninguna advertencia pública de validación");
    }

    // ---- Los tramos de TODAS las rutas se piden en una sola consulta (antes una por ruta y una por variante)
    private ViajeRepository.OpcionRow filaDeRuta(long id, String codigo, double acceso, double km) {
        return new ViajeRepository.OpcionRow(id, codigo, "Demo", "#176650", false, acceso / 2, acceso / 2, km, acceso, true,
                "SENTIDO_DEL_TRAZADO", "IDA", true, "{\"type\":\"Point\",\"coordinates\":[-73.65,4.14]}",
                "{\"type\":\"Point\",\"coordinates\":[-73.624,4.145]}", "{\"type\":\"LineString\",\"coordinates\":[[-73.65,4.14],[-73.624,4.145]]}");
    }

    private AbordajeService.Optimo optimo(long ruta, double fSube, double caminaSube, double fBaja, double caminaBaja) {
        return new AbordajeService.Optimo(new AbordajeService.Candidato(ruta, "IDA", 10_000, fSube, -73.63, 4.14, caminaSube, caminaSube, null),
                new AbordajeService.Candidato(ruta, "IDA", 10_000, fBaja, -73.62, 4.15, caminaBaja, caminaBaja, null), true, caminaSube + caminaBaja);
    }

    @Test void theTramesOfAllRoutesAndFasterVariantsAreRequestedInOneQueryEach() {
        var abordaje = mock(AbordajeService.class);
        when(repository.planificar(any(), anyInt())).thenReturn(List.of(filaDeRuta(1, "R001", 1600, 3), filaDeRuta(2, "R002", 2000, 3)));
        // R001: la de menos caminata (1.600 m) y otra más rápida (200 m a pie, ahorra ≈ 18 min); R002 solo tiene la de menos caminata.
        when(abordaje.optimizar(any(), any(), anyList(), anyDouble(), anyDouble())).thenReturn(java.util.Map.of(
                1L, new AbordajeService.Optimos(optimo(1, 0.30, 800, 0.60, 800), optimo(1, 0.35, 100, 0.62, 100)),
                2L, new AbordajeService.Optimos(optimo(2, 0.20, 1000, 0.50, 1000), null)));
        tramoCualquiera();
        var response = conAbordaje(abordaje).planificar(request());
        assertEquals(List.of("R001", "R001", "R002"), response.opciones().stream().map(ViajeResponse.Opcion::codigo).toList());
        assertEquals(List.of("MENOS_CAMINATA", "MAS_RAPIDA", "MENOS_CAMINATA"), response.opciones().stream().map(ViajeResponse.Opcion::variante).toList());
        @SuppressWarnings("unchecked") ArgumentCaptor<List<ViajeRepository.TramoPedido>> pedidos = ArgumentCaptor.forClass(List.class);
        verify(repository, times(2)).tramosEntre(pedidos.capture());
        assertEquals(List.of(new ViajeRepository.TramoPedido(1, 0.30, 0.60), new ViajeRepository.TramoPedido(2, 0.20, 0.50)),
                pedidos.getAllValues().get(0), "una consulta con el tramo de cada ruta");
        assertEquals(List.of(new ViajeRepository.TramoPedido(1, 0.35, 0.62)), pedidos.getAllValues().get(1), "otra con el de las variantes más rápidas");
    }

    @Test void aRouteWhoseTramoIsMissingIsLeftOutOfTheRefinedOnesWithoutBreakingTheTrip() {
        var abordaje = mock(AbordajeService.class);
        when(repository.planificar(any(), anyInt())).thenReturn(List.of(filaDeRuta(1, "R001", 1600, 3), filaDeRuta(2, "R002", 2000, 3)));
        when(abordaje.optimizar(any(), any(), anyList(), anyDouble(), anyDouble())).thenReturn(java.util.Map.of(
                1L, new AbordajeService.Optimos(optimo(1, 0.30, 800, 0.60, 800), null),
                2L, new AbordajeService.Optimos(optimo(2, 0.20, 1000, 0.50, 1000), null)));
        tramoCualquiera();
        when(repository.tramoEntre(eq(2L), anyDouble(), anyDouble())).thenReturn(null);          // la ruta 2 ya no existe
        var response = conAbordaje(abordaje).planificar(request());
        assertEquals("R001", response.opciones().getFirst().codigo());
        assertTrue(response.opciones().getFirst().caminataPorCalles(), "la ruta con tramo sí se refinó");
        assertEquals(2, response.opciones().size(), "la otra se muestra con sus datos originales, sin romper el viaje");
        assertFalse(response.opciones().get(1).caminataPorCalles());
    }

    // ------------------------------------------------------------------ 4.13.2 · recomendación (política 2)
    private ViajeResponse.Transbordo transbordoSintetico(long r1, long r2, double ini, double cambio, double fin, double minutos) throws Exception {
        var vacio = JsonMapper.builder().build().readTree("{}");
        var bus1 = new ViajeResponse.BusLeg(r1, "R" + r1, "Uno", "#d32f2f", true, vacio, vacio, vacio, 3, null, null);
        var bus2 = new ViajeResponse.BusLeg(r2, "R" + r2, "Dos", "#1e5fbf", true, vacio, vacio, vacio, 3, null, null);
        var pie = (java.util.function.DoubleFunction<ViajeResponse.WalkLeg>) m -> new ViajeResponse.WalkLeg(List.of(new double[]{-73.6, 4.1}, new double[]{-73.61, 4.11}), Math.round(m), 5);
        return new ViajeResponse.Transbordo(minutos, ini + cambio + fin, 6, 2, 8, bus1, bus2, pie.apply(ini), pie.apply(cambio), pie.apply(fin), "");
    }

    @Test void theResponseCarriesStableIdsAndARecommendationThatTheBackendDecides() {
        when(repository.planificar(any(), anyInt())).thenReturn(List.of(fila("R001", false, 200, 4)));
        var response = service.planificar(request());
        assertEquals("D:1:MENOS_CAMINATA", response.opciones().getFirst().id());
        var rec = response.recomendacion();
        assertNotNull(rec);
        assertEquals(PoliticaRecomendacion.VERSION, rec.politicaVersion());
        assertEquals("D:1:MENOS_CAMINATA", rec.idRecomendada());
        assertEquals(1, rec.ranking().size());
        assertEquals(1, rec.ranking().getFirst().orden());
        assertTrue(rec.motivo().contains("Sin transbordo"), rec.motivo());
        assertTrue(response.alcance().contains("política " + PoliticaRecomendacion.VERSION));
    }

    @Test void aReverseDirectionAlternativeNeverBecomesTheRecommendedOption() {
        when(repository.planificar(any(), anyInt())).thenReturn(List.of(row("R001", 100, true, "SENTIDO_INVERSO", "IDA", false)));
        var response = service.planificar(request());
        assertTrue(response.opciones().isEmpty());
        assertEquals("A:1:MENOS_CAMINATA", response.alternativas().getFirst().id());
        assertNull(response.recomendacion().idRecomendada(), "sin opción en el sentido correcto no se recomienda la contraria");
        assertFalse(response.recomendacion().ranking().getFirst().elegible());
    }

    @Test void aTransferWithMuchCloserBoardingIsRecommendedAndTheDirectOptionIsKept() throws Exception {
        // directa: 900 m al inicio + 100 al final (como el caso A de la política); transbordo: 100 + 100 + 100
        var directa = new ViajeRepository.OpcionRow(1, "R001", "Demo", "#176650", false, 900, 100, 3.0, 1000, true, "SENTIDO_DEL_TRAZADO", "IDA", true,
                "{\"type\":\"Point\",\"coordinates\":[-73.65,4.14]}", "{\"type\":\"Point\",\"coordinates\":[-73.624,4.145]}",
                "{\"type\":\"LineString\",\"coordinates\":[[-73.65,4.14],[-73.624,4.145]]}");
        when(repository.planificar(any(), anyInt())).thenReturn(List.of(directa));
        var t = transbordoSintetico(2, 3, 100, 100, 100, 34);      // la directa tarda ≈ 23,7 min (1.000 m a pie + 3 km en bus): +10 min, dentro del límite de +15
        var transbordos = mock(TransbordoService.class);
        when(transbordos.calcularConAlternativas(any(), any())).thenReturn(new TransbordoService.Resultado(List.of(t), List.of()));
        service.usarTransbordos(transbordos);
        var response = service.planificar(request());
        assertEquals("T:2>3", response.recomendacion().idRecomendada());
        assertEquals(List.of("T:2>3", "D:1:MENOS_CAMINATA"), response.recomendacion().ranking().stream().map(ViajeResponse.Item::id).toList());
        assertEquals(1, response.opciones().size(), "la directa se conserva con su geometría");
        assertEquals(1, response.transbordos().size());
        assertEquals("T:2>3", response.transbordos().getFirst().id());
        assertTrue(response.recomendacion().motivo().contains("100 m") && response.recomendacion().motivo().contains("900 m"));
        // el servicio pasa al cálculo de transbordos la directa «base» de la política
        var captor = ArgumentCaptor.forClass(TransbordoService.Directo.class);
        verify(transbordos).calcularConAlternativas(any(), captor.capture());
        assertEquals(900, captor.getValue().baseInicialMetros(), 1e-9);
        assertEquals(1000, captor.getValue().baseTotalMetros(), 1e-9);
    }

    @Test void aTransferClearlyDominatedByTheDirectOptionIsLeftOut() throws Exception {
        when(repository.planificar(any(), anyInt())).thenReturn(List.of(fila("R001", false, 200, 4)));
        var peor = transbordoSintetico(2, 3, 5000, 100, 100, 90);           // más caminata y más tiempo que la directa en todo
        var transbordos = mock(TransbordoService.class);
        when(transbordos.calcularConAlternativas(any(), any())).thenReturn(new TransbordoService.Resultado(List.of(peor), List.of()));
        service.usarTransbordos(transbordos);
        var response = service.planificar(request());
        assertTrue(response.transbordos().isEmpty(), "el transbordo dominado no se ofrece");
        assertEquals("D:1:MENOS_CAMINATA", response.recomendacion().idRecomendada());
    }

    @Test void theOldConstructorsOfTheResponseStillMeanNoRecommendation() {
        assertNull(new ViajeResponse(List.of(), List.of(), List.of(), "x").recomendacion());
    }
}
