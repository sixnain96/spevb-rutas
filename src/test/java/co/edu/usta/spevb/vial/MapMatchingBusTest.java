package co.edu.usta.spevb.vial;

import co.edu.usta.spevb.vial.MapMatchingBus.Estado;
import co.edu.usta.spevb.vial.MapMatchingBus.Opciones;
import co.edu.usta.spevb.vial.MapMatchingBus.Resultado;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.Set;

import static co.edu.usta.spevb.vial.Sinteticas.*;
import static org.junit.jupiter.api.Assertions.*;

class MapMatchingBusTest {
    private static final double[] RUIDO_FUERTE = {9, -8, 5, 9, -9, 0, 8.5, -6, 9, 2, -9, 7, -4, 9};

    private static Opciones op(boolean extremos) { return new Opciones(30, 500, 10, extremos); }

    private static Resultado ajustar(List<ViaOsm> vias, List<double[]> pts, Set<Integer> anclas, boolean extremos) {
        return new MapMatchingBus(new RedVialBus(vias)).ajustar(pts, anclas, op(extremos));
    }

    private static double maxAbsY(List<double[]> l, double yVia) {
        double m = 0;
        for (double[] p : l) m = Math.max(m, Math.abs(yDe(p) - yVia));
        return m;
    }

    private static double longitud(List<double[]> l) {
        double s = 0;
        for (int i = 1; i < l.size(); i++) s += RedVialBus.distanciaM(l.get(i - 1)[0], l.get(i - 1)[1], l.get(i)[0], l.get(i)[1]);
        return s;
    }

    // ------------------------------------------------------------------ ajuste básico
    @Test void ajustaUnPuntoDesplazado10MetrosSobreLaVia() {
        var via = recta(1, 0, 0, 600, 0, 100, "highway", "residential");
        var pts = recorrer(20, new double[]{20, 0}, new double[]{200, 0});
        pts.set(4, p(xDe(pts.get(4)), 10));
        var r = ajustar(List.of(via), pts, Set.of(), false);
        assertTrue(maxAbsY(r.propuesta(), 0) < 0.1, "toda la propuesta queda sobre la vía");
        assertEquals(10, r.metricas().desviacionMaxM(), 0.2, "el punto desplazado se movió 10 m");
        assertTrue(r.tramos().stream().allMatch(t -> t.estado() == Estado.OK));
        assertEquals(0, r.metricas().puntosSinCoincidencia());
        assertEquals(100, r.metricas().pctAjustado(), 1e-9);
        assertFalse(r.requiereRevisionManual());
        assertEquals(pts.size(), r.metricas().puntosOriginales());
        assertEquals(r.propuesta().size(), r.metricas().puntosPropuestos());
    }

    @Test void puntoAMasDe30MetrosSeConservaConAdvertencia() {
        var via = recta(1, 0, 0, 600, 0, 100, "highway", "residential");
        var pts = recorrer(20, new double[]{20, 0}, new double[]{300, 0});
        double[] lejano = p(xDe(pts.get(7)), 40);
        pts.set(7, lejano);
        var r = ajustar(List.of(via), pts, Set.of(), false);
        assertTrue(contiene(r.propuesta(), lejano), "el punto original se conserva sin mover");
        assertEquals(1, r.metricas().puntosSinCoincidencia());
        var t = r.tramos().stream().filter(x -> x.estado() == Estado.SIN_COINCIDENCIA).toList();
        assertEquals(1, t.size());
        assertEquals(7, t.get(0).desde());
        assertEquals(7, t.get(0).hasta());
        assertTrue(t.get(0).motivo().contains("30"), t.get(0).motivo());
        assertFalse(r.advertencias().isEmpty());
        assertTrue(r.requiereRevisionManual());
    }

