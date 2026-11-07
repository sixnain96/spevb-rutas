package co.edu.usta.spevb.vial;

import co.edu.usta.spevb.vial.RedVialBus.Snap;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Ajusta un recorrido de bus (puntos [lat,lng]) a la {@link RedVialBus} con un modelo oculto de Markov resuelto con
 * Viterbi. Nunca une puntos con línea recta "inventada" sobre zonas donde no hay vía: lo que no se puede ajustar se
 * CONSERVA tal como llegó y se marca.
 *
 * <h2>Modelo</h2>
 * <ul>
 *   <li>Estados de cada punto: hasta 8 posiciones candidatas sobre segmentos dentro de {@code radioMetros}, más un estado
 *       «sin coincidencia» (se descarta el punto como atípico a un costo fijo).</li>
 *   <li>Emisión: costo gaussiano por la distancia del punto a la vía ({@code sigmaMetros}).</li>
 *   <li>Transición: camino más corto DIRIGIDO por la red (respeta sentidos únicos y glorietas) acotado por
 *       {@code maxSaltoMetros}; el costo crece con la diferencia entre la longitud de la ruta y la distancia en línea
 *       recta entre los dos puntos, y los giros en U se penalizan. Por eso la continuidad de la ruta pesa más que la
 *       cercanía puntual: en calzadas paralelas se queda en la misma, y como un punto de una vía no conectada exige un
 *       salto sin camino, no se «salta» sobre separadores.</li>
 *   <li>Si no existe ningún camino legal hacia ningún candidato, se admite una ruptura (costo alto) y el tramo queda
 *       DUDOSO; la propuesta une ambos lados sin dibujar vías que no existen.</li>
 * </ul>
 * Los recorridos largos se procesan en fragmentos de 1.500 puntos con 100 puntos de solapamiento (decodificación de
 * rezago fijo): cada fragmento arranca desde el estado ya decidido del anterior, por lo que el resultado conserva orden
 * y continuidad.
 *
 * <h2>Anclas y extremos</h2>
 * Un punto ancla (índice en {@code anclas}) y, con {@code preservarExtremos}, el primero y el último NO se mueven: la
 * propuesta contiene exactamente su coordenada original (el ajuste se fuerza a su candidato más cercano para que el resto
 * de la ruta pase por ahí, pero el vértice emitido es el original). Si no hay vía a menos del radio, también se conserva.
 *
 * <p>No modifica la entrada. Una instancia es segura entre hilos (cada llamada crea su propio estado).
 */
public final class MapMatchingBus {
    public enum Estado { OK, DUDOSO, SIN_COINCIDENCIA }

    /**
     * @param radioMetros      distancia máxima de un punto a una vía para considerarla (por defecto 30)
     * @param maxSaltoMetros   mayor longitud de ruta admitida entre dos puntos consecutivos (por defecto 500)
     * @param sigmaMetros      ruido esperado del GPS (por defecto 10)
     * @param preservarExtremos si es true el primer y el último punto se conservan sin mover
     */
    public record Opciones(double radioMetros, double maxSaltoMetros, double sigmaMetros, boolean preservarExtremos) {
        public Opciones {
            if (!(radioMetros > 0)) radioMetros = 30;
            if (!(maxSaltoMetros > 0)) maxSaltoMetros = 500;
            if (!(sigmaMetros > 0)) sigmaMetros = 10;
        }
        public static Opciones porDefecto() { return new Opciones(30, 500, 10, true); }
    }

    /**
     * Rango [desde, hasta] (ambos incluidos) de índices del recorrido ORIGINAL con el mismo estado, y rango
     * [propDesde, propHasta] (ambos incluidos) de {@code propuesta} que lo reemplaza. Tramos consecutivos comparten el
     * vértice de unión (propHasta de uno = propDesde del siguiente; el primero empieza en 0 y el último termina en el
     * último vértice). Aceptar un tramo = sustituir los puntos originales [desde..hasta] por propuesta[propDesde..propHasta].
     * En SIN_COINCIDENCIA ese rango contiene los puntos originales tal cual.
     */
    public record Tramo(int desde, int hasta, int propDesde, int propHasta, Estado estado, double desviacionMediaM,
                        double desviacionMaxM, String motivo) {}

