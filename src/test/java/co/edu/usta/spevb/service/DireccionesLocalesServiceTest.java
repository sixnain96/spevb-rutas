package co.edu.usta.spevb.service;

import co.edu.usta.spevb.config.CityArea;
import co.edu.usta.spevb.repository.DireccionRepository;
import co.edu.usta.spevb.repository.DireccionRepository.Fila;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DireccionesLocalesServiceTest {
    private final DireccionRepository repo = mock(DireccionRepository.class);
    private final DireccionesLocalesService service = new DireccionesLocalesService(repo,
            new CityArea("Villavicencio", "Meta", "Colombia", 3.95, 4.35, -73.85, -73.45));

    private Fila fila(long id, String via, String cuad, String cruce, int placa, double lat, double lng) {
        return new Fila(id, "texto", "K", via, cuad, cruce, placa, 0, "", "", "EL BARZAL", lat, lng, "PREDIO_APROXIMADO", 70);
    }

    @Test void exactMatchIsReturnedWithReadableName() {
        when(repo.confirmadaPorUsuarios(anyString())).thenReturn(Optional.empty());
        when(repo.exactas("K", "48", "19", 15, 8)).thenReturn(List.of(fila(1, "48", "", "19", 15, 4.14, -73.63)));
        var r = service.buscar("Cra 48 No. 19-15", 5);
        assertEquals(1, r.size());
        assertEquals("Carrera 48 #19-15", r.get(0).nombre());
        assertEquals("PREDIO_APROXIMADO", r.get(0).precision());
        assertTrue(r.get(0).direccion().contains("El Barzal"));
    }

    @Test void sameAddressInNearbyApartmentsIsShownOnce() {
        when(repo.confirmadaPorUsuarios(anyString())).thenReturn(Optional.empty());
        when(repo.exactas(anyString(), anyString(), anyString(), anyInt(), anyInt())).thenReturn(List.of(
                fila(1, "48", "", "19", 15, 4.14000, -73.63000), fila(2, "48", "", "19", 15, 4.14001, -73.63001),
                fila(3, "48", "", "19", 15, 4.15000, -73.64000)));
        assertEquals(2, service.buscar("Carrera 48 #19-15", 5).size());
    }

    @Test void userConfirmedPointComesFirst() {
        when(repo.confirmadaPorUsuarios("K|48||19|15")).thenReturn(Optional.of(new DireccionRepository.Punto(4.141, -73.631, 4)));
        when(repo.exactas(anyString(), anyString(), anyString(), anyInt(), anyInt())).thenReturn(List.of(fila(1, "48", "", "19", 15, 4.14, -73.63)));
        var r = service.buscar("Carrera 48 #19-15", 5);
        assertEquals("CONFIRMADA_POR_USUARIOS", r.get(0).precision());
        assertEquals(2, r.size());
    }

    @Test void missingPlateIsInterpolatedBetweenNeighboursOfTheSameParity() {
        when(repo.confirmadaPorUsuarios(anyString())).thenReturn(Optional.empty());
        when(repo.exactas(anyString(), anyString(), anyString(), anyInt(), anyInt())).thenReturn(List.of());
        when(repo.vecina("K", "48", "", "19", 25, false)).thenReturn(Optional.of(fila(1, "48", "", "19", 21, 4.1400, -73.6300)));
        when(repo.vecina("K", "48", "", "19", 25, true)).thenReturn(Optional.of(fila(2, "48", "", "19", 29, 4.1408, -73.6308)));
        var r = service.buscar("Carrera 48 #19-25", 5).get(0);
        assertEquals("INTERPOLADA", r.precision());
        assertEquals(4.1404, r.lat(), 1e-9);
        assertEquals(-73.6304, r.lng(), 1e-9);
    }

    @Test void farNeighbourIsNotUsedAsAGuess() {
        when(repo.confirmadaPorUsuarios(anyString())).thenReturn(Optional.empty());
        when(repo.exactas(anyString(), anyString(), anyString(), anyInt(), anyInt())).thenReturn(List.of());
        when(repo.vecina(anyString(), anyString(), anyString(), anyString(), anyInt(), eq(false)))
                .thenReturn(Optional.of(fila(1, "48", "", "19", 3, 4.14, -73.63)));
        when(repo.vecina(anyString(), anyString(), anyString(), anyString(), anyInt(), eq(true))).thenReturn(Optional.empty());
        when(repo.crucesDeVia(anyString(), anyString(), anyString())).thenReturn(List.of());
        assertTrue(service.buscar("Carrera 48 #19-99", 5).isEmpty());
        verify(repo).registrarSinResultado(anyString());
    }

    @Test void unknownCrossFallsBackToTheZoneBetweenKnownCrosses() {
        when(repo.confirmadaPorUsuarios(anyString())).thenReturn(Optional.empty());
        when(repo.exactas(anyString(), anyString(), anyString(), anyInt(), anyInt())).thenReturn(List.of());
        when(repo.vecina(anyString(), anyString(), anyString(), anyString(), anyInt(), anyBoolean())).thenReturn(Optional.empty());
        when(repo.crucesDeVia("K", "48", "")).thenReturn(List.of(new double[]{10, 4.130, -73.640}, new double[]{30, 4.150, -73.620}));
        var r = service.buscar("Carrera 48 #20-05", 5).get(0);
        assertEquals("VIA_APROXIMADA", r.precision());
        assertEquals(4.140, r.lat(), 1e-9);
    }

    @Test void intersectionUsesTheCornerOfBothStreets() {
        when(repo.esquina("C", "40", "K", "25")).thenReturn(Optional.of(new DireccionRepository.Punto(4.15, -73.63, 12)));
        var r = service.buscar("Calle 40 con Carrera 25", 5);
        assertEquals("ESQUINA_APROXIMADA", r.get(0).precision());
    }

    @Test void confirmationRequiresACompleteAddressInsideTheCityAndNearTheEstimate() {
        assertFalse(service.confirmar("barrio san marcos", 4.14, -73.63));
        assertFalse(service.confirmar("Carrera 48 #19-15", 10.0, -73.63));
        when(repo.confirmadaPorUsuarios(anyString())).thenReturn(Optional.empty());
        when(repo.exactas("K", "48", "19", 15, 8)).thenReturn(List.of(fila(1, "48", "", "19", 15, 4.14, -73.63)));
        assertFalse(service.confirmar("Carrera 48 #19-15", 4.25, -73.55), "más de 1,5 km de lo que ya sabe la base");
        verify(repo, never()).guardarConfirmacion(anyString(), anyDouble(), anyDouble());
        assertTrue(service.confirmar("Carrera 48 #19-15", 4.1402, -73.6301));
        verify(repo).guardarConfirmacion("K|48||19|15", 4.1402, -73.6301);
    }

    @Test void suggestionsForAPartialAddressUseTheTypedPrefixes() {
        when(repo.sugerirPorVia("K", "48", "", "19", "", 18)).thenReturn(List.of(
                fila(1, "48", "", "19", 15, 4.14, -73.63), fila(2, "48", "", "19", 17, 4.1401, -73.6301)));
        var r = service.sugerir("Carrera 48 #19", 6);
        assertEquals(2, r.size());
        assertEquals("Carrera 48 #19-15", r.get(0).texto());
        assertEquals("El Barzal", r.get(0).detalle());
    }

    @Test void formatsBisSouthAndLeadingZeros() {
        assertEquals("Calle 17 Sur #39-03", DireccionesLocalesService.formatear(
                new Fila(1, "", "C", "17", "S", "39", 3, 0, "", "", "", 0, 0, "", 0)));
        assertEquals("Calle 37 Bis A #21B-27", DireccionesLocalesService.formatear(
                new Fila(1, "", "C", "37BISA", "", "21B", 27, 0, "", "", "", 0, 0, "", 0)));
    }

    @Test void namedPlaceIsFoundAmongOsmPois() {
        when(repo.buscarPois("unicentro", 8)).thenReturn(List.of(
                new DireccionRepository.PoiFila(3, "Unicentro Villavicencio", "Centro comercial", "", 4.1395, -73.6366, "OSM")));
        var r = service.buscar("Unicentro", 5);
        assertEquals("Unicentro Villavicencio", r.get(0).nombre());
        assertEquals("LUGAR", r.get(0).precision());
        assertEquals("OpenStreetMap", r.get(0).fuente());
        assertTrue(r.get(0).direccion().startsWith("Centro comercial"));
    }

    @Test void poiSuggestionsUseNegativeIdsSoSelectionCountsTheRightTable() {
        when(repo.buscarPois("unic", 12)).thenReturn(List.of(
                new DireccionRepository.PoiFila(3, "Unicentro Villavicencio", "Centro comercial", "", 4.1395, -73.6366, "OSM")));
        var s = service.sugerir("unic", 6).get(0);
        assertEquals(-3, s.id());
        service.elegir(s.id());
        verify(repo).sumarPopularidadPoi(3);
        service.elegir(77);
        verify(repo).sumarPopularidad(77);
    }

    @Test void teamCorrectionIsStoredWithTopConfidenceAndClearsThePendingEntry() {
        var clave = service.corregir("Cra 48 #19-99", 4.1402, -73.6301);
        assertEquals("K|48||19|99", clave.get());
        var captor = org.mockito.ArgumentCaptor.forClass(DireccionRepository.Nueva.class);
        verify(repo).corregirDireccion(captor.capture());
        assertEquals("EQUIPO", captor.getValue().fuente());
        assertEquals(95, captor.getValue().confianza());
        assertEquals("VERIFICADO_LOCAL", captor.getValue().precision());
        verify(repo).descartarSinResultado("K|48||19|99");
        assertTrue(service.corregir("Avenida Catama", 4.14, -73.63).isEmpty());
        assertThrows(IllegalArgumentException.class, () -> service.corregir("Cra 48 #19-99", 10, -73.6));
    }

    @Test void pendingKeysAreShownInReadableForm() {
        assertEquals("Carrera 48 #19-15", DireccionesLocalesService.legible("K|48||19|15"));
        assertEquals("Calle 17 Sur #39-03", DireccionesLocalesService.legible("C|17|S|39|3"));
        assertEquals("unicentro", DireccionesLocalesService.legible("unicentro"));
    }

    @Test void duplicatedPlacesNearEachOtherAreShownOnce() {
        when(repo.buscarPois("universidad de los llanos", 8)).thenReturn(List.of(
                new DireccionRepository.PoiFila(1, "Universidad De Los LLanos", "Universidad", "", 4.0750, -73.5850, "OSM"),
                new DireccionRepository.PoiFila(2, "Universidad de los Llanos", "Universidad", "", 4.0751, -73.5851, "OSM"),
                new DireccionRepository.PoiFila(3, "Universidad de los Llanos", "Universidad", "", 4.1500, -73.6300, "OSM")));
        assertEquals(2, service.buscar("universidad de los llanos", 5).size(), "la sede lejana sí es otro lugar");
    }
}
