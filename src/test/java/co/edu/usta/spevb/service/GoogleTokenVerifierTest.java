package co.edu.usta.spevb.service;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.util.Base64;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class GoogleTokenVerifierTest {
    private static final String CLIENT = "123-abc.apps.googleusercontent.com";
    private static final long AHORA = 1_800_000_000L;
    private static final ObjectMapper M = new ObjectMapper();
    private static KeyPair par, otro;

    @BeforeAll
    static void claves() throws Exception {
        var g = KeyPairGenerator.getInstance("RSA");
        g.initialize(2048);
        par = g.generateKeyPair();
        otro = g.generateKeyPair();
    }

    private static String b64(byte[] b) { return Base64.getUrlEncoder().withoutPadding().encodeToString(b); }

    private static String token(KeyPair k, Map<String, Object> extra) throws Exception {
        var c = new java.util.LinkedHashMap<String, Object>(Map.of("iss", "https://accounts.google.com", "aud", CLIENT, "sub", "1098765",
                "email", "Ana@Gmail.com", "email_verified", true, "name", "Ana Pérez", "iat", AHORA - 10, "exp", AHORA + 3000));
        c.putAll(extra);
        c.values().removeIf(v -> v == null);
        String cuerpo = b64(M.writeValueAsBytes(Map.of("alg", "RS256", "kid", "k1"))) + "." + b64(M.writeValueAsBytes(c));
        Signature s = Signature.getInstance("SHA256withRSA");
        s.initSign(k.getPrivate());
        s.update(cuerpo.getBytes(StandardCharsets.US_ASCII));
        return cuerpo + "." + b64(s.sign());
    }

    private GoogleTokenVerifier verificador() {
        return new GoogleTokenVerifier(M, CLIENT, kid -> "k1".equals(kid) ? par.getPublic() : null);
    }

    private void rechaza(String token, String contiene) {
        var e = assertThrows(GoogleTokenVerifier.TokenInvalido.class, () -> verificador().verificar(token, AHORA));
        assertTrue(e.getMessage().toLowerCase().contains(contiene.toLowerCase()), e.getMessage());
    }

    @Test
    void aceptaUnTokenValido() throws Exception {
        var id = verificador().verificar(token(par, Map.of()), AHORA);
        assertEquals("1098765", id.sub());
        assertEquals("ana@gmail.com", id.correo());
        assertEquals("Ana Pérez", id.nombre());
    }

    @Test
    void rechazaFirmaAjenaAlteradaYAlgoritmoNone() throws Exception {
        rechaza(token(otro, Map.of()), "firma");
        String t = token(par, Map.of());
        String[] p = t.split("\\.");
        String alterado = p[0] + "." + b64(M.writeValueAsBytes(Map.of("iss", "https://accounts.google.com", "aud", CLIENT, "sub", "999", "email", "otro@x.com",
                "email_verified", true, "iat", AHORA, "exp", AHORA + 3000))) + "." + p[2];
        rechaza(alterado, "firma");
        String none = b64(M.writeValueAsBytes(Map.of("alg", "none"))) + "." + p[1] + ".";
        rechaza(none, "no válida");
        rechaza("basura", "no válida");
        rechaza(null, "no válida");
    }

    @Test
    void rechazaEmisorAudienciaVencidoYCorreoSinVerificar() throws Exception {
        rechaza(token(par, Map.of("iss", "https://evil.com")), "Google");
        rechaza(token(par, Map.of("aud", "otra-app.apps.googleusercontent.com")), "esta aplicación");
        rechaza(token(par, Map.of("exp", AHORA - 3600)), "venció");
        rechaza(token(par, Map.of("email_verified", false)), "verificado");
        rechaza(token(par, Map.of("sub", "")), "incompleta");
    }

    @Test
    void aceptaAudienciaEnListaYNombreVacioUsaElCorreo() throws Exception {
        var id = verificador().verificar(token(par, Map.of("aud", java.util.List.of("x", CLIENT), "name", "")), AHORA);
        assertEquals("ana", id.nombre());
    }

    @Test
    void sinClientIdConfiguradoNadaEntra() throws Exception {
        var v = new GoogleTokenVerifier(M, "", kid -> par.getPublic());
        assertFalse(v.activo());
        assertThrows(GoogleTokenVerifier.TokenInvalido.class, () -> v.verificar(token(par, Map.of()), AHORA));
    }
}
