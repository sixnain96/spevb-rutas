package co.edu.usta.spevb.dto;

import tools.jackson.databind.JsonNode;

public record ActualizarRecorridoRequest(
        JsonNode recorrido,
        String origen,
        String destino,
        String fuente,
        boolean validada
) {
}
