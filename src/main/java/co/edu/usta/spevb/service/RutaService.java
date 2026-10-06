package co.edu.usta.spevb.service;

import co.edu.usta.spevb.config.CityArea;
import co.edu.usta.spevb.dto.*;
import co.edu.usta.spevb.exception.ConflictoVersionException;
import co.edu.usta.spevb.exception.RutaNoEncontradaException;
import co.edu.usta.spevb.repository.RutaRepository;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;

@Service
public class RutaService {
    private final RutaRepository rutaRepository;
    private final ObjectMapper objectMapper;
    private final GeometriaValidator geometriaValidator;
    private final CityArea cityArea;

    public RutaService(RutaRepository rutaRepository, ObjectMapper objectMapper,
                       GeometriaValidator geometriaValidator, CityArea cityArea) {
        this.rutaRepository = rutaRepository;
        this.objectMapper = objectMapper;
        this.geometriaValidator = geometriaValidator;
        this.cityArea = cityArea;
    }

    public List<RutaResumenResponse> listarRutasActivas() { return rutaRepository.findAllActivas(); }
    public void comprobarConexion() { rutaRepository.comprobarConexion(); }

    @Transactional(readOnly = true, isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public RutaDetalleResponse obtenerRuta(Long id) {
        RutaRepository.RutaDetalleRow row = rutaRepository.findById(id)
                .orElseThrow(() -> new RutaNoEncontradaException(id));
        try {
            List<ParaderoResponse> paraderos = new ArrayList<>();
            List<String> advertencias = new ArrayList<>();
            for (RutaRepository.ParaderoRow p : rutaRepository.findParaderos(id)) {
                paraderos.add(new ParaderoResponse(p.id(), p.secuencia(), p.nombre(),
                        objectMapper.readTree(p.geojson()), p.distanciaMetros()));
                if (p.distanciaMetros() > 100) {
                    advertencias.add("El paradero «" + p.nombre() + "» está a "
                            + Math.round(p.distanciaMetros()) + " m del recorrido; requiere revisión.");
                }
            }
            return new RutaDetalleResponse(row.id(), row.codigo(), row.nombre(), row.origen(), row.destino(),
                    row.sentido(), row.descripcion(), row.color(), row.distanciaKm(), row.activa(), row.demostrativa(),
                    row.validada(), row.fuente(), objectMapper.readTree(row.inicioGeojson()),
                    objectMapper.readTree(row.finGeojson()), objectMapper.readTree(row.geojson()), paraderos,
                    row.version(), row.actualizadoEn(), row.notaValidacion(), advertencias);
        } catch (JacksonException e) {
            throw new IllegalStateException("No se pudo convertir la geometría de la ruta a GeoJSON", e);
        }
    }

    @Transactional
    public RutaDetalleResponse crearRuta(CrearRutaRequest request) {
        geometriaValidator.validar(request.recorrido());
        validarEnVillavicencio(request.recorrido());
        String fuente = safe(request.fuente());
        String nota = safe(request.notaValidacion());
        validarRevision(request.validada(), fuente, nota);
        try {
            Long id = rutaRepository.crear(request, request.recorrido().toString(), request.validada() ? nota : null);
            return obtenerRuta(id);
        } catch (DuplicateKeyException ex) {
            throw new IllegalArgumentException("Ya existe una ruta con el código " + request.codigo().strip().toUpperCase() + ".");
        }
    }

    @Transactional
    public RutaDetalleResponse actualizarRecorrido(Long id, ActualizarRecorridoRequest request) {
        geometriaValidator.validar(request.recorrido());
        validarEnVillavicencio(request.recorrido());
        RutaRepository.RutaDetalleRow actual = rutaRepository.findById(id)
                .orElseThrow(() -> new RutaNoEncontradaException(id));
        if (request.version() == null || actual.version() != request.version()) throw new ConflictoVersionException();
        String fuente = safe(request.fuente());
        String nota = safe(request.notaValidacion());
        validarRevision(request.validada(), fuente, nota);

        rutaRepository.archivar(id, actual.version());
        boolean actualizado = rutaRepository.actualizarRecorrido(id, request.recorrido().toString(),
                request.nombre().strip(), request.origen().strip(), request.destino().strip(), request.sentido(),
                safe(request.descripcion()), request.color(), fuente, request.validada(), request.version(),
                request.validada() ? nota : null);
        if (!actualizado) throw new ConflictoVersionException();
        return obtenerRuta(id);
    }

    @Transactional(readOnly = true)
    public List<RevisionResponse> revisiones(Long id) {
        rutaRepository.findById(id).orElseThrow(() -> new RutaNoEncontradaException(id));
        return rutaRepository.revisiones(id).stream().map(row ->
                new RevisionResponse(row.version(), row.archivadoEn(), objectMapper.readTree(row.datos()))).toList();
    }

    @Transactional(readOnly = true)
    /** Sin radio devuelve las rutas más cercanas sin importar la distancia; con radio, sólo las que quedan dentro. */
    public List<RutaCercanaResponse> cercanas(double lat, double lng, Integer radioMetros, boolean soloValidadas) {
        cityArea.requireInside(lat, lng);
        if (radioMetros != null && (radioMetros < 100 || radioMetros > 5000))
            throw new IllegalArgumentException("El radio debe estar entre 100 y 5.000 metros.");
        return rutaRepository.cercanas(lat, lng, radioMetros, soloValidadas).stream().map(row ->
                new RutaCercanaResponse(row.id(), row.codigo(), row.nombre(), row.origen(), row.destino(), row.color(),
                        row.validada(), row.distanciaMetros(), objectMapper.readTree(row.puntoJson()))).toList();
    }

    private void validarRevision(boolean validada, String fuente, String nota) {
        if (validada && (fuente.isBlank() || nota.isBlank())) {
            throw new IllegalArgumentException("Para validar registra la fuente y una nota con responsable, fecha y evidencia de verificación.");
        }
    }

    private void validarEnVillavicencio(JsonNode geometry) {
        for (JsonNode point : geometry.path("coordinates")) {
            cityArea.requireInside(point.get(1).asDouble(), point.get(0).asDouble());
        }
    }

    private String safe(String value) { return value == null ? "" : value.strip(); }
}
