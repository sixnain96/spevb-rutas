package co.edu.usta.spevb.dto;

import tools.jackson.databind.JsonNode;

public record RutaCercanaResponse(
        long rutaId,
        String codigo,
        String nombre,
        String origen,
        String destino,
        String color,
        boolean validada,
        double distanciaMetros,
        JsonNode puntoCercano
) {}
