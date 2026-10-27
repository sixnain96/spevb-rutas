package co.edu.usta.spevb.service;

import co.edu.usta.spevb.repository.ViajeRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AbordajeServiceTest {
    private final ViajeRepository repository = mock(ViajeRepository.class);
    private final CaminataService caminata = mock(CaminataService.class);
    private final AbordajeService service = new AbordajeService(repository, caminata);

    private ViajeRepository.CandidatoRow row(double fraccion, double recta) {
        return new ViajeRepository.CandidatoRow(1, "IDA", 8000, fraccion, -73.63, 4.14, recta, null);
    }

    @Test void usesWalkingDistancesWhenTheMatrixIsAvailable() {
        when(repository.candidatos(anyDouble(), anyDouble(), anyList(), anyInt(), anyInt()))
                .thenReturn(List.of(row(0.1, 200), row(0.3, 450)));
        when(caminata.distancias(anyList(), anyList(), anyInt())).thenReturn(Optional.of(new double[][]{
                {1500, 520, 600, 900}, {1800, 1700, 250, 700}}));
        var ev = service.evaluar(new double[]{4.14, -73.65}, new double[]{4.15, -73.62}, List.of(1L), 2);
        assertTrue(ev.porCalles());
        assertEquals(1500, ev.subidas().get(0).caminataMetros());
        assertEquals(520, ev.subidas().get(1).caminataMetros());
        assertEquals(250, ev.bajadas().get(0).caminataMetros(), "la fila 1 de la matriz es el destino B");
    }

    @Test void fallsBackToStraightLineWhenProviderIsUnavailable() {
        when(repository.candidatos(anyDouble(), anyDouble(), anyList(), anyInt(), anyInt())).thenReturn(List.of(row(0.1, 200)));
        when(caminata.distancias(anyList(), anyList(), anyInt())).thenReturn(Optional.empty());
        var ev = service.evaluar(new double[]{4.14, -73.65}, null, List.of(1L), 2);
        assertFalse(ev.porCalles());
        assertEquals(200, ev.subidas().getFirst().caminataMetros());
    }

    @Test void unreachablePointsArePenalisedSoAccessibleOnesWin() {
        when(repository.candidatos(anyDouble(), anyDouble(), anyList(), anyInt(), anyInt()))
                .thenReturn(List.of(row(0.1, 100), row(0.3, 400)));
        when(caminata.distancias(anyList(), anyList(), anyInt())).thenReturn(Optional.of(new double[][]{{Double.NaN, 700}}));
        var ev = service.evaluar(new double[]{4.14, -73.65}, null, List.of(1L), 2);
        assertTrue(ev.subidas().get(0).caminataMetros() > ev.subidas().get(1).caminataMetros());
    }

    @Test void noCandidatesMeansNoEvaluation() {
        when(repository.candidatos(anyDouble(), anyDouble(), anyList(), anyInt(), anyInt())).thenReturn(List.of());
        assertSame(AbordajeService.Evaluacion.VACIA, service.evaluar(new double[]{4.14, -73.65}, null, List.of(1L), 2));
        verifyNoInteractions(caminata);
    }
}
