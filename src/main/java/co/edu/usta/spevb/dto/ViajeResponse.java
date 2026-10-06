package co.edu.usta.spevb.dto;

import tools.jackson.databind.JsonNode;
import java.util.List;

public record ViajeResponse(List<Opcion> opciones, List<Opcion> alternativas, String alcance) {
    public record Opcion(long rutaId, String codigo, String nombre, String color, boolean validada,
                         double acercamientoInicioMetros, double acercamientoFinMetros, double recorridoKm,
                         double accesoTotalMetros, boolean dentroLimite, String sentidoViaje,
                         String sentidoRuta, boolean sentidoPermitido,
                         JsonNode puntoSubida, JsonNode puntoBajada, JsonNode tramo,
                         String advertencia) {}
}
