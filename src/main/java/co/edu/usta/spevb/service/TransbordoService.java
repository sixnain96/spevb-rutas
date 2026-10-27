package co.edu.usta.spevb.service;

import co.edu.usta.spevb.dto.PlanificarViajeRequest;
import co.edu.usta.spevb.dto.ViajeResponse;
import co.edu.usta.spevb.repository.ViajeRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Viajes con UN transbordo (dos buses): camina → bus 1 → camina hasta otra ruta → bus 2 → camina.
 *
 * <p>Se muestrea cada ruta cercana a A o a B (un punto cada 50 m, más sus paraderos) y se mide con la red de calles la
 * caminata desde A y hasta B a cada punto. Por ruta se calcula, con una pasada, el mejor sitio donde subir para llegar a
 * cada punto de la línea (costo mínimo acumulado) y el mejor sitio donde bajar a partir de cada punto. Los transbordos
 * posibles son los pares de puntos de dos rutas a menos de 250 m. Se respeta el sentido de cada ruta. Los mejores pares
 * se afinan con las caminatas reales por calles y los tramos exactos del bus.
 *
 * <p>Tiempos: caminar 80 m/min, bus 267 m/min (16 km/h) y una espera estimada de 8 min en el cambio (no hay horarios).
 */
@Service
public class TransbordoService {
    private static final Logger log = LoggerFactory.getLogger(TransbordoService.class);

    static final double PASO_METROS = 50;
    static final double RADIO_RUTAS_METROS = 2500;
    static final double MAX_CAMBIO_METROS = 250;
    static final double CAMINA_M_MIN = 80;
    static final double BUS_M_MIN = 267;
    static final double ESPERA_CAMBIO_MIN = 8;
    static final double MAX_CAMINATA_EXTREMO_METROS = 1800;
    static final double TRAMO_BUS_MINIMO_METROS = 120;
    static final double FACTOR_CALLES_CAMBIO = 1.25;
    static final double BONUS_PARADERO_MIN = 0.3;
    static final int FINALISTAS = 8;
    static final int MAXIMO_OPCIONES = 3;
    private static final double CELDA = 0.0025;

    /** Lo que se compara contra el mejor viaje directo para decidir si vale la pena mostrar un transbordo. */
    public record Directo(double minutos, double caminataMetros) {}

    private final ViajeRepository repository;
    private final ObjectMapper mapper;
    private volatile RedPeatonalService red;

    public TransbordoService(ViajeRepository repository, ObjectMapper mapper) {
        this.repository = repository;
        this.mapper = mapper;
    }

    @Autowired(required = false)
    void usarRed(RedPeatonalService red) { this.red = red; }

    // ------------------------------------------------------------------ datos internos
    private record Muestra(int ruta, double f, double t, double lat, double lng, String parada) {}

    private static final class RutaM {
        final long id; final String sentido; final double largo; final int desde, hasta;
        RutaM(long id, String sentido, double largo, int desde, int hasta) { this.id = id; this.sentido = sentido; this.largo = largo; this.desde = desde; this.hasta = hasta; }
    }

    private record Candidato(int x, int y, int j, int k, double distancia, double minutos) {}

