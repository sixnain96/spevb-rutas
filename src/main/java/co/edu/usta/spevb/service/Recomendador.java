package co.edu.usta.spevb.service;

import co.edu.usta.spevb.dto.ViajeResponse;
import co.edu.usta.spevb.dto.ViajeResponse.Item;
import co.edu.usta.spevb.dto.ViajeResponse.Recomendacion;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Decide qué opción se recomienda. Es puro: no consulta la base ni la red; trabaja con las cifras ya calculadas.
 *
 * <ol>
 *   <li><b>Viabilidad</b> (qué puede ser recomendado): se excluye el sentido contrario al registrado; una opción con caminata inicial o
 *       final mayor que {@code maxCaminataExtremoMetros} deja de ser elegible salvo que ninguna esté dentro del límite (entonces se recomienda
 *       la menos mala: la política nunca deja al pasajero sin recomendación si existe alguna forma de ir en el sentido correcto).</li>
 *   <li><b>Prioridad de acceso</b>: la base es la mejor directa elegible (menor caminata total). Un transbordo la reemplaza solo si cumple
 *       TODAS las condiciones de {@link PoliticaRecomendacion#transbordoReemplazaA}. Sin directa elegible se recomienda el mejor transbordo.</li>
 *   <li><b>Desempates estables</b>: caminata total, tiempo, número de buses, tipo (directa antes que transbordo) e id.</li>
 * </ol>
 * Las directas, las alternativas «baja y camina»/«solo la segunda ruta» y el sentido contrario se conservan como alternativas del ranking.
 */
public final class Recomendador {
    private Recomendador() {}

    static final String DIRECTA = "DIRECTA", MAS_RAPIDA = "DIRECTA_MAS_RAPIDA", BAJAR_Y_CAMINAR = "BAJAR_Y_CAMINAR",
            SOLO_SEGUNDA_RUTA = "SOLO_SEGUNDA_RUTA", CONTRARIO = "SENTIDO_CONTRARIO", TRANSBORDO = "TRANSBORDO";
    static final String CALLES = "CALLES", RECTA = "RECTA";

    /** Una opción con las cifras que usa la política. {@code elegibleBase}: viabilidad independiente de los límites de caminata. */
    public record Candidato(String id, String tipo, boolean elegibleBase, String motivoNoElegible, double inicialM, double cambioM,
                            double finalM, double minutos, int buses, String calidad) {
        double totalM() { return inicialM + cambioM + finalM; }
        boolean esTransbordo() { return TRANSBORDO.equals(tipo); }
    }

    // ------------------------------------------------------------------ conversión desde los DTO
    private static double minutosDirecta(ViajeResponse.Opcion o) {
        return (o.acercamientoInicioMetros() + o.acercamientoFinMetros()) / TransbordoService.CAMINA_M_MIN
                + o.recorridoKm() * 1000 / TransbordoService.BUS_M_MIN;
    }

    /** Convierte opciones directas, de sentido contrario y transbordos en candidatos. Los ids deben estar ya asignados. */
    public static List<Candidato> candidatos(List<ViajeResponse.Opcion> directas, List<ViajeResponse.Opcion> contrarias,
                                             List<ViajeResponse.Transbordo> transbordos) {
        List<Candidato> r = new ArrayList<>();
        for (var o : directas) r.add(deOpcion(o));
        for (var o : contrarias) r.add(deOpcion(o));
        for (var t : transbordos) {
            double ini = t.caminataInicio() == null ? 0 : t.caminataInicio().metros();
            double cam = t.caminataCambio() == null ? 0 : t.caminataCambio().metros();
            double fin = t.caminataFin() == null ? 0 : t.caminataFin().metros();
            // Los tres tramos a pie de un transbordo salen siempre de la red peatonal (si falta alguno, el transbordo no se ofrece).
            r.add(new Candidato(t.id(), TRANSBORDO, true, null, ini, cam, fin, t.minutos(), 2, CALLES));
        }
        return r;
    }

    private static Candidato deOpcion(ViajeResponse.Opcion o) {
        String tipo = !o.sentidoPermitido() ? CONTRARIO
                : "MAS_RAPIDA".equals(o.variante()) ? MAS_RAPIDA
                : "BAJAR_Y_CAMINAR".equals(o.variante()) ? BAJAR_Y_CAMINAR
                : "SOLO_SEGUNDA_RUTA".equals(o.variante()) ? SOLO_SEGUNDA_RUTA : DIRECTA;
        boolean ok = o.sentidoPermitido();
        return new Candidato(o.id(), tipo, ok, ok ? null : "Va en sentido contrario al registrado para la ruta.",
                o.acercamientoInicioMetros(), 0, o.acercamientoFinMetros(), minutosDirecta(o), 1, o.caminataPorCalles() ? CALLES : RECTA);
    }

    // ------------------------------------------------------------------ política
    private static int rangoTipo(String tipo) {
        return switch (tipo) {
            case DIRECTA -> 0;
            case MAS_RAPIDA -> 1;
            case BAJAR_Y_CAMINAR -> 2;
            case SOLO_SEGUNDA_RUTA -> 3;
            case TRANSBORDO -> 4;
            default -> 5;
        };
    }

    /** Desempate estable: caminata total, tiempo, buses, tipo e id. */
    static final Comparator<Candidato> DESEMPATE = Comparator.comparingDouble(Candidato::totalM)
            .thenComparingDouble(Candidato::minutos).thenComparingInt(Candidato::buses)
            .thenComparingInt((Candidato c) -> rangoTipo(c.tipo()))
            .thenComparing(Candidato::id, Comparator.nullsLast(Comparator.naturalOrder()));

    /** Entre transbordos que cumplen la política: menor caminata inicial, luego el desempate estable. */
    private static final Comparator<Candidato> ENTRE_TRANSBORDOS = Comparator.comparingDouble(Candidato::inicialM).thenComparing(DESEMPATE);

    /** ¿{@code a} es claramente peor que {@code b}? (b no camina más al inicio ni en total, no tarda más ni usa más buses, y es estrictamente mejor en algo). */
    static boolean dominada(Candidato a, Candidato b) {
        if (a == b || b.inicialM() > a.inicialM() || b.totalM() > a.totalM() || b.minutos() > a.minutos() || b.buses() > a.buses()) return false;
        return b.inicialM() < a.inicialM() || b.totalM() < a.totalM() || b.minutos() < a.minutos() || b.buses() < a.buses();
    }

    /** Transbordos claramente dominados por una directa elegible: se omiten de la lista de transbordos (las directas y sus geometrías no se tocan). */
    public static boolean transbordoDominado(PoliticaRecomendacion p, Candidato transbordo, List<Candidato> todos) {
        for (var b : todos) {
            if (b.esTransbordo() || !b.elegibleBase() || !DIRECTA.equals(b.tipo())) continue;
            if (b.inicialM() <= p.maxCaminataExtremoMetros() && b.finalM() <= p.maxCaminataExtremoMetros() && dominada(transbordo, b)) return true;
        }
        return false;
    }

    public static Recomendacion recomendar(PoliticaRecomendacion p, List<Candidato> candidatos) {
        // 1) viabilidad
        boolean[] dentro = new boolean[candidatos.size()];
        boolean hayDentro = false;
        for (int i = 0; i < candidatos.size(); i++) {
            var c = candidatos.get(i);
            dentro[i] = c.elegibleBase() && c.inicialM() <= p.maxCaminataExtremoMetros() && c.finalM() <= p.maxCaminataExtremoMetros();
            hayDentro |= dentro[i];
        }
        List<Candidato> elegibles = new ArrayList<>();
        List<String> motivoNo = new ArrayList<>();
        for (int i = 0; i < candidatos.size(); i++) {
            var c = candidatos.get(i);
            boolean ok = c.elegibleBase() && (dentro[i] || !hayDentro);
            motivoNo.add(ok ? null : c.motivoNoElegible() != null ? c.motivoNoElegible()
                    : "Obliga a caminar más de " + Math.round(p.maxCaminataExtremoMetros()) + " m al inicio o al final.");
            if (ok) elegibles.add(c);
        }
        // 2) prioridad de acceso
        Candidato base = elegibles.stream().filter(c -> DIRECTA.equals(c.tipo())).min(DESEMPATE).orElse(null);
        if (base == null) base = elegibles.stream().filter(c -> !c.esTransbordo()).min(DESEMPATE).orElse(null);
        final Candidato baseFinal = base;
        Candidato elegida = base;
        List<Candidato> transbordosElegibles = elegibles.stream().filter(Candidato::esTransbordo).toList();
        if (base == null) {
            elegida = transbordosElegibles.stream().min(ENTRE_TRANSBORDOS).orElse(null);
        } else {
            Candidato t = transbordosElegibles.stream()
                    .filter(x -> p.transbordoReemplazaA(baseFinal.inicialM(), baseFinal.totalM(), baseFinal.minutos(),
                            x.inicialM(), x.cambioM(), x.totalM(), x.minutos()))
                    .filter(x -> elegibles.stream().noneMatch(o -> DIRECTA.equals(o.tipo()) && dominada(x, o)))
                    .min(ENTRE_TRANSBORDOS).orElse(null);
            if (t != null) elegida = t;
        }
        // 3) ranking: la elegida primero; después las elegibles por desempate estable y al final las no elegibles
        final Candidato primera = elegida;
        List<Candidato> orden = new ArrayList<>();
        if (primera != null) orden.add(primera);
        candidatos.stream().filter(c -> c != primera && elegibles.contains(c)).sorted(DESEMPATE).forEach(orden::add);
        candidatos.stream().filter(c -> !elegibles.contains(c)).sorted(DESEMPATE).forEach(orden::add);
        List<List<String>> etiquetas = etiquetar(orden, elegibles);
        List<Item> ranking = new ArrayList<>();
        for (int i = 0; i < orden.size(); i++) {
            var c = orden.get(i);
            int idx = candidatos.indexOf(c);
            ranking.add(new Item(c.id(), c.tipo(), i + 1, elegibles.contains(c), motivoNo.get(idx), c.inicialM(), c.cambioM(), c.finalM(),
                    c.totalM(), c.minutos(), c.buses(), c.calidad(), etiquetas.get(i)));
        }
        return new Recomendacion(p.version(), primera == null ? null : primera.id(), motivo(primera, base, transbordosElegibles), List.copyOf(ranking));
    }

    /** Etiquetas solo cuando de verdad se comparó (≥ 2 opciones elegibles) y la opción es la mejor sin empate en esa propiedad. */
    private static List<List<String>> etiquetar(List<Candidato> orden, List<Candidato> elegibles) {
        List<List<String>> r = new ArrayList<>();
        for (int i = 0; i < orden.size(); i++) r.add(new ArrayList<>());
        if (elegibles.size() >= 2) {
            boolean hayTransbordo = elegibles.stream().anyMatch(Candidato::esTransbordo);
            boolean hayUnBus = elegibles.stream().anyMatch(c -> !c.esTransbordo());
            if (hayTransbordo && hayUnBus)
                for (int i = 0; i < orden.size(); i++)
                    if (elegibles.contains(orden.get(i)) && !orden.get(i).esTransbordo()) r.get(i).add("SIN_TRANSBORDO");
            marcarUnico(orden, elegibles, r, "PRIMER_BUS_MAS_CERCANO", Candidato::inicialM);
            marcarUnico(orden, elegibles, r, "MENOR_CAMINATA_TOTAL", Candidato::totalM);
            marcarUnico(orden, elegibles, r, "MENOR_TIEMPO", Candidato::minutos);
        }
        return r.stream().map(List::copyOf).collect(Collectors.toList());
    }

    private static void marcarUnico(List<Candidato> orden, List<Candidato> elegibles, List<List<String>> r, String etiqueta,
                                    java.util.function.ToDoubleFunction<Candidato> f) {
        double min = elegibles.stream().mapToDouble(f).min().orElse(Double.NaN);
        List<Candidato> mejores = elegibles.stream().filter(c -> Math.abs(f.applyAsDouble(c) - min) < 1e-9).toList();
        if (mejores.size() != 1) return;
        r.get(orden.indexOf(mejores.get(0))).add(etiqueta);
    }

    private static String m(double metros) { return Math.round(metros / 10.0) * 10 + " m"; }

    private static String motivo(Candidato primera, Candidato base, List<Candidato> transbordos) {
        if (primera == null) return "No hay ninguna opción que respete el sentido de las rutas registradas; no se recomienda ninguna.";
        if (primera.esTransbordo()) {
            String cambio = primera.cambioM() > 0 ? ", con unos " + m(primera.cambioM()) + " de caminata en el cambio" : "";
            if (base != null)
                return "Recomendada: tomas el primer bus a " + m(primera.inicialM()) + " de tu ubicación (la mejor ruta sin transbordo te obliga a caminar "
                        + m(base.inicialM()) + " hasta el bus). Requiere un transbordo (dos buses)" + cambio + ".";
            return "Recomendada: no hay una ruta sin transbordo en el sentido correcto. Tomas el primer bus a " + m(primera.inicialM())
                    + " de tu ubicación. Requiere un transbordo (dos buses)" + cambio + ".";
        }
        String comparacion = transbordos.isEmpty() ? "" : " Se comparó con " + (transbordos.size() == 1 ? "un transbordo" : transbordos.size() + " transbordos")
                + ": ninguno reduce de forma significativa la caminata hasta el primer bus sin empeorar demasiado el viaje.";
        return "Recomendada: tomas el bus a " + m(primera.inicialM()) + " de tu ubicación y caminas " + m(primera.finalM())
                + " hasta tu destino. Sin transbordo." + comparacion;
    }
}
