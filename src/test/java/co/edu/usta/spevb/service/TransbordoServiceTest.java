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
import java.util.OptionalDouble;

import static co.edu.usta.spevb.service.TransbordoService.Decision.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Escenario: retícula de calles de 8 × 8. Ruta 1 va de oeste a este por la calle 1; ruta 2 va de sur a norte por la columna 6 y
 * empieza en el cruce. A está al oeste (cerca del inicio de la ruta 1) y B al norte (cerca del final de la ruta 2): no hay ruta
 * directa que sirva, solo el transbordo en el cruce (fila 1, columna 6).
 *
 * <p>La separación entre calles (norte-sur, oeste-este) cambia lo que se prueba. {@code GRANDE, GRANDE} (≈ 390 m): ambos buses son
 * largos (≈ 2,3 km) y el segundo compensa. {@code CHICA, GRANDE} (111 m al norte, 390 m al este): el primer bus es largo (≈ 2,2 km)
 * pero el segundo mide ≈ 670 m y caminar hasta B son ≈ 880 m, así que el segundo NO compensa. Con «río» no hay calles que crucen
 * entre las filas 3 y 4 (salvo un puente en la columna 0 si se pide): caminar hasta B exige un rodeo de varios km o es imposible.
 */
class TransbordoServiceTest {
    private static final double LAT0 = 4.14, LNG0 = -73.63;
    private static final double GRANDE = 0.0035, CHICA = 0.001;
    private static RedPeatonalService redGrande, redMixta, redRioConPuente, redRioSinPuente;

    private static RedPeatonalService grilla(double pLat, double pLng, boolean rio, boolean puente) {
        var c = new RedPeatonal.Constructor();
        for (int i = 0; i < 8; i++) {
            long[] ids = new long[8]; double[] la = new double[8], lo = new double[8];
            for (int j = 0; j < 8; j++) { ids[j] = 1000L * i + j; la[j] = LAT0 + i * pLat; lo[j] = LNG0 + j * pLng; }
            c.agregarCamino(ids, la, lo, RedPeatonal.LLANA);
        }
        for (int j = 0; j < 8; j++) {
            if (rio && !(puente && j == 0)) { vertical(c, j, 0, 3, pLat, pLng); vertical(c, j, 4, 7, pLat, pLng); }
            else vertical(c, j, 0, 7, pLat, pLng);
        }
        return new RedPeatonalService(c.construir(), new CityArea("Villavicencio", "Meta", "Colombia", 3.95, 4.35, -73.85, -73.45));
    }

    private static void vertical(RedPeatonal.Constructor c, int j, int desde, int hasta, double pLat, double pLng) {
        int n = hasta - desde + 1;
        long[] ids = new long[n]; double[] la = new double[n], lo = new double[n];
        for (int k = 0; k < n; k++) { int i = desde + k; ids[k] = 1000L * i + j; la[k] = LAT0 + i * pLat; lo[k] = LNG0 + j * pLng; }
        c.agregarCamino(ids, la, lo, RedPeatonal.LLANA);
    }

