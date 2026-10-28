package co.edu.usta.spevb.service;

import co.edu.usta.spevb.config.CityArea;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.*;

/** Prueba con las calles reales de Villavicencio incluidas como semilla. */
class RedPeatonalSemillaTest {
    private static RedPeatonalService servicio;

    @BeforeAll static void cargar() throws Exception {
        servicio = new RedPeatonalService(false, "data/no-existe.bin.gz", 60, "", 
                new CityArea("Villavicencio", "Meta", "Colombia", 3.95, 4.35, -73.85, -73.45), JsonMapper.builder().build());
        servicio.cargarSemilla();
    }

    @Test void laSemillaCargaUnaRedGrandeYConectada() {
        var r = servicio.getRedParaPruebas();
        assertTrue(r.nodos() > 30_000, "nodos: " + r.nodos());
        assertTrue(r.nodosAccesibles() > r.nodos() * 0.98, "casi toda la red está conectada");
        assertEquals("LISTA", servicio.estado().estado());
    }

    @Test void calle36ConCarrera27HastaAvenidaCatamaSigueLasCalles() {
        // Caso real: A en la Calle 36 con Carrera 27, bus en la Avenida Catama (≈ 130 m al sur).
        var t = servicio.ruta(4.15015, -73.63280, 4.14884, -73.63294).orElseThrow();
        assertTrue(t.metros() > 120 && t.metros() < 200, "metros: " + t.metros());
    }

    @Test void macarenaHastaAvenidaCatamaDaUnCaminoPorCallesNoUnaRecta() {
        double recta = RedPeatonal.metros(4.12872, -73.61654, 4.14884, -73.63294);
        var t = servicio.ruta(4.12872, -73.61654, 4.14884, -73.63294).orElseThrow();
        assertTrue(t.coordenadas().size() > 40, "el camino por calles tiene muchos vértices: " + t.coordenadas().size());
        assertTrue(t.metros() > recta && t.metros() < recta * 1.6, "metros " + t.metros() + " recta " + recta);
    }

    @Test void sinCalleCercaNoSeInventaUnCamino() {
        assertTrue(servicio.ruta(4.3, -73.5, 4.31, -73.5).isEmpty());
    }
}
