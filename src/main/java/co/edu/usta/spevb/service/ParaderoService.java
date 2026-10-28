package co.edu.usta.spevb.service;

import co.edu.usta.spevb.config.CityArea;
import co.edu.usta.spevb.exception.RutaNoEncontradaException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Paraderos de cada ruta. El editor envía la lista completa y aquí se valida y se guarda de una vez (todo o nada):
 * cada paradero debe estar dentro de la ciudad y pegado al recorrido, y la secuencia se calcula sola según su posición a lo largo de la ruta.
 */
@Service
public class ParaderoService {
    public static final int MAXIMO = 200;
    public static final double DISTANCIA_MAXIMA_M = 30;
    private final JdbcTemplate jdbc;
    private final CityArea area;
    private final org.springframework.beans.factory.ObjectProvider<ViajeService> viajes;

    public ParaderoService(JdbcTemplate jdbc, CityArea area, org.springframework.beans.factory.ObjectProvider<ViajeService> viajes) {
        this.jdbc = jdbc;
        this.area = area;
        this.viajes = viajes;
    }

    public record Entrada(String nombre, double lat, double lng) {}

    @Transactional
    public void reemplazar(long rutaId, List<Entrada> entradas) {
        Long existe = jdbc.queryForObject("SELECT COUNT(*) FROM rutas WHERE id = ?", Long.class, rutaId);
        if (existe == null || existe == 0) throw new RutaNoEncontradaException(rutaId);
        List<Entrada> lista = entradas == null ? List.of() : entradas;
        if (lista.size() > MAXIMO) throw new IllegalArgumentException("Máximo " + MAXIMO + " paraderos por ruta.");

        record Listo(String nombre, double lat, double lng, double fraccion) {}
        List<Listo> listos = new ArrayList<>();
        int n = 0;
        for (Entrada e : lista) {
            n++;
            String nombre = e.nombre() == null ? "" : e.nombre().replaceAll("[\\p{Cntrl}]", " ").strip();
            if (nombre.length() < 2) throw new IllegalArgumentException("Paradero " + n + ": escribe un nombre (mínimo 2 letras).");
            if (nombre.length() > 160) nombre = nombre.substring(0, 160);
            if (!area.contains(e.lat(), e.lng())) throw new IllegalArgumentException("Paradero «" + nombre + "»: está fuera del área de Villavicencio.");
            var fila = jdbc.queryForMap("""
                    SELECT ST_Distance(r.recorrido::geography, p.g::geography) AS distancia,
                           ST_LineLocatePoint(r.recorrido, p.g) AS fraccion
                    FROM rutas r, (SELECT ST_SetSRID(ST_MakePoint(?, ?), 4326) AS g) p WHERE r.id = ?""", e.lng(), e.lat(), rutaId);
            double distancia = ((Number) fila.get("distancia")).doubleValue();
            if (distancia > DISTANCIA_MAXIMA_M) {
                throw new IllegalArgumentException("Paradero «" + nombre + "»: queda a " + Math.round(distancia)
                        + " m del recorrido (máximo " + Math.round(DISTANCIA_MAXIMA_M) + " m). Ponlo sobre la línea de la ruta.");
            }
            listos.add(new Listo(nombre, e.lat(), e.lng(), ((Number) fila.get("fraccion")).doubleValue()));
        }
        listos.sort(Comparator.comparingDouble(Listo::fraccion));       // orden estable: empates conservan el orden recibido
        jdbc.update("DELETE FROM paraderos WHERE ruta_id = ?", rutaId);
        int secuencia = 1;
        for (Listo l : listos) {
            jdbc.update("INSERT INTO paraderos (ruta_id, secuencia, nombre, ubicacion) VALUES (?, ?, ?, ST_SetSRID(ST_MakePoint(?, ?), 4326))",
                    rutaId, secuencia++, l.nombre(), l.lng(), l.lat());
        }
        var v = viajes.getIfAvailable();
        if (v != null) v.invalidarCache();      // los planes guardados (90 s) ya no valen
    }
}
