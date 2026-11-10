package co.edu.usta.spevb.controller;

import co.edu.usta.spevb.dto.CrearLugarRequest;
import co.edu.usta.spevb.config.LimitadorSolicitudes;
import co.edu.usta.spevb.dto.LugarResponse;
import co.edu.usta.spevb.service.LugarService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class LugarController {
    private final LugarService lugares;
    private final LimitadorSolicitudes limitador;

    public LugarController(LugarService lugares) { this(lugares, new LimitadorSolicitudes()); }

    @org.springframework.beans.factory.annotation.Autowired
    public LugarController(LugarService lugares, LimitadorSolicitudes limitador) {
        this.lugares = lugares;
        this.limitador = limitador;
    }

    private static final java.util.regex.Pattern SESION = java.util.regex.Pattern.compile("[A-Za-z0-9_-]{8,64}");
    private static String sesion(String s) { return s != null && SESION.matcher(s).matches() ? s : null; }

    /** Búsqueda explícita (Enter o botón) o resolución de la sugerencia elegida (magicKey «g:…»). SOLO Google; «estado» dice qué pasó. */
    @GetMapping("/lugares")
    public Map<String, Object> buscar(@RequestParam String q,
                                      @RequestParam(required = false, defaultValue = "") String magicKey,
                                      @RequestParam(required = false) String sesion) {
        if (q == null || q.length() > 160 || q.strip().length() < 3)
            throw new IllegalArgumentException("Escribe entre 3 y 160 caracteres para buscar un lugar.");
        var results = lugares.buscarDetallado(q.strip(), magicKey, sesion(sesion));
        return Map.of(
                "lugares", results.lugares(),
                "avisos", results.avisos(),
                "estado", results.estado(),
                "proveedor", LugarService.PROVEEDOR,
                "direccionesHabilitadas", lugares.direccionesHabilitadas(),
                "ciudad", "Villavicencio"
        );
    }

    /** Autocompletado de Google. Siempre HTTP 200 con un «estado» (OK, SIN_RESULTADOS, ERROR_SERVICIO, ERROR_CONFIGURACION, SIN_CONFIGURAR, CONSULTA_CORTA). */
    @GetMapping("/lugares/sugerencias")
    public Map<String, Object> sugerencias(@RequestParam String q, @RequestParam(required = false) String sesion) {
        if (q == null || q.length() > 160)
            return Map.of("estado", LugarService.CONSULTA_CORTA, "sugerencias", List.of(), "proveedor", LugarService.PROVEEDOR);
        var r = lugares.sugerir(q.strip(), sesion(sesion));
        return Map.of("estado", r.estado(), "sugerencias", r.sugerencias(), "proveedor", LugarService.PROVEEDOR,
                "direccionesHabilitadas", lugares.direccionesHabilitadas());
    }

    public record ConfirmacionRequest(String consulta, double lat, double lng) {}
    public record EleccionRequest(long id) {}

    /** Una persona escribió una dirección y fijó el punto en el mapa: se guarda para mejorar futuras búsquedas. */
    @PostMapping("/lugares/confirmaciones")
    public ResponseEntity<Map<String, Object>> confirmar(@RequestBody ConfirmacionRequest request,
                                                         jakarta.servlet.http.HttpServletRequest http) {
        if (!limitador.permitir("conf:" + http.getRemoteAddr(), 30, java.time.Duration.ofMinutes(1)))
            return ResponseEntity.status(429).body(Map.of("ok", false));
        if (request.consulta() == null || request.consulta().length() > 160)
            return ResponseEntity.badRequest().body(Map.of("ok", false));
        return ResponseEntity.ok(Map.of("ok", lugares.confirmarDireccion(request.consulta(), request.lat(), request.lng())));
    }

    /** La persona eligió una sugerencia: sube su popularidad para ordenarla antes la próxima vez. */
    @PostMapping("/lugares/elecciones")
    public ResponseEntity<Map<String, Object>> elegir(@RequestBody EleccionRequest request,
                                                      jakarta.servlet.http.HttpServletRequest http) {
        if (!limitador.permitir("elec:" + http.getRemoteAddr(), 60, java.time.Duration.ofMinutes(1)))
            return ResponseEntity.status(429).body(Map.of("ok", false));
        lugares.elegirDireccion(request.id());
        return ResponseEntity.ok(Map.of("ok", true));
    }

    @GetMapping("/lugares/reverso")
    public LugarResponse reverso(@RequestParam double lat, @RequestParam double lng) {
        return lugares.reverso(lat, lng);
    }

    @GetMapping("/editor/lugares")
    public Map<String, Object> listar(@RequestParam(defaultValue = "") String q,
                                      @RequestParam(defaultValue = "200") int limit) {
        return Map.of("lugares", lugares.listar(q, limit), "total", lugares.contar());
    }

    @PostMapping("/editor/lugares")
    public ResponseEntity<LugarResponse> crear(@Valid @RequestBody CrearLugarRequest request) {
        return ResponseEntity.ok(lugares.crear(request));
    }

    @DeleteMapping("/editor/lugares/{id}")
    public Map<String, Object> eliminar(@PathVariable long id) {
        lugares.eliminar(id);
        return Map.of("ok", true, "id", id);
    }
}
