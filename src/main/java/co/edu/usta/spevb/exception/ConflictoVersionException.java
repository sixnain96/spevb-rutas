package co.edu.usta.spevb.exception;

public class ConflictoVersionException extends RuntimeException {
    private static final long serialVersionUID = 1L;
    public ConflictoVersionException() {
        super("La ruta cambió desde que la cargaste. Exporta tu borrador y recarga la versión actual antes de guardar.");
    }
}
