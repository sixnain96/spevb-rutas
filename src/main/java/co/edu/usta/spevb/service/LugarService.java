package co.edu.usta.spevb.service;

import co.edu.usta.spevb.config.CityArea;
import co.edu.usta.spevb.dto.CrearLugarRequest;
import co.edu.usta.spevb.dto.LugarResponse;
import co.edu.usta.spevb.dto.SugerenciaResponse;
import co.edu.usta.spevb.repository.LugarRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * Búsqueda de direcciones y lugares. POLÍTICA DE PROVEEDORES (docs/CONTRATO-RECOMENDACION-Y-BUSQUEDA.md §B y docs/DIRECCIONES.md):
 * <ol>
 *   <li><b>Google primero</b> (si el mapa es de Google y hay clave), <b>ArcGIS</b> si Google falla, no aporta resultados utilizables o está en pausa, y por
 *       último el <b>respaldo local</b> (índice local/catastro/OSM importado y, solo en búsqueda EXPLÍCITA, Nominatim). No se mezclan con cupos: si un
 *       proveedor devuelve resultados utilizables no se consulta el siguiente ni se espera por él.</li>
 *   <li>Sin Google: ArcGIS → índice local. Nominatim <b>nunca</b> se usa para sugerencias (su servicio público no admite autocompletado).</li>
 *   <li>Una sugerencia elegida se resuelve con ESE identificador y nada más: si falla se informa ({@code eleccionFallida}); no se sustituye por el
 *       primer resultado de otra fuente.</li>
 *   <li>Cada búsqueda tiene un presupuesto total de tiempo y un plazo por proveedor (sin esperas acumuladas en cascada); un proveedor con fallos
 *       seguidos entra en pausa ({@link Cortacircuito}).</li>
 * </ol>
 * <b>Resultado utilizable:</b> dentro de Villavicencio ({@code CityArea}); con coordenadas válidas cuando se resuelve el punto (una predicción vale sin
 * coordenadas hasta pedir su detalle); relacionado con la consulta; y con el detalle adecuado: si se escribió una dirección con vía y números, una
 * ciudad, un barrio o una calle sin número no cuentan, y la dirección recibida debe conservar el tipo de vía y los números.
 * Favoritos, coordenadas escritas y puntos elegidos en el mapa no pasan por aquí.
 */
@Service
public class LugarService {
    public static final String GOOGLE = "google", ARCGIS = "arcgis", LOCAL = "local", NOMINATIM = "nominatim", NINGUNO = "ninguno";

    private final LugarRepository repository;
    private final GeocodingService geocoder;
    private final CityArea cityArea;
    private final DireccionesHibridasService hibrido;
    private final DireccionesLocalesService locales;

    // ---- presupuesto de tiempo (milisegundos). Configurables con spevb.busqueda.* (valores por defecto razonables, sin variables de entorno nuevas).
    private final long presupuestoSugerenciasMs, timeoutProveedorSugerenciasMs, presupuestoExplicitaMs, timeoutProveedorExplicitaMs;
    private volatile LongSupplier reloj = System::currentTimeMillis;
    private final Cortacircuito cortaGoogle = new Cortacircuito(() -> reloj.getAsLong());
    private final Cortacircuito cortaArcgis = new Cortacircuito(() -> reloj.getAsLong());
    private final Cortacircuito cortaNominatim = new Cortacircuito(() -> reloj.getAsLong());

    public LugarService(LugarRepository repository, GeocodingService geocoder, CityArea cityArea,
                        DireccionesHibridasService hibrido, DireccionesLocalesService locales) {
        this(repository, geocoder, cityArea, hibrido, locales, 3500, 1800, 8000, 3000);
    }