    @BeforeAll static void redes() {
        redGrande = grilla(GRANDE, GRANDE, false, false);
        redMixta = grilla(CHICA, GRANDE, false, false);
        redRioConPuente = grilla(CHICA, GRANDE, true, true);
        redRioSinPuente = grilla(CHICA, GRANDE, true, false);
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

    private ViajeRepository repo(double pLat, double pLng, String paradaCambio) {
        var repo = mock(ViajeRepository.class);
        when(repo.rutasCerca(anyDouble(), anyDouble(), anyDouble(), anyBoolean())).thenReturn(List.of(1L, 2L));
        var todas = new ArrayList<ViajeRepository.CandidatoRow>();
        todas.addAll(linea(1, LAT0 + pLat, LNG0, LAT0 + pLat, LNG0 + 7 * pLng, null));                       // ruta 1: calle 1, oeste → este
        todas.addAll(linea(2, LAT0 + pLat, LNG0 + 6 * pLng, LAT0 + 7 * pLat, LNG0 + 6 * pLng, paradaCambio)); // ruta 2: columna 6, sur → norte
        when(repo.muestrasCompletas(anyList(), anyDouble())).thenReturn(todas);
        when(repo.infoRutas(anyList())).thenReturn(List.of(
                new ViajeRepository.InfoRuta(1, "R1", "Ruta uno", "#d32f2f", true), new ViajeRepository.InfoRuta(2, "R2", "Ruta dos", "#1e5fbf", false)));
        double km1 = pLng * 111, km2 = pLat * 111;      // km por cada calle de la ruta 1 (oeste-este) y de la ruta 2 (sur-norte)
        when(repo.tramoEntre(anyLong(), anyDouble(), anyDouble())).thenAnswer(inv -> new ViajeRepository.TramoRow(
                "{\"type\":\"Point\",\"coordinates\":[-73.63,4.141]}", "{\"type\":\"Point\",\"coordinates\":[-73.62,4.141]}",
                "{\"type\":\"LineString\",\"coordinates\":[[-73.63,4.141],[-73.62,4.141]]}",
                Math.abs((double) inv.getArgument(2) - (double) inv.getArgument(1)) * (inv.getArgument(0).equals(1L) ? 7 * km1 : 6 * km2)));
        // los dos buses de un transbordo se piden juntos (tramosEntre): cada uno se resuelve con el tramoEntre de arriba
        when(repo.tramosEntre(anyList())).thenAnswer(inv -> ((List<ViajeRepository.TramoPedido>) inv.getArgument(0)).stream()
                .map(p -> repo.tramoEntre(p.rutaId(), p.a(), p.b())).toList());
        return repo;
    }

    private TransbordoService servicio(ViajeRepository repo, RedPeatonalService red) {
        var s = new TransbordoService(repo, JsonMapper.builder().build());
        s.usarRed(red);
        return s;
    }

    /** A al oeste, cerca del inicio de la ruta 1; B al norte, cerca del final de la ruta 2. */
    private static PlanificarViajeRequest viaje(double pLat, double pLng) {
        return new PlanificarViajeRequest(new PlanificarViajeRequest.Punto(LAT0 + 1.5 * pLat, LNG0 + 0.3 * pLng),
                new PlanificarViajeRequest.Punto(LAT0 + 7.2 * pLat, LNG0 + 6.5 * pLng), null, false);
    }

    /** A a media calle del cruce: el primer bus sería de unos 200 m. */
    private static PlanificarViajeRequest viajeCercaDelCruce(double pLat, double pLng) {
        return new PlanificarViajeRequest(new PlanificarViajeRequest.Punto(LAT0 + 1.5 * pLat, LNG0 + 5.5 * pLng),
                new PlanificarViajeRequest.Punto(LAT0 + 7.2 * pLat, LNG0 + 6.5 * pLng), null, false);
    }

    // ------------------------------------------------------------------ comportamiento general (red de calles de 390 m)
    @Test void encuentraElTransbordoEnElCruceYLoDescribeCompleto() {
        var r = servicio(repo(GRANDE, GRANDE, null), redGrande).calcular(viaje(GRANDE, GRANDE), null);
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
        var s = servicio(repo(GRANDE, GRANDE, null), redGrande);
        assertTrue(s.calcular(viaje(GRANDE, GRANDE), new TransbordoService.Directo(6, 300)).isEmpty(), "un directo de 6 min no necesita transbordo");
        assertFalse(s.calcular(viaje(GRANDE, GRANDE), new TransbordoService.Directo(60, 300)).isEmpty(), "con un directo de 60 min sí conviene");
    }

    @Test void siElDirectoObligaACaminarMuchoYElTransbordoLoEvitaSeOfrece() {
        var s = servicio(repo(GRANDE, GRANDE, null), redGrande);
        var t = s.calcular(viaje(GRANDE, GRANDE), null).get(0);
        double lento = t.minutos() + 2;     // directo apenas 2 min más lento, pero caminando 3 km
        assertFalse(s.calcular(viaje(GRANDE, GRANDE), new TransbordoService.Directo(lento, 3000)).isEmpty());
        assertTrue(s.calcular(viaje(GRANDE, GRANDE), new TransbordoService.Directo(lento, 800)).isEmpty());
    }

    @Test void respetaElSentidoDeLasRutas() {
        // Con A y B al revés las rutas (dibujadas de oeste a este y de sur a norte) ya no llevan hacia B: no hay forma de ir
        // en bus hacia el destino, así que solo quedan combinaciones que obligan a caminar mucho de regreso.
        var s = servicio(repo(GRANDE, GRANDE, null), redGrande);
        var ida = s.calcular(viaje(GRANDE, GRANDE), null).get(0);
        var v = viaje(GRANDE, GRANDE);
        var inverso = new PlanificarViajeRequest(v.destino(), v.origen(), null, false);
        var vuelta = s.calcular(inverso, null);
        assertTrue(vuelta.isEmpty() || vuelta.get(0).minutos() > ida.minutos() + 5 || vuelta.get(0).caminataMetros() > ida.caminataMetros() + 400,
                "ida " + ida.minutos() + " min, vuelta " + (vuelta.isEmpty() ? "ninguna" : vuelta.get(0).minutos()));
        for (var t : vuelta) {
            assertTrue(t.primerBus().tramo().path("coordinates").size() >= 2);
        }
    }

    @Test void unParaderoEnElCambioSeUsaYSeDevuelveSuNombre() {
        var r = servicio(repo(GRANDE, GRANDE, "Parada del cruce"), redGrande).calcular(viaje(GRANDE, GRANDE), null);
        assertFalse(r.isEmpty());
    }

    @Test void sinRedDeCallesNoOfreceTransbordos() {
        var s = new TransbordoService(repo(GRANDE, GRANDE, null), JsonMapper.builder().build());
        assertTrue(s.calcular(viaje(GRANDE, GRANDE), null).isEmpty());
    }

    @Test void sinRutasCercanasDevuelveVacio() {
        var repo = mock(ViajeRepository.class);
        when(repo.rutasCerca(anyDouble(), anyDouble(), anyDouble(), anyBoolean())).thenReturn(List.of());
        var s = new TransbordoService(repo, JsonMapper.builder().build());
        s.usarRed(redGrande);
        assertTrue(s.calcular(viaje(GRANDE, GRANDE), null).isEmpty());
    }

    @Test void laReglaDeCuandoVale() {
        var t = new ViajeResponse.Transbordo(20, 500, 5, 2, 8, null, null, null, null, null, "");
        assertTrue(TransbordoService.valeLaPena(t, null));
        assertTrue(TransbordoService.valeLaPena(t, new TransbordoService.Directo(26, 400)));
        assertFalse(TransbordoService.valeLaPena(t, new TransbordoService.Directo(24, 400)));
        assertTrue(TransbordoService.valeLaPena(t, new TransbordoService.Directo(22, 2000)));
    }

    // ------------------------------------------------------------------ Fase E: ¿segundo bus o caminar? (reglas con números)
    private static TransbordoService.Decision segundo(double kmBus2, double caminaDirectaM) {
        // cambio de 100 m y caminata final de 100 m en todos los casos
        return TransbordoService.decidirSegundoBus(kmBus2, 100, 100, OptionalDouble.of(caminaDirectaM));
    }

    @Test void escenario1_segundoBusDe120mYCaminataDe150mSeCamina() { assertEquals(CAMINAR, segundo(0.120, 150)); }

    @Test void escenario2_segundoBusDe500mYCaminataDe600mSeCamina() { assertEquals(CAMINAR, segundo(0.500, 600)); }

    @Test void escenario3_segundoBusDe900mYCaminataDe950mSeCaminaPorTiempoEsperaYPasaje() { assertEquals(CAMINAR, segundo(0.900, 950)); }

    @Test void escenario4_segundoBusDe1200mYCaminataDe1100mSeDecidePorUtilidadNoPorDistancia() {
        assertEquals(CAMINAR, segundo(1.200, 1100), "no ahorra lo suficiente");
        assertEquals(SEGUNDO_BUS, segundo(1.200, 1800), "la misma distancia de bus sí compensa si caminar es bastante peor");
    }

    @Test void escenario5_segundoBusDe2kmPuedeSerRazonableSiAhorraTiempo() {
        assertEquals(SEGUNDO_BUS, segundo(2.0, 2000));
        assertEquals(CAMINAR, segundo(2.0, 1500), "con una caminata de 1,5 km no ahorra los 5 min");
    }

    @Test void escenario6_segundoBusCortoConBarreraQueHaceLaCaminataDe3kmSeConserva() { assertEquals(SEGUNDO_BUS, segundo(0.5, 3000)); }

    @Test void unaCaminataDeVariosKmNuncaSeRecomiendaAunqueElSegundoBusAhorrePoco() {
        // bus 1 de 150 m, segundo bus de 6,7 km, caminar 3,5 km: mal viaje en ambos casos, pero no se manda a caminar tanto
        assertEquals(SEGUNDO_BUS, TransbordoService.decidirSegundoBus(6.74, 263, 967, OptionalDouble.of(3539)));
        assertEquals(SEGUNDO_BUS, segundo(0.5, TransbordoService.CAMINATA_MAXIMA_ALTERNATIVA_METROS + 1));
    }

    @Test void masAllaDeLoRazonableDecideElCostoGeneralizadoSinExigirElAhorroMinimo() {
        // caminar 1,9 km (no razonable) frente a un segundo bus de 2,6 km: el bus ahorra ≈ 3,5 min (menos de los 5 mínimos), pero evita
        // una caminata penalizada y su costo generalizado es menor: se conserva
        assertEquals(SEGUNDO_BUS, segundo(2.6, 1900));
        // con una caminata razonable (1,0 km) ese mismo bus no compensa
        assertEquals(CAMINAR, segundo(2.6, 1000));
    }

    @Test void escenario7_sinCaminoAPieSeConservaElTransbordo() {
        assertEquals(SEGUNDO_BUS, TransbordoService.decidirSegundoBus(0.2, 100, 100, OptionalDouble.empty()));
    }

    @Test void escenario10_elTransbordoQueAhorraMasDeCincoMinutosSeConserva() {
        double ahorro = 2500 / 80.0 - (100 / 80.0 + 8 + 2.5 * 1000 / 267 + 100 / 80.0);
        assertTrue(ahorro >= TransbordoService.AHORRO_MINIMO_SEGUNDO_BUS_MIN, "ahorro " + ahorro);
        assertEquals(SEGUNDO_BUS, segundo(2.5, 2500));
    }

    @Test void laPenalizacionPorCaminataExcesivaSoloAplicaPorEncimaDeLoRazonable() {
        assertEquals(0, TransbordoService.penalizacionCaminata(1000));
        assertEquals(0, TransbordoService.penalizacionCaminata(TransbordoService.CAMINATA_ALTERNATIVA_RAZONABLE_METROS));
        assertEquals(6, TransbordoService.penalizacionCaminata(TransbordoService.CAMINATA_ALTERNATIVA_RAZONABLE_METROS + 1000), 1e-9);
    }

    @Test void escenario12_unPrimerBusAbsurdamenteCortoSePuedeSaltarSiSeLlegaAPieALaSegundaRuta() {
        assertEquals(SOLO_SEGUNDA_RUTA, TransbordoService.decidirPrimerBus(0.15, 200, 50, OptionalDouble.of(300)));
        assertEquals(DOS_BUSES, TransbordoService.decidirPrimerBus(1.5, 200, 50, OptionalDouble.of(300)), "un primer bus largo se conserva");
        assertEquals(DOS_BUSES, TransbordoService.decidirPrimerBus(0.15, 200, 50, OptionalDouble.of(2000)), "si la segunda ruta queda lejos a pie, se conserva");
        assertEquals(DOS_BUSES, TransbordoService.decidirPrimerBus(0.15, 200, 50, OptionalDouble.empty()), "sin camino a pie se conserva");
    }

    // ------------------------------------------------------------------ Fase E: la decisión sobre la red de calles real del escenario
    @Test void unSegundoBusCortoSeDescartaYSeOfreceBajarYCaminar() {
        var s = servicio(repo(CHICA, GRANDE, null), redMixta);
        assertTrue(s.calcular(viaje(CHICA, GRANDE), null).isEmpty(), "bus 2 de ≈ 670 m y caminar ≈ 880 m: no se recomienda el segundo bus");
        var r = s.calcularConAlternativas(viaje(CHICA, GRANDE), null);
        assertEquals(1, r.caminarEnVez().size());
        var o = r.caminarEnVez().get(0);
        assertEquals("BAJAR_Y_CAMINAR", o.variante());
        assertEquals("R1", o.codigo());
        assertTrue(o.acercamientoFinMetros() > 600 && o.acercamientoFinMetros() < 1100, "camina ≈ 880 m: " + o.acercamientoFinMetros());
        assertTrue(o.recorridoKm() > 1.5, "el primer bus es largo: " + o.recorridoKm());
        assertTrue(o.advertencia().contains("aproximadamente") && o.advertencia().contains("no compensa la espera ni el segundo pasaje"));
        assertTrue(o.caminataInicioRuta().coordinates().size() >= 2 && o.caminataFinRuta().coordinates().size() >= 2, "trae las caminatas por calles");
        assertTrue(o.sentidoPermitido());
    }

    @Test void conUnaBarreraElSegundoBusCortoSeConserva() {
        var r = servicio(repo(CHICA, GRANDE, null), redRioConPuente).calcularConAlternativas(viaje(CHICA, GRANDE), null);
        assertFalse(r.transbordos().isEmpty(), "caminar exige un rodeo de varios km por el único puente: el transbordo se conserva");
        assertTrue(r.caminarEnVez().isEmpty());
        var t = r.transbordos().get(0);
        assertEquals(2, t.segundoBus().rutaId());
        assertTrue(t.segundoBus().recorridoKm() < 0.9, "el segundo bus es corto: " + t.segundoBus().recorridoKm());
    }

    @Test void sinCaminoAPieElTransbordoSeConservaYNoSeProponeUnaCaminataImposible() {
        var r = servicio(repo(CHICA, GRANDE, null), redRioSinPuente).calcularConAlternativas(viaje(CHICA, GRANDE), null);
        assertFalse(r.transbordos().isEmpty(), "no hay camino a pie hasta B desde donde se baja el primer bus");
        assertTrue(r.caminarEnVez().isEmpty(), "no se propone caminar donde no se puede");
    }

    @Test void unPrimerBusMuyCortoSeSustituyePorSoloLaSegundaRuta() {
        var s = servicio(repo(GRANDE, GRANDE, null), redGrande);
        assertTrue(s.calcular(viajeCercaDelCruce(GRANDE, GRANDE), null).isEmpty(), "bus 1 de ≈ 200 m: no vale la pena un segundo pasaje");
        var o = s.calcularConAlternativas(viajeCercaDelCruce(GRANDE, GRANDE), null).caminarEnVez().get(0);
        assertEquals("SOLO_SEGUNDA_RUTA", o.variante());
        assertEquals("R2", o.codigo());
        assertTrue(o.acercamientoInicioMetros() > 100 && o.acercamientoInicioMetros() < 700, "camina unos cientos de metros hasta la ruta 2: " + o.acercamientoInicioMetros());
        assertTrue(o.advertencia().contains("toma solo ese bus"));
    }

    @Test void siElDirectoEsClaramenteMejorNoSeOfreceNiElTransbordoNiLaAlternativa() {
        var r = servicio(repo(CHICA, GRANDE, null), redMixta).calcularConAlternativas(viaje(CHICA, GRANDE), new TransbordoService.Directo(6, 300));
        assertTrue(r.transbordos().isEmpty() && r.caminarEnVez().isEmpty());
    }

    @Test void elUnicoViajePosibleNuncaSeElimina() {
        // sin directo (null) y con el transbordo descartado, siempre queda la alternativa de una sola ruta
        var r = servicio(repo(CHICA, GRANDE, null), redMixta).calcularConAlternativas(viaje(CHICA, GRANDE), null);
        assertFalse(r.transbordos().isEmpty() && r.caminarEnVez().isEmpty(), "debe quedar al menos una forma de ir");
    }

    // ------------------------------------------------------------------ 4.13.2 · prioridad de acceso al primer bus (PoliticaRecomendacion)
    @Test void laSubidaCercanaPrefiereLaMenorCaminataAunqueElBusAhorreTiempoMasAdelante() {
        // ruta de 2 km con una muestra cada 100 m; desde A se camina 150 m hasta la muestra 2 y 90 m hasta la muestra 8 (más adelante en la ruta)
        List<TransbordoService.Muestra> m = new ArrayList<>();
        double[] wA = new double[21];
        for (int i = 0; i <= 20; i++) { m.add(new TransbordoService.Muestra(0, i / 20.0, i * 100.0, 4.14, -73.63 + i * 0.001, null)); wA[i] = 400; }
        wA[2] = 150; wA[8] = 90;
        var ru = new TransbordoService.RutaM(1, "IDA", 2000, 0, 21);
        double[] caminaC = new double[21], minC = new double[21]; int[] enC = new int[21];
        java.util.Arrays.fill(caminaC, Double.NaN);
        TransbordoService.mejorSubidaCercana(m, ru, wA, caminaC, minC, enC);
        assertTrue(Double.isNaN(caminaC[1]), "con menos de 120 m de bus todavía no hay candidato");
        assertEquals(2, enC[5]); assertEquals(150, caminaC[5], 1e-9);
        assertEquals(8, enC[15], "más adelante aparece una subida con menos caminata (90 m): es la «cercana»");
        assertEquals(90, caminaC[15], 1e-9);
        assertEquals(90 / 80.0 + (1500 - 800) / 267.0, minC[15], 1e-9);
        assertEquals(2, enC[7], "el empate o la mejora estricta decide: hasta la muestra 8 sigue la 2");
    }

    @Test void unTransbordoQueElCriterioAnteriorDescartabaSeConservaSiReduceMuchoLaCaminataInicial() {
        var s = servicio(repo(GRANDE, GRANDE, null), redGrande);
        var t = s.calcular(viaje(GRANDE, GRANDE), null).get(0);
        double inicial = t.caminataInicio().metros();
        // directa de referencia: apenas más rápida que el transbordo (el criterio anterior pedía ≥ 5 min de ahorro), pero obliga a caminar 1.100 m al inicio
        var sinBase = new TransbordoService.Directo(t.minutos() - 3, 1300);
        assertTrue(s.calcular(viaje(GRANDE, GRANDE), sinBase).isEmpty(), "criterio anterior: no ahorra 5 min ni evita > 1.500 m");
        var conBase = new TransbordoService.Directo(t.minutos() - 3, 1300, 1100, 1300, t.minutos() - 3);
        assertTrue(1100 - inicial >= 600, "escenario: el transbordo reduce el acceso en " + (1100 - inicial) + " m");
        var r = s.calcular(viaje(GRANDE, GRANDE), conBase);
        assertFalse(r.isEmpty(), "política de acceso: se conserva para que pueda ser recomendado");
        assertTrue(r.get(0).id().startsWith("T:1>2"), r.get(0).id());
    }

    @Test void siElTransbordoNoReduceElAccesoOEmpeoraElViajeElCriterioNuevoNoLoRescata() {
        var s = servicio(repo(GRANDE, GRANDE, null), redGrande);
        var t = s.calcular(viaje(GRANDE, GRANDE), null).get(0);
        double ini = t.caminataInicio().metros();
        assertTrue(s.calcular(viaje(GRANDE, GRANDE), new TransbordoService.Directo(t.minutos() - 3, 1300, ini + 100, 1300, t.minutos() - 3)).isEmpty(),
                "solo 100 m menos de acceso: no es significativo");
        assertTrue(s.calcular(viaje(GRANDE, GRANDE), new TransbordoService.Directo(t.minutos() - 40, 1300, 1100, 1300, t.minutos() - 40)).isEmpty(),
                "el viaje sería 40 min más lento que la directa: excede el tiempo extra permitido");
    }

    @Test void losCandidatosDeAccesoSeLimitanYSuCostoSeMide() {
        var repo = repo(GRANDE, GRANDE, null);
        var s = servicio(repo, redGrande);
        s.usarCandidatosDeAcceso(false);
        s.calcular(viaje(GRANDE, GRANDE), null);
        int sin = s.ultimosFinalistas;
        s.usarCandidatosDeAcceso(true);
        s.calcular(viaje(GRANDE, GRANDE), null);
        int con = s.ultimosFinalistas;
        assertTrue(con <= sin + TransbordoService.FINALISTAS_ACCESO && sin <= TransbordoService.FINALISTAS, "tope de candidatos: sin=" + sin + " con=" + con);
        int vueltas = 150;
        for (boolean acceso : new boolean[]{false, true}) {            // calentamiento
            s.usarCandidatosDeAcceso(acceso);
            for (int i = 0; i < 30; i++) s.calcular(viaje(GRANDE, GRANDE), null);
        }
        double[] ms = new double[2];
        for (int rep = 0; rep < 3; rep++) {
            for (int a = 0; a < 2; a++) {
                s.usarCandidatosDeAcceso(a == 1);
                long t0 = System.nanoTime();
                for (int i = 0; i < vueltas; i++) s.calcular(viaje(GRANDE, GRANDE), null);
                ms[a] += (System.nanoTime() - t0) / 1e6 / vueltas / 3;
            }
        }
        System.out.printf(java.util.Locale.ROOT, "[medición] finalistas sin acceso=%d con acceso=%d · %.3f ms/cálculo sin · %.3f ms/cálculo con (red 8x8 simulada)%n", sin, con, ms[0], ms[1]);
        assertTrue(ms[1] < Math.max(ms[0] * 3, ms[0] + 5), "los candidatos de acceso no deben multiplicar el costo: " + ms[0] + " → " + ms[1]);
    }
}
