package co.edu.usta.spevb.exception;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.*;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;
import java.time.Instant;
import org.springframework.web.server.ResponseStatusException;
@RestControllerAdvice
public class GlobalExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);
    private ResponseEntity<ApiError> error(HttpStatus status, String message, HttpServletRequest request) {
        return ResponseEntity.status(status).body(new ApiError(Instant.now(), status.value(),
                status.getReasonPhrase(), message, request.getRequestURI()));
    }
    @ExceptionHandler(RutaNoEncontradaException.class)
    public ResponseEntity<ApiError> notFound(RutaNoEncontradaException ex, HttpServletRequest request) {
        return error(HttpStatus.NOT_FOUND, ex.getMessage(), request);
    }
    @ExceptionHandler(ConflictoVersionException.class)
    public ResponseEntity<ApiError> conflict(ConflictoVersionException ex, HttpServletRequest request) {
        return error(HttpStatus.CONFLICT, ex.getMessage(), request);
    }
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiError> invalid(IllegalArgumentException ex, HttpServletRequest request) {
        return error(HttpStatus.BAD_REQUEST, ex.getMessage(), request);
    }
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> validation(MethodArgumentNotValidException ex, HttpServletRequest request) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .map(e -> e.getField() + ": " + e.getDefaultMessage()).sorted()
                .collect(java.util.stream.Collectors.joining("; "));
        return error(HttpStatus.BAD_REQUEST, message, request);
    }
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiError> unreadable(HttpServletRequest request) {
        return error(HttpStatus.BAD_REQUEST, "El cuerpo de la solicitud debe ser JSON válido.", request);
    }
    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<ApiError> database(DataAccessException ex, HttpServletRequest request) {
        log.error("Error de base de datos en {}", request.getRequestURI(), ex);
        return error(HttpStatus.SERVICE_UNAVAILABLE, "PostGIS no pudo completar el cálculo. Revisa que la base esté activa y que los recorridos guardados sean LineString válidos.", request);
    }
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ApiError> external(ResponseStatusException ex, HttpServletRequest request) {
        return error(HttpStatus.valueOf(ex.getStatusCode().value()), ex.getReason(), request);
    }
}
