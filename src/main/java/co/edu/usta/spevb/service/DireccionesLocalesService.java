package co.edu.usta.spevb.service;

import co.edu.usta.spevb.config.CityArea;
import co.edu.usta.spevb.dto.LugarResponse;
import co.edu.usta.spevb.repository.DireccionRepository;
import co.edu.usta.spevb.repository.DireccionRepository.Fila;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Buscador sobre la base propia de direcciones: coincidencia exacta de vía + cruce + placa, interpolación
 * entre vecinas, esquinas, manzana/casa, barrios y puntos confirmados por usuarios. Sin cuotas ni servicios externos.
 */
@Service
public class DireccionesLocalesService {
    public static final String FUENTE = "Catastro de Villavicencio (base propia)";
    public static final String FUENTE_USUARIOS = "Confirmada por usuarios";
    private static final long REFRESCO_DISPONIBLE_MS = 30_000;

    private final DireccionRepository repo;
    private final CityArea area;
    private volatile long ultimaComprobacion;
    private volatile boolean hayDatos;

    public DireccionesLocalesService(DireccionRepository repo, CityArea area) {
        this.repo = repo;
        this.area = area;
    }

    /** ¿Hay direcciones cargadas? Se cachea unos segundos para no contar en cada tecla. */
    public boolean disponible() {
        long ahora = System.currentTimeMillis();
        if (ahora - ultimaComprobacion > REFRESCO_DISPONIBLE_MS) {
            try { hayDatos = repo.contar() > 0; } catch (RuntimeException ex) { hayDatos = false; }
            ultimaComprobacion = ahora;
        }
        return hayDatos;
    }

    public record SugerenciaLocal(long id, String texto, String detalle, double lat, double lng, String fuente, String precision) {}

    // ---------------------------------------------------------------- búsqueda
    public List<LugarResponse> buscar(String consulta, int limite) {
        if (consulta == null || consulta.isBlank()) return List.of();
        var d = DireccionParser.parsear(consulta);
        List<LugarResponse> salida = new ArrayList<>();

        if (d.estructurada()) {
            repo.confirmadaPorUsuarios(d.clave()).ifPresent(p -> salida.add(new LugarResponse(null, titulo(d), titulo(d) + ", Villavicencio",
                    p.lat(), p.lng(), FUENTE_USUARIOS + " (" + p.n() + ")", false, "CONFIRMADA_POR_USUARIOS")));
            var exactas = repo.exactas(d.tipoVia(), d.via(), d.cruce(), d.placa(), 8).stream()
                    .filter(f -> d.cuadrante().isEmpty() || f.cuadrante().equals(d.cuadrante()))
                    .sorted(java.util.Comparator.comparing((Fila f) -> !f.cuadrante().equals(d.cuadrante())))
                    .toList();
            // Varios predios con la misma placa a pocos metros (apartamentos, bloques) se muestran como uno solo.
            List<Fila> distintas = new ArrayList<>();
            for (Fila f : exactas)
                if (distintas.stream().noneMatch(g -> AbordajeService.haversine(g.lat(), g.lng(), f.lat(), f.lng()) < 30)) distintas.add(f);
            distintas.forEach(f -> salida.add(aLugar(f)));
            if (exactas.isEmpty()) interpolar(d).ifPresent(salida::add);
            if (salida.isEmpty()) repo.registrarSinResultado(sinResultadoClave(d));
        } else if (d.esInterseccion()) {
            repo.esquina(d.tipoVia(), d.via(), d.interseccionTipo(), d.interseccionVia()).ifPresent(p -> salida.add(
                    new LugarResponse(null, nombreVia(d.tipoVia()) + " " + d.via() + " con " + nombreVia(d.interseccionTipo()) + " " + d.interseccionVia(),
                            "Esquina aproximada, Villavicencio", p.lat(), p.lng(), FUENTE, false, "ESQUINA_APROXIMADA")));
        } else if (d.esManzanaCasa()) {
            repo.porManzana(d.barrio(), d.manzana(), d.casa(), limite).forEach(f -> salida.add(aLugar(f)));
            if (salida.isEmpty()) repo.registrarSinResultado(clip("MZ " + d.manzana() + " CS " + d.casa() + " BR " + d.barrio()));
        } else if (d.tipoVia().isEmpty() || d.via().isEmpty()) {
            String q = textoLibre(d);
            String nombre = OsmImportService.normalizar(consulta);
            if (nombre.length() >= 3) distintos(repo.buscarPois(nombre, 8)).stream().limit(5).forEach(p -> salida.add(aLugar(p)));
            if (q.length() >= 3)
                repo.buscarBarrios(q, 3).forEach(b -> salida.add(new LugarResponse(null, "Barrio " + titulo(b.nombre()),
                        "Barrio, Villavicencio · " + b.predios() + " predios", b.lat(), b.lng(), FUENTE, false, "BARRIO")));
            if (salida.isEmpty() && nombre.length() >= 4) repo.registrarSinResultado(clip(nombre));
        }
        return salida.size() > limite ? List.copyOf(salida.subList(0, limite)) : List.copyOf(salida);
    }

