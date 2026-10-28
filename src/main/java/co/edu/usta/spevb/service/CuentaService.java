package co.edu.usta.spevb.service;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/** Cuentas de usuario, sesiones y datos sincronizados (lugares, rutas favoritas, viajes guardados, historial). */
@Service
public class CuentaService {
    public static final int DIAS_SESION = 30;
    private static final int MAX_SESIONES = 10;
    private static final Pattern CORREO = Pattern.compile("^[^\\s@]{1,64}@[^\\s@]+\\.[^\\s@]{2,}$");
    /** Clave de relleno para igualar el tiempo de respuesta cuando el correo no existe (no revela qué correos hay registrados). */
    private static final String HASH_RELLENO = ClaveHash.hacer("relleno-sin-uso");
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final DatosUsuario datos;
    private final SecureRandom azar = new SecureRandom();
    private final java.util.Set<String> admins;

    public CuentaService(JdbcTemplate jdbc, ObjectMapper mapper,
                         @org.springframework.beans.factory.annotation.Value("${spevb.admin.correos:}") String adminCorreos) {
        this.admins = java.util.Arrays.stream(adminCorreos.split("[,;\\s]+")).map(String::toLowerCase).filter(c -> !c.isBlank()).collect(java.util.stream.Collectors.toUnmodifiableSet());
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.datos = new DatosUsuario(mapper);
    }

    /** Solo es administrador quien tiene su correo en ADMIN_CORREOS Y lo verificó con Google (nadie puede «adelantarse» registrando ese correo). */
    public boolean esAdmin(String correo, boolean verificado) { return verificado && correo != null && admins.contains(correo.toLowerCase()); }
    public boolean adminConfigurado() { return !admins.isEmpty(); }

    public record Usuario(long id, String nombre, String correo, boolean tieneClave, boolean conGoogle, boolean admin) {}

    // ------------------------------------------------------------------ validaciones
    public static String normalizarCorreo(String correo) {
        String c = correo == null ? "" : correo.strip().toLowerCase();
        if (c.length() > 120 || !CORREO.matcher(c).matches()) throw new IllegalArgumentException("Escribe un correo válido.");
        return c;
    }

    public static void validarClave(String clave) {
        if (clave == null || clave.length() < 8) throw new IllegalArgumentException("La contraseña debe tener al menos 8 caracteres.");
        if (clave.length() > 128) throw new IllegalArgumentException("La contraseña es demasiado larga (máximo 128 caracteres).");
        if (clave.chars().allMatch(Character::isDigit)) throw new IllegalArgumentException("La contraseña no puede ser solo números.");
    }

    private static String nombreLimpio(String nombre) {
        String n = nombre == null ? "" : nombre.replaceAll("[\\p{Cntrl}]", " ").strip();
        if (n.length() < 2) throw new IllegalArgumentException("Escribe tu nombre (mínimo 2 letras).");
        return n.length() > 60 ? n.substring(0, 60) : n;
    }

    // ------------------------------------------------------------------ cuentas
    public Usuario registrar(String nombre, String correo, String clave) {
        String c = normalizarCorreo(correo), n = nombreLimpio(nombre);
        validarClave(clave);
        Long yaExiste = jdbc.queryForObject("SELECT COUNT(*) FROM usuarios WHERE LOWER(correo) = ?", Long.class, c);
        if (yaExiste != null && yaExiste > 0) throw new ResponseStatusException(HttpStatus.CONFLICT, "Ese correo ya tiene una cuenta. Inicia sesión.");
        try {
            Long id = jdbc.queryForObject("INSERT INTO usuarios (correo, nombre, clave_hash) VALUES (?, ?, ?) RETURNING id", Long.class, c, n, ClaveHash.hacer(clave));
            return new Usuario(id, n, c, true, false, false);
        } catch (org.springframework.dao.DuplicateKeyException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Ese correo ya tiene una cuenta. Inicia sesión.");
        }
    }

