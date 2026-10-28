package co.edu.usta.spevb.dto;

import tools.jackson.databind.JsonNode;
import java.util.List;

public record ViajeResponse(List<Opcion> opciones, List<Opcion> alternativas, List<Transbordo> transbordos, String alcance) {
    public ViajeResponse(List<Opcion> opciones, List<Opcion> alternativas, String alcance) {
        this(opciones, alternativas, List.of(), alcance);
    }

    public record Opcion(long rutaId, String codigo, String nombre, String color, boolean validada,
                         double acercamientoInicioMetros, double acercamientoFinMetros, double recorridoKm,
                         double accesoTotalMetros, boolean dentroLimite, String sentidoViaje,
                         String sentidoRuta, boolean sentidoPermitido,
                         JsonNode puntoSubida, JsonNode puntoBajada, JsonNode tramo,
                         String advertencia, boolean caminataPorCalles,
                         String paraderoSubida, String paraderoBajada, WalkLeg caminataInicioRuta, WalkLeg caminataFinRuta,
                         String variante) {}

    /** Trayecto a pie ya calculado por calles (se adelanta para la mejor opción y evita dos consultas más del navegador). */
    public record WalkLeg(List<double[]> coordinates, long metros, long minutos) {}

    /** Un tramo en bus de un viaje con transbordo. */
    public record BusLeg(long rutaId, String codigo, String nombre, String color, boolean validada,
                         JsonNode puntoSubida, JsonNode puntoBajada, JsonNode tramo, double recorridoKm,
                         String paraderoSubida, String paraderoBajada) {}

    /**
     * Viaje con un transbordo: camina → bus 1 → camina (cambio) → bus 2 → camina. {@code minutos} incluye la espera estimada
     * en el cambio (no hay horarios registrados) y no la espera inicial, igual que las opciones directas.
     */
    public record Transbordo(double minutos, double caminataMetros, double busKm, int pasajes, double esperaCambioMinutos,
                             BusLeg primerBus, BusLeg segundoBus, WalkLeg caminataInicio, WalkLeg caminataCambio,
                             WalkLeg caminataFin, String advertencia) {}
}
