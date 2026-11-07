package co.edu.usta.spevb.dto;

import jakarta.validation.constraints.*;

import java.util.List;

/** Edición de un puente o paso existente: el id y el tipo no cambian; {@code version} evita pisar cambios de otra persona (409). */
public record PasoPeatonalActualizarRequest(
        @NotBlank @Size(min = 3, max = 120) String nombre,
        @Size(max = 300) String nota,
        @NotNull @Size(min = 2, max = 300) List<double[]> coordenadas,
        @NotNull @PositiveOrZero Long version) {}
