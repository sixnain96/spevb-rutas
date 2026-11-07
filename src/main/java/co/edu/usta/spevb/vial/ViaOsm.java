package co.edu.usta.spevb.vial;

import java.util.List;
import java.util.Map;

/**
 * Una vía (way) de OpenStreetMap tal como la necesita la red vial de buses.
 *
 * @param id          identificador OSM de la vía
 * @param tags        etiquetas OSM (highway, oneway, access, bus, ...)
 * @param coordenadas vértices en orden, cada uno como {@code [lat, lng]}
 */
public record ViaOsm(long id, Map<String, String> tags, List<double[]> coordenadas) {}
