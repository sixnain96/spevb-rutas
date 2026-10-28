package co.edu.usta.spevb.service;

import co.edu.usta.spevb.repository.ViajeRepository;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Evalúa varios puntos de subida y de bajada por ruta usando la distancia a pie real por la red peatonal
 * (una sola consulta por cálculo). Si el servicio peatonal no responde, usa la distancia en línea recta.
 */
@Service
public class AbordajeService {
    /** Penalización de las celdas sin camino peatonal: se prefieren siempre los puntos accesibles. */
    private static final double FACTOR_SIN_CAMINO = 2.5;

    /** {@code parada} es el nombre del paradero registrado si el candidato es uno (si no, null). */
    public record Candidato(long rutaId, String sentido, double largoMetros, double fraccion,
                            double lng, double lat, double rectaMetros, double caminataMetros, String parada) {
        /** Ventaja (m) al elegir: se prefiere un paradero real si no obliga a caminar mucho más. */
        public double ventaja() { return parada == null ? 0 : PARADERO_VENTAJA_METROS; }
    }

    public static final double PARADERO_VENTAJA_METROS = 25;
    private static final double PASO_MUESTRAS_METROS = 15;
    private static final double PESO_BUS = 0.02;
    /** Cuánto más puede caminarse con el sentido correcto antes de preferir (y avisar de) la opción en sentido contrario. */
    private static final double TOLERANCIA_SENTIDO_METROS = 600;

    public record Evaluacion(List<Candidato> subidas, List<Candidato> bajadas, boolean porCalles) {
        public static final Evaluacion VACIA = new Evaluacion(List.of(), List.of(), false);
    }

    private final ViajeRepository repository;
    private final CaminataService caminata;

    public AbordajeService(ViajeRepository repository, CaminataService caminata) {
        this.repository = repository;
        this.caminata = caminata;
    }

    /** Red de calles propia: permite elegir el punto óptimo entre todas las muestras de cada ruta. */
    private volatile RedPeatonalService red;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void usarRed(RedPeatonalService red) { this.red = red; }

    public record Optimo(Candidato subida, Candidato bajada, boolean permitido, double costo) {}

    /** Para cada ruta: el par que menos hace caminar y el que menos tarda en total (caminando a 4,8 km/h y en bus a 16 km/h). */
    public record Optimos(Optimo menosCaminata, Optimo masRapida) {}

    /** Metros de caminata que equivalen a un metro de bus en tiempo (80 m/min a pie contra 267 m/min en bus). */
    private static final double METROS_A_PIE_POR_METRO_BUS = 80.0 / 267.0;

    /**
     * Subida y bajada óptimas por ruta: se muestrea cada ruta cada 15 m, se mide la caminata real por la red de calles
     * desde A y hasta B para cada muestra (dos exploraciones en total) y se elige el par que menos hace caminar,
     * respetando el sentido de la ruta (con un descuento si el punto es un paradero registrado y un peso pequeño al
     * trayecto en bus). Devuelve null si la red no está lista o A no tiene calle cerca: se usa el método aproximado.
     */
    public Map<Long, Optimos> optimizar(double[] origen, double[] destino, List<Long> rutaIds, double radioA, double radioB) {
        RedPeatonalService r = red;
        if (r == null || !r.lista() || rutaIds.isEmpty()) return null;
        var filas = repository.muestras(rutaIds, PASO_MUESTRAS_METROS, origen[0], origen[1], radioA, destino[0], destino[1], radioB);
        if (filas.isEmpty()) return null;
        List<double[]> puntos = new ArrayList<>(filas.size());
        filas.forEach(c -> puntos.add(new double[]{c.lng(), c.lat()}));
        var matriz = r.distancias(List.of(new double[]{origen[1], origen[0]}, new double[]{destino[1], destino[0]}), puntos);
        if (matriz.isEmpty()) return null;
        double[] wA = matriz.get()[0], wB = matriz.get()[1];

        Map<Long, List<Integer>> porRuta = new java.util.LinkedHashMap<>();
        for (int i = 0; i < filas.size(); i++) porRuta.computeIfAbsent(filas.get(i).rutaId(), k -> new ArrayList<>()).add(i);
        Map<Long, Optimos> resultado = new java.util.HashMap<>();
        for (var e : porRuta.entrySet()) {
            var o = mejorDeRuta(filas, e.getValue(), wA, wB);
            if (o != null) resultado.put(e.getKey(), o);
        }
        return resultado;
    }

