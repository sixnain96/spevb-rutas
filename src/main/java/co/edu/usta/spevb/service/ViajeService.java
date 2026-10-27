package co.edu.usta.spevb.service;

import co.edu.usta.spevb.config.CityArea;
import co.edu.usta.spevb.dto.PlanificarViajeRequest;
import co.edu.usta.spevb.dto.ViajeResponse;
import co.edu.usta.spevb.repository.ViajeRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

@Service
public class ViajeService {
    private final ViajeRepository repository;
    private final ObjectMapper mapper;
    private final CityArea cityArea;

    private static final java.util.concurrent.ExecutorService VIRTUALES = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor();
    private final AbordajeService abordaje;
    private volatile TransbordoService transbordo;
    @Autowired(required = false)
    void usarTransbordos(TransbordoService transbordo) { this.transbordo = transbordo; }
    private final CaminataService caminata;
    /** Planes recientes: pedir de nuevo los mismos dos puntos (p. ej. al cambiar de filtro) es instantáneo. */
    private static final long CACHE_TTL_MS = 90_000;
    private static final int CACHE_MAX = 120;
    private record Guardado(ViajeResponse respuesta, long vence) {}
    private final Map<String, Guardado> cachePlanes = new java.util.LinkedHashMap<>(64, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, Guardado> e) { return size() > CACHE_MAX; }
    };

    /** Rutas que se refinan con la caminata real (las más cercanas en línea recta). */
    private static final int RUTAS_A_REFINAR = 8;
    private static final int CANDIDATOS_POR_RUTA = 2;
    private static final int RUTAS_A_AFINAR = 2;
    private static final int CANDIDATOS_ENSANCHADO = 4;
    private static final double UMBRAL_AFINAR_METROS = 250;
    /** Caminata real / línea recta a partir de la cual se sospecha una barrera. */
    private static final double DESVIO_SOSPECHOSO = 1.6;
    /** Desplazamientos (m) a ambos lados del punto elegido en la etapa fina. */
    private static final double[] DESPLAZAMIENTOS_FINOS = {35, 70, 110};
    /** Desempate: poco peso al recorrido en bus; lo que manda es caminar menos. */
    private static final double PESO_BUS = 0.02;

    public ViajeService(ViajeRepository repository, ObjectMapper mapper, CityArea cityArea) {
        this(repository, mapper, cityArea, null);
    }

    public ViajeService(ViajeRepository repository, ObjectMapper mapper, CityArea cityArea, AbordajeService abordaje) {
        this(repository, mapper, cityArea, abordaje, null);
    }

    @Autowired
    public ViajeService(ViajeRepository repository, ObjectMapper mapper, CityArea cityArea, AbordajeService abordaje,
                        CaminataService caminata) {
        this.repository = repository;
        this.mapper = mapper;
        this.cityArea = cityArea;
        this.abordaje = abordaje;
        this.caminata = caminata;
    }

    public ViajeResponse planificar(PlanificarViajeRequest request) {
        if (!Double.isFinite(request.origen().lat()) || !Double.isFinite(request.origen().lng())
                || !Double.isFinite(request.destino().lat()) || !Double.isFinite(request.destino().lng()))
            throw new IllegalArgumentException("Las coordenadas deben ser números finitos.");
        cityArea.requireInside(request.origen().lat(), request.origen().lng());
        cityArea.requireInside(request.destino().lat(), request.destino().lng());

        String clave = String.format(java.util.Locale.ROOT, "%.5f,%.5f>%.5f,%.5f|%b|%d", request.origen().lat(), request.origen().lng(),
                request.destino().lat(), request.destino().lng(), request.soloValidadas(), request.caminataReferencia());
        synchronized (cachePlanes) {
            var g = cachePlanes.get(clave);
            if (g != null && g.vence() > System.currentTimeMillis()) return g.respuesta();
        }
        var respuesta = calcular(request);
        synchronized (cachePlanes) { cachePlanes.put(clave, new Guardado(respuesta, System.currentTimeMillis() + CACHE_TTL_MS)); }
        return respuesta;
    }

    /** Se llama al editar rutas: los planes guardados dejan de ser válidos. */
    public void invalidarCache() { synchronized (cachePlanes) { cachePlanes.clear(); } }

    private ViajeResponse calcular(PlanificarViajeRequest request) {
        var filas = repository.planificar(request, 60);
        var refinado = refinar(request, filas);
        List<ViajeResponse.Opcion> todas = new ArrayList<>();
        boolean primera = true;
        for (var f : refinado.filas()) {
            var opcion = toOption(f, refinado.porCalles().contains(f.id()), refinado.pares().get(f.id()), request, primera && f.sentidoPermitido(), "MENOS_CAMINATA");
            if (primera && f.sentidoPermitido()) primera = false;
            todas.add(opcion);
            var rapida = variantePorTiempo(f, refinado.pares().get(f.id()), refinado.rapidos().get(f.id()), request);
            if (rapida != null) todas.add(rapida);
        }
        // Sin límite de distancia: la primera opción es siempre la ruta registrada más cercana.
        List<ViajeResponse.Opcion> directas = todas.stream().filter(ViajeResponse.Opcion::sentidoPermitido).limit(16).toList();
        List<ViajeResponse.Opcion> alternativas = todas.stream().filter(o -> !o.sentidoPermitido()).limit(5).toList();

        String alcance;
        if (directas.isEmpty() && alternativas.isEmpty()) {
            alcance = "Todavía no hay rutas registradas que conecten estos dos puntos. Cuando registres más rutas, el cálculo las usará automáticamente.";
        } else if (directas.isEmpty()) {
            alcance = "Las rutas registradas sólo conectan estos puntos recorriéndolas en sentido contrario al dibujado. Registra la ruta de regreso para obtener una opción directa.";
        } else {
            alcance = "Se muestran las rutas registradas más cercanas, sin importar la distancia, ordenadas por lo que hay que caminar desde A hasta la ruta y desde la ruta hasta B. Para cada ruta se evalúan varios puntos de subida y bajada y se elige el que menos obliga a caminar, por calles peatonales cuando el servicio está disponible (si no, en línea recta).";
        }
        var transbordos = transbordosPara(request, directas);
        if (!transbordos.isEmpty()) {
            alcance += " También se muestran viajes con un transbordo (dos buses) cuando no hay ruta directa o ahorran tiempo o caminata;"
                    + " su tiempo incluye una espera estimada de 8 min en el cambio.";
        }
        return new ViajeResponse(directas, alternativas, transbordos, alcance);
    }

    /** Una variante «más rápida» sólo se ofrece si ahorra al menos este tiempo respecto a la de menos caminata. */
    private static final double AHORRO_MINIMO_MINUTOS = 2;

    private static double minutos(Par p) {
        double bus = p.permitido() || "CIRCULAR".equals(p.subida().sentido())
                ? (p.bajada().fraccion() >= p.subida().fraccion() ? p.bajada().fraccion() - p.subida().fraccion()
                        : 1 - p.subida().fraccion() + p.bajada().fraccion()) * p.subida().largoMetros()
                : (p.subida().fraccion() - p.bajada().fraccion()) * p.subida().largoMetros();
        return (p.subida().caminataMetros() + p.bajada().caminataMetros()) / 80.0 + bus / 267.0;
    }

    private ViajeResponse.Opcion variantePorTiempo(ViajeRepository.OpcionRow fila, Par base, Par rapido, PlanificarViajeRequest request) {
        if (base == null || rapido == null || rapido.permitido() != base.permitido()) return null;
        boolean mismoPar = Math.abs(rapido.subida().fraccion() - base.subida().fraccion()) < 1e-6
                && Math.abs(rapido.bajada().fraccion() - base.bajada().fraccion()) < 1e-6;
        if (mismoPar || minutos(base) - minutos(rapido) < AHORRO_MINIMO_MINUTOS) return null;
        var f = construir(fila, rapido, request.caminataReferencia());
        return f == null ? null : toOption(f, true, rapido, request, false, "MAS_RAPIDA");
    }

    /** Viajes con un transbordo. Nunca afectan al cálculo directo: si fallan, simplemente no se ofrecen. */
    private List<ViajeResponse.Transbordo> transbordosPara(PlanificarViajeRequest request, List<ViajeResponse.Opcion> directas) {
        if (transbordo == null) return List.of();
        try {
            TransbordoService.Directo mejor = directas.stream()
                    .map(o -> new TransbordoService.Directo(
                            (o.acercamientoInicioMetros() + o.acercamientoFinMetros()) / 80.0 + o.recorridoKm() * 1000 / 267.0,
                            o.acercamientoInicioMetros() + o.acercamientoFinMetros()))
                    .min(Comparator.comparingDouble(TransbordoService.Directo::minutos)).orElse(null);
            return transbordo.calcular(request, mejor);
        } catch (RuntimeException ex) {
            return List.of();
        }
    }

    private record Refinado(List<ViajeRepository.OpcionRow> filas, java.util.Set<Long> porCalles, Map<Long, Par> pares,
                            Map<Long, Par> rapidos) {
        Refinado(List<ViajeRepository.OpcionRow> filas, java.util.Set<Long> porCalles, Map<Long, Par> pares) {
            this(filas, porCalles, pares, Map.of());
        }
    }

    private record Par(AbordajeService.Candidato subida, AbordajeService.Candidato bajada, boolean permitido, double costo) {}

    /**
     * Elige los puntos de subida/bajada en dos etapas («de grueso a fino»):
     * <ol>
     *   <li>Etapa gruesa: varios candidatos por ruta (puntos distintos a lo largo de la línea) en las rutas más
     *       cercanas, medidos con la caminata real por la red peatonal. Gana el par que menos camina,
     *       respetando el sentido de la ruta.</li>
     *   <li>Etapa fina: para las dos mejores rutas se prueban puntos a ± decenas de metros del par elegido,
     *       porque el mejor punto casi nunca cae exactamente en una muestra.</li>
     * </ol>
     * Las coordenadas de la línea no cambian: todos los puntos son posiciones sobre ella.
     */
    private Refinado refinar(PlanificarViajeRequest request, List<ViajeRepository.OpcionRow> filas) {
        if (abordaje == null || filas.isEmpty()) return new Refinado(filas, java.util.Set.of(), Map.of());
        // Camino exacto: con la red de calles propia se mide cada muestra (cada 15 m) de todas las rutas cercanas.
        double minA = filas.stream().mapToDouble(ViajeRepository.OpcionRow::inicioMetros).min().orElse(0);
        double minB = filas.stream().mapToDouble(ViajeRepository.OpcionRow::finMetros).min().orElse(0);
        try {
            var optimo = abordaje.optimizar(new double[]{request.origen().lat(), request.origen().lng()},
                    new double[]{request.destino().lat(), request.destino().lng()},
                    filas.stream().map(ViajeRepository.OpcionRow::id).distinct().toList(),
                    Math.min(6000, Math.max(1500, 2 * minA + 600)), Math.min(6000, Math.max(1500, 2 * minB + 600)));
            if (optimo != null && !optimo.isEmpty()) {
                Map<Long, Par> exactos = new java.util.HashMap<>(), rapidos = new java.util.HashMap<>();
                optimo.forEach((id, o) -> {
                    exactos.put(id, new Par(o.menosCaminata().subida(), o.menosCaminata().bajada(), o.menosCaminata().permitido(), o.menosCaminata().costo()));
                    var r = o.masRapida();
                    if (r != null) rapidos.put(id, new Par(r.subida(), r.bajada(), r.permitido(), r.costo()));
                });
                var base = armar(filas, exactos, true, request);
                return new Refinado(base.filas(), base.porCalles(), base.pares(), rapidos);
            }
        } catch (RuntimeException ex) {
            // si falla el cálculo exacto se usa el método aproximado de abajo
        }
        List<Long> ids = filas.stream().map(ViajeRepository.OpcionRow::id).distinct().limit(RUTAS_A_REFINAR).toList();
        double[] a = {request.origen().lat(), request.origen().lng()};
        double[] b = {request.destino().lat(), request.destino().lng()};
        var evaluacion = abordaje.evaluar(a, b, ids, CANDIDATOS_POR_RUTA);
        if (evaluacion == null || evaluacion.subidas().isEmpty() || evaluacion.bajadas().isEmpty())
            return new Refinado(filas, java.util.Set.of(), Map.of());

        Map<Long, List<AbordajeService.Candidato>> subidas = new java.util.HashMap<>(evaluacion.subidas().stream()
                .collect(Collectors.groupingBy(AbordajeService.Candidato::rutaId)));
        Map<Long, List<AbordajeService.Candidato>> bajadas = new java.util.HashMap<>(evaluacion.bajadas().stream()
                .collect(Collectors.groupingBy(AbordajeService.Candidato::rutaId)));
        Map<Long, Par> pares = new java.util.HashMap<>();
        ids.forEach(id -> {
            var par = mejorPar(subidas.getOrDefault(id, List.of()), bajadas.getOrDefault(id, List.of()));
            if (par != null) pares.put(id, par);
        });

        if (evaluacion.porCalles()) afinar(a, b, pares, subidas, bajadas);

        return armar(filas, pares, evaluacion.porCalles(), request);
    }

    /** Construye las filas con los pares elegidos y las ordena por lo que hay que caminar. */
    private Refinado armar(List<ViajeRepository.OpcionRow> filas, Map<Long, Par> pares, boolean porCalles,
                           PlanificarViajeRequest request) {
        List<ViajeRepository.OpcionRow> refinadas = new ArrayList<>();
        List<ViajeRepository.OpcionRow> resto = new ArrayList<>();
        var conCalles = new LinkedHashSet<Long>();
        for (var fila : filas) {
            var par = pares.get(fila.id());
            var fila2 = par == null ? null : construir(fila, par, request.caminataReferencia());
            if (fila2 == null) { resto.add(fila); continue; }
            refinadas.add(fila2);
            if (porCalles) conCalles.add(fila.id());
        }
        Comparator<ViajeRepository.OpcionRow> orden = Comparator
                .comparing((ViajeRepository.OpcionRow r) -> !r.sentidoPermitido())
                .thenComparingDouble(ViajeRepository.OpcionRow::accesoTotalMetros)
                .thenComparing((ViajeRepository.OpcionRow r) -> !r.validada())
                .thenComparingDouble(ViajeRepository.OpcionRow::tramoKm)
                .thenComparing(ViajeRepository.OpcionRow::codigo);
        refinadas.sort(orden);
        // Las filas no refinadas se miden sólo en línea recta: van después para no mezclar métricas distintas.
        List<ViajeRepository.OpcionRow> todas = new ArrayList<>(refinadas);
        todas.addAll(resto);
        return new Refinado(todas, conCalles, pares);
    }

    /** Etapa fina para las dos mejores rutas: puntos cercanos al par elegido. Si falla, se conserva el par grueso. */
    private void afinar(double[] a, double[] b, Map<Long, Par> pares,
                        Map<Long, List<AbordajeService.Candidato>> subidas, Map<Long, List<AbordajeService.Candidato>> bajadas) {
        var mejores = pares.entrySet().stream()
                .sorted(Comparator.comparing((Map.Entry<Long, Par> e) -> !e.getValue().permitido())
                        .thenComparingDouble(e -> e.getValue().costo()))
                .limit(RUTAS_A_AFINAR).map(Map.Entry::getKey).toList();
        ensanchar(a, b, mejores, pares, subidas, bajadas);
        // Si en ambos extremos ya se camina muy poco, la etapa fina no aporta y ahorra una consulta al servicio peatonal.
        if (mejores.stream().allMatch(id -> pares.get(id).subida().caminataMetros() < UMBRAL_AFINAR_METROS
                && pares.get(id).bajada().caminataMetros() < UMBRAL_AFINAR_METROS)) return;
        Map<Long, List<Double>> fa = new java.util.LinkedHashMap<>(), fb = new java.util.LinkedHashMap<>();
        for (long id : mejores) {
            var par = pares.get(id);
            double largo = Math.max(1, par.subida().largoMetros());
            fa.put(id, vecinas(par.subida().fraccion(), largo));
            fb.put(id, vecinas(par.bajada().fraccion(), largo));
        }
        AbordajeService.Evaluacion fina;
        try {
            fina = abordaje.evaluarFracciones(a, b, fa, fb);
        } catch (RuntimeException ex) {
            return;
        }
        if (fina == null || !fina.porCalles()) return;
        for (long id : mejores) {
            var sub = new ArrayList<>(subidas.getOrDefault(id, List.of()));
            var baj = new ArrayList<>(bajadas.getOrDefault(id, List.of()));
            fina.subidas().stream().filter(c -> c.rutaId() == id).forEach(sub::add);
            fina.bajadas().stream().filter(c -> c.rutaId() == id).forEach(baj::add);
            var mejor = mejorPar(sub, baj);
            if (mejor != null && mejor.costo() < pares.get(id).costo() + 1e-9
                    && (mejor.permitido() || !pares.get(id).permitido())) pares.put(id, mejor);
        }
    }

    /**
     * Si caminar hasta el punto elegido exige mucho más que la distancia en línea recta, hay una barrera entre medias
     * (cerro, caño, manzana sin paso). En ese caso se piden más candidatos, más juntos, para esa ruta.
     */
    private void ensanchar(double[] a, double[] b, List<Long> ids, Map<Long, Par> pares,
                           Map<Long, List<AbordajeService.Candidato>> subidas, Map<Long, List<AbordajeService.Candidato>> bajadas) {
        List<Long> sospechosas = ids.stream().filter(id -> desviada(pares.get(id).subida()) || desviada(pares.get(id).bajada())).toList();
        if (sospechosas.isEmpty()) return;
        try {
            var extra = abordaje.evaluar(a, b, sospechosas, CANDIDATOS_ENSANCHADO, 120);
            if (extra == null || !extra.porCalles()) return;
            for (long id : sospechosas) {
                var sub = new ArrayList<>(subidas.getOrDefault(id, List.of()));
                var baj = new ArrayList<>(bajadas.getOrDefault(id, List.of()));
                extra.subidas().stream().filter(c -> c.rutaId() == id).forEach(sub::add);
                extra.bajadas().stream().filter(c -> c.rutaId() == id).forEach(baj::add);
                subidas.put(id, sub);
                bajadas.put(id, baj);
                var mejor = mejorPar(sub, baj);
                if (mejor != null && (mejor.permitido() || !pares.get(id).permitido())) pares.put(id, mejor);
            }
        } catch (RuntimeException ex) {
            // se conserva la elección anterior
        }
    }

    private static boolean desviada(AbordajeService.Candidato c) {
        return c.rectaMetros() >= 120 && c.caminataMetros() > DESVIO_SOSPECHOSO * c.rectaMetros();
    }

    private static List<Double> vecinas(double fraccion, double largoMetros) {
        List<Double> r = new ArrayList<>();
        for (double metros : DESPLAZAMIENTOS_FINOS) {
            double d = metros / largoMetros;
            if (fraccion - d >= 0) r.add(fraccion - d);
            if (fraccion + d <= 1) r.add(fraccion + d);
        }
        return r;
    }

    private Par mejorPar(List<AbordajeService.Candidato> subidas, List<AbordajeService.Candidato> bajadas) {
        Par mejor = null;
        for (var a : subidas) {
            for (var b : bajadas) {
                if (Math.abs(b.fraccion() - a.fraccion()) < 1e-6) continue;
                boolean circular = "CIRCULAR".equals(a.sentido());
                boolean permitido = b.fraccion() >= a.fraccion() || circular;
                double bus = b.fraccion() >= a.fraccion() ? (b.fraccion() - a.fraccion()) * a.largoMetros()
                        : circular ? (1 - a.fraccion() + b.fraccion()) * a.largoMetros()
                        : (a.fraccion() - b.fraccion()) * a.largoMetros();
                double costo = a.caminataMetros() - a.ventaja() + b.caminataMetros() - b.ventaja() + PESO_BUS * bus;
                // Una opción con el sentido correcto siempre gana a una de sentido contrario.
                if (mejor == null || (permitido && !mejor.permitido())
                        || (permitido == mejor.permitido() && costo < mejor.costo()))
                    mejor = new Par(a, b, permitido, costo);
            }
        }
        return mejor;
    }

    private ViajeRepository.OpcionRow construir(ViajeRepository.OpcionRow base, Par par, int referencia) {
        var tramo = repository.tramoEntre(base.id(), par.subida().fraccion(), par.bajada().fraccion());
        if (tramo == null) return null;
        double inicio = par.subida().caminataMetros(), fin = par.bajada().caminataMetros();
        // Si el candidato es un paradero registrado, el punto mostrado es el paradero.
        String subida = par.subida().parada() == null ? tramo.subida() : punto(par.subida());
        String bajada = par.bajada().parada() == null ? tramo.bajada() : punto(par.bajada());
        return new ViajeRepository.OpcionRow(base.id(), base.codigo(), base.nombre(), base.color(), base.validada(),
                inicio, fin, tramo.km(), inicio + fin, inicio <= referencia && fin <= referencia,
                par.permitido() ? "SENTIDO_DEL_TRAZADO" : "SENTIDO_INVERSO", base.sentidoRuta(), par.permitido(),
                subida, bajada, tramo.tramo());
    }

    private static String punto(AbordajeService.Candidato c) {
        return String.format(java.util.Locale.ROOT, "{\"type\":\"Point\",\"coordinates\":[%.7f,%.7f]}", c.lng(), c.lat());
    }

    private ViajeResponse.WalkLeg tramoAPie(double lat1, double lng1, double lat2, double lng2) {
        if (caminata == null) return null;
        try {
            var t = caminata.ruta(lat1, lng1, lat2, lng2).orElse(null);
            return t == null ? null : new ViajeResponse.WalkLeg(t.coordenadas(), Math.round(t.metros()), Math.max(1, Math.round(t.segundos() / 60.0)));
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private ViajeResponse.Opcion toOption(ViajeRepository.OpcionRow r, boolean porCalles, Par par,
                                          PlanificarViajeRequest request, boolean conTramosAPie, String variante) {
        String advertencia = r.sentidoPermitido()
                ? "Los puntos de subida y bajada están sobre la línea registrada y se eligieron para caminar lo menos posible. Confirma en campo el paradero permitido."
                : "Esta ruta está registrada en sentido " + r.sentidoRuta() + " y el tramo propuesto va al revés. "
                  + "Sólo sirve si el bus también circula de regreso por estas calles; si es así, registra la ruta "
                  + "de regreso o, si se dibujó al revés, usa «Invertir sentido» en el editor. Confirma en campo.";
        var subida = mapper.readTree(r.subida());
        var bajada = mapper.readTree(r.bajada());
        ViajeResponse.WalkLeg ini = null, fin = null;
        if (conTramosAPie && porCalles && caminata != null) {
            // Para la mejor opción se adelantan los dos tramos a pie (en paralelo): el navegador los dibuja sin más consultas.
            double sLat = subida.path("coordinates").path(1).asDouble(), sLng = subida.path("coordinates").path(0).asDouble();
            double bLat = bajada.path("coordinates").path(1).asDouble(), bLng = bajada.path("coordinates").path(0).asDouble();
            var f1 = CompletableFuture.supplyAsync(() -> tramoAPie(request.origen().lat(), request.origen().lng(), sLat, sLng),
                    VIRTUALES);
            var f2 = CompletableFuture.supplyAsync(() -> tramoAPie(bLat, bLng, request.destino().lat(), request.destino().lng()),
                    VIRTUALES);
            // Como máximo 1,6 s de espera en total: si el servicio peatonal tarda, el navegador los pide por su cuenta.
            long limite = System.nanoTime() + 1_600_000_000L;
            try { ini = f1.get(Math.max(1, limite - System.nanoTime()), java.util.concurrent.TimeUnit.NANOSECONDS); } catch (Exception ex) { ini = null; }
            try { fin = f2.get(Math.max(1, limite - System.nanoTime()), java.util.concurrent.TimeUnit.NANOSECONDS); } catch (Exception ex) { fin = null; }
        }
        return new ViajeResponse.Opcion(
                r.id(), r.codigo(), r.nombre(), r.color(), r.validada(),
                r.inicioMetros(), r.finMetros(), r.tramoKm(), r.accesoTotalMetros(),
                r.dentroLimite(), r.sentidoViaje(), r.sentidoRuta(), r.sentidoPermitido(),
                subida, bajada, mapper.readTree(r.tramo()),
                advertencia, porCalles,
                par == null ? null : par.subida().parada(), par == null ? null : par.bajada().parada(), ini, fin,
                variante
        );
    }
}
