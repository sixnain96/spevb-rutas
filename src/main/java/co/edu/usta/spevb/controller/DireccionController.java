package co.edu.usta.spevb.controller;

import co.edu.usta.spevb.repository.DireccionRepository;
import co.edu.usta.spevb.service.CatastroImportService;
import co.edu.usta.spevb.service.DireccionesLocalesService;
import co.edu.usta.spevb.service.OsmImportService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Estado de la base de direcciones (público) y administración de la importación (requiere clave de editor). */
@RestController
@RequestMapping("/api")
public class DireccionController {
    private final CatastroImportService importacion;
    private final OsmImportService osm;
    private final DireccionesLocalesService locales;
    private final DireccionRepository repo;

    public DireccionController(CatastroImportService importacion, OsmImportService osm,
                               DireccionesLocalesService locales, DireccionRepository repo) {
        this.importacion = importacion;
        this.osm = osm;
        this.locales = locales;
        this.repo = repo;
    }

    public record CorreccionRequest(String texto, double lat, double lng) {}
    public record DescarteRequest(String consulta) {}

    @GetMapping("/direcciones/estado")
    public Map<String, Object> estado() {
        var e = importacion.estado();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("direcciones", repo.contar());
        m.put("importacion", e.estado());
        m.put("leidos", e.leidos());
        m.put("guardados", e.guardados());
        m.put("mensaje", e.mensaje());
        m.put("ultimaImportacion", repo.ultimaImportacionExitosa().map(Object::toString).orElse(null));
        var o = osm.estado();
        m.put("lugares", repo.contarPois());
        m.put("osm", o.estado());
        m.put("osmMensaje", o.mensaje());
        return m;
    }

    @PostMapping("/editor/direcciones/importar-catastro")
    public ResponseEntity<Map<String, Object>> importar() {
        boolean iniciada = importacion.iniciar();
        return ResponseEntity.status(iniciada ? 202 : 409).body(Map.of("iniciada", iniciada,
                "mensaje", iniciada ? "Importación iniciada en segundo plano." : "Ya hay una importación en curso o el catastro no está configurado."));
    }

    @PostMapping("/editor/direcciones/importar-osm")
    public ResponseEntity<Map<String, Object>> importarOsm() {
        boolean iniciada = osm.iniciar();
        return ResponseEntity.status(iniciada ? 202 : 409).body(Map.of("iniciada", iniciada,
                "mensaje", iniciada ? "Importación de OpenStreetMap iniciada en segundo plano." : "Ya hay una importación en curso o no hay servidores Overpass configurados."));
    }

    /** El equipo fija el punto de una dirección completa; desde ese momento es el primer resultado de esa dirección. */
    @PostMapping("/editor/direcciones/corregir")
    public ResponseEntity<Map<String, Object>> corregir(@RequestBody CorreccionRequest request) {
        if (request.texto() == null || request.texto().isBlank() || request.texto().length() > 200)
            return ResponseEntity.badRequest().body(Map.of("ok", false, "mensaje", "Escribe la dirección."));
        var clave = locales.corregir(request.texto(), request.lat(), request.lng());
        return ResponseEntity.ok(clave.isPresent() ? Map.of("ok", true, "tipo", "DIRECCION", "clave", clave.get())
                : Map.of("ok", false, "tipo", "SIN_CAMBIOS", "mensaje", "No es una dirección completa (vía, cruce y placa); se guardó sólo como lugar del catálogo."));
    }

    @PostMapping("/editor/direcciones/sin-resultado/descartar")
    public Map<String, Object> descartar(@RequestBody DescarteRequest request) {
        if (request.consulta() != null) locales.descartarPendiente(request.consulta());
        return Map.of("ok", true);
    }

    /** Lo que la gente buscó y no se encontró: lista de pendientes para completar la base. */
    @GetMapping("/editor/direcciones/sin-resultado")
    public List<Map<String, Object>> sinResultado(@RequestParam(defaultValue = "50") int limite) {
        return repo.topSinResultado(Math.max(1, Math.min(limite, 200))).stream()
                .map(r -> Map.<String, Object>of("consulta", r[0], "texto", DireccionesLocalesService.legible(r[0]),
                        "veces", Integer.parseInt(r[1]))).toList();
    }
}
