package co.edu.usta.spevb.service;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Optimización de {@code RedPeatonal.ajustarVarios}: la raíz cuadrada directa y la comparación al cuadrado deben dar EXACTAMENTE la misma calle y la misma
 * distancia que la fuerza bruta con {@code Math.hypot} (la versión original), en una red irregular con calles en diagonal.
 */
class RedPeatonalEquivalenciaTest {
    private static final double LAT0 = 4.10, LNG0 = -73.66;
    private static RedPeatonal red;
    private static List<double[]> segmentos;

    /** Retícula conectada de 30×30 nodos con posiciones perturbadas (calles irregulares) y varias diagonales. */
    @BeforeAll static void construir() {
        var azar = new Random(42);
        int n = 30;
        double[][] la = new double[n][n], lo = new double[n][n];
        for (int i = 0; i < n; i++) for (int j = 0; j < n; j++) {
            la[i][j] = LAT0 + i * 0.0012 + (azar.nextDouble() - 0.5) * 0.0007;
            lo[i][j] = LNG0 + j * 0.0012 + (azar.nextDouble() - 0.5) * 0.0007;
        }
        var c = new RedPeatonal.Constructor();
        for (int i = 0; i < n; i++) {            // filas
            long[] ids = new long[n]; double[] a = new double[n], b = new double[n];
            for (int j = 0; j < n; j++) { ids[j] = 1000L * i + j + 1; a[j] = la[i][j]; b[j] = lo[i][j]; }
            c.agregarCamino(ids, a, b, RedPeatonal.LLANA);
        }
        for (int j = 0; j < n; j++) {            // columnas
            long[] ids = new long[n]; double[] a = new double[n], b = new double[n];
            for (int i = 0; i < n; i++) { ids[i] = 1000L * i + j + 1; a[i] = la[i][j]; b[i] = lo[i][j]; }
            c.agregarCamino(ids, a, b, RedPeatonal.LLANA);
        }
        for (int k = 0; k < 120; k++) {          // diagonales sueltas
            int i = azar.nextInt(n - 1), j = azar.nextInt(n - 1);
            c.agregarCamino(new long[]{1000L * i + j + 1, 1000L * (i + 1) + j + 2},
                    new double[]{la[i][j], la[i + 1][j + 1]}, new double[]{lo[i][j], lo[i + 1][j + 1]}, RedPeatonal.LLANA);
        }
        red = c.construir();
        segmentos = red.segmentos(LAT0 - 0.05, LNG0 - 0.05, LAT0 + 0.15, LNG0 + 0.15, 100_000);
    }

    /** Distancia mínima en metros del punto a cualquier segmento de la red, por fuerza bruta y con Math.hypot. */
    private static double referencia(double la, double lo) {
        double kx = Math.cos(Math.toRadians(la)) * 111_320, ky = 110_574, mejor = Double.MAX_VALUE;
        for (double[] s : segmentos) {
            double ux = (s[0] - lo) * kx, uy = (s[1] - la) * ky, vx = (s[2] - lo) * kx, vy = (s[3] - la) * ky;      // [lng1, lat1, lng2, lat2]
            double dx = vx - ux, dy = vy - uy, l2 = dx * dx + dy * dy;
            double t = l2 == 0 ? 0 : Math.max(0, Math.min(1, -(ux * dx + uy * dy) / l2));
            mejor = Math.min(mejor, Math.hypot(ux + t * dx, uy + t * dy));
        }
        return mejor;
    }

    @Test void laCalleMasCercanaEsLaMismaQueConLaFuerzaBruta() {
        assertTrue(segmentos.size() > 1500, "la red de prueba tiene muchas calles: " + segmentos.size());
        var azar = new Random(7);
        int comprobados = 0;
        for (int i = 0; i < 600; i++) {
            double la = LAT0 + azar.nextDouble() * 0.036, lo = LNG0 + azar.nextDouble() * 0.036;
            var s = red.ajustar(la, lo, 250);
            double ref = referencia(la, lo);
            if (ref > 250) { assertNull(s, "más lejos de 250 m no hay calle"); continue; }
            assertNotNull(s, "hay calle a " + ref + " m");
            assertEquals(ref, s.distM(), 1e-6, "misma distancia que la fuerza bruta");
            // el punto ajustado está realmente a esa distancia del original
            // distM usa un plano local y metros() la fórmula esférica: difieren ≈ 0,5 % por el método, no por el código
            assertEquals(s.distM(), RedPeatonal.metros(la, lo, s.lat(), s.lng()), Math.max(0.5, 0.02 * s.distM()), "el punto ajustado queda a distM del original");
            comprobados++;
        }
        assertTrue(comprobados > 100, "se comprobaron suficientes puntos: " + comprobados);
    }

