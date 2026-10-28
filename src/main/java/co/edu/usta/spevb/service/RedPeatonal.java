package co.edu.usta.spevb.service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

/**
 * Red peatonal en memoria (calles, andenes, senderos y escaleras de OpenStreetMap) con búsqueda de camino A*.
 * Los puntos se «pegan» a la calle más cercana; el camino sólo recorre aristas de la red, así que nunca atraviesa
 * manzanas, edificios ni lotes. Las piezas aisladas de la red (islotes) se descartan para no dejar a nadie
 * atrapado en un tramo sin salida.
 */
public final class RedPeatonal {
    /** Punto proyectado sobre una arista: {@code t} es la fracción desde {@code u} hacia {@code v}. */
    public record Snap(int arista, int u, int v, double t, double lat, double lng, double distM) {}

    public record Camino(List<double[]> coordenadas, double metros) {}

    /** Multiplicadores de costo por clase de vía: sólo desempatan (prefieren andenes y calles tranquilas). */
    private static final double[] FACTOR = {1.0, 1.25, 1.1, 1.05, 1.15, 2.5};
    static final byte LLANA = 0, ESCALERA = 1, SENDERO = 2, VIA_LOCAL = 3, VIA_PRINCIPAL = 4, TRONCAL = 5;
    private static final double CELDA = 0.0025;
    private static final int TAMANO_MINIMO_ISLOTE = 30;

    /** Clase de costo para un valor de {@code highway}; {@code -1} si no se debe recorrer a pie. */
    public static byte clase(String highway) {
        if (highway == null) return -1;
        return switch (highway) {
            case "footway", "pedestrian", "cycleway", "living_street", "residential", "service", "unclassified",
                 "corridor", "road", "pedestrian_area" -> LLANA;
            case "path", "track", "bridleway" -> SENDERO;
            case "steps" -> ESCALERA;
            case "tertiary", "tertiary_link" -> VIA_LOCAL;
            case "secondary", "secondary_link", "primary", "primary_link" -> VIA_PRINCIPAL;
            case "trunk", "trunk_link" -> TRONCAL;
            default -> -1;
        };
    }

    private final double[] lat, lng;
    private final int[] ini, dst, origen;
    private final float[] largo;
    private final byte[] clase;
    private final boolean[] valido;
    private final Map<Long, int[]> rejilla;
    private final double minLat, maxLat, minLng, maxLng;

    private RedPeatonal(double[] lat, double[] lng, int[] ini, int[] dst, int[] origen, float[] largo, byte[] clase,
                        boolean[] valido, Map<Long, int[]> rejilla) {
        this.lat = lat; this.lng = lng; this.ini = ini; this.dst = dst; this.origen = origen;
        this.largo = largo; this.clase = clase; this.valido = valido; this.rejilla = rejilla;
        double a = 90, b = -90, c = 180, d = -180;
        for (int i = 0; i < lat.length; i++) {
            if (!valido[i]) continue;
            a = Math.min(a, lat[i]); b = Math.max(b, lat[i]); c = Math.min(c, lng[i]); d = Math.max(d, lng[i]);
        }
        minLat = a; maxLat = b; minLng = c; maxLng = d;
    }

