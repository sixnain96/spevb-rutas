package co.edu.usta.spevb.vial;

import co.edu.usta.spevb.vial.RedVialBus.Snap;

import java.util.Arrays;

/**
 * Camino más corto acotado (Dijkstra) entre dos posiciones sobre segmentos de una {@link RedVialBus}, respetando los
 * sentidos únicos. Reutiliza sus arreglos entre consultas: NO es seguro entre hilos (se crea uno por ajuste).
 */
final class Enrutador {
    /** Tipos de maniobra de una ruta. */
    static final byte NORMAL = 0, GIRO_U = 2;

    record Ruta(double metros, byte tipo, int[] nodos) {}

    private final RedVialBus r;
    private final double[] dist;
    private final int[] marca;
    private final int[] padre;
    private int sello;
    private double[] hk = new double[512];
    private int[] hn = new int[512];
    private int hs;

    Enrutador(RedVialBus red) {
        this.r = red;
        this.dist = new double[red.nNodos];
        this.marca = new int[red.nNodos];
        this.padre = new int[red.nNodos];
    }

    /**
     * Longitud de la ruta (m) desde {@code o} hasta cada destino; {@code NaN} si no hay camino de a lo sumo {@code corte} m.
     * {@code tipos[i]} queda en {@link #GIRO_U} si la ruta exige dar la vuelta (salir de un segmento por un extremo y
     * volver a entrar por el mismo extremo).
     */
    void rutas(Snap o, Snap[] destinos, double corte, boolean dirigido, double[] metros, byte[] tipos) {
        correr(o, corte, dirigido);
        for (int i = 0; i < destinos.length; i++) {
            long[] e = evaluar(o, destinos[i], dirigido, corte);
            metros[i] = Double.longBitsToDouble(e[0]);
            tipos[i] = (byte) e[1];
        }
    }

    /** Ruta con la secuencia de nodos intermedios (vacía si ambos están en el mismo segmento); {@code null} si no hay. */
    Ruta ruta(Snap o, Snap d, double corte, boolean dirigido) {
        correr(o, corte, dirigido);
        long[] e = evaluar(o, d, dirigido, corte);
        double m = Double.longBitsToDouble(e[0]);
        if (Double.isNaN(m)) return null;
        int entrada = (int) e[2];
        if (entrada < 0) return new Ruta(m, (byte) e[1], new int[0]);
        int n = 0;
        for (int v = entrada; v >= 0; v = padre[v]) n++;
        int[] nodos = new int[n];
        int k = n;
        for (int v = entrada; v >= 0; v = padre[v]) nodos[--k] = v;
        return new Ruta(m, (byte) e[1], nodos);
    }

    // ------------------------------------------------------------------ núcleo
    private void correr(Snap o, double corte, boolean dirigido) {
        sello++;
        hs = 0;
        int[] inicio = dirigido ? r.oStart : r.uStart, hacia = dirigido ? r.oTo : r.uTo;
        double[] largo = dirigido ? r.oLen : r.uLen;
        int s = o.seg();
        double L = r.sLen[s];
        if (!dirigido || r.sAdelante[s]) relajar(r.sB[s], (1 - o.t()) * L, -1);
        if (!dirigido || r.sAtras[s]) relajar(r.sA[s], o.t() * L, -1);
        while (hs > 0) {
            double k = hk[0];
            int n = hn[0];
            sacar();
            if (k > dist[n]) continue;
            if (k > corte) break;
            for (int e = inicio[n]; e < inicio[n + 1]; e++) {
                double nd = k + largo[e];
                if (nd <= corte) relajar(hacia[e], nd, n);
            }
        }
    }

    /** {bits de metros (NaN si nada), tipo, nodo de entrada o -1 si es directo}. */
    private long[] evaluar(Snap o, Snap d, boolean dirigido, double corte) {
        double mejor = Double.POSITIVE_INFINITY;
        byte tipo = NORMAL;
        int entrada = -1;
        int ds = d.seg();
        double Ld = r.sLen[ds];
        if (ds == o.seg()) {
            double L = Ld;
            boolean fw = !dirigido || r.sAdelante[ds], bw = !dirigido || r.sAtras[ds];
            if (fw && d.t() >= o.t()) mejor = (d.t() - o.t()) * L;
            else if (bw && d.t() <= o.t()) mejor = (o.t() - d.t()) * L;
        }
        if (!dirigido || r.sAdelante[ds]) {
            int n = r.sA[ds];
            if (marca[n] == sello) {
                double tot = dist[n] + d.t() * Ld;
                if (tot < mejor) { mejor = tot; entrada = n; tipo = (ds == o.seg() && padre[n] == -1) ? GIRO_U : NORMAL; }
            }
        }
        if (!dirigido || r.sAtras[ds]) {
            int n = r.sB[ds];
            if (marca[n] == sello) {
                double tot = dist[n] + (1 - d.t()) * Ld;
                if (tot < mejor) { mejor = tot; entrada = n; tipo = (ds == o.seg() && padre[n] == -1) ? GIRO_U : NORMAL; }
            }
        }
        if (mejor > corte) return new long[]{Double.doubleToRawLongBits(Double.NaN), 0, -1};
        return new long[]{Double.doubleToRawLongBits(mejor), tipo, entrada};
    }

    private void relajar(int nodo, double costo, int de) {
        if (marca[nodo] == sello && costo >= dist[nodo]) return;
        marca[nodo] = sello;
        dist[nodo] = costo;
        padre[nodo] = de;
        if (hs == hk.length) { hk = Arrays.copyOf(hk, hs * 2); hn = Arrays.copyOf(hn, hs * 2); }
        int i = hs++;
        while (i > 0) {
            int p = (i - 1) >> 1;
            if (hk[p] <= costo) break;
            hk[i] = hk[p]; hn[i] = hn[p];
            i = p;
        }
        hk[i] = costo; hn[i] = nodo;
    }

    private void sacar() {
        hs--;
        if (hs == 0) return;
        double k = hk[hs];
        int n = hn[hs];
        int i = 0;
        while (true) {
            int c = 2 * i + 1;
            if (c >= hs) break;
            if (c + 1 < hs && hk[c + 1] < hk[c]) c++;
            if (hk[c] >= k) break;
            hk[i] = hk[c]; hn[i] = hn[c];
            i = c;
        }
        hk[i] = k; hn[i] = n;
    }
}
