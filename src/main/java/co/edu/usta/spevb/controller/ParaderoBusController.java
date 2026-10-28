package co.edu.usta.spevb.controller;

import co.edu.usta.spevb.service.ParaderoBusService;
import co.edu.usta.spevb.service.ParaderoBusService.Entrada;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/** Paraderos de bus del sistema (independientes de las rutas). Lectura pública para el mapa; escritura solo del administrador (/api/editor). */
@RestController
@RequestMapping("/api")
public class ParaderoBusController {
    private final ParaderoBusService paraderos;

    public ParaderoBusController(ParaderoBusService paraderos) { this.paraderos = paraderos; }

    public record Cuerpo(String nombre, String nota, Double lat, Double lng, Boolean activo) {
        Entrada entrada() {
            if (lat == null || lng == null) throw new IllegalArgumentException("Faltan las coordenadas del paradero.");
            return new Entrada(nombre, nota, lat, lng);
        }
    }
    public record Lote(List<Cuerpo> paraderos) {}

    /** Paraderos activos dentro de la zona visible del mapa. */
    @GetMapping("/paraderos")
    public List<ParaderoBusService.Punto> enCaja(@RequestParam double sur, @RequestParam double oeste, @RequestParam double norte,
                                                 @RequestParam double este, @RequestParam(defaultValue = "1500") int limite) {
        return paraderos.enCaja(sur, oeste, norte, este, limite);
    }

    @GetMapping("/editor/paraderos")
    public List<ParaderoBusService.Paradero> buscar(@RequestParam(defaultValue = "") String q, @RequestParam(defaultValue = "100") int limite) {
        return paraderos.buscar(q, limite);
    }

    @GetMapping("/editor/paraderos/{id}")
    public ParaderoBusService.Paradero obtener(@PathVariable long id) { return paraderos.obtener(id); }

    @PostMapping("/editor/paraderos")
    public ParaderoBusService.Paradero crear(@RequestBody Cuerpo c) { return paraderos.crear(c.entrada()); }

    @PutMapping("/editor/paraderos/{id}")
    public ParaderoBusService.Paradero actualizar(@PathVariable long id, @RequestBody Cuerpo c) { return paraderos.actualizar(id, c.entrada(), c.activo()); }

    @DeleteMapping("/editor/paraderos/{id}")
    public Map<String, Object> eliminar(@PathVariable long id) { paraderos.eliminar(id); return Map.of("ok", true); }

    @PostMapping("/editor/paraderos/lote")
    public ParaderoBusService.ResultadoLote lote(@RequestBody Lote l) {
        if (l.paraderos() == null) throw new IllegalArgumentException("No hay paraderos para importar.");
        return paraderos.crearLote(l.paraderos().stream().map(Cuerpo::entrada).toList());
    }
}
