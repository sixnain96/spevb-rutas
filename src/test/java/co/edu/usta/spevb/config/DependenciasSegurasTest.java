package co.edu.usta.spevb.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Las versiones de Tomcat y Jackson fijadas en pom.xml por vulnerabilidades conocidas no pueden retroceder (comprobado en el classpath real). */
class DependenciasSegurasTest {
    private static int[] version(String v) {
        var partes = v.replaceAll("[^0-9.].*$", "").split("\\.");
        int[] r = new int[3];
        for (int i = 0; i < 3 && i < partes.length; i++) r[i] = Integer.parseInt(partes[i]);
        return r;
    }

    private static boolean esAlMenos(String actual, String minimo) {
        int[] a = version(actual), m = version(minimo);
        for (int i = 0; i < 3; i++) if (a[i] != m[i]) return a[i] > m[i];
        return true;
    }

    @Test void tomcatEsAlMenos11_0_25() {
        String v = org.apache.catalina.util.ServerInfo.getServerNumber();
        assertTrue(esAlMenos(v, "11.0.25"), "Tomcat embebido: " + v);
    }

    @Test void jacksonEsAlMenos3_1_7() {
        assertTrue(esAlMenos(tools.jackson.core.json.PackageVersion.VERSION.toString(), "3.1.7"), "jackson-core: " + tools.jackson.core.json.PackageVersion.VERSION);
        assertTrue(esAlMenos(tools.jackson.databind.cfg.PackageVersion.VERSION.toString(), "3.1.7"), "jackson-databind: " + tools.jackson.databind.cfg.PackageVersion.VERSION);
    }
}
