package co.edu.usta.spevb.service;

import co.edu.usta.spevb.config.CityArea;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class RedPeatonalTest {
    private static final double LAT0 = 4.14, LNG0 = -73.63, PASO = 0.001;
    private static RedPeatonal red;

    private static long id(int i, int j) { return 1000L * i + j; }

    /** Retícula de calles 8×8 (manzanas de ≈ 110 m) más un islote aislado de 3 nodos. */
    @BeforeAll static void construir() {
        var c = new RedPeatonal.Constructor();
        for (int i = 0; i < 8; i++) {
            long[] ids = new long[8]; double[] la = new double[8], lo = new double[8];
            for (int j = 0; j < 8; j++) { ids[j] = id(i, j); la[j] = LAT0 + i * PASO; lo[j] = LNG0 + j * PASO; }
            c.agregarCamino(ids, la, lo, RedPeatonal.LLANA);
        }
        for (int j = 0; j < 8; j++) {
            long[] ids = new long[8]; double[] la = new double[8], lo = new double[8];
            for (int i = 0; i < 8; i++) { ids[i] = id(i, j); la[i] = LAT0 + i * PASO; lo[i] = LNG0 + j * PASO; }
            c.agregarCamino(ids, la, lo, RedPeatonal.LLANA);
        }
        c.agregarCamino(new long[]{900001, 900002, 900003}, new double[]{4.20, 4.2005, 4.201},
                new double[]{-73.55, -73.55, -73.55}, RedPeatonal.LLANA);
        red = c.construir();
    }

    private static boolean sobreCalle(double[] p) {
        double fi = (p[1] - LAT0) / PASO, fj = (p[0] - LNG0) / PASO;
        return Math.abs(fi - Math.rint(fi)) < 1e-6 || Math.abs(fj - Math.rint(fj)) < 1e-6;
    }

    @Test void elCaminoSoloRecorreCallesYNuncaAtraviesaManzanas() {
        // Dos puntos en el centro de manzanas opuestas: en línea recta cruzarían varias manzanas.
        var a = red.ajustar(LAT0 + 0.0005, LNG0 + 0.0005, 250);
        var b = red.ajustar(LAT0 + 0.0065, LNG0 + 0.0065, 250);
        assertNotNull(a); assertNotNull(b);
        var camino = red.camino(List.of(a), List.of(b), 15_000);
        assertNotNull(camino);
        for (double[] p : camino.coordenadas()) assertTrue(sobreCalle(p), "fuera de calle: " + p[1] + "," + p[0]);
        double recta = RedPeatonal.metros(a.lat(), a.lng(), b.lat(), b.lng());
        assertTrue(camino.metros() >= recta - 1, "el camino no puede ser más corto que la recta");
        assertTrue(camino.metros() <= recta * 1.5, "Manhattan ≈ 1,27 × recta como máximo en retícula");
    }

    @Test void ajustaElPuntoALaCalleMasCercana() {
        var s = red.ajustar(LAT0 + 0.0031, LNG0 + 0.0020 + 0.00002, 250); // a ≈ 2 m de la calle j=2
        assertNotNull(s);
        assertTrue(s.distM() < 3);
        assertTrue(sobreCalle(new double[]{s.lng(), s.lat()}));
        assertNull(red.ajustar(4.30, -73.40, 250), "sin calle cerca no se inventa un camino");
    }

    @Test void unPuntoEnMedioDeManzanaProbaVariasCallesYEligeLaQueMenosRodea() {
        // Centro de una manzana: las 4 calles están a ≈ 55 m. Hacia el norte conviene salir por la calle de arriba.
        double la = LAT0 + 0.0025, lo = LNG0 + 0.0035;
        var varias = red.ajustarVarios(la, lo, 250, 4, 40);
        assertEquals(4, varias.size());
        var destino = red.ajustar(LAT0 + 0.0060, LNG0 + 0.0035, 50);
        var unica = red.camino(List.of(varias.get(0)), List.of(destino), 15_000);
        var todas = red.camino(varias, List.of(destino), 15_000);
        assertTrue(todas.metros() <= unica.metros() + 1e-6);
        assertEquals(440, todas.metros(), 8, "sale por la calle del norte y sube sin rodear: 55 (a la esquina) + 330 + 55");
    }

    @Test void losIslotesAisladosNoSirvenDeDestino() {
        assertNull(red.ajustar(4.2005, -73.55, 100), "el islote de 3 nodos se descarta");
    }

    @Test void mismaAristaYCostosMultiples() {
        var a = red.ajustar(LAT0 + 0.0001, LNG0 + 0.0030, 50);
        var b = red.ajustar(LAT0 + 0.0003, LNG0 + 0.0030, 50);
        var c = red.camino(List.of(a), List.of(b), 1000);
        assertNotNull(c);
        assertEquals(2, c.coordenadas().size());
        assertEquals(22, c.metros(), 3);

        var lejos = red.ajustar(LAT0 + 0.0060, LNG0 + 0.0060, 50);
        double[] costos = red.costosDesde(List.of(a), List.of(b, lejos), 8000);
        assertEquals(22, costos[0], 3);
        assertEquals(red.camino(List.of(a), List.of(lejos), 15000).metros(), costos[1], costos[1] * 0.03); // factores = 1: mismo valor
    }

    @Test void respetaElCostoMaximo() {
        var a = red.ajustar(LAT0, LNG0, 50);
        var b = red.ajustar(LAT0 + 0.007, LNG0 + 0.007, 50);
        assertNull(red.camino(List.of(a), List.of(b), 100));
    }

    @Test void elServicioExponeTramoConCoordenadasYDistancias() {
        var area = new CityArea("Villavicencio", "Meta", "Colombia", 3.95, 4.35, -73.85, -73.45);
        var servicio = new RedPeatonalService(red, area);
        Optional<CaminataService.Tramo> t = servicio.ruta(LAT0 + 0.0005, LNG0 + 0.0005, LAT0 + 0.0065, LNG0 + 0.0065);
        assertTrue(t.isPresent());
        assertTrue(t.get().coordenadas().stream().allMatch(RedPeatonalTest::sobreCalle));
        var m = servicio.distancias(List.of(new double[]{LNG0 + 0.0005, LAT0 + 0.0005}),
                List.of(new double[]{LNG0 + 0.0065, LAT0 + 0.0065}, new double[]{-73.40, 4.30}));
        assertTrue(m.isPresent());
        assertTrue(m.get()[0][0] > 700 && m.get()[0][0] < 1500);
        assertTrue(Double.isNaN(m.get()[0][1]));
    }

    @Test void leeLaRespuestaDeOverpassPorElementosYDescartaLoQueNoSirve() {
        String json = "{\"version\":0.6,\"elements\":["
                + "{\"type\":\"way\",\"id\":1,\"nodes\":[10,11,12],\"geometry\":[{\"lat\":4.14,\"lon\":-73.63},{\"lat\":4.141,\"lon\":-73.63},{\"lat\":4.142,\"lon\":-73.63}],\"tags\":{\"highway\":\"residential\"}},"
                + "{\"type\":\"way\",\"id\":2,\"nodes\":[20,21],\"geometry\":[{\"lat\":4.14,\"lon\":-73.63},{\"lat\":4.14,\"lon\":-73.629}],\"tags\":{\"highway\":\"motorway\"}},"
                + "{\"type\":\"way\",\"id\":3,\"nodes\":[10,30],\"geometry\":[{\"lat\":4.14,\"lon\":-73.63},{\"lat\":4.14,\"lon\":-73.629}],\"tags\":{\"highway\":\"steps\"}}]}";
        var area = new CityArea("Villavicencio", "Meta", "Colombia", 3.95, 4.35, -73.85, -73.45);
        var servicio = new RedPeatonalService(false, "data/x", 60, "", area, JsonMapper.builder().build());
        List<RedPeatonal.CaminoGuardado> out = new ArrayList<>();
        servicio.leerCaminos(new ByteArrayInputStream(json.getBytes()), out, new HashSet<>());
        assertEquals(2, out.size(), "la autopista no es transitable a pie");
        assertEquals(RedPeatonal.ESCALERA, out.get(1).clase());
    }

    @Test void springPuedeCrearElServicioYLoInyectaEnCaminata() {
        try (var ctx = new org.springframework.context.annotation.AnnotationConfigApplicationContext()) {
            ctx.registerBean(CityArea.class, () -> new CityArea("Villavicencio", "Meta", "Colombia", 3.95, 4.35, -73.85, -73.45));
            ctx.registerBean(tools.jackson.databind.ObjectMapper.class, () -> JsonMapper.builder().build());
            ctx.registerBean(org.springframework.context.support.PropertySourcesPlaceholderConfigurer.class);
            ctx.getEnvironment().getPropertySources().addFirst(new org.springframework.core.env.MapPropertySource("t",
                    java.util.Map.of("spevb.red.enabled", "false", "spevb.walking.url", "")));
            ctx.register(RedPeatonalService.class, CaminataService.class);
            ctx.refresh();
            assertNotNull(ctx.getBean(RedPeatonalService.class));
            assertNotNull(ctx.getBean(CaminataService.class));
        }
    }
}
