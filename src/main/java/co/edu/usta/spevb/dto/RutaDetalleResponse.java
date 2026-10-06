package co.edu.usta.spevb.dto;

import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.util.List;

public record RutaDetalleResponse(
        Long id,
        String codigo,
        String nombre,
        String origen,
        String destino,
        String sentido,
        String descripcion,
        String color,
        BigDecimal distanciaKm,
        boolean activa,
        boolean demostrativa,
        boolean validada,
        String fuente,
        JsonNode inicio,
        JsonNode fin,
        JsonNode recorrido,
        List<ParaderoResponse> paraderos,
        long version,
        String actualizadoEn,
        String notaValidacion,
        List<String> advertencias
) {
}
