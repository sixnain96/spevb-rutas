package co.edu.usta.spevb.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/** Acceso a la base propia de direcciones (tabla {@code direcciones} y auxiliares). */
@Repository
public class DireccionRepository {
    private final JdbcTemplate jdbc;

    public DireccionRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public record Fila(long id, String texto, String tipoVia, String via, String cuadrante, String cruce, int placa,
                       int placa2, String manzana, String casa, String barrio, double lat, double lng,
                       String precision, int confianza) {}

    public record Nueva(String fuente, String fuenteId, String texto, String norm, String tipoVia, String via,
                        String cuadrante, String cruce, int placa, int placa2, String manzana, String casa,
                        String barrio, double lat, double lng, String precision, int confianza) {}

    public record Punto(double lat, double lng, int n) {}

    private static final String COLUMNAS = """
            id, texto, tipo_via, via, cuadrante, cruce, placa, placa2, manzana, casa, barrio,
            ST_Y(ubicacion) AS lat, ST_X(ubicacion) AS lng, precision, confianza""";

    private static Fila fila(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
        return new Fila(rs.getLong("id"), rs.getString("texto"), rs.getString("tipo_via"), rs.getString("via"),
                rs.getString("cuadrante"), rs.getString("cruce"), rs.getInt("placa"), rs.getInt("placa2"),
                rs.getString("manzana"), rs.getString("casa"), rs.getString("barrio"), rs.getDouble("lat"),
                rs.getDouble("lng"), rs.getString("precision"), rs.getInt("confianza"));
    }

    public long contar() {
        Long n = jdbc.queryForObject("SELECT COUNT(*) FROM direcciones", Long.class);
        return n == null ? 0 : n;
    }

    // ------------------------------------------------------------------ importación
    public int guardarLote(List<Nueva> lote) {
        if (lote.isEmpty()) return 0;
        jdbc.batchUpdate("""
                INSERT INTO direcciones (fuente, fuente_id, texto, norm, tipo_via, via, cuadrante, cruce, placa, placa2,
                                         manzana, casa, barrio, ubicacion, precision, confianza, actualizado_en)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ST_SetSRID(ST_MakePoint(?, ?), 4326), ?, ?, NOW())
                ON CONFLICT (fuente, fuente_id) DO UPDATE SET
                    texto = EXCLUDED.texto, norm = EXCLUDED.norm, tipo_via = EXCLUDED.tipo_via, via = EXCLUDED.via,
                    cuadrante = EXCLUDED.cuadrante, cruce = EXCLUDED.cruce, placa = EXCLUDED.placa,
                    placa2 = EXCLUDED.placa2, manzana = EXCLUDED.manzana, casa = EXCLUDED.casa,
                    barrio = EXCLUDED.barrio, ubicacion = EXCLUDED.ubicacion, precision = EXCLUDED.precision,
                    confianza = EXCLUDED.confianza, actualizado_en = NOW()
                """, lote, lote.size(), (ps, d) -> {
            int i = 1;
            ps.setString(i++, d.fuente()); ps.setString(i++, d.fuenteId()); ps.setString(i++, d.texto());
            ps.setString(i++, d.norm()); ps.setString(i++, d.tipoVia()); ps.setString(i++, d.via());
            ps.setString(i++, d.cuadrante()); ps.setString(i++, d.cruce()); ps.setInt(i++, d.placa());
            ps.setInt(i++, d.placa2()); ps.setString(i++, d.manzana()); ps.setString(i++, d.casa());
            ps.setString(i++, d.barrio()); ps.setDouble(i++, d.lng()); ps.setDouble(i++, d.lat());
            ps.setString(i++, d.precision()); ps.setInt(i, d.confianza());
        });
        return lote.size();
    }

    public void recalcularBarrios() {
        jdbc.execute("TRUNCATE barrios");
        jdbc.execute("""
                INSERT INTO barrios (nombre, predios, ubicacion)
                SELECT barrio, COUNT(*), ST_Centroid(ST_Collect(ubicacion))
                FROM direcciones WHERE barrio <> '' GROUP BY barrio HAVING COUNT(*) >= 3
                """);
    }