    // ---------------------------------------------------------------- interpolación
    Optional<LugarResponse> interpolar(DireccionParser.Direccion d) {
        var menor = repo.vecina(d.tipoVia(), d.via(), d.cuadrante(), d.cruce(), d.placa(), false);
        var mayor = repo.vecina(d.tipoVia(), d.via(), d.cuadrante(), d.cruce(), d.placa(), true);
        String nombre = titulo(d);
        if (menor.isPresent() && mayor.isPresent() && mayor.get().placa() - menor.get().placa() <= 80) {
            double t = (double) (d.placa() - menor.get().placa()) / (mayor.get().placa() - menor.get().placa());
            double lat = menor.get().lat() + (mayor.get().lat() - menor.get().lat()) * t;
            double lng = menor.get().lng() + (mayor.get().lng() - menor.get().lng()) * t;
            return Optional.of(new LugarResponse(null, nombre, nombre + ", Villavicencio · estimada entre las placas "
                    + menor.get().placa() + " y " + mayor.get().placa(), lat, lng, FUENTE, false, "INTERPOLADA"));
        }
        for (var v : new Fila[]{menor.orElse(null), mayor.orElse(null)}) {
            if (v != null && Math.abs(v.placa() - d.placa()) <= 12)
                return Optional.of(new LugarResponse(null, nombre, nombre + ", Villavicencio · cerca de la placa " + v.placa(),
                        v.lat(), v.lng(), FUENTE, false, "VECINA_APROXIMADA"));
        }
        // La vía existe pero no ese cruce/placa: se estima por la posición del cruce entre los cruces conocidos.
        var cruces = repo.crucesDeVia(d.tipoVia(), d.via(), d.cuadrante());
        int c = digitos(d.cruce());
        double[] bajo = null, alto = null;
        for (double[] x : cruces) {
            if (x[0] <= c) bajo = x;
            if (x[0] >= c && alto == null) alto = x;
        }
        if (bajo != null && alto != null) {
            double t = alto[0] == bajo[0] ? 0 : (c - bajo[0]) / (alto[0] - bajo[0]);
            return Optional.of(new LugarResponse(null, nombre, nombre + ", Villavicencio · ubicación aproximada de la zona",
                    bajo[1] + (alto[1] - bajo[1]) * t, bajo[2] + (alto[2] - bajo[2]) * t, FUENTE, false, "VIA_APROXIMADA"));
        }
        return Optional.empty();
    }

    // ---------------------------------------------------------------- autocompletado
    public List<SugerenciaLocal> sugerir(String consulta, int limite) {
        if (consulta == null || consulta.strip().length() < 2) return List.of();
        var d = DireccionParser.parsear(consulta);
        Map<String, SugerenciaLocal> salida = new LinkedHashMap<>();
        if (!d.tipoVia().isEmpty() && !d.via().isEmpty()) {
            String cruce = !d.cruce().isEmpty() ? d.cruce() : d.cruceParcial();
            String placa = d.placa() > 0 ? String.valueOf(d.placa()) : "";
            for (Fila f : repo.sugerirPorVia(d.tipoVia(), d.via(), d.cuadrante(), cruce, placa, limite * 3)) {
                String texto = formatear(f);
                salida.putIfAbsent(texto, new SugerenciaLocal(f.id(), texto, detalle(f), f.lat(), f.lng(), FUENTE, f.precision()));
                if (salida.size() >= limite) break;
            }
            if (salida.isEmpty() && d.estructurada())
                interpolar(d).ifPresent(l -> salida.put(l.nombre(), new SugerenciaLocal(0, l.nombre() + " (aprox.)", "Ubicación estimada",
                        l.lat(), l.lng(), FUENTE, l.precision())));
        } else {
            String q = textoLibre(d);
            String nombre = OsmImportService.normalizar(consulta);
            if (nombre.length() >= 3)
                for (var p : distintos(repo.buscarPois(nombre, limite * 2)))
                    salida.putIfAbsent("poi:" + p.id(), new SugerenciaLocal(-p.id(), p.nombre(), p.categoria().isEmpty() ? "Lugar" : p.categoria(),
                            p.lat(), p.lng(), fuentePoi(p.fuente()), "LUGAR"));
            if (q.length() >= 3 && salida.size() < limite)
                repo.buscarBarrios(q, limite - salida.size()).forEach(b -> salida.putIfAbsent(b.nombre(), new SugerenciaLocal(0, "Barrio " + titulo(b.nombre()),
                        b.predios() + " predios", b.lat(), b.lng(), FUENTE, "BARRIO")));
        }
        return List.copyOf(salida.values());
    }

