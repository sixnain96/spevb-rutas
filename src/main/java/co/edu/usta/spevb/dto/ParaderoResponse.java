package co.edu.usta.spevb.dto;

import tools.jackson.databind.JsonNode;

public record ParaderoResponse(
        Long id,
        Integer secuencia,
        String nombre,
        JsonNode ubicacion
) {
}
