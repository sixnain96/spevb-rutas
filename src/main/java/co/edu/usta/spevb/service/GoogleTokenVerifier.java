package co.edu.usta.spevb.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.math.BigInteger;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.RSAPublicKeySpec;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * Verifica el «ID token» que entrega Google al iniciar sesión: firma RS256 con las claves públicas de Google (JWKS),
 * emisor, destinatario (nuestro Client ID), vigencia y correo verificado. No se envía nada a Google desde el servidor
 * salvo la descarga (con caché) de las claves públicas.
 */
@Component
public class GoogleTokenVerifier {
    public record Identidad(String sub, String correo, String nombre) {}
    public static class TokenInvalido extends RuntimeException { public TokenInvalido(String m) { super(m); } }

    private static final Set<String> EMISORES = Set.of("https://accounts.google.com", "accounts.google.com");
    private static final long TOLERANCIA_S = 60;
    private final ObjectMapper mapper;
    private final String clientId;
    private final Function<String, PublicKey> claves;       // kid → clave pública (se puede sustituir en pruebas)

    @Autowired
    public GoogleTokenVerifier(ObjectMapper mapper, @Value("${spevb.google.client-id:}") String clientId,
                               @Value("${spevb.google.jwks-url:https://www.googleapis.com/oauth2/v3/certs}") String jwksUrl) {
        this(mapper, clientId, new JwksCache(mapper, jwksUrl)::clave);
    }

    public GoogleTokenVerifier(ObjectMapper mapper, String clientId, Function<String, PublicKey> claves) {
        this.mapper = mapper;
        this.clientId = clientId == null ? "" : clientId.strip();
        this.claves = claves;
    }

    public boolean activo() { return !clientId.isEmpty(); }
    public String clientId() { return clientId; }

    public Identidad verificar(String token) { return verificar(token, System.currentTimeMillis() / 1000); }

    Identidad verificar(String token, long ahoraS) {
        if (!activo()) throw new TokenInvalido("El inicio de sesión con Google no está configurado en este servidor.");
        if (token == null || token.length() > 4096) throw new TokenInvalido("Credencial de Google no válida.");
        String[] p = token.split("\\.");
        if (p.length != 3) throw new TokenInvalido("Credencial de Google no válida.");
        try {
            JsonNode cabecera = mapper.readTree(decodificar(p[0])), c = mapper.readTree(decodificar(p[1]));
            if (!"RS256".equals(cabecera.path("alg").asString(""))) throw new TokenInvalido("Credencial de Google no válida.");
            PublicKey clave = claves.apply(cabecera.path("kid").asString(""));
            if (clave == null) throw new TokenInvalido("No se pudo comprobar la credencial de Google. Inténtalo de nuevo.");
            Signature firma = Signature.getInstance("SHA256withRSA");
            firma.initVerify(clave);
            firma.update((p[0] + "." + p[1]).getBytes(StandardCharsets.US_ASCII));
            if (!firma.verify(Base64.getUrlDecoder().decode(p[2]))) throw new TokenInvalido("La firma de la credencial de Google no es válida.");

            if (!EMISORES.contains(c.path("iss").asString(""))) throw new TokenInvalido("La credencial no viene de Google.");
            if (!audiencia(c.get("aud"))) throw new TokenInvalido("La credencial no es para esta aplicación.");
            long exp = c.path("exp").asLong(0), iat = c.path("iat").asLong(0);
            if (exp + TOLERANCIA_S < ahoraS) throw new TokenInvalido("La credencial de Google venció. Inténtalo de nuevo.");
            if (iat > ahoraS + TOLERANCIA_S * 5) throw new TokenInvalido("Credencial de Google no válida.");
            String sub = c.path("sub").asString(""), correo = c.path("email").asString("").strip().toLowerCase();
            boolean verificado = c.path("email_verified").asBoolean(false) || "true".equals(c.path("email_verified").asString(""));
            if (sub.isEmpty() || sub.length() > 40 || correo.isEmpty()) throw new TokenInvalido("Credencial de Google incompleta.");
            if (!verificado) throw new TokenInvalido("Tu correo de Google no está verificado.");
            String nombre = c.path("name").asString("").replaceAll("[\\p{Cntrl}]", " ").strip();
            if (nombre.length() < 2) nombre = correo.substring(0, correo.indexOf('@'));
            return new Identidad(sub, correo, nombre.length() > 60 ? nombre.substring(0, 60) : nombre);
        } catch (TokenInvalido e) {
            throw e;
        } catch (Exception e) {
            throw new TokenInvalido("Credencial de Google no válida.");
        }
    }

    private boolean audiencia(JsonNode aud) {
        if (aud == null) return false;
        if (aud.isArray()) { for (JsonNode a : aud) if (clientId.equals(a.asString(""))) return true; return false; }
        return clientId.equals(aud.asString(""));
    }

    private static byte[] decodificar(String b64) { return Base64.getUrlDecoder().decode(b64); }

    /** Claves públicas de Google: se descargan una vez y se guardan según la caché que indica Google (máx. 12 h). */
    static final class JwksCache {
        private final ObjectMapper mapper;
        private final String url;
        private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        private final Map<String, PublicKey> claves = new ConcurrentHashMap<>();
        private volatile long venceMs = 0, ultimoIntentoMs = 0;

        JwksCache(ObjectMapper mapper, String url) { this.mapper = mapper; this.url = url; }

        PublicKey clave(String kid) {
            long ahora = System.currentTimeMillis();
            PublicKey k = claves.get(kid);
            if (k != null && ahora < venceMs) return k;
            // Descarga si venció la caché o llegó una clave desconocida (Google rota sus claves), sin insistir más de una vez por minuto.
            if (ahora - ultimoIntentoMs > 60_000) { ultimoIntentoMs = ahora; descargar(); }
            return claves.get(kid);
        }

        private synchronized void descargar() {
            try {
                HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(8)).GET().build(), HttpResponse.BodyHandlers.ofString());
                if (r.statusCode() != 200) return;
                long maxAge = r.headers().firstValue("Cache-Control").map(h -> { var m = java.util.regex.Pattern.compile("max-age=(\\d+)").matcher(h); return m.find() ? Long.parseLong(m.group(1)) : 3600L; }).orElse(3600L);
                Map<String, PublicKey> nuevas = new ConcurrentHashMap<>();
                for (JsonNode j : mapper.readTree(r.body()).path("keys")) {
                    if (!"RSA".equals(j.path("kty").asString(""))) continue;
                    nuevas.put(j.path("kid").asString(""), KeyFactory.getInstance("RSA").generatePublic(new RSAPublicKeySpec(
                            new BigInteger(1, Base64.getUrlDecoder().decode(j.path("n").asString(""))),
                            new BigInteger(1, Base64.getUrlDecoder().decode(j.path("e").asString(""))))));
                }
                if (!nuevas.isEmpty()) { claves.clear(); claves.putAll(nuevas); venceMs = System.currentTimeMillis() + Math.min(maxAge, 43_200) * 1000; }
            } catch (Exception ignorado) { /* sin red: se usan las claves ya guardadas */ }
        }
    }
}
