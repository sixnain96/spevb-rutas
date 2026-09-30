package co.edu.usta.spevb.service;

import co.edu.usta.spevb.dto.*;
import co.edu.usta.spevb.exception.RutaNoEncontradaException;
import co.edu.usta.spevb.repository.RutaRepository;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;

@Service
public class RutaService {

    private final RutaRepository rutaRepository;
    private final ObjectMapper objectMapper;

    public RutaService(RutaRepository rutaRepository, ObjectMapper objectMapper) {
        this.rutaRepository = rutaRepository;
        this.objectMapper = objectMapper;
    }

    public List<RutaResumenResponse> listarRutasActivas() {
        return rutaRepository.findAllActivas();
    }

    public RutaDetalleResponse obtenerRuta(Long id) {
        RutaRepository.RutaDetalleRow row = rutaRepository.findById(id)
                .orElseThrow(() -> new RutaNoEncontradaException(id));

        try {
            List<ParaderoResponse> paraderos = new ArrayList<>();
            for (RutaRepository.ParaderoRow p : rutaRepository.findParaderos(id)) {
                paraderos.add(new ParaderoResponse(
                        p.id(), p.secuencia(), p.nombre(), objectMapper.readTree(p.geojson())
                ));
            }

            return new RutaDetalleResponse(
                    row.id(), row.codigo(), row.nombre(), row.origen(), row.destino(), row.sentido(),
                    row.descripcion(), row.color(), row.distanciaKm(), row.activa(), row.demostrativa(),
                    row.validada(), row.fuente(), objectMapper.readTree(row.inicioGeojson()),
                    objectMapper.readTree(row.finGeojson()), objectMapper.readTree(row.geojson()), paraderos
            );
        } catch (JacksonException e) {
            throw new IllegalStateException("No se pudo convertir la geometría de la ruta a GeoJSON", e);
        }
    }

    public void actualizarRecorrido(Long id, ActualizarRecorridoRequest request) {
        if (request == null || request.recorrido() == null ||
                !"LineString".equals(request.recorrido().path("type").asText())) {
            throw new IllegalArgumentException("El recorrido debe ser un GeoJSON LineString válido.");
        }

        String geojson = request.recorrido().toString();
        boolean actualizado = rutaRepository.actualizarRecorrido(
                id, geojson, request.origen(), request.destino(), request.fuente(), request.validada()
        );
        if (!actualizado) {
            throw new RutaNoEncontradaException(id);
        }
    }
}