    // ---------------------------------------------------------------- aprendizaje
    /** Guarda «esta dirección escrita estaba aquí». Sólo se acepta si la dirección es completa y está dentro de la ciudad. */
    public boolean confirmar(String consulta, double lat, double lng) {
        var d = DireccionParser.parsear(consulta);
        if (!d.estructurada() || !area.contains(lat, lng)) return false;
        // Si la base ya tiene una estimación y el punto está a más de 1,5 km, lo escrito no se refiere a ese lugar.
        var previa = buscar(consulta, 1);
        if (!previa.isEmpty() && AbordajeService.haversine(previa.get(0).lat(), previa.get(0).lng(), lat, lng) > 1500) return false;
        repo.guardarConfirmacion(d.clave(), lat, lng);
        return true;
    }

    public void elegir(long id) {
        if (id > 0) repo.sumarPopularidad(id);
        else if (id < 0) repo.sumarPopularidadPoi(-id);
    }

    /** Corrección del equipo: fija el punto de una dirección completa. Tiene prioridad sobre el catastro. Devuelve la clave o vacío si no es una dirección completa. */
    public Optional<String> corregir(String texto, double lat, double lng) {
        area.requireInside(lat, lng);
        var d = DireccionParser.parsear(texto);
        if (!d.estructurada()) return Optional.empty();
        repo.corregirDireccion(new DireccionRepository.Nueva("EQUIPO", clip(d.clave(), 64), clip(titulo(d), 220), clip(d.texto(), 220),
                d.tipoVia(), d.via(), d.cuadrante(), d.cruce(), d.placa(), d.placa2(), d.manzana(), d.casa(), clip(d.barrio(), 80),
                lat, lng, "VERIFICADO_LOCAL", 95));
        repo.descartarSinResultado(clip(d.clave(), 120));
        return Optional.of(d.clave());
    }

    public void descartarPendiente(String consulta) { repo.descartarSinResultado(clip(consulta, 120)); }

    /** «K|48||19|15» → «Carrera 48 #19-15» para mostrarlo en la lista de pendientes. */
    public static String legible(String clave) {
        String[] p = clave.split("\\|", -1);
        if (p.length == 5 && p[4].matches("\\d+"))
            return nombreVia(p[0]) + " " + viaLegible(p[1]) + (p[2].isEmpty() ? "" : " " + cuadranteLegible(p[2]))
                    + " #" + viaLegible(p[3]) + "-" + String.format(Locale.ROOT, "%02d", Integer.parseInt(p[4]));
        return clave;
    }

    /** Quita lugares repetidos (mismo nombre a menos de 200 m: edificio y entrada, o dos mapeos del mismo sitio). */
    private static List<DireccionRepository.PoiFila> distintos(List<DireccionRepository.PoiFila> lista) {
        List<DireccionRepository.PoiFila> salida = new ArrayList<>();
        for (var p : lista) {
            String n = OsmImportService.normalizar(p.nombre());
            if (salida.stream().noneMatch(q -> OsmImportService.normalizar(q.nombre()).equals(n)
                    && AbordajeService.haversine(q.lat(), q.lng(), p.lat(), p.lng()) < 200)) salida.add(p);
        }
        return salida;
    }

    private static String fuentePoi(String fuente) { return "OSM".equals(fuente) ? "OpenStreetMap" : "Confirmado por el equipo"; }

