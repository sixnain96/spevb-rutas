package co.edu.usta.spevb.vial;

import co.edu.usta.spevb.vial.ReglasBus.Sentido;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.Set;

/**
 * Grafo dirigido de vías transitables por buses, SEPARADO de la red peatonal. Se construye una vez desde una lista de
 * {@link ViaOsm}; solo entran las vías que {@link ReglasBus#evaluar} acepta. Es inmutable y seguro entre hilos.
 *
 * <p>Los nodos son coordenadas redondeadas a 7 decimales: dos vías se conectan únicamente si comparten un vértice
 * (así, vías paralelas o que se cruzan sin vértice común, como un puente, NO están conectadas). Cada tramo entre vértices
 * consecutivos de una vía es un segmento con una o dos direcciones según su {@link Sentido}. Las distancias usan una
 * proyección equirectangular local en metros y un índice de rejilla de 100 m para consultar segmentos cercanos.
 */
public final class RedVialBus {
    /** Segmento cercano a un punto consultado. {@code distanciaM}: del punto al segmento. */
    public record Candidato(long viaId, int tramo, double lat, double lng, double distanciaM, String highway,
                            String nombre, Sentido sentido) {}

    /** Posición sobre un segmento (t en [0,1] desde el vértice A hacia el B), uso interno del mapa-matching. */
    record Snap(int seg, double t, double lat, double lng, double x, double y, double dist) {}

    private static final double M_POR_GRADO = 6_371_008.8 * Math.PI / 180.0;
    private static final double CELDA_M = 100;
    private static final double MAX_BUSQUEDA_M = 20_000;

    // proyección local
    final double lat0, lng0, mLng;
    // nodos
    final int nNodos;
    final double[] nLat, nLng, nX, nY;
    // segmentos (A -> B en el orden de los vértices de la vía)
    final int nSeg;
    final int[] sA, sB, sVia, sTramo;
    final double[] sLen;
    final boolean[] sAdelante, sAtras;
    // vías aceptadas
    private final long[] viaId;
    private final String[] viaHighway, viaNombre;
    private final Sentido[] viaSentido;
    // aristas dirigidas (CSR) y no dirigidas (para diagnosticar contrasentidos)
    final int[] oStart, oTo;
    final double[] oLen;
    final int[] uStart, uTo;
    final double[] uLen;
    private final Map<Long, int[]> rejilla;
    private final int viasAceptadas, viasDescartadas;