    /** La clave de la malla debe ser única por celda y su hashCode no debe coincidir para miles de celdas (era x ^ y). */
    @Test void laClaveDeLaMallaEsUnicaYSuHashSeReparteBien() throws Exception {
        var m = RedPeatonal.class.getDeclaredMethod("clave", int.class, int.class);
        m.setAccessible(true);
        var vistas = new java.util.HashSet<Long>();
        var cubetas = new java.util.HashMap<Integer, Integer>();
        int n = 0;
        for (int x = 29_000; x < 29_000 + 120; x++) for (int y = 28_700; y < 28_700 + 120; y++) {   // celdas de ≈ 277 m: una ciudad de 33 × 33 km
            long k = (long) m.invoke(null, x, y);
            assertTrue(vistas.add(k), "clave repetida en (" + x + "," + y + ")");
            cubetas.merge(Long.hashCode(k) & 0xFFFF, 1, Integer::sum);
            n++;
        }
        int peor = cubetas.values().stream().max(Integer::compare).orElse(0);
        assertEquals(14_400, n);
        assertTrue(peor <= 12, "el hash se reparte: la cubeta más llena tiene " + peor + " celdas (con x^y llegaba a 84)");
    }

    // ------------------------------------------------------------------ versión ORIGINAL de ajustarVarios (referencia exacta)
    private static Object campo(String nombre) throws Exception {
        var f = RedPeatonal.class.getDeclaredField(nombre);
        f.setAccessible(true);
        return f.get(red);
    }

    /**
     * Copia literal del algoritmo anterior (reunir TODAS las aristas del vecindario en una lista de objetos, un conjunto para no repetirlas y un
     * ordenamiento): la optimización de rendimiento debe devolver los mismos {@code Snap}, en el mismo orden, para cualquier punto y parámetros.
     */
    @SuppressWarnings("unchecked")
    private static List<RedPeatonal.Snap> ajustarVariosOriginal(double la, double lo, double maxMetros, int k, double margenMetros) throws Exception {
        double[] lat = (double[]) campo("lat"), lng = (double[]) campo("lng");
        int[] origen = (int[]) campo("origen"), dst = (int[]) campo("dst");
        var rejilla = (java.util.Map<Long, int[]>) campo("rejilla");
        var celda = RedPeatonal.class.getDeclaredMethod("celda", double.class);
        var clave = RedPeatonal.class.getDeclaredMethod("clave", int.class, int.class);
        celda.setAccessible(true); clave.setAccessible(true);
        double kx = Math.cos(Math.toRadians(la)) * 111_320, ky = 110_574, maxCuadrado = maxMetros * maxMetros;
        int cx = (int) celda.invoke(null, lo + 180), cy = (int) celda.invoke(null, la + 90);
        List<RedPeatonal.Snap> encontrados = new java.util.ArrayList<>();
        java.util.Set<Integer> vistas = k > 1 ? new java.util.HashSet<>() : null;
        RedPeatonal.Snap mejor1 = null;
        for (int radio = 1; radio <= 2 && encontrados.isEmpty() && mejor1 == null; radio++) {
            for (int y = cy - radio; y <= cy + radio; y++) for (int x = cx - radio; x <= cx + radio; x++) {
                int[] aristas = rejilla.get((long) clave.invoke(null, x, y));
                if (aristas == null) continue;
                for (int a : aristas) {
                    if (vistas != null && !vistas.add(a)) continue;
                    int u = origen[a], v = dst[a];
                    double ux = (lng[u] - lo) * kx, uy = (lat[u] - la) * ky, vx = (lng[v] - lo) * kx, vy = (lat[v] - la) * ky;
                    double dx = vx - ux, dy = vy - uy, l2 = dx * dx + dy * dy;
                    double t = l2 == 0 ? 0 : Math.max(0, Math.min(1, -(ux * dx + uy * dy) / l2));
                    double px = ux + t * dx, py = uy + t * dy, d2 = px * px + py * py;
                    if (d2 > maxCuadrado) continue;
                    double d = Math.sqrt(d2);
                    if (vistas == null) { if (mejor1 == null || d < mejor1.distM()) mejor1 = new RedPeatonal.Snap(a, u, v, t, la + py / ky, lo + px / kx, d); }
                    else encontrados.add(new RedPeatonal.Snap(a, u, v, t, la + py / ky, lo + px / kx, d));
                }
            }
        }
        if (mejor1 != null) return List.of(mejor1);
        if (encontrados.isEmpty()) return List.of();
        encontrados.sort(java.util.Comparator.comparingDouble(RedPeatonal.Snap::distM));
        double limite = encontrados.get(0).distM() + margenMetros;
        List<RedPeatonal.Snap> r = new java.util.ArrayList<>();
        for (var sn : encontrados) if (r.size() < k && sn.distM() <= limite) r.add(sn);
        return r;
    }