    public int nodos() { return lat.length; }
    public int aristas() { return dst.length / 2; }
    /** Nodos que forman parte de la red principal (los islotes aislados se descartan). */
    public int nodosAccesibles() { int c = 0; for (boolean v : valido) if (v) c++; return c; }
    /** Puntos [lat, lng] al azar sobre la red (para pruebas y diagnóstico). */
    double[][] puntosDeMuestra(int cuantos, java.util.Random azar) {
        List<double[]> r = new ArrayList<>();
        while (r.size() < cuantos) { int i = azar.nextInt(lat.length); if (valido[i]) r.add(new double[]{lat[i], lng[i]}); }
        return r.toArray(new double[0][]);
    }
    /**
     * Tramos de la red dentro de una caja, como [lng1, lat1, lng2, lat2] (para mostrarlos en el mapa de administración).
     * Devuelve como máximo {@code maximo} tramos.
     */
    public List<double[]> segmentos(double sur, double oeste, double norte, double este, int maximo) {
        List<double[]> r = new ArrayList<>();
        for (int a = 0; a < dst.length && r.size() < maximo; a++) {
            int u = origen[a], v = dst[a];
            if (u >= v || !valido[u]) continue;
            if ((lat[u] < sur && lat[v] < sur) || (lat[u] > norte && lat[v] > norte)
                    || (lng[u] < oeste && lng[v] < oeste) || (lng[u] > este && lng[v] > este)) continue;
            r.add(new double[]{lng[u], lat[u], lng[v], lat[v]});
        }
        return r;
    }

    public boolean vacia() { return rejilla.isEmpty(); }

    // ------------------------------------------------------------------ construcción
    public static final class Constructor {
        private final Map<Long, Integer> indice = new HashMap<>();
        private double[] lat = new double[1024], lng = new double[1024];
        private int n;
        private int[] eu = new int[2048], ev = new int[2048];
        private byte[] ec = new byte[2048];
        private boolean[] ep = new boolean[2048];   // arista de un paso propio: los bloqueos no la tocan
        private int m;
        private long sintetico = -1;
        private final List<double[][]> bloqueos = new ArrayList<>();   // cada uno: {lats, lngs}

        private int nodo(long id, double la, double lo) {
            Integer i = indice.get(id);
            if (i != null) return i;
            if (n == lat.length) { lat = Arrays.copyOf(lat, n * 2); lng = Arrays.copyOf(lng, n * 2); }
            lat[n] = la; lng[n] = lo;
            indice.put(id, n);
            return n++;
        }

        /** Agrega un camino: cada par de vértices consecutivos es una arista bidireccional. */
        public void agregarCamino(long[] ids, double[] lats, double[] lngs, byte clase) {
            int anterior = -1;
            for (int i = 0; i < ids.length; i++) {
                int actual = nodo(ids[i], lats[i], lngs[i]);
                if (anterior >= 0 && anterior != actual) {
                    agregarArista(anterior, actual, clase, false);
                }
                anterior = actual;
            }
        }

        private void agregarArista(int u, int v, byte clase, boolean propia) {
            if (m == eu.length) {
                eu = Arrays.copyOf(eu, m * 2); ev = Arrays.copyOf(ev, m * 2);
                ec = Arrays.copyOf(ec, m * 2); ep = Arrays.copyOf(ep, m * 2);
            }
            eu[m] = u; ev[m] = v; ec[m] = clase; ep[m] = propia; m++;
        }

        /** Nodo existente más cercano al punto, dentro de {@code maxMetros}; -1 si no hay. */
        private int nodoCercano(double la, double lo, double maxMetros) {
            int mejor = -1;
            double dmin = maxMetros;
            for (int i = 0; i < n; i++) {
                if (Math.abs(lat[i] - la) > 0.0004 || Math.abs(lng[i] - lo) > 0.0004) continue;
                double d = metros(la, lo, lat[i], lng[i]);
                if (d <= dmin) { dmin = d; mejor = i; }
            }
            return mejor;
        }

