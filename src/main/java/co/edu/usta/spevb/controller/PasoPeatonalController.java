package co.edu.usta.spevb.controller;

import co.edu.usta.spevb.dto.PasoPeatonalActualizarRequest;
import co.edu.usta.spevb.dto.PasoPeatonalRequest;
import co.edu.usta.spevb.service.PasoPeatonalService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/** Administración de los pasos peatonales propios (protegido por el filtro de /api/editor/). */
@RestController
@RequestMapping("/api/editor/pasos")
public class PasoPeatonalController {
    private final PasoPeatonalService pasos;
    public PasoPeatonalController(PasoPeatonalService pasos) { this.pasos = pasos; }

    @GetMapping
    public List<Map<String, Object>> listar() { return pasos.listar(); }

    @PostMapping
    public ResponseEntity<Map<String, Object>> crear(@Valid @RequestBody PasoPeatonalRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(pasos.crear(request));
    }

    /** Edita un puente o paso existente conservando su id (control de versión: 409 si otra persona lo cambió). */
    @PutMapping("/{id}")
    public ResponseEntity<Map<String, Object>> actualizar(@PathVariable long id, @Valid @RequestBody PasoPeatonalActualizarRequest request) {
        return pasos.actualizar(id, request).map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> borrar(@PathVariable long id) {
        return pasos.borrar(id) ? ResponseEntity.noContent().build() : ResponseEntity.notFound().build();
    }
}
