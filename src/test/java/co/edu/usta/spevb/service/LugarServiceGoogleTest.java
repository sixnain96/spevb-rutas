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

/** Google se suma a ArcGIS como proveedor externo opcional: con clave aparece en sugerencias y su elección se resuelve con Google, sin tocar ArcGIS. */
class LugarServiceGoogleTest {
    private final CityArea area = new CityArea("Villavicencio", "Meta", "Colombia", 3.95, 4.35, -73.85, -73.45);
    private LugarRepository repositorio;
    private DireccionesHibridasService hibrido;
    private DireccionesLocalesService locales;
    private GoogleDireccionesService google;
    private GeocodingService geocodificador;
    private GeocodingService geocoder() { return geocodificador; }
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
        when(google.sugerir("unic")).thenReturn(List.of(new GoogleDireccionesService.Sugerencia("Unicentro, Villavicencio", "ChIJabcdefghij123")));
        var s = servicio.sugerir("unic");
        assertEquals(1, s.size());
        assertEquals("g:ChIJabcdefghij123", s.get(0).magicKey());
        assertEquals("Google Maps", s.get(0).detalle());
        assertTrue(servicio.arcgisActivo(), "la bandera de «hay sugerencias externas» también vale para Google");
    }

    @Test void googleSiempreSeVeAunqueLaBasePropiaYaTraigaMuchasOpciones() {
        when(google.activo()).thenReturn(true);
        when(locales.disponible()).thenReturn(true);
        List<DireccionesLocalesService.SugerenciaLocal> muchas = new java.util.ArrayList<>();
        for (int i = 0; i < 6; i++) muchas.add(new DireccionesLocalesService.SugerenciaLocal((long) i + 1, "Local " + i, "Catastro", 4.1 + i / 1000.0, -73.6, "Catastro de Villavicencio (base propia)", "LUGAR"));
        when(locales.sugerir(eq("unic"), anyInt())).thenReturn(muchas);
        when(hibrido.arcgisActivo()).thenReturn(true);
        when(hibrido.sugerirArcgis("unic")).thenReturn(List.of(new DireccionesHibridasService.Sugerencia("Arc 1", "m1xxxxx"), new DireccionesHibridasService.Sugerencia("Arc 2", "m2xxxxx"), new DireccionesHibridasService.Sugerencia("Arc 3", "m3xxxxx")));
        when(google.sugerir("unic")).thenReturn(List.of(new GoogleDireccionesService.Sugerencia("Goog 1", "ChIJ000000001"), new GoogleDireccionesService.Sugerencia("Goog 2", "ChIJ000000002"), new GoogleDireccionesService.Sugerencia("Goog 3", "ChIJ000000003"), new GoogleDireccionesService.Sugerencia("Goog 4", "ChIJ000000004")));
        var s = servicio.sugerir("unic");
        assertEquals(8, s.size());
        assertEquals(3, s.stream().filter(x -> "Google Maps".equals(x.detalle())).count(), "tres de Google, aunque la base propia traiga seis");
        assertEquals(3, s.stream().filter(x -> x.texto().startsWith("Local")).count());
        assertEquals(2, s.stream().filter(x -> "ArcGIS".equals(x.detalle())).count());
    }

    @Test void conEnterOBuscarSeConsultaGoogleSinElegirSugerencia() {
        when(google.activo()).thenReturn(true);
        when(google.buscarTexto("Unicentro")).thenReturn(List.of(
                new LugarResponse(null, "Unicentro", "Cra. 33", 4.1272, -73.6203, "Google Maps", false, "LUGAR")));
        var r = servicio.buscarDetallado("Unicentro", "");
        assertTrue(r.lugares().stream().anyMatch(l -> "Google Maps".equals(l.fuente())));
    }

    @Test void sinGoogleNadaCambiaYSiFallaSeSigueSinEl() {
        when(google.activo()).thenReturn(false);
        assertTrue(servicio.sugerir("unic").isEmpty());
        assertFalse(servicio.arcgisActivo());
        when(google.activo()).thenReturn(true);
        when(google.sugerir(anyString())).thenThrow(new IllegalStateException("x"));
        assertDoesNotThrow(() -> servicio.sugerir("unic"), "Google es opcional: si falla no rompe la búsqueda");
    }

    @Test void elegirUnaSugerenciaDeGoogleSeResuelveConGoogleYNoConArcgis() {
        when(google.activo()).thenReturn(true);
        when(hibrido.arcgisActivo()).thenReturn(true);
        when(google.buscar("ChIJabcdefghij123")).thenReturn(List.of(
                new LugarResponse(null, "Unicentro", "Cra. 33 #38-10", 4.1272, -73.6203, "Google Maps", false, "LUGAR")));
        var r = servicio.buscarDetallado("Unicentro", "g:ChIJabcdefghij123");
        assertEquals(1, r.lugares().size());
        assertEquals("Google Maps", r.lugares().get(0).fuente());
        verify(hibrido, never()).buscarArcgis(anyString(), anyString());
    }

    @Test void alElegirUnaSugerenciaSoloVuelveEseLugarAunqueHayaOtrosParecidos() {
        when(google.activo()).thenReturn(true);
        when(locales.disponible()).thenReturn(true);
        when(locales.buscar(anyString(), anyInt())).thenReturn(List.of(
                new LugarResponse(null, "Colegio Adventista", "x", 4.1, -73.6, "Catastro", false, "LUGAR"),
                new LugarResponse(null, "Antonio Villavicencio", "x", 4.2, -73.6, "Catastro", false, "LUGAR")));
        when(google.buscar("ChIJabcdefghij123")).thenReturn(List.of(
                new LugarResponse(null, "Aeropuerto Vanguardia", "Villavicencio", 4.1644, -73.6184, "Google Maps", false, "LUGAR")));
        var r = servicio.buscarDetallado("Aeropuerto Vanguardia", "g:ChIJabcdefghij123");
        assertEquals(1, r.lugares().size(), "sin un segundo menú de coincidencias");
        assertEquals("Aeropuerto Vanguardia", r.lugares().get(0).nombre());
        verify(geocoder(), never()).buscar(anyString());
    }

    @Test void alElegirUnaSugerenciaDeArcgisTambienSoloVuelveEseLugar() {
        when(hibrido.arcgisActivo()).thenReturn(true);
        when(locales.disponible()).thenReturn(true);
        when(locales.buscar(anyString(), anyInt())).thenReturn(List.of(new LugarResponse(null, "Otro", "x", 4.1, -73.6, "Catastro", false, "LUGAR")));
        when(hibrido.buscarArcgis("Unicentro", "clave12345")).thenReturn(List.of(
                new LugarResponse(null, "Unicentro", "Cra 33", 4.127, -73.62, "ArcGIS", false, "LUGAR"),
                new LugarResponse(null, "Unicentro 2", "Cra 34", 4.128, -73.62, "ArcGIS", false, "LUGAR")));
        var r = servicio.buscarDetallado("Unicentro", "clave12345");
        assertEquals(List.of("Unicentro"), r.lugares().stream().map(LugarResponse::nombre).toList());
    }

    @Test void siGoogleFallaAlUbicarSeAvisaEnVezDeInventarUnPunto() {
        when(google.activo()).thenReturn(true);
        when(google.buscar(anyString())).thenThrow(new IllegalStateException("x"));
        var r = servicio.buscarDetallado("Unicentro", "g:ChIJabcdefghij123");
        assertTrue(r.lugares().isEmpty());
        assertTrue(r.avisos().stream().anyMatch(a -> a.contains("Google")));
    }
}
