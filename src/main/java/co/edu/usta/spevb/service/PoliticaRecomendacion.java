package co.edu.usta.spevb.service;

/**
 * Parámetros de la política de recomendación de viajes (versión {@value #VERSION}). Todas las cantidades llevan su unidad en el nombre.
 *
 * <p><b>Son hipótesis de producto</b>, no datos medidos en Villavicencio ni indicados por el profesor: se eligieron para que la intención
 * («priorizar el punto donde se puede tomar el bus») se pueda probar y explicar, y están expuestas aquí para ajustarlas con datos reales.
 * Al cambiar cualquiera hay que subir {@link #VERSION}: entra en la clave de caché del servidor y el navegador la usa para marcar
 * los resultados guardados con reglas anteriores.
 *
 * <p>Tres capas separadas (ver {@link Recomendador}): viabilidad (qué puede ser recomendado), prioridad de acceso (cuándo un transbordo
 * reemplaza a la mejor directa) y desempates estables.
 *
 * @param reduccionAccesoMinimaMetros     la caminata inicial del transbordo debe ser al menos esto menor que la de la directa
 * @param reduccionAccesoMinimaRelativa   … y al menos esta fracción de la caminata inicial de la directa (se exige el MAYOR de los dos)
 * @param holguraCaminataTotalMetros      la caminata total del transbordo no puede superar la de la directa en más de esto
 * @param maxCaminataCambioMetros         caminata máxima entre la bajada del primer bus y la subida del segundo
 * @param maxTiempoExtraMinutos           el transbordo puede tardar como máximo esto más que la directa (incluye la espera ESTIMADA del cambio)
 * @param maxCaminataExtremoMetros        caminata inicial o final máxima para que una opción pueda ser la recomendada (si ninguna cumple, se recomienda la menos mala)
 */
public record PoliticaRecomendacion(int version, double reduccionAccesoMinimaMetros, double reduccionAccesoMinimaRelativa,
                                    double holguraCaminataTotalMetros, double maxCaminataCambioMetros,
                                    double maxTiempoExtraMinutos, double maxCaminataExtremoMetros) {
    public static final int VERSION = 2;

    public static final PoliticaRecomendacion DEFECTO = new PoliticaRecomendacion(VERSION, 300, 0.5, 300, 400, 15, 1800);

    /** ¿La caminata inicial {@code inicialMetros} reduce de forma significativa la {@code baseInicialMetros} de la directa? */
    public boolean reduccionSignificativa(double baseInicialMetros, double inicialMetros) {
        if (Double.isNaN(baseInicialMetros) || Double.isNaN(inicialMetros)) return false;
        return baseInicialMetros - inicialMetros >= Math.max(reduccionAccesoMinimaMetros, reduccionAccesoMinimaRelativa * baseInicialMetros);
    }

    /**
     * ¿Un transbordo con estas cifras reemplaza a la directa {@code base}? Exige TODO: reducción significativa de la caminata inicial,
     * caminata total sin pasar de la de la directa más la holgura, cambio corto y tiempo extra acotado.
     */
    public boolean transbordoReemplazaA(double baseInicialM, double baseTotalM, double baseMinutos,
                                        double inicialM, double cambioM, double totalM, double minutos) {
        return reduccionSignificativa(baseInicialM, inicialM)
                && totalM <= baseTotalM + holguraCaminataTotalMetros
                && cambioM <= maxCaminataCambioMetros
                && minutos <= baseMinutos + maxTiempoExtraMinutos;
    }
}
