package co.edu.usta.spevb.service;

import co.edu.usta.spevb.config.CityArea;
import co.edu.usta.spevb.dto.LugarResponse;
import co.edu.usta.spevb.repository.LugarRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 4.14: con la política «solo Google», ArcGIS, la base propia/catastro y Nominatim NO participan en la búsqueda del pasajero y no hay respaldo silencioso. */
class LugarServiceSoloGoogleTest {
    private final CityArea area = new CityArea("Villavicencio", "Meta", "Colombia", 3.95, 4.35, -73.85, -73.45);
    private LugarRepository repo; private GeocodingService geo; private DireccionesHibridasService hib; private DireccionesLocalesService loc; private GoogleDireccionesService google;
    private LugarService s;

    @BeforeEach void preparar() {
        repo = mock(LugarRepository.class); geo = mock(GeocodingService.class); hib = mock(DireccionesHibridasService.class);
        loc = mock(DireccionesLocalesService.class); google = mock(GoogleDireccionesService.class);
        when(google.activo()).thenReturn(true);
        when(hib.arcgisActivo()).thenReturn(true); when(hib.catastroActivo()).thenReturn(true);
        when(loc.disponible()).thenReturn(true); when(geo.configurado()).thenReturn(true);
        s = new LugarService(repo, geo, area, hib, loc);
        s.usarGoogle(google);
        s.soloGoogle(true);
    }

    @Test void sugerirNoConsultaOtrosProveedoresAunqueGoogleNoDevuelvaNada() {
        when(google.sugerir(anyString(), any())).thenReturn(List.of());
        s.sugerirDetallado("unicentro", "sesion-abc-123");
        verify(hib, never()).sugerirArcgis(anyString());
        verify(loc, never()).sugerir(anyString(), anyInt());
    }

    @Test void siGoogleFallaNoHayRespaldoSilencioso() {
        when(google.sugerir(anyString(), any())).thenThrow(new IllegalStateException("x"));
        when(google.buscarTexto(anyString())).thenThrow(new IllegalStateException("x"));
        var r = s.buscarDetallado("unicentro", "", "sesion-abc-123");
        assertTrue(r.lugares().isEmpty());
        verify(hib, never()).buscarCatastro(anyString());
        verify(hib, never()).buscarArcgisResultado(anyString(), anyString());
        verify(loc, never()).buscar(anyString(), anyInt());
        verify(geo, never()).buscar(anyString());
        verify(repo, never()).buscarCatalogo(anyString(), anyInt());
    }

    @Test void elPuntoDelMapaNoConsultaNominatim() {
        when(repo.masCercano(anyDouble(), anyDouble(), anyDouble())).thenReturn(Optional.empty());
        LugarResponse l = s.reverso(4.14, -73.63);
        assertTrue(l.nombre().startsWith("Punto en mapa"));
        verify(geo, never()).reverso(anyDouble(), anyDouble());
    }
}
