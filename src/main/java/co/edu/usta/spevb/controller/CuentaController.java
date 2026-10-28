package co.edu.usta.spevb.controller;

import co.edu.usta.spevb.config.LimitadorSolicitudes;
import co.edu.usta.spevb.service.CuentaService;
import co.edu.usta.spevb.service.GoogleTokenVerifier;
import co.edu.usta.spevb.service.CuentaService.Usuario;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;

import java.net.URI;
import java.time.Duration;
import java.util.Map;

/** Registro, inicio de sesión (cookie HttpOnly) y datos de la cuenta. Todo bajo /api/cuenta. */
@RestController
@RequestMapping("/api/cuenta")
public class CuentaController {
    static final String COOKIE = "spevb_sesion";
    private final CuentaService cuentas;
    private final LimitadorSolicitudes limitador;
    private final GoogleTokenVerifier google;

    public CuentaController(CuentaService cuentas, LimitadorSolicitudes limitador, GoogleTokenVerifier google) {
        this.google = google;
        this.cuentas = cuentas;
        this.limitador = limitador;
    }

    public record RegistroRequest(String nombre, String correo, String clave) {}
    public record EntrarRequest(String correo, String clave) {}
    public record SalirRequest(Boolean todas) {}
    public record ClaveRequest(String actual, String nueva) {}
    public record EliminarRequest(String clave) {}
    public record GoogleRequest(String credential) {}
    public record DatosRequest(JsonNode valor) {}