        /**
         * Extremo de un paso: se une al nodo cercano; si no hay, a la calle más cercana partiéndola en dos; si tampoco
         * hay calle cerca, queda suelto. {@code unido[0]} indica si se logró conectar.
         */
        private int extremo(double la, double lo, double enlaceMetros, boolean[] unido) {
            int existente = nodoCercano(la, lo, enlaceMetros);
            if (existente >= 0) { unido[0] = true; return existente; }
            double kx = Math.cos(Math.toRadians(la)) * 111_320, ky = 110_574, mejorD = enlaceMetros * 2, mejorT = 0;
            int mejorE = -1;
            for (int i = 0; i < m; i++) {
                if (ep[i]) continue;
                double ux = (lng[eu[i]] - lo) * kx, uy = (lat[eu[i]] - la) * ky, vx = (lng[ev[i]] - lo) * kx, vy = (lat[ev[i]] - la) * ky;
                if (Math.min(ux, vx) > mejorD || Math.max(ux, vx) < -mejorD || Math.min(uy, vy) > mejorD || Math.max(uy, vy) < -mejorD) continue;
                double dx = vx - ux, dy = vy - uy, l2 = dx * dx + dy * dy;
                double t = l2 == 0 ? 0 : Math.max(0, Math.min(1, -(ux * dx + uy * dy) / l2));
                double d = Math.hypot(ux + t * dx, uy + t * dy);
                if (d < mejorD) { mejorD = d; mejorE = i; mejorT = t; }
            }
            if (mejorE >= 0 && mejorD <= enlaceMetros * 2) {
                int u = eu[mejorE], v = ev[mejorE];
                double nl = lat[u] + mejorT * (lat[v] - lat[u]), no = lng[u] + mejorT * (lng[v] - lng[u]);
                int nuevo = nodo(sintetico--, nl, no);
                byte c = ec[mejorE];
                ev[mejorE] = nuevo;                 // u—nuevo reemplaza a u—v
                agregarArista(nuevo, v, c, false);  // nuevo—v
                unido[0] = true;
                return nuevo;
            }
            unido[0] = false;
            return nodo(sintetico--, la, lo);
        }

        /**
         * Paso peatonal propio (andén, sendero, puente, atajo entre manzanas) que se suma a la red de OpenStreetMap.
         * Devuelve cuántos de sus dos extremos quedaron conectados a la red (0, 1 o 2).
         */
        public int agregarPaso(double[] lats, double[] lngs, double enlaceMetros) {
            int conectados = 0;
            int anterior = -1;
            for (int i = 0; i < lats.length; i++) {
                boolean extremo = i == 0 || i == lats.length - 1;
                int actual;
                if (extremo) {
                    boolean[] u = new boolean[1];
                    actual = extremo(lats[i], lngs[i], enlaceMetros, u);
                    if (u[0]) conectados++;
                } else {
                    actual = nodo(sintetico--, lats[i], lngs[i]);
                }
                if (anterior >= 0 && anterior != actual) agregarArista(anterior, actual, LLANA, true);
                anterior = actual;
            }
            return conectados;
        }

        /** Bloqueo: las calles de OpenStreetMap que pasan a menos de {@code metros} de la línea dejan de ser transitables. */
        public void bloquear(double[] lats, double[] lngs) { bloqueos.add(new double[][]{lats.clone(), lngs.clone()}); }

        private boolean bloqueada(int i, double radio) {
            double la = (lat[eu[i]] + lat[ev[i]]) / 2;
            double kx = Math.cos(Math.toRadians(la)) * 111_320, ky = 110_574;
            double ax = (lng[eu[i]] - lng[ev[i]]) * kx / 2, ay = (lat[eu[i]] - lat[ev[i]]) * ky / 2; // arista centrada en su punto medio
            double mx = (lng[eu[i]] + lng[ev[i]]) / 2, my = (lat[eu[i]] + lat[ev[i]]) / 2;
            for (double[][] b : bloqueos) {
                for (int k = 0; k + 1 < b[0].length; k++) {
                    double px = (b[1][k] - mx) * kx, py = (b[0][k] - my) * ky, qx = (b[1][k + 1] - mx) * kx, qy = (b[0][k + 1] - my) * ky;
                    if (distanciaSegmentos(-ax, -ay, ax, ay, px, py, qx, qy) <= radio) return true;
                }
            }
            return false;
        }

