package co.edu.usta.spevb.exception;

public class RutaNoEncontradaException extends RuntimeException {
    private static final long serialVersionUID = 1L;
    public RutaNoEncontradaException(Long id) {
        super("No existe una ruta con id " + id);
    }
}
