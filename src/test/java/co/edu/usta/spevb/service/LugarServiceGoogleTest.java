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

/**
 * 4.14: la búsqueda de direcciones y lugares del pasajero es EXCLUSIVAMENTE Google. Estas pruebas fijan el contrato:
 * ningún otro proveedor ni base local participa, no hay respaldo silencioso y cada situación (sin resultados, error de servicio,
 * error de configuración, sin configurar) devuelve un estado distinto para que la interfaz sepa qué decir.
 */
class LugarServiceGoogleTest {
    private final CityArea area = new CityArea("Villavicencio", "Meta", "Colombia", 3.95, 4.35, -73.85, -73.45);
    private LugarRepository repositorio;
    private DireccionesLocalesService locales;
    private GoogleDireccionesService google;
    private LugarService servicio;

    @BeforeEach void preparar() {
        repositorio = mock(LugarRepository.class);
        locales = mock(DireccionesLocalesService.class);
        google = mock(GoogleDireccionesService.class);
        when(google.activo()).thenReturn(true);
        servicio = new LugarService(repositorio, area, locales, google);
    }

    private static LugarResponse lugar(String nombre) {
        return new LugarResponse(null, nombre, "Cra. 33 #38-10, Villavicencio", 4.1272, -73.6203, "Google Maps", false, "LUGAR");
    }

    @Test void lasSugerenciasSonSoloDeGoogleYLlevanSuPrefijoYSesion() {
        when(google.sugerir("unic", "sesion-abc-123")).thenReturn(List.of(new GoogleDireccionesService.Sugerencia("Unicentro, Villavicencio", "ChIJabcdefghij123", "Villavicencio, Meta")));
        var r = servicio.sugerir("unic", "sesion-abc-123");
        assertEquals(LugarService.OK, r.estado());
        assertEquals(1, r.sugerencias().size());
        assertEquals("g:ChIJabcdefghij123", r.sugerencias().get(0).magicKey());
        assertEquals("Google Maps", r.sugerencias().get(0).fuente());
        verifyNoInteractions(locales);
    }

    @Test void sinResultadosEsUnEstadoPropioNoUnError() {
        when(google.sugerir(anyString(), any())).thenReturn(List.of());
        assertEquals(LugarService.SIN_RESULTADOS, servicio.sugerir("zzzzzz", null).estado());
    }

    @Test void unaConsultaCortaNoLlamaAGoogle() {
        assertEquals(LugarService.CONSULTA_CORTA, servicio.sugerir("un", null).estado());
        assertEquals(LugarService.CONSULTA_CORTA, servicio.sugerir("  ", null).estado());
        verify(google, never()).sugerir(anyString(), any());
    }

    @Test void sinConfigurarSeDiceYNoSeConsultaNada() {
        when(google.activo()).thenReturn(false);
        assertEquals(LugarService.SIN_CONFIGURAR, servicio.sugerir("unicentro", null).estado());
        var r = servicio.buscarDetallado("unicentro", "", null);
        assertEquals(LugarService.SIN_CONFIGURAR, r.estado());
        assertTrue(r.lugares().isEmpty());
        assertFalse(r.avisos().isEmpty());
        verify(google, never()).sugerir(anyString(), any());
        verify(google, never()).buscarTexto(anyString());
    }

    @Test void siGoogleFallaSeDistingueServicioDeConfiguracionYNoHayRespaldo() {
        doThrow(new GoogleDireccionesService.ErrorGoogle(GoogleDireccionesService.ErrorGoogle.Tipo.SERVICIO, "x")).when(google).sugerir(anyString(), any());
        assertEquals(LugarService.ERROR_SERVICIO, servicio.sugerir("unicentro", null).estado());
        doThrow(new GoogleDireccionesService.ErrorGoogle(GoogleDireccionesService.ErrorGoogle.Tipo.CONFIGURACION, "x")).when(google).sugerir(anyString(), any());
        assertEquals(LugarService.ERROR_CONFIGURACION, servicio.sugerir("unicentro", null).estado());
        doThrow(new IllegalStateException("x")).when(google).sugerir(anyString(), any());
        assertEquals(LugarService.ERROR_SERVICIO, servicio.sugerir("unicentro", null).estado());
        verifyNoInteractions(locales);
        verify(repositorio, never()).buscarCatalogo(anyString(), anyInt());
    }

    @Test void conEnterOBuscarSeConsultaSoloGoogleYNoSeMezclaNada() {
        when(google.buscarTexto("Unicentro")).thenReturn(List.of(lugar("Unicentro")));
        var r = servicio.buscarDetallado("Unicentro", "", null);
        assertEquals(LugarService.OK, r.estado());
        assertEquals(List.of("Unicentro"), r.lugares().stream().map(LugarResponse::nombre).toList());
        verify(repositorio, never()).buscarCatalogo(anyString(), anyInt());
        verifyNoInteractions(locales);
    }

    @Test void elegirUnaSugerenciaResuelveEseLugarConLaMismaSesion() {
        when(google.buscar("ChIJabcdefghij123", "sesion-abc-123")).thenReturn(List.of(lugar("Unicentro"), lugar("Otro parecido")));
        var r = servicio.buscarDetallado("Unicentro", "g:ChIJabcdefghij123", "sesion-abc-123");
        assertEquals(1, r.lugares().size(), "sin un segundo menú de coincidencias");
        assertEquals("Unicentro", r.lugares().get(0).nombre());
        verify(google, never()).buscarTexto(anyString());
    }

    @Test void siFallaElDetalleDelElegidoNoSeEligeOtroEnSilencio() {
        when(google.buscar(anyString(), any())).thenThrow(new GoogleDireccionesService.ErrorGoogle(GoogleDireccionesService.ErrorGoogle.Tipo.SERVICIO, "x"));
        var r = servicio.buscarDetallado("Unicentro", "g:ChIJabcdefghij123", null);
        assertTrue(r.lugares().isEmpty());
        assertEquals(LugarService.ERROR_SERVICIO, r.estado());
        assertTrue(r.avisos().stream().anyMatch(a -> a.contains("Google")));
        verify(google, never()).buscarTexto(anyString());
    }

    @Test void unElegidoFueraDeLaCiudadNoSeSustituyeConOtroLugar() {
        when(google.buscar(anyString(), any())).thenReturn(List.of());
        var r = servicio.buscarDetallado("Unicentro Bogotá", "g:ChIJabcdefghij123", null);
        assertTrue(r.lugares().isEmpty());
        assertEquals(LugarService.SIN_RESULTADOS, r.estado());
        verify(google, never()).buscarTexto(anyString());
    }

    @Test void elPuntoEnElMapaNoConsultaProveedoresDeDirecciones() {
        when(repositorio.masCercano(anyDouble(), anyDouble(), anyDouble())).thenReturn(Optional.empty());
        var l = servicio.reverso(4.14, -73.63);
        assertTrue(l.nombre().startsWith("Punto en mapa"));
        assertEquals("Punto seleccionado manualmente", l.fuente());
        verifyNoInteractions(google, locales);
        assertThrows(RuntimeException.class, () -> servicio.reverso(10.0, -73.63), "fuera de Villavicencio se rechaza");
    }
}
