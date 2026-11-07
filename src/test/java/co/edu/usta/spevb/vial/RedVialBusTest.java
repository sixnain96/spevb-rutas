package co.edu.usta.spevb.vial;

import co.edu.usta.spevb.vial.ReglasBus.Sentido;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static co.edu.usta.spevb.vial.Sinteticas.*;
import static org.junit.jupiter.api.Assertions.*;

class RedVialBusTest {

    @Test void cuentaNodosYAristasSegunElSentido() {
        var dobleSentido = via(1, tags("highway", "residential"), new double[]{0, 0}, new double[]{100, 0}, new double[]{200, 0});
        var unico = via(2, tags("highway", "residential", "oneway", "yes"), new double[]{0, 50}, new double[]{100, 50});
        var red = new RedVialBus(List.of(dobleSentido, unico));
        assertEquals(5, red.nodos());
        assertEquals(4 + 1, red.aristas(), "2 tramos de doble sentido = 4 aristas, 1 tramo único = 1");
    }

    @Test void viasQueComparteVerticeSeConectanYLasQueSoloSeCruzanNo() {
        var a = via(1, tags("highway", "residential"), new double[]{0, 0}, new double[]{100, 0});
        var b = via(2, tags("highway", "residential"), new double[]{100, 0}, new double[]{100, 100});
        assertEquals(3, new RedVialBus(List.of(a, b)).nodos(), "comparten el vértice (100,0)");
        var puente = via(3, tags("highway", "primary", "bridge", "yes"), new double[]{50, -50}, new double[]{50, 50});
        assertEquals(4, new RedVialBus(List.of(a, puente)).nodos(), "se cruzan sin vértice común: no hay nodo en el cruce");
    }

    @Test void soloEntranLasViasQueAceptaReglasBus() {
        var red = new RedVialBus(List.of(
                via(1, tags("highway", "residential"), new double[]{0, 0}, new double[]{100, 0}),
                via(2, tags("highway", "footway"), new double[]{0, 5}, new double[]{100, 5}),
                via(3, tags("highway", "steps"), new double[]{0, 6}, new double[]{100, 6}),
                via(4, tags("highway", "residential", "access", "private"), new double[]{0, 7}, new double[]{100, 7})));
        assertEquals(1, red.viasAceptadas());
        assertEquals(3, red.viasDescartadas());
        assertEquals(2, red.nodos());
    }

    @Test void candidatosOrdenadosPorDistanciaSinRepetirLaMismaVia() {
        var a = recta(10, 0, 0, 400, 0, 100, "highway", "residential", "name", "Calle A");
        var b = via(20, tags("highway", "secondary", "oneway", "yes", "name", "Calle B"), new double[]{0, 12}, new double[]{400, 12});
        var lejos = recta(30, 0, 100, 400, 100, 100, "highway", "residential");
        var peatonal = recta(40, 0, 1, 400, 1, 100, "highway", "footway");
        var red = new RedVialBus(List.of(a, b, lejos, peatonal));
        var q = p(150, 5);
        var c = red.candidatos(q[0], q[1], 30, 10);
        assertEquals(2, c.size(), "A y B (la vía lejana y la peatonal no cuentan), una vez cada vía");
        assertEquals(10, c.get(0).viaId());
        assertEquals(20, c.get(1).viaId());
        assertEquals(5, c.get(0).distanciaM(), 0.05);
        assertEquals(7, c.get(1).distanciaM(), 0.05);
        assertEquals("Calle A", c.get(0).nombre());
        assertEquals("residential", c.get(0).highway());
        assertEquals(1, c.get(0).tramo(), "x=150 cae en el segundo tramo (100-200)");
        assertEquals(Sentido.SOLO_ADELANTE, c.get(1).sentido());
        assertEquals(1, red.candidatos(q[0], q[1], 30, 1).size());
        var lejano = p(150, 50);
        assertTrue(red.candidatos(lejano[0], lejano[1], 30, 5).stream().noneMatch(x -> x.viaId() == 10),
                "a 50 m de A no debe ser candidata con radio 30");
    }

    @Test void distanciaMasCercanaYRedVacia() {
        var red = new RedVialBus(List.of(recta(1, 0, 0, 1000, 0, 100, "highway", "residential")));
        assertEquals(40, red.distanciaMasCercana(p(500, 40)[0], p(500, 40)[1]).orElseThrow(), 0.1);
        assertEquals(3000, red.distanciaMasCercana(p(500, 3000)[0], p(500, 3000)[1]).orElseThrow(), 1);
        assertTrue(new RedVialBus(List.of()).distanciaMasCercana(4.15, -73.63).isEmpty());
        assertTrue(new RedVialBus(List.of()).candidatos(4.15, -73.63, 30, 5).isEmpty());
    }

    @Test void diezMilConsultasSonRapidas() {
        List<ViaOsm> vias = new ArrayList<>();
        long id = 1;
        for (int k = 0; k <= 40; k++) {
            vias.add(recta(id++, 0, k * 100, 4000, k * 100, 25, "highway", "residential"));
            vias.add(recta(id++, k * 100, 0, k * 100, 4000, 25, "highway", "residential"));
        }
        var red = new RedVialBus(vias);
        Random r = new Random(7);
        long t0 = System.nanoTime();
        int encontrados = 0;
        for (int i = 0; i < 10_000; i++) {
            double[] q = p(r.nextDouble() * 4000, r.nextDouble() * 4000);
            encontrados += red.candidatos(q[0], q[1], 30, 5).size();
        }
        long ms = (System.nanoTime() - t0) / 1_000_000;
        System.out.println("[RENDIMIENTO] 10.000 consultas de candidatos sobre " + red.nodos() + " nodos / " + red.aristas() + " aristas: " + ms + " ms");
        assertTrue(encontrados > 0);
        assertTrue(ms < 3000, "10.000 consultas tardaron " + ms + " ms");
    }
}