    /**
     * {@code diferenciaPct}: |propuesta − original| / original × 100. {@code desviacionMediaM/MaxM}: cuánto se movió cada
     * punto ajustado (los conservados cuentan 0). {@code pctAjustado}: % de puntos con coincidencia en la red.
     * {@code giroIlegales}: maniobras que la propuesta no puede garantizar (giros en U y avances contra un sentido único);
     * el modelo nunca recorre un sentido único al revés, esos casos quedan conservados o marcados.
     */
    public record Metricas(int puntosOriginales, int puntosPropuestos, double longitudOriginalM, double longitudPropuestaM,
                           double diferenciaPct, double desviacionMediaM, double desviacionMaxM, double pctAjustado,
                           int tramosDudosos, int puntosSinCoincidencia, int cambiosDeViaAmbiguos, int giroIlegales) {}

    public record Resultado(List<double[]> propuesta, List<Tramo> tramos, Metricas metricas,
                            boolean requiereRevisionManual, List<String> advertencias,
                            List<Integer> problemas) {}

    private static final int MAX_CANDIDATOS = 8;
    private static final int FRAGMENTO = 1500;
    private static final int SOLAPE = 100;
    private static final double INF = Double.POSITIVE_INFINITY;
    private static final double COSTO_DESCARTE_EXTRA = 2.0;
    private static final double COSTO_RUPTURA = 60;
    private static final double COSTO_GIRO_U = 30;
    private static final double HAZ = 30;
    private static final double MARGEN_AMBIGUO = 2.0;
    private static final double LIMITE_CAMBIO_LONGITUD_PCT = 15;

    private final RedVialBus red;

    public MapMatchingBus(RedVialBus red) { this.red = java.util.Objects.requireNonNull(red); }

    public Resultado ajustar(List<double[]> puntosLatLng, Set<Integer> anclas, Opciones op) {
        List<double[]> pts = puntosLatLng == null ? List.of() : puntosLatLng;
        for (int i = 0; i < pts.size(); i++) {
            double[] p = pts.get(i);
            if (p == null || p.length < 2 || !Double.isFinite(p[0]) || !Double.isFinite(p[1]))
                throw new IllegalArgumentException("El punto " + i + " no tiene coordenadas [lat,lng] válidas");
        }
        return new Ejecucion(pts, anclas == null ? Set.of() : anclas, op == null ? Opciones.porDefecto() : op).ejecutar();
    }

    // =====================================================================================================
    private static final class Capa {
        final double[] sc;
        final int[] bp;
        final boolean[] rup;
        int lrCapa = -1, lrIdx = -1; // último real de la rama de «descarte» (solo para el estado de descarte)
        Capa(int m) {
            sc = new double[m + 1];
            Arrays.fill(sc, INF);
            bp = new int[m + 1];
            Arrays.fill(bp, -1);
            rup = new boolean[m + 1];
        }
    }

    private final class Ejecucion {
        final int n;
        final double[] lat, lng, x, y;
        final Opciones op;
        final boolean[] fija;
        final Snap[][] cand;
        final int[] decidido;
        final boolean[] ruptura, ambiguo;
        final Enrutador en = new Enrutador(red);
        final double beta, costoDescarte;
        final double[] lenBuf = new double[MAX_CANDIDATOS + 1];
        final byte[] tipoBuf = new byte[MAX_CANDIDATOS + 1];

        Ejecucion(List<double[]> pts, Set<Integer> anclas, Opciones op) {
            this.op = op;
            n = pts.size();
            lat = new double[n]; lng = new double[n]; x = new double[n]; y = new double[n];
            fija = new boolean[n];
            cand = new Snap[n][];
            decidido = new int[n];
            ruptura = new boolean[n];
            ambiguo = new boolean[n];
            beta = op.sigmaMetros();
            double r = op.radioMetros() / op.sigmaMetros();
            costoDescarte = 0.5 * r * r + COSTO_DESCARTE_EXTRA;
            for (int i = 0; i < n; i++) {
                lat[i] = pts.get(i)[0];
                lng[i] = pts.get(i)[1];
                x[i] = red.x(lng[i]);
                y[i] = red.y(lat[i]);
                fija[i] = anclas.contains(i) || (op.preservarExtremos() && (i == 0 || i == n - 1));
            }
        }