    @Test void noSePegaAFootwayStepsPathNiPuentePeatonalAunqueSeanLoMasCercano() {
        var calle = recta(1, 0, 12, 600, 12, 50, "highway", "residential");
        var footway = recta(2, 0, 2, 600, 2, 50, "highway", "footway");
        var steps = recta(3, 0, 4, 600, 4, 50, "highway", "steps");
        var path = recta(4, 0, 6, 600, 6, 50, "highway", "path");
        var puentePeatonal = via(5, tags("highway", "footway", "bridge", "yes", "layer", "1"), new double[]{60, -100}, new double[]{60, 100});
        var pts = recorrer(20, new double[]{20, 0}, new double[]{200, 0});
        var r = ajustar(List.of(calle, footway, steps, path, puentePeatonal), pts, Set.of(), false);
        assertTrue(maxAbsY(r.propuesta(), 12) < 0.1, "todo se ajusta a la calle y nunca a lo peatonal");
        assertEquals(12, r.metricas().desviacionMediaM(), 0.5);
        assertEquals(0, r.metricas().puntosSinCoincidencia());
        var red = new RedVialBus(List.of(calle, footway, steps, path, puentePeatonal));
        assertEquals(1, red.viasAceptadas());
        var cand = red.candidatos(p(60, 0)[0], p(60, 0)[1], 30, 10);
        assertEquals(List.of(1L), cand.stream().map(RedVialBus.Candidato::viaId).toList());
    }

    @Test void conSoloViasPeatonalesNadaSeAjustaYSeConservaElRecorrido() {
        var footway = recta(2, 0, 0, 600, 0, 50, "highway", "footway");
        var pts = recorrer(20, new double[]{20, 3}, new double[]{200, 3});
        var r = ajustar(List.of(footway), pts, Set.of(), false);
        assertEquals(pts.size(), r.metricas().puntosSinCoincidencia());
        assertEquals(pts.size(), r.propuesta().size());
        for (int i = 0; i < pts.size(); i++) assertArrayEquals(pts.get(i), r.propuesta().get(i), 0);
        assertTrue(r.requiereRevisionManual());
        assertEquals(0, r.metricas().pctAjustado(), 1e-9);
    }

    @Test void permiteAccessNoConBusDesignated() {
        var carril = recta(1, 0, 0, 600, 0, 100, "highway", "service", "access", "no", "bus", "designated");
        var r = ajustar(List.of(carril), recorrer(20, new double[]{20, 6}, new double[]{200, 6}), Set.of(), false);
        assertEquals(0, r.metricas().puntosSinCoincidencia());
        assertTrue(maxAbsY(r.propuesta(), 0) < 0.1);
    }

    @Test void motorVehicleNoRechazaSalvoQueBusSeaYes() {
        var pts = recorrer(20, new double[]{20, 5}, new double[]{200, 5});
        var cerrada = ajustar(List.of(recta(1, 0, 0, 600, 0, 100, "highway", "residential", "motor_vehicle", "no")), pts, Set.of(), false);
        assertEquals(pts.size(), cerrada.metricas().puntosSinCoincidencia());
        var abierta = ajustar(List.of(recta(1, 0, 0, 600, 0, 100, "highway", "residential", "motor_vehicle", "no", "bus", "yes")), pts, Set.of(), false);
        assertEquals(0, abierta.metricas().puntosSinCoincidencia());
    }

    // ------------------------------------------------------------------ sentidos
    private static final double[] ORIENTE = {0, 0}, FIN = {1000, 0};

    @Test void respetaOnewayYAvanceEnSentidoPermitido() {
        var via = recta(1, 0, 0, 1000, 0, 100, "highway", "residential", "oneway", "yes");
        var aFavor = recorrer(20, new double[]{100, 3}, new double[]{800, 3});
        var r = ajustar(List.of(via), aFavor, Set.of(), false);
        assertEquals(0, r.metricas().puntosSinCoincidencia());
        assertEquals(0, r.metricas().giroIlegales());
        assertFalse(r.requiereRevisionManual());
    }