    /** Devuelve el usuario si la clave es correcta; siempre tarda lo mismo exista o no el correo. */
    public Optional<Usuario> autenticar(String correo, String clave) {
        String c = correo == null ? "" : correo.strip().toLowerCase();
        List<Map<String, Object>> filas = c.length() > 120 ? List.of()
                : jdbc.queryForList("SELECT id, nombre, correo, clave_hash, google_sub, correo_verificado FROM usuarios WHERE LOWER(correo) = ?", c);
        // Una cuenta creada con Google puede no tener contraseña: se trata igual que un correo inexistente (mismo mensaje y tiempo).
        String hash = filas.isEmpty() || filas.get(0).get("clave_hash") == null ? HASH_RELLENO : (String) filas.get(0).get("clave_hash");
        boolean ok = clave != null && clave.length() <= 128 && ClaveHash.verificar(clave, hash);
        if (!ok || filas.isEmpty() || filas.get(0).get("clave_hash") == null) return Optional.empty();
        var f = filas.get(0);
        jdbc.update("UPDATE usuarios SET ultimo_acceso = NOW() WHERE id = ?", f.get("id"));
        return Optional.of(new Usuario(((Number) f.get("id")).longValue(), (String) f.get("nombre"), (String) f.get("correo"), true, f.get("google_sub") != null,
                esAdmin((String) f.get("correo"), Boolean.TRUE.equals(f.get("correo_verificado")))));
    }

    /**
     * Entra (o crea la cuenta) con una identidad ya verificada por Google.
     * · Si el «sub» ya existe: es esa cuenta.
     * · Si el correo existe sin Google: se vincula. Como el correo de las cuentas con contraseña no se verifica, quien lo verificó
     *   (Google) es el dueño real: se anula la contraseña anterior y se cierran sus sesiones (evita que alguien se haya
     *   «adelantado» creando una cuenta con el correo de otra persona).
     * · Si no existe: se crea la cuenta (sin contraseña).
     */
    public Usuario entrarConGoogle(GoogleTokenVerifier.Identidad g) {
        List<Map<String, Object>> porSub = jdbc.queryForList("SELECT id, nombre, correo, clave_hash FROM usuarios WHERE google_sub = ?", g.sub());
        if (!porSub.isEmpty()) {
            var f = porSub.get(0);
            jdbc.update("UPDATE usuarios SET ultimo_acceso = NOW() WHERE id = ?", f.get("id"));
            return new Usuario(((Number) f.get("id")).longValue(), (String) f.get("nombre"), (String) f.get("correo"), f.get("clave_hash") != null, true, esAdmin((String) f.get("correo"), true));
        }
        List<Map<String, Object>> porCorreo = jdbc.queryForList("SELECT id, nombre, correo, correo_verificado, clave_hash FROM usuarios WHERE LOWER(correo) = ?", g.correo());
        if (!porCorreo.isEmpty()) {
            var f = porCorreo.get(0);
            long id = ((Number) f.get("id")).longValue();
            boolean verificada = Boolean.TRUE.equals(f.get("correo_verificado"));
            if (verificada) {
                jdbc.update("UPDATE usuarios SET google_sub = ?, ultimo_acceso = NOW() WHERE id = ?", g.sub(), id);
            } else {
                // Nombre y datos los puso quien creó esa cuenta sin verificar el correo: se reemplazan por los de Google y se descartan.
                jdbc.update("UPDATE usuarios SET google_sub = ?, correo_verificado = TRUE, clave_hash = NULL, nombre = ?, ultimo_acceso = NOW() WHERE id = ?", g.sub(), g.nombre(), id);
                jdbc.update("DELETE FROM sesiones WHERE usuario_id = ?", id);
                jdbc.update("DELETE FROM usuario_datos WHERE usuario_id = ?", id);
            }
            return new Usuario(id, verificada ? (String) f.get("nombre") : g.nombre(), (String) f.get("correo"), verificada && f.get("clave_hash") != null, true, esAdmin((String) f.get("correo"), true));
        }
        try {
            Long id = jdbc.queryForObject("INSERT INTO usuarios (correo, nombre, clave_hash, google_sub, correo_verificado, ultimo_acceso) VALUES (?, ?, NULL, ?, TRUE, NOW()) RETURNING id",
                    Long.class, g.correo(), g.nombre(), g.sub());
            return new Usuario(id, g.nombre(), g.correo(), false, true, esAdmin(g.correo(), true));
        } catch (org.springframework.dao.DuplicateKeyException e) {
            return entrarConGoogle(g);       // otra solicitud lo creó justo antes
        }
    }

    public void cambiarClave(long id, String actual, String nueva) {
        validarClave(nueva);
        String hash = jdbc.queryForObject("SELECT clave_hash FROM usuarios WHERE id = ?", String.class, id);
        // Una cuenta de Google sin contraseña puede crear una (la sesión ya la abrió Google); si ya tiene, se pide la actual.
        if (hash != null && (actual == null || !ClaveHash.verificar(actual, hash))) throw new IllegalArgumentException("La contraseña actual no es correcta.");
        jdbc.update("UPDATE usuarios SET clave_hash = ? WHERE id = ?", ClaveHash.hacer(nueva), id);
    }

