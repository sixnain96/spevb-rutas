package co.edu.usta.spevb.vial;

import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Reglas puras (sin estado) que deciden si una vía de OpenStreetMap la puede usar un bus y en qué sentido.
 *
 * <h2>Etiquetas {@code highway} aceptadas</h2>
 * motorway, motorway_link, trunk, trunk_link, primary, primary_link, secondary, secondary_link, tertiary, tertiary_link,
 * unclassified, residential, living_street, service, busway, bus_guideway, road.
 * {@code service} se acepta salvo que el acceso sea private/no (regla general de acceso, más abajo).
 * {@code track} solo se acepta con {@code bus=yes} o {@code bus=designated}.
 *
 * <h2>Etiquetas {@code highway} rechazadas siempre</h2>
 * footway, path, pedestrian, steps, cycleway, corridor, bridleway, platform, elevator, proposed, construction, raceway,
 * y cualquier otro valor desconocido o ausente (no se adivina). Una vía con la etiqueta {@code construction=*}
 * (distinta de «no») también se rechaza. Un puente peatonal es un {@code footway} con {@code bridge=yes}: se rechaza
 * por su {@code highway}, nunca por ser puente.
 *
 * <h2>Acceso</h2>
 * Se mira la primera clave presente en este orden de precedencia: {@code bus} &gt; {@code psv} &gt; {@code motor_vehicle}
 * &gt; {@code vehicle} &gt; {@code access}. Su valor decide:
 * {@code no|private|agricultural|forestry} rechaza; cualquier otro valor (yes, designated, official, permit, permissive,
 * destination, delivery, customers...) acepta. Así {@code access=no} + {@code bus=designated} es transitable,
 * {@code motor_vehicle=no} rechaza salvo que {@code bus}/{@code psv} lo permita, y {@code bus=no} o {@code psv=no} rechazan
 * aunque {@code access=yes}.
 *
 * <h2>Sentido</h2>
 * Precedencia: {@code oneway:bus} &gt; {@code oneway:psv} &gt; {@code oneway}. Valores: yes|true|1 = SOLO_ADELANTE
 * (en el orden de los vértices de la vía); -1|reverse = SOLO_ATRAS; no|false|0 = AMBOS; otro valor se ignora.
 * Si no hay ningún valor reconocido: {@code junction=roundabout|circular} implica SOLO_ADELANTE, y
 * {@code motorway}/{@code motorway_link} también (convención de OpenStreetMap); en cualquier otro caso, AMBOS.
 * Un {@code oneway:bus=no} permite el contraflujo de buses aunque {@code oneway=yes} (carril bus contraflujo).
 *
 * <p>No se modelan restricciones de giro (relaciones OSM), ni horarios ({@code :conditional}), ni peso/altura.
 */
public final class ReglasBus {
    private ReglasBus() {}

    public enum Sentido { AMBOS, SOLO_ADELANTE, SOLO_ATRAS }

    public record Acceso(boolean transitable, Sentido sentido, String motivo) {}

    private static final Set<String> PERMITIDOS = Set.of("motorway", "motorway_link", "trunk", "trunk_link", "primary",
            "primary_link", "secondary", "secondary_link", "tertiary", "tertiary_link", "unclassified", "residential",
            "living_street", "service", "busway", "bus_guideway", "road");
    private static final Set<String> RECHAZADOS = Set.of("footway", "path", "pedestrian", "steps", "cycleway", "corridor",
            "bridleway", "platform", "elevator", "proposed", "construction", "raceway");
    private static final String[] PRECEDENCIA_ACCESO = {"bus", "psv", "motor_vehicle", "vehicle", "access"};
    private static final Set<String> NIEGA = Set.of("no", "private", "agricultural", "forestry");

    public static Acceso evaluar(Map<String, String> tags) {
        if (tags == null || tags.isEmpty()) return rechazo("sin etiquetas");
        String hw = valor(tags, "highway");
        if (hw == null) return rechazo("sin etiqueta highway");
        if (RECHAZADOS.contains(hw)) {
            return rechazo(hw.equals("construction") || hw.equals("proposed")
                    ? "vía en construcción o proyectada (highway=" + hw + ")"
                    : "highway=" + hw + " no es apta para buses");
        }
        boolean track = hw.equals("track");
        if (track) {
            String bus = valor(tags, "bus");
            if (!"yes".equals(bus) && !"designated".equals(bus)) return rechazo("highway=track sin bus=yes/designated");
        } else if (!PERMITIDOS.contains(hw)) {
            return rechazo("highway=" + hw + " no es vehicular");
        }
        String construccion = valor(tags, "construction");
        if (construccion != null && !construccion.equals("no")) return rechazo("vía en construcción (construction=" + construccion + ")");
        String proyectada = valor(tags, "proposed");
        if (proyectada != null && !proyectada.equals("no")) return rechazo("vía proyectada (proposed=" + proyectada + ")");

        String motivoAcceso = "highway=" + hw;
        for (String clave : PRECEDENCIA_ACCESO) {
            String v = valor(tags, clave);
            if (v == null || v.isEmpty()) continue;
            if (NIEGA.contains(v)) return rechazo("acceso denegado (" + clave + "=" + v + ")");
            motivoAcceso = "highway=" + hw + ", " + clave + "=" + v;
            break;
        }
        return new Acceso(true, sentido(tags, hw), motivoAcceso);
    }

    private static Sentido sentido(Map<String, String> tags, String hw) {
        Sentido s = parseOneway(valor(tags, "oneway:bus"));
        if (s == null) s = parseOneway(valor(tags, "oneway:psv"));
        if (s == null) s = parseOneway(valor(tags, "oneway"));
        if (s != null) return s;
        String junction = valor(tags, "junction");
        if ("roundabout".equals(junction) || "circular".equals(junction)) return Sentido.SOLO_ADELANTE;
        if (hw.equals("motorway") || hw.equals("motorway_link")) return Sentido.SOLO_ADELANTE;
        return Sentido.AMBOS;
    }

    private static Sentido parseOneway(String v) {
        if (v == null) return null;
        return switch (v) {
            case "yes", "true", "1" -> Sentido.SOLO_ADELANTE;
            case "-1", "reverse" -> Sentido.SOLO_ATRAS;
            case "no", "false", "0" -> Sentido.AMBOS;
            default -> null;
        };
    }

    private static String valor(Map<String, String> tags, String clave) {
        String v = tags.get(clave);
        return v == null ? null : v.strip().toLowerCase(Locale.ROOT);
    }

    private static Acceso rechazo(String motivo) { return new Acceso(false, Sentido.AMBOS, motivo); }
}
