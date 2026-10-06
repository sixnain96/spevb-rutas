package co.edu.usta.spevb.dto;

import tools.jackson.databind.JsonNode;
import jakarta.validation.constraints.*;

public record ActualizarRecorridoRequest(
        @NotNull JsonNode recorrido,
        @NotBlank @Size(max = 120) String nombre,
        @NotBlank @Size(max = 160) String origen,
        @NotBlank @Size(max = 160) String destino,
        @NotBlank @Pattern(regexp = "IDA|REGRESO|CIRCULAR") String sentido,
        @Size(max = 1000) String descripcion,
        @NotBlank @Pattern(regexp = "#[0-9A-Fa-f]{6}") String color,
        @Size(max = 300) String fuente,
        boolean validada,
        @NotNull @PositiveOrZero Long version,
        @Size(max = 500) String notaValidacion
) {}
