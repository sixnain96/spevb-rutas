package co.edu.usta.spevb.service;

import co.edu.usta.spevb.config.CityArea;
import co.edu.usta.spevb.repository.DireccionRepository;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class OsmImportServiceTest {
    private final Holder holder = new Holder();
    static class Holder { final tools.jackson.databind.ObjectMapper mapper = JsonMapper.builder().build(); }

    private final OsmImportService service = new OsmImportService("https://ejemplo.test/api", false, 30,
            mock(DireccionRepository.class), new CityArea("Villavicencio", "Meta", "Colombia", 3.95, 4.35, -73.85, -73.45),
            holder.mapper);

    private tools.jackson.databind.JsonNode json(String s) { return holder.mapper.readTree(s); }

    @Test void namedPlaceBecomesAPoiWithSpanishCategoryAndRelevance() {
        var p = service.convertirLugar(json("{\"type\":\"node\",\"id\":7,\"lat\":4.1421,\"lon\":-73.6267,"
                + "\"tags\":{\"name\":\"Universidad de los Llanos\",\"amenity\":\"university\"}}"));
        assertEquals("OSM", p.fuente()); assertEquals("n7", p.fuenteId());
        assertEquals("universidad de los llanos", p.norm());
        assertEquals("Universidad", p.categoria()); assertEquals(95, p.relevancia());
    }

    @Test void wayUsesItsCenterAndNeighbourhoodsAreRecognised() {
        var p = service.convertirLugar(json("{\"type\":\"way\",\"id\":9,\"center\":{\"lat\":4.15,\"lon\":-73.63},"
                + "\"tags\":{\"name\":\"Barrio San Marcos\",\"place\":\"neighbourhood\"}}"));
        assertEquals("w9", p.fuenteId()); assertEquals("Barrio o sector", p.categoria()); assertEquals(70, p.relevancia());
    }

    @Test void placesOutsideTheCityOrWithoutNameAreIgnored() {
        assertNull(service.convertirLugar(json("{\"type\":\"node\",\"id\":1,\"lat\":10.0,\"lon\":-73.6,\"tags\":{\"name\":\"Lejos\",\"amenity\":\"school\"}}")));
        assertNull(service.convertirLugar(json("{\"type\":\"node\",\"id\":2,\"lat\":4.14,\"lon\":-73.6,\"tags\":{\"amenity\":\"school\"}}")));
        assertNull(service.convertirLugar(json("{\"type\":\"node\",\"id\":3,\"lat\":4.14,\"lon\":-73.6,\"tags\":{\"name\":\"Sin clase\"}}")));
    }

    @Test void osmAddressIsParsedIntoTheSameStructureAsTheCatastro() {
        var d = service.convertirDireccion(json("{\"type\":\"node\",\"id\":5,\"lat\":4.14,\"lon\":-73.63,"
                + "\"tags\":{\"addr:street\":\"Calle 11A\",\"addr:housenumber\":\"36-29\"}}"));
        assertEquals("C", d.tipoVia()); assertEquals("11A", d.via()); assertEquals("36", d.cruce()); assertEquals(29, d.placa());
        assertEquals("DIRECCION_PUNTUAL", d.precision()); assertEquals(90, d.confianza());
    }

    @Test void addressThatIsNotCompleteIsDropped() {
        assertNull(service.convertirDireccion(json("{\"type\":\"node\",\"id\":6,\"lat\":4.14,\"lon\":-73.63,"
                + "\"tags\":{\"addr:street\":\"Avenida Catama\",\"addr:housenumber\":\"12\"}}")));
    }

    @Test void bundledSnapshotIsReadable() throws Exception {
        try (var in = new java.util.zip.GZIPInputStream(getClass().getResourceAsStream("/osm/villavicencio-osm.json.gz"))) {
            var root = holder.mapper.readTree(in.readAllBytes());
            assertTrue(root.path("pois").size() > 1000);
            assertTrue(root.path("lugares").size() > 100);
            assertEquals("2026-10-17", root.path("fecha").asText());
        }
    }
}
