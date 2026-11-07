package co.edu.usta.spevb.service;

import co.edu.usta.spevb.config.CityArea;
import co.edu.usta.spevb.dto.PasoPeatonalActualizarRequest;
import co.edu.usta.spevb.dto.PasoPeatonalRequest;
import co.edu.usta.spevb.exception.ConflictoVersionException;
import co.edu.usta.spevb.dto.PuentePublicoResponse;
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
    static final double LARGO_MAXIMO_PUENTE_METROS = 600;
    private static final Set<String> TIPOS = Set.of("PASO", "BLOQUEO", "PUENTE");

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
                    "metros", Math.round(f.metros()), "trazo", mapper.readTree(f.geojson()), "version", f.version()));
        return r;
    }

    /** Solo los puentes peatonales, en su proyección pública (sin notas internas): lo que ve cualquier persona en el mapa. */
    public List<PuentePublicoResponse> listarPuentes() {
        List<PuentePublicoResponse> r = new ArrayList<>();
        for (var f : repository.listar())
            if ("PUENTE".equals(f.tipo()))
                r.add(new PuentePublicoResponse(f.id(), f.nombre(), Math.round(f.metros()), mapper.readTree(f.geojson())));
        return r;
    }

    /** Trazo ya validado: GeoJSON de un LineString y su largo en metros. */
    private record Trazo(String geojson, double largo) {}

    private Trazo validarTrazo(String tipo, List<double[]> coordenadas) {
        double largo = 0;
        StringBuilder coords = new StringBuilder();
        double[] anterior = null;
        for (double[] p : coordenadas) {
            if (p == null || p.length < 2) throw new IllegalArgumentException("Cada punto necesita longitud y latitud.");
            area.requireInside(p[1], p[0]);
            if (anterior != null) largo += RedPeatonal.metros(anterior[1], anterior[0], p[1], p[0]);
            anterior = p;
            if (coords.length() > 0) coords.append(',');
            coords.append(String.format(Locale.ROOT, "[%.7f,%.7f]", p[0], p[1]));
        }
        if (largo < 3) throw new IllegalArgumentException("El trazo es demasiado corto.");
        if ("PUENTE".equals(tipo) && largo > LARGO_MAXIMO_PUENTE_METROS)
            throw new IllegalArgumentException("Un puente peatonal no puede superar 600 m: revisa los puntos que marcaste.");
        if (largo > LARGO_MAXIMO_METROS) throw new IllegalArgumentException("El trazo no puede superar 3 km: divídelo en varios.");
        return new Trazo("{\"type\":\"LineString\",\"coordinates\":[" + coords + "]}", largo);
    }

    /**
     * Edita un puente/paso existente (mismo id y tipo). Los puentes son PEATONALES: se reconectan a la red peatonal (no a la vial de buses),
     * se reconstruye la red y se invalidan las cachés de caminata y de viajes. Vacío si no existe; 409 si otra persona lo modificó antes.
     */
    public java.util.Optional<Map<String, Object>> actualizar(long id, PasoPeatonalActualizarRequest request) {
        var actual = repository.buscar(id);
        if (actual.isEmpty()) return java.util.Optional.empty();
        String tipo = actual.get().tipo();
        var trazo = validarTrazo(tipo, request.coordenadas());
        if (actual.get().version() != request.version()) throw new ConflictoVersionException();
        String nota = request.nota() == null ? null : request.nota().strip();
        if (!repository.actualizar(id, request.nombre().strip(), nota == null || nota.isEmpty() ? null : nota, trazo.geojson(), request.version()))
            throw new ConflictoVersionException();
        var conexiones = aplicar();
        Integer c = conexiones.get(id);
        String mensaje = c == null ? "Guardado. Se aplicará cuando la red de calles termine de cargar."
                : c == 2 ? "Puente actualizado: sigue conectado a la red peatonal por sus dos extremos."
                : "ATENCIÓN: solo " + c + " de sus 2 extremos quedó conectado a una calle (a menos de 25 m). Ajusta los extremos y guarda de nuevo.";
        return java.util.Optional.of(Map.of("id", id, "tipo", tipo, "metros", Math.round(trazo.largo()), "conectados", c == null ? -1 : c,
                "version", request.version() + 1, "mensaje", mensaje));
    }

    public Map<String, Object> crear(PasoPeatonalRequest request) {
        String tipo = request.tipo().strip().toUpperCase(Locale.ROOT);
        if (!TIPOS.contains(tipo)) throw new IllegalArgumentException("El tipo debe ser PASO, PUENTE o BLOQUEO.");
        var trazo = validarTrazo(tipo, request.coordenadas());
        double largo = trazo.largo();
        String nota = request.nota() == null ? null : request.nota().strip();
        long id = repository.crear(tipo, request.nombre().strip(), nota == null || nota.isEmpty() ? null : nota,
                trazo.geojson());
        var conexiones = aplicar();
        Integer c = conexiones.get(id);
        String mensaje;
        if ("BLOQUEO".equals(tipo)) mensaje = conexiones.isEmpty() ? "Guardado. Se aplicará cuando la red de calles termine de cargar."
                : "Guardado. Las calles que pasan por ese trazo dejaron de ser transitables.";
        else if (c == null) mensaje = "Guardado. Se aplicará cuando la red de calles termine de cargar.";
        else if (c == 2) mensaje = "PUENTE".equals(tipo) ? "Puente guardado: ya se puede cruzar a pie y se usa en las rutas."
                : "Guardado y conectado a la red por sus dos extremos.";
        else mensaje = "ATENCIÓN: solo " + c + " de sus 2 extremos quedó conectado a una calle. Un paso o puente útil debe empezar y terminar "
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