    @Test void ajustarVariosOptimizadoDaExactamenteLoMismoQueLaVersionOriginal() throws Exception {
        var azar = new Random(2024);
        int[][] configuraciones = {{250, 5, 40}, {250, 4, 40}, {550, 4, 40}, {250, 3, 0}, {100, 2, 15}, {250, 1, 0}, {550, 8, 120}};
        int conResultado = 0, conVarias = 0;
        for (int[] c : configuraciones) {
            for (int i = 0; i < 700; i++) {
                // dentro de la red, en el borde y fuera de ella (para que también haya casos sin calle y con radio ampliado)
                double la = LAT0 - 0.004 + azar.nextDouble() * 0.045, lo = LNG0 - 0.004 + azar.nextDouble() * 0.045;
                var esperado = ajustarVariosOriginal(la, lo, c[0], c[1], c[2]);
                var obtenido = red.ajustarVarios(la, lo, c[0], c[1], c[2]);
                assertEquals(esperado, obtenido, "mismas calles, mismo orden y mismas distancias en (" + la + "," + lo + ") con " + java.util.Arrays.toString(c));
                if (!esperado.isEmpty()) conResultado++;
                if (esperado.size() > 1) conVarias++;
            }
        }
        assertTrue(conResultado > 3000, "la prueba cubre muchos puntos con calle: " + conResultado);
        assertTrue(conVarias > 1200, "y muchos con varias calles casi igual de cerca (empates incluidos): " + conVarias);
    }

    /** Copia literal del Dijkstra anterior (con {@code java.util.PriorityQueue} de objetos): referencia exacta para {@code costosDesde}. */
    private static double[] costosDesdeOriginal(List<RedPeatonal.Snap> salidas, List<RedPeatonal.Snap> destinos, double costoMaximo) throws Exception {
        double[] lat = (double[]) campo("lat");
        int[] ini = (int[]) campo("ini"), dst = (int[]) campo("dst");
        float[] largo = (float[]) campo("largo");
        byte[] clase = (byte[]) campo("clase");
        boolean[] valido = (boolean[]) campo("valido");
        var factorCampo = RedPeatonal.class.getDeclaredField("FACTOR");
        factorCampo.setAccessible(true);
        double[] FACTOR = (double[]) factorCampo.get(null);
        record Item(double clave, double g, int nodo) {}
        double[] r = new double[destinos.size()];
        java.util.Arrays.fill(r, Double.NaN);
        int n = lat.length;
        boolean[] pendiente = new boolean[n];
        int faltan = 0;
        for (var d : destinos) if (d != null) {
            if (!pendiente[d.u()]) { pendiente[d.u()] = true; faltan++; }
            if (!pendiente[d.v()]) { pendiente[d.v()] = true; faltan++; }
        }
        double[] g = new double[n];
        java.util.Arrays.fill(g, Double.MAX_VALUE);
        boolean[] fijo = new boolean[n];
        var cola = new java.util.PriorityQueue<Item>((x, y) -> Double.compare(x.clave, y.clave));
        for (var a : salidas) {
            double fa = FACTOR[clase[a.arista()]];
            double gu = a.distM() + a.t() * largo[a.arista()] * fa, gv = a.distM() + (1 - a.t()) * largo[a.arista()] * fa;
            if (gu < g[a.u()]) { g[a.u()] = gu; cola.add(new Item(gu, gu, a.u())); }
            if (gv < g[a.v()]) { g[a.v()] = gv; cola.add(new Item(gv, gv, a.v())); }
        }
        while (!cola.isEmpty() && faltan > 0) {
            Item it = cola.poll();
            if (it.g > costoMaximo) break;
            if (fijo[it.nodo]) continue;
            fijo[it.nodo] = true;
            if (pendiente[it.nodo]) faltan--;
            for (int e = ini[it.nodo]; e < ini[it.nodo + 1]; e++) {
                int w = dst[e];
                if (!valido[w] || fijo[w]) continue;
                double ng = it.g + largo[e] * FACTOR[clase[e]];
                if (ng < g[w]) { g[w] = ng; cola.add(new Item(ng, ng, w)); }
            }
        }
        for (int i = 0; i < r.length; i++) {
            var d = destinos.get(i);
            if (d == null) continue;
            double fb = FACTOR[clase[d.arista()]];
            double viaU = fijo[d.u()] ? g[d.u()] + d.t() * largo[d.arista()] * fb : Double.NaN;
            double viaV = fijo[d.v()] ? g[d.v()] + (1 - d.t()) * largo[d.arista()] * fb : Double.NaN;
            double mejor = Double.isNaN(viaU) ? viaV : Double.isNaN(viaV) ? viaU : Math.min(viaU, viaV);
            for (var a : salidas) if (a.arista() == d.arista()) {
                double directo = a.distM() + Math.abs(a.t() - d.t()) * largo[d.arista()] * fb;
                mejor = Double.isNaN(mejor) ? directo : Math.min(mejor, directo);
            }
            r[i] = mejor;
        }
        return r;
    }

