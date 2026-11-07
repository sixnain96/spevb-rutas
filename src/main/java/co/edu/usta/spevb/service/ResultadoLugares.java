package co.edu.usta.spevb.service;

import co.edu.usta.spevb.dto.LugarResponse;
import java.util.List;

/** Lugares resueltos por un proveedor y cuántos descartó por quedar fuera de Villavicencio (para distinguir «fuera de la ciudad» de «sin coincidencias»). */
public record ResultadoLugares(List<LugarResponse> lugares, int fueraDeCiudad) {
    public static final ResultadoLugares VACIO = new ResultadoLugares(List.of(), 0);
}