    @Test void nuncaRecorreUnSentidoUnicoAlReves() {
        var via = recta(1, 0, 0, 1000, 0, 100, "highway", "residential", "oneway", "yes");
        var enContra = recorrer(20, new double[]{800, 3}, new double[]{100, 3});
        var r = ajustar(List.of(via), enContra, Set.of(), false);
        assertTrue(r.metricas().puntosSinCoincidencia() >= enContra.size() - 3, "salvo los 2 primeros (que pueden quedarse en el mismo sitio) nada se ajusta");
        assertTrue(r.metricas().giroIlegales() >= 1, "se informa el avance contra el sentido");
        assertTrue(r.requiereRevisionManual());
        // los puntos no ajustados se conservan tal cual: no se dibuja nada inventado sobre el contrasentido
        assertTrue(contiene(r.propuesta(), enContra.get(5)));
    }

    @Test void onewayBusNoPermiteElContraflujoDeLosBuses() {
        var via = recta(1, 0, 0, 1000, 0, 100, "highway", "residential", "oneway", "yes", "oneway:bus", "no");
        var enContra = recorrer(20, new double[]{800, 3}, new double[]{100, 3});
        var r = ajustar(List.of(via), enContra, Set.of(), false);
        assertEquals(0, r.metricas().puntosSinCoincidencia());
        assertEquals(0, r.metricas().giroIlegales());
        assertTrue(maxAbsY(r.propuesta(), 0) < 0.1);
    }

    @Test void onewayMenosUnoSoloSePuedeRecorrerContraElOrdenDeLosVertices() {
        var via = recta(1, 0, 0, 1000, 0, 100, "highway", "residential", "oneway", "-1");
        var haciaOeste = recorrer(20, new double[]{800, 3}, new double[]{100, 3});
        var ok = ajustar(List.of(via), haciaOeste, Set.of(), false);
        assertEquals(0, ok.metricas().puntosSinCoincidencia());
        var haciaEste = recorrer(20, new double[]{100, 3}, new double[]{800, 3});
        var mal = ajustar(List.of(via), haciaEste, Set.of(), false);
        assertTrue(mal.metricas().puntosSinCoincidencia() >= haciaEste.size() - 1);
        assertTrue(mal.requiereRevisionManual());
    }

    // ------------------------------------------------------------------ glorieta
    private static List<ViaOsm> glorieta() {
        List<double[]> anillo = new ArrayList<>();
        for (int k = 0; k <= 16; k++) {
            double a = Math.toRadians(-90 + 22.5 * k);
            anillo.add(new double[]{30 * Math.cos(a), 30 * Math.sin(a)});
        }
        return List.of(
                via(100, tags("highway", "primary", "junction", "roundabout"), anillo.toArray(new double[0][])),
                via(101, tags("highway", "residential"), new double[]{0, -130}, new double[]{0, -80}, new double[]{0, -30}),
                via(102, tags("highway", "residential"), new double[]{0, 30}, new double[]{0, 80}, new double[]{0, 130}));
    }

    private static List<double[]> pasoPorGlorieta(boolean antihorario) {
        List<double[]> pts = recorrer(10, new double[]{0, -130}, new double[]{0, -30});
        for (int k = 1; k < 16; k++) {
            double a = Math.toRadians(antihorario ? -90 + 11.25 * k : -90 - 11.25 * k);
            pts.add(p(30 * Math.cos(a), 30 * Math.sin(a)));
        }
        pts.addAll(recorrer(10, new double[]{0, 30}, new double[]{0, 130}));
        return pts;
    }

    @Test void glorietaSeRecorreEnElSentidoDeLaCirculacion() {
        var r = ajustar(glorieta(), pasoPorGlorieta(true), Set.of(), false);
        assertEquals(0, r.metricas().puntosSinCoincidencia());
        assertEquals(0, r.metricas().giroIlegales());
        double minX = 1e9, maxX = -1e9;
        for (double[] q : r.propuesta()) { minX = Math.min(minX, xDe(q)); maxX = Math.max(maxX, xDe(q)); }
        assertTrue(maxX > 25, "pasa por el lado este de la glorieta");
        assertTrue(minX > -1, "nunca por el lado oeste (iría contra el sentido)");
        assertFalse(r.requiereRevisionManual());
    }

