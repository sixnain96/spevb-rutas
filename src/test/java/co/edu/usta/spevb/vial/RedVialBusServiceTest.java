package co.edu.usta.spevb.vial;

import co.edu.usta.spevb.vial.RedVialBusService.Fase;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static co.edu.usta.spevb.vial.Sinteticas.*;
import static org.junit.jupiter.api.Assertions.*;

class RedVialBusServiceTest {
    private final tools.jackson.databind.ObjectMapper mapper = JsonMapper.builder().build();

    private static List<ViaOsm> vias() {
        return List.of(recta(1, 0, 0, 400, 0, 100, "highway", "residential", "name", "Calle \"Uno\"", "oneway", "yes"),
                recta(2, 0, 10, 400, 10, 100, "highway", "footway"),
                recta(3, 400, 0, 400, 300, 100, "highway", "tertiary", "access", "no", "bus", "designated"));
    }

    @Test void elAvisoEsElTextoFijoYNoHablaDeAutorizacionOficial() {
        assertEquals("Vía transitable según la red vial disponible. Requiere revisión del equipo", RedVialBusService.AVISO);
        assertFalse(RedVialBusService.AVISO.toLowerCase().contains("autorizad"));
    }

    @Test void cargaDesdeElProveedorInyectadoSinDescargarNada(@TempDir Path dir) {
        var s = new RedVialBusService(true, dir.resolve("red-vial-bus.json.gz"), 60, 30, () -> vias(), mapper);
        assertNull(s.red());
        assertFalse(s.lista());
        assertEquals(Fase.NO_DISPONIBLE, s.estado().estado());
        s.cargarTodo();
        assertTrue(s.lista());
        assertEquals(Fase.LISTA, s.estado().estado());
        assertEquals(s.red().nodos(), s.estado().nodos());
        assertEquals(s.red().aristas(), s.estado().aristas());
        assertEquals(2, s.red().viasAceptadas(), "el footway no entra en la red de buses");
        assertEquals(RedVialBusService.AVISO, s.estado().aviso());
        assertNotNull(s.matcher());
        assertEquals(30, s.opcionesPorDefecto().radioMetros());
        assertFalse(s.externo().activo());
    }

    @Test void guardaCachePropiaYLaReutilizaSinVolverAPedirLasVias(@TempDir Path dir) {
        Path archivo = dir.resolve("data").resolve("red-vial-bus.json.gz");
        AtomicInteger pedidos = new AtomicInteger();
        var s1 = new RedVialBusService(true, archivo, 60, 30, () -> { pedidos.incrementAndGet(); return vias(); }, mapper);
        s1.cargarTodo();
        assertTrue(Files.exists(archivo));
        assertFalse(archivo.getFileName().toString().contains("peatonal"), "caché distinta de la red peatonal");
        assertEquals(1, pedidos.get());
        var s2 = new RedVialBusService(true, archivo, 60, 30, () -> { pedidos.incrementAndGet(); return List.of(); }, mapper);
        s2.cargarTodo();
        assertEquals(1, pedidos.get(), "la caché vigente no se vuelve a descargar");
        assertTrue(s2.lista());
        assertEquals(s1.red().nodos(), s2.red().nodos());
        assertEquals(s1.red().aristas(), s2.red().aristas());
        // el sentido y los acuerdos de acceso sobreviven a la caché (nombre con comillas incluido)
        var c = s2.red().candidatos(p(150, 3)[0], p(150, 3)[1], 30, 5);
        assertEquals("Calle \"Uno\"", c.get(0).nombre());
        assertEquals(ReglasBus.Sentido.SOLO_ADELANTE, c.get(0).sentido());
        assertEquals(1, s2.red().candidatos(p(400, 150)[0], p(400, 150)[1], 30, 5).size(), "access=no + bus=designated sigue transitable");
    }

    @Test void fallaDelProveedorSinCacheQuedaNoDisponibleYNoLanzaExcepcion(@TempDir Path dir) {
        var s = new RedVialBusService(true, dir.resolve("x.json.gz"), 60, 30, () -> { throw new java.io.IOException("sin Internet"); }, mapper);
        assertDoesNotThrow(s::cargarTodo);
        assertEquals(Fase.NO_DISPONIBLE, s.estado().estado());
        assertNull(s.red());
        assertTrue(s.estado().detalle().contains("sin Internet"));
    }

    @Test void deshabilitadaNoCargaNiLlamaAlProveedor(@TempDir Path dir) {
        AtomicInteger pedidos = new AtomicInteger();
        var s = new RedVialBusService(false, dir.resolve("x.json.gz"), 60, 30, () -> { pedidos.incrementAndGet(); return vias(); }, mapper);
        s.alIniciar();
        assertEquals(0, pedidos.get());
        assertEquals(Fase.NO_DISPONIBLE, s.estado().estado());
        assertFalse(s.configurada());
    }

    @Test void siFallaElRefrescoSeConservaLaRedAnterior(@TempDir Path dir) throws Exception {
        Path archivo = dir.resolve("red.json.gz");
        new RedVialBusService(true, archivo, 60, 30, () -> vias(), mapper).cargarTodo();
        Files.setLastModifiedTime(archivo, java.nio.file.attribute.FileTime.from(java.time.Instant.now().minus(java.time.Duration.ofDays(90))));
        var s = new RedVialBusService(true, archivo, 60, 30, () -> { throw new IllegalStateException("overpass caído"); }, mapper);
        s.cargarTodo();
        assertTrue(s.lista());
        assertEquals(Fase.LISTA, s.estado().estado());
        assertTrue(s.estado().detalle().contains("Se conserva la red anterior"));
    }

    @Test void interpretaElFormatoDeOverpassOutGeom() {
        String json = """
                {"elements":[
                 {"type":"way","id":5,"nodes":[1,2],"tags":{"highway":"residential","name":"Cra 5","oneway":"yes","foo":"x"},
                  "geometry":[{"lat":4.15,"lon":-73.63},{"lat":4.151,"lon":-73.63}]},
                 {"type":"node","id":6},
                 {"type":"way","id":7,"tags":{"name":"sin highway"},"geometry":[{"lat":4.15,"lon":-73.63},{"lat":4.151,"lon":-73.63}]}
                ]}""";
        var s = new RedVialBusService(false, Path.of("data/no-usado"), 60, 30, () -> List.of(), mapper);
        var v = s.leerVias(new java.io.ByteArrayInputStream(json.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        assertEquals(1, v.size());
        assertEquals(5, v.get(0).id());
        assertEquals("yes", v.get(0).tags().get("oneway"));
        assertFalse(v.get(0).tags().containsKey("foo"), "solo se guardan las etiquetas relevantes");
        assertEquals(2, v.get(0).coordenadas().size());
    }
}
