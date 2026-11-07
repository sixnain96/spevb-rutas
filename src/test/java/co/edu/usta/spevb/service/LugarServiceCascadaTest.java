package co.edu.usta.spevb.service;

import co.edu.usta.spevb.config.CityArea;
import co.edu.usta.spevb.dto.LugarResponse;
import co.edu.usta.spevb.repository.LugarRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Política de búsqueda: Google → ArcGIS → local (+ Nominatim solo explícita), estados tipados, plazos, cortacircuito y elección sin sustitución. */
class LugarServiceCascadaTest {
    private final CityArea area = new CityArea("Villavicencio", "Meta", "Colombia", 3.95, 4.35, -73.85, -73.45);
    private LugarRepository repositorio;
    private DireccionesHibridasService hibrido;
    private DireccionesLocalesService locales;
    private GoogleDireccionesService google;
    private GeocodingService nominatim;
    private LugarService servicio;

    private static LugarResponse lugar(String nombre, String direccion, String fuente, String precision) {
        return new LugarResponse(null, nombre, direccion, 4.1272, -73.6203, fuente, false, precision);
    }
    private static ResultadoLugares lugares(LugarResponse... l) { return new ResultadoLugares(List.of(l), 0); }
    private static GoogleDireccionesService.Sugerencia sg(String t, String id) { return new GoogleDireccionesService.Sugerencia(t, id); }

    @BeforeEach void preparar() {
        repositorio = mock(LugarRepository.class);
        hibrido = mock(DireccionesHibridasService.class);
        locales = mock(DireccionesLocalesService.class);
        google = mock(GoogleDireccionesService.class);
        nominatim = mock(GeocodingService.class);
        servicio = new LugarService(repositorio, nominatim, area, hibrido, locales);
        servicio.usarGoogle(google);
        when(google.activo()).thenReturn(true);
        when(hibrido.arcgisActivo()).thenReturn(true);
        when(locales.disponible()).thenReturn(false);
        when(nominatim.configurado()).thenReturn(true);
        when(repositorio.buscarCatalogo(anyString(), anyInt())).thenReturn(List.of());
    }

    // ------------------------------------------------------------------ sugerencias: prioridad
    @Test void googleConResultadosUtilizablesNoConsultaArcgisNiLocal() {
        when(google.sugerir(eq("unic"), any())).thenReturn(List.of(sg("Unicentro Villavicencio, Meta", "ChIJabcdefghij123")));
        var r = servicio.sugerirDetallado("unic", null);
        assertEquals("google", r.proveedor());
        verify(hibrido, never()).sugerirArcgis(anyString());
        verify(locales, never()).sugerir(anyString(), anyInt());
    }

    @Test void googleVacioPasaAArcgis() {
        when(google.sugerir(anyString(), any())).thenReturn(List.of());
        when(hibrido.sugerirArcgis("unic")).thenReturn(List.of(new DireccionesHibridasService.Sugerencia("Unicentro Villavicencio", "magicKey12345")));
        var r = servicio.sugerirDetallado("unic", null);
        assertEquals("arcgis", r.proveedor());
        assertEquals("magicKey12345", r.sugerencias().get(0).magicKey());
        assertEquals(List.of("google", "arcgis"), r.intentos().stream().map(LugarService.Intento::proveedor).toList());
        assertEquals(EstadoBusqueda.SIN_COINCIDENCIAS, r.intentos().get(0).estado(), "vacío no es fallo");
    }

    @Test void siGoogleYArcgisFallanSeUsaElIndiceLocalYSeInformaQueFallaron() {
        when(locales.disponible()).thenReturn(true);
        when(google.sugerir(anyString(), any())).thenThrow(new ProveedorException(EstadoBusqueda.RED, "x"));
        when(hibrido.sugerirArcgis(anyString())).thenThrow(new ProveedorException(EstadoBusqueda.TIMEOUT, "x"));
        when(locales.sugerir(eq("unic"), anyInt())).thenReturn(List.of(new DireccionesLocalesService.SugerenciaLocal(1L, "Unicentro", "Catastro", 4.1272, -73.6203, "Catastro de Villavicencio (base propia)", "LUGAR")));
        var r = servicio.sugerirDetallado("unic", null);
        assertEquals("local", r.proveedor());
        assertEquals(EstadoBusqueda.OK, r.estado());
        assertEquals(List.of(EstadoBusqueda.RED, EstadoBusqueda.TIMEOUT, EstadoBusqueda.OK), r.intentos().stream().map(LugarService.Intento::estado).toList());
    }