    @Test void glorietaRecorridaAlReves() {
        var r = ajustar(glorieta(), pasoPorGlorieta(false), Set.of(), false);
        assertTrue(r.requiereRevisionManual());
        assertTrue(r.metricas().puntosSinCoincidencia() + r.metricas().tramosDudosos() > 0, "avanzar contra la glorieta queda marcado");
    }

    // ------------------------------------------------------------------ calzadas paralelas
    @Test void calzadasParalelasConSentidoUnicoSeQuedaEnLaMismaCalzadaConRuidoFuerte() {
        var a = via(1, tags("highway", "primary", "oneway", "yes"), pasos(0, 0, 2000, 0));
        var b = via(2, tags("highway", "primary", "oneway", "yes"), pasos(2000, 15, 0, 15)); // sentido contrario
        var enlace1 = via(3, tags("highway", "residential"), new double[]{2000, 0}, new double[]{2000, 15});
        var enlace2 = via(4, tags("highway", "residential"), new double[]{0, 15}, new double[]{0, 0});
        var pts = conRuido(recorrer(20, new double[]{40, 0}, new double[]{1960, 0}), RUIDO_FUERTE);
        long masCercaDeB = pts.stream().filter(q -> Math.abs(yDe(q) - 15) < Math.abs(yDe(q))).count();
        assertTrue(masCercaDeB >= 8, "el ruido acerca varios puntos a la otra calzada (" + masCercaDeB + ")");
        var r = ajustar(List.of(a, b, enlace1, enlace2), pts, Set.of(), false);
        assertTrue(maxAbsY(r.propuesta(), 0) < 0.1, "toda la propuesta sigue la calzada A (sentido único este)");
        assertEquals(0, r.metricas().puntosSinCoincidencia());
        assertEquals(0, r.metricas().giroIlegales());
    }

    @Test void calzadasParalelasDobleSentidoConectadasSeMantieneLaMismaCalzada() {
        List<ViaOsm> vias = new ArrayList<>(List.of(recta(1, 0, 0, 2000, 0, 100, "highway", "secondary"),
                recta(2, 0, 15, 2000, 15, 100, "highway", "secondary")));
        for (int x = 0; x <= 2000; x += 500) vias.add(recta(10 + x, x, 0, x, 15, 15, "highway", "residential"));
        var pts = conRuido(recorrer(20, new double[]{40, 0}, new double[]{1960, 0}), RUIDO_FUERTE);
        var r = ajustar(vias, pts, Set.of(), false);
        assertTrue(maxAbsY(r.propuesta(), 0) < 0.1, "no cambia de calzada aunque el ruido la acerque a la otra");
    }

    @Test void noSaltaSobreSeparadorEntreViasNoConectadas() {
        var a = recta(1, 0, 0, 2000, 0, 100, "highway", "secondary");
        var b = recta(2, 0, 15, 2000, 15, 100, "highway", "secondary");
        var pts = conRuido(recorrer(20, new double[]{40, 0}, new double[]{1960, 0}), RUIDO_FUERTE);
        var r = ajustar(List.of(a, b), pts, Set.of(), false);
        double enA = maxAbsY(r.propuesta(), 0), enB = maxAbsY(r.propuesta(), 15);
        assertTrue(enA < 0.1 || enB < 0.1, "la propuesta completa está en UNA sola vía (desvío a A: " + enA + ", a B: " + enB + ")");
        assertEquals(0, r.metricas().puntosSinCoincidencia());
        assertEquals(0, r.metricas().tramosDudosos(), "sin saltos entre vías no conectadas");
    }

    private static double[][] pasos(double x0, double y0, double x1, double y1) {
        int n = (int) Math.round(Math.hypot(x1 - x0, y1 - y0) / 100);
        double[][] r = new double[n + 1][];
        for (int i = 0; i <= n; i++) r[i] = new double[]{x0 + (x1 - x0) * i / n, y0 + (y1 - y0) * i / n};
        return r;
    }

