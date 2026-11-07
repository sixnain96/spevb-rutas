package co.edu.usta.spevb.controller;

import co.edu.usta.spevb.config.CityArea;
import co.edu.usta.spevb.exception.RutaNoEncontradaException;
import co.edu.usta.spevb.vial.CalidadRutasService;
import co.edu.usta.spevb.vial.MapMatchingBus;
import co.edu.usta.spevb.vial.RedVialBusService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Herramientas del editor sobre la red vial PARA BUSES (distinta de la peatonal). Todo vive bajo /api/editor/ (protegido por el filtro de
 * administración). Son consultas y propuestas: ninguna modifica ni guarda rutas. Aviso fijo: «Vía transitable según la red vial disponible.
 * Requiere revisión del equipo».
 */
@RestController
@RequestMapping("/api/editor")
public class VialController {
    static final double MAX_CANDIDATOS_M = 100, MAX_RADIO_M = 100, MIN_RADIO_M = 5;
    private final RedVialBusService vial;
    private final CalidadRutasService calidad;
    private final CityArea area;

    public VialController(RedVialBusService vial, CalidadRutasService calidad, CityArea area) {
        this.vial = vial;
        this.calidad = calidad;
        this.area = area;
    }

    public record CandidatosRequest(@NotNull Double lat, @NotNull Double lng, Double maxMetros, Integer max) {}

    /** {@code puntos}: [lat, lng] en el orden del recorrido; {@code anclas}: índices que el ajuste no puede mover. */
    public record AjustarRutaRequest(@NotNull @Size(min = 2, max = 10000) List<double[]> puntos, List<Integer> anclas, Boolean preservarExtremos, Double radioMetros) {}

    @GetMapping("/vial/estado")
    public Map<String, Object> estado() {
        var e = vial.estado();
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("estado", e.estado().name());
        r.put("nodos", e.nodos());
        r.put("aristas", e.aristas());
        r.put("detalle", e.detalle());
        r.put("aviso", RedVialBusService.AVISO);
        return r;
    }

    /** Hasta {@code max} (≤ 3) vías para buses cercanas al punto, con su distancia. Si ninguna está dentro del límite, lista vacía y la más cercana. */
    @PostMapping("/vial/candidatos")
    public Map<String, Object> candidatos(@Valid @RequestBody CandidatosRequest request) {
        double lat = request.lat(), lng = request.lng();
        punto(lat, lng);
        var red = redLista();
        double limite = acotar(request.maxMetros() == null ? vial.maxSnapMetros() : request.maxMetros(), 1, MAX_CANDIDATOS_M);
        int max = request.max() == null ? 3 : Math.max(1, Math.min(3, request.max()));
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("candidatos", red.candidatos(lat, lng, limite, max));
        r.put("maxMetros", limite);
        r.put("masCercanaM", red.distanciaMasCercana(lat, lng).isPresent() ? red.distanciaMasCercana(lat, lng).getAsDouble() : null);
        r.put("aviso", RedVialBusService.AVISO);
        return r;
    }

    /** Propuesta de ajuste de TODO el recorrido. No modifica ni guarda nada: la persona compara y confirma en el editor. */
    @PostMapping("/vial/ajustar-ruta")
    public Map<String, Object> ajustarRuta(@Valid @RequestBody AjustarRutaRequest request) {
        for (double[] p : request.puntos()) {
            if (p == null || p.length < 2) throw new IllegalArgumentException("Cada punto necesita latitud y longitud.");
            punto(p[0], p[1]);
        }
        redLista();
        Set<Integer> anclas = new TreeSet<>();
        if (request.anclas() != null) for (Integer i : request.anclas()) if (i != null && i >= 0 && i < request.puntos().size()) anclas.add(i);
        var base = vial.opcionesPorDefecto();
        double radio = request.radioMetros() == null ? base.radioMetros() : acotar(request.radioMetros(), MIN_RADIO_M, MAX_RADIO_M);
        var op = new MapMatchingBus.Opciones(radio, base.maxSaltoMetros(), base.sigmaMetros(), request.preservarExtremos() == null || request.preservarExtremos());
        var res = vial.matcher().ajustar(request.puntos(), anclas, op);
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("propuesta", res.propuesta());
        r.put("tramos", res.tramos());
        r.put("metricas", res.metricas());
        r.put("requiereRevisionManual", res.requiereRevisionManual());
        r.put("problemas", res.problemas());
        r.put("advertencias", res.advertencias());
        r.put("aviso", RedVialBusService.AVISO);
        return r;
    }

    /** Auditoría de TODAS las rutas activas (solo lectura). Sin ajustes masivos: cada ruta se revisa y aprueba por separado. */
    @GetMapping("/calidad")
    public CalidadRutasService.Listado calidadTodas() {
        try { return calidad.analizarTodas(); }
        catch (CalidadRutasService.RedNoDisponible e) { throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, e.getMessage() + " (" + vial.estado().detalle() + ")"); }
    }

    @GetMapping("/rutas/{id}/calidad")
    public CalidadRutasService.Calidad calidadRuta(@PathVariable long id) {
        try { return calidad.analizar(id).orElseThrow(() -> new RutaNoEncontradaException(id)); }
        catch (CalidadRutasService.RedNoDisponible e) { throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, e.getMessage() + " (" + vial.estado().detalle() + ")"); }
    }

    private co.edu.usta.spevb.vial.RedVialBus redLista() {
        var red = vial.red();
        if (red == null) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "La red vial para buses todavía no está lista (" + vial.estado().detalle() + "). Se conserva la geometría original.");
        return red;
    }

    private void punto(double lat, double lng) {
        if (!Double.isFinite(lat) || !Double.isFinite(lng)) throw new IllegalArgumentException("Coordenadas inválidas.");
        area.requireInside(lat, lng);
    }

    private static double acotar(double v, double min, double max) { return Math.max(min, Math.min(max, v)); }
}
