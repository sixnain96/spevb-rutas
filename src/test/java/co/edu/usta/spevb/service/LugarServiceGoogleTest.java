package co.edu.usta.spevb.service;

import co.edu.usta.spevb.config.CityArea;
import co.edu.usta.spevb.dto.LugarResponse;
import co.edu.usta.spevb.repository.LugarRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Google como primer proveedor (política por prioridad, no mezcla con cupos): con clave sus sugerencias llevan el prefijo «g:», su elección se
 * resuelve con Google y, si falla, se informa sin sustituir el lugar elegido. La cascada completa y los estados están en {@link LugarServiceCascadaTest}.
 */
class LugarServiceGoogleTest {
    private final CityArea area = new CityArea("Villavicencio", "Meta", "Colombia", 3.95, 4.35, -73.85, -73.45);
    private LugarRepository repositorio;
    private DireccionesHibridasService hibrido;
    private DireccionesLocalesService locales;
    private GoogleDireccionesService google;
    private GeocodingService geocodificador;
    private LugarService servicio;

    @BeforeEach void preparar() {
        repositorio = mock(LugarRepository.class);
        hibrido = mock(DireccionesHibridasService.class);
        locales = mock(DireccionesLocalesService.class);
        google = mock(GoogleDireccionesService.class);
        geocodificador = mock(GeocodingService.class);
        servicio = new LugarService(repositorio, geocodificador, area, hibrido, locales);
        servicio.usarGoogle(google);
        when(locales.disponible()).thenReturn(false);
        when(repositorio.buscarCatalogo(anyString(), anyInt())).thenReturn(List.of());
    }

    @Test void conClaveLasSugerenciasDeGoogleLlevanSuPrefijoYSuFuente() {
        when(google.activo()).thenReturn(true);
        when(google.sugerir(eq("unic"), any())).thenReturn(List.of(new GoogleDireccionesService.Sugerencia("Unicentro, Villavicencio", "ChIJabcdefghij123")));
        var s = servicio.sugerir("unic");
        assertEquals(1, s.size());
        assertEquals("g:ChIJabcdefghij123", s.get(0).magicKey());
        assertEquals("Google Maps", s.get(0).detalle());
        assertTrue(servicio.arcgisActivo(), "la bandera de «hay sugerencias externas» también vale para Google");
    }

    @Test void googleTienePrioridadYNoSeMezclaConCuposConLasDemasFuentes() {
        // Antes: cupos 3 (base propia) + 3 (Google) + 2 (ArcGIS). Ahora: si Google devuelve algo utilizable, ES la respuesta; no se consulta nada más.
        when(google.activo()).thenReturn(true);
        when(locales.disponible()).thenReturn(true);
        when(hibrido.arcgisActivo()).thenReturn(true);
        when(google.sugerir(eq("unic"), any())).thenReturn(List.of(new GoogleDireccionesService.Sugerencia("Goog 1 Unicentro", "ChIJ000000001"),
                new GoogleDireccionesService.Sugerencia("Goog 2 Unicentro", "ChIJ000000002"), new GoogleDireccionesService.Sugerencia("Goog 3 Unicentro", "ChIJ000000003"),
                new GoogleDireccionesService.Sugerencia("Goog 4 Unicentro", "ChIJ000000004")));
        var r = servicio.sugerirDetallado("unic", null);
        assertEquals(4, r.sugerencias().size());
        assertTrue(r.sugerencias().stream().allMatch(x -> "Google Maps".equals(x.detalle())));
        assertEquals(EstadoBusqueda.OK, r.estado());
        assertEquals("google", r.proveedor());
        verify(hibrido, never()).sugerirArcgis(anyString());
        verify(locales, never()).sugerir(anyString(), anyInt());
        verify(geocodificador, never()).buscar(anyString());
    }

    @Test void conEnterOBuscarSeConsultaGoogleSinElegirSugerencia() {
        when(google.activo()).thenReturn(true);
        when(google.buscarTextoResultado("Unicentro")).thenReturn(new ResultadoLugares(List.of(
                new LugarResponse(null, "Unicentro", "Cra. 33", 4.1272, -73.6203, "Google Maps", false, "LUGAR")), 0));
        var r = servicio.buscarDetallado("Unicentro", "");
        assertTrue(r.lugares().stream().anyMatch(l -> "Google Maps".equals(l.fuente())));
        assertEquals("google", r.proveedor());
    }