    // ------------------------------------------------------------------ atípicos, anclas, entrada
    @Test void detectaUnPuntoAtipicoQueSoloTieneUnaViaNoConectadaCerca() {
        var a = recta(1, 0, 0, 1000, 0, 100, "highway", "residential");
        var aislada = recta(2, 0, 45, 1000, 45, 100, "highway", "residential");
        var pts = recorrer(20, new double[]{20, 0}, new double[]{980, 0});
        double[] atipico = p(xDe(pts.get(25)), 40); // a 40 m de A (fuera del radio) y a 5 m de la vía aislada
        pts.set(25, atipico);
        var r = ajustar(List.of(a, aislada), pts, Set.of(), false);
        var sin = r.tramos().stream().filter(t -> t.estado() == Estado.SIN_COINCIDENCIA).toList();
        assertEquals(1, sin.size());
        assertEquals(25, sin.get(0).desde());
        assertEquals(25, sin.get(0).hasta());
        assertTrue(contiene(r.propuesta(), atipico), "el punto atípico se conserva");
        assertEquals(1, r.metricas().puntosSinCoincidencia());
        assertTrue(r.requiereRevisionManual());
        // el resto sigue en A: el atípico no arrastró la ruta hacia la vía aislada
        long enAislada = r.propuesta().stream().filter(q -> Math.abs(yDe(q) - 45) < 1).count();
        assertEquals(0, enAislada);
    }

    @Test void anclaYExtremosNoSeMueven() {
        var via = recta(1, 0, 0, 600, 0, 100, "highway", "residential");
        var pts = recorrer(20, new double[]{20, 7}, new double[]{300, 7});
        pts.set(5, p(xDe(pts.get(5)), 8)); // ancla desplazada 8 m
        var ancla = pts.get(5).clone();
        var primero = pts.get(0).clone();
        var ultimo = pts.get(pts.size() - 1).clone();
        var r = ajustar(List.of(via), pts, Set.of(5), true);
        assertTrue(contiene(r.propuesta(), ancla), "el ancla queda con su coordenada original");
        assertArrayEquals(primero, r.propuesta().get(0), 0, "primer punto preservado");
        assertArrayEquals(ultimo, r.propuesta().get(r.propuesta().size() - 1), 0, "último punto preservado");
        // los demás puntos intermedios sí se ajustaron a la vía
        int sobreLaVia = 0;
        for (double[] q : r.propuesta()) if (Math.abs(yDe(q)) < 0.1) sobreLaVia++;
        assertTrue(sobreLaVia >= pts.size() - 4, "el resto de la propuesta está sobre la vía (" + sobreLaVia + ")");
        // sin preservar extremos, el primero sí se ajusta
        var libre = ajustar(List.of(via), pts, Set.of(), false);
        assertEquals(0, yDe(libre.propuesta().get(0)), 0.1);
    }

    @Test void noModificaLaEntrada() {
        var via = recta(1, 0, 0, 600, 0, 100, "highway", "residential");
        var pts = conRuido(recorrer(20, new double[]{20, 0}, new double[]{400, 0}), RUIDO_FUERTE);
        var copia = copiaProfunda(pts);
        var anclas = Set.of(3);
        ajustar(List.of(via), pts, anclas, true);
        assertEquals(copia.size(), pts.size());
        for (int i = 0; i < pts.size(); i++) assertArrayEquals(copia.get(i), pts.get(i), 0);
        assertEquals(Set.of(3), anclas);
    }