    @Autowired
    public LugarService(LugarRepository repository, GeocodingService geocoder, CityArea cityArea,
                        DireccionesHibridasService hibrido, DireccionesLocalesService locales,
                        @Value("${spevb.busqueda.presupuesto-sugerencias-ms:3500}") long presupuestoSugerenciasMs,
                        @Value("${spevb.busqueda.timeout-proveedor-sugerencias-ms:1800}") long timeoutProveedorSugerenciasMs,
                        @Value("${spevb.busqueda.presupuesto-explicita-ms:8000}") long presupuestoExplicitaMs,
                        @Value("${spevb.busqueda.timeout-proveedor-explicita-ms:3000}") long timeoutProveedorExplicitaMs) {
        this.repository = repository;
        this.geocoder = geocoder;
        this.cityArea = cityArea;
        this.hibrido = hibrido;
        this.locales = locales;
        this.presupuestoSugerenciasMs = presupuestoSugerenciasMs;
        this.timeoutProveedorSugerenciasMs = timeoutProveedorSugerenciasMs;
        this.presupuestoExplicitaMs = presupuestoExplicitaMs;
        this.timeoutProveedorExplicitaMs = timeoutProveedorExplicitaMs;
    }

    /** Google (opcional, apagado por defecto): solo existe si hay clave. Se inyecta aparte para no cambiar el constructor. */
    private volatile GoogleDireccionesService google;
    @Autowired(required = false)
    void usarGoogle(GoogleDireccionesService google) { this.google = google; }
    private boolean googleActivo() { var g = google; return g != null && g.activo(); }

    /**
     * Política «SOLO Google» (4.14, {@code spevb.busqueda.solo-google}, por defecto TRUE en la aplicación): en la búsqueda del pasajero ni ArcGIS, ni la base propia/catastro,
     * ni Nominatim participan (ni para sugerir, ni para buscar, ni para nombrar un punto del mapa) y no hay respaldo silencioso. Con FALSE vuelve la cascada
     * anterior Google → ArcGIS → local (→ Nominatim solo explícita). En pruebas unitarias sin Spring vale FALSE (comportamiento histórico).
     */
    @org.springframework.beans.factory.annotation.Value("${spevb.busqueda.solo-google:true}")
    private volatile boolean soloGoogle = false;
    void soloGoogle(boolean v) { this.soloGoogle = v; }
    public boolean esSoloGoogle() { return soloGoogle; }
    private boolean usaArcgis() { return !soloGoogle && hibrido.arcgisActivo(); }
    private boolean usaCatastro() { return !soloGoogle && hibrido.catastroActivo(); }
    private boolean usaLocales() { return !soloGoogle && locales.disponible(); }
    private boolean usaNominatim() { return !soloGoogle && geocoder.configurado(); }

    /** Reloj de pruebas (presupuesto y cortacircuitos). */
    void usarReloj(LongSupplier reloj) { this.reloj = reloj; }

    // ------------------------------------------------------------------ tipos de respuesta
    /** Lo que se consultó y cómo respondió cada proveedor (contrato B.2). */
    public record Intento(String proveedor, EstadoBusqueda estado) {}

    public record ResultadoSugerencias(List<SugerenciaResponse> sugerencias, EstadoBusqueda estado, String proveedor, List<Intento> intentos) {}

    public record ResultadoBusqueda(List<LugarResponse> lugares, List<String> avisos, boolean catastroConfigurado, boolean arcgisConfigurado,
                                    EstadoBusqueda estado, String proveedor, List<Intento> intentos, boolean eleccionFallida) {
        /** Forma anterior (sin estados tipados). */
        public ResultadoBusqueda(List<LugarResponse> lugares, List<String> avisos, boolean catastroConfigurado, boolean arcgisConfigurado) {
            this(lugares, avisos, catastroConfigurado, arcgisConfigurado,
                    lugares.isEmpty() ? EstadoBusqueda.SIN_COINCIDENCIAS : EstadoBusqueda.OK, NINGUNO, List.of(), false);
        }
    }

    public List<LugarResponse> buscar(String query) {
        return buscarDetallado(query).lugares();
    }

    public ResultadoBusqueda buscarDetallado(String query) {
        return buscarDetallado(query, "");
    }

