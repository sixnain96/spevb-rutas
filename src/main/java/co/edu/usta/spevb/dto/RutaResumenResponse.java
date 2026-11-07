package co.edu.usta.spevb.dto;

import java.math.BigDecimal;

public record RutaResumenResponse(
        Long id,
        String codigo,
        String nombre,
        String origen,
        String destino,
        String sentido,
        String color,
        BigDecimal distanciaKm,
        boolean activa,
        boolean validada
) {
}
