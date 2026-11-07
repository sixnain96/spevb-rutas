package co.edu.usta.spevb.repository;

import co.edu.usta.spevb.dto.CrearLugarRequest;
import co.edu.usta.spevb.dto.LugarResponse;
import co.edu.usta.spevb.service.GeocodingService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public class LugarRepository {
    private final JdbcTemplate jdbc;

    public LugarRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<LugarResponse> buscarCatalogo(String query, int limit) {
        String like = "%" + query.strip() + "%";
        return jdbc.query("""
                WITH catalogo AS (
                    SELECT l.id, l.nombre, l.direccion, l.ubicacion, l.fuente, l.confirmado, 0 AS prioridad
                    FROM lugares l
                    UNION ALL
                    SELECT NULL::bigint, r.origen, r.origen, ST_StartPoint(r.recorrido),
                           'Extremo de ruta ' || r.codigo, FALSE, 1
                    FROM rutas r WHERE r.activa
                    UNION ALL
                    SELECT NULL::bigint, r.destino, r.destino, ST_EndPoint(r.recorrido),
                           'Extremo de ruta ' || r.codigo, FALSE, 1
                    FROM rutas r WHERE r.activa
                    UNION ALL
                    SELECT NULL::bigint, p.nombre, p.nombre, p.ubicacion,
                           'Paradero de ' || r.codigo, FALSE, 2
                    FROM paraderos p JOIN rutas r ON r.id = p.ruta_id WHERE r.activa
                )
                SELECT id, nombre, direccion, ST_Y(ubicacion) AS lat, ST_X(ubicacion) AS lng,
                       fuente, confirmado
                FROM catalogo
                WHERE LOWER(nombre) LIKE LOWER(?) OR LOWER(COALESCE(direccion,'')) LIKE LOWER(?)
                ORDER BY confirmado DESC, prioridad, nombre
                LIMIT ?
                """, (rs, n) -> new LugarResponse(
                rs.getObject("id", Long.class), rs.getString("nombre"), rs.getString("direccion"),
                rs.getDouble("lat"), rs.getDouble("lng"), rs.getString("fuente"), rs.getBoolean("confirmado")
        ), like, like, limit);
    }

    public Optional<LugarResponse> masCercano(double lat, double lng, double maxMetros) {
        var results = jdbc.query("""
                WITH p AS (SELECT ST_SetSRID(ST_MakePoint(?, ?), 4326) AS punto)
                SELECT l.id, l.nombre, l.direccion, ST_Y(l.ubicacion) AS lat, ST_X(l.ubicacion) AS lng,
                       l.fuente, l.confirmado,
                       ST_Distance(l.ubicacion::geography, p.punto::geography) AS distancia
                FROM lugares l CROSS JOIN p
                WHERE ST_DWithin(l.ubicacion::geography, p.punto::geography, ?)
                ORDER BY l.confirmado DESC, distancia
                LIMIT 1
                """, (rs, n) -> new LugarResponse(
                rs.getLong("id"), rs.getString("nombre"), rs.getString("direccion"), rs.getDouble("lat"),
                rs.getDouble("lng"), rs.getString("fuente"), rs.getBoolean("confirmado")
        ), lng, lat, maxMetros);
        return results.stream().findFirst();
    }

    public void guardarExterno(GeocodingService.Resultado resultado) {
        if (resultado.externalId() == null || resultado.externalId().isBlank()) return;
        jdbc.update("""
                INSERT INTO lugares (nombre, direccion, ubicacion, fuente, confirmado, external_id, actualizado_en)
                VALUES (?, ?, ST_SetSRID(ST_MakePoint(?, ?),4326), ?, FALSE, ?, NOW())
                ON CONFLICT (external_id) DO UPDATE SET
                    nombre = EXCLUDED.nombre,
                    direccion = EXCLUDED.direccion,
                    ubicacion = EXCLUDED.ubicacion,
                    fuente = EXCLUDED.fuente,
                    actualizado_en = NOW()
                """, resultado.nombre(), resultado.direccion(), resultado.lng(), resultado.lat(),
                resultado.fuente(), resultado.externalId());
    }

    public LugarResponse crear(CrearLugarRequest request) {
        Long id = jdbc.queryForObject("""
                INSERT INTO lugares (nombre, direccion, ubicacion, fuente, confirmado, actualizado_en)
                VALUES (?, NULLIF(?,''), ST_SetSRID(ST_MakePoint(?, ?),4326), NULLIF(?,''), TRUE, NOW())
                RETURNING id
                """, Long.class, request.nombre().strip(), safe(request.direccion()), request.lng(), request.lat(),
                safe(request.fuente()));
        return findById(id).orElseThrow();
    }

    public Optional<LugarResponse> findById(long id) {
        var rows = jdbc.query("""
                SELECT id, nombre, direccion, ST_Y(ubicacion) AS lat, ST_X(ubicacion) AS lng,
                       COALESCE(fuente,'Catálogo local SPEVB') AS fuente, confirmado
                FROM lugares WHERE id = ?
                """, (rs, n) -> new LugarResponse(rs.getLong("id"), rs.getString("nombre"), rs.getString("direccion"),
                rs.getDouble("lat"), rs.getDouble("lng"), rs.getString("fuente"), rs.getBoolean("confirmado")), id);
        return rows.stream().findFirst();
    }

    public List<LugarResponse> listar(String query, int limit) {
        String like = "%" + (query == null ? "" : query.strip()) + "%";
        return jdbc.query("""
                SELECT id, nombre, direccion, ST_Y(ubicacion) AS lat, ST_X(ubicacion) AS lng,
                       COALESCE(fuente,'Catálogo local SPEVB') AS fuente, confirmado
                FROM lugares
                WHERE LOWER(nombre) LIKE LOWER(?) OR LOWER(COALESCE(direccion,'')) LIKE LOWER(?)
                ORDER BY confirmado DESC, actualizado_en DESC, nombre
                LIMIT ?
                """, (rs, n) -> new LugarResponse(rs.getLong("id"), rs.getString("nombre"), rs.getString("direccion"),
                rs.getDouble("lat"), rs.getDouble("lng"), rs.getString("fuente"), rs.getBoolean("confirmado")),
                like, like, limit);
    }

    public boolean eliminar(long id) {
        return jdbc.update("DELETE FROM lugares WHERE id = ?", id) == 1;
    }

    public long contar() {
        Long value = jdbc.queryForObject("SELECT COUNT(*) FROM lugares", Long.class);
        return value == null ? 0 : value;
    }

    private String safe(String value) {
        return value == null ? "" : value.strip();
    }
}