    /**
     * Solo la subida: el punto de cada ruta al que se llega caminando con menos esfuerzo desde {@code origen}
     * (para «rutas que pasan cerca del origen»). Null si la red de calles no está lista.
     */
    public Map<Long, Candidato> mejoresSubidas(double[] origen, List<Long> rutaIds, double radio) {
        RedPeatonalService r = red;
        if (r == null || !r.lista() || rutaIds.isEmpty()) return null;
        var filas = repository.muestras(rutaIds, PASO_MUESTRAS_METROS, origen[0], origen[1], radio, origen[0], origen[1], 1);
        if (filas.isEmpty()) return null;
        List<double[]> puntos = new ArrayList<>(filas.size());
        filas.forEach(c -> puntos.add(new double[]{c.lng(), c.lat()}));
        var matriz = r.distancias(List.of(new double[]{origen[1], origen[0]}), puntos);
        if (matriz.isEmpty()) return null;
        double[] w = matriz.get()[0];
        Map<Long, Integer> mejor = new java.util.HashMap<>();
        for (int i = 0; i < filas.size(); i++) {
            if (Double.isNaN(w[i])) continue;
            Integer m = mejor.get(filas.get(i).rutaId());
            if (m == null || w[i] - ventaja(filas.get(i)) < w[m] - ventaja(filas.get(m))) mejor.put(filas.get(i).rutaId(), i);
        }
        Map<Long, Candidato> resultado = new java.util.HashMap<>();
        mejor.forEach((id, i) -> resultado.put(id, candidato(filas.get(i), w[i])));
        return resultado;
    }

    private static double ventaja(ViajeRepository.CandidatoRow c) { return c.parada() == null ? 0 : PARADERO_VENTAJA_METROS; }

    private Optimos mejorDeRuta(List<ViajeRepository.CandidatoRow> filas, List<Integer> idx, double[] wA, double[] wB) {
        List<Integer> sube = idx.stream().filter(i -> !Double.isNaN(wA[i])).toList();
        List<Integer> baja = idx.stream().filter(i -> !Double.isNaN(wB[i])).toList();
        if (sube.isEmpty() || baja.isEmpty()) return null;
        double largo = filas.get(idx.get(0)).largoMetros();
        boolean circular = "CIRCULAR".equals(filas.get(idx.get(0)).sentido());
        // [0] menos caminata, [1] más rápida; cada una con su mejor opción en el sentido correcto y en el contrario.
        Optimo[] directo = new Optimo[2], inverso = new Optimo[2];
        for (int i : sube) {
            var a = filas.get(i);
            for (int j : baja) {
                var b = filas.get(j);
                if (Math.abs(b.fraccion() - a.fraccion()) < 1e-6) continue;
                boolean adelante = b.fraccion() > a.fraccion();
                boolean permitido = adelante || circular;
                double bus = adelante ? (b.fraccion() - a.fraccion()) * largo
                        : circular ? (1 - a.fraccion() + b.fraccion()) * largo : (a.fraccion() - b.fraccion()) * largo;
                double caminata = wA[i] - ventaja(a) + wB[j] - ventaja(b);
                double[] costo = {caminata + PESO_BUS * bus, caminata + METROS_A_PIE_POR_METRO_BUS * bus};
                for (int k = 0; k < 2; k++) {
                    var destino = permitido ? directo : inverso;
                    if (destino[k] == null || costo[k] < destino[k].costo())
                        destino[k] = new Optimo(candidato(a, wA[i]), candidato(b, wB[j]), permitido, costo[k]);
                }
            }
        }
        Optimo menos = elegirSentido(directo[0], inverso[0]), rapida = elegirSentido(directo[1], inverso[1]);
        return menos == null ? null : new Optimos(menos, rapida);
    }

    /** El sentido correcto gana, salvo que obligue a caminar muchísimo más: entonces se muestra el inverso con su advertencia. */
    private static Optimo elegirSentido(Optimo directo, Optimo inverso) {
        if (directo != null && (inverso == null || directo.costo() <= inverso.costo() + TOLERANCIA_SENTIDO_METROS)) return directo;
        return inverso != null ? inverso : directo;
    }

    private static Candidato candidato(ViajeRepository.CandidatoRow c, double caminata) {
        return new Candidato(c.rutaId(), c.sentido(), c.largoMetros(), c.fraccion(), c.lng(), c.lat(), caminata, caminata, c.parada());
    }

