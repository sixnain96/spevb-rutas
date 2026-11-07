package co.edu.usta.spevb.config;

import java.util.regex.Pattern;

/** Quita de los textos de registro los datos personales: correos, pares de coordenadas y tokens largos. */
public final class LogPrivado {
    private static final Pattern CORREO = Pattern.compile("([A-Za-z0-9._%+-])[A-Za-z0-9._%+-]*@([A-Za-z0-9.-]+\\.[A-Za-z]{2,})");
    private static final Pattern COORDENADAS = Pattern.compile("-?\\d{1,3}\\.\\d{3,}\\s*[,;]\\s*-?\\d{1,3}\\.\\d{3,}");
    private static final Pattern TOKEN = Pattern.compile("\\b[A-Za-z0-9_\\-]{32,}\\b");

    private LogPrivado() {}

    public static String limpiar(String texto) {
        if (texto == null) return "";
        String r = CORREO.matcher(texto).replaceAll("$1***@$2");
        r = COORDENADAS.matcher(r).replaceAll("<coordenadas>");
        return TOKEN.matcher(r).replaceAll("<token>");
    }

    /** Resumen seguro de una excepción para el registro: clase y mensaje depurado (sin traza, que puede incluir datos de la consulta). */
    public static String resumen(Throwable ex) {
        return ex == null ? "" : ex.getClass().getSimpleName() + ": " + limpiar(ex.getMessage());
    }
}
