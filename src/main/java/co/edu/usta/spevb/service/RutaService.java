package co.edu.usta.spevb.service;

import co.edu.usta.spevb.config.CityArea;
import co.edu.usta.spevb.dto.*;
import co.edu.usta.spevb.exception.ConflictoVersionException;
import co.edu.usta.spevb.exception.RutaNoEncontradaException;
import co.edu.usta.spevb.repository.RutaRepository;
import org.springframework.beans.factory.annotation.Autowired;
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

    private final AbordajeService abordaje;

    public RutaService(RutaRepository rutaRepository, ObjectMapper objectMapper,
                       GeometriaValidator geometriaValidator, CityArea cityArea) {
        this(rutaRepository, objectMapper, geometriaValidator, cityArea, null);
    }

    @Autowired
    public RutaService(RutaRepository rutaRepository, ObjectMapper objectMapper,
                       GeometriaValidator geometriaValidator, CityArea cityArea, AbordajeService abordaje) {
        this.rutaRepository = rutaRepository;
        this.objectMapper = objectMapper;
        this.geometriaValidator = geometriaValidator;
        this.cityArea = cityArea;
        this.abordaje = abordaje;
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

        // Código: opcional. Cambiarlo exige confirmación explícita, que no exista otra ruta (ni un alias de otra ruta) con ese código y
        // deja el anterior como alias de ESTA ruta (favoritos e historiales antiguos siguen resolviendo). El id nunca cambia.
        String nuevoCodigo = request.codigo() == null || request.codigo().isBlank() ? actual.codigo() : request.codigo().strip().toUpperCase(java.util.Locale.ROOT);
        boolean cambiaCodigo = !nuevoCodigo.equalsIgnoreCase(actual.codigo());
        if (cambiaCodigo) {
            if (!Boolean.TRUE.equals(request.confirmarCambioCodigo()))
                throw new IllegalArgumentException("Cambiar el código de " + actual.codigo() + " a " + nuevoCodigo + " requiere confirmación explícita.");
            if (rutaRepository.existeCodigo(nuevoCodigo, id))
                throw new IllegalArgumentException("Ya existe una ruta con el código " + nuevoCodigo + ".");
            var dueno = rutaRepository.rutaDeAlias(nuevoCodigo);
            if (dueno.isPresent() && dueno.get() != id)
                throw new IllegalArgumentException("El código " + nuevoCodigo + " perteneció a otra ruta; elige uno distinto.");
        }

        rutaRepository.archivar(id, actual.version());
        if (cambiaCodigo) {
            try {
                rutaRepository.registrarAlias(actual.codigo(), id);
                rutaRepository.borrarAlias(nuevoCodigo, id);
                rutaRepository.cambiarCodigo(id, nuevoCodigo);
            } catch (DuplicateKeyException ex) {
                throw new IllegalArgumentException("Ya existe una ruta con el código " + nuevoCodigo + ".");
            }
        }
        if (request.activa() != null && request.activa() != actual.activa()) rutaRepository.cambiarActiva(id, request.activa());
        boolean actualizado = rutaRepository.actualizarRecorrido(id, request.recorrido().toString(),
                request.nombre().strip(), request.origen().strip(), request.destino().strip(), request.sentido(),
                safe(request.descripcion()), request.color(), fuente, request.validada(), request.version(),
                request.validada() ? nota : null);
        if (!actualizado) throw new ConflictoVersionException();
        return obtenerRuta(id);
    }

    /** Resuelve un código vigente o anterior (alias) hacia la ruta actual. Vacío si no existe ni existió. */
    @Transactional(readOnly = true)
    public java.util.Map<String, String> nombresPopulares() { return rutaRepository.nombresPopulares(); }

    /** Nombre popular (como lo llama la gente): hasta 60 caracteres, sin saltos de línea. Vacío lo borra. Devuelve el valor guardado. */
    public String guardarNombrePopular(long id, String nombre) {
        String limpio = nombre == null ? "" : nombre.replaceAll("\\p{Cntrl}", " ").strip().replaceAll("\\s{2,}", " ");
        if (limpio.length() > 60) throw new IllegalArgumentException("El nombre popular admite hasta 60 caracteres.");
        if (!rutaRepository.guardarNombrePopular(id, limpio)) throw new co.edu.usta.spevb.exception.RutaNoEncontradaException(id);
        return limpio;
    }

    public java.util.Optional<co.edu.usta.spevb.dto.RutaCodigoResponse> resolverCodigo(String codigo) {
        if (codigo == null || !codigo.matches("[A-Za-z0-9_-]{2,20}")) return java.util.Optional.empty();
        var directo = rutaRepository.idPorCodigo(codigo);
        var id = directo.isPresent() ? directo : rutaRepository.rutaDeAlias(codigo);
        return id.flatMap(rutaRepository::findById).map(r -> new co.edu.usta.spevb.dto.RutaCodigoResponse(
                r.id(), r.codigo(), r.nombre(), !r.codigo().equalsIgnoreCase(codigo), codigo));
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
        var filas = rutaRepository.cercanas(lat, lng, radioMetros, soloValidadas);
        // Mejor punto de subida: se prueban varios candidatos por ruta y gana el de menor caminata real.
        java.util.Map<Long, AbordajeService.Candidato> mejores = new java.util.HashMap<>();
        boolean porCalles = false, exacto = false;
        // Camino exacto: con la red de calles propia se mide cada punto (cada 15 m) de cada ruta cercana.
        if (abordaje != null && !filas.isEmpty()) {
            try {
                double minimo = filas.stream().mapToDouble(RutaRepository.CercanaRow::distanciaMetros).min().orElse(0);
                var exactas = abordaje.mejoresSubidas(new double[]{lat, lng},
                        filas.stream().map(RutaRepository.CercanaRow::id).toList(), Math.min(6000, Math.max(1500, 2 * minimo + 600)));
                if (exactas != null && !exactas.isEmpty()) {
                    mejores.putAll(exactas);
                    porCalles = exacto = true;
                }
            } catch (RuntimeException ignored) { /* se usa el método aproximado */ }
        }
        if (!porCalles && abordaje != null && !filas.isEmpty()) {
            var ev = abordaje.evaluar(new double[]{lat, lng}, null,
                    filas.stream().map(RutaRepository.CercanaRow::id).toList(), 2);
            if (ev != null) {
                porCalles = ev.porCalles();
                for (var c : ev.subidas())
                    mejores.merge(c.rutaId(), c, (x, y) -> y.caminataMetros() < x.caminataMetros() ? y : x);
            }
        }
        // Afinado local de la mejor ruta: puntos a ± decenas de metros del elegido.
        if (porCalles && !exacto && !mejores.isEmpty()) {
            var primera = mejores.values().stream()
                    .min(java.util.Comparator.comparingDouble(AbordajeService.Candidato::caminataMetros)).orElseThrow();
            java.util.List<Double> vecinas = new java.util.ArrayList<>();
            double largo = Math.max(1, primera.largoMetros());
            for (double m : new double[]{35, 70, 110}) {
                if (primera.fraccion() - m / largo >= 0) vecinas.add(primera.fraccion() - m / largo);
                if (primera.fraccion() + m / largo <= 1) vecinas.add(primera.fraccion() + m / largo);
            }
            try {
                var fina = abordaje.evaluarFracciones(new double[]{lat, lng}, null,
                        java.util.Map.of(primera.rutaId(), vecinas), java.util.Map.of());
                if (fina != null && fina.porCalles())
                    fina.subidas().stream().min(java.util.Comparator.comparingDouble(AbordajeService.Candidato::caminataMetros))
                            .filter(c -> c.caminataMetros() < primera.caminataMetros())
                            .ifPresent(c -> mejores.put(c.rutaId(), c));
            } catch (RuntimeException ignored) { /* se conserva el punto de la etapa gruesa */ }
        }
        final boolean calles = porCalles;
        return filas.stream().map(row -> {
            var mejor = mejores.get(row.id());
            if (mejor == null)
                return new RutaCercanaResponse(row.id(), row.codigo(), row.nombre(), row.origen(), row.destino(),
                        row.color(), row.validada(), row.distanciaMetros(), objectMapper.readTree(row.puntoJson()), false);
            String punto = String.format(java.util.Locale.ROOT, "{\"type\":\"Point\",\"coordinates\":[%.7f,%.7f]}",
                    mejor.lng(), mejor.lat());
            return new RutaCercanaResponse(row.id(), row.codigo(), row.nombre(), row.origen(), row.destino(),
                    row.color(), row.validada(), mejor.caminataMetros(), objectMapper.readTree(punto), calles);
        }).sorted(java.util.Comparator.comparingDouble(RutaCercanaResponse::distanciaMetros)
                .thenComparing(RutaCercanaResponse::codigo)).toList();
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
