package co.edu.usta.spevb.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;

class GeometriaValidatorTest {
    private final GeometriaValidator validator = new GeometriaValidator();
    private final JsonMapper mapper = JsonMapper.builder().build();

    @Test void acceptsWgs84LineString() {
        assertDoesNotThrow(() -> validator.validar(mapper.readTree("""
                {"type":"LineString","coordinates":[[-73.62,4.14],[-73.63,4.15]]}
                """)));
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "null", "{}", "{\"type\":\"Point\",\"coordinates\":[0,0]}",
        "{\"type\":\"LineString\",\"coordinates\":[]}",
        "{\"type\":\"LineString\",\"coordinates\":[[0,0]]}",
        "{\"type\":\"LineString\",\"coordinates\":[[0,0],[181,0]]}",
        "{\"type\":\"LineString\",\"coordinates\":[[0,0],[0,91]]}",
        "{\"type\":\"LineString\",\"coordinates\":[[0,0],[0,0]]}",
        "{\"type\":\"LineString\",\"coordinates\":[[0,0],[1,2,3]]}",
        "{\"type\":\"LineString\",\"coordinates\":[[0,0],[\"1\",2]]}",
        "{\"type\":\"LineString\",\"coordinates\":[[0,0],[1,2]],\"crs\":{}}"
    }) void rejectsInvalidGeometries(String json) {
        assertThrows(IllegalArgumentException.class, () -> validator.validar(mapper.readTree(json)));
    }

    @Test void rejectsOversizedGeometry() {
        var root = mapper.createObjectNode(); root.put("type", "LineString");
        var coordinates = root.putArray("coordinates");
        for (int i = 0; i < 10001; i++) coordinates.addArray().add(i / 10000.0).add(4);
        assertThrows(IllegalArgumentException.class, () -> validator.validar(root));
    }
}
