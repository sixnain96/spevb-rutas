package co.edu.usta.spevb.service;

import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

@Component
public class GeometriaValidator {
    public void validar(JsonNode geometry) {
        if (geometry == null || !geometry.isObject() ||
                !"LineString".equals(geometry.path("type").asText())) {
            throw new IllegalArgumentException("El recorrido debe ser una geometría GeoJSON LineString.");
        }
        JsonNode coordinates = geometry.path("coordinates");
        if (!coordinates.isArray() || coordinates.size() < 2 || coordinates.size() > 10000) {
            throw new IllegalArgumentException("El recorrido debe contener entre 2 y 10.000 puntos.");
        }
        double previousLng = Double.NaN, previousLat = Double.NaN;
        for (JsonNode point : coordinates) {
            if (!point.isArray() || point.size() != 2 || !point.get(0).isNumber() || !point.get(1).isNumber()) {
                throw new IllegalArgumentException("Cada punto debe contener exactamente [longitud, latitud] numéricas.");
            }
            double lng = point.get(0).asDouble(), lat = point.get(1).asDouble();
            if (!Double.isFinite(lng) || !Double.isFinite(lat) || lng < -180 || lng > 180 || lat < -90 || lat > 90) {
                throw new IllegalArgumentException("Coordenadas fuera de rango: longitud ±180 y latitud ±90.");
            }
            if (lng == previousLng && lat == previousLat) {
                throw new IllegalArgumentException("El recorrido contiene puntos consecutivos duplicados.");
            }
            previousLng = lng;
            previousLat = lat;
        }
        if (geometry.has("crs")) {
            throw new IllegalArgumentException("Usa coordenadas WGS84; no incluyas un CRS alternativo.");
        }
    }
}
