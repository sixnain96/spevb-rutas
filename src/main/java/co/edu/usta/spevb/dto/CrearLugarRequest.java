package co.edu.usta.spevb.dto;

import jakarta.validation.constraints.*;

public record CrearLugarRequest(
        @NotBlank @Size(max = 160) String nombre,
        @Size(max = 240) String direccion,
        @DecimalMin("-90") @DecimalMax("90") double lat,
        @DecimalMin("-180") @DecimalMax("180") double lng,
        @Size(max = 200) String fuente
) {}
