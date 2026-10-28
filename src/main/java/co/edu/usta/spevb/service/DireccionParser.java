package co.edu.usta.spevb.service;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Intérprete de la nomenclatura colombiana de Villavicencio. Entiende tanto lo que escribe la gente
 * («Carrera 48 #19-15 Sur», «cra 48 no 19 15», «cl 17 sur 39-03») como el formato del catastro
 * («C 17SUR 39 03 MZ B CS 10 URB SAN J»).
 *
 * <p>Estructura: {@code TIPO VÍA [cuadrante] CRUCE [cuadrante] PLACA [placa2]} más complementos
 * (manzana, casa/lote, etapa) y barrio. Las direcciones del catastro vienen truncadas a 32 caracteres,
 * por eso el barrio puede estar incompleto: se compara siempre por prefijo.
 */
public final class DireccionParser {
    private DireccionParser() {}

    /** Resultado del análisis. Los campos vacíos son cadenas vacías (nunca null). */
    public record Direccion(String tipoVia, String via, String viaNombre, String cuadrante, String cruce, int placa, int placa2,
                            String manzana, String casa, String barrio, String interseccionTipo, String interseccionVia,
                            String cruceParcial, String texto) {
        /** Dirección completa: vía + cruce + placa. */
        public boolean estructurada() { return !tipoVia.isEmpty() && !via.isEmpty() && !cruce.isEmpty() && placa > 0; }
        /** Cruce de dos vías («Calle 40 con Carrera 25»). */
        public boolean esInterseccion() { return !tipoVia.isEmpty() && !via.isEmpty() && !interseccionVia.isEmpty(); }
        /** Barrio + manzana + casa, sin vía numerada. */
        public boolean esManzanaCasa() { return via.isEmpty() && !manzana.isEmpty() && !barrio.isEmpty(); }
        /** Clave canónica de dirección completa, estable ante variantes de escritura. */
        public String clave() { return tipoVia + "|" + via + "|" + cuadrante + "|" + cruce + "|" + placa; }
    }

    private static final Map<String, String> TIPOS = Map.ofEntries(
            Map.entry("C", "C"), Map.entry("CL", "C"), Map.entry("CLL", "C"), Map.entry("CLE", "C"), Map.entry("CALLE", "C"),
            Map.entry("K", "K"), Map.entry("KR", "K"), Map.entry("KRA", "K"), Map.entry("CR", "K"), Map.entry("CRA", "K"),
            Map.entry("CRR", "K"), Map.entry("CARR", "K"), Map.entry("CARRERA", "K"),
            Map.entry("D", "D"), Map.entry("DG", "D"), Map.entry("DIAG", "D"), Map.entry("DIAGONAL", "D"),
            Map.entry("T", "T"), Map.entry("TV", "T"), Map.entry("TRV", "T"), Map.entry("TRANS", "T"),
            Map.entry("TRANSV", "T"), Map.entry("TRANSVERSAL", "T"),
            Map.entry("AV", "AV"), Map.entry("AVDA", "AV"), Map.entry("AVENIDA", "AV"),
            Map.entry("AK", "AK"), Map.entry("AC", "AC"), Map.entry("CIR", "CIR"), Map.entry("CIRCUNVALAR", "CIR"));
    private static final Set<String> CUADRANTES_LARGOS = Set.of("SUR", "ESTE", "NORTE", "OESTE");
    private static final Set<String> CUADRANTES_CORTOS = Set.of("S", "E", "N", "O");
    private static final Set<String> RELLENO = Set.of("NO", "NRO", "NUM", "NUMERO", "N", "DE", "EN", "LA", "EL", "VILLAVICENCIO", "META", "COLOMBIA");
    private static final Set<String> UNIONES = Set.of("CON", "Y", "ESQ", "ESQUINA", "CRUCE", "ENTRE");
    private static final Set<String> MZ = Set.of("MZ", "MZA", "MANZANA", "M");
    private static final Set<String> CASA = Set.of("CS", "CASA", "LT", "LO", "LOTE", "LOC", "LC", "APTO", "AP", "BODEGA");
    private static final Set<String> BARRIO = Set.of("BR", "BARRIO", "URB", "URBANIZACION", "COND", "CONDOMINIO", "CONJ", "CONJUNTO", "SECTOR", "VDA");
    private static final Pattern NUM_LETRA = Pattern.compile("^(\\d{1,4})([A-Z]?)$");
    private static final Pattern TIPO_PEGADO = Pattern.compile("^(CALLE|CARRERA|DIAGONAL|TRANSVERSAL|AVENIDA|CRA|CLL|CL|KR|KRA|CR|DG|TV|TRV|AV|C|K|D|T)(\\d.*)$");
    private static final Pattern CUADRANTE_PEGADO = Pattern.compile("^(\\d{1,4}[A-Z]?)(SUR|ESTE|NORTE|OESTE)$");
    private static final Pattern BIS_PEGADO = Pattern.compile("^(\\d{1,4}[A-Z]?)BIS(SUR|ESTE|NORTE|OESTE)?$");

    public static String sinAcentos(String s) {
        return Normalizer.normalize(s == null ? "" : s, Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
    }

    /** Texto en mayúsculas, sin acentos ni signos, con números y letras separados por un espacio simple. */
    public static String normalizar(String raw) {
        String s = sinAcentos(raw).toUpperCase(Locale.ROOT);
        s = s.replaceAll("[#°º\\-–—.,;:/()\"']", " ").replace("Nº", " ").replaceAll("\\s+", " ").strip();
        return s;
    }

    public static Direccion parsear(String raw) {
        String norm = normalizar(raw);
        if (norm.isEmpty()) return vacia("");
        List<String> t = new ArrayList<>();
        for (String tok : norm.split(" ")) {
            Matcher p = TIPO_PEGADO.matcher(tok);
            Matcher q = CUADRANTE_PEGADO.matcher(tok);
            Matcher b = BIS_PEGADO.matcher(tok);
            if (b.matches()) { t.add(b.group(1)); t.add("BIS"); if (b.group(2) != null) t.add(b.group(2)); }
            else if (p.matches()) { t.add(p.group(1)); String resto = p.group(2); addConCuadrante(t, resto); }
            else if (q.matches()) { t.add(q.group(1)); t.add(q.group(2)); }
            else t.add(tok);
        }
        int i = 0;
        String tipo = "", viaNombre = "", via = "", cuad = "", cruce = "", mz = "", casa = "", barrio = "";
        String intTipo = "", intVia = "", cruceParcial = "";
        int placa = 0, placa2 = 0;

        // Tipo de vía (admite «AVENIDA CALLE», «AVENIDA CARRERA»)
        if (i < t.size() && TIPOS.containsKey(t.get(i))) {
            tipo = TIPOS.get(t.get(i)); i++;
            if (tipo.equals("AV") && i < t.size()) {
                String sig = TIPOS.getOrDefault(t.get(i), "");
                if (sig.equals("C")) { tipo = "AC"; i++; }
                else if (sig.equals("K")) { tipo = "AK"; i++; }
            }
        }
        // Número de vía o nombre de vía (p. ej. «AVENIDA CATAMA»)
        if (!tipo.isEmpty() && i < t.size()) {
            Matcher m = NUM_LETRA.matcher(t.get(i));
            if (m.matches()) {
                via = (m.group(1).replaceFirst("^0+(?=\\d)", "")) + m.group(2); i++;
                if (i < t.size() && t.get(i).equals("BIS")) {
                    via += "BIS"; i++;
                    if (i + 1 < t.size() && t.get(i).matches("^[A-Z]$") && !TIPOS.containsKey(t.get(i))
                            && !MZ.contains(t.get(i)) && NUM_LETRA.matcher(t.get(i + 1)).matches()) via += t.get(i++);
                }
            } else if (!MZ.contains(t.get(i)) && !BARRIO.contains(t.get(i))) {
                StringBuilder nombre = new StringBuilder();
                while (i < t.size() && !NUM_LETRA.matcher(t.get(i)).matches() && !UNIONES.contains(t.get(i))
                        && !MZ.contains(t.get(i)) && !BARRIO.contains(t.get(i))) {
                    if (nombre.length() > 0) nombre.append(' ');
                    nombre.append(t.get(i++));
                }
                viaNombre = nombre.toString();
            }
        }
        // Cuadrante de la vía
        if (!via.isEmpty() && i < t.size() && esCuadrante(t, i, true)) { cuad = abreviar(t.get(i)); i++; }
        // Intersección: «CALLE 40 CON CARRERA 25»
        if (!tipo.isEmpty() && (!via.isEmpty() || !viaNombre.isEmpty()) && i < t.size() && UNIONES.contains(t.get(i))) {
            int j = i + 1;
            if (j < t.size() && TIPOS.containsKey(t.get(j))) {
                String t2 = TIPOS.get(t.get(j)); j++;
                if (j < t.size() && NUM_LETRA.matcher(t.get(j)).matches()) {
                    intTipo = t2; intVia = t.get(j).replaceFirst("^0+(?=\\d)", ""); j++;
                    if (j < t.size() && esCuadrante(t, j, true) && cuad.isEmpty()) { cuad = abreviar(t.get(j)); j++; }
                    i = j;
                }
            }
        } else if (!via.isEmpty()) {
            // Cruce y placa: dos números (o tres, con segunda placa), con cuadrante opcional entre ellos
            List<String> nums = new ArrayList<>();
            while (i < t.size() && nums.size() < 3) {
                String x = t.get(i);
                if (RELLENO.contains(x) && !CUADRANTES_CORTOS.contains(x)) { i++; continue; }
                if (NUM_LETRA.matcher(x).matches() || x.matches("^\\d{1,4}$")) { nums.add(x); i++; }
                else if (esCuadrante(t, i, !nums.isEmpty())) { if (cuad.isEmpty()) cuad = abreviar(x); i++; }
                else if (x.equals("BIS") && !nums.isEmpty()) {
                    nums.set(nums.size() - 1, nums.get(nums.size() - 1) + "BIS"); i++;
                    if (i + 1 < t.size() && t.get(i).matches("^[A-Z]$") && !TIPOS.containsKey(t.get(i))
                            && !MZ.contains(t.get(i)) && NUM_LETRA.matcher(t.get(i + 1)).matches())
                        nums.set(nums.size() - 1, nums.get(nums.size() - 1) + t.get(i++));
                }
                else break;
            }
            if (nums.size() == 1) cruceParcial = nums.get(0);
            if (nums.size() >= 2) {
                cruce = nums.get(0).replaceFirst("^0+(?=\\d)", "");
                placa = numero(nums.get(1));
                if (nums.size() == 3) placa2 = numero(nums.get(2));
            }
        }
        // Complementos y barrio
        for (; i < t.size(); i++) {
            String x = t.get(i);
            if (MZ.contains(x) && i + 1 < t.size()) { mz = t.get(++i); }
            else if (CASA.contains(x) && i + 1 < t.size() && casa.isEmpty()) { casa = t.get(++i); }
            else if (BARRIO.contains(x)) {
                StringBuilder b = new StringBuilder(Set.of("BR", "BARRIO", "URB", "URBANIZACION").contains(x) ? "" : x);
                for (i = i + 1; i < t.size(); i++) {
                    if (MZ.contains(t.get(i)) && i + 1 < t.size() && t.get(i + 1).length() <= 4) { i--; break; }
                    if (CASA.contains(t.get(i)) && i + 1 < t.size() && t.get(i + 1).matches("\\d{1,3}[A-Z]?")) { i--; break; }
                    if (b.length() > 0) b.append(' ');
                    b.append(t.get(i));
                }
                barrio = b.toString().strip();
            }
        }
        // «San Marcos MZ C CS 5» (sin la palabra «barrio»): el barrio es lo que está antes de la manzana
        if (via.isEmpty() && barrio.isEmpty() && !mz.isEmpty()) {
            StringBuilder b = new StringBuilder();
            for (String x : t) {
                if (MZ.contains(x)) break;
                if (BARRIO.contains(x) || RELLENO.contains(x) && b.length() == 0) continue;
                if (b.length() > 0) b.append(' ');
                b.append(x);
            }
            barrio = b.toString();
        }
        String texto = norm;
        return new Direccion(tipo, via, viaNombre, cuad, cruce, placa, placa2, mz, casa, barrio, intTipo, intVia, cruceParcial, texto);
    }

    private static void addConCuadrante(List<String> t, String resto) {
        Matcher q = CUADRANTE_PEGADO.matcher(resto);
        if (q.matches()) { t.add(q.group(1)); t.add(q.group(2)); } else t.add(resto);
    }

    private static boolean esCuadrante(List<String> t, int i, boolean seguidoDeNumero) {
        String x = t.get(i);
        if (CUADRANTES_LARGOS.contains(x)) return true;
        // «S/E/N/O» sueltos sólo cuentan como cuadrante dentro del bloque numérico (si no, son letras de manzana, etc.)
        return seguidoDeNumero && CUADRANTES_CORTOS.contains(x) && !x.equals("N") && !x.equals("O")
                && i + 1 < t.size() && t.get(i + 1).matches("^\\d{1,4}[A-Z]?$");
    }

    private static String abreviar(String c) {
        return switch (c) { case "SUR", "S" -> "S"; case "ESTE", "E" -> "E"; case "NORTE", "N" -> "N"; default -> "O"; };
    }

    private static int numero(String s) {
        Matcher m = Pattern.compile("^(\\d{1,5})").matcher(s);
        return m.find() ? Integer.parseInt(m.group(1)) : 0;
    }

    private static Direccion vacia(String texto) {
        return new Direccion("", "", "", "", "", 0, 0, "", "", "", "", "", "", texto);
    }
}
