package co.edu.usta.spevb.controller;

import co.edu.usta.spevb.dto.PlanificarViajeRequest;
import co.edu.usta.spevb.dto.ViajeResponse;
import co.edu.usta.spevb.service.CaminataService;
import co.edu.usta.spevb.service.RedPeatonalService;
import co.edu.usta.spevb.service.ViajeService;
import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@RestController
@RequestMapping("/api")
public class ViajeController {
    private final ViajeService viaje;
    private final CaminataService caminata;
    private final RedPeatonalService red;
    public ViajeController(ViajeService viaje, CaminataService caminata, RedPeatonalService red) {
        this.viaje = viaje;
        this.caminata = caminata;
        this.red = red;
    }

    /** Estado de la red peatonal propia (calles de OpenStreetMap en memoria). */
    @GetMapping("/caminata/estado")
    public Map<String, Object> estadoRed() {
        var e = red.estado();
        return Map.of("estado", e.estado(), "nodos", e.nodos(), "aristas", e.aristas(), "mensaje", e.mensaje());
    }

    /** Calles que conoce la app en una zona (para el mapa de pasos peatonales). */
    @GetMapping("/caminata/red")
    public List<double[]> segmentosRed(@RequestParam double sur, @RequestParam double oeste,
                                       @RequestParam double norte, @RequestParam double este) {
        return red.segmentos(sur, oeste, norte, este);
    }

    @PostMapping("/editor/red-peatonal/actualizar")
    public ResponseEntity<Map<String, Object>> actualizarRed() {
        boolean iniciada = red.actualizar();
        return ResponseEntity.status(iniciada ? 202 : 409).body(Map.of("iniciada", iniciada));
    }

    /** Trayecto a pie por calles entre dos puntos. 503 si no hay servicio: la interfaz usa línea recta. */
    @GetMapping("/caminata")
    public ResponseEntity<Map<String, Object>> caminata(@RequestParam double olat, @RequestParam double olng,
                                        @RequestParam double dlat, @RequestParam double dlng) {
        var tramo = caminata.ruta(olat, olng, dlat, dlng).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Trayecto peatonal no disponible."));
        // La red peatonal cambia poco: el navegador puede reutilizar la respuesta una hora.
        return ResponseEntity.ok().cacheControl(CacheControl.maxAge(1, TimeUnit.HOURS).cachePrivate())
                .body(Map.of("coordinates", tramo.coordenadas(), "metros", Math.round(tramo.metros()),
                        "minutos", Math.max(1, Math.round(tramo.segundos() / 60.0)),
                        "fuente", "OpenStreetMap (calles y senderos peatonales)"));
    }

    @PostMapping("/viajes/planificar")
    public ViajeResponse planificar(@Valid @RequestBody PlanificarViajeRequest request) {
        return viaje.planificar(request);
    }
}