    @Test void casosLimiteVacioUnPuntoYPuntosInvalidos() {
        var mm = new MapMatchingBus(new RedVialBus(List.of(recta(1, 0, 0, 600, 0, 100, "highway", "residential"))));
        var vacio = mm.ajustar(List.of(), Set.of(), Opciones.porDefecto());
        assertTrue(vacio.propuesta().isEmpty());
        assertFalse(vacio.requiereRevisionManual());
        var uno = mm.ajustar(List.of(p(100, 5)), Set.of(), new Opciones(30, 500, 10, false));
        assertEquals(1, uno.propuesta().size());
        assertEquals(0, yDe(uno.propuesta().get(0)), 0.1);
        assertThrows(IllegalArgumentException.class, () -> mm.ajustar(List.of(new double[]{Double.NaN, 1}), Set.of(), null));
        var redVacia = new MapMatchingBus(new RedVialBus(List.of())).ajustar(List.of(p(0, 0), p(10, 0)), null, null);
        assertEquals(2, redVacia.metricas().puntosSinCoincidencia());
        assertTrue(redVacia.requiereRevisionManual());
    }

    // ------------------------------------------------------------------ contrato de tramos para la interfaz
    @Test void reemplazarTramoATramoReconstruyeLaPropuestaContinua() {
        var a = recta(1, 0, 0, 1000, 0, 100, "highway", "residential");
        var aislada = recta(2, 0, 45, 1000, 45, 100, "highway", "residential");
        var pts = recorrer(20, new double[]{20, 3}, new double[]{980, 3});
        pts.set(10, p(xDe(pts.get(10)), 40)); // atípico
        pts.set(30, p(xDe(pts.get(30)), 41)); // otro atípico
        var r = ajustar(List.of(a, aislada), pts, Set.of(), false);
        assertTrue(r.tramos().size() >= 5);
        List<double[]> rec = new ArrayList<>();
        int sigOriginal = 0, previoHasta = 0;
        for (int k = 0; k < r.tramos().size(); k++) {
            var t = r.tramos().get(k);
            assertEquals(sigOriginal, t.desde(), "los tramos cubren los índices originales sin huecos");
            sigOriginal = t.hasta() + 1;
            assertTrue(t.propDesde() <= t.propHasta());
            if (k == 0) assertEquals(0, t.propDesde());
            else assertEquals(previoHasta, t.propDesde(), "tramos consecutivos comparten el vértice de unión");
            previoHasta = t.propHasta();
            for (int i = t.propDesde() + (k == 0 ? 0 : 1); i <= t.propHasta(); i++) rec.add(r.propuesta().get(i));
            if (t.estado() == Estado.SIN_COINCIDENCIA)
                for (int o = t.desde(); o <= t.hasta(); o++)
                    assertTrue(contiene(r.propuesta().subList(t.propDesde(), t.propHasta() + 1), pts.get(o)), "SIN_COINCIDENCIA conserva el original " + o);
        }
        assertEquals(pts.size(), sigOriginal);
        assertEquals(r.propuesta().size() - 1, previoHasta, "el último tramo termina en el último vértice");
        assertEquals(r.propuesta().size(), rec.size());
        for (int i = 0; i < rec.size(); i++) assertArrayEquals(r.propuesta().get(i), rec.get(i), 0);
        // continuidad: ningún salto mayor que el de la propia línea original (nada inventado)
        for (int i = 1; i < rec.size(); i++)
            assertTrue(RedVialBus.distanciaM(rec.get(i - 1)[0], rec.get(i - 1)[1], rec.get(i)[0], rec.get(i)[1]) < 100);
        // lista de problemas = índices de tramos no OK, en orden
        List<Integer> esperados = new ArrayList<>();
        for (int k = 0; k < r.tramos().size(); k++) if (r.tramos().get(k).estado() != Estado.OK) esperados.add(k);
        assertEquals(esperados, r.problemas());
        assertEquals(2, r.problemas().size());
    }

    @Test void sinProblemasLaListaVieneVacia() {
        var r = ajustar(List.of(recta(1, 0, 0, 600, 0, 100, "highway", "residential")),
                recorrer(20, new double[]{20, 0}, new double[]{200, 0}), Set.of(), false);
        assertTrue(r.problemas().isEmpty());
        assertEquals(1, r.tramos().size());
        assertEquals(0, r.tramos().get(0).propDesde());
        assertEquals(r.propuesta().size() - 1, r.tramos().get(0).propHasta());
    }

