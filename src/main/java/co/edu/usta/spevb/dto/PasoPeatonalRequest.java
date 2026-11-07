package co.edu.usta.spevb.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/** {@code coordenadas}: puntos [lng, lat] en el orden en que se dibujó el paso. */
public record PasoPeatonalRequest(
        @NotBlank String tipo,
        @NotBlank @Size(min = 3, max = 120) String nombre,
        @Size(max = 300) String nota,
        @NotNull @Size(min = 2, max = 300) List<double[]> coordenadas) {}
