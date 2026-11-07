package co.edu.usta.spevb.dto;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

/**
 * {@code maxCaminataMetros} es opcional y ya no filtra rutas: el planificador siempre devuelve las
 * rutas registradas más cercanas. Sólo sirve para marcar si la caminata es corta ({@code dentroLimite}).
 */
public record PlanificarViajeRequest(@NotNull @Valid Punto origen, @NotNull @Valid Punto destino,
                                     @Min(200) @Max(3000) Integer maxCaminataMetros, boolean soloValidadas) {
    public static final int CAMINATA_REFERENCIA_METROS = 1000;

    public int caminataReferencia() {
        return maxCaminataMetros == null ? CAMINATA_REFERENCIA_METROS : maxCaminataMetros;
    }

    public record Punto(@DecimalMin("-90") @DecimalMax("90") double lat,
                        @DecimalMin("-180") @DecimalMax("180") double lng) {}
}