    // ------------------------------------------------------------------ ejecución con plazo
    private static final class Salida<T> {
        final T valor; final EstadoBusqueda estado;
        Salida(T valor, EstadoBusqueda estado) { this.valor = valor; this.estado = estado; }
        boolean ok() { return estado == EstadoBusqueda.OK; }
    }

    /**
     * Ejecuta la consulta de un proveedor en un hilo virtual con plazo = mínimo entre su límite y lo que queda del presupuesto total; si vence, el hilo se
     * interrumpe (la llamada HTTP se cancela) y la búsqueda sigue SIN esperar más. Registra el intento y alimenta el cortacircuito.
     * {@code ignorarPausa}: la resolución de una sugerencia ya elegida no se bloquea por la pausa (es una acción puntual de la persona).
     */
    private <T> Salida<T> consultar(String id, Cortacircuito corta, long timeoutMs, long finMs, boolean ignorarPausa, Supplier<T> tarea, List<Intento> intentos) {
        long quedan = finMs - reloj.getAsLong();
        if (quedan <= 0) { intentos.add(new Intento(id, EstadoBusqueda.TIMEOUT)); return new Salida<>(null, EstadoBusqueda.TIMEOUT); }
        if (!ignorarPausa && corta != null && !corta.permitir()) { intentos.add(new Intento(id, EstadoBusqueda.PAUSADO)); return new Salida<>(null, EstadoBusqueda.PAUSADO); }
        CompletableFuture<T> futuro = new CompletableFuture<>();
        Thread hilo = Thread.ofVirtual().unstarted(() -> {
            try { futuro.complete(tarea.get()); } catch (Throwable t) { futuro.completeExceptionally(t); }
        });
        hilo.start();
        EstadoBusqueda estado;
        T valor = null;
        try {
            valor = futuro.get(Math.max(1, Math.min(timeoutMs, quedan)), TimeUnit.MILLISECONDS);
            estado = EstadoBusqueda.OK;
        } catch (TimeoutException ex) {
            hilo.interrupt();
            estado = EstadoBusqueda.TIMEOUT;
        } catch (ExecutionException ex) {
            estado = ProveedorException.clasificar(ex.getCause());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            hilo.interrupt();
            estado = EstadoBusqueda.TIMEOUT;
        }
        if (corta != null) { if (estado.esFallo()) corta.fallo(); else corta.exito(); }
        intentos.add(new Intento(id, estado));
        return new Salida<>(valor, estado);
    }

    private static void marcar(List<Intento> intentos, String proveedor, EstadoBusqueda estado) {
        for (int i = intentos.size() - 1; i >= 0; i--) {
            if (intentos.get(i).proveedor().equals(proveedor)) { intentos.set(i, new Intento(proveedor, estado)); return; }
        }
        intentos.add(new Intento(proveedor, estado));
    }

    /** Estado final cuando ninguna fuente aportó nada: el fallo más grave; si ninguna falló, «fuera de la ciudad» o «sin coincidencias». */
    private static EstadoBusqueda estadoVacio(List<Intento> intentos) {
        return intentos.stream().map(Intento::estado).filter(e -> e != EstadoBusqueda.OK)
                .min(Comparator.comparingInt(EstadoBusqueda::gravedad)).orElse(EstadoBusqueda.SIN_COINCIDENCIAS);
    }

    private static String nombreProveedor(String id) {
        return switch (id) { case GOOGLE -> "Google"; case ARCGIS -> "ArcGIS"; case NOMINATIM -> "OpenStreetMap/Nominatim"; default -> "La base local"; };
    }

    /** Texto para la persona según qué falló (no todo es «no hay direcciones»). */
    static String avisoDe(String proveedor, EstadoBusqueda estado) {
        String p = nombreProveedor(proveedor);
        return switch (estado) {
            case TIMEOUT -> p + " tardó demasiado en responder. Puedes intentar de nuevo o buscar la dirección de otra forma.";
            case RED -> p + " no respondió. Comprueba tu conexión; puedes buscar la dirección de otra forma.";
            case CUOTA -> p + " alcanzó su límite de consultas por ahora. Intenta de nuevo más tarde.";
            case CLAVE -> p + " no aceptó la clave o la configuración. Avisa a quien administra la aplicación.";
            case RESPUESTA_INVALIDA -> p + " devolvió una respuesta que no se pudo leer.";
            case PAUSADO -> p + " está en pausa unos segundos por fallos seguidos.";
            default -> "";
        };
    }

