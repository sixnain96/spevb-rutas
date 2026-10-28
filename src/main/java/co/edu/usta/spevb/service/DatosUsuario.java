package co.edu.usta.spevb.service;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.Map;

/**
 * Valida y limpia lo que cada dispositivo sube: solo los campos esperados, con tipos, rangos y largos controlados.
 * Así la base nunca guarda contenido arbitrario ni crece sin límite.
 */
public final class DatosUsuario {
    public static final Map<String, Integer> MAXIMO = Map.of("lugares", 30, "rutas", 100, "viajes", 50, "historial", 50);
    private final ObjectMapper mapper;

    public DatosUsuario(ObjectMapper mapper) { this.mapper = mapper; }

    public boolean claveValida(String clave) { return MAXIMO.containsKey(clave); }

    public ArrayNode limpiar(String clave, JsonNode entrada) {
        if (!claveValida(clave)) throw new IllegalArgumentException("Tipo de datos desconocido.");
        if (entrada == null || !entrada.isArray()) throw new IllegalArgumentException("Se esperaba una lista.");
        ArrayNode salida = mapper.createArrayNode();
        for (JsonNode item : entrada) {
            if (salida.size() >= MAXIMO.get(clave)) break;
            ObjectNode limpio = item != null && item.isObject() ? limpiarItem(clave, item) : null;
            if (limpio != null) salida.add(limpio);
        }
        return salida;
    }

    private ObjectNode limpiarItem(String clave, JsonNode in) {
        String id = texto(in, "id", 40);
        if (id == null) return null;
        ObjectNode o = mapper.createObjectNode();
        o.put("id", id);
        switch (clave) {
            case "lugares" -> {
                String nombre = texto(in, "nombre", 40);
                Double lat = numero(in, "lat", -90, 90), lng = numero(in, "lng", -180, 180);
                if (nombre == null || lat == null || lng == null) return null;
                o.put("nombre", nombre); o.put("lat", lat); o.put("lng", lng);
            }
            case "rutas" -> {
                String nombre = texto(in, "nombre", 80);
                if (nombre != null) o.put("nombre", nombre);
            }
            case "viajes", "historial" -> {
                ObjectNode a = punto(in.get("a")), b = punto(in.get("b"));
                if (a == null || b == null) return null;
                o.set("a", a); o.set("b", b);
                o.put("soloValidadas", in.path("soloValidadas").asBoolean(false));
                Long t = in.hasNonNull("t") && in.get("t").canConvertToLong() ? in.get("t").asLong() : null;
                o.put("t", t != null && t > 0 ? t : System.currentTimeMillis());
                if ("viajes".equals(clave)) {
                    String nombre = texto(in, "nombre", 60);
                    o.put("nombre", nombre != null ? nombre : "Viaje guardado");
                } else if (in.get("codigos") instanceof ArrayNode cs) {
                    ArrayNode codigos = mapper.createArrayNode();
                    for (JsonNode c : cs) { if (codigos.size() < 3 && c.isString() && c.asString().length() <= 40) codigos.add(c.asString().strip()); }
                    o.set("codigos", codigos);
                }
            }
            default -> { return null; }
        }
        return o;
    }

    private ObjectNode punto(JsonNode p) {
        if (p == null || !p.isObject()) return null;
        Double lat = numero(p, "lat", -90, 90), lng = numero(p, "lng", -180, 180);
        if (lat == null || lng == null) return null;
        ObjectNode o = mapper.createObjectNode();
        o.put("lat", lat); o.put("lng", lng);
        String nombre = texto(p, "nombre", 80);
        o.put("nombre", nombre != null ? nombre : "");
        return o;
    }

    private static String texto(JsonNode n, String campo, int max) {
        JsonNode v = n.get(campo);
        if (v == null || !v.isString()) return null;
        String s = v.asString().replaceAll("[\\p{Cntrl}]", " ").strip();
        if (s.isEmpty()) return null;
        return s.length() > max ? s.substring(0, max) : s;
    }

    private static Double numero(JsonNode n, String campo, double min, double max) {
        JsonNode v = n.get(campo);
        if (v == null || !v.isNumber()) return null;
        double d = v.asDouble();
        return Double.isFinite(d) && d >= min && d <= max ? d : null;
    }
}