    /** {@code destino} puede ser null cuando sólo interesa dónde subir. Puntos en [lat, lng]. */
    public Evaluacion evaluar(double[] origen, double[] destino, List<Long> rutaIds, int porRuta) {
        return evaluar(origen, destino, rutaIds, porRuta, 250);
    }

    /** {@code cubetaMetros}: separación mínima entre candidatos de una misma ruta (más pequeña = más candidatos distintos). */
    public Evaluacion evaluar(double[] origen, double[] destino, List<Long> rutaIds, int porRuta, int cubetaMetros) {
        var subidas = repository.candidatos(origen[0], origen[1], rutaIds, porRuta, cubetaMetros);
        var bajadas = destino == null ? List.<ViajeRepository.CandidatoRow>of()
                : repository.candidatos(destino[0], destino[1], rutaIds, porRuta, cubetaMetros);
        if (subidas == null || subidas.isEmpty()) return Evaluacion.VACIA;
        return medir(origen, destino, subidas, bajadas, 3);
    }

    /**
     * Afinado local: mide la caminata real hacia puntos concretos de cada ruta (fracciones de su línea
     * cercanas al candidato elegido). Devuelve null si no hay puntos que medir.
     */
    public Evaluacion evaluarFracciones(double[] origen, double[] destino, Map<Long, List<Double>> subidas,
                                        Map<Long, List<Double>> bajadas) {
        var a = puntos(subidas);
        var b = puntos(bajadas);
        if (a.isEmpty() || (destino != null && b.isEmpty())) return null;
        return medir(origen, destino, a, b, 2);
    }

    private List<ViajeRepository.CandidatoRow> puntos(Map<Long, List<Double>> porRuta) {
        List<Long> ids = new ArrayList<>();
        List<Double> fracciones = new ArrayList<>();
        porRuta.forEach((id, lista) -> lista.forEach(f -> { ids.add(id); fracciones.add(f); }));
        return repository.puntosEn(ids, fracciones);
    }

    private Evaluacion medir(double[] origen, double[] destino, List<ViajeRepository.CandidatoRow> subidas,
                             List<ViajeRepository.CandidatoRow> bajadas, int segundosMaximos) {
        List<double[]> puntos = new ArrayList<>();
        subidas.forEach(c -> puntos.add(new double[]{c.lng(), c.lat()}));
        bajadas.forEach(c -> puntos.add(new double[]{c.lng(), c.lat()}));
        List<double[]> origenes = new ArrayList<>();
        origenes.add(new double[]{origen[1], origen[0]});
        if (destino != null) origenes.add(new double[]{destino[1], destino[0]});

        Optional<double[][]> matriz = caminata.distancias(origenes, puntos, segundosMaximos);
        List<Candidato> a = new ArrayList<>(), b = new ArrayList<>();
        for (int i = 0; i < subidas.size(); i++)
            a.add(convertir(subidas.get(i), origen, valor(matriz, 0, i), matriz.isPresent()));
        for (int j = 0; j < bajadas.size(); j++)
            b.add(convertir(bajadas.get(j), destino, valor(matriz, 1, subidas.size() + j), matriz.isPresent()));
        return new Evaluacion(List.copyOf(a), List.copyOf(b), matriz.isPresent());
    }

    private static double valor(Optional<double[][]> m, int fila, int col) {
        return m.isPresent() ? m.get()[fila][col] : Double.NaN;
    }

    private static Candidato convertir(ViajeRepository.CandidatoRow c, double[] desde, double metros, boolean hayMatriz) {
        double recta = c.rectaMetros() > 0 || desde == null ? c.rectaMetros() : haversine(desde[0], desde[1], c.lat(), c.lng());
        double caminata = hayMatriz
                ? (Double.isNaN(metros) ? recta * FACTOR_SIN_CAMINO + 500 : metros)
                : recta;
        return new Candidato(c.rutaId(), c.sentido(), c.largoMetros(), c.fraccion(), c.lng(), c.lat(), recta, caminata, c.parada());
    }

    static double haversine(double lat1, double lng1, double lat2, double lng2) {
        double r = 6_371_000, dLat = Math.toRadians(lat2 - lat1), dLng = Math.toRadians(lng2 - lng1);
        double h = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        return 2 * r * Math.asin(Math.sqrt(h));
    }
}
