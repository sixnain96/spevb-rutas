package co.edu.usta.spevb.dto;

import jakarta.validation.constraints.*;
import tools.jackson.databind.JsonNode;

public record CrearRutaRequest(
        @NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{2,20}") String codigo,
        @NotBlank @Size(max = 120) String nombre,
        @NotBlank @Size(max = 160) String origen,
        @NotBlank @Size(max = 160) String destino,
        @NotBlank @Pattern(regexp = "IDA|REGRESO|CIRCULAR") String sentido,
        @Size(max = 1000) String descripcion,
        @NotBlank @Pattern(regexp = "#[0-9A-Fa-f]{6}") String color,
        @Size(max = 300) String fuente,
        boolean validada,
        @Size(max = 500) String notaValidacion,
        @NotNull JsonNode recorrido
) {}
