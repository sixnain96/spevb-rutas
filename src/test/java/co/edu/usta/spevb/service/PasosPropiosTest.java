package co.edu.usta.spevb.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PasosPropiosTest {
    private static final double LAT0 = 4.14, LNG0 = -73.63, PASO = 0.001;

    /** Retícula de {@code filas} × columnas [c0, c1] con ids únicos por (fila, columna). */
    private static void retícula(RedPeatonal.Constructor c, int filas, int c0, int c1) {
        for (int i = 0; i < filas; i++) {
            int n = c1 - c0 + 1; long[] ids = new long[n]; double[] la = new double[n], lo = new double[n];
            for (int j = c0; j <= c1; j++) { ids[j - c0] = 1000L * i + j; la[j - c0] = LAT0 + i * PASO; lo[j - c0] = LNG0 + j * PASO; }
            c.agregarCamino(ids, la, lo, RedPeatonal.LLANA);
        }
        for (int j = c0; j <= c1; j++) {
            long[] ids = new long[filas]; double[] la = new double[filas], lo = new double[filas];
            for (int i = 0; i < filas; i++) { ids[i] = 1000L * i + j; la[i] = LAT0 + i * PASO; lo[i] = LNG0 + j * PASO; }
            c.agregarCamino(ids, la, lo, RedPeatonal.LLANA);
        }
    }

    private static boolean hayRuta(RedPeatonal r, double la, double lo, double lb, double lob) {
        var a = r.ajustarVarios(la, lo, 250, 4, 40);
        var b = r.ajustarVarios(lb, lob, 250, 4, 40);
        return !a.isEmpty() && !b.isEmpty() && r.camino(a, b, 20_000) != null;
    }

    /** Dos barrios de 8 × 4 calles separados por un hueco de 2 columnas sin calle (un lote o un caño). */
    private static RedPeatonal.Constructor dosBarrios() {
        var c = new RedPeatonal.Constructor();
        retícula(c, 8, 0, 3);
        retícula(c, 8, 6, 9);
        return c;
    }

    @Test void sinPasoLosDosBarriosNoSeConectan() {
        var r = dosBarrios().construir();
        assertFalse(hayRuta(r, LAT0 + 0.003, LNG0 + 0.003, LAT0 + 0.003, LNG0 + 0.006));
    }

    @Test void unPasoPropioConectaLosBarriosYElCaminoLoUsa() {
        var c = dosBarrios();
        // Un puente/atajo de la esquina (3,3) a la (3,6), con puntos intermedios; extremos a unos 5 m de los nodos.
        int conectados = c.agregarPaso(new double[]{LAT0 + 0.003 + 0.00004, LAT0 + 0.003, LAT0 + 0.003 - 0.00004},
                new double[]{LNG0 + 0.003, LNG0 + 0.0045, LNG0 + 0.006}, 12);
        assertEquals(2, conectados);
        var r = c.construir();
        assertTrue(hayRuta(r, LAT0 + 0.003, LNG0 + 0.0028, LAT0 + 0.003, LNG0 + 0.0062));
        var a = r.ajustarVarios(LAT0 + 0.003, LNG0 + 0.0028, 250, 4, 40);
        var b = r.ajustarVarios(LAT0 + 0.003, LNG0 + 0.0062, 250, 4, 40);
        var camino = r.camino(a, b, 20_000);
        assertTrue(camino.coordenadas().stream().anyMatch(p -> Math.abs(p[0] - (LNG0 + 0.0045)) < 1e-6), "pasa por el punto medio del paso");
    }

    @Test void unPasoConUnExtremoLejosDeCualquierCalleAvisaQueNoQuedoConectado() {
        var c = dosBarrios();
        int conectados = c.agregarPaso(new double[]{LAT0 + 0.003, LAT0 + 0.003}, new double[]{LNG0 + 0.0031, LNG0 + 0.0045}, 12);
        assertEquals(1, conectados, "el segundo extremo cae en el hueco sin calles");
    }

    @Test void unExtremoSobreLaMitadDeUnaCalleLaPartePorEseLugar() {
        var c = new RedPeatonal.Constructor();
        retícula(c, 8, 0, 3);
        retícula(c, 8, 6, 9);
        // Extremos a mitad de cuadra (no hay nodo en el centro de la calle (3,3)-(4,3)).
        int conectados = c.agregarPaso(new double[]{LAT0 + 0.0035, LAT0 + 0.0035},
                new double[]{LNG0 + 0.003, LNG0 + 0.006}, 12);
        assertEquals(2, conectados);
        assertTrue(hayRuta(c.construir(), LAT0 + 0.005, LNG0 + 0.003, LAT0 + 0.005, LNG0 + 0.006));
    }

    @Test void unBloqueoDesviaElCaminoPeroNoLoImpideSiHayOtraCalle() {
        var c = new RedPeatonal.Constructor();
        retícula(c, 8, 0, 7);
        // Se cierra la calle de la fila 3 entre las columnas 2 y 5.
        c.bloquear(new double[]{LAT0 + 0.003, LAT0 + 0.003}, new double[]{LNG0 + 0.0015, LNG0 + 0.0055});
        var r = c.construir();
        var a = r.ajustarVarios(LAT0 + 0.003, LNG0 + 0.0, 100, 1, 0);
        var b = r.ajustarVarios(LAT0 + 0.003, LNG0 + 0.007, 100, 1, 0);
        var camino = r.camino(a, b, 20_000);
        assertNotNull(camino);
        assertTrue(camino.metros() > 7 * PASO * 111_320 * Math.cos(Math.toRadians(LAT0)) + 150, "da la vuelta por otra calle: " + camino.metros());
        for (double[] p : camino.coordenadas()) {
            boolean enCerrada = Math.abs(p[1] - (LAT0 + 0.003)) < 1e-7 && p[0] > LNG0 + 0.0015 && p[0] < LNG0 + 0.0055;
            assertFalse(enCerrada, "no puede pasar por la calle cerrada");
        }
    }

    @Test void distanciaEntreSegmentos() {
        assertEquals(0, RedPeatonal.distanciaSegmentos(0, 0, 10, 10, 0, 10, 10, 0), 1e-9);
        assertEquals(5, RedPeatonal.distanciaSegmentos(0, 0, 10, 0, 0, 5, 10, 5), 1e-9);
    }
}