        public RedPeatonal construir() {
            if (!bloqueos.isEmpty()) {
                int k = 0;
                for (int i = 0; i < m; i++) {
                    if (!ep[i] && bloqueada(i, 6)) continue;
                    eu[k] = eu[i]; ev[k] = ev[i]; ec[k] = ec[i]; ep[k] = ep[i]; k++;
                }
                m = k;
            }
            int e = m * 2;
            int[] grado = new int[n + 1];
            for (int i = 0; i < m; i++) { grado[eu[i] + 1]++; grado[ev[i] + 1]++; }
            for (int i = 0; i < n; i++) grado[i + 1] += grado[i];
            int[] ini = grado, pos = Arrays.copyOf(grado, n), dst = new int[e], origen = new int[e];
            float[] largo = new float[e];
            byte[] clase = new byte[e];
            int[] padre = new int[n];
            for (int i = 0; i < n; i++) padre[i] = i;
            for (int i = 0; i < m; i++) {
                float d = (float) metros(lat[eu[i]], lng[eu[i]], lat[ev[i]], lng[ev[i]]);
                int a = pos[eu[i]]++, b = pos[ev[i]]++;
                dst[a] = ev[i]; origen[a] = eu[i]; largo[a] = d; clase[a] = ec[i];
                dst[b] = eu[i]; origen[b] = ev[i]; largo[b] = d; clase[b] = ec[i];
                unir(padre, eu[i], ev[i]);
            }
            int[] tamano = new int[n];
            for (int i = 0; i < n; i++) tamano[raiz(padre, i)]++;
            int mayor = 0;
            for (int i = 0; i < n; i++) if (tamano[i] > tamano[mayor]) mayor = i;
            boolean[] valido = new boolean[n];
            for (int i = 0; i < n; i++) {
                int r = raiz(padre, i);
                valido[i] = tamano[r] >= TAMANO_MINIMO_ISLOTE || (n > 0 && r == mayor);
            }
            // Rejilla: cada arista (una sola vez, sentido u<v) se registra en las celdas que cubre su caja.
            Map<Long, List<Integer>> temp = new HashMap<>();
            for (int a = 0; a < e; a++) {
                int u = origen[a], v = dst[a];
                if (u >= v || !valido[u]) continue;
                int x0 = celda(Math.min(lng[u], lng[v]) + 180), x1 = celda(Math.max(lng[u], lng[v]) + 180);
                int y0 = celda(Math.min(lat[u], lat[v]) + 90), y1 = celda(Math.max(lat[u], lat[v]) + 90);
                for (int y = y0; y <= y1; y++) for (int x = x0; x <= x1; x++)
                    temp.computeIfAbsent(clave(x, y), k -> new ArrayList<>()).add(a);
            }
            Map<Long, int[]> rejilla = new HashMap<>(temp.size() * 2);
            temp.forEach((k, v) -> rejilla.put(k, v.stream().mapToInt(Integer::intValue).toArray()));
            return new RedPeatonal(Arrays.copyOf(lat, n), Arrays.copyOf(lng, n), ini, dst, origen, largo, clase, valido, rejilla);
        }

        private static int raiz(int[] p, int i) {
            while (p[i] != i) { p[i] = p[p[i]]; i = p[i]; }
            return i;
        }
        private static void unir(int[] p, int a, int b) { p[raiz(p, a)] = raiz(p, b); }
    }

    private static int celda(double v) { return (int) Math.floor(v / CELDA); }
    private static long clave(int x, int y) { return ((long) y << 32) ^ (x & 0xffffffffL); }

    // ------------------------------------------------------------------ geometría
    /** Distancia mínima entre los segmentos AB y CD en un plano (metros). */
    static double distanciaSegmentos(double ax, double ay, double bx, double by, double cx, double cy, double dx, double dy) {
        double d1 = (bx - ax) * (cy - ay) - (by - ay) * (cx - ax), d2 = (bx - ax) * (dy - ay) - (by - ay) * (dx - ax);
        double d3 = (dx - cx) * (ay - cy) - (dy - cy) * (ax - cx), d4 = (dx - cx) * (by - cy) - (dy - cy) * (bx - cx);
        if (d1 * d2 < 0 && d3 * d4 < 0) return 0;
        return Math.min(Math.min(puntoSegmento(ax, ay, cx, cy, dx, dy), puntoSegmento(bx, by, cx, cy, dx, dy)),
                Math.min(puntoSegmento(cx, cy, ax, ay, bx, by), puntoSegmento(dx, dy, ax, ay, bx, by)));
    }