    @Test void costosDesdeConElMonticuloDeArreglosDaExactamenteLoMismoQueConPriorityQueue() throws Exception {
        var azar = new Random(314);
        int comparados = 0, alcanzados = 0;
        for (int i = 0; i < 80; i++) {
            double la = LAT0 + azar.nextDouble() * 0.036, lo = LNG0 + azar.nextDouble() * 0.036;
            var salidas = red.ajustarVarios(la, lo, 550, 4, 40);
            if (salidas.isEmpty()) continue;
            List<RedPeatonal.Snap> destinos = new java.util.ArrayList<>();
            int cuantos = i % 5 == 0 ? 3 : 300 + azar.nextInt(700);
            for (int j = 0; j < cuantos; j++) {
                var d = red.ajustarVarios(LAT0 - 0.003 + azar.nextDouble() * 0.042, LNG0 - 0.003 + azar.nextDouble() * 0.042, 250, 5, 40);
                destinos.addAll(d);
                if (j % 97 == 0) destinos.add(null);                          // un destino sin calle
            }
            double tope = i % 4 == 0 ? 400 : i % 4 == 1 ? 1500 : 8000;         // con tope corto muchos quedan sin alcanzar (NaN)
            double[] esperado = costosDesdeOriginal(salidas, destinos, tope), obtenido = red.costosDesde(salidas, destinos, tope);
            assertArrayEquals(esperado, obtenido, 0.0, "mismos costos (incluidos los NaN) con tope " + tope);
            comparados++;
            for (double v : obtenido) if (!Double.isNaN(v)) alcanzados++;
        }
        assertTrue(comparados > 60, "casos comparados: " + comparados);
        assertTrue(alcanzados > 20_000, "destinos alcanzados en total: " + alcanzados);
    }

    @Test void ajustarVariosConAristasRepetidasEnVariasCeldasNoLasDuplica() {
        // la red de prueba tiene calles de más de una celda de largo: un punto cerca de ellas no puede devolver la misma arista dos veces
        var azar = new Random(5);
        for (int i = 0; i < 400; i++) {
            double la = LAT0 + azar.nextDouble() * 0.036, lo = LNG0 + azar.nextDouble() * 0.036;
            var r = red.ajustarVarios(la, lo, 550, 8, 200);
            assertEquals(r.size(), r.stream().map(RedPeatonal.Snap::arista).distinct().count(), "sin aristas repetidas");
        }
    }

    @Test void laCacheDeAjustesDevuelveLoMismoQueElCalculoYReutilizaElResultado() {
        var azar = new Random(9);
        int antes = red.ajustesGuardados();
        for (int i = 0; i < 300; i++) {
            double la = LAT0 + azar.nextDouble() * 0.036, lo = LNG0 + azar.nextDouble() * 0.036;
            var directo = red.ajustarVarios(la, lo, 250, 5, 40);
            var primera = red.ajustarVariosGuardado(la, lo, 250, 5, 40);
            var segunda = red.ajustarVariosGuardado(la, lo, 250, 5, 40);
            assertEquals(directo, primera, "la caché no cambia el resultado");
            assertSame(primera, segunda, "el segundo pedido sale de la caché");
            // otros parámetros sobre el mismo punto no comparten entrada
            assertEquals(red.ajustarVarios(la, lo, 550, 4, 40), red.ajustarVariosGuardado(la, lo, 550, 4, 40));
        }
        assertTrue(red.ajustesGuardados() - antes >= 300, "se guardaron los puntos distintos");
        assertThrows(UnsupportedOperationException.class, () -> red.ajustarVariosGuardado(LAT0, LNG0, 250, 5, 40).add(null),
                "lo guardado es inmutable: nadie puede alterarlo para los demás");
    }

    @Test void variasCallesCercanasSiguenOrdenadasYDentroDelMargen() {
        var azar = new Random(11);
        for (int i = 0; i < 200; i++) {
            double la = LAT0 + azar.nextDouble() * 0.036, lo = LNG0 + azar.nextDouble() * 0.036;
            var varias = red.ajustarVarios(la, lo, 250, 3, 40);
            for (int j = 1; j < varias.size(); j++) {
                assertTrue(varias.get(j).distM() >= varias.get(j - 1).distM(), "ordenadas de menor a mayor distancia");
                assertTrue(varias.get(j).distM() <= varias.get(0).distM() + 40 + 1e-9, "dentro del margen de la más cercana");
                assertNotEquals(varias.get(j).arista(), varias.get(0).arista(), "aristas distintas");
            }
            if (!varias.isEmpty()) assertEquals(referencia(la, lo), varias.get(0).distM(), 1e-6);
        }
    }
}