    /** El catastro trunca la dirección a 32 caracteres: se completa el barrio con la variante más larga y frecuente. */
    public void completarBarriosTruncados() {
        jdbc.update("""
                UPDATE direcciones d SET barrio = m.completo
                FROM (
                    SELECT b.nombre AS corto,
                           (SELECT b2.nombre FROM barrios b2 WHERE b2.nombre LIKE b.nombre || '_%' AND b2.predios >= 3
                            ORDER BY b2.predios DESC LIMIT 1) AS completo
                    FROM barrios b WHERE length(b.nombre) >= 6
                ) m
                WHERE d.barrio = m.corto AND m.completo IS NOT NULL AND length(d.texto) >= 31
                """);
    }

    public long iniciarImportacion(String fuente) {
        Long id = jdbc.queryForObject("INSERT INTO direcciones_importaciones (fuente) VALUES (?) RETURNING id", Long.class, fuente);
        return id == null ? 0 : id;
    }

    public void cerrarImportacion(long id, String estado, int leidos, int guardados, int descartados, String mensaje) {
        jdbc.update("UPDATE direcciones_importaciones SET fin = NOW(), estado = ?, leidos = ?, guardados = ?, descartados = ?, mensaje = ? WHERE id = ?",
                estado, leidos, guardados, descartados, mensaje == null ? null : mensaje.substring(0, Math.min(300, mensaje.length())), id);
    }

    public Optional<java.time.Instant> ultimaImportacionExitosa() {
        var r = jdbc.query("SELECT fin FROM direcciones_importaciones WHERE estado = 'OK' ORDER BY fin DESC LIMIT 1",
                (rs, n) -> rs.getTimestamp("fin").toInstant());
        return r.stream().findFirst();
    }

    // ------------------------------------------------------------------ búsqueda estructurada
    /** Coincidencias exactas de vía + cruce + placa (también contra la segunda placa). */
    public List<Fila> exactas(String tipoVia, String via, String cruce, int placa, int limite) {
        return jdbc.query("SELECT " + COLUMNAS + """
                 FROM direcciones
                WHERE tipo_via = ? AND via = ? AND cruce = ? AND (placa = ? OR placa2 = ?)
                ORDER BY confianza DESC, popularidad DESC, id LIMIT ?
                """, DireccionRepository::fila, tipoVia, via, cruce, placa, placa, limite);
    }

    /** Vecina inmediata en la misma vía y cruce, con la misma paridad de placa. */
    public Optional<Fila> vecina(String tipoVia, String via, String cuadrante, String cruce, int placa, boolean mayor) {
        String sql = "SELECT " + COLUMNAS + " FROM direcciones WHERE tipo_via = ? AND via = ? AND cruce = ? AND cuadrante = ? "
                + "AND placa > 0 AND placa % 2 = ? AND placa " + (mayor ? "> ? ORDER BY placa ASC" : "< ? ORDER BY placa DESC") + " LIMIT 1";
        return jdbc.query(sql, DireccionRepository::fila, tipoVia, via, cruce, cuadrante, placa % 2, placa).stream().findFirst();
    }

    /** Cruces conocidos de una vía, con el punto medio de sus predios, para estimar un cruce que no está en la base. */
    public List<double[]> crucesDeVia(String tipoVia, String via, String cuadrante) {
        return jdbc.query("""
                SELECT c, AVG(lat) AS lat, AVG(lng) AS lng FROM (
                    SELECT NULLIF(regexp_replace(cruce, '[^0-9]', '', 'g'), '')::int AS c,
                           ST_Y(ubicacion) AS lat, ST_X(ubicacion) AS lng
                    FROM direcciones
                    WHERE tipo_via = ? AND via = ? AND cuadrante = ? AND cruce <> ''
                ) s WHERE c IS NOT NULL GROUP BY c ORDER BY c
                """, (rs, n) -> new double[]{rs.getInt("c"), rs.getDouble("lat"), rs.getDouble("lng")}, tipoVia, via, cuadrante);
    }

    /** Esquina aproximada: punto medio de los predios de una vía sobre su cruce con otra (en cualquiera de los dos sentidos). */
    public Optional<Punto> esquina(String tipo1, String via1, String tipo2, String via2) {
        return jdbc.query("""
                SELECT AVG(ST_Y(ubicacion)) AS lat, AVG(ST_X(ubicacion)) AS lng, COUNT(*) AS n
                FROM direcciones
                WHERE (tipo_via = ? AND via = ? AND cruce = ?) OR (tipo_via = ? AND via = ? AND cruce = ?)
                HAVING COUNT(*) > 0
                """, (rs, n) -> new Punto(rs.getDouble("lat"), rs.getDouble("lng"), rs.getInt("n")),
                tipo1, via1, via2, tipo2, via2, via1).stream().findFirst();
    }