    @Test void sinGoogleLaCascadaEsArcgisYLuegoLocal() {
        when(google.activo()).thenReturn(false);
        when(locales.disponible()).thenReturn(true);
        when(hibrido.sugerirArcgis(anyString())).thenReturn(List.of());
        when(locales.sugerir(anyString(), anyInt())).thenReturn(List.of(new DireccionesLocalesService.SugerenciaLocal(1L, "Unicentro", "Catastro", 4.1272, -73.6203, "Catastro", "LUGAR")));
        var r = servicio.sugerirDetallado("unic", null);
        assertEquals("local", r.proveedor());
        verify(google, never()).sugerir(anyString(), any());
    }

    @Test void lasSugerenciasNuncaUsanNominatimAunqueTodoFalleOQuedeVacio() {
        when(google.sugerir(anyString(), any())).thenReturn(List.of());
        when(hibrido.sugerirArcgis(anyString())).thenReturn(List.of());
        var r = servicio.sugerirDetallado("unic", null);
        assertEquals(EstadoBusqueda.SIN_COINCIDENCIAS, r.estado());
        verify(nominatim, never()).buscar(anyString());
    }

    @Test void unaPrediccionGenericaOQueNoConservaLaViaNoEsUtilizable() {
        // «Villavicencio» no responde a la búsqueda de un lugar; «Calle 20…» no responde a «Carrera 22 #38».
        when(google.sugerir(anyString(), any())).thenReturn(List.of(sg("Villavicencio, Meta, Colombia", "ChIJgenerica0001")));
        when(hibrido.sugerirArcgis(anyString())).thenReturn(List.of(new DireccionesHibridasService.Sugerencia("Unicentro Villavicencio", "magicKey12345")));
        assertEquals("arcgis", servicio.sugerirDetallado("unicentro", null).proveedor());

        when(google.sugerir(eq("Carrera 22 #38"), any())).thenReturn(List.of(sg("Calle 20 #38-10, Villavicencio", "ChIJotravia00001")));
        when(hibrido.sugerirArcgis("Carrera 22 #38")).thenReturn(List.of());
        var r = servicio.sugerirDetallado("Carrera 22 #38", null);
        assertTrue(r.sugerencias().isEmpty());
        assertEquals(EstadoBusqueda.SIN_COINCIDENCIAS, r.estado());
    }

    // ------------------------------------------------------------------ estados tipados
    @Test void distingueSinCoincidenciasDeCadaTipoDeFallo() {
        when(google.sugerir(anyString(), any())).thenThrow(new ProveedorException(EstadoBusqueda.CUOTA, "x"));
        when(hibrido.sugerirArcgis(anyString())).thenThrow(new ProveedorException(EstadoBusqueda.CLAVE, "x"));
        assertEquals(EstadoBusqueda.CLAVE, servicio.sugerirDetallado("unic", null).estado(), "gana el fallo más grave");

        reset(google);
        when(google.activo()).thenReturn(true);
        when(google.sugerir(anyString(), any())).thenThrow(new ProveedorException(EstadoBusqueda.RESPUESTA_INVALIDA, "x"));
        doThrow(new ProveedorException(EstadoBusqueda.RED, "x")).when(hibrido).sugerirArcgis(anyString());
        assertEquals(EstadoBusqueda.RED, servicio.sugerirDetallado("unic", null).estado());

        reset(google, hibrido);
        when(google.activo()).thenReturn(false);
        when(hibrido.arcgisActivo()).thenReturn(false);
        assertEquals(EstadoBusqueda.SIN_COINCIDENCIAS, servicio.sugerirDetallado("unic", null).estado(), "sin proveedores ni base local: sin coincidencias");
    }

