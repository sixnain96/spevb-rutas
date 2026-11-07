package co.edu.usta.spevb.controller;

import co.edu.usta.spevb.dto.PuentePublicoResponse;
import co.edu.usta.spevb.service.PasoPeatonalService;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.List;

/** Puentes peatonales registrados por la administración, para dibujarlos en el mapa (lectura pública). */
@RestController
@RequestMapping("/api/puentes")
public class PuenteController {
    private final PasoPeatonalService pasos;
    public PuenteController(PasoPeatonalService pasos) { this.pasos = pasos; }

    @GetMapping
    public ResponseEntity<List<PuentePublicoResponse>> listar() {
        return ResponseEntity.ok().cacheControl(CacheControl.maxAge(Duration.ofMinutes(1))).body(pasos.listarPuentes());
    }
}