    /** Barrio + manzana (+ casa). El barrio del catastro puede estar truncado: se compara por prefijo en ambos sentidos. */
    public List<Fila> porManzana(String barrio, String manzana, String casa, int limite) {
        String filtroCasa = casa.isEmpty() ? "" : " AND casa = ?";
        Object[] args = casa.isEmpty() ? new Object[]{barrio, barrio, manzana, limite} : new Object[]{barrio, barrio, manzana, casa, limite};
        return jdbc.query("SELECT " + COLUMNAS + " FROM direcciones WHERE barrio <> '' AND (barrio LIKE ? || '%' OR ? LIKE barrio || '%') "
                + "AND manzana = ?" + filtroCasa + " ORDER BY confianza DESC, id LIMIT ?", DireccionRepository::fila, args);
    }

    // ------------------------------------------------------------------ autocompletado
    public List<Fila> sugerirPorVia(String tipoVia, String via, String cuadrante, String crucePrefijo, String placaPrefijo, int limite) {
        StringBuilder sql = new StringBuilder("SELECT " + COLUMNAS + " FROM direcciones WHERE tipo_via = ? AND via = ? AND placa > 0");
        List<Object> args = new java.util.ArrayList<>(List.of(tipoVia, via));
        if (!cuadrante.isEmpty()) { sql.append(" AND cuadrante = ?"); args.add(cuadrante); }
        if (!crucePrefijo.isEmpty()) { sql.append(" AND cruce LIKE ?"); args.add(crucePrefijo + "%"); }
        if (!placaPrefijo.isEmpty()) { sql.append(" AND placa::text LIKE ?"); args.add(placaPrefijo + "%"); }
        sql.append(" ORDER BY popularidad DESC, cruce, placa LIMIT ?");
        args.add(limite);
        return jdbc.query(sql.toString(), DireccionRepository::fila, args.toArray());
    }

    public record BarrioFila(String nombre, int predios, double lat, double lng) {}

    public List<BarrioFila> buscarBarrios(String consulta, int limite) {
        return jdbc.query("""
                SELECT nombre, predios, ST_Y(ubicacion) AS lat, ST_X(ubicacion) AS lng
                FROM barrios
                WHERE predios >= 8 AND (nombre LIKE ? || '%' OR nombre % ?)
                ORDER BY (nombre LIKE ? || '%') DESC, similarity(nombre, ?) DESC, predios DESC
                LIMIT ?
                """, (rs, n) -> new BarrioFila(rs.getString("nombre"), rs.getInt("predios"), rs.getDouble("lat"), rs.getDouble("lng")),
                consulta, consulta, consulta, consulta, limite);
    }

    public void sumarPopularidad(long id) {
        jdbc.update("UPDATE direcciones SET popularidad = LEAST(popularidad + 1, 100000) WHERE id = ?", id);
    }

    // ------------------------------------------------------------------ aprendizaje
    public void guardarConfirmacion(String clave, double lat, double lng) {
        jdbc.update("INSERT INTO direccion_confirmaciones (clave, ubicacion) VALUES (?, ST_SetSRID(ST_MakePoint(?, ?), 4326))", clave, lng, lat);
    }

    /** Mayor grupo de confirmaciones coherentes (≈ 55 m, mínimo 3) para una clave. */
    public Optional<Punto> confirmadaPorUsuarios(String clave) {
        return jdbc.query("""
                SELECT ST_Y(c) AS lat, ST_X(c) AS lng, n FROM (
                    SELECT ST_Centroid(ST_Collect(ubicacion)) AS c, COUNT(*) AS n FROM (
                        SELECT ubicacion, ST_ClusterDBSCAN(ubicacion, eps := 0.0005, minpoints := 3) OVER () AS cid
                        FROM direccion_confirmaciones WHERE clave = ?
                    ) s WHERE cid IS NOT NULL GROUP BY cid
                ) x ORDER BY n DESC LIMIT 1
                """, (rs, n) -> new Punto(rs.getDouble("lat"), rs.getDouble("lng"), rs.getInt("n")), clave).stream().findFirst();
    }

