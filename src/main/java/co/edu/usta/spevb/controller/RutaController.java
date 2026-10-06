package co.edu.usta.spevb.controller;

import co.edu.usta.spevb.dto.*;
import co.edu.usta.spevb.service.LugarService;
import co.edu.usta.spevb.service.RutaService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class RutaController {
    private final RutaService rutaService;
    private final LugarService lugarService;

    public RutaController(RutaService rutaService, LugarService lugarService) {
        this.rutaService = rutaService;
        this.lugarService = lugarService;
    }

    @GetMapping("/rutas")
    public List<RutaResumenResponse> listarRutas() { return rutaService.listarRutasActivas(); }

    @GetMapping("/rutas/{id}")
    public RutaDetalleResponse obtenerRuta(@PathVariable Long id) { return rutaService.obtenerRuta(id); }

    @GetMapping("/rutas/cercanas")
    public List<RutaCercanaResponse> cercanas(@RequestParam double lat, @RequestParam double lng,
                                               @RequestParam(required = false) Integer radioMetros,
                                               @RequestParam(defaultValue = "false") boolean soloValidadas) {
        return rutaService.cercanas(lat, lng, radioMetros, soloValidadas);
    }

    @PostMapping("/editor/rutas")
    public ResponseEntity<RutaDetalleResponse> crearRuta(@Valid @RequestBody CrearRutaRequest request) {
        return ResponseEntity.ok(rutaService.crearRuta(request));
    }

    @PutMapping("/editor/rutas/{id}/recorrido")
    public ResponseEntity<RutaDetalleResponse> actualizarRecorrido(@PathVariable Long id,
                                                                    @Valid @RequestBody ActualizarRecorridoRequest request) {
        return ResponseEntity.ok(rutaService.actualizarRecorrido(id, request));
    }

    @GetMapping("/editor/rutas/{id}/historial")
    public List<RevisionResponse> historial(@PathVariable Long id) { return rutaService.revisiones(id); }

    @GetMapping("/estado")
    public Map<String, Object> estado() {
        rutaService.comprobarConexion();
        return Map.of("servicio", "SPEVB - Módulo de rutas V3", "version", "3.0.0", "estado", "OK",
                "baseDatos", "OK", "lugaresGuardados", lugarService.contar(),
                "geocodificador", lugarService.geocodificadorActivo() ? "ACTIVO" : "SOLO_LOCAL");
    }
}
