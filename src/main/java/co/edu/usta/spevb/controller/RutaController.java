package co.edu.usta.spevb.controller;

import co.edu.usta.spevb.dto.ActualizarRecorridoRequest;
import co.edu.usta.spevb.dto.RutaDetalleResponse;
import co.edu.usta.spevb.dto.RutaResumenResponse;
import co.edu.usta.spevb.service.RutaService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class RutaController {

    private final RutaService rutaService;

    public RutaController(RutaService rutaService) {
        this.rutaService = rutaService;
    }

    @GetMapping("/rutas")
    public List<RutaResumenResponse> listarRutas() {
        return rutaService.listarRutasActivas();
    }

    @GetMapping("/rutas/{id}")
    public RutaDetalleResponse obtenerRuta(@PathVariable Long id) {
        return rutaService.obtenerRuta(id);
    }

    @PutMapping("/editor/rutas/{id}/recorrido")
    public ResponseEntity<Map<String, Object>> actualizarRecorrido(
            @PathVariable Long id,
            @RequestBody ActualizarRecorridoRequest request
    ) {
        rutaService.actualizarRecorrido(id, request);
        return ResponseEntity.ok(Map.of("ok", true, "rutaId", id));
    }

    @GetMapping("/estado")
    public Map<String, Object> estado() {
        return Map.of("servicio", "SPEVB - Módulo de rutas V2", "estado", "OK");
    }
}
