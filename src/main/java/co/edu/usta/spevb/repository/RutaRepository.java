package co.edu.usta.spevb.repository;

import co.edu.usta.spevb.dto.CrearRutaRequest;
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

    public void comprobarConexion() { jdbcTemplate.queryForObject("SELECT 1", Integer.class); }

    public List<RutaResumenResponse> findAllActivas() {
        return jdbcTemplate.query("""
                SELECT id, codigo, nombre, origen, destino, sentido, color_hex,
                       ROUND((ST_Length(recorrido::geography) / 1000.0)::numeric, 2) AS distancia_km,
                       activa, validada
                FROM rutas
                WHERE activa = TRUE
                ORDER BY codigo
                """, (rs, rowNum) -> new RutaResumenResponse(
                rs.getLong("id"), rs.getString("codigo"), rs.getString("nombre"), rs.getString("origen"),
                rs.getString("destino"), rs.getString("sentido"), rs.getString("color_hex"),
                rs.getObject("distancia_km", BigDecimal.class), rs.getBoolean("activa"), rs.getBoolean("validada")
        ));
    }

    public Optional<RutaDetalleRow> findById(Long id) {
        var results = jdbcTemplate.query("""
                SELECT id, codigo, nombre, origen, destino, sentido, descripcion, color_hex,
                       ROUND((ST_Length(recorrido::geography) / 1000.0)::numeric, 2) AS distancia_km,
                       activa, demostrativa, validada, fuente, version, actualizado_en, nota_validacion,
                       ST_AsGeoJSON(ST_StartPoint(recorrido), 7) AS inicio_geojson,
                       ST_AsGeoJSON(ST_EndPoint(recorrido), 7) AS fin_geojson,
                       ST_AsGeoJSON(recorrido, 7) AS geojson
                FROM rutas WHERE id = ?
                """, (rs, rowNum) -> new RutaDetalleRow(
                rs.getLong("id"), rs.getString("codigo"), rs.getString("nombre"), rs.getString("origen"),
                rs.getString("destino"), rs.getString("sentido"), rs.getString("descripcion"),
                rs.getString("color_hex"), rs.getObject("distancia_km", BigDecimal.class),
                rs.getBoolean("activa"), rs.getBoolean("demostrativa"), rs.getBoolean("validada"),
                rs.getString("fuente"), rs.getString("inicio_geojson"), rs.getString("fin_geojson"),
                rs.getString("geojson"), rs.getLong("version"), rs.getTimestamp("actualizado_en").toInstant().toString(),
                rs.getString("nota_validacion")
        ), id);
        return results.stream().findFirst();
    }

    public List<ParaderoRow> findParaderos(Long rutaId) {
        return jdbcTemplate.query("""
                SELECT p.id, p.secuencia, p.nombre, ST_AsGeoJSON(p.ubicacion, 7) AS geojson,
                       ST_Distance(p.ubicacion::geography, r.recorrido::geography) AS distancia_m
                FROM paraderos p JOIN rutas r ON r.id = p.ruta_id
                WHERE p.ruta_id = ? ORDER BY secuencia
                """, (rs, rowNum) -> new ParaderoRow(
                rs.getLong("id"), rs.getInt("secuencia"), rs.getString("nombre"),
                rs.getString("geojson"), rs.getDouble("distancia_m")
        ), rutaId);
    }

    public Long crear(CrearRutaRequest request, String geojson, String nota) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO rutas (codigo, nombre, origen, destino, sentido, descripcion, color_hex, activa,
                                   demostrativa, validada, fuente, nota_validacion, recorrido, version, actualizado_en)
                VALUES (?, ?, ?, ?, ?, NULLIF(?,''), ?, TRUE, FALSE, ?, NULLIF(?,''), ?,
                        ST_SetSRID(ST_GeomFromGeoJSON(?),4326), 0, NOW())
                RETURNING id
                """, Long.class,
                request.codigo().strip().toUpperCase(), request.nombre().strip(), request.origen().strip(),
                request.destino().strip(), request.sentido(), safe(request.descripcion()), request.color(),
                request.validada(), safe(request.fuente()), nota, geojson);
    }

    public boolean actualizarRecorrido(Long id, String geojson, String nombre, String origen, String destino,
                                       String sentido, String descripcion, String color, String fuente,
                                       boolean validada, long version, String nota) {
        return jdbcTemplate.update("""
                UPDATE rutas
                SET recorrido = ST_SetSRID(ST_GeomFromGeoJSON(?), 4326),
                    nombre = ?, origen = ?, destino = ?, sentido = ?, descripcion = NULLIF(?,''),
                    color_hex = ?, fuente = NULLIF(?, ''), validada = ?, demostrativa = FALSE,
                    nota_validacion = ?, version = version + 1, actualizado_en = NOW()
                WHERE id = ? AND version = ?
                """, geojson, nombre, origen, destino, sentido, safe(descripcion), color,
                fuente, validada, nota, id, version) == 1;
    }

    public void archivar(Long id, long version) {
        jdbcTemplate.update("""
                INSERT INTO ruta_revisiones (ruta_id, version, datos)
                SELECT id, version, jsonb_build_object(
                    'codigo', codigo, 'nombre', nombre, 'recorrido', ST_AsGeoJSON(recorrido, 7)::jsonb,
                    'origen', origen, 'destino', destino, 'sentido', sentido, 'descripcion', descripcion,
                    'color', color_hex, 'fuente', fuente, 'validada', validada,
                    'demostrativa', demostrativa, 'notaValidacion', nota_validacion)
                FROM rutas WHERE id = ? AND version = ?
                ON CONFLICT (ruta_id, version) DO NOTHING
                """, id, version);
    }

    public List<RevisionRow> revisiones(Long id) {
        return jdbcTemplate.query("""
                SELECT version, archivado_en, datos::text AS datos FROM ruta_revisiones
                WHERE ruta_id = ? ORDER BY version DESC LIMIT 30
                """, (rs, n) -> new RevisionRow(rs.getLong("version"),
                rs.getTimestamp("archivado_en").toInstant().toString(), rs.getString("datos")), id);
    }

    public List<CercanaRow> cercanas(double lat, double lng, Integer radioMetros, boolean soloValidadas) {
        String filtroRadio = radioMetros == null ? ""
                : "  AND ST_DWithin(r.recorrido::geography, p.punto::geography, ?)\n";
        String sql = """
                WITH p AS (SELECT ST_SetSRID(ST_MakePoint(?, ?),4326) AS punto)
                SELECT r.id, r.codigo, r.nombre, r.origen, r.destino, r.color_hex, r.validada,
                       ST_Distance(r.recorrido::geography, p.punto::geography) AS distancia_m,
                       ST_AsGeoJSON(ST_ClosestPoint(r.recorrido, p.punto),7) AS punto_json
                FROM rutas r CROSS JOIN p
                WHERE r.activa AND (NOT ? OR r.validada)
                """ + filtroRadio + """
                ORDER BY distancia_m, r.validada DESC, r.codigo
                LIMIT 8
                """;
        Object[] args = radioMetros == null
                ? new Object[]{lng, lat, soloValidadas}
                : new Object[]{lng, lat, soloValidadas, radioMetros};
        return jdbcTemplate.query(sql, (rs, n) -> new CercanaRow(rs.getLong("id"), rs.getString("codigo"), rs.getString("nombre"),
                rs.getString("origen"), rs.getString("destino"), rs.getString("color_hex"), rs.getBoolean("validada"),
                rs.getDouble("distancia_m"), rs.getString("punto_json")), args);
    }

    private String safe(String value) { return value == null ? "" : value.strip(); }

    public record RutaDetalleRow(Long id, String codigo, String nombre, String origen, String destino, String sentido,
                                 String descripcion, String color, BigDecimal distanciaKm, boolean activa,
                                 boolean demostrativa, boolean validada, String fuente,
                                 String inicioGeojson, String finGeojson, String geojson,
                                 long version, String actualizadoEn, String notaValidacion) {}
    public record ParaderoRow(Long id, Integer secuencia, String nombre, String geojson, double distanciaMetros) {}
    public record RevisionRow(long version, String archivadoEn, String datos) {}
    public record CercanaRow(long id, String codigo, String nombre, String origen, String destino, String color,
                             boolean validada, double distanciaMetros, String puntoJson) {}
}