    // ------------------------------------------------------------------ cálculo
    /**
     * Mejores viajes con un transbordo para ir de A a B. {@code directo} es el mejor viaje directo (null si no hay);
     * un transbordo solo se ofrece si no hay directo, si ahorra al menos 5 min o si evita mucha caminata.
     */
    public List<ViajeResponse.Transbordo> calcular(PlanificarViajeRequest request, Directo directo) {
        RedPeatonalService r = red;
        if (r == null || !r.lista()) return List.of();
        double[] a = {request.origen().lat(), request.origen().lng()}, b = {request.destino().lat(), request.destino().lng()};

        List<Long> cercaA = repository.rutasCerca(a[0], a[1], RADIO_RUTAS_METROS, request.soloValidadas());
        List<Long> cercaB = repository.rutasCerca(b[0], b[1], RADIO_RUTAS_METROS, request.soloValidadas());
        if (cercaA.isEmpty() || cercaB.isEmpty()) return List.of();
        Set<Long> union = new LinkedHashSet<>(cercaA);
        union.addAll(cercaB);
        if (union.size() < 2) return List.of();
        List<Long> ids = new ArrayList<>(union).subList(0, Math.min(union.size(), 80));

        // 1) muestras de las líneas completas
        List<ViajeRepository.CandidatoRow> filas = repository.muestrasCompletas(ids, PASO_METROS);
        if (filas.isEmpty()) return List.of();
        List<RutaM> rutas = new ArrayList<>();
        int inicio = 0;
        for (int i = 1; i <= filas.size(); i++) {
            if (i == filas.size() || filas.get(i).rutaId() != filas.get(inicio).rutaId()) {
                var f0 = filas.get(inicio);
                rutas.add(new RutaM(f0.rutaId(), f0.sentido(), f0.largoMetros(), inicio, i));
                inicio = i;
            }
        }
        List<Muestra> m = new ArrayList<>(filas.size());
        for (int ri = 0; ri < rutas.size(); ri++) {
            for (int i = rutas.get(ri).desde; i < rutas.get(ri).hasta; i++) {
                var f = filas.get(i);
                m.add(new Muestra(ri, f.fraccion(), f.fraccion() * f.largoMetros(), f.lat(), f.lng(), f.parada()));
            }
        }

        // 2) caminata real desde A y hasta B a cada muestra (dos exploraciones de la red)
        List<double[]> puntos = new ArrayList<>(m.size());
        m.forEach(s -> puntos.add(new double[]{s.lng(), s.lat()}));
        var matriz = r.distancias(List.of(new double[]{a[1], a[0]}, new double[]{b[1], b[0]}), puntos);
        if (matriz.isEmpty()) return List.of();
        double[] wA = matriz.get()[0], wB = matriz.get()[1];

        // 3) por ruta: mejor subida acumulada (para llegar a cada punto) y mejor bajada (a partir de cada punto)
        Set<Long> setA = new HashSet<>(cercaA), setB = new HashSet<>(cercaB);
        double[] subida = new double[m.size()], bajada = new double[m.size()];
        int[] subidaEn = new int[m.size()], bajadaEn = new int[m.size()];
        Arrays.fill(subida, Double.NaN); Arrays.fill(bajada, Double.NaN);
        for (RutaM ru : rutas) {
            if (setA.contains(ru.id)) mejorSubida(m, ru, wA, subida, subidaEn);
            if (setB.contains(ru.id)) mejorBajada(m, ru, wB, bajada, bajadaEn);
        }

        // 4) cambios posibles: pares de puntos de rutas distintas a ≤ 250 m (índice por celdas)
        Map<Long, List<Integer>> rejilla = new HashMap<>();
        for (int k = 0; k < m.size(); k++) {
            if (Double.isNaN(bajada[k])) continue;
            rejilla.computeIfAbsent(celda(m.get(k).lat(), m.get(k).lng()), x -> new ArrayList<>()).add(k);
        }
        Map<Long, Candidato> mejorPorPar = new HashMap<>();
        for (int j = 0; j < m.size(); j++) {
            if (Double.isNaN(subida[j])) continue;
            Muestra sj = m.get(j);
            int cy = (int) Math.floor((sj.lat() + 90) / CELDA), cx = (int) Math.floor((sj.lng() + 180) / CELDA);
            for (int dy = -1; dy <= 1; dy++) for (int dx = -1; dx <= 1; dx++) {
                var lista = rejilla.get(clave(cx + dx, cy + dy));
                if (lista == null) continue;
                for (int k : lista) {
                    Muestra sk = m.get(k);
                    if (sk.ruta() == sj.ruta()) continue;
                    double d = RedPeatonal.metros(sj.lat(), sj.lng(), sk.lat(), sk.lng());
                    if (d > MAX_CAMBIO_METROS) continue;
                    double minutos = subida[j] + d * FACTOR_CALLES_CAMBIO / CAMINA_M_MIN + ESPERA_CAMBIO_MIN + bajada[k]
                            - (sj.parada() != null ? BONUS_PARADERO_MIN : 0) - (sk.parada() != null ? BONUS_PARADERO_MIN : 0);
                    long par = (long) sj.ruta() * 100_003L + sk.ruta();
                    var previo = mejorPorPar.get(par);
                    if (previo == null || minutos < previo.minutos()) mejorPorPar.put(par, new Candidato(sj.ruta(), sk.ruta(), j, k, d, minutos));
                }
            }
        }
        if (mejorPorPar.isEmpty()) return List.of();

        // 5) los mejores pares se afinan con calles reales y tramos exactos
        List<Candidato> top = mejorPorPar.values().stream().sorted(Comparator.comparingDouble(Candidato::minutos)).limit(FINALISTAS).toList();
        Map<Long, ViajeRepository.InfoRuta> info = new HashMap<>();
        repository.infoRutas(top.stream().flatMap(c -> java.util.stream.Stream.of(rutas.get(c.x()).id, rutas.get(c.y()).id)).distinct().toList())
                .forEach(i -> info.put(i.id(), i));
        List<ViajeResponse.Transbordo> afinados = new ArrayList<>();
        for (Candidato c : top) {
            try {
                var t = afinar(c, m, rutas, subidaEn, bajadaEn, a, b, r, info);
                if (t != null) afinados.add(t);
            } catch (RuntimeException ex) {
                log.debug("Transbordo descartado: {}", ex.toString());
            }
        }
        afinados.sort(Comparator.comparingDouble(ViajeResponse.Transbordo::minutos));

        // 6) solo se ofrecen los que valen la pena y con rutas distintas entre sí
        List<ViajeResponse.Transbordo> resultado = new ArrayList<>();
        Set<String> usados = new HashSet<>();
        for (var t : afinados) {
            if (!valeLaPena(t, directo)) continue;
            if (!usados.add(t.primerBus().rutaId() + ">" + t.segundoBus().rutaId())) continue;
            resultado.add(t);
            if (resultado.size() >= MAXIMO_OPCIONES) break;
        }
        return List.copyOf(resultado);
    }

