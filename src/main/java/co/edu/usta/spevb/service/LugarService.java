package co.edu.usta.spevb.service;

import co.edu.usta.spevb.config.CityArea;
import co.edu.usta.spevb.dto.CrearLugarRequest;
import co.edu.usta.spevb.dto.LugarResponse;
import co.edu.usta.spevb.dto.SugerenciaResponse;
import co.edu.usta.spevb.repository.LugarRepository;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Búsqueda de direcciones y lugares del PASAJERO: exclusivamente Google (Places API «New»).
 * Ningún otro proveedor ni base de direcciones participa en estos flujos, y no hay respaldo silencioso si Google falla.
 * El mapa, los puntos elegidos a mano y el GPS del teléfono no son buscadores de texto y siguen disponibles.
 */
@Service
public class LugarService {
    public static final String PROVEEDOR = "google";
    /** Estados que la interfaz distingue para decir qué hacer (todos con HTTP 200: el texto escrito y los puntos confirmados nunca se pierden). */
    public static final String OK = "OK", SIN_RESULTADOS = "SIN_RESULTADOS", ERROR_SERVICIO = "ERROR_SERVICIO",
            ERROR_CONFIGURACION = "ERROR_CONFIGURACION", SIN_CONFIGURAR = "SIN_CONFIGURAR", CONSULTA_CORTA = "CONSULTA_CORTA";

    private final LugarRepository repository;
    private final CityArea cityArea;
    private final DireccionesLocalesService locales;
    private final GoogleDireccionesService google;

    public LugarService(LugarRepository repository, CityArea cityArea, DireccionesLocalesService locales, GoogleDireccionesService google) {
        this.repository = repository;
        this.cityArea = cityArea;
        this.locales = locales;
        this.google = google;
    }

    public boolean googleActivo() { return google != null && google.activo(); }

    public record ResultadoBusqueda(List<LugarResponse> lugares, List<String> avisos, String estado) {}
    public record ResultadoSugerencias(String estado, List<SugerenciaResponse> sugerencias) {}

    private static String estadoDe(GoogleDireccionesService.ErrorGoogle ex) {
        return ex.tipo() == GoogleDireccionesService.ErrorGoogle.Tipo.CONFIGURACION ? ERROR_CONFIGURACION : ERROR_SERVICIO;
    }

    private static String avisoDe(String estado) {
        return switch (estado) {
            case ERROR_CONFIGURACION -> "La búsqueda con Google no está bien configurada (clave o permisos). Puedes elegir el punto en el mapa o usar tu ubicación.";
            case SIN_CONFIGURAR -> "La búsqueda de direcciones no está configurada en este servidor. Puedes elegir el punto en el mapa o usar tu ubicación.";
            case ERROR_SERVICIO -> "Google no respondió. Tu texto se conserva: reintenta, o elige el punto en el mapa.";
            default -> "";
        };
    }

    /**
     * Sugerencias al escribir: SOLO Google (autocompletado). Si Google falla no hay respaldo silencioso: se informa el estado para que la persona
     * reintente o elija en el mapa. {@code sesion} agrupa autocompletar + detalle como una sola sesión de Google.
     */
    public ResultadoSugerencias sugerir(String query, String sesion) {
        if (query == null || query.strip().length() < 3) return new ResultadoSugerencias(CONSULTA_CORTA, List.of());
        if (!googleActivo()) return new ResultadoSugerencias(SIN_CONFIGURAR, List.of());
        try {
            List<SugerenciaResponse> salida = new ArrayList<>();
            for (var g : google.sugerir(query.strip(), sesion))
                salida.add(new SugerenciaResponse(g.texto(), g.detalle() == null || g.detalle().isBlank() ? GoogleDireccionesService.FUENTE : g.detalle(),
                        GoogleDireccionesService.PREFIJO + g.lugarId(), null, null, null, GoogleDireccionesService.FUENTE, null));
            return new ResultadoSugerencias(salida.isEmpty() ? SIN_RESULTADOS : OK, List.copyOf(salida));
        } catch (GoogleDireccionesService.ErrorGoogle ex) {
            return new ResultadoSugerencias(estadoDe(ex), List.of());
        } catch (IllegalStateException ex) {
            return new ResultadoSugerencias(ERROR_SERVICIO, List.of());
        }
    }

    public boolean confirmarDireccion(String consulta, double lat, double lng) { return locales.confirmar(consulta, lat, lng); }
    public void elegirDireccion(long id) { locales.elegir(id); }

    public ResultadoBusqueda buscarDetallado(String query, String magicKey, String sesion) {
        if (!googleActivo()) return new ResultadoBusqueda(List.of(), List.of(avisoDe(SIN_CONFIGURAR)), SIN_CONFIGURAR);
        boolean elegida = magicKey != null && magicKey.startsWith(GoogleDireccionesService.PREFIJO);
        try {
            // La persona ELIGIÓ una sugerencia: se resuelve exactamente ese lugar. Si Google no lo resuelve NO se sustituye por otro: se informa.
            if (elegida) {
                List<LugarResponse> elegido = google.buscar(magicKey.substring(GoogleDireccionesService.PREFIJO.length()), sesion);
                return elegido.isEmpty()
                        ? new ResultadoBusqueda(List.of(), List.of("Ese lugar quedó fuera de Villavicencio o Google no pudo ubicarlo. Prueba con otra opción o elige el punto en el mapa."), SIN_RESULTADOS)
                        : new ResultadoBusqueda(List.of(elegido.get(0)), List.of(), OK);
            }
            if (query == null || query.strip().length() < 3) return new ResultadoBusqueda(List.of(), List.of(), CONSULTA_CORTA);
            List<LugarResponse> lugares = deduplicate(google.buscarTexto(query.strip()), 8);
            return new ResultadoBusqueda(lugares, List.of(), lugares.isEmpty() ? SIN_RESULTADOS : OK);
        } catch (GoogleDireccionesService.ErrorGoogle ex) {
            String estado = estadoDe(ex);
            return new ResultadoBusqueda(List.of(), List.of(avisoDe(estado)), estado);
        } catch (IllegalStateException ex) {
            return new ResultadoBusqueda(List.of(), List.of(avisoDe(ERROR_SERVICIO)), ERROR_SERVICIO);
        }
    }

    /**
     * Punto elegido en el mapa: valida que esté en Villavicencio y le pone nombre con la base propia de lugares (≤ 45 m) o, si no hay, con sus coordenadas.
     * No se consulta ningún proveedor de direcciones: el punto en el mapa es una decisión de la persona, no una búsqueda de texto.
     */
    public LugarResponse reverso(double lat, double lng) {
        cityArea.requireInside(lat, lng);
        var local = repository.masCercano(lat, lng, 45);
        if (local.isPresent()) return local.get();
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
    public boolean direccionesHabilitadas() { return googleActivo(); }

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
