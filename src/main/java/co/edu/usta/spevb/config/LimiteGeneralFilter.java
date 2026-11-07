package co.edu.usta.spevb.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * Freno por IP para cuando la aplicación está publicada en internet: una sola persona (o un programa) no puede saturar el
 * servidor. Los límites son holgados para el uso normal (varias búsquedas por minuto). Se desactiva con LIMITE_SOLICITUDES=false.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class LimiteGeneralFilter extends OncePerRequestFilter {
    private final LimitadorSolicitudes limitador;
    private final boolean activo;

    public LimiteGeneralFilter(LimitadorSolicitudes limitador, @Value("${spevb.limite.habilitado:true}") boolean activo) {
        this.limitador = limitador;
        this.activo = activo;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !activo || !request.getRequestURI().startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String path = request.getRequestURI();
        String ip = request.getRemoteAddr();
        // Cálculo pesado (planificar) más estricto que el resto; la lectura de rutas y teselas es barata.
        boolean permitido;
        if (path.startsWith("/api/viajes/")) permitido = limitador.permitir("g:viaje:" + ip, 40, Duration.ofMinutes(1));
        else if (path.startsWith("/api/caminata")) permitido = limitador.permitir("g:cam:" + ip, 150, Duration.ofMinutes(1));
        else if (path.startsWith("/api/lugares") || path.startsWith("/api/direcciones")) permitido = limitador.permitir("g:lug:" + ip, 120, Duration.ofMinutes(1));
        else permitido = limitador.permitir("g:api:" + ip, 400, Duration.ofMinutes(1));
        if (!permitido) {
            response.setStatus(429);
            response.setHeader("Retry-After", "30");
            response.setContentType("application/json;charset=UTF-8");
            response.getOutputStream().write("{\"status\":429,\"message\":\"Demasiadas solicitudes seguidas. Espera unos segundos e inténtalo de nuevo.\"}"
                    .getBytes(StandardCharsets.UTF_8));
            return;
        }
        chain.doFilter(request, response);
    }
}