    // ------------------------------------------------------------------ sugerencias
    /** Máximo de sugerencias que se muestran. */
    private static final int MAX_SUGERENCIAS = 6;

    /** Sugerencias al escribir (forma simple, sin estados): ver {@link #sugerirDetallado}. */
    public List<SugerenciaResponse> sugerir(String query) {
        return sugerirDetallado(query, null).sugerencias();
    }

    /**
     * Sugerencias al escribir con la cascada Google → ArcGIS → local (nunca Nominatim). {@code sesion} es el token de sesión de UNA búsqueda de la
     * persona (lo genera el navegador; sin token válido Google se consulta sin sesión y no se inventa uno compartido).
     */
    public ResultadoSugerencias sugerirDetallado(String query, String sesion) {
        List<Intento> intentos = new ArrayList<>();
        if (query == null || query.strip().length() < 3) return new ResultadoSugerencias(List.of(), EstadoBusqueda.SIN_COINCIDENCIAS, NINGUNO, List.of());
        long fin = reloj.getAsLong() + presupuestoSugerenciasMs;

        if (googleActivo()) {
            var r = consultar(GOOGLE, cortaGoogle, timeoutProveedorSugerenciasMs, fin, false, () -> google.sugerir(query, sesion), intentos);
            if (r.ok()) {
                List<SugerenciaResponse> utiles = new ArrayList<>();
                for (var g : r.valor == null ? List.<GoogleDireccionesService.Sugerencia>of() : r.valor)
                    if (sugerenciaUtilizable(query, g.texto()))
                        utiles.add(new SugerenciaResponse(g.texto(), GoogleDireccionesService.FUENTE, GoogleDireccionesService.PREFIJO + g.lugarId(), null, null, null, GoogleDireccionesService.FUENTE, null));
                if (!utiles.isEmpty()) return new ResultadoSugerencias(List.copyOf(utiles.subList(0, Math.min(MAX_SUGERENCIAS, utiles.size()))), EstadoBusqueda.OK, GOOGLE, List.copyOf(intentos));
                marcar(intentos, GOOGLE, EstadoBusqueda.SIN_COINCIDENCIAS);
            }
        }
        if (usaArcgis()) {
            var r = consultar(ARCGIS, cortaArcgis, timeoutProveedorSugerenciasMs, fin, false, () -> hibrido.sugerirArcgis(query), intentos);
            if (r.ok()) {
                List<SugerenciaResponse> utiles = new ArrayList<>();
                for (var s : r.valor == null ? List.<DireccionesHibridasService.Sugerencia>of() : r.valor)
                    if (sugerenciaUtilizable(query, s.texto()))
                        utiles.add(new SugerenciaResponse(s.texto(), "ArcGIS", s.magicKey(), null, null, null, "ArcGIS World Geocoding", null));
                if (!utiles.isEmpty()) return new ResultadoSugerencias(List.copyOf(utiles.subList(0, Math.min(MAX_SUGERENCIAS, utiles.size()))), EstadoBusqueda.OK, ARCGIS, List.copyOf(intentos));
                marcar(intentos, ARCGIS, EstadoBusqueda.SIN_COINCIDENCIAS);
            }
        }
        if (usaLocales()) {
            try {
                List<SugerenciaResponse> propias = new ArrayList<>();
                for (var l : locales.sugerir(query, MAX_SUGERENCIAS))
                    propias.add(new SugerenciaResponse(l.texto(), l.detalle(), null, l.lat(), l.lng(), l.id() > 0 ? l.id() : null, l.fuente(), l.precision()));
                intentos.add(new Intento(LOCAL, propias.isEmpty() ? EstadoBusqueda.SIN_COINCIDENCIAS : EstadoBusqueda.OK));
                if (!propias.isEmpty()) return new ResultadoSugerencias(List.copyOf(propias), EstadoBusqueda.OK, LOCAL, List.copyOf(intentos));
            } catch (RuntimeException ex) {
                intentos.add(new Intento(LOCAL, EstadoBusqueda.RED));
            }
        }
        return new ResultadoSugerencias(List.of(), estadoVacio(intentos), NINGUNO, List.copyOf(intentos));
    }