    @Test void sinGoogleNadaCambiaYSiFallaSeSigueSinEl() {
        when(google.activo()).thenReturn(false);
        assertTrue(servicio.sugerir("unic").isEmpty());
        assertFalse(servicio.arcgisActivo());
        when(google.activo()).thenReturn(true);
        when(google.sugerir(anyString(), any())).thenThrow(new IllegalStateException("x"));
        assertDoesNotThrow(() -> servicio.sugerir("unic"), "Google es opcional: si falla no rompe la búsqueda");
    }

    @Test void elegirUnaSugerenciaDeGoogleSeResuelveConGoogleYNoConArcgis() {
        when(google.activo()).thenReturn(true);
        when(hibrido.arcgisActivo()).thenReturn(true);
        when(google.resolver("ChIJabcdefghij123", "Unicentro", null)).thenReturn(new ResultadoLugares(List.of(
                new LugarResponse(null, "Unicentro", "Cra. 33 #38-10", 4.1272, -73.6203, "Google Maps", false, "LUGAR")), 0));
        var r = servicio.buscarDetallado("Unicentro", "g:ChIJabcdefghij123");
        assertEquals(1, r.lugares().size());
        assertEquals("Google Maps", r.lugares().get(0).fuente());
        assertFalse(r.eleccionFallida());
        verify(hibrido, never()).buscarArcgisResultado(anyString(), anyString());
    }

    @Test void alElegirUnaSugerenciaSoloVuelveEseLugarAunqueHayaOtrosParecidos() {
        when(google.activo()).thenReturn(true);
        when(locales.disponible()).thenReturn(true);
        when(locales.buscar(anyString(), anyInt())).thenReturn(List.of(
                new LugarResponse(null, "Colegio Adventista", "x", 4.1, -73.6, "Catastro", false, "LUGAR"),
                new LugarResponse(null, "Antonio Villavicencio", "x", 4.2, -73.6, "Catastro", false, "LUGAR")));
        when(google.resolver("ChIJabcdefghij123", "Aeropuerto Vanguardia", null)).thenReturn(new ResultadoLugares(List.of(
                new LugarResponse(null, "Aeropuerto Vanguardia", "Villavicencio", 4.1644, -73.6184, "Google Maps", false, "LUGAR")), 0));
        var r = servicio.buscarDetallado("Aeropuerto Vanguardia", "g:ChIJabcdefghij123");
        assertEquals(1, r.lugares().size(), "sin un segundo menú de coincidencias");
        assertEquals("Aeropuerto Vanguardia", r.lugares().get(0).nombre());
        verify(geocodificador, never()).buscar(anyString());
        verify(locales, never()).buscar(anyString(), anyInt());
    }

    @Test void alElegirUnaSugerenciaDeArcgisTambienSoloVuelveEseLugar() {
        when(hibrido.arcgisActivo()).thenReturn(true);
        when(locales.disponible()).thenReturn(true);
        when(locales.buscar(anyString(), anyInt())).thenReturn(List.of(new LugarResponse(null, "Otro", "x", 4.1, -73.6, "Catastro", false, "LUGAR")));
        when(hibrido.buscarArcgisResultado("Unicentro", "clave12345")).thenReturn(new ResultadoLugares(List.of(
                new LugarResponse(null, "Unicentro", "Cra 33", 4.127, -73.62, "ArcGIS", false, "LUGAR"),
                new LugarResponse(null, "Unicentro 2", "Cra 34", 4.128, -73.62, "ArcGIS", false, "LUGAR")), 0));
        var r = servicio.buscarDetallado("Unicentro", "clave12345");
        assertEquals(List.of("Unicentro"), r.lugares().stream().map(LugarResponse::nombre).toList());
    }

    @Test void siGoogleFallaAlUbicarSeAvisaEnVezDeInventarUnPuntoNiSustituirlo() {
        when(google.activo()).thenReturn(true);
        when(locales.disponible()).thenReturn(true);
        when(google.resolver(anyString(), any(), any())).thenThrow(new ProveedorException(EstadoBusqueda.TIMEOUT, "x"));
        var r = servicio.buscarDetallado("Unicentro", "g:ChIJabcdefghij123");
        assertTrue(r.lugares().isEmpty());
        assertTrue(r.eleccionFallida());
        assertEquals(EstadoBusqueda.TIMEOUT, r.estado());
        assertTrue(r.avisos().stream().anyMatch(a -> a.contains("Google")));
        verify(locales, never()).buscar(anyString(), anyInt());
        verify(geocodificador, never()).buscar(anyString());
        verify(repositorio, never()).buscarCatalogo(anyString(), anyInt());
    }
}
