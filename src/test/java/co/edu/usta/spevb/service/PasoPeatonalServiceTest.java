package co.edu.usta.spevb.service;

import co.edu.usta.spevb.config.CityArea;
import co.edu.usta.spevb.dto.PasoPeatonalRequest;
import co.edu.usta.spevb.repository.PasoPeatonalRepository;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PasoPeatonalServiceTest {
    private final PasoPeatonalRepository repo = mock(PasoPeatonalRepository.class);
    private final RedPeatonalService red = mock(RedPeatonalService.class);
    private final CaminataService caminata = mock(CaminataService.class);
    private final ViajeService viajes = mock(ViajeService.class);
    private final PasoPeatonalService service = new PasoPeatonalService(repo, red, caminata, viajes,
            new CityArea("Villavicencio", "Meta", "Colombia", 3.95, 4.35, -73.85, -73.45), JsonMapper.builder().build());

    private static PasoPeatonalRequest req(String tipo, double... lngLat) {
        var pts = new java.util.ArrayList<double[]>();
        for (int i = 0; i < lngLat.length; i += 2) pts.add(new double[]{lngLat[i], lngLat[i + 1]});
        return new PasoPeatonalRequest(tipo, "Atajo de prueba", "", pts);
    }

    @Test void guardaAplicaLaRedYLimpiaLasCachesYAvisaSiQuedoConectado() {
        when(repo.crear(eq("PASO"), anyString(), any(), anyString())).thenReturn(7L);
        when(red.aplicarPasos()).thenReturn(Map.of(7L, 2));
        var r = service.crear(req("paso", -73.6300, 4.1400, -73.6290, 4.1400));
        assertEquals(7L, r.get("id"));
        assertEquals(2, r.get("conectados"));
        assertTrue(r.get("mensaje").toString().contains("conectado a la red por sus dos extremos"));
        verify(red).aplicarPasos(); verify(caminata).invalidarCache(); verify(viajes).invalidarCache();
    }

    @Test void siUnExtremoNoQuedaConectadoLoDiceClaramente() {
        when(repo.crear(anyString(), anyString(), any(), anyString())).thenReturn(8L);
        when(red.aplicarPasos()).thenReturn(Map.of(8L, 1));
        var r = service.crear(req("PASO", -73.6300, 4.1400, -73.6290, 4.1400));
        assertTrue(r.get("mensaje").toString().startsWith("ATENCIÓN"), r.get("mensaje").toString());
    }

    @Test void rechazaTipoDesconocidoTrazoCortoLargoYFueraDeLaCiudad() {
        assertThrows(IllegalArgumentException.class, () -> service.crear(req("OTRO", -73.63, 4.14, -73.629, 4.14)));
        assertThrows(IllegalArgumentException.class, () -> service.crear(req("PASO", -73.63, 4.14, -73.63001, 4.14)), "menos de 3 m");
        assertThrows(IllegalArgumentException.class, () -> service.crear(req("PASO", -73.63, 4.14, -73.60, 4.14)), "más de 3 km");
        assertThrows(IllegalArgumentException.class, () -> service.crear(req("PASO", -70.0, 4.14, -70.001, 4.14)), "fuera de Villavicencio");
        verifyNoInteractions(repo);
    }

    @Test void alBorrarSoloReconstruyeSiExistia() {
        when(repo.borrar(1L)).thenReturn(true);
        when(repo.borrar(2L)).thenReturn(false);
        assertTrue(service.borrar(1L));
        assertFalse(service.borrar(2L));
        verify(red, times(1)).aplicarPasos();
    }

    @Test void listaConLaGeometriaComoGeoJson() {
        when(repo.listar()).thenReturn(List.of(new PasoPeatonalRepository.Fila(3, "BLOQUEO", "Caño", null,
                "{\"type\":\"LineString\",\"coordinates\":[[-73.63,4.14],[-73.62,4.14]]}", 1100)));
        var l = service.listar();
        assertEquals(1, l.size());
        assertEquals("BLOQUEO", l.get(0).get("tipo"));
        assertEquals("", l.get(0).get("nota"));
    }
}
