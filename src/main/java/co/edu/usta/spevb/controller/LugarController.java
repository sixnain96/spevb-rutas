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

    @GetMapping("/lugares")
    public Map<String, Object> buscar(@RequestParam String q,
                                      @RequestParam(required = false, defaultValue = "") String magicKey) {
        if (q == null || q.strip().length() < 3 || q.length() > 160)
            throw new IllegalArgumentException("Escribe entre 3 y 160 caracteres para buscar un lugar.");
        var results = lugares.buscarDetallado(q.strip(), magicKey);
        return Map.of(
                "lugares", results.lugares(),
                "avisos", results.avisos(),
                "catastroConfigurado", results.catastroConfigurado(),
                "arcgisConfigurado", results.arcgisConfigurado(),
                "direccionesHabilitadas", lugares.geocodificadorActivo(),
                "reversoHabilitado", lugares.reversoActivo(),
                "ciudad", "Villavicencio"
        );
    }

    @GetMapping("/lugares/sugerencias")
    public Map<String, Object> sugerencias(@RequestParam String q) {
        if (q == null || q.strip().length() < 3 || q.length() > 160)
            return Map.of("sugerencias", List.of(), "arcgisConfigurado", lugares.arcgisActivo(),
                    "direccionesLocales", lugares.direccionesLocales());
        return Map.of("sugerencias", lugares.sugerir(q.strip()),
                "arcgisConfigurado", lugares.arcgisActivo(), "direccionesLocales", lugares.direccionesLocales());
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