        Resultado ejecutar() {
            if (n == 0) return vacio();
            for (int i = 0; i < n; i++) cand[i] = candidatos(i);
            for (int lo = 0; lo < n; ) {
                int hiFijo = Math.min(n, lo + FRAGMENTO);
                ventana(lo, hiFijo, Math.min(n, hiFijo + SOLAPE));
                lo = hiFijo;
            }
            return armar();
        }

        Resultado vacio() {
            return new Resultado(List.of(), List.of(), new Metricas(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0), false, List.of(), List.of());
        }

        // ------------------------------------------------------------------ candidatos
        Snap[] candidatos(int i) {
            List<Snap> todos = red.snaps(lat[i], lng[i], op.radioMetros());
            List<Snap> k = new ArrayList<>();
            for (Snap s : todos) {
                boolean repetido = false;
                for (Snap q : k)
                    if (red.idVia(q.seg()) == red.idVia(s.seg()) && Math.hypot(q.x() - s.x(), q.y() - s.y()) < 1.0) { repetido = true; break; }
                if (repetido) continue;
                k.add(s);
                if (k.size() >= MAX_CANDIDATOS || (fija[i])) break; // un punto fijo solo usa su candidato más cercano
            }
            return k.toArray(new Snap[0]);
        }

        double emision(Snap s) {
            double z = s.dist() / op.sigmaMetros();
            return 0.5 * z * z;
        }

        double corte(double d) { return Math.min(op.maxSaltoMetros(), 4 * d + 60); }

        double distPuntos(int a, int b) { return Math.hypot(x[a] - x[b], y[a] - y[b]); }

        // ------------------------------------------------------------------ Viterbi por ventana
        Capa congelada(int p) {
            int m = cand[p].length;
            Capa c = new Capa(m);
            c.sc[decidido[p]] = 0;
            if (decidido[p] == m) {
                for (int q = p - 1; q >= 0; q--)
                    if (decidido[q] < cand[q].length) { c.lrCapa = q; c.lrIdx = decidido[q]; break; }
            }
            return c;
        }

        void ventana(int lo, int hiFijo, int hi) {
            Capa prev = lo == 0 ? null : congelada(lo - 1);
            Capa[] capas = new Capa[hi - lo];
            for (int j = lo; j < hi; j++) {
                capas[j - lo] = construir(j, prev);
                prev = capas[j - lo];
            }
            Capa ult = capas[hi - lo - 1];
            int s = 0;
            for (int k = 1; k < ult.sc.length; k++) if (ult.sc[k] < ult.sc[s]) s = k;
            for (int j = hi - 1; j >= lo; j--) {
                Capa c = capas[j - lo];
                int m = cand[j].length;
                if (j < hiFijo) {
                    decidido[j] = s;
                    ruptura[j] = s < m && c.rup[s];
                    if (s < m && j > 0) {
                        int pi = c.bp[s];
                        if (pi >= 0 && pi < cand[j - 1].length
                                && red.idVia(cand[j - 1][pi].seg()) != red.idVia(cand[j][s].seg())) {
                            double alt = INF;
                            long via = red.idVia(cand[j][s].seg());
                            for (int k = 0; k < m; k++) if (k != s && red.idVia(cand[j][k].seg()) != via) alt = Math.min(alt, c.sc[k]);
                            if (alt - c.sc[s] < MARGEN_AMBIGUO) ambiguo[j] = true;
                        }
                    }
                }
                s = c.bp[s];
                if (s < 0) break;
            }
        }