    @Test void clasificaLosErroresDeLosProveedores() {
        assertEquals(EstadoBusqueda.CUOTA, ProveedorException.desdeHttp(429, ""));
        assertEquals(EstadoBusqueda.CUOTA, ProveedorException.desdeHttp(200, "{\"error\":{\"status\":\"RESOURCE_EXHAUSTED\"}}"));
        assertEquals(EstadoBusqueda.CLAVE, ProveedorException.desdeHttp(403, ""));
        assertEquals(EstadoBusqueda.CLAVE, ProveedorException.desdeHttp(400, "{\"error\":{\"message\":\"API key not valid\",\"status\":\"INVALID_ARGUMENT\",\"details\":[{\"reason\":\"API_KEY_INVALID\"}]}}"));
        assertEquals(EstadoBusqueda.RED, ProveedorException.desdeHttp(500, ""));
        assertEquals(EstadoBusqueda.TIMEOUT, ProveedorException.clasificar(new java.util.concurrent.TimeoutException()));
        assertEquals(EstadoBusqueda.CUOTA, ProveedorException.clasificar(new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "x")));
        assertEquals(EstadoBusqueda.RESPUESTA_INVALIDA, ProveedorException.clasificar(new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Respuesta geográfica inválida.")));
        assertEquals(EstadoBusqueda.RED, ProveedorException.clasificar(new java.io.IOException("x")));
        assertFalse(EstadoBusqueda.SIN_COINCIDENCIAS.esFallo());
        assertTrue(EstadoBusqueda.CUOTA.esFallo());
    }

    // ------------------------------------------------------------------ búsqueda explícita
    @Test void laBusquedaExplicitaUsaGoogleYSoloSiNoResuelveSigueConArcgisYElRespaldo() {
        when(google.buscarTextoResultado("Unicentro")).thenReturn(lugares(lugar("Unicentro", "Cra 33", "Google Maps", "LUGAR")));
        var r = servicio.buscarDetallado("Unicentro", "");
        assertEquals("google", r.proveedor());
        verify(hibrido, never()).buscarArcgisResultado(anyString(), anyString());
        verify(nominatim, never()).buscar(anyString());

        when(google.buscarTextoResultado("Hospital")).thenReturn(ResultadoLugares.VACIO);
        when(hibrido.buscarArcgisResultado("Hospital", "")).thenReturn(lugares(lugar("Hospital Departamental", "Villavicencio", "ArcGIS", "LUGAR")));
        assertEquals("arcgis", servicio.buscarDetallado("Hospital", "").proveedor());
        verify(nominatim, never()).buscar(anyString());
    }

    @Test void nominatimSoloEntraEnLaBusquedaExplicitaCuandoNingunProveedorResuelve() {
        when(google.buscarTextoResultado(anyString())).thenReturn(ResultadoLugares.VACIO);
        when(hibrido.buscarArcgisResultado(anyString(), anyString())).thenReturn(ResultadoLugares.VACIO);
        when(nominatim.buscar("Parque Los Libertadores")).thenReturn(List.of(
                new GeocodingService.Resultado("Parque Los Libertadores", "Villavicencio", 4.15, -73.63, "OpenStreetMap / Nominatim", "osm:way:1")));
        var r = servicio.buscarDetallado("Parque Los Libertadores", "");
        assertEquals("nominatim", r.proveedor());
        assertEquals(EstadoBusqueda.OK, r.estado());
        verify(nominatim, times(1)).buscar("Parque Los Libertadores");
    }

    @Test void unaCiudadNoResponderAUnaDireccionNumeradaYSeSigueConLaSiguienteFuente() {
        when(google.buscarTextoResultado("Carrera 22 #38-10")).thenReturn(lugares(
                lugar("Villavicencio", "Villavicencio, Meta", "Google Maps", "CIUDAD"), lugar("Carrera 22", "Cra. 22, Villavicencio", "Google Maps", "VIA")));
        when(hibrido.buscarArcgisResultado("Carrera 22 #38-10", "")).thenReturn(lugares(
                lugar("Cra 22 #38-10", "Cra 22 #38-10, Villavicencio", "ArcGIS World Geocoding", "DIRECCION_PUNTUAL")));
        var r = servicio.buscarDetallado("Carrera 22 #38-10", "");
        assertEquals("arcgis", r.proveedor());
        assertEquals(1, r.lugares().size());
    }

    @Test void lasFallasDeLaExplicitaSeExplicanYNoSeVuelvenNoHayDirecciones() {
        when(google.buscarTextoResultado(anyString())).thenThrow(new ProveedorException(EstadoBusqueda.CUOTA, "x"));
        when(hibrido.buscarArcgisResultado(anyString(), anyString())).thenThrow(new ProveedorException(EstadoBusqueda.TIMEOUT, "x"));
        when(nominatim.buscar(anyString())).thenThrow(new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "x"));
        var r = servicio.buscarDetallado("Unicentro", "");
        assertTrue(r.lugares().isEmpty());
        assertEquals(EstadoBusqueda.CUOTA, r.estado());
        assertEquals(List.of("google", "arcgis", "nominatim"), r.intentos().stream().map(LugarService.Intento::proveedor).toList());
        assertTrue(r.avisos().stream().anyMatch(a -> a.contains("Google") && a.contains("límite")));
        assertTrue(r.avisos().stream().anyMatch(a -> a.contains("ArcGIS") && a.contains("tardó")));
    }

