package co.edu.usta.spevb.dto;

import tools.jackson.databind.JsonNode;
import java.util.List;

public record ViajeResponse(List<Opcion> opciones, List<Opcion> alternativas, List<Transbordo> transbordos, String alcance,
                            Recomendacion recomendacion) {
    public ViajeResponse(List<Opcion> opciones, List<Opcion> alternativas, String alcance) {
        this(opciones, alternativas, List.of(), alcance, null);
    }

    /** Constructor anterior a la política de recomendación 2: sin {@code recomendacion} (el navegador lo trata como «reglas anteriores»). */
    public ViajeResponse(List<Opcion> opciones, List<Opcion> alternativas, List<Transbordo> transbordos, String alcance) {
        this(opciones, alternativas, transbordos, alcance, null);
    }

    /**
     * Qué opción se recomienda y por qué (política versión {@code politicaVersion}, ver docs/CONTRATO-RECOMENDACION-Y-BUSQUEDA.md).
     * {@code ranking} lista TODAS las opciones comparadas en orden de recomendación; la primera es {@code idRecomendada} si existe.
     */
    public record Recomendacion(int politicaVersion, String idRecomendada, String motivo, List<Item> ranking) {}

    /**
     * Una opción comparada. {@code tipo}: DIRECTA | DIRECTA_MAS_RAPIDA | BAJAR_Y_CAMINAR | SOLO_SEGUNDA_RUTA | SENTIDO_CONTRARIO | TRANSBORDO.
     * {@code calidadDistancia}: CALLES (red peatonal) | RECTA (línea recta aproximada). {@code etiquetas}: SIN_TRANSBORDO, PRIMER_BUS_MAS_CERCANO,
     * MENOR_CAMINATA_TOTAL, MENOR_TIEMPO; solo si esa propiedad se comparó de verdad y la opción es la mejor sin empate.
     */
    public record Item(String id, String tipo, int orden, boolean elegible, String motivoNoElegible,
                       double caminataInicialMetros, double caminataCambioMetros, double caminataFinalMetros, double caminataTotalMetros,
                       double minutos, int buses, String calidadDistancia, List<String> etiquetas) {}

    /** {@code variante}: MENOS_CAMINATA, MAS_RAPIDA, BAJAR_Y_CAMINAR (bus 1 y caminar hasta B) o SOLO_SEGUNDA_RUTA (caminar y tomar solo la 2.ª ruta). */
    public record Opcion(long rutaId, String codigo, String nombre, String color, boolean validada,
                         double acercamientoInicioMetros, double acercamientoFinMetros, double recorridoKm,
                         double accesoTotalMetros, boolean dentroLimite, String sentidoViaje,
                         String sentidoRuta, boolean sentidoPermitido,
                         JsonNode puntoSubida, JsonNode puntoBajada, JsonNode tramo,
                         String advertencia, boolean caminataPorCalles,
                         String paraderoSubida, String paraderoBajada, WalkLeg caminataInicioRuta, WalkLeg caminataFinRuta,
                         String variante, String id) {
        /** Constructor anterior al {@code id}: el servicio lo rellena con {@link #conId(String)}. */
        public Opcion(long rutaId, String codigo, String nombre, String color, boolean validada,
                      double acercamientoInicioMetros, double acercamientoFinMetros, double recorridoKm,
                      double accesoTotalMetros, boolean dentroLimite, String sentidoViaje,
                      String sentidoRuta, boolean sentidoPermitido,
                      JsonNode puntoSubida, JsonNode puntoBajada, JsonNode tramo,
                      String advertencia, boolean caminataPorCalles,
                      String paraderoSubida, String paraderoBajada, WalkLeg caminataInicioRuta, WalkLeg caminataFinRuta,
                      String variante) {
            this(rutaId, codigo, nombre, color, validada, acercamientoInicioMetros, acercamientoFinMetros, recorridoKm, accesoTotalMetros,
                    dentroLimite, sentidoViaje, sentidoRuta, sentidoPermitido, puntoSubida, puntoBajada, tramo, advertencia, caminataPorCalles,
                    paraderoSubida, paraderoBajada, caminataInicioRuta, caminataFinRuta, variante, null);
        }

        public Opcion conId(String nuevo) {
            return new Opcion(rutaId, codigo, nombre, color, validada, acercamientoInicioMetros, acercamientoFinMetros, recorridoKm,
                    accesoTotalMetros, dentroLimite, sentidoViaje, sentidoRuta, sentidoPermitido, puntoSubida, puntoBajada, tramo, advertencia,
                    caminataPorCalles, paraderoSubida, paraderoBajada, caminataInicioRuta, caminataFinRuta, variante, nuevo);
        }
    }

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
                             WalkLeg caminataFin, String advertencia, String id) {
        /** Constructor anterior al {@code id}. */
        public Transbordo(double minutos, double caminataMetros, double busKm, int pasajes, double esperaCambioMinutos,
                          BusLeg primerBus, BusLeg segundoBus, WalkLeg caminataInicio, WalkLeg caminataCambio,
                          WalkLeg caminataFin, String advertencia) {
            this(minutos, caminataMetros, busKm, pasajes, esperaCambioMinutos, primerBus, segundoBus, caminataInicio, caminataCambio,
                    caminataFin, advertencia, null);
        }

        public Transbordo conId(String nuevo) {
            return new Transbordo(minutos, caminataMetros, busKm, pasajes, esperaCambioMinutos, primerBus, segundoBus, caminataInicio,
                    caminataCambio, caminataFin, advertencia, nuevo);
        }
    }
}