    public RedVialBus(List<ViaOsm> vias) {
        List<ViaOsm> ok = new ArrayList<>();
        List<Sentido> sentidos = new ArrayList<>();
        int descartadas = 0, totalCoords = 0;
        double minLat = Double.MAX_VALUE, maxLat = -Double.MAX_VALUE, minLng = Double.MAX_VALUE, maxLng = -Double.MAX_VALUE;
        for (ViaOsm v : vias == null ? List.<ViaOsm>of() : vias) {
            var acceso = v == null ? null : ReglasBus.evaluar(v.tags());
            if (acceso == null || !acceso.transitable() || v.coordenadas() == null || v.coordenadas().size() < 2 || !coordenadasValidas(v)) {
                descartadas++;
                continue;
            }
            ok.add(v);
            sentidos.add(acceso.sentido());
            totalCoords += v.coordenadas().size();
            for (double[] c : v.coordenadas()) {
                minLat = Math.min(minLat, c[0]); maxLat = Math.max(maxLat, c[0]);
                minLng = Math.min(minLng, c[1]); maxLng = Math.max(maxLng, c[1]);
            }
        }
        viasAceptadas = ok.size();
        viasDescartadas = descartadas;
        lat0 = ok.isEmpty() ? 0 : (minLat + maxLat) / 2;
        lng0 = ok.isEmpty() ? 0 : (minLng + maxLng) / 2;
        mLng = M_POR_GRADO * Math.cos(Math.toRadians(lat0));

        double[] tLat = new double[totalCoords], tLng = new double[totalCoords];
        int[] a = new int[Math.max(0, totalCoords - ok.size())], b = new int[a.length], via = new int[a.length], tramo = new int[a.length];
        boolean[] fw = new boolean[a.length], bw = new boolean[a.length];
        viaId = new long[ok.size()];
        viaHighway = new String[ok.size()];
        viaNombre = new String[ok.size()];
        viaSentido = sentidos.toArray(new Sentido[0]);
        Map<Long, Integer> indice = new HashMap<>(Math.max(16, totalCoords * 2));
        int nn = 0, ns = 0;
        for (int vi = 0; vi < ok.size(); vi++) {
            ViaOsm v = ok.get(vi);
            viaId[vi] = v.id();
            viaHighway[vi] = v.tags().getOrDefault("highway", "").strip().toLowerCase();
            viaNombre[vi] = v.tags().getOrDefault("name", "");
            Sentido s = viaSentido[vi];
            int previo = -1;
            for (int i = 0; i < v.coordenadas().size(); i++) {
                double[] c = v.coordenadas().get(i);
                long la = Math.round(c[0] * 1e7), lo = Math.round(c[1] * 1e7);
                long clave = (la << 32) ^ (lo & 0xffffffffL);
                Integer id = indice.get(clave);
                if (id == null) {
                    id = nn++;
                    indice.put(clave, id);
                    tLat[id] = la / 1e7;
                    tLng[id] = lo / 1e7;
                }
                if (previo >= 0 && previo != id) {
                    a[ns] = previo; b[ns] = id; via[ns] = vi; tramo[ns] = i - 1;
                    fw[ns] = s != Sentido.SOLO_ATRAS;
                    bw[ns] = s != Sentido.SOLO_ADELANTE;
                    ns++;
                }
                previo = id;
            }
        }
        nNodos = nn;
        nLat = Arrays.copyOf(tLat, nn);
        nLng = Arrays.copyOf(tLng, nn);
        nX = new double[nn];
        nY = new double[nn];
        for (int i = 0; i < nn; i++) { nX[i] = (nLng[i] - lng0) * mLng; nY[i] = (nLat[i] - lat0) * M_POR_GRADO; }
        nSeg = ns;
        sA = Arrays.copyOf(a, ns); sB = Arrays.copyOf(b, ns); sVia = Arrays.copyOf(via, ns); sTramo = Arrays.copyOf(tramo, ns);
        sAdelante = Arrays.copyOf(fw, ns); sAtras = Arrays.copyOf(bw, ns);
        sLen = new double[ns];
        for (int i = 0; i < ns; i++) sLen[i] = Math.hypot(nX[sB[i]] - nX[sA[i]], nY[sB[i]] - nY[sA[i]]);

        // CSR dirigido y no dirigido
        int[] gradoO = new int[nn + 1], gradoU = new int[nn + 1];
        for (int i = 0; i < ns; i++) {
            if (sAdelante[i]) gradoO[sA[i] + 1]++;
            if (sAtras[i]) gradoO[sB[i] + 1]++;
            gradoU[sA[i] + 1]++; gradoU[sB[i] + 1]++;
        }
        for (int i = 0; i < nn; i++) { gradoO[i + 1] += gradoO[i]; gradoU[i + 1] += gradoU[i]; }
        oStart = gradoO; uStart = gradoU;
        oTo = new int[gradoO[nn]]; oLen = new double[oTo.length];
        uTo = new int[gradoU[nn]]; uLen = new double[uTo.length];
        int[] po = Arrays.copyOf(oStart, nn), pu = Arrays.copyOf(uStart, nn);
        for (int i = 0; i < ns; i++) {
            if (sAdelante[i]) { int k = po[sA[i]]++; oTo[k] = sB[i]; oLen[k] = sLen[i]; }
            if (sAtras[i]) { int k = po[sB[i]]++; oTo[k] = sA[i]; oLen[k] = sLen[i]; }
            int k1 = pu[sA[i]]++; uTo[k1] = sB[i]; uLen[k1] = sLen[i];
            int k2 = pu[sB[i]]++; uTo[k2] = sA[i]; uLen[k2] = sLen[i];
        }
        rejilla = construirRejilla();
    }

    private static boolean coordenadasValidas(ViaOsm v) {
        for (double[] c : v.coordenadas())
            if (c == null || c.length < 2 || !Double.isFinite(c[0]) || !Double.isFinite(c[1])
                    || Math.abs(c[0]) > 90 || Math.abs(c[1]) > 180) return false;
        return true;
    }

    // ------------------------------------------------------------------ API pública
    public int nodos() { return nNodos; }
    /** Aristas dirigidas: un tramo de doble sentido cuenta dos. */
    public int aristas() { return oTo.length; }
    public int viasAceptadas() { return viasAceptadas; }
    /** Vías recibidas que las reglas de bus rechazaron (peatonales, privadas, en construcción...) o que eran inválidas. */
    public int viasDescartadas() { return viasDescartadas; }

    /**
     * Segmentos más cercanos a un punto dentro de {@code maxMetros}, del más cercano al más lejano. Solo se devuelve el
     * segmento más cercano de cada vía.
     */
    public List<Candidato> candidatos(double lat, double lng, double maxMetros, int max) {
        List<Candidato> r = new ArrayList<>();
        if (max <= 0 || nSeg == 0) return r;
        Set<Integer> vias = new HashSet<>();
        for (Snap s : snaps(lat, lng, maxMetros)) {
            int v = sVia[s.seg()];
            if (!vias.add(v)) continue;
            r.add(new Candidato(viaId[v], sTramo[s.seg()], s.lat(), s.lng(), s.dist(), viaHighway[v], viaNombre[v], viaSentido[v]));
            if (r.size() >= max) break;
        }
        return r;
    }

    /** Distancia en metros a la vía transitable más cercana (vacío si no hay ninguna en 20 km). */
    public OptionalDouble distanciaMasCercana(double lat, double lng) {
        if (nSeg == 0) return OptionalDouble.empty();
        for (double r = 50; ; r *= 2) {
            double radio = Math.min(r, MAX_BUSQUEDA_M);
            List<Snap> s = snaps(lat, lng, radio);
            if (!s.isEmpty()) return OptionalDouble.of(s.get(0).dist());
            if (radio >= MAX_BUSQUEDA_M) return OptionalDouble.empty();
        }
    }