    @Test void fueraDeLaCiudadSeDistingueDeSinCoincidencias() {
        when(google.buscarTextoResultado(anyString())).thenReturn(new ResultadoLugares(List.of(), 2));
        when(hibrido.arcgisActivo()).thenReturn(false);
        when(nominatim.configurado()).thenReturn(false);
        assertEquals(EstadoBusqueda.FUERA_DE_CIUDAD, servicio.buscarDetallado("Unicentro Bogotá", "").estado());
    }

    // ------------------------------------------------------------------ elección de una sugerencia
    @Test void laEleccionFallidaNoSeSustituyeNiConOtroProveedorNiConUnaBusquedaGenerica() {
        when(locales.disponible()).thenReturn(true);
        when(locales.buscar(anyString(), anyInt())).thenReturn(List.of(lugar("Otro lugar parecido", "x", "Catastro", "LUGAR")));
        when(hibrido.buscarArcgisResultado(anyString(), anyString())).thenReturn(lugares(lugar("Otro de ArcGIS", "x", "ArcGIS", "LUGAR")));
        when(google.resolver(eq("ChIJabcdefghij123"), any(), any())).thenReturn(ResultadoLugares.VACIO);
        var r = servicio.buscarDetallado("Unicentro", "g:ChIJabcdefghij123");
        assertTrue(r.eleccionFallida());
        assertEquals(EstadoBusqueda.SIN_COINCIDENCIAS, r.estado());
        assertTrue(r.lugares().isEmpty(), "no se acepta «el primer resultado» de otra fuente");
        verifyNoInteractions(nominatim);
        verify(hibrido, never()).buscarArcgisResultado(anyString(), anyString());
        verify(locales, never()).buscar(anyString(), anyInt());
    }

    @Test void laEleccionFueraDeLaCiudadSeInformaConSuEstado() {
        when(google.resolver(anyString(), any(), any())).thenReturn(new ResultadoLugares(List.of(), 1));
        var r = servicio.buscarDetallado("Unicentro Bogotá", "g:ChIJabcdefghij123");
        assertTrue(r.eleccionFallida());
        assertEquals(EstadoBusqueda.FUERA_DE_CIUDAD, r.estado());
    }

    @Test void elTokenDeSesionLlegaAlDetalleYLaEleccionConGoogleInactivoFallaConClave() {
        when(google.resolver("ChIJabcdefghij123", "Unicentro", "sesion-1234567890abcd")).thenReturn(lugares(lugar("Unicentro", "Cra 33", "Google Maps", "LUGAR")));
        assertEquals(1, servicio.buscarDetallado("Unicentro", "g:ChIJabcdefghij123", "sesion-1234567890abcd").lugares().size());
        when(google.sugerir(anyString(), eq("sesion-1234567890abcd"))).thenReturn(List.of(sg("Unicentro Villavicencio", "ChIJabcdefghij123")));
        servicio.sugerirDetallado("unic", "sesion-1234567890abcd");
        verify(google).sugerir("unic", "sesion-1234567890abcd");

        when(google.activo()).thenReturn(false);
        var r = servicio.buscarDetallado("Unicentro", "g:ChIJabcdefghij123");
        assertTrue(r.eleccionFallida());
        assertEquals(EstadoBusqueda.CLAVE, r.estado());
    }

    // ------------------------------------------------------------------ plazos y presupuesto
    @Test void unProveedorLentoSeCortaAlVencerSuPlazoYLaBusquedaSigueSinEsperarlo() {
        var rapido = new LugarService(repositorio, nominatim, area, hibrido, locales, 1000, 250, 1000, 250);
        rapido.usarGoogle(google);
        AtomicBoolean interrumpido = new AtomicBoolean();
        when(google.sugerir(anyString(), any())).thenAnswer(inv -> {
            try { Thread.sleep(5000); } catch (InterruptedException e) { interrumpido.set(true); Thread.currentThread().interrupt(); }
            return List.of(sg("Unicentro Villavicencio", "ChIJabcdefghij123"));
        });
        when(hibrido.sugerirArcgis(anyString())).thenReturn(List.of(new DireccionesHibridasService.Sugerencia("Unicentro Villavicencio", "magicKey12345")));
        long t0 = System.nanoTime();
        var r = rapido.sugerirDetallado("unic", null);
        long ms = (System.nanoTime() - t0) / 1_000_000;
        assertEquals("arcgis", r.proveedor());
        assertEquals(EstadoBusqueda.TIMEOUT, r.intentos().get(0).estado());
        assertTrue(ms < 1500, "no se espera al proveedor lento: " + ms + " ms");
        assertTimeoutPreemptively(java.time.Duration.ofSeconds(2), () -> { while (!interrumpido.get()) Thread.sleep(10); }, "la llamada lenta se interrumpe (se cancela la petición)");
    }

