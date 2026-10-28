package co.edu.usta.spevb.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public class PasoPeatonalRepository {
    private final JdbcTemplate jdbc;
    public PasoPeatonalRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    /** {@code geojson} es un LineString con coordenadas [lng, lat]. */
    public record Fila(long id, String tipo, String nombre, String nota, String geojson, double metros) {}

    public List<Fila> listar() {
        return jdbc.query("""
            SELECT id, tipo, nombre, nota, ST_AsGeoJSON(trazo, 7) AS geojson, ST_Length(trazo::geography) AS metros
            FROM pasos_peatonales ORDER BY id
            """, (rs, n) -> new Fila(rs.getLong("id"), rs.getString("tipo"), rs.getString("nombre"),
                rs.getString("nota"), rs.getString("geojson"), rs.getDouble("metros")));
    }

    public long crear(String tipo, String nombre, String nota, String lineStringGeoJson) {
        return jdbc.queryForObject("""
            INSERT INTO pasos_peatonales (tipo, nombre, nota, trazo)
            VALUES (?, ?, ?, ST_SetSRID(ST_GeomFromGeoJSON(?), 4326)) RETURNING id
            """, Long.class, tipo, nombre, nota, lineStringGeoJson);
    }

    public boolean borrar(long id) {
        return jdbc.update("DELETE FROM pasos_peatonales WHERE id = ?", id) > 0;
    }
}
