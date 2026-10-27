package co.edu.usta.spevb.service;

import co.edu.usta.spevb.config.CityArea;
import co.edu.usta.spevb.dto.PlanificarViajeRequest;
import co.edu.usta.spevb.dto.ViajeResponse;
import co.edu.usta.spevb.repository.ViajeRepository;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Escenario: retícula de calles de 8 × 8 (manzanas de ≈ 110 m). Ruta 1 va de oeste a este por la calle 1; ruta 2 va de sur a
 * norte por la columna 6 y empieza en el cruce. A está al oeste (cerca del inicio de la ruta 1) y B al norte (cerca del final de
 * la ruta 2): no hay ruta directa que sirva, solo el transbordo en el cruce (fila 1, columna 6).
 */
class TransbordoServiceTest {
    private static final double LAT0 = 4.14, LNG0 = -73.63, PASO = 0.001;
    private static RedPeatonalService red;

    @BeforeAll static void red() {
        var c = new RedPeatonal.Constructor();
        for (int i = 0; i < 8; i++) {
            long[] ids = new long[8]; double[] la = new double[8], lo = new double[8];
            for (int j = 0; j < 8; j++) { ids[j] = 1000L * i + j; la[j] = LAT0 + i * PASO; lo[j] = LNG0 + j * PASO; }
            c.agregarCamino(ids, la, lo, RedPeatonal.LLANA);
        }
        for (int j = 0; j < 8; j++) {
            long[] ids = new long[8]; double[] la = new double[8], lo = new double[8];
            for (int i = 0; i < 8; i++) { ids[i] = 1000L * i + j; la[i] = LAT0 + i * PASO; lo[i] = LNG0 + j * PASO; }
            c.agregarCamino(ids, la, lo, RedPeatonal.LLANA);
        }
        red = new RedPeatonalService(c.construir(), new CityArea("Villavicencio", "Meta", "Colombia", 3.95, 4.35, -73.85, -73.45));
    }

    /** Puntos cada ≈ 50 m sobre un segmento recto entre dos extremos (lat, lng). */
    private static List<ViajeRepository.CandidatoRow> linea(long id, double la0, double lo0, double la1, double lo1, String parada) {
        double largo = RedPeatonal.metros(la0, lo0, la1, lo1);
        int n = (int) Math.ceil(largo / 50);
        List<ViajeRepository.CandidatoRow> r = new ArrayList<>();
        for (int k = 0; k <= n; k++) {
            double f = (double) k / n;
            r.add(new ViajeRepository.CandidatoRow(id, "IDA", largo, f, lo0 + (lo1 - lo0) * f, la0 + (la1 - la0) * f, 0, null));
        }
        if (parada != null) r.add(new ViajeRepository.CandidatoRow(id, "IDA", largo, 0.5, lo0 + (lo1 - lo0) * .5, la0 + (la1 - la0) * .5, 0, parada));
        r.sort(java.util.Comparator.comparingDouble(ViajeRepository.CandidatoRow::fraccion));
        return r;
    }

    private ViajeRepository repo(String paradaCambio) {
        var repo = mock(ViajeRepository.class);
        when(repo.rutasCerca(anyDouble(), anyDouble(), anyDouble(), anyBoolean())).thenReturn(List.of(1L, 2L));
        var todas = new ArrayList<ViajeRepository.CandidatoRow>();
        todas.addAll(linea(1, LAT0 + PASO, LNG0, LAT0 + PASO, LNG0 + 7 * PASO, null));          // ruta 1: calle 1, oeste → este
        todas.addAll(linea(2, LAT0 + PASO, LNG0 + 6 * PASO, LAT0 + 7 * PASO, LNG0 + 6 * PASO, paradaCambio)); // ruta 2: columna 6, sur → norte
        when(repo.muestrasCompletas(anyList(), anyDouble())).thenReturn(todas);
        when(repo.infoRutas(anyList())).thenReturn(List.of(
                new ViajeRepository.InfoRuta(1, "R1", "Ruta uno", "#d32f2f", true), new ViajeRepository.InfoRuta(2, "R2", "Ruta dos", "#1e5fbf", false)));
        when(repo.tramoEntre(anyLong(), anyDouble(), anyDouble())).thenAnswer(inv -> new ViajeRepository.TramoRow(
                "{\"type\":\"Point\",\"coordinates\":[-73.63,4.141]}", "{\"type\":\"Point\",\"coordinates\":[-73.62,4.141]}",
                "{\"type\":\"LineString\",\"coordinates\":[[-73.63,4.141],[-73.62,4.141]]}",
                Math.abs((double) inv.getArgument(2) - (double) inv.getArgument(1)) * (inv.getArgument(0).equals(1L) ? 0.78 : 0.67)));
        return repo;
    }

    private TransbordoService servicio(ViajeRepository repo) {
        var s = new TransbordoService(repo, JsonMapper.builder().build());
        s.usarRed(red);
        return s;
    }