        Capa construir(int j, Capa prev) {
            Snap[] cj = cand[j];
            int m = cj.length;
            Capa c = new Capa(m);
            double[] em = new double[m];
            for (int b = 0; b < m; b++) em[b] = emision(cj[b]);
            boolean puedeDescartar = m == 0 || !fija[j];
            if (prev == null) {
                for (int b = 0; b < m; b++) c.sc[b] = em[b];
                if (puedeDescartar) c.sc[m] = costoDescarte;
                return c;
            }
            Snap[] cp = cand[j - 1];
            int pm = cp.length;
            double mejorPrev = INF;
            int mejorIdx = -1;
            for (int a = 0; a <= pm; a++) if (prev.sc[a] < mejorPrev) { mejorPrev = prev.sc[a]; mejorIdx = a; }
            // estado de descarte
            if (puedeDescartar) {
                c.sc[m] = mejorPrev + costoDescarte;
                c.bp[m] = mejorIdx;
                if (mejorIdx < pm) { c.lrCapa = j - 1; c.lrIdx = mejorIdx; }
                else { c.lrCapa = prev.lrCapa; c.lrIdx = prev.lrIdx; }
            }
            if (m == 0) return c;
            // ruptura: el camino queda sin conexión (alternativa cara)
            for (int b = 0; b < m; b++) {
                c.sc[b] = mejorPrev + COSTO_RUPTURA + em[b];
                c.bp[b] = mejorIdx;
                c.rup[b] = true;
            }
            double d = distPuntos(j - 1, j);
            for (int a = 0; a < pm; a++) {
                if (prev.sc[a] > mejorPrev + HAZ) continue;
                en.rutas(cp[a], cj, corte(d), true, lenBuf, tipoBuf);
                actualizar(c, prev.sc[a], a, d, em, false);
            }
            if (prev.sc[pm] <= mejorPrev + HAZ) {
                if (prev.lrCapa < 0) {
                    for (int b = 0; b < m; b++) {
                        double v = prev.sc[pm] + em[b];
                        if (v < c.sc[b]) { c.sc[b] = v; c.bp[b] = pm; c.rup[b] = false; }
                    }
                } else {
                    double d2 = distPuntos(prev.lrCapa, j);
                    en.rutas(cand[prev.lrCapa][prev.lrIdx], cj, corte(d2), true, lenBuf, tipoBuf);
                    actualizar(c, prev.sc[pm], pm, d2, em, true);
                }
            }
            return c;
        }

        void actualizar(Capa c, double base, int desde, double d, double[] em, boolean esDescarte) {
            for (int b = 0; b < em.length; b++) {
                double len = lenBuf[b];
                if (Double.isNaN(len)) continue;
                double v = base + Math.abs(len - d) / beta + (tipoBuf[b] == Enrutador.GIRO_U ? COSTO_GIRO_U : 0) + em[b];
                if (v < c.sc[b]) { c.sc[b] = v; c.bp[b] = desde; c.rup[b] = false; }
            }
        }

        // ------------------------------------------------------------------ armado del resultado
        /** Polilínea con coordenadas [lat,lng]; los vértices protegidos (originales conservados) nunca se eliminan. */
        final class Poli {
            double[] la = new double[256], lo = new double[256];
            boolean[] pr = new boolean[256];
            int sz;
            final int[] mk = new int[n];
            int ultimo = -1;
            final double maxPliegue = 2 * op.sigmaMetros();

            void add(double a, double b, boolean prot) {
                if (sz > 0 && Math.abs(la[sz - 1] - a) < 1e-9 && Math.abs(lo[sz - 1] - b) < 1e-9) { pr[sz - 1] |= prot; return; }
                while (sz >= 2 && !pr[sz - 1] && pliegue(a, b)) sz--;
                if (sz > 0 && Math.abs(la[sz - 1] - a) < 1e-9 && Math.abs(lo[sz - 1] - b) < 1e-9) {
                    pr[sz - 1] |= prot;
                    for (int q = ultimo; q >= 0 && mk[q] >= sz; q--) mk[q] = sz - 1;
                    return;
                }
                for (int q = ultimo; q >= 0 && mk[q] >= sz; q--) mk[q] = sz; // vértices retirados: apuntan al que los reemplaza
                if (sz == la.length) { la = Arrays.copyOf(la, sz * 2); lo = Arrays.copyOf(lo, sz * 2); pr = Arrays.copyOf(pr, sz * 2); }
                la[sz] = a; lo[sz] = b; pr[sz] = prot;
                sz++;
            }

            /** ¿Llegar a (a,b) deshace en menos de 2σ el último avance (retroceso sobre la misma vía)? */
            boolean pliegue(double a, double b) {
                double xx = red.x(lo[sz - 2]), xy = red.y(la[sz - 2]), yx = red.x(lo[sz - 1]), yy = red.y(la[sz - 1]);
                double qx = red.x(b), qy = red.y(a);
                double ux = yx - xx, uy = yy - xy, vx = qx - yx, vy = qy - yy;
                double lv = Math.hypot(vx, vy), lu = Math.hypot(ux, uy);
                if (lu == 0 || lv > maxPliegue || ux * vx + uy * vy >= 0) return false;
                return Math.abs(ux * vy - uy * vx) / lu <= 1.0;
            }