    /**
     * ¿Una predicción es utilizable para esta consulta? No vacía; no es solo la ciudad cuando se buscó algo concreto; y si se escribió una dirección
     * con vía y números, conserva el tipo de vía y los primeros números (la placa completa se verifica al pedir las coordenadas).
     */
    boolean sugerenciaUtilizable(String consulta, String texto) {
        if (texto == null || texto.isBlank()) return false;
        if (DireccionesHibridasService.esSoloCiudad(texto) && DireccionesHibridasService.busquedaEspecifica(consulta)) return false;
        if (DireccionesHibridasService.esDireccionNumerada(consulta) || consulta.strip().matches("(?i)^(carrera|calle|avenida|diagonal|transversal|cra|cll|cl|kr|av|dg|tv)\\b.*"))
            return DireccionesHibridasService.sugerenciaRelevante(consulta, texto);
        return true;
    }

    public boolean direccionesLocales() { return usaLocales(); }
    public boolean confirmarDireccion(String consulta, double lat, double lng) { return locales.confirmar(consulta, lat, lng); }
    public void elegirDireccion(long id) { locales.elegir(id); }

    /** El navegador usa esta bandera para pedir sugerencias: vale si hay ArcGIS o Google (cualquiera de los dos proveedores externos). */
    public boolean arcgisActivo() { return usaArcgis() || googleActivo(); }

    // ------------------------------------------------------------------ búsqueda explícita y elección de una sugerencia
    public ResultadoBusqueda buscarDetallado(String query, String magicKey) {
        return buscarDetallado(query, magicKey, null);
    }

    public ResultadoBusqueda buscarDetallado(String query, String magicKey, String sesion) {
        if (magicKey != null && !magicKey.isBlank()) return resolverElegida(query, magicKey.strip(), sesion);
        return buscarExplicita(query);
    }

    private boolean catastroOLocal() { return usaCatastro() || usaLocales(); }

    /**
     * La persona ELIGIÓ una sugerencia: se resuelve ese identificador (Google «g:…» o ArcGIS) y NADA más. Si el proveedor falla o no devuelve el lugar,
     * se informa ({@code eleccionFallida}, con su estado) y no se sustituye por otro resultado ni se cae a una búsqueda genérica.
     */
    private ResultadoBusqueda resolverElegida(String query, String magicKey, String sesion) {
        List<Intento> intentos = new ArrayList<>();
        List<String> avisos = new ArrayList<>();
        boolean esGoogle = magicKey.startsWith(GoogleDireccionesService.PREFIJO);
        String proveedor = esGoogle ? GOOGLE : ARCGIS;
        long fin = reloj.getAsLong() + presupuestoExplicitaMs;
        boolean activo = esGoogle ? googleActivo() : usaArcgis();
        if (!activo) {
            intentos.add(new Intento(proveedor, EstadoBusqueda.CLAVE));
            avisos.add(avisoDe(proveedor, EstadoBusqueda.CLAVE));
            return fallida(avisos, intentos, EstadoBusqueda.CLAVE, proveedor);
        }
        Salida<ResultadoLugares> r = esGoogle
                ? consultar(GOOGLE, cortaGoogle, timeoutProveedorExplicitaMs, fin, true,
                        () -> google.resolver(magicKey.substring(GoogleDireccionesService.PREFIJO.length()), query, sesion), intentos)
                : consultar(ARCGIS, cortaArcgis, timeoutProveedorExplicitaMs, fin, true, () -> hibrido.buscarArcgisResultado(query, magicKey), intentos);
        if (r.ok() && r.valor != null && !r.valor.lugares().isEmpty())
            return new ResultadoBusqueda(List.of(r.valor.lugares().get(0)), List.of(), catastroOLocal(), arcgisActivo(), EstadoBusqueda.OK, proveedor, List.copyOf(intentos), false);
        EstadoBusqueda estado;
        if (!r.ok()) estado = r.estado;
        else if (r.valor != null && r.valor.fueraDeCiudad() > 0) estado = EstadoBusqueda.FUERA_DE_CIUDAD;
        else estado = r.valor == null ? EstadoBusqueda.RESPUESTA_INVALIDA : EstadoBusqueda.SIN_COINCIDENCIAS;
        marcar(intentos, proveedor, estado);
        String aviso = avisoDe(proveedor, estado);
        if (estado == EstadoBusqueda.FUERA_DE_CIUDAD) aviso = "Ese lugar queda fuera de Villavicencio.";
        else if (estado == EstadoBusqueda.SIN_COINCIDENCIAS) aviso = nombreProveedor(proveedor) + " ya no encuentra ese lugar.";
        avisos.add(aviso + " No se cambió por otro resultado: vuelve a elegirlo o escoge otra opción.");
        return fallida(avisos, intentos, estado, proveedor);
    }

