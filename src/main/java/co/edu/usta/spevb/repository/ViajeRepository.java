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
            ORDER BY sentido_permitido DESC, acceso_total_m ASC, tramo_km ASC, codigo
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

    /**
     * Puntos candidatos para subir (o bajar) en cada ruta: la línea se muestrea cada ~60 m y se conserva el
     * punto más cercano en línea recta de cada tramo de 250 m, además de la proyección exacta. Así hay
     * varias alternativas distintas por ruta (no sólo la más cercana en línea recta, que puede quedar al
     * otro lado de un cerro, un caño o una manzana sin paso) para evaluarlas luego por caminata real.
     * No se modifica ninguna coordenada: todos los puntos están sobre la línea registrada.
     */
    public List<CandidatoRow> candidatos(double lat, double lng, List<Long> rutaIds, int porRuta) {
        return candidatos(lat, lng, rutaIds, porRuta, 250);
    }

    /**
     * Igual que {@link #candidatos(double, double, List, int)} con el tamaño del tramo (m) dentro del cual sólo se
     * conserva el mejor punto. Además de las muestras sobre la línea se incluyen los **paraderos** registrados de cada
     * ruta (con una ventaja de 25 m al ordenarlos: se prefiere subir en un paradero si no queda mucho más lejos).
     */
    public List<CandidatoRow> candidatos(double lat, double lng, List<Long> rutaIds, int porRuta, int cubetaMetros) {
        if (rutaIds.isEmpty()) return List.of();
        String marcas = String.join(",", java.util.Collections.nCopies(rutaIds.size(), "?"));
        Object[] args = new Object[2 + rutaIds.size() + 2];
        args[0] = lng; args[1] = lat;
        for (int i = 0; i < rutaIds.size(); i++) args[2 + i] = rutaIds.get(i);
        args[args.length - 2] = Math.max(60, cubetaMetros);
        args[args.length - 1] = porRuta;
        return jdbc.query("""
            WITH p AS (SELECT ST_SetSRID(ST_MakePoint(?, ?), 4326) AS punto),
            r AS (
                SELECT id, recorrido, sentido, ST_Length(recorrido::geography) AS largo
                FROM rutas WHERE activa AND ST_NPoints(recorrido) >= 2 AND id IN (""" + marcas + """
            )),
            pasos AS (
                SELECT r.id, r.recorrido, r.sentido, r.largo,
                       LEAST(1.0, gs::double precision / GREATEST(1, CEIL(r.largo / 60.0))) AS f
                FROM r, LATERAL generate_series(0, GREATEST(1, CEIL(r.largo / 60.0))::int) gs
            ),
            muestras AS (
                SELECT id, sentido, largo, f, ST_LineInterpolatePoint(recorrido, f) AS pt, NULL::text AS parada FROM pasos
                UNION ALL
                SELECT r.id, r.sentido, r.largo, ST_LineLocatePoint(r.recorrido, p.punto),
                       ST_ClosestPoint(r.recorrido, p.punto), NULL::text
                FROM r CROSS JOIN p
                UNION ALL
                SELECT r.id, r.sentido, r.largo, ST_LineLocatePoint(r.recorrido, pa.ubicacion), pa.ubicacion, pa.nombre
                FROM r JOIN LATERAL (
                    SELECT x.ubicacion, x.nombre FROM paraderos x WHERE x.ruta_id = r.id
                      AND ST_DWithin(x.ubicacion::geography, r.recorrido::geography, 30)   -- un paradero lejos de la línea no sirve para subir
                    UNION ALL
                    -- paraderos de bus del sistema (independientes de las rutas): sirven a toda ruta que pase a ≤ 30 m
                    SELECT b.ubicacion, b.nombre FROM paraderos_buses b
                    WHERE b.activo AND b.ubicacion && ST_Expand(r.recorrido, 0.0005) AND ST_DWithin(b.ubicacion::geography, r.recorrido::geography, 30)
                ) pa ON TRUE
            ),
            medidas AS (
                SELECT m.*, ST_Distance(m.pt::geography, p.punto::geography) AS d,
                       ST_Distance(m.pt::geography, p.punto::geography) - CASE WHEN m.parada IS NULL THEN 0 ELSE 25 END AS d_ord,
                       FLOOR(m.f * m.largo / ?::double precision) AS cubeta
                FROM muestras m CROSS JOIN p
            ),
            mejores AS (
                SELECT DISTINCT ON (id, cubeta) * FROM medidas ORDER BY id, cubeta, d_ord
            ),
            ranq AS (
                SELECT *, ROW_NUMBER() OVER (PARTITION BY id ORDER BY d_ord) AS rn FROM mejores
            )
            SELECT id, sentido, largo, f, ST_X(pt) AS lng, ST_Y(pt) AS lat, d, parada
            FROM ranq WHERE rn <= ? ORDER BY id, rn
            """, (rs, n) -> new CandidatoRow(rs.getLong("id"), rs.getString("sentido"), rs.getDouble("largo"),
                rs.getDouble("f"), rs.getDouble("lng"), rs.getDouble("lat"), rs.getDouble("d"), rs.getString("parada")), args);
    }

    /**
     * Muestras densas de las rutas (un punto cada {@code pasoMetros}) más sus paraderos, limitadas a los puntos que
     * quedan a {@code radioA} m del origen o a {@code radioB} m del destino. Sirve para elegir el punto de subida y bajada
     * óptimo midiendo la caminata real a cada muestra. Todos los puntos están sobre la línea registrada.
     */
    public List<CandidatoRow> muestras(List<Long> rutaIds, double pasoMetros, double latA, double lngA, double radioA,
                                       double latB, double lngB, double radioB) {
        if (rutaIds.isEmpty()) return List.of();
        var rejilla = rejilla(pasoMetros);
        if (rejilla == null) return muestrasSql(rutaIds, pasoMetros, latA, lngA, radioA, latB, lngB, radioB);
        // Mismas filas y mismo orden (ruta, posición) que la consulta de abajo: se descartan las de otras rutas y las que quedan fuera de ambos radios.
        var ids = new java.util.HashSet<>(rutaIds);
        List<CandidatoRow> resultado = new java.util.ArrayList<>();
        for (CandidatoRow f : rejilla)
            if (ids.contains(f.rutaId()) && (dentro(latA, lngA, radioA, f.lat(), f.lng()) || dentro(latB, lngB, radioB, f.lat(), f.lng())))
                resultado.add(f);
        return resultado;
    }

    /** Consulta original contra PostGIS (distancia geodésica con ST_DWithin). Se usa para pasos no guardados y como referencia en las pruebas. */
    public List<CandidatoRow> muestrasSql(List<Long> rutaIds, double pasoMetros, double latA, double lngA, double radioA,
                                          double latB, double lngB, double radioB) {
        if (rutaIds.isEmpty()) return List.of();
        String marcas = String.join(",", java.util.Collections.nCopies(rutaIds.size(), "?"));
        Object[] args = new Object[rutaIds.size() + 8];
        args[0] = lngA; args[1] = latA; args[2] = lngB; args[3] = latB;
        for (int i = 0; i < rutaIds.size(); i++) args[4 + i] = rutaIds.get(i);
        int k = 4 + rutaIds.size();
        args[k] = pasoMetros; args[k + 1] = pasoMetros; args[k + 2] = radioA; args[k + 3] = radioB;
        return jdbc.query("""
            WITH a AS (SELECT ST_SetSRID(ST_MakePoint(?, ?), 4326)::geography AS g),
            b AS (SELECT ST_SetSRID(ST_MakePoint(?, ?), 4326)::geography AS g),
            r AS (
                SELECT id, recorrido, sentido, ST_Length(recorrido::geography) AS largo
                FROM rutas WHERE activa AND ST_NPoints(recorrido) >= 2 AND id IN (""" + marcas + """
            )),
            pasos AS (
                SELECT r.id, r.sentido, r.largo, r.recorrido,
                       LEAST(1.0, gs::double precision / GREATEST(1, CEIL(r.largo / ?::double precision))) AS f, NULL::text AS parada
                FROM r, LATERAL generate_series(0, GREATEST(1, CEIL(r.largo / ?::double precision))::int) gs
            ),
            todas AS (
                SELECT id, sentido, largo, f, ST_LineInterpolatePoint(recorrido, f) AS pt, parada FROM pasos
                UNION ALL
                SELECT r.id, r.sentido, r.largo, ST_LineLocatePoint(r.recorrido, pa.ubicacion), pa.ubicacion, pa.nombre
                FROM r JOIN LATERAL (
                    SELECT x.ubicacion, x.nombre FROM paraderos x WHERE x.ruta_id = r.id
                      AND ST_DWithin(x.ubicacion::geography, r.recorrido::geography, 30)   -- un paradero lejos de la línea no sirve para subir
                    UNION ALL
                    -- paraderos de bus del sistema (independientes de las rutas): sirven a toda ruta que pase a ≤ 30 m
                    SELECT b.ubicacion, b.nombre FROM paraderos_buses b
                    WHERE b.activo AND b.ubicacion && ST_Expand(r.recorrido, 0.0005) AND ST_DWithin(b.ubicacion::geography, r.recorrido::geography, 30)
                ) pa ON TRUE
            )
            SELECT id, sentido, largo, f, ST_X(pt) AS lng, ST_Y(pt) AS lat, parada
            FROM todas, a, b
            WHERE ST_DWithin(pt::geography, a.g, ?::double precision) OR ST_DWithin(pt::geography, b.g, ?::double precision)
            ORDER BY id, f
            """, (rs, n) -> new CandidatoRow(rs.getLong("id"), rs.getString("sentido"), rs.getDouble("largo"),
                rs.getDouble("f"), rs.getDouble("lng"), rs.getDouble("lat"), 0, rs.getString("parada")), args);
    }

    /** Rutas activas (opcionalmente solo las revisadas) con algún punto a menos de {@code radioMetros} del punto dado. */
    public List<Long> rutasCerca(double lat, double lng, double radioMetros, boolean soloValidadas) {
        return jdbc.query("""
            SELECT r.id FROM rutas r
            WHERE r.activa AND NOT ST_IsEmpty(r.recorrido) AND ST_NPoints(r.recorrido) >= 2 AND (NOT ? OR r.validada)
              AND ST_DWithin(r.recorrido::geography, ST_SetSRID(ST_MakePoint(?, ?), 4326)::geography, ?::double precision)
            ORDER BY r.id
            """, (rs, n) -> rs.getLong("id"), soloValidadas, lng, lat, radioMetros);
    }

    public record InfoRuta(long id, String codigo, String nombre, String color, boolean validada) {}

    public List<InfoRuta> infoRutas(List<Long> ids) {
        if (ids.isEmpty()) return List.of();
        String marcas = String.join(",", java.util.Collections.nCopies(ids.size(), "?"));
        return jdbc.query("SELECT id, codigo, nombre, color_hex, validada FROM rutas WHERE id IN (" + marcas + ")",
                (rs, n) -> new InfoRuta(rs.getLong("id"), rs.getString("codigo"), rs.getString("nombre"),
                        rs.getString("color_hex"), rs.getBoolean("validada")), ids.toArray());
    }

    /** Muestras de TODA la línea de cada ruta (un punto cada {@code pasoMetros}) más sus paraderos, ordenadas por ruta y posición. */
    public List<CandidatoRow> muestrasCompletas(List<Long> rutaIds, double pasoMetros) {
        if (rutaIds.isEmpty()) return List.of();
        var rejilla = rejilla(pasoMetros);
        if (rejilla == null) return muestrasCompletasSql(rutaIds, pasoMetros);
        var ids = new java.util.HashSet<>(rutaIds);
        return rejilla.stream().filter(f -> ids.contains(f.rutaId())).toList();
    }

    /** Consulta original contra PostGIS (sin rejilla en memoria). */
    public List<CandidatoRow> muestrasCompletasSql(List<Long> rutaIds, double pasoMetros) {
        if (rutaIds.isEmpty()) return List.of();
        String marcas = String.join(",", java.util.Collections.nCopies(rutaIds.size(), "?"));
        Object[] args = new Object[rutaIds.size() + 2];
        for (int i = 0; i < rutaIds.size(); i++) args[i] = rutaIds.get(i);
        args[rutaIds.size()] = pasoMetros;
        args[rutaIds.size() + 1] = pasoMetros;
        return jdbc.query("""
            WITH r AS (
                SELECT id, recorrido, sentido, ST_Length(recorrido::geography) AS largo
                FROM rutas WHERE activa AND ST_NPoints(recorrido) >= 2 AND id IN (""" + marcas + """
            )),
            pasos AS (
                SELECT r.id, r.sentido, r.largo, r.recorrido,
                       LEAST(1.0, gs::double precision / GREATEST(1, CEIL(r.largo / ?::double precision))) AS f, NULL::text AS parada
                FROM r, LATERAL generate_series(0, GREATEST(1, CEIL(r.largo / ?::double precision))::int) gs
            ),
            todas AS (
                SELECT id, sentido, largo, f, ST_LineInterpolatePoint(recorrido, f) AS pt, parada FROM pasos
                UNION ALL
                SELECT r.id, r.sentido, r.largo, ST_LineLocatePoint(r.recorrido, pa.ubicacion), pa.ubicacion, pa.nombre
                FROM r JOIN LATERAL (
                    SELECT x.ubicacion, x.nombre FROM paraderos x WHERE x.ruta_id = r.id
                      AND ST_DWithin(x.ubicacion::geography, r.recorrido::geography, 30)   -- un paradero lejos de la línea no sirve para subir
                    UNION ALL
                    -- paraderos de bus del sistema (independientes de las rutas): sirven a toda ruta que pase a ≤ 30 m
                    SELECT b.ubicacion, b.nombre FROM paraderos_buses b
                    WHERE b.activo AND b.ubicacion && ST_Expand(r.recorrido, 0.0005) AND ST_DWithin(b.ubicacion::geography, r.recorrido::geography, 30)
                ) pa ON TRUE
            )
            SELECT id, sentido, largo, f, ST_X(pt) AS lng, ST_Y(pt) AS lat, parada FROM todas ORDER BY id, f
            """, (rs, n) -> new CandidatoRow(rs.getLong("id"), rs.getString("sentido"), rs.getDouble("largo"),
                rs.getDouble("f"), rs.getDouble("lng"), rs.getDouble("lat"), 0, rs.getString("parada")), args);
    }

    /** Puntos de una ruta en fracciones concretas (0–1) de su recorrido: afinado local de subida/bajada. */
    public List<CandidatoRow> puntosEn(List<Long> rutaIds, List<Double> fracciones) {
        if (fracciones.isEmpty()) return List.of();
        String valores = String.join(",", java.util.Collections.nCopies(fracciones.size(), "(?::bigint, ?::double precision)"));
        Object[] args = new Object[fracciones.size() * 2];
        for (int i = 0; i < fracciones.size(); i++) {
            args[2 * i] = rutaIds.get(i);
            args[2 * i + 1] = Math.min(1.0, Math.max(0.0, fracciones.get(i)));
        }
        return jdbc.query("""
            SELECT r.id, r.sentido, ST_Length(r.recorrido::geography) AS largo, v.f,
                   ST_X(ST_LineInterpolatePoint(r.recorrido, v.f)) AS lng,
                   ST_Y(ST_LineInterpolatePoint(r.recorrido, v.f)) AS lat
            FROM (VALUES """ + valores + """
            ) AS v(id, f) JOIN rutas r ON r.id = v.id
            """, (rs, n) -> new CandidatoRow(rs.getLong("id"), rs.getString("sentido"), rs.getDouble("largo"),
                rs.getDouble("f"), rs.getDouble("lng"), rs.getDouble("lat"), 0, null), args);
    }

    /** Tramo del bus entre dos posiciones (fracciones 0–1 de la línea) con el mismo criterio de sentido. */
    public TramoRow tramoEntre(long rutaId, double a, double b) {
        return jdbc.queryForObject("""
            WITH x AS (SELECT recorrido, sentido, ?::double precision AS a, ?::double precision AS b
                       FROM rutas WHERE id = ?),
            t AS (
                SELECT recorrido, a, b,
                       CASE
                         WHEN b >= a THEN ST_LineSubstring(recorrido, a, b)
                         WHEN sentido = 'CIRCULAR' THEN ST_MakeLine(
                                ST_LineSubstring(recorrido, a, 1), ST_LineSubstring(recorrido, 0, b))
                         ELSE ST_Reverse(ST_LineSubstring(recorrido, b, a))
                       END AS tramo
                FROM x
            )
            SELECT ST_AsGeoJSON(ST_LineInterpolatePoint(recorrido, a), 7) AS subida,
                   ST_AsGeoJSON(ST_LineInterpolatePoint(recorrido, b), 7) AS bajada,
                   ST_AsGeoJSON(tramo, 7) AS tramo,
                   ST_Length(tramo::geography) / 1000.0 AS km
            FROM t
            """, (rs, n) -> new TramoRow(rs.getString("subida"), rs.getString("bajada"),
                rs.getString("tramo"), rs.getDouble("km")), a, b, rutaId);
    }

    // ------------------------------------------------------------------ rejillas de muestras en memoria
    /**
     * Las muestras de cada ruta (un punto cada 15 m y cada 50 m, más los paraderos) son SIEMPRE las mismas mientras no cambien las rutas ni los
     * paraderos, pero se recalculaban en PostGIS en cada viaje (≈ 100 ms de los ≈ 230 ms de un cálculo en frío, medido con EXPLAIN ANALYZE:
     * miles de {@code ST_LineInterpolatePoint} sobre líneas largas). Ahora se calculan una vez, con la MISMA consulta, y cada viaje solo filtra.
     * Vigencia: una «huella» barata (≈ 1 ms: versión y fecha de edición de las rutas, cantidad y posición de los paraderos) se compara en cada
     * uso, se vacía al editar paraderos o pasos ({@link #invalidarMuestras()}) y, por si alguien edita la base a mano sin tocar esos campos, se
     * recarga a los 10 minutos.
     */
    private static final java.util.Set<Double> PASOS_GUARDADOS = java.util.Set.of(15.0, 50.0);
    private static final long VIGENCIA_MAXIMA_MS = 10 * 60_000L;
    private record Rejilla(String huella, long cargadaMs, List<CandidatoRow> filas) {}
    private final java.util.concurrent.ConcurrentHashMap<Double, Rejilla> rejillas = new java.util.concurrent.ConcurrentHashMap<>();
    private final Object cargaRejilla = new Object();

    /** Descarta las rejillas: la siguiente consulta las calcula de nuevo. */
    public void invalidarMuestras() { rejillas.clear(); }

    private String huella() {
        return jdbc.queryForObject("""
            SELECT (SELECT COUNT(*) || ':' || COALESCE(SUM(version), 0) || ':' || COALESCE(MAX(actualizado_en)::text, '') FROM rutas WHERE activa)
                || '|' || (SELECT COUNT(*) || ':' || COALESCE(SUM(ST_X(ubicacion) * 7 + ST_Y(ubicacion)), 0)::text FROM paraderos)
                || '|' || (SELECT COUNT(*) || ':' || COALESCE(MAX(actualizado_en)::text, '') || ':'
                                  || COALESCE(SUM(ST_X(ubicacion) * 7 + ST_Y(ubicacion)), 0)::text FROM paraderos_buses WHERE activo)
            """, String.class);
    }

    /** Las filas de todas las rutas activas para ese paso (ordenadas por ruta y posición), o null si ese paso no se guarda. */
    private List<CandidatoRow> rejilla(double pasoMetros) {
        if (!PASOS_GUARDADOS.contains(pasoMetros)) return null;
        String huella = huella();
        Rejilla r = rejillas.get(pasoMetros);
        if (vigente(r, huella)) return r.filas();
        synchronized (cargaRejilla) {
            r = rejillas.get(pasoMetros);
            if (vigente(r, huella)) return r.filas();
            List<Long> ids = jdbc.queryForList("SELECT id FROM rutas WHERE activa ORDER BY id", Long.class);
            List<CandidatoRow> filas = ids.isEmpty() ? List.of() : List.copyOf(muestrasCompletasSql(ids, pasoMetros));
            rejillas.put(pasoMetros, new Rejilla(huella, System.currentTimeMillis(), filas));
            return filas;
        }
    }

    private static boolean vigente(Rejilla r, String huella) {
        return r != null && r.huella().equals(huella) && System.currentTimeMillis() - r.cargadaMs() < VIGENCIA_MAXIMA_MS;
    }

    private static final double WGS84_A = 6_378_137.0, WGS84_E2 = 0.00669437999014;

    /**
     * ¿El punto (lat, lng) está a ≤ {@code radio} metros de (lat0, lng0)? Plano tangente sobre el elipsoide WGS84 a la latitud media: con
     * radios de hasta 6 km difiere de la distancia geodésica de PostGIS menos de 1 mm (se mide en {@code MuestrasPostgisTest}); solo un punto
     * a menos de 1 mm del borde exacto del radio podría decidirse distinto.
     */
    static boolean dentro(double lat0, double lng0, double radio, double lat, double lng) {
        double dLat = lat - lat0;
        if (Math.abs(dLat) * 110_000 > radio) return false;          // 1° de latitud mide ≥ 110,5 km: ya está fuera, sin trigonometría
        double fi = Math.toRadians((lat + lat0) / 2), s = Math.sin(fi), w = 1 - WGS84_E2 * s * s, raiz = Math.sqrt(w);
        double dy = Math.toRadians(dLat) * WGS84_A * (1 - WGS84_E2) / (w * raiz);
        double dx = Math.toRadians(lng - lng0) * WGS84_A / raiz * Math.cos(fi);
        return dx * dx + dy * dy <= radio * radio;
    }

    /** Un tramo que se pide: ruta y las dos posiciones (fracciones 0–1 de la línea) entre las que va el bus. */
    public record TramoPedido(long rutaId, double a, double b) {}

    /**
     * {@link #tramoEntre} para varios tramos en UNA sola consulta (con la misma expresión por fila): un viaje pedía ≈ 20 tramos uno por uno, cada
     * uno con su ida y vuelta a la base (≈ 25 ms de ≈ 134 por viaje). El resultado va en el mismo orden que {@code pedidos}; un elemento es
     * {@code null} si esa ruta ya no existe (antes {@code tramoEntre} lanzaba una excepción en ese caso).
     */
    public List<TramoRow> tramosEntre(List<TramoPedido> pedidos) {
        if (pedidos.isEmpty()) return List.of();
        String valores = String.join(",", java.util.Collections.nCopies(pedidos.size(),
                "(?::int, ?::bigint, ?::double precision, ?::double precision)"));
        Object[] args = new Object[pedidos.size() * 4];
        for (int i = 0; i < pedidos.size(); i++) {
            args[4 * i] = i; args[4 * i + 1] = pedidos.get(i).rutaId();
            args[4 * i + 2] = pedidos.get(i).a(); args[4 * i + 3] = pedidos.get(i).b();
        }
        TramoRow[] resultado = new TramoRow[pedidos.size()];
        jdbc.query("""
            WITH v(i, rid, a, b) AS (VALUES """ + valores + """
            ),
            x AS (SELECT v.i, r.recorrido, r.sentido, v.a, v.b FROM v JOIN rutas r ON r.id = v.rid),
            t AS MATERIALIZED (
                SELECT i, recorrido, a, b,
                       CASE
                         WHEN b >= a THEN ST_LineSubstring(recorrido, a, b)
                         WHEN sentido = 'CIRCULAR' THEN ST_MakeLine(
                                ST_LineSubstring(recorrido, a, 1), ST_LineSubstring(recorrido, 0, b))
                         ELSE ST_Reverse(ST_LineSubstring(recorrido, b, a))
                       END AS tramo
                FROM x
            )
            SELECT i, ST_AsGeoJSON(ST_LineInterpolatePoint(recorrido, a), 7) AS subida,
                   ST_AsGeoJSON(ST_LineInterpolatePoint(recorrido, b), 7) AS bajada,
                   ST_AsGeoJSON(tramo, 7) AS tramo,
                   ST_Length(tramo::geography) / 1000.0 AS km
            FROM t
            """, rs -> {
                resultado[rs.getInt("i")] = new TramoRow(rs.getString("subida"), rs.getString("bajada"),
                        rs.getString("tramo"), rs.getDouble("km"));
            }, args);
        return java.util.Arrays.asList(resultado);
    }

    public record CandidatoRow(long rutaId, String sentido, double largoMetros, double fraccion,
                               double lng, double lat, double rectaMetros, String parada) {}
    public record TramoRow(String subida, String bajada, String tramo, double km) {}

    public record OpcionRow(long id, String codigo, String nombre, String color, boolean validada,
                            double inicioMetros, double finMetros, double tramoKm, double accesoTotalMetros,
                            boolean dentroLimite, String sentidoViaje, String sentidoRuta,
                            boolean sentidoPermitido, String subida, String bajada, String tramo) {}
}
