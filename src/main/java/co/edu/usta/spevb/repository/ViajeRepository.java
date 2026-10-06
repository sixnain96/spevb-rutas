package co.edu.usta.spevb.repository;

import co.edu.usta.spevb.dto.PlanificarViajeRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public class ViajeRepository {
    private final JdbcTemplate jdbc;
    public ViajeRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    /**
     * Compara origen y destino con todas las rutas activas, sin límite de distancia, y las ordena
     * de la más cercana a la más lejana (suma de la distancia A→línea y línea→B).
     *
     * El sentido del viaje se calcula por la posición de A y B sobre el LineString:
     * <ul>
     *   <li>IDA / REGRESO: el bus circula en el orden en que se dibujó la línea. Un viaje en
     *       sentido contrario se devuelve con {@code sentido_permitido = false} para que el
     *       servicio lo muestre como alternativa con advertencia y no como opción directa.</li>
     *   <li>CIRCULAR: el bus da la vuelta completa. Si B queda "antes" que A sobre la línea, el
     *       tramo continúa hasta el final del trazado y sigue desde el inicio (nunca se invierte).</li>
     * </ul>
     */
    public List<OpcionRow> planificar(PlanificarViajeRequest request, int limit) {
        return jdbc.query("""
            WITH p AS (
                SELECT ST_SetSRID(ST_MakePoint(?, ?), 4326) AS salida,
                       ST_SetSRID(ST_MakePoint(?, ?), 4326) AS llegada
            ), candidatos AS (
                SELECT r.id, r.codigo, r.nombre, r.color_hex, r.validada, r.recorrido,
                       r.sentido AS sentido_ruta, p.salida, p.llegada,
                       ST_LineLocatePoint(r.recorrido, p.salida) AS a,
                       ST_LineLocatePoint(r.recorrido, p.llegada) AS b
                FROM rutas r CROSS JOIN p
                WHERE r.activa
                  AND NOT ST_IsEmpty(r.recorrido)
                  AND ST_NPoints(r.recorrido) >= 2
                  AND (NOT ? OR r.validada)
            ), proyectados AS (
                SELECT *,
                       ST_LineInterpolatePoint(recorrido, a) AS subida,
                       ST_LineInterpolatePoint(recorrido, b) AS bajada,
                       CASE
                         WHEN b >= a THEN ST_LineSubstring(recorrido, a, b)
                         WHEN sentido_ruta = 'CIRCULAR' THEN ST_MakeLine(
                                ST_LineSubstring(recorrido, a, 1), ST_LineSubstring(recorrido, 0, b))
                         ELSE ST_Reverse(ST_LineSubstring(recorrido, b, a))
                       END AS tramo,
                       CASE
                         WHEN b >= a OR sentido_ruta = 'CIRCULAR' THEN 'SENTIDO_DEL_TRAZADO'
                         ELSE 'SENTIDO_INVERSO'
                       END AS sentido_viaje
                FROM candidatos
                WHERE ABS(b - a) > 0.0000005
            ), medidos AS (
                SELECT *,
                       ST_Distance(salida::geography, subida::geography) AS inicio_m,
                       ST_Distance(llegada::geography, bajada::geography) AS fin_m,
                       ST_Length(tramo::geography) / 1000.0 AS tramo_km
                FROM proyectados
            ), clasificados AS (
                SELECT id, codigo, nombre, color_hex, validada, inicio_m, fin_m, tramo_km,
                       (inicio_m + fin_m) AS acceso_total_m,
                       (inicio_m <= ? AND fin_m <= ?) AS dentro_limite,
                       sentido_viaje, sentido_ruta,
                       (sentido_viaje = 'SENTIDO_DEL_TRAZADO') AS sentido_permitido,
                       subida, bajada, tramo
                FROM medidos
            )
            SELECT id, codigo, nombre, color_hex, validada, inicio_m, fin_m, tramo_km,
                   acceso_total_m, dentro_limite, sentido_viaje, sentido_ruta, sentido_permitido,
                   ST_AsGeoJSON(subida, 7) AS subida_json,
                   ST_AsGeoJSON(bajada, 7) AS bajada_json,
                   ST_AsGeoJSON(tramo, 7) AS tramo_json
            FROM clasificados
            ORDER BY sentido_permitido DESC, acceso_total_m ASC, validada DESC, tramo_km ASC, codigo
            LIMIT ?
            """, (rs,n) -> new OpcionRow(
                    rs.getLong("id"), rs.getString("codigo"), rs.getString("nombre"),
                    rs.getString("color_hex"), rs.getBoolean("validada"),
                    rs.getDouble("inicio_m"), rs.getDouble("fin_m"), rs.getDouble("tramo_km"),
                    rs.getDouble("acceso_total_m"), rs.getBoolean("dentro_limite"),
                    rs.getString("sentido_viaje"), rs.getString("sentido_ruta"),
                    rs.getBoolean("sentido_permitido"), rs.getString("subida_json"),
                    rs.getString("bajada_json"), rs.getString("tramo_json")
                ),
                request.origen().lng(), request.origen().lat(),
                request.destino().lng(), request.destino().lat(),
                request.soloValidadas(),
                request.caminataReferencia(), request.caminataReferencia(), limit);
    }

    public record OpcionRow(long id, String codigo, String nombre, String color, boolean validada,
                            double inicioMetros, double finMetros, double tramoKm, double accesoTotalMetros,
                            boolean dentroLimite, String sentidoViaje, String sentidoRuta,
                            boolean sentidoPermitido, String subida, String bajada, String tramo) {}
}