    private ResultadoBusqueda fallida(List<String> avisos, List<Intento> intentos, EstadoBusqueda estado, String proveedor) {
        return new ResultadoBusqueda(List.of(), List.copyOf(avisos), catastroOLocal(), arcgisActivo(), estado, proveedor, List.copyOf(intentos), true);
    }

    /** ¿Un lugar de un proveedor externo sirve como respuesta a esta consulta de texto? Ver «resultado utilizable» en la documentación de la clase. */
    boolean lugarUtilizable(String consulta, LugarResponse l) {
        if (l == null || !cityArea.contains(l.lat(), l.lng())) return false;
        String precision = l.precision() == null ? "" : l.precision();
        if (DireccionesHibridasService.esDireccionNumerada(consulta)) {
            if (precision.equals("VIA") || precision.equals("SECTOR") || precision.equals("CIUDAD")) return false;
            return DireccionesHibridasService.direccionCoincide(consulta, l.direccion() == null ? l.nombre() : l.direccion());
        }
        return !(precision.equals("CIUDAD") && DireccionesHibridasService.busquedaEspecifica(consulta));
    }

    private ResultadoBusqueda buscarExplicita(String query) {
        List<Intento> intentos = new ArrayList<>();
        List<String> avisos = new ArrayList<>();
        long fin = reloj.getAsLong() + presupuestoExplicitaMs;
        List<LugarResponse> local = soloGoogle ? List.<LugarResponse>of() : repository.buscarCatalogo(cityArea.normalizeAddress(query), 8);
        List<LugarResponse> merged = new ArrayList<>();
        // Los puntos comprobados por el equipo no se sustituyen por predicciones externas.
        local.stream().filter(LugarResponse::confirmado).forEach(merged::add);
        String proveedorFinal = merged.isEmpty() ? NINGUNO : LOCAL;
        int fueraDeCiudad = 0;
        boolean externoFallo = false;

        // 1) Google
        boolean resuelto = false;
        if (googleActivo()) {
            var r = consultar(GOOGLE, cortaGoogle, timeoutProveedorExplicitaMs, fin, false, () -> google.buscarTextoResultado(query), intentos);
            if (r.ok() && r.valor != null) {
                List<LugarResponse> utiles = r.valor.lugares().stream().filter(l -> lugarUtilizable(query, l)).toList();
                fueraDeCiudad += r.valor.fueraDeCiudad();
                if (!utiles.isEmpty()) { merged.addAll(utiles); proveedorFinal = GOOGLE; resuelto = true; }
                else marcar(intentos, GOOGLE, r.valor.fueraDeCiudad() > 0 ? EstadoBusqueda.FUERA_DE_CIUDAD : EstadoBusqueda.SIN_COINCIDENCIAS);
            } else if (!r.ok()) {
                externoFallo = true;
                String aviso = avisoDe(GOOGLE, r.estado);
                if (!aviso.isEmpty()) avisos.add(aviso);
            }
        }
        // 2) ArcGIS (solo si Google no resolvió)
        if (!resuelto && usaArcgis()) {
            var r = consultar(ARCGIS, cortaArcgis, timeoutProveedorExplicitaMs, fin, false, () -> hibrido.buscarArcgisResultado(query, ""), intentos);
            if (r.ok() && r.valor != null) {
                List<LugarResponse> utiles = r.valor.lugares().stream().filter(l -> lugarUtilizable(query, l)).toList();
                fueraDeCiudad += r.valor.fueraDeCiudad();
                if (!utiles.isEmpty()) { merged.addAll(utiles); proveedorFinal = ARCGIS; resuelto = true; }
                else marcar(intentos, ARCGIS, r.valor.fueraDeCiudad() > 0 ? EstadoBusqueda.FUERA_DE_CIUDAD : EstadoBusqueda.SIN_COINCIDENCIAS);
            } else if (!r.ok()) {
                externoFallo = true;
                String aviso = avisoDe(ARCGIS, r.estado);
                if (!aviso.isEmpty()) avisos.add(aviso);
            }
        }
        // 3) Respaldo local (solo si ningún proveedor externo resolvió)
        boolean tienePredio = false, direccionPuntual = false;
        if (!resuelto) {
            boolean baseLocal = usaLocales();
            if (baseLocal) {
                List<LugarResponse> propios = locales.buscar(query, 8);
                merged.addAll(propios);
                intentos.add(new Intento(LOCAL, propios.isEmpty() ? EstadoBusqueda.SIN_COINCIDENCIAS : EstadoBusqueda.OK));
                if (!propios.isEmpty() && NINGUNO.equals(proveedorFinal)) proveedorFinal = LOCAL;
                var estimadas = Set.of("INTERPOLADA", "VECINA_APROXIMADA", "VIA_APROXIMADA");
                if (propios.stream().anyMatch(p -> estimadas.contains(p.precision()))
                        && propios.stream().noneMatch(p -> "PREDIO_APROXIMADO".equals(p.precision()) || "CONFIRMADA_POR_USUARIOS".equals(p.precision())))
                    avisos.add("Esa numeración no aparece tal cual en el catastro: la posición se estimó con las direcciones vecinas. Compruébala en el mapa.");
            } else if (usaCatastro()) {
                try {
                    List<LugarResponse> predios = hibrido.buscarCatastro(query);
                    merged.addAll(predios);
                    intentos.add(new Intento(LOCAL, predios.isEmpty() ? EstadoBusqueda.SIN_COINCIDENCIAS : EstadoBusqueda.OK));
                    if (!predios.isEmpty() && NINGUNO.equals(proveedorFinal)) proveedorFinal = LOCAL;
                } catch (IllegalStateException ex) {
                    externoFallo = true;
                    EstadoBusqueda e = ex instanceof ProveedorException p ? p.estado() : EstadoBusqueda.RED;
                    intentos.add(new Intento(LOCAL, e));
                    avisos.add("El catastro de Villavicencio no respondió correctamente. Comprueba la URL y la conexión a Internet.");
                }
            }
            tienePredio = merged.stream().anyMatch(p -> "PREDIO_APROXIMADO".equals(p.precision()) || "CONFIRMADA_POR_USUARIOS".equals(p.precision()));
            direccionPuntual = merged.stream().anyMatch(p -> "DIRECCION_PUNTUAL".equals(p.precision()));
            // 4) Nominatim: SOLO aquí (búsqueda explícita), nunca en sugerencias.
            boolean direccionNumerada = cityArea.normalizeAddress(query).matches("(?i)^(Carrera|Calle|Avenida|Diagonal|Transversal)\\s+.*\\d.*");
            if (!tienePredio && !direccionPuntual && usaNominatim() && (direccionNumerada || merged.size() < 3)) {
                var r = consultar(NOMINATIM, cortaNominatim, timeoutProveedorExplicitaMs, fin, false, () -> geocoder.buscar(query), intentos);
                if (r.ok() && r.valor != null) {
                    int antes = merged.size();
                    for (var result : r.valor) merged.add(toResponse(result));
                    if (merged.size() > antes) { if (NINGUNO.equals(proveedorFinal)) proveedorFinal = NOMINATIM; }
                    else marcar(intentos, NOMINATIM, EstadoBusqueda.SIN_COINCIDENCIAS);
                } else if (!r.ok()) {
                    externoFallo = true;
                    String aviso = avisoDe(NOMINATIM, r.estado);
                    if (!aviso.isEmpty()) avisos.add(aviso);
                }
            }
        }
        // Incluye lugares no confirmados sólo como sugerencias, sin sobreponerlos al catastro.
        local.stream().filter(p -> !p.confirmado()).forEach(merged::add);
        if (merged.isEmpty() && externoFallo)
            avisos.add("No se pudo comprobar la dirección en las fuentes externas. No se asignó ningún marcador arbitrario.");
        var lugares = deduplicate(merged, 8);
        EstadoBusqueda estado = lugares.isEmpty()
                ? (fueraDeCiudad > 0 && intentos.stream().noneMatch(i -> i.estado().esFallo()) ? EstadoBusqueda.FUERA_DE_CIUDAD : estadoVacio(intentos))
                : EstadoBusqueda.OK;
        return new ResultadoBusqueda(lugares, List.copyOf(avisos), catastroOLocal(), arcgisActivo(), estado,
                lugares.isEmpty() ? NINGUNO : proveedorFinal, List.copyOf(intentos), false);
    }