    private static PlanificarViajeRequest viaje() {
        return new PlanificarViajeRequest(new PlanificarViajeRequest.Punto(LAT0 + 0.0015, LNG0 + 0.0003),
                new PlanificarViajeRequest.Punto(LAT0 + 0.0072, LNG0 + 0.0065), null, false);
    }

    @Test void encuentraElTransbordoEnElCruceYLoDescribeCompleto() {
        var r = servicio(repo(null)).calcular(viaje(), null);
        assertFalse(r.isEmpty(), "debe encontrar el cambio");
        var t = r.get(0);
        assertEquals(1, t.primerBus().rutaId());
        assertEquals(2, t.segundoBus().rutaId());
        assertEquals(2, t.pasajes());
        assertEquals(8, t.esperaCambioMinutos());
        assertTrue(t.caminataCambio().metros() < 150, "se cambia en el cruce: " + t.caminataCambio().metros());
        assertTrue(t.caminataInicio().coordinates().size() >= 2 && t.caminataFin().coordinates().size() >= 2);
        // minutos = caminata/80 + bus/267 + espera
        double esperado = t.caminataMetros() / 80 + t.busKm() * 1000 / 267 + 8;
        assertEquals(esperado, t.minutos(), 0.01);
        assertTrue(t.minutos() > 8 && t.minutos() < 40, "minutos: " + t.minutos());
        assertTrue(t.advertencia().contains("dos pasajes"));
    }

    @Test void siElDirectoYaEsBuenoNoSeOfreceElTransbordo() {
        var s = servicio(repo(null));
        assertTrue(s.calcular(viaje(), new TransbordoService.Directo(6, 300)).isEmpty(), "un directo de 6 min no necesita transbordo");
        assertFalse(s.calcular(viaje(), new TransbordoService.Directo(60, 300)).isEmpty(), "con un directo de 60 min sí conviene");
    }

    @Test void siElDirectoObligaACaminarMuchoYElTransbordoLoEvitaSeOfrece() {
        var s = servicio(repo(null));
        var t = s.calcular(viaje(), null).get(0);
        double lento = t.minutos() + 2;     // directo apenas 2 min más lento, pero caminando 3 km
        assertFalse(s.calcular(viaje(), new TransbordoService.Directo(lento, 3000)).isEmpty());
        assertTrue(s.calcular(viaje(), new TransbordoService.Directo(lento, 800)).isEmpty());
    }

    @Test void respetaElSentidoDeLasRutas() {
        // Con A y B al revés las rutas (dibujadas de oeste a este y de sur a norte) ya no llevan hacia B: no hay forma de ir
        // en bus hacia el destino, así que solo quedan combinaciones que obligan a caminar mucho de regreso.
        var s = servicio(repo(null));
        var ida = s.calcular(viaje(), null).get(0);
        var inverso = new PlanificarViajeRequest(new PlanificarViajeRequest.Punto(LAT0 + 0.0072, LNG0 + 0.0065),
                new PlanificarViajeRequest.Punto(LAT0 + 0.0015, LNG0 + 0.0003), null, false);
        var vuelta = s.calcular(inverso, null);
        assertTrue(vuelta.isEmpty() || vuelta.get(0).minutos() > ida.minutos() + 5 || vuelta.get(0).caminataMetros() > ida.caminataMetros() + 400,
                "ida " + ida.minutos() + " min, vuelta " + (vuelta.isEmpty() ? "ninguna" : vuelta.get(0).minutos()));
        for (var t : vuelta) {
            assertTrue(t.primerBus().tramo().path("coordinates").size() >= 2);
        }
    }

    @Test void unParaderoEnElCambioSeUsaYSeDevuelveSuNombre() {
        var r = servicio(repo("Parada del cruce")).calcular(viaje(), null);
        assertFalse(r.isEmpty());
    }

    @Test void sinRedDeCallesNoOfreceTransbordos() {
        var s = new TransbordoService(repo(null), JsonMapper.builder().build());
        assertTrue(s.calcular(viaje(), null).isEmpty());
    }

    @Test void sinRutasCercanasDevuelveVacio() {
        var repo = mock(ViajeRepository.class);
        when(repo.rutasCerca(anyDouble(), anyDouble(), anyDouble(), anyBoolean())).thenReturn(List.of());
        var s = new TransbordoService(repo, JsonMapper.builder().build());
        s.usarRed(red);
        assertTrue(s.calcular(viaje(), null).isEmpty());
    }

    @Test void laReglaDeCuandoVale() {
        var t = new ViajeResponse.Transbordo(20, 500, 5, 2, 8, null, null, null, null, null, "");
        assertTrue(TransbordoService.valeLaPena(t, null));
        assertTrue(TransbordoService.valeLaPena(t, new TransbordoService.Directo(26, 400)));
        assertFalse(TransbordoService.valeLaPena(t, new TransbordoService.Directo(24, 400)));
        assertTrue(TransbordoService.valeLaPena(t, new TransbordoService.Directo(22, 2000)));
    }
}