    // ------------------------------------------------------------------ ayudas
    /** Las operaciones que cambian algo solo se aceptan desde esta misma página (defensa contra CSRF). */
    private static void mismoSitio(HttpServletRequest r) {
        String sitio = r.getHeader("Sec-Fetch-Site");
        if ("cross-site".equals(sitio)) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Solicitud de otro sitio no permitida.");
        String origen = r.getHeader("Origin");
        if (origen != null && !origen.isBlank() && !"null".equals(origen)) {
            try {
                String host = URI.create(origen).getAuthority();
                String propio = r.getHeader("X-Forwarded-Host") != null ? r.getHeader("X-Forwarded-Host").split(",")[0].strip() : r.getHeader("Host");
                if (host != null && propio != null && !host.equalsIgnoreCase(propio))
                    throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Solicitud de otro sitio no permitida.");
            } catch (IllegalArgumentException e) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Solicitud no válida.");
            }
        }
    }

    private static String token(HttpServletRequest r) {
        if (r.getCookies() == null) return null;
        for (Cookie c : r.getCookies()) if (COOKIE.equals(c.getName())) return c.getValue();
        return null;
    }

    private Usuario exigirUsuario(HttpServletRequest r) {
        return cuentas.usuarioDeSesion(token(r)).orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Inicia sesión para continuar."));
    }

    private static void poner(HttpServletRequest r, HttpServletResponse resp, String valor, Duration vida) {
        resp.addHeader(HttpHeaders.SET_COOKIE, ResponseCookie.from(COOKIE, valor).httpOnly(true).secure(r.isSecure())
                .sameSite("Lax").path("/").maxAge(vida).build().toString());
    }

    private static Map<String, Object> vista(Usuario u) {
        return Map.of("id", u.id(), "nombre", u.nombre(), "correo", u.correo(), "tieneClave", u.tieneClave(), "conGoogle", u.conGoogle(), "admin", u.admin());
    }

    private void limite(String clave, int max, Duration ventana, String mensaje) {
        if (!limitador.permitir(clave, max, ventana)) throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, mensaje);
    }

    // ------------------------------------------------------------------ cuenta
    @PostMapping("/registro")
    public Map<String, Object> registro(@RequestBody RegistroRequest q, HttpServletRequest r, HttpServletResponse resp) {
        mismoSitio(r);
        limite("reg:" + r.getRemoteAddr(), 6, Duration.ofHours(1), "Se crearon varias cuentas desde esta red. Inténtalo más tarde.");
        Usuario u = cuentas.registrar(q.nombre(), q.correo(), q.clave());
        poner(r, resp, cuentas.abrirSesion(u.id(), r.getHeader("User-Agent")), Duration.ofDays(CuentaService.DIAS_SESION));
        return Map.of("usuario", vista(u));
    }

    @PostMapping("/entrar")
    public Map<String, Object> entrar(@RequestBody EntrarRequest q, HttpServletRequest r, HttpServletResponse resp) {
        mismoSitio(r);
        String correo = q.correo() == null ? "" : q.correo().strip().toLowerCase();
        limite("ent:ip:" + r.getRemoteAddr(), 20, Duration.ofMinutes(10), "Demasiados intentos. Espera unos minutos.");
        limite("ent:c:" + correo, 8, Duration.ofMinutes(10), "Demasiados intentos con este correo. Espera unos minutos.");
        Usuario u = cuentas.autenticar(correo, q.clave())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Correo o contraseña incorrectos."));
        poner(r, resp, cuentas.abrirSesion(u.id(), r.getHeader("User-Agent")), Duration.ofDays(CuentaService.DIAS_SESION));
        return Map.of("usuario", vista(u));
    }

    /** Datos públicos para la pantalla de acceso: el Client ID de Google (no es un secreto) o null si no está configurado. */
    @GetMapping("/config")
    public Map<String, Object> config() {
        var m = new java.util.LinkedHashMap<String, Object>();
        if (google.activo()) m.put("google", google.clientId());
        m.put("adminConfigurado", cuentas.adminConfigurado());
        return m;
    }

    /** Inicio de sesión / registro con Google: llega el ID token que entrega el botón de Google y se verifica aquí. */
    @PostMapping("/google")
    public Map<String, Object> conGoogle(@RequestBody GoogleRequest q, HttpServletRequest r, HttpServletResponse resp) {
        mismoSitio(r);
        limite("goo:" + r.getRemoteAddr(), 30, Duration.ofMinutes(10), "Demasiados intentos. Espera unos minutos.");
        GoogleTokenVerifier.Identidad id;
        try {
            id = google.verificar(q.credential());
        } catch (GoogleTokenVerifier.TokenInvalido e) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, e.getMessage());
        }
        Usuario u = cuentas.entrarConGoogle(id);
        poner(r, resp, cuentas.abrirSesion(u.id(), r.getHeader("User-Agent")), Duration.ofDays(CuentaService.DIAS_SESION));
        return Map.of("usuario", vista(u));
    }

    @PostMapping("/salir")
    public Map<String, Object> salir(@RequestBody(required = false) SalirRequest q, HttpServletRequest r, HttpServletResponse resp) {
        mismoSitio(r);
        var usuario = cuentas.usuarioDeSesion(token(r));
        if (q != null && Boolean.TRUE.equals(q.todas()) && usuario.isPresent()) cuentas.cerrarTodas(usuario.get().id());
        else cuentas.cerrarSesion(token(r));
        poner(r, resp, "", Duration.ZERO);
        return Map.of("ok", true);
    }

    /** Siempre responde 200: quien no ha entrado recibe {@code autenticado:false} (sin errores en la consola del navegador). */
    @GetMapping("/yo")
    public Map<String, Object> yo(HttpServletRequest r) {
        return cuentas.usuarioDeSesion(token(r))
                .<Map<String, Object>>map(u -> Map.of("autenticado", true, "usuario", vista(u)))
                .orElse(Map.of("autenticado", false));
    }

    @PostMapping("/clave")
    public Map<String, Object> clave(@RequestBody ClaveRequest q, HttpServletRequest r) {
        mismoSitio(r);
        Usuario u = exigirUsuario(r);
        limite("clave:" + u.id(), 6, Duration.ofMinutes(10), "Demasiados intentos. Espera unos minutos.");
        cuentas.cambiarClave(u.id(), q.actual(), q.nueva());
        return Map.of("ok", true);
    }

    @PostMapping("/eliminar")
    public Map<String, Object> eliminar(@RequestBody EliminarRequest q, HttpServletRequest r, HttpServletResponse resp) {
        mismoSitio(r);
        Usuario u = exigirUsuario(r);
        limite("elim:" + u.id(), 5, Duration.ofMinutes(10), "Demasiados intentos. Espera unos minutos.");
        cuentas.eliminar(u.id(), q.clave());
        poner(r, resp, "", Duration.ZERO);
        return Map.of("ok", true);
    }

    // ------------------------------------------------------------------ datos sincronizados
    @GetMapping("/datos")
    public Map<String, Object> datos(HttpServletRequest r) {
        return cuentas.leerDatos(exigirUsuario(r).id());
    }

    @PostMapping("/datos/{clave}")
    public Map<String, Object> guardar(@PathVariable String clave, @RequestBody DatosRequest q, HttpServletRequest r) {
        mismoSitio(r);
        Usuario u = exigirUsuario(r);
        limite("datos:" + u.id(), 120, Duration.ofMinutes(1), "Demasiados cambios seguidos. Espera unos segundos.");
        cuentas.guardarDatos(u.id(), clave, q.valor());
        return Map.of("ok", true);
    }
}