    LugarResponse aLugar(DireccionRepository.PoiFila p) {
        String detalle = (p.categoria().isEmpty() ? "Lugar" : p.categoria()) + (p.direccion().isEmpty() ? "" : " · " + p.direccion()) + ", Villavicencio";
        return new LugarResponse(null, p.nombre(), detalle, p.lat(), p.lng(), fuentePoi(p.fuente()), false, "LUGAR");
    }

    // ---------------------------------------------------------------- formato
    LugarResponse aLugar(Fila f) {
        String nombre = formatear(f);
        String lote = f.via().isEmpty() || f.manzana().isEmpty() ? ""
                : " · Mz " + f.manzana() + (f.casa().isEmpty() ? "" : " Casa " + f.casa());
        return new LugarResponse(f.id(), nombre, nombre + lote + (f.barrio().isEmpty() ? "" : ", " + titulo(f.barrio())) + ", Villavicencio",
                f.lat(), f.lng(), fuenteDe(f.precision()), false, f.precision());
    }

    private static String fuenteDe(String precision) {
        return switch (precision) {
            case "VERIFICADO_LOCAL" -> "Confirmado por el equipo";
            case "DIRECCION_PUNTUAL" -> "OpenStreetMap";
            default -> FUENTE;
        };
    }

    static String detalle(Fila f) { return f.barrio().isEmpty() ? "Villavicencio" : titulo(f.barrio()); }

    static String formatear(Fila f) {
        if (f.via().isEmpty()) return "Mz " + f.manzana() + (f.casa().isEmpty() ? "" : " Casa " + f.casa()) + ", " + titulo(f.barrio());
        StringBuilder sb = new StringBuilder(nombreVia(f.tipoVia())).append(' ').append(viaLegible(f.via()));
        if (!f.cuadrante().isEmpty()) sb.append(' ').append(cuadranteLegible(f.cuadrante()));
        sb.append(" #").append(viaLegible(f.cruce())).append('-').append(String.format(Locale.ROOT, "%02d", f.placa()));
        return sb.toString();
    }

    static String titulo(DireccionParser.Direccion d) {
        StringBuilder sb = new StringBuilder(nombreVia(d.tipoVia())).append(' ').append(viaLegible(d.via()));
        if (!d.cuadrante().isEmpty()) sb.append(' ').append(cuadranteLegible(d.cuadrante()));
        return sb.append(" #").append(viaLegible(d.cruce())).append('-').append(String.format(Locale.ROOT, "%02d", d.placa())).toString();
    }

    static String nombreVia(String tipo) {
        return switch (tipo) {
            case "C" -> "Calle"; case "K" -> "Carrera"; case "D" -> "Diagonal"; case "T" -> "Transversal";
            case "AV" -> "Avenida"; case "AK" -> "Avenida Carrera"; case "AC" -> "Avenida Calle"; case "CIR" -> "Circunvalar";
            default -> tipo;
        };
    }

    static String cuadranteLegible(String c) {
        return switch (c) { case "S" -> "Sur"; case "E" -> "Este"; case "N" -> "Norte"; default -> "Oeste"; };
    }

    /** «37BISA» → «37 Bis A». */
    static String viaLegible(String v) {
        return v.replaceAll("(\\d)BIS", "$1 Bis").replaceAll("Bis([A-Z])$", "Bis $1");
    }

    static String titulo(String mayusculas) {
        StringBuilder sb = new StringBuilder();
        for (String w : mayusculas.toLowerCase(Locale.ROOT).split(" ")) {
            if (w.isEmpty()) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(Character.toUpperCase(w.charAt(0))).append(w.substring(1));
        }
        return sb.toString();
    }

    private static String textoLibre(DireccionParser.Direccion d) {
        String t = d.texto().replaceAll("\\b(BARRIO|BR|URB|URBANIZACION|SECTOR|VILLAVICENCIO|META|COLOMBIA)\\b", " ").replaceAll("\\s+", " ").strip();
        return t.length() > 60 ? t.substring(0, 60) : t;
    }

    private static int digitos(String s) {
        String n = s.replaceAll("[^0-9]", "");
        return n.isEmpty() ? 0 : Integer.parseInt(n.substring(0, Math.min(5, n.length())));
    }

    private static String sinResultadoClave(DireccionParser.Direccion d) { return clip(d.clave()); }

    private static String clip(String s) { return clip(s, 120); }
    private static String clip(String s, int max) { return s.length() > max ? s.substring(0, max) : s; }
}
