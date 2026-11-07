package co.edu.usta.spevb.service;

import co.edu.usta.spevb.config.CityArea;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Paraderos de bus propios del sistema, independientes de las rutas. El administrador los pone en el mapa; el planificador los
 * prefiere como punto de subida y bajada cuando están sobre la ruta (ver {@code ViajeRepository}) y la gente los ve en el mapa.
 */
@Service
public class ParaderoBusService {
    public static final int MAXIMO_TOTAL = 20_000;
    public static final int MAXIMO_LOTE = 500;
    /** Una ruta «sirve» a un paradero si pasa a esta distancia o menos de él. */
    public static final double DISTANCIA_RUTA_M = 30;
    private final JdbcTemplate jdbc;
    private final CityArea area;
    private final org.springframework.beans.factory.ObjectProvider<ViajeService> viajes;

    public ParaderoBusService(JdbcTemplate jdbc, CityArea area, org.springframework.beans.factory.ObjectProvider<ViajeService> viajes) {
        this.jdbc = jdbc;
        this.area = area;
        this.viajes = viajes;
    }

    /** Los planes de viaje guardados en memoria (90 s) ya no valen si cambian los paraderos. */
    private void cambioHecho() { var v = viajes.getIfAvailable(); if (v != null) v.invalidarCache(); }

    public record Entrada(String nombre, String nota, double lat, double lng) {}
    public record Paradero(long id, String nombre, String nota, double lat, double lng, boolean activo, List<RutaCerca> rutas) {}
    public record RutaCerca(String codigo, String nombre, long metros) {}
    public record Punto(long id, String nombre, double lat, double lng) {}
    public record ResultadoLote(int creados, int repetidos) {}

    // ------------------------------------------------------------------ lectura
    /** Paraderos activos dentro de una caja (para dibujarlos en el mapa). Acota la caja y la cantidad para no saturar. */
    public List<Punto> enCaja(double sur, double oeste, double norte, double este, int limite) {
        if (!(sur < norte && oeste < este)) throw new IllegalArgumentException("La zona pedida no es válida.");
        if (norte - sur > 0.25 || este - oeste > 0.25) throw new IllegalArgumentException("Acerca el mapa para ver los paraderos.");
        return jdbc.query("""
                SELECT id, nombre, ST_Y(ubicacion) AS lat, ST_X(ubicacion) AS lng FROM paraderos_buses
                WHERE activo AND ubicacion && ST_MakeEnvelope(?, ?, ?, ?, 4326) ORDER BY id LIMIT ?""",
                (rs, n) -> new Punto(rs.getLong("id"), rs.getString("nombre"), rs.getDouble("lat"), rs.getDouble("lng")),
                oeste, sur, este, norte, Math.max(1, Math.min(limite, 3000)));
    }

    /** Lista para el administrador: todos (también los desactivados), con búsqueda por nombre. */
    public List<Paradero> buscar(String q, int limite) {
        String filtro = q == null ? "" : q.strip();
        var filas = jdbc.queryForList("""
                SELECT id, nombre, nota, activo, ST_Y(ubicacion) AS lat, ST_X(ubicacion) AS lng FROM paraderos_buses
                WHERE (? = '' OR translate(lower(nombre), 'áéíóúüñ', 'aeiouun') LIKE '%' || translate(lower(?), 'áéíóúüñ', 'aeiouun') || '%') ORDER BY actualizado_en DESC, id DESC LIMIT ?""",
                filtro, filtro, Math.max(1, Math.min(limite, 500)));
        List<Paradero> salida = new ArrayList<>();
        for (var f : filas) salida.add(armar(f, false));
        return salida;
    }