    static boolean valeLaPena(ViajeResponse.Transbordo t, Directo d) {
        if (d == null) return true;
        if (t.minutos() <= d.minutos() - 5) return true;
        return d.caminataMetros() > 1500 && t.caminataMetros() < 0.6 * d.caminataMetros() && t.minutos() <= d.minutos() + 3;
    }

    // ------------------------------------------------------------------ pasadas por ruta
    private static boolean usable(double w) { return !Double.isNaN(w) && w <= MAX_CAMINATA_EXTREMO_METROS; }
    private static double bonus(Muestra s) { return s.parada() != null ? BONUS_PARADERO_MIN : 0; }

    /** subida[j]: minutos mínimos para estar sobre el bus en el punto j (caminar desde A + ir en bus), con ≥ 120 m de bus. */
    private static void mejorSubida(List<Muestra> m, RutaM ru, double[] wA, double[] subida, int[] subidaEn) {
        double corrido = Double.MAX_VALUE; int arg = -1, p = ru.desde;
        for (int j = ru.desde; j < ru.hasta; j++) {
            double tj = m.get(j).t();
            while (p < j && m.get(p).t() <= tj - TRAMO_BUS_MINIMO_METROS) {
                if (usable(wA[p])) {
                    double u = wA[p] / CAMINA_M_MIN - bonus(m.get(p)) - m.get(p).t() / BUS_M_MIN;
                    if (u < corrido) { corrido = u; arg = p; }
                }
                p++;
            }
            if (arg >= 0) { subida[j] = corrido + tj / BUS_M_MIN; subidaEn[j] = arg; }
        }
    }

