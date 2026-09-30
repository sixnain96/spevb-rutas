package co.edu.usta.spevb.exception;

public class RutaNoEncontradaException extends RuntimeException {
    public RutaNoEncontradaException(Long id) {
        super("No existe una ruta con id " + id);
    }
}
