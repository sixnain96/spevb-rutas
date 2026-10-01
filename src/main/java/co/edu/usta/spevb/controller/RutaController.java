package co.edu.usta.spevb.controller;

import co.edu.usta.spevb.dto.ActualizarRecorridoRequest;
import co.edu.usta.spevb.dto.RutaDetalleResponse;
import co.edu.usta.spevb.dto.RutaResumenResponse;
import co.edu.usta.spevb.service.RutaService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
@Tag(name = "Rutas", description = "Endpoints para la gestión y consulta de rutas de transporte público")
public class RutaController {

    private final RutaService rutaService;

    public RutaController(RutaService rutaService) {
        this.rutaService = rutaService;
    }

    @GetMapping("/rutas")
    @Operation(summary = "Listar rutas activas", description = "Obtiene un resumen de todas las rutas de transporte público activas.")
    public List<RutaResumenResponse> listarRutas() {
        return rutaService.listarRutasActivas();
    }

    @GetMapping("/rutas/{id}")
    @Operation(summary = "Obtener detalle de ruta", description = "Obtiene los detalles completos de una ruta por su ID, incluyendo geometrías GeoJSON y paraderos.")
    public RutaDetalleResponse obtenerRuta(
            @Parameter(description = "ID único de la ruta", example = "1") @PathVariable Long id
    ) {
        return rutaService.obtenerRuta(id);
    }

    @PutMapping("/editor/rutas/{id}/recorrido")
    @Operation(summary = "Actualizar recorrido de ruta", description = "Actualiza el trazado GeoJSON, origen, destino y estado de validación de una ruta.")
    public ResponseEntity<Map<String, Object>> actualizarRecorrido(
            @Parameter(description = "ID de la ruta a actualizar", example = "1") @PathVariable Long id,
            @RequestBody ActualizarRecorridoRequest request
    ) {
        rutaService.actualizarRecorrido(id, request);
        return ResponseEntity.ok(Map.of("ok", true, "rutaId", id));
    }

    @GetMapping("/estado")
    @Operation(summary = "Estado del servicio", description = "Retorna la salud y estado actual del módulo de rutas SPEVB.")
    public Map<String, Object> estado() {
        return Map.of("servicio", "SPEVB - Módulo de rutas V2", "estado", "OK");
    }
}

