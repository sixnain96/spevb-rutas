package co.edu.usta.spevb.service;

/** Fallo de un proveedor de direcciones con su causa clasificada. Sigue siendo una IllegalStateException para no romper a quien ya la captura. */
public class ProveedorException extends IllegalStateException {
    private final EstadoBusqueda estado;

    public ProveedorException(EstadoBusqueda estado, String mensaje) { super(mensaje); this.estado = estado; }
    public ProveedorException(EstadoBusqueda estado, String mensaje, Throwable causa) { super(mensaje, causa); this.estado = estado; }

    public EstadoBusqueda estado() { return estado; }

    /** Clasifica cualquier excepción lanzada por un proveedor (o por la espera del plazo). */
    public static EstadoBusqueda clasificar(Throwable t) {
        Throwable x = t;
        for (int i = 0; i < 6 && x != null; i++, x = x.getCause()) {
            if (x instanceof ProveedorException p) return p.estado();
            if (x instanceof java.util.concurrent.TimeoutException || x instanceof java.net.http.HttpTimeoutException
                    || x instanceof java.net.SocketTimeoutException) return EstadoBusqueda.TIMEOUT;
            if (x instanceof org.springframework.web.server.ResponseStatusException r) {
                int c = r.getStatusCode().value();
                if (c == 429) return EstadoBusqueda.CUOTA;
                if (c == 401 || c == 403) return EstadoBusqueda.CLAVE;
                if (c == 504) return EstadoBusqueda.TIMEOUT;
                String m = String.valueOf(r.getReason());
                return m.contains("inválida") || m.contains("grande") ? EstadoBusqueda.RESPUESTA_INVALIDA : EstadoBusqueda.RED;
            }
            if (x instanceof tools.jackson.core.JacksonException) return EstadoBusqueda.RESPUESTA_INVALIDA;
            if (x instanceof java.io.IOException) return EstadoBusqueda.RED;
        }
        return EstadoBusqueda.RED;
    }

    /** Estado según el código HTTP y el cuerpo (Google: RESOURCE_EXHAUSTED, API_KEY_INVALID, PERMISSION_DENIED…). */
    public static EstadoBusqueda desdeHttp(int codigo, String cuerpo) {
        String c = cuerpo == null ? "" : cuerpo;
        if (codigo == 429 || c.contains("RESOURCE_EXHAUSTED") || c.contains("rateLimitExceeded")) return EstadoBusqueda.CUOTA;
        if (codigo == 401 || codigo == 403 || c.contains("API_KEY_INVALID") || c.contains("PERMISSION_DENIED") || c.contains("API key not valid"))
            return EstadoBusqueda.CLAVE;
        if (codigo == 408 || codigo == 504) return EstadoBusqueda.TIMEOUT;
        if (codigo == 400 && c.contains("API key")) return EstadoBusqueda.CLAVE;
        return EstadoBusqueda.RED;
    }
}