    // ------------------------------------------------------------------ rendimiento
    private static RedVialBus cuadricula() {
        List<ViaOsm> vias = new ArrayList<>();
        long id = 1;
        for (int k = 0; k <= 30; k++) {
            vias.add(recta(id++, 0, k * 100, 3000, k * 100, 25, "highway", "residential"));
            vias.add(recta(id++, k * 100, 0, k * 100, 3000, 25, "highway", "residential"));
        }
        return new RedVialBus(vias);
    }

    /** Recorrido en serpentina por la cuadrícula con un punto cada 8 m y ruido gaussiano de ~5 m. Devuelve {puntos, longitud ideal}. */
    private static List<double[]> serpentina(int n, long semilla) {
        List<double[]> ruta = new ArrayList<>();
        ruta.add(new double[]{0, 0});
        for (int k = 0; k < 30; k++) {
            boolean este = k % 2 == 0;
            ruta.add(new double[]{este ? 3000 : 0, k * 100});
            ruta.add(new double[]{este ? 3000 : 0, (k + 1) * 100});
        }
        ruta.add(new double[]{30 % 2 == 0 ? 3000 : 0, 3000});
        List<double[]> ideales = recorrer(8, ruta.toArray(new double[0][]));
        Random r = new Random(semilla);
        List<double[]> pts = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            double[] q = ideales.get(i);
            double dx = Math.max(-12, Math.min(12, r.nextGaussian() * 5)), dy = Math.max(-12, Math.min(12, r.nextGaussian() * 5));
            pts.add(p(xDe(q) + dx, yDe(q) + dy));
        }
        return pts;
    }

    private void medir(int n) {
        var red = cuadricula();
        var pts = serpentina(n, 99);
        var mm = new MapMatchingBus(red);
        long t0 = System.nanoTime();
        var r = mm.ajustar(pts, Set.of(), op(false));
        long ms = (System.nanoTime() - t0) / 1_000_000;
        double idealLen = 8.0 * (n - 1);
        double maxSalto = 0;
        for (int i = 1; i < r.propuesta().size(); i++)
            maxSalto = Math.max(maxSalto, RedVialBus.distanciaM(r.propuesta().get(i - 1)[0], r.propuesta().get(i - 1)[1], r.propuesta().get(i)[0], r.propuesta().get(i)[1]));
        System.out.printf("[RENDIMIENTO] ajuste de %d puntos sobre %d nodos / %d aristas: %d ms (propuesta %d puntos, long. %.0f m vs ideal %.0f m, "
                        + "desviación media %.2f m, máx %.2f m, %.2f%% ajustado, dudosos %d, sin coincidencia %d, mayor salto %.1f m)%n",
                n, red.nodos(), red.aristas(), ms, r.propuesta().size(), r.metricas().longitudPropuestaM(), idealLen,
                r.metricas().desviacionMediaM(), r.metricas().desviacionMaxM(), r.metricas().pctAjustado(),
                r.metricas().tramosDudosos(), r.metricas().puntosSinCoincidencia(), maxSalto);
        assertTrue(ms < 15_000, n + " puntos tardaron " + ms + " ms");
        assertTrue(r.metricas().pctAjustado() >= 99, "pctAjustado=" + r.metricas().pctAjustado());
        assertEquals(idealLen, r.metricas().longitudPropuestaM(), idealLen * 0.05, "la propuesta mide lo que el recorrido real");
        assertTrue(maxSalto <= 30.5, "sin atajos: el mayor salto entre vértices consecutivos es de " + maxSalto + " m");
        assertEquals(0, r.metricas().giroIlegales());
    }

    @Test void rutaDe1500PuntosTerminaRapido() { medir(1500); }

    @Test void rutaDe10000PuntosTerminaEnTiempoRazonable() { medir(10_000); }
}
