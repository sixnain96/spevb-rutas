package co.edu.usta.spevb.repository;

import co.edu.usta.spevb.dto.RutaResumenResponse;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

@Repository
public class RutaRepository {

    private final JdbcTemplate jdbcTemplate;

    public RutaRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<RutaResumenResponse> findAllActivas() {
        String sql = """
                SELECT id, codigo, nombre, origen, destino, sentido, color_hex,
                       ROUND((ST_Length(recorrido::geography) / 1000.0)::numeric, 2) AS distancia_km,
                       activa, validada
                FROM rutas
                WHERE activa = TRUE
                ORDER BY codigo
                """;

        return jdbcTemplate.query(sql, (rs, rowNum) -> new RutaResumenResponse(
                rs.getLong("id"),
                rs.getString("codigo"),
                rs.getString("nombre"),
                rs.getString("origen"),
                rs.getString("destino"),
                rs.getString("sentido"),
                rs.getString("color_hex"),
                rs.getObject("distancia_km", BigDecimal.class),
                rs.getBoolean("activa"),
                rs.getBoolean("validada")
        ));
    }

    public Optional<RutaDetalleRow> findById(Long id) {
        String sql = """
                SELECT id, codigo, nombre, origen, destino, sentido, descripcion, color_hex,
                       ROUND((ST_Length(recorrido::geography) / 1000.0)::numeric, 2) AS distancia_km,
                       activa, demostrativa, validada, fuente,
                       ST_AsGeoJSON(ST_StartPoint(recorrido), 7) AS inicio_geojson,
                       ST_AsGeoJSON(ST_EndPoint(recorrido), 7) AS fin_geojson,
                       ST_AsGeoJSON(recorrido, 7) AS geojson
                FROM rutas
                WHERE id = ?
                """;

        List<RutaDetalleRow> resultados = jdbcTemplate.query(sql, (rs, rowNum) -> new RutaDetalleRow(
                rs.getLong("id"),
                rs.getString("codigo"),
                rs.getString("nombre"),
                rs.getString("origen"),
                rs.getString("destino"),
                rs.getString("sentido"),
                rs.getString("descripcion"),
                rs.getString("color_hex"),
                rs.getObject("distancia_km", BigDecimal.class),
                rs.getBoolean("activa"),
                rs.getBoolean("demostrativa"),
                rs.getBoolean("validada"),
                rs.getString("fuente"),
                rs.getString("inicio_geojson"),
                rs.getString("fin_geojson"),
                rs.getString("geojson")
        ), id);

        return resultados.stream().findFirst();
    }

    public List<ParaderoRow> findParaderos(Long rutaId) {
        String sql = """
                SELECT id, secuencia, nombre, ST_AsGeoJSON(ubicacion, 7) AS geojson
                FROM paraderos
                WHERE ruta_id = ?
                ORDER BY secuencia
                """;
        return jdbcTemplate.query(sql, (rs, rowNum) -> new ParaderoRow(
                rs.getLong("id"),
                rs.getInt("secuencia"),
                rs.getString("nombre"),
                rs.getString("geojson")
        ), rutaId);
    }

    public boolean actualizarRecorrido(Long id, String geojson, String origen, String destino,
                                       String fuente, boolean validada) {
        String sql = """
                UPDATE rutas
                SET recorrido = ST_SetSRID(ST_GeomFromGeoJSON(?), 4326),
                    origen = COALESCE(NULLIF(?, ''), origen),
                    destino = COALESCE(NULLIF(?, ''), destino),
                    fuente = COALESCE(NULLIF(?, ''), fuente),
                    validada = ?,
                    demostrativa = NOT ?,
                    actualizado_en = NOW()
                WHERE id = ?
                """;
        return jdbcTemplate.update(sql, geojson, origen, destino, fuente, validada, validada, id) == 1;
    }

    public record RutaDetalleRow(
            Long id, String codigo, String nombre, String origen, String destino, String sentido,
            String descripcion, String color, BigDecimal distanciaKm, boolean activa,
            boolean demostrativa, boolean validada, String fuente,
            String inicioGeojson, String finGeojson, String geojson
    ) {}

    public record ParaderoRow(Long id, Integer secuencia, String nombre, String geojson) {}
}
