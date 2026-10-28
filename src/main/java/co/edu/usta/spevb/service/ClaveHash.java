package co.edu.usta.spevb.service;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/** Contraseñas con PBKDF2-HMAC-SHA256 (incluido en Java): sal aleatoria por clave y comparación en tiempo constante. */
public final class ClaveHash {
    public static final int ITERACIONES = 600_000;       // recomendación de OWASP para PBKDF2-HMAC-SHA256
    private static final SecureRandom AZAR = new SecureRandom();
    private ClaveHash() {}

    public static String hacer(String clave) { return hacer(clave, ITERACIONES); }

    static String hacer(String clave, int iteraciones) {
        byte[] sal = new byte[16];
        AZAR.nextBytes(sal);
        return "pbkdf2$" + iteraciones + "$" + b64(sal) + "$" + b64(derivar(clave, sal, iteraciones));
    }

    public static boolean verificar(String clave, String guardado) {
        try {
            String[] p = guardado.split("\\$");
            if (p.length != 4 || !p[0].equals("pbkdf2")) return false;
            int it = Integer.parseInt(p[1]);
            byte[] esperado = Base64.getUrlDecoder().decode(p[3]);
            return MessageDigest.isEqual(esperado, derivar(clave, Base64.getUrlDecoder().decode(p[2]), it));
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static byte[] derivar(String clave, byte[] sal, int iteraciones) {
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                    .generateSecret(new PBEKeySpec(clave.toCharArray(), sal, iteraciones, 256)).getEncoded();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("PBKDF2 no disponible", e);
        }
    }

    private static String b64(byte[] b) { return Base64.getUrlEncoder().withoutPadding().encodeToString(b); }
}
