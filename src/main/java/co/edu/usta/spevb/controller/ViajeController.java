package co.edu.usta.spevb.controller;

import co.edu.usta.spevb.dto.PlanificarViajeRequest;
import co.edu.usta.spevb.dto.ViajeResponse;
import co.edu.usta.spevb.service.ViajeService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class ViajeController {
    private final ViajeService viaje;
    public ViajeController(ViajeService viaje) { this.viaje = viaje; }

    @PostMapping("/viajes/planificar")
    public ViajeResponse planificar(@Valid @RequestBody PlanificarViajeRequest request) {
        return viaje.planificar(request);
    }
}
