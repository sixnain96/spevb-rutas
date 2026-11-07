package co.edu.usta.spevb.dto;

/** precision identifica el tipo de punto, NO certifica su exactitud física. */
public record LugarResponse(
        Long id,
        String nombre,
        String direccion,
        double lat,
        double lng,
        String fuente,
        boolean confirmado,
        String precision
) {
    // Compatible con el contrato de los lugares anteriores.
    public LugarResponse(Long id, String nombre, String direccion, double lat,
                         double lng, String fuente, boolean confirmado) {
        this(id, nombre, direccion, lat, lng, fuente, confirmado,
                confirmado ? "VERIFICADO_LOCAL" : "SIN_VERIFICAR");
    }
}