    // ------------------------------------------------------------------ uso interno
    double x(double lng) { return (lng - lng0) * mLng; }
    double y(double lat) { return (lat - lat0) * M_POR_GRADO; }
    long idVia(int seg) { return viaId[sVia[seg]]; }

    /** Todos los segmentos a menos de {@code max} metros, ordenados por distancia. Seguro entre hilos. */
    List<Snap> snaps(double lat, double lng, double max) {
        double px = x(lng), py = y(lat);
        int cx0 = (int) Math.floor((px - max) / CELDA_M), cx1 = (int) Math.floor((px + max) / CELDA_M);
        int cy0 = (int) Math.floor((py - max) / CELDA_M), cy1 = (int) Math.floor((py + max) / CELDA_M);
        long celdas = (long) (cx1 - cx0 + 1) * (cy1 - cy0 + 1);
        int[] ids;
        int n = 0;
        if (celdas > nSeg) {
            ids = new int[nSeg];
            for (int i = 0; i < nSeg; i++) ids[n++] = i;
        } else {
            int[] buf = new int[64];
            for (int cx = cx0; cx <= cx1; cx++)
                for (int cy = cy0; cy <= cy1; cy++) {
                    int[] c = rejilla.get(clave(cx, cy));
                    if (c == null) continue;
                    if (n + c.length > buf.length) buf = Arrays.copyOf(buf, Math.max(buf.length * 2, n + c.length));
                    System.arraycopy(c, 0, buf, n, c.length);
                    n += c.length;
                }
            ids = buf;
        }
        Arrays.sort(ids, 0, n);
        List<Snap> r = new ArrayList<>();
        int previo = -1;
        for (int k = 0; k < n; k++) {
            int s = ids[k];
            if (s == previo) continue;
            previo = s;
            double ax = nX[sA[s]], ay = nY[sA[s]], dx = nX[sB[s]] - ax, dy = nY[sB[s]] - ay;
            double l2 = dx * dx + dy * dy;
            double t = l2 == 0 ? 0 : Math.max(0, Math.min(1, ((px - ax) * dx + (py - ay) * dy) / l2));
            double qx = ax + t * dx, qy = ay + t * dy;
            double d = Math.hypot(px - qx, py - qy);
            if (d > max) continue;
            double la = nLat[sA[s]], lo = nLng[sA[s]];
            r.add(new Snap(s, t, la + t * (nLat[sB[s]] - la), lo + t * (nLng[sB[s]] - lo), qx, qy, d));
        }
        r.sort((p, q) -> Double.compare(p.dist(), q.dist()));
        return r;
    }

    private static long clave(int cx, int cy) { return ((long) cx << 32) ^ (cy & 0xffffffffL); }

    private Map<Long, int[]> construirRejilla() {
        Map<Long, int[]> m = new HashMap<>();
        Map<Long, int[]> tam = new HashMap<>(); // [0] = cantidad usada
        for (int s = 0; s < nSeg; s++) {
            double ax = nX[sA[s]], ay = nY[sA[s]], bx = nX[sB[s]], by = nY[sB[s]];
            int cx0 = (int) Math.floor(Math.min(ax, bx) / CELDA_M), cx1 = (int) Math.floor(Math.max(ax, bx) / CELDA_M);
            int cy0 = (int) Math.floor(Math.min(ay, by) / CELDA_M), cy1 = (int) Math.floor(Math.max(ay, by) / CELDA_M);
            for (int cx = cx0; cx <= cx1; cx++)
                for (int cy = cy0; cy <= cy1; cy++) {
                    long k = clave(cx, cy);
                    int[] lista = m.get(k);
                    int[] cnt = tam.get(k);
                    if (lista == null) { lista = new int[4]; m.put(k, lista); cnt = new int[1]; tam.put(k, cnt); }
                    if (cnt[0] == lista.length) { lista = Arrays.copyOf(lista, lista.length * 2); m.put(k, lista); }
                    lista[cnt[0]++] = s;
                }
        }
        for (var e : m.entrySet()) e.setValue(Arrays.copyOf(e.getValue(), tam.get(e.getKey())[0]));
        return m;
    }

    /** Distancia en metros entre dos coordenadas (haversine). */
    static double distanciaM(double lat1, double lng1, double lat2, double lng2) {
        double f1 = Math.toRadians(lat1), f2 = Math.toRadians(lat2);
        double df = f2 - f1, dl = Math.toRadians(lng2 - lng1);
        double h = Math.sin(df / 2) * Math.sin(df / 2) + Math.cos(f1) * Math.cos(f2) * Math.sin(dl / 2) * Math.sin(dl / 2);
        return 2 * 6_371_008.8 * Math.asin(Math.min(1, Math.sqrt(h)));
    }
}
