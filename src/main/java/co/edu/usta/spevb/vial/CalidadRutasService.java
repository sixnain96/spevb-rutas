package co.edu.usta.spevb.vial;

import co.edu.usta.spevb.service.RutaService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Auditoría de calidad de recorridos: compara cada ruta con la red vial para buses SIN modificarla ni guardar nada.
 * Mide qué porcentaje del recorrido queda sobre vías transitables, la desviación, los segmentos sospechosos y los sentidos posiblemente
 * incorrectos, y clasifica la ruta como CORRECTA, REQUIERE_REVISION o CRITICA. Es solo informativo: «Vía transitable según la red
 * vial disponible. Requiere revisión del equipo».
 */
@Service
public class CalidadRutasService {
    public enum Estado { CORRECTA, REQUIERE_REVISION, CRITICA }

    /** Recorrido a auditar; {@code puntos} en [lat, lng]. */
    public record RutaPuntos(long id, String codigo, String nombre, List<double[]> puntos) {}

    public interface FuenteRutas {
        List<RutaPuntos> todas();
        Optional<RutaPuntos> porId(long id);
    }

    /** Primer tramo problemático, para abrir la ruta directamente en él. */
    public record Problema(int desdeIndice, int hastaIndice, double lat, double lng, String estado, String motivo) {}

    public record Calidad(long id, String codigo, String nombre, int puntos, double longitudM, double pctSobreVias, double desviacionMediaM,
                          double desviacionMaxM, int segmentosSospechosos, int puntosSinCoincidencia, int giroIlegales,
                          boolean sentidoPosiblementeIncorrecto, Estado estado, String motivo, Problema primerProblema) {}

    public record Listado(List<Calidad> rutas, int correctas, int requierenRevision, int criticas, String aviso) {}

    private final FuenteRutas fuente;
    private final Supplier<MapMatchingBus> matcher;
    private final Supplier<MapMatchingBus.Opciones> opciones;

    @Autowired
    public CalidadRutasService(RutaService rutas, RedVialBusService vial) {
        this(new FuenteRutas() {
            @Override public List<RutaPuntos> todas() {
                List<RutaPuntos> r = new ArrayList<>();
                for (var resumen : rutas.listarRutasActivas()) r.add(desde(rutas.obtenerRuta(resumen.id())));
                return r;
            }
            @Override public Optional<RutaPuntos> porId(long id) {
                try { return Optional.of(desde(rutas.obtenerRuta(id))); } catch (co.edu.usta.spevb.exception.RutaNoEncontradaException e) { return Optional.empty(); }
            }
        }, vial::matcher, vial::opcionesPorDefecto);
    }

    public CalidadRutasService(FuenteRutas fuente, Supplier<MapMatchingBus> matcher, Supplier<MapMatchingBus.Opciones> opciones) {
        this.fuente = fuente;
        this.matcher = matcher;
        this.opciones = opciones;
    }

    private static RutaPuntos desde(co.edu.usta.spevb.dto.RutaDetalleResponse d) {
        List<double[]> pts = new ArrayList<>();
        JsonNode coords = d.recorrido().path("coordinates");
        for (JsonNode c : coords) pts.add(new double[]{c.get(1).asDouble(), c.get(0).asDouble()});
        return new RutaPuntos(d.id(), d.codigo(), d.nombre(), pts);
    }

    /** La red vial todavía no está lista: no se puede auditar (el llamador responde 503; jamás se inventa un resultado). */
    public static class RedNoDisponible extends RuntimeException {
        public RedNoDisponible() { super("La red vial para buses todavía no está lista."); }
    }

    public Optional<Calidad> analizar(long id) {
        MapMatchingBus m = matcher.get();
        if (m == null) throw new RedNoDisponible();
        return fuente.porId(id).map((r) -> evaluar(m, r));
    }

    public Listado analizarTodas() {
        MapMatchingBus m = matcher.get();
        if (m == null) throw new RedNoDisponible();
        List<Calidad> lista = new ArrayList<>();
        for (RutaPuntos r : fuente.todas()) lista.add(evaluar(m, r));
        // Las más críticas primero (luego por código), para empezar por lo que más urge.
        lista.sort(Comparator.comparing((Calidad c) -> c.estado().ordinal()).reversed().thenComparing(Calidad::codigo));
        int ok = (int) lista.stream().filter((c) -> c.estado() == Estado.CORRECTA).count();
        int crit = (int) lista.stream().filter((c) -> c.estado() == Estado.CRITICA).count();
        return new Listado(lista, ok, lista.size() - ok - crit, crit, RedVialBusService.AVISO);
    }

    Calidad evaluar(MapMatchingBus m, RutaPuntos r) {
        if (r.puntos().size() < 2) {
            return new Calidad(r.id(), r.codigo(), r.nombre(), r.puntos().size(), 0, 0, 0, 0, 0, 0, 0, false, Estado.CRITICA, "Menos de 2 puntos.", null);
        }
        var op = opciones.get();
        var res = m.ajustar(r.puntos(), Set.of(), op);
        var met = res.metricas();
        int n = r.puntos().size();
        boolean sentido = met.giroIlegales() > 0;
        Estado estado;
        String motivo;
        if (met.pctAjustado() < 60 || met.puntosSinCoincidencia() > 0.10 * n) {
            estado = Estado.CRITICA;
            motivo = "Menos del 60 % del recorrido coincide con vías para buses o hay muchos puntos sin vía.";
        } else if (!res.problemas().isEmpty() || sentido || met.desviacionMaxM() > op.radioMetros() || met.pctAjustado() < 95) {
            estado = Estado.REQUIERE_REVISION;
            motivo = sentido ? "Posible sentido incorrecto o giro ilegal." : "Hay tramos dudosos o desviaciones sobre el límite de " + Math.round(op.radioMetros()) + " m.";
        } else {
            estado = Estado.CORRECTA;
            motivo = "El recorrido coincide con vías transitables dentro del límite.";
        }
        Problema primero = null;
        if (!res.problemas().isEmpty()) {
            var t = res.tramos().get(res.problemas().get(0));
            double[] p = r.puntos().get(Math.min(t.desde(), n - 1));
            primero = new Problema(t.desde(), t.hasta(), p[0], p[1], t.estado().name(), t.motivo());
        }
        int sospechosos = res.problemas().size();
        return new Calidad(r.id(), r.codigo(), r.nombre(), n, met.longitudOriginalM(), met.pctAjustado(), met.desviacionMediaM(), met.desviacionMaxM(),
                sospechosos, met.puntosSinCoincidencia(), met.giroIlegales(), sentido, estado, motivo, primero);
    }
}