    /** Borra la cuenta y todo lo suyo. Pide la contraseña o, si la cuenta solo usa Google, que se escriba el correo de la cuenta. */
    public void eliminar(long id, String confirmacion) {
        Map<String, Object> f = jdbc.queryForMap("SELECT clave_hash, correo FROM usuarios WHERE id = ?", id);
        if (f.get("clave_hash") != null) {
            if (confirmacion == null || !ClaveHash.verificar(confirmacion, (String) f.get("clave_hash"))) throw new IllegalArgumentException("La contraseña no es correcta.");
        } else if (confirmacion == null || !confirmacion.strip().equalsIgnoreCase((String) f.get("correo"))) {
            throw new IllegalArgumentException("Escribe el correo de tu cuenta para confirmar.");
        }
        jdbc.update("DELETE FROM usuarios WHERE id = ?", id);
    }

    // ------------------------------------------------------------------ sesiones
    private static String hashToken(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }

    public String abrirSesion(long usuarioId, String agente) {
        byte[] b = new byte[32];
        azar.nextBytes(b);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(b);
        jdbc.update("DELETE FROM sesiones WHERE expira_en < NOW()");
        jdbc.update("""
                DELETE FROM sesiones WHERE usuario_id = ? AND id NOT IN (
                    SELECT id FROM sesiones WHERE usuario_id = ? ORDER BY vista_en DESC LIMIT ?)""", usuarioId, usuarioId, MAX_SESIONES - 1);
        jdbc.update("INSERT INTO sesiones (usuario_id, token_hash, agente, expira_en) VALUES (?, ?, ?, NOW() + make_interval(days => ?))",
                usuarioId, hashToken(token), agente == null ? null : (agente.length() > 160 ? agente.substring(0, 160) : agente), DIAS_SESION);
        return token;
    }

    public Optional<Usuario> usuarioDeSesion(String token) {
        if (token == null || token.length() < 20 || token.length() > 100) return Optional.empty();
        List<Map<String, Object>> filas = jdbc.queryForList("""
                SELECT u.id, u.nombre, u.correo, (u.clave_hash IS NOT NULL) AS tiene_clave, (u.google_sub IS NOT NULL) AS con_google, u.correo_verificado, s.vista_en < NOW() - INTERVAL '1 day' AS renovar
                FROM sesiones s JOIN usuarios u ON u.id = s.usuario_id
                WHERE s.token_hash = ? AND s.expira_en > NOW()""", hashToken(token));
        if (filas.isEmpty()) return Optional.empty();
        var f = filas.get(0);
        if (Boolean.TRUE.equals(f.get("renovar")))
            jdbc.update("UPDATE sesiones SET vista_en = NOW(), expira_en = NOW() + make_interval(days => ?) WHERE token_hash = ?", DIAS_SESION, hashToken(token));
        return Optional.of(new Usuario(((Number) f.get("id")).longValue(), (String) f.get("nombre"), (String) f.get("correo"),
                Boolean.TRUE.equals(f.get("tiene_clave")), Boolean.TRUE.equals(f.get("con_google")),
                esAdmin((String) f.get("correo"), Boolean.TRUE.equals(f.get("correo_verificado")))));
    }

    public void cerrarSesion(String token) {
        if (token != null) jdbc.update("DELETE FROM sesiones WHERE token_hash = ?", hashToken(token));
    }

    public void cerrarTodas(long usuarioId) { jdbc.update("DELETE FROM sesiones WHERE usuario_id = ?", usuarioId); }

    // ------------------------------------------------------------------ datos sincronizados
    public Map<String, Object> leerDatos(long usuarioId) {
        Map<String, Object> salida = new LinkedHashMap<>();
        for (Map<String, Object> f : jdbc.queryForList("SELECT clave, valor::text AS valor, (EXTRACT(EPOCH FROM actualizado_en) * 1000)::bigint AS t FROM usuario_datos WHERE usuario_id = ?", usuarioId)) {
            salida.put((String) f.get("clave"), Map.of("valor", mapper.readTree((String) f.get("valor")), "actualizadoEn", f.get("t")));
        }
        return salida;
    }

    public void guardarDatos(long usuarioId, String clave, JsonNode valor) {
        var limpio = datos.limpiar(clave, valor);
        jdbc.update("""
                INSERT INTO usuario_datos (usuario_id, clave, valor, actualizado_en) VALUES (?, ?, ?::jsonb, NOW())
                ON CONFLICT (usuario_id, clave) DO UPDATE SET valor = EXCLUDED.valor, actualizado_en = NOW()""",
                usuarioId, clave, mapper.writeValueAsString(limpio));
    }
}
