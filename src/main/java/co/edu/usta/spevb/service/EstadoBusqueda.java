package co.edu.usta.spevb.service;

/**
 * Resultado de consultar un proveedor de direcciones o la búsqueda completa. «Sin coincidencias» NO es un fallo: los demás estados sí lo son
 * y la interfaz los explica distinto (no todo se convierte en «no hay direcciones»). Contrato: docs/CONTRATO-RECOMENDACION-Y-BUSQUEDA.md §B.2.
 */
public enum EstadoBusqueda {
    OK, SIN_COINCIDENCIAS, TIMEOUT, RED, CUOTA, CLAVE, FUERA_DE_CIUDAD, RESPUESTA_INVALIDA, PAUSADO;

    /** ¿Cuenta como fallo del proveedor (abre el cortacircuito y se informa)? */
    public boolean esFallo() {
        return this != OK && this != SIN_COINCIDENCIAS && this != FUERA_DE_CIUDAD;
    }

    /** Orden de gravedad para elegir qué estado informar cuando ninguna fuente aportó nada (menor índice = más grave). */
    int gravedad() {
        return switch (this) {
            case CLAVE -> 0; case CUOTA -> 1; case TIMEOUT -> 2; case RED -> 3; case RESPUESTA_INVALIDA -> 4; case PAUSADO -> 5;
            case FUERA_DE_CIUDAD -> 6; case SIN_COINCIDENCIAS -> 7; case OK -> 8;
        };
    }
}