    @Test void elPresupuestoTotalNoSeAcumulaEnCascada() {
        var s = new LugarService(repositorio, nominatim, area, hibrido, locales, 600, 400, 600, 400);
        s.usarGoogle(google);
        when(google.sugerir(anyString(), any())).thenAnswer(inv -> { Thread.sleep(3000); return List.of(); });
        when(hibrido.sugerirArcgis(anyString())).thenAnswer(inv -> { Thread.sleep(3000); return List.of(); });
        long t0 = System.nanoTime();
        var r = s.sugerirDetallado("unic", null);
        long ms = (System.nanoTime() - t0) / 1_000_000;
        assertTrue(ms < 1200, "2 proveedores lentos no suman 6 s: " + ms + " ms");
        assertEquals(EstadoBusqueda.TIMEOUT, r.estado());
    }

    // ------------------------------------------------------------------ cortacircuito
    @Test void tresFallosSeguidosPonenAlProveedorEnPausaYLuegoSeHaceUnaPrueba() {
        AtomicLong ahora = new AtomicLong(1_000_000);
        servicio.usarReloj(ahora::get);
        when(google.sugerir(anyString(), any())).thenThrow(new ProveedorException(EstadoBusqueda.RED, "x"));
        when(hibrido.sugerirArcgis(anyString())).thenReturn(List.of(new DireccionesHibridasService.Sugerencia("Unicentro Villavicencio", "magicKey12345")));
        for (int i = 0; i < 3; i++) servicio.sugerirDetallado("unic", null);
        verify(google, times(3)).sugerir(anyString(), any());
        var pausado = servicio.sugerirDetallado("unic", null);
        verify(google, times(3)).sugerir(anyString(), any());          // la 4.ª pulsación NO llama a Google
        assertEquals(EstadoBusqueda.PAUSADO, pausado.intentos().get(0).estado());
        assertEquals("arcgis", pausado.proveedor(), "mientras Google está en pausa la búsqueda sigue con el siguiente");

        ahora.addAndGet(Cortacircuito.PAUSA_MS + 1);                    // pasa la pausa: UNA consulta de prueba
        doReturn(List.of(sg("Unicentro Villavicencio", "ChIJabcdefghij123"))).when(google).sugerir(anyString(), any());
        assertEquals("google", servicio.sugerirDetallado("unic", null).proveedor());
        verify(google, times(4)).sugerir(anyString(), any());
        assertEquals("google", servicio.sugerirDetallado("unic", null).proveedor(), "la prueba salió bien: el circuito se cerró");
    }

    @Test void elCortacircuitoAbreCierraYReabreConUnaSolaPruebaAlTiempo() {
        AtomicLong t = new AtomicLong(0);
        var c = new Cortacircuito(t::get);
        assertTrue(c.permitir());
        c.fallo(); c.fallo();
        assertTrue(c.permitir(), "con 2 fallos sigue abierto al paso");
        c.fallo();
        assertFalse(c.permitir(), "3 fallos seguidos: pausa");
        t.set(Cortacircuito.PAUSA_MS - 1);
        assertFalse(c.permitir());
        t.set(Cortacircuito.PAUSA_MS);
        assertTrue(c.permitir(), "una consulta de prueba");
        assertFalse(c.permitir(), "las demás esperan el resultado de la prueba");
        c.fallo();                                                       // la prueba falló → nueva pausa completa
        assertFalse(c.permitir());
        t.addAndGet(Cortacircuito.PAUSA_MS);
        assertTrue(c.permitir());
        c.exito();
        assertTrue(c.permitir()); assertTrue(c.permitir());
        c.fallo(); c.fallo();
        assertTrue(c.permitir(), "un éxito reinicia la cuenta de fallos seguidos");
    }

    @Test void sinCoincidenciasNoCuentaComoFalloDelProveedor() {
        when(google.sugerir(anyString(), any())).thenReturn(List.of());
        when(hibrido.arcgisActivo()).thenReturn(false);
        for (int i = 0; i < 6; i++) servicio.sugerirDetallado("unic", null);
        verify(google, times(6)).sugerir(anyString(), any());
    }
}