    private static double puntoSegmento(double px, double py, double ax, double ay, double bx, double by) {
        double dx = bx - ax, dy = by - ay, l2 = dx * dx + dy * dy;
        double t = l2 == 0 ? 0 : Math.max(0, Math.min(1, ((px - ax) * dx + (py - ay) * dy) / l2));
        return Math.hypot(px - (ax + t * dx), py - (ay + t * dy));
    }

    static double metros(double lat1, double lng1, double lat2, double lng2) {
        double dLat = Math.toRadians(lat2 - lat1), dLng = Math.toRadians(lng2 - lng1);
        double h = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        return 2 * 6_371_000 * Math.asin(Math.sqrt(h));
    }

    /** Ajusta un punto a la arista más cercana (máximo {@code maxMetros}); {@code null} si no hay calle cerca. */
    public Snap ajustar(double la, double lo, double maxMetros) {
        var r = ajustarVarios(la, lo, maxMetros, 1, 0);
        return r.isEmpty() ? null : r.get(0);
    }

    /**
     * Las hasta {@code k} calles más cercanas (cada una como arista distinta) que no superen la más cercana en más de
     * {@code margenMetros}. Un punto en medio de una manzana tiene a menudo dos o tres calles casi igual de cerca: probarlas
     * todas evita elegir la que obliga a rodear.
     */
    public List<Snap> ajustarVarios(double la, double lo, double maxMetros, int k, double margenMetros) {
        if (vacia()) return List.of();
        double kx = Math.cos(Math.toRadians(la)) * 111_320, ky = 110_574;
        int cx = celda(lo + 180), cy = celda(la + 90);
        List<Snap> encontrados = new ArrayList<>();
        java.util.Set<Integer> vistas = k > 1 ? new java.util.HashSet<>() : null;
        Snap mejor1 = null;
        for (int radio = 1; radio <= 2 && encontrados.isEmpty() && mejor1 == null; radio++) {
            for (int y = cy - radio; y <= cy + radio; y++) for (int x = cx - radio; x <= cx + radio; x++) {
                int[] aristas = rejilla.get(clave(x, y));
                if (aristas == null) continue;
                for (int a : aristas) {
                    if (vistas != null && !vistas.add(a)) continue;
                    int u = origen[a], v = dst[a];
                    double ux = (lng[u] - lo) * kx, uy = (lat[u] - la) * ky, vx = (lng[v] - lo) * kx, vy = (lat[v] - la) * ky;
                    double dx = vx - ux, dy = vy - uy, l2 = dx * dx + dy * dy;
                    double t = l2 == 0 ? 0 : Math.max(0, Math.min(1, -(ux * dx + uy * dy) / l2));
                    double px = ux + t * dx, py = uy + t * dy, d = Math.hypot(px, py);
                    if (d > maxMetros) continue;
                    if (vistas == null) { if (mejor1 == null || d < mejor1.distM()) mejor1 = new Snap(a, u, v, t, la + py / ky, lo + px / kx, d); }
                    else encontrados.add(new Snap(a, u, v, t, la + py / ky, lo + px / kx, d));
                }
            }
        }
        if (mejor1 != null) return List.of(mejor1);
        if (encontrados.isEmpty()) return List.of();
        encontrados.sort(java.util.Comparator.comparingDouble(Snap::distM));
        double limite = encontrados.get(0).distM() + margenMetros;
        List<Snap> r = new ArrayList<>();
        for (Snap sn : encontrados) if (r.size() < k && sn.distM() <= limite) r.add(sn);
        return r;
    }

