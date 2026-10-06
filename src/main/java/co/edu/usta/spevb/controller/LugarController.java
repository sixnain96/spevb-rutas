package co.edu.usta.spevb.controller;

import co.edu.usta.spevb.dto.CrearLugarRequest;
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

    public LugarController(LugarService lugares) {
        this.lugares = lugares;
    }

    @GetMapping("/lugares")
    public Map<String, Object> buscar(@RequestParam String q) {
        if (q == null || q.strip().length() < 3 || q.length() > 160)
            throw new IllegalArgumentException("Escribe entre 3 y 160 caracteres para buscar un lugar.");
        List<LugarResponse> results = lugares.buscar(q.strip());
        return Map.of(
                "lugares", results,
                "direccionesHabilitadas", lugares.geocodificadorActivo(),
                "reversoHabilitado", lugares.reversoActivo(),
                "ciudad", "Villavicencio"
        );
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