    /** bajada[k]: minutos mínimos desde el punto k hasta B (bus + caminar), con ≥ 120 m de bus. */
    private static void mejorBajada(List<Muestra> m, RutaM ru, double[] wB, double[] bajada, int[] bajadaEn) {
        double corrido = Double.MAX_VALUE; int arg = -1, p = ru.hasta - 1;
        for (int k = ru.hasta - 1; k >= ru.desde; k--) {
            double tk = m.get(k).t();
            while (p > k && m.get(p).t() >= tk + TRAMO_BUS_MINIMO_METROS) {
                if (usable(wB[p])) {
                    double u = wB[p] / CAMINA_M_MIN - bonus(m.get(p)) + m.get(p).t() / BUS_M_MIN;
                    if (u < corrido) { corrido = u; arg = p; }
                }
                p--;
            }
            if (arg >= 0) { bajada[k] = corrido - tk / BUS_M_MIN; bajadaEn[k] = arg; }
        }
    }

    private static long celda(double lat, double lng) { return clave((int) Math.floor((lng + 180) / CELDA), (int) Math.floor((lat + 90) / CELDA)); }
    private static long clave(int x, int y) { return ((long) y << 32) ^ (x & 0xffffffffL); }

    // ------------------------------------------------------------------ afinado de un finalista
    private ViajeResponse.Transbordo afinar(Candidato c, List<Muestra> m, List<RutaM> rutas, int[] subidaEn, int[] bajadaEn,
                                            double[] a, double[] b, RedPeatonalService r, Map<Long, ViajeRepository.InfoRuta> info) {
        Muestra sj = m.get(c.j()), sk = m.get(c.k()), si = m.get(subidaEn[c.j()]), sl = m.get(bajadaEn[c.k()]);
        RutaM x = rutas.get(c.x()), y = rutas.get(c.y());
        var caminaIni = r.ruta(a[0], a[1], si.lat(), si.lng());
        var caminaCambio = r.ruta(sj.lat(), sj.lng(), sk.lat(), sk.lng());
        var caminaFin = r.ruta(sl.lat(), sl.lng(), b[0], b[1]);
        if (caminaIni.isEmpty() || caminaCambio.isEmpty() || caminaFin.isEmpty()) return null;
        var tramo1 = repository.tramoEntre(x.id, si.f(), sj.f());
        var tramo2 = repository.tramoEntre(y.id, sk.f(), sl.f());
        if (tramo1 == null || tramo2 == null) return null;
        var ri = info.get(x.id); var rj = info.get(y.id);
        if (ri == null || rj == null) return null;

        double caminata = caminaIni.get().metros() + caminaCambio.get().metros() + caminaFin.get().metros();
        double km = tramo1.km() + tramo2.km();
        double minutos = caminata / CAMINA_M_MIN + km * 1000 / BUS_M_MIN + ESPERA_CAMBIO_MIN;
        var bus1 = new ViajeResponse.BusLeg(x.id, ri.codigo(), ri.nombre(), ri.color(), ri.validada(), mapper.readTree(tramo1.subida()),
                mapper.readTree(tramo1.bajada()), mapper.readTree(tramo1.tramo()), tramo1.km(), si.parada(), sj.parada());
        var bus2 = new ViajeResponse.BusLeg(y.id, rj.codigo(), rj.nombre(), rj.color(), rj.validada(), mapper.readTree(tramo2.subida()),
                mapper.readTree(tramo2.bajada()), mapper.readTree(tramo2.tramo()), tramo2.km(), sk.parada(), sl.parada());
        String aviso = "Necesitas dos pasajes. El tiempo incluye unos " + (int) ESPERA_CAMBIO_MIN + " min de espera estimada en el cambio "
                + "(no hay horarios registrados). Confirma en campo dónde para cada ruta.";
        return new ViajeResponse.Transbordo(minutos, caminata, km, 2, ESPERA_CAMBIO_MIN, bus1, bus2,
                pierna(caminaIni.get()), pierna(caminaCambio.get()), pierna(caminaFin.get()), aviso);
    }

    private static ViajeResponse.WalkLeg pierna(CaminataService.Tramo t) {
        return new ViajeResponse.WalkLeg(t.coordenadas(), Math.round(t.metros()), Math.max(1, Math.round(t.segundos() / 60.0)));
    }
}