    public Paradero obtener(long id) {
        var filas = jdbc.queryForList("SELECT id, nombre, nota, activo, ST_Y(ubicacion) AS lat, ST_X(ubicacion) AS lng FROM paraderos_buses WHERE id = ?", id);
        if (filas.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No existe ese paradero.");
        return armar(filas.get(0), true);
    }

    private Paradero armar(Map<String, Object> f, boolean conRutas) {
        double lat = ((Number) f.get("lat")).doubleValue(), lng = ((Number) f.get("lng")).doubleValue();
        return new Paradero(((Number) f.get("id")).longValue(), (String) f.get("nombre"), (String) f.get("nota"), lat, lng,
                Boolean.TRUE.equals(f.get("activo")), conRutas ? rutasCerca(lat, lng) : List.of());
    }

    /** Rutas activas que pasan a menos de {@link #DISTANCIA_RUTA_M} del punto: las que este paradero sirve. */
    public List<RutaCerca> rutasCerca(double lat, double lng) {
        return jdbc.query("""
                SELECT r.codigo, r.nombre, ST_Distance(r.recorrido::geography, p.g::geography) AS d
                FROM rutas r, (SELECT ST_SetSRID(ST_MakePoint(?, ?), 4326) AS g) p
                WHERE r.activa AND ST_NPoints(r.recorrido) >= 2 AND r.recorrido && ST_Expand(p.g, 0.001)
                  AND ST_DWithin(r.recorrido::geography, p.g::geography, ?) ORDER BY d, r.codigo""",
                (rs, n) -> new RutaCerca(rs.getString("codigo"), rs.getString("nombre"), Math.round(rs.getDouble("d"))), lng, lat, DISTANCIA_RUTA_M);
    }

    // ------------------------------------------------------------------ escritura
    private String nombre(String n, int pos) {
        String s = n == null ? "" : n.replaceAll("[\\p{Cntrl}]", " ").strip();
        String etiqueta = pos > 0 ? "Paradero " + pos + ": " : "";
        if (s.length() < 2) throw new IllegalArgumentException(etiqueta + "escribe un nombre (mínimo 2 letras).");
        return s.length() > 160 ? s.substring(0, 160) : s;
    }

    private static String nota(String n) {
        String s = n == null ? "" : n.replaceAll("[\\p{Cntrl}&&[^\\n]]", " ").strip();
        return s.isEmpty() ? null : (s.length() > 300 ? s.substring(0, 300) : s);
    }

    private void punto(double lat, double lng) {
        if (!area.contains(lat, lng)) throw new IllegalArgumentException("El paradero está fuera del área de Villavicencio.");
    }

    @Transactional
    public Paradero crear(Entrada e) {
        String n = nombre(e.nombre(), 0);
        punto(e.lat(), e.lng());
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM paraderos_buses", Long.class);
        if (total != null && total >= MAXIMO_TOTAL) throw new IllegalArgumentException("Se alcanzó el máximo de " + MAXIMO_TOTAL + " paraderos.");
        long id = jdbc.queryForObject("INSERT INTO paraderos_buses (nombre, nota, ubicacion) VALUES (?, ?, ST_SetSRID(ST_MakePoint(?, ?), 4326)) RETURNING id",
                Long.class, n, nota(e.nota()), e.lng(), e.lat());
        cambioHecho();
        return obtener(id);
    }

    @Transactional
    public Paradero actualizar(long id, Entrada e, Boolean activo) {
        String n = nombre(e.nombre(), 0);
        punto(e.lat(), e.lng());
        int filas = jdbc.update("""
                UPDATE paraderos_buses SET nombre = ?, nota = ?, ubicacion = ST_SetSRID(ST_MakePoint(?, ?), 4326),
                       activo = COALESCE(?, activo), actualizado_en = NOW() WHERE id = ?""", n, nota(e.nota()), e.lng(), e.lat(), activo, id);
        if (filas == 0) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No existe ese paradero.");
        cambioHecho();
        return obtener(id);
    }

    @Transactional
    public void eliminar(long id) {
        if (jdbc.update("DELETE FROM paraderos_buses WHERE id = ?", id) == 0) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No existe ese paradero.");
        cambioHecho();
    }

    /** Importación masiva (GeoJSON/CSV desde el editor): valida todo antes de guardar y omite los que ya existen (mismo nombre a menos de 5 m). */
    @Transactional
    public ResultadoLote crearLote(List<Entrada> lista) {
        if (lista == null || lista.isEmpty()) throw new IllegalArgumentException("No hay paraderos para importar.");
        if (lista.size() > MAXIMO_LOTE) throw new IllegalArgumentException("Importa máximo " + MAXIMO_LOTE + " paraderos por vez.");
        int pos = 0;
        List<Entrada> validos = new ArrayList<>();
        for (Entrada e : lista) {
            pos++;
            String n = nombre(e.nombre(), pos);
            if (!area.contains(e.lat(), e.lng())) throw new IllegalArgumentException("Paradero " + pos + " («" + n + "»): está fuera del área de Villavicencio.");
            validos.add(new Entrada(n, nota(e.nota()), e.lat(), e.lng()));
        }
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM paraderos_buses", Long.class);
        if (total != null && total + validos.size() > MAXIMO_TOTAL) throw new IllegalArgumentException("Se superaría el máximo de " + MAXIMO_TOTAL + " paraderos.");
        int creados = 0, repetidos = 0;
        for (Entrada e : validos) {
            Long ya = jdbc.queryForObject("""
                    SELECT COUNT(*) FROM paraderos_buses WHERE lower(nombre) = lower(?)
                      AND ST_DWithin(ubicacion::geography, ST_SetSRID(ST_MakePoint(?, ?), 4326)::geography, 5)""", Long.class, e.nombre(), e.lng(), e.lat());
            if (ya != null && ya > 0) { repetidos++; continue; }
            jdbc.update("INSERT INTO paraderos_buses (nombre, nota, ubicacion) VALUES (?, ?, ST_SetSRID(ST_MakePoint(?, ?), 4326))", e.nombre(), e.nota(), e.lng(), e.lat());
            creados++;
        }
        if (creados > 0) cambioHecho();
        return new ResultadoLote(creados, repetidos);
    }
}