    private double costoArista(int a) { return largo[a] * FACTOR[clase[a]]; }

    // ------------------------------------------------------------------ búsqueda
    private record Item(double clave, double g, int nodo) {}

    /** Camino a pie desde cualquiera de las calles de salida hasta cualquiera de las de llegada (menor costo). */
    public Camino camino(List<Snap> salidas, List<Snap> llegadas, double costoMaximo) {
        double directo = Double.MAX_VALUE;
        Snap dA = null, dB = null;
        for (Snap a : salidas) for (Snap b : llegadas) if (a.arista() == b.arista()) {
            double c = a.distM() + Math.abs(a.t() - b.t()) * largo[a.arista()] * FACTOR[clase[a.arista()]] + b.distM();
            if (c < directo) { directo = c; dA = a; dB = b; }
        }
        // Colas de llegada: costo desde cada extremo de la arista de llegada hasta el punto (incluye su ajuste a la calle).
        Map<Integer, Double> cola = new HashMap<>();
        Map<Integer, Snap> metaDe = new HashMap<>();
        for (Snap b : llegadas) {
            double fb = FACTOR[clase[b.arista()]];
            double cu = b.distM() + b.t() * largo[b.arista()] * fb, cv = b.distM() + (1 - b.t()) * largo[b.arista()] * fb;
            if (cu < cola.getOrDefault(b.u(), Double.MAX_VALUE)) { cola.put(b.u(), cu); metaDe.put(b.u(), b); }
            if (cv < cola.getOrDefault(b.v(), Double.MAX_VALUE)) { cola.put(b.v(), cv); metaDe.put(b.v(), b); }
        }
        Map<Integer, Double> g = new HashMap<>();
        Map<Integer, Integer> previo = new HashMap<>();
        Map<Integer, Snap> inicioDe = new HashMap<>();
        PriorityQueue<Item> pq = new PriorityQueue<>((x, y) -> Double.compare(x.clave, y.clave));
        for (Snap a : salidas) {
            double fa = FACTOR[clase[a.arista()]];
            double gu = a.distM() + a.t() * largo[a.arista()] * fa, gv = a.distM() + (1 - a.t()) * largo[a.arista()] * fa;
            if (gu < g.getOrDefault(a.u(), Double.MAX_VALUE)) { g.put(a.u(), gu); previo.put(a.u(), -1); inicioDe.put(a.u(), a); pq.add(new Item(gu + h(a.u(), llegadas), gu, a.u())); }
            if (gv < g.getOrDefault(a.v(), Double.MAX_VALUE)) { g.put(a.v(), gv); previo.put(a.v(), -1); inicioDe.put(a.v(), a); pq.add(new Item(gv + h(a.v(), llegadas), gv, a.v())); }
        }
        double mejor = directo;
        int fin = -1;
        while (!pq.isEmpty()) {
            Item it = pq.poll();
            if (it.clave >= mejor || it.g > costoMaximo) break;
            if (it.g > g.getOrDefault(it.nodo, Double.MAX_VALUE)) continue;
            Double c = cola.get(it.nodo);
            if (c != null && it.g + c < mejor) { mejor = it.g + c; fin = it.nodo; }
            for (int e = ini[it.nodo]; e < ini[it.nodo + 1]; e++) {
                int w = dst[e];
                if (!valido[w]) continue;
                double ng = it.g + costoArista(e);
                if (ng < g.getOrDefault(w, Double.MAX_VALUE)) {
                    g.put(w, ng); previo.put(w, it.nodo);
                    pq.add(new Item(ng + h(w, llegadas), ng, w));
                }
            }
        }
        if (fin < 0) {
            if (dA == null) return null;
            return new Camino(List.of(new double[]{dA.lng(), dA.lat()}, new double[]{dB.lng(), dB.lat()}),
                    Math.abs(dA.t() - dB.t()) * largo[dA.arista()]);
        }
        List<Integer> nodos = new ArrayList<>();
        int n0 = fin;
        for (int n = fin; n >= 0; n = previo.get(n)) { nodos.add(n); n0 = n; }
        java.util.Collections.reverse(nodos);
        Snap a = inicioDe.get(n0), b = metaDe.get(fin);
        List<double[]> puntos = new ArrayList<>(nodos.size() + 2);
        puntos.add(new double[]{a.lng(), a.lat()});
        for (int n : nodos) puntos.add(new double[]{lng[n], lat[n]});
        puntos.add(new double[]{b.lng(), b.lat()});
        double total = 0;
        for (int i = 1; i < puntos.size(); i++)
            total += metros(puntos.get(i - 1)[1], puntos.get(i - 1)[0], puntos.get(i)[1], puntos.get(i)[0]);
        return new Camino(List.copyOf(puntos), total);
    }

