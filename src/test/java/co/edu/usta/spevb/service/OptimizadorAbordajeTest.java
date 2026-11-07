package co.edu.usta.spevb.service;

import co.edu.usta.spevb.config.CityArea;
import co.edu.usta.spevb.repository.ViajeRepository;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class OptimizadorAbordajeTest {
    private static final double LAT0 = 4.14, LNG0 = -73.63, PASO = 0.001;
    private static RedPeatonalService servicioRed;

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
        servicioRed = new RedPeatonalService(c.construir(), new CityArea("Villavicencio", "Meta", "Colombia", 3.95, 4.35, -73.85, -73.45));
    }

    /** Ruta de oeste a este por la calle i=3, con una muestra cada ≈ 15 m. */
    private static List<ViajeRepository.CandidatoRow> muestras(String sentido) {
        double largo = 7 * PASO * 111_320 * Math.cos(Math.toRadians(LAT0));
        int n = (int) Math.ceil(largo / 15);
        List<ViajeRepository.CandidatoRow> r = new ArrayList<>();
        for (int k = 0; k <= n; k++) {
            double f = (double) k / n;
            r.add(new ViajeRepository.CandidatoRow(1, sentido, largo, f, LNG0 + f * 7 * PASO, LAT0 + 3 * PASO, 0, null));
        }
        return r;
    }

    /** Igual que {@link #muestras} pero la ruta mide 8 km: cada metro de la retícula equivale a ≈ 10 m de bus. */
    private static List<ViajeRepository.CandidatoRow> muestrasLargas() {
        return muestras("IDA").stream().map(c -> new ViajeRepository.CandidatoRow(1, "IDA", 8000, c.fraccion(), c.lng(), c.lat(), 0, null)).toList();
    }

    private AbordajeService servicio(String sentido) {
        var repo = mock(ViajeRepository.class);
        when(repo.muestras(anyList(), anyDouble(), anyDouble(), anyDouble(), anyDouble(), anyDouble(), anyDouble(), anyDouble()))
                .thenReturn(muestras(sentido));
        var s = new AbordajeService(repo, mock(CaminataService.class));
        s.usarRed(servicioRed);
        return s;
    }

    @Test void eligeElPuntoConMenosCaminataPorCallesYRespetaElSentido() {
        var s = servicio("IDA");
        // A al sur de la ruta, en el centro de la manzana de la columna 1–2; B al norte, columna 5–6.
        double[] a = {LAT0 + 0.0005, LNG0 + 0.0015}, b = {LAT0 + 0.0055, LNG0 + 0.0055};
        var optimo = s.optimizar(a, b, List.of(1L), 2000, 2000).get(1L).menosCaminata();
        assertNotNull(optimo);
        assertTrue(optimo.permitido());
        assertTrue(optimo.subida().fraccion() < optimo.bajada().fraccion(), "se sube antes de bajar");
        // Caminar desde A por calles hasta la ruta (i=3) es: salir a una calle vertical y subir ≈ 2,5 manzanas.
        assertTrue(optimo.subida().caminataMetros() < 345, "caminata a la subida: " + optimo.subida().caminataMetros());
        // La subida cae sobre una calle vertical (columna 1 o 2): ahí no hay que rodear la manzana.
        double col = (optimo.subida().lng() - LNG0) / PASO;
        assertTrue(Math.abs(col - Math.rint(col)) < 0.12, "subida en una esquina/calle transversal: " + col);
    }

    @Test void sinSentidoPosibleLaOpcionQuedaMarcadaComoInversa() {
        var s = servicio("IDA");
        double[] a = {LAT0 + 0.0035, LNG0 + 0.0065}, b = {LAT0 + 0.0035, LNG0 + 0.0005};   // de este a oeste
        var optimo = s.optimizar(a, b, List.of(1L), 2000, 2000).get(1L).menosCaminata();
        assertNotNull(optimo);
        assertFalse(optimo.permitido());
    }

    @Test void enRutaCircularSiempreHayOpcionPermitida() {
        var s = servicio("CIRCULAR");
        double[] a = {LAT0 + 0.0035, LNG0 + 0.0065}, b = {LAT0 + 0.0035, LNG0 + 0.0005};
        assertTrue(s.optimizar(a, b, List.of(1L), 2000, 2000).get(1L).menosCaminata().permitido());
    }

    @Test void sinRedListaDevuelveNullYSeUsaElMetodoAproximado() {
        var s = new AbordajeService(mock(ViajeRepository.class), mock(CaminataService.class));
        assertNull(s.optimizar(new double[]{4.14, -73.63}, new double[]{4.15, -73.62}, List.of(1L), 2000, 2000));
    }

    @Test void laVarianteRapidaSubeMasAdelanteSiAsiAhorraTiempoEnBus() {
        var repo = mock(ViajeRepository.class);
        when(repo.muestras(anyList(), anyDouble(), anyDouble(), anyDouble(), anyDouble(), anyDouble(), anyDouble(), anyDouble()))
                .thenReturn(muestrasLargas());
        var s = new AbordajeService(repo, mock(CaminataService.class));
        s.usarRed(servicioRed);
        double[] a = {LAT0 + 0.0035, LNG0 + 0.0005}, b = {LAT0 + 0.0035, LNG0 + 0.0065};
        var o = s.optimizar(a, b, List.of(1L), 2000, 2000).get(1L);
        assertTrue(o.menosCaminata().subida().caminataMetros() <= o.masRapida().subida().caminataMetros(),
                "la de menos caminata nunca camina más hasta la subida");
        assertTrue(o.masRapida().bajada().fraccion() - o.masRapida().subida().fraccion()
                <= o.menosCaminata().bajada().fraccion() - o.menosCaminata().subida().fraccion() + 1e-9,
                "la rápida pasa menos o igual tramo en bus");
        double caminaMenos = o.menosCaminata().subida().caminataMetros() + o.menosCaminata().bajada().caminataMetros();
        double caminaRapida = o.masRapida().subida().caminataMetros() + o.masRapida().bajada().caminataMetros();
        assertTrue(caminaRapida >= caminaMenos - 1e-9);
    }

    @Test void mejoresSubidasEligeElPuntoMasFacilDeAlcanzarCaminando() {
        var s = servicio("IDA");
        var m = s.mejoresSubidas(new double[]{LAT0 + 0.0005, LNG0 + 0.0015}, List.of(1L), 2000);
        assertNotNull(m);
        assertTrue(m.get(1L).caminataMetros() < 345);
        assertNull(new AbordajeService(mock(ViajeRepository.class), mock(CaminataService.class))
                .mejoresSubidas(new double[]{4.14, -73.63}, List.of(1L), 2000));
    }
}