    // ------------------------------------------------------------------ lugares con nombre (OpenStreetMap / equipo)
    public record NuevoPoi(String fuente, String fuenteId, String nombre, String norm, String categoria, String direccion,
                           double lat, double lng, int relevancia) {}

    public record PoiFila(long id, String nombre, String categoria, String direccion, double lat, double lng, String fuente) {}

    public long contarPois() {
        Long n = jdbc.queryForObject("SELECT COUNT(*) FROM pois", Long.class);
        return n == null ? 0 : n;
    }

    public int guardarPois(List<NuevoPoi> lote) {
        if (lote.isEmpty()) return 0;
        jdbc.batchUpdate("""
                INSERT INTO pois (fuente, fuente_id, nombre, norm, categoria, direccion, ubicacion, relevancia, actualizado_en)
                VALUES (?, ?, ?, ?, ?, ?, ST_SetSRID(ST_MakePoint(?, ?), 4326), ?, NOW())
                ON CONFLICT (fuente, fuente_id) DO UPDATE SET nombre = EXCLUDED.nombre, norm = EXCLUDED.norm,
                    categoria = EXCLUDED.categoria, direccion = EXCLUDED.direccion, ubicacion = EXCLUDED.ubicacion,
                    relevancia = EXCLUDED.relevancia, actualizado_en = NOW()
                """, lote, lote.size(), (ps, d) -> {
            ps.setString(1, d.fuente()); ps.setString(2, d.fuenteId()); ps.setString(3, d.nombre()); ps.setString(4, d.norm());
            ps.setString(5, d.categoria()); ps.setString(6, d.direccion()); ps.setDouble(7, d.lng()); ps.setDouble(8, d.lat());
            ps.setInt(9, d.relevancia());
        });
        return lote.size();
    }

    /** Coincidencia por prefijo, por palabra o por parecido (pg_trgm). Lo más relevante y popular primero. */
    public List<PoiFila> buscarPois(String norm, int limite) {
        return jdbc.query("""
                SELECT id, nombre, categoria, direccion, ST_Y(ubicacion) AS lat, ST_X(ubicacion) AS lng, fuente
                FROM pois
                WHERE norm LIKE ? || '%' OR norm LIKE '% ' || ? || '%' OR norm % ?
                ORDER BY (norm LIKE ? || '%') DESC, similarity(norm, ?) DESC, popularidad DESC, relevancia DESC
                LIMIT ?
                """, (rs, n) -> new PoiFila(rs.getLong("id"), rs.getString("nombre"), rs.getString("categoria"),
                rs.getString("direccion"), rs.getDouble("lat"), rs.getDouble("lng"), rs.getString("fuente")),
                norm, norm, norm, norm, norm, limite);
    }

    public void borrarPoisOsmAnteriores(java.time.Instant desde) {
        jdbc.update("DELETE FROM pois WHERE fuente = 'OSM' AND actualizado_en < ?", java.sql.Timestamp.from(desde));
    }

    public void sumarPopularidadPoi(long id) {
        jdbc.update("UPDATE pois SET popularidad = LEAST(popularidad + 1, 100000) WHERE id = ?", id);
    }

    /** Corrección del equipo para una dirección completa: tiene prioridad sobre el catastro. */
    public void corregirDireccion(Nueva d) { guardarLote(List.of(d)); }

    public void descartarSinResultado(String consulta) {
        jdbc.update("DELETE FROM busquedas_sin_resultado WHERE consulta = ?", consulta);
    }

    public void registrarSinResultado(String consulta) {
        jdbc.update("""
                INSERT INTO busquedas_sin_resultado (consulta) VALUES (?)
                ON CONFLICT (consulta) DO UPDATE SET veces = busquedas_sin_resultado.veces + 1, ultima = NOW()
                """, consulta);
    }

    public List<String[]> topSinResultado(int limite) {
        return jdbc.query("SELECT consulta, veces FROM busquedas_sin_resultado ORDER BY veces DESC, ultima DESC LIMIT ?",
                (rs, n) -> new String[]{rs.getString("consulta"), String.valueOf(rs.getInt("veces"))}, limite);
    }
}
