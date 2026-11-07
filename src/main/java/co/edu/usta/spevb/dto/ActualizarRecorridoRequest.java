package co.edu.usta.spevb.dto;

import jakarta.validation.constraints.*;
import tools.jackson.databind.JsonNode;

/**
 * Edición de una ruta existente. {@code codigo}, {@code activa} y {@code confirmarCambioCodigo} son opcionales (compatibilidad con
 * clientes anteriores): sin código se conserva el actual. El id numérico de la ruta nunca cambia.
 */
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
        @Size(max = 500) String notaValidacion,
        @Pattern(regexp = "[A-Za-z0-9_-]{2,20}") String codigo,
        Boolean activa,
        Boolean confirmarCambioCodigo
) {
    /** Constructor con la forma anterior (sin código, estado ni confirmación). */
    public ActualizarRecorridoRequest(JsonNode recorrido, String nombre, String origen, String destino, String sentido, String descripcion,
                                      String color, String fuente, boolean validada, Long version, String notaValidacion) {
        this(recorrido, nombre, origen, destino, sentido, descripcion, color, fuente, validada, version, notaValidacion, null, null, null);
    }
}