    public LugarResponse reverso(double lat, double lng) {
        cityArea.requireInside(lat, lng);
        var local = repository.masCercano(lat, lng, 45);
        if (local.isPresent()) return local.get();
        var external = soloGoogle ? null : geocoder.reverso(lat, lng);      // solo Google: el punto del mapa no consulta ningún proveedor de direcciones
        if (external != null) {
            return toResponse(external);
        }
        return new LugarResponse(null,
                "Punto en mapa: " + String.format(Locale.ROOT, "%.6f, %.6f", lat, lng),
                null, lat, lng, "Punto seleccionado manualmente", false);
    }

    public LugarResponse crear(CrearLugarRequest request) {
        cityArea.requireInside(request.lat(), request.lng());
        return repository.crear(request);
    }

    public List<LugarResponse> listar(String query, int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 500));
        return repository.listar(query, safeLimit);
    }

    public void eliminar(long id) {
        if (!repository.eliminar(id)) throw new IllegalArgumentException("El lugar ya no existe.");
    }

    public long contar() { return repository.contar(); }
    public boolean geocodificadorActivo() { return hibrido.tieneProveedores() || usaNominatim(); }
    public boolean reversoActivo() { return geocoder.reversoConfigurado(); }

    private LugarResponse toResponse(GeocodingService.Resultado result) {
        return new LugarResponse(null, result.nombre(), result.direccion(), result.lat(), result.lng(),
                result.fuente(), false, result.precision());
    }

    private List<LugarResponse> deduplicate(List<LugarResponse> values, int max) {
        LinkedHashMap<String, LugarResponse> unique = new LinkedHashMap<>();
        for (LugarResponse value : values) {
            String key = normalize(value.nombre()) + "|" + String.format(Locale.ROOT, "%.5f|%.5f", value.lat(), value.lng());
            unique.putIfAbsent(key, value);
            if (unique.size() == max) break;
        }
        return List.copyOf(unique.values());
    }

    private String normalize(String value) {
        return java.text.Normalizer.normalize(value == null ? "" : value, java.text.Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "").toLowerCase(Locale.ROOT).strip();
    }
}
