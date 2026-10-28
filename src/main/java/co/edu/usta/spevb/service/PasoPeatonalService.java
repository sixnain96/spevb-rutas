package co.edu.usta.spevb.service;

import co.edu.usta.spevb.config.CityArea;
import co.edu.usta.spevb.dto.PasoPeatonalRequest;
import co.edu.usta.spevb.repository.PasoPeatonalRepository;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Correcciones del equipo a la red de calles: pasos que faltan y tramos que no se pueden recorrer. */
@Service
public class PasoPeatonalService {
    static final double LARGO_MAXIMO_METROS = 3000;
    private static final Set<String> TIPOS = Set.of("PASO", "BLOQUEO");

    private final PasoPeatonalRepository repository;
    private final RedPeatonalService red;
    private final CaminataService caminata;
    private final ViajeService viajes;
    private final CityArea area;
    private final ObjectMapper mapper;

    public PasoPeatonalService(PasoPeatonalRepository repository, RedPeatonalService red, CaminataService caminata,
                               ViajeService viajes, CityArea area, ObjectMapper mapper) {
        this.repository = repository;
        this.red = red;
        this.caminata = caminata;
        this.viajes = viajes;
        this.area = area;
        this.mapper = mapper;
    }

    public List<Map<String, Object>> listar() {
        List<Map<String, Object>> r = new ArrayList<>();
        for (var f : repository.listar())
            r.add(Map.of("id", f.id(), "tipo", f.tipo(), "nombre", f.nombre(), "nota", f.nota() == null ? "" : f.nota(),
                    "metros", Math.round(f.metros()), "trazo", mapper.readTree(f.geojson())));
        return r;
    }

    public Map<String, Object> crear(PasoPeatonalRequest request) {
        String tipo = request.tipo().strip().toUpperCase(Locale.ROOT);
        if (!TIPOS.contains(tipo)) throw new IllegalArgumentException("El tipo debe ser PASO o BLOQUEO.");
        double largo = 0;
        StringBuilder coords = new StringBuilder();
        double[] anterior = null;
        for (double[] p : request.coordenadas()) {
            if (p == null || p.length < 2) throw new IllegalArgumentException("Cada punto necesita longitud y latitud.");
            area.requireInside(p[1], p[0]);
            if (anterior != null) largo += RedPeatonal.metros(anterior[1], anterior[0], p[1], p[0]);
            anterior = p;
            if (coords.length() > 0) coords.append(',');
            coords.append(String.format(Locale.ROOT, "[%.7f,%.7f]", p[0], p[1]));
        }
        if (largo < 3) throw new IllegalArgumentException("El trazo es demasiado corto.");
        if (largo > LARGO_MAXIMO_METROS) throw new IllegalArgumentException("El trazo no puede superar 3 km: divídelo en varios.");
        String nota = request.nota() == null ? null : request.nota().strip();
        long id = repository.crear(tipo, request.nombre().strip(), nota == null || nota.isEmpty() ? null : nota,
                "{\"type\":\"LineString\",\"coordinates\":[" + coords + "]}");
        var conexiones = aplicar();
        Integer c = conexiones.get(id);
        String mensaje;
        if ("BLOQUEO".equals(tipo)) mensaje = conexiones.isEmpty() ? "Guardado. Se aplicará cuando la red de calles termine de cargar."
                : "Guardado. Las calles que pasan por ese trazo dejaron de ser transitables.";
        else if (c == null) mensaje = "Guardado. Se aplicará cuando la red de calles termine de cargar.";
        else if (c == 2) mensaje = "Guardado y conectado a la red por sus dos extremos.";
        else mensaje = "ATENCIÓN: solo " + c + " de sus 2 extremos quedó conectado a una calle. Un paso útil debe empezar y terminar "
                + "en una esquina o sobre una calle (a menos de 25 m). Bórralo y dibújalo de nuevo con los extremos sobre las calles.";
        return Map.of("id", id, "tipo", tipo, "metros", Math.round(largo), "conectados", c == null ? -1 : c, "mensaje", mensaje);
    }

    public boolean borrar(long id) {
        boolean borrado = repository.borrar(id);
        if (borrado) aplicar();
        return borrado;
    }

    /** Reconstruye la red con los pasos actuales y descarta los cálculos guardados. */
    private Map<Long, Integer> aplicar() {
        var conexiones = red.aplicarPasos();
        caminata.invalidarCache();
        viajes.invalidarCache();
        return conexiones;
    }
}