    private double h(int nodo, List<Snap> metas) {
        double m = Double.MAX_VALUE;
        for (Snap s : metas) m = Math.min(m, metros(lat[nodo], lng[nodo], s.lat(), s.lng()));
        return m;
    }

    /**
     * Costo a pie (≈ metros, incluye lo que separa el punto de partida de su calle) desde las calles de salida a muchos
     * destinos con una sola exploración. {@code NaN} = sin camino dentro del costo máximo. El ajuste de cada destino a su
     * calle ({@code distM}) no se incluye: lo suma quien llama.
     */
    public double[] costosDesde(List<Snap> salidas, List<Snap> destinos, double costoMaximo) {
        double[] r = new double[destinos.size()];
        Arrays.fill(r, Double.NaN);
        int n = lat.length;
        boolean[] pendiente = new boolean[n];
        int faltan = 0;
        for (Snap d : destinos) if (d != null) {
            if (!pendiente[d.u()]) { pendiente[d.u()] = true; faltan++; }
            if (!pendiente[d.v()]) { pendiente[d.v()] = true; faltan++; }
        }
        double[] g = new double[n];
        Arrays.fill(g, Double.MAX_VALUE);
        boolean[] fijo = new boolean[n];
        PriorityQueue<Item> cola = new PriorityQueue<>((x, y) -> Double.compare(x.clave, y.clave));
        for (Snap a : salidas) {
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
                double ng = it.g + costoArista(e);
                if (ng < g[w]) { g[w] = ng; cola.add(new Item(ng, ng, w)); }
            }
        }
        for (int i = 0; i < r.length; i++) {
            Snap d = destinos.get(i);
            if (d == null) continue;
            double fb = FACTOR[clase[d.arista()]];
            double viaU = fijo[d.u()] ? g[d.u()] + d.t() * largo[d.arista()] * fb : Double.NaN;
            double viaV = fijo[d.v()] ? g[d.v()] + (1 - d.t()) * largo[d.arista()] * fb : Double.NaN;
            double mejor = Double.isNaN(viaU) ? viaV : Double.isNaN(viaV) ? viaU : Math.min(viaU, viaV);
            for (Snap a : salidas) if (a.arista() == d.arista()) {
                double directo = a.distM() + Math.abs(a.t() - d.t()) * largo[d.arista()] * fb;
                mejor = Double.isNaN(mejor) ? directo : Math.min(mejor, directo);
            }
            r[i] = mejor;
        }
        return r;
    }

    /** Cobertura de la red: sirve para decidir si un punto cae fuera de la zona cargada. */
    public boolean cubre(double la, double lo) {
        return !vacia() && la >= minLat - 0.01 && la <= maxLat + 0.01 && lo >= minLng - 0.01 && lo <= maxLng + 0.01;
    }

    // ------------------------------------------------------------------ persistencia compacta
    /** Exportación de caminos para guardar en disco (se reconstruye con {@link Constructor}). */
    public record CaminoGuardado(long[] ids, double[] lats, double[] lngs, byte clase) {}
}