            /** Registra que el punto original i quedó representado por el último vértice. */
            void marca(int i) { mk[i] = sz - 1; ultimo = i; }

            List<double[]> lista() {
                List<double[]> r = new ArrayList<>(sz);
                for (int i = 0; i < sz; i++) r.add(new double[]{la[i], lo[i]});
                return r;
            }
        }

        Resultado armar() {
            Estado[] estado = new Estado[n];
            String[] motivo = new String[n];
            double[] mov = new double[n];
            double[] snapDist = new double[n];
            int ilegales = 0, ambiguos = 0;
            Poli poli = new Poli();
            List<Integer> pend = new ArrayList<>();
            int prevReal = -1;
            boolean enRachaContra = false;

            for (int i = 0; i < n; i++) {
                int m = cand[i].length;
                if (decidido[i] == m) {
                    estado[i] = Estado.SIN_COINCIDENCIA;
                    pend.add(i);
                    if (m == 0) {
                        motivo[i] = "a más de " + fmt(op.radioMetros()) + " m de cualquier vía transitable; se conserva el punto original";
                    } else if (contraSentido(i)) {
                        motivo[i] = "solo se llega contra un sentido único; se conserva el punto original";
                        if (!enRachaContra) ilegales++;
                        enRachaContra = true;
                    } else {
                        motivo[i] = "punto atípico: no hay camino razonable desde los puntos vecinos; se conserva el punto original";
                    }
                    continue;
                }
                enRachaContra = false;
                Snap c = cand[i][decidido[i]];
                snapDist[i] = c.dist();
                estado[i] = Estado.OK;
                double ex = fija[i] ? lat[i] : c.lat(), ey = fija[i] ? lng[i] : c.lng();
                mov[i] = fija[i] ? 0 : c.dist();
                if (c.dist() > 0.7 * op.radioMetros()) { estado[i] = Estado.DUDOSO; motivo[i] = "punto a " + fmt(c.dist()) + " m de la vía"; }
                if (ambiguo[i]) { ambiguos++; if (estado[i] == Estado.OK) { estado[i] = Estado.DUDOSO; motivo[i] = "cambio de vía ambiguo"; } }

                if (!pend.isEmpty()) {
                    for (int q : pend) { poli.add(lat[q], lng[q], true); poli.marca(q); }
                    pend.clear();
                    poli.add(ex, ey, fija[i]);
                    poli.marca(i);
                } else if (prevReal >= 0) {
                    Snap pc = cand[prevReal][decidido[prevReal]];
                    double d = distPuntos(prevReal, i);
                    Enrutador.Ruta ru = ruptura[i] ? null : en.ruta(pc, c, corte(d), true);
                    if (ru == null) {
                        // sin camino legal: no se dibuja nada entre ambos lados
                        boolean contra = en.ruta(pc, c, corte(d), false) != null;
                        if (contra) ilegales++;
                        estado[i] = Estado.DUDOSO;
                        motivo[i] = contra ? "avance contra el sentido único de la vía" : "sin camino por la red desde el punto anterior (vías no conectadas)";
                        poli.add(ex, ey, fija[i]);
                        poli.marca(i);
                    } else {
                        for (int nodo : ru.nodos()) poli.add(red.nLat[nodo], red.nLng[nodo], false);
                        poli.add(ex, ey, fija[i]);
                        poli.marca(i);
                        if (ru.tipo() == Enrutador.GIRO_U) {
                            ilegales++;
                            estado[i] = Estado.DUDOSO;
                            motivo[i] = "la ruta exige un giro en U o cambio de sentido";
                        } else if (ru.metros() > 3 * d + 30 && estado[i] == Estado.OK) {
                            estado[i] = Estado.DUDOSO;
                            motivo[i] = "desvío de " + fmt(ru.metros()) + " m por la red para " + fmt(d) + " m en línea recta";
                        }
                    }
                } else {
                    poli.add(ex, ey, fija[i]);
                    poli.marca(i);
                }
                prevReal = i;
            }
            for (int q : pend) { poli.add(lat[q], lng[q], true); poli.marca(q); }

            List<double[]> propuesta = poli.lista();
            List<Tramo> tramos = new ArrayList<>();
            List<String> advertencias = new ArrayList<>();
            List<Integer> problemas = new ArrayList<>();
            int dudosos = 0, sin = 0, matched = 0;
            double suma = 0, max = 0;
            for (int i = 0; i < n; i++) {
                if (estado[i] != Estado.SIN_COINCIDENCIA) { matched++; suma += mov[i]; max = Math.max(max, mov[i]); } else sin++;
            }
            for (int i = 0; i < n; ) {
                int j = i;
                while (j + 1 < n && estado[j + 1] == estado[i]) j++;
                double s = 0, mx = 0;
                Set<String> motivos = new LinkedHashSet<>();
                for (int k = i; k <= j; k++) {
                    if (estado[k] != Estado.SIN_COINCIDENCIA) { s += mov[k]; mx = Math.max(mx, mov[k]); }
                    if (motivo[k] != null && motivos.size() < 3) motivos.add(motivo[k]);
                }
                String mot = estado[i] == Estado.OK ? "ajustado a la red vial"
                        : String.join("; ", motivos);
                int pd = tramos.isEmpty() ? 0 : tramos.get(tramos.size() - 1).propHasta();
                int ph = j == n - 1 ? propuesta.size() - 1 : Math.max(pd, poli.mk[j]);
                tramos.add(new Tramo(i, j, pd, ph, estado[i], s / (j - i + 1), mx, mot));
                if (estado[i] != Estado.OK) problemas.add(tramos.size() - 1);
                if (estado[i] != Estado.OK) {
                    if (estado[i] == Estado.DUDOSO) dudosos++;
                    advertencias.add((estado[i] == Estado.DUDOSO ? "Tramo dudoso" : "Sin coincidencia") + " en los puntos "
                            + i + (j > i ? "-" + j : "") + ": " + mot);
                }
                i = j + 1;
            }
            double lo = longitud(lat, lng), lp = 0;
            for (int i = 1; i < propuesta.size(); i++)
                lp += RedVialBus.distanciaM(propuesta.get(i - 1)[0], propuesta.get(i - 1)[1], propuesta.get(i)[0], propuesta.get(i)[1]);
            double dif = lo > 0 ? Math.abs(lp - lo) / lo * 100 : 0;
            boolean revisar = dif > LIMITE_CAMBIO_LONGITUD_PCT || sin > 0 || ilegales > 0;
            if (dif > LIMITE_CAMBIO_LONGITUD_PCT)
                advertencias.add("La longitud cambió " + fmt(dif) + " % respecto al recorrido original");
            if (ilegales > 0) advertencias.add("Se detectaron " + ilegales + " maniobra(s) ilegal(es) o contra sentido que requieren revisión");
            Metricas met = new Metricas(n, propuesta.size(), lo, lp, dif, matched == 0 ? 0 : suma / matched, max,
                    100.0 * (n - sin) / n, dudosos, sin, ambiguos, ilegales);
            return new Resultado(propuesta, tramos, met, revisar, advertencias, problemas);
        }

        /** ¿El punto descartado es inalcanzable con los vecinos solo por un sentido único (pero sí por la red sin dirección)? */
        boolean contraSentido(int i) {
            boolean algunaNoDirigida = false;
            for (int q : new int[]{i - 1, i + 1}) {
                if (q < 0 || q >= n || cand[q].length == 0) continue;
                double corte = corte(distPuntos(i, q));
                for (int a = 0; a < Math.min(3, cand[q].length); a++)
                    for (int b = 0; b < Math.min(3, cand[i].length); b++) {
                        Snap desde = q < i ? cand[q][a] : cand[i][b], hasta = q < i ? cand[i][b] : cand[q][a];
                        if (en.ruta(desde, hasta, corte, true) != null) return false;
                        if (en.ruta(desde, hasta, corte, false) != null) algunaNoDirigida = true;
                    }
            }
            return algunaNoDirigida;
        }
    }

    private static double longitud(double[] lat, double[] lng) {
        double l = 0;
        for (int i = 1; i < lat.length; i++) l += RedVialBus.distanciaM(lat[i - 1], lng[i - 1], lat[i], lng[i]);
        return l;
    }

    private static String fmt(double v) { return String.format(java.util.Locale.ROOT, "%.1f", v); }
}
