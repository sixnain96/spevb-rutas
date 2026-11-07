package co.edu.usta.spevb.vial;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Redes y recorridos sintéticos en metros locales (x = este, y = norte) alrededor de un origen en Villavicencio. */
final class Sinteticas {
    static final double LAT0 = 4.15, LNG0 = -73.63;
    static final double M_LAT = 6_371_008.8 * Math.PI / 180.0;
    static final double M_LNG = M_LAT * Math.cos(Math.toRadians(LAT0));

    private Sinteticas() {}

    static double[] p(double x, double y) { return new double[]{LAT0 + y / M_LAT, LNG0 + x / M_LNG}; }
    static double xDe(double[] latLng) { return (latLng[1] - LNG0) * M_LNG; }
    static double yDe(double[] latLng) { return (latLng[0] - LAT0) * M_LAT; }

    static Map<String, String> tags(String... kv) {
        Map<String, String> m = new HashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) m.put(kv[i], kv[i + 1]);
        return m;
    }

    /** Vía con vértices dados en metros {x,y}. */
    static ViaOsm via(long id, Map<String, String> tags, double[]... xy) {
        List<double[]> c = new ArrayList<>();
        for (double[] v : xy) c.add(p(v[0], v[1]));
        return new ViaOsm(id, tags, c);
    }

    /** Vía recta con un vértice cada {@code paso} metros. */
    static ViaOsm recta(long id, double x0, double y0, double x1, double y1, double paso, String... kv) {
        double len = Math.hypot(x1 - x0, y1 - y0);
        int n = Math.max(1, (int) Math.round(len / paso));
        List<double[]> c = new ArrayList<>();
        for (int i = 0; i <= n; i++) c.add(p(x0 + (x1 - x0) * i / n, y0 + (y1 - y0) * i / n));
        return new ViaOsm(id, tags(kv), c);
    }

    /** Puntos a lo largo de la poligonal {@code xy} cada {@code paso} metros. */
    static List<double[]> recorrer(double paso, double[]... xy) {
        List<double[]> r = new ArrayList<>();
        double resto = 0;
        r.add(p(xy[0][0], xy[0][1]));
        for (int i = 1; i < xy.length; i++) {
            double dx = xy[i][0] - xy[i - 1][0], dy = xy[i][1] - xy[i - 1][1], l = Math.hypot(dx, dy);
            double pos = paso - resto;
            while (pos <= l + 1e-9) {
                r.add(p(xy[i - 1][0] + dx * pos / l, xy[i - 1][1] + dy * pos / l));
                pos += paso;
            }
            resto = l - (pos - paso);
        }
        return r;
    }

    /** Suma un desplazamiento en y (norte), cíclico, a cada punto. */
    static List<double[]> conRuido(List<double[]> pts, double[] ruidoY) {
        List<double[]> r = new ArrayList<>();
        for (int i = 0; i < pts.size(); i++) r.add(p(xDe(pts.get(i)), yDe(pts.get(i)) + ruidoY[i % ruidoY.length]));
        return r;
    }

    static List<double[]> copiaProfunda(List<double[]> l) {
        List<double[]> r = new ArrayList<>();
        for (double[] a : l) r.add(a.clone());
        return r;
    }

    static boolean contiene(List<double[]> l, double[] p) {
        for (double[] a : l) if (a[0] == p[0] && a[1] == p[1]) return true;
        return false;
    }
}
