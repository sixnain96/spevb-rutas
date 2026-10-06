package co.edu.usta.spevb.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Component
public class CityArea {
    private final String city;
    private final String region;
    private final String country;
    private final double minLat;
    private final double maxLat;
    private final double minLng;
    private final double maxLng;

    public CityArea(@Value("${spevb.city.name:Villavicencio}") String city,
                    @Value("${spevb.city.region:Meta}") String region,
                    @Value("${spevb.city.country:Colombia}") String country,
                    @Value("${spevb.city.min-lat:3.95}") double minLat,
                    @Value("${spevb.city.max-lat:4.35}") double maxLat,
                    @Value("${spevb.city.min-lng:-73.85}") double minLng,
                    @Value("${spevb.city.max-lng:-73.45}") double maxLng) {
        this.city = city; this.region = region; this.country = country;
        this.minLat = minLat; this.maxLat = maxLat; this.minLng = minLng; this.maxLng = maxLng;
    }

    public boolean contains(double lat, double lng) {
        return Double.isFinite(lat) && Double.isFinite(lng)
                && lat >= minLat && lat <= maxLat && lng >= minLng && lng <= maxLng;
    }

    public void requireInside(double lat, double lng) {
        if (!contains(lat, lng)) throw new IllegalArgumentException("El punto debe estar dentro del área de trabajo de Villavicencio.");
    }

    public String normalizeAddress(String raw) {
        return normalizeColombianAddress(raw == null ? "" : raw.strip());
    }

    public String searchQuery(String raw) {
        return withCity(normalizeAddress(raw));
    }

    /** Devuelve como máximo dos variantes para no abusar del proveedor público. */
    public List<String> searchQueries(String raw) {
        String normalized = normalizeAddress(raw);
        ArrayList<String> result = new ArrayList<>();
        result.add(withCity(normalized));
        String relaxed = normalized.replace(" # ", " ").replaceAll("(?<=\\w)-(?=\\w)", " ");
        relaxed = relaxed.replaceAll("\\s+", " ").strip();
        String relaxedWithCity = withCity(relaxed);
        if (!relaxedWithCity.equalsIgnoreCase(result.getFirst())) result.add(relaxedWithCity);
        return List.copyOf(result);
    }

    private String withCity(String value) {
        String normalized = stripAccents(value).toLowerCase(Locale.ROOT);
        if (!normalized.contains(stripAccents(city).toLowerCase(Locale.ROOT)))
            return value + ", " + city + ", " + region + ", " + country;
        return value;
    }

    /*
     * Cada abreviatura debe ser una palabra completa: (?U) hace que \b reconozca letras
     * con tilde (desde Java 19 \b es ASCII por defecto), y FIN_PALABRA impide reemplazar prefijos de palabras
     * normales ("Avenida", "Diagonal", "Norte", "Nogal", "Clínica", "Cristo"...).
     */
    private static final String FIN_PALABRA = "(?![\\p{L}\\p{N}])";
    private static final String VIA = "(Carrera|Calle|Avenida|Diagonal|Transversal)";
    private static final String NUMERO_VIA = "(\\d+(?:\\s?[A-Z](?!\\p{L}))?(?:\\s+Bis)?(?:\\s+(?:Norte|Sur|Este|Oeste))?)";
    private static final String NUMERO_PLACA = "(\\d+(?:\\s?[A-Z](?!\\p{L}))?)";

    private String normalizeColombianAddress(String value) {
        String q = value;
        q = q.replaceAll("(?iU)\\b(?:cra|kra|cr|kr)" + FIN_PALABRA + "\\.?\\s*", "Carrera ");
        q = q.replaceAll("(?iU)\\b(?:cll|cl)" + FIN_PALABRA + "\\.?\\s*", "Calle ");
        q = q.replaceAll("(?iU)\\b(?:av|avda)" + FIN_PALABRA + "\\.?\\s*", "Avenida ");
        q = q.replaceAll("(?iU)\\b(?:diag|dg)" + FIN_PALABRA + "\\.?\\s*", "Diagonal ");
        q = q.replaceAll("(?iU)\\b(?:tv|transv|tr)" + FIN_PALABRA + "\\.?\\s*", "Transversal ");
        // "No", "Nº" y "N°" sólo se interpretan como número de placa si les sigue un dígito.
        q = q.replaceAll("(?iU)\\bno" + FIN_PALABRA + "\\.?\\s*(?=\\d)", "# ");
        q = q.replaceAll("(?iU)\\bn[º°]\\.?\\s*(?=\\d)", "# ");
        q = q.replaceAll("\\s*#\\s*", " # ");
        // Ej.: Carrera 27 Norte 5 a 50 -> Carrera 27 Norte # 5-50
        q = q.replaceAll("(?iU)^" + VIA + "\\s+" + NUMERO_VIA + "\\s+(?!#)" + NUMERO_PLACA
                + "\\s*(?:\\s(?:a)\\s|-)\\s*(\\d+)(.*)$", "$1 $2 # $3-$4$5");
        // Ej.: Carrera 27 Norte # 5 a 50 -> Carrera 27 Norte # 5-50
        q = q.replaceAll("(?iU)\\s+#\\s+" + NUMERO_PLACA + "\\s*(?:\\s(?:a)\\s|-)\\s*(\\d+)", " # $1-$2");
        return q.replaceAll("\\s+", " ").strip();
    }

    private String stripAccents(String value) {
        return Normalizer.normalize(value, Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
    }

    public String viewbox() { return minLng + "," + maxLat + "," + maxLng + "," + minLat; }
    public String city() { return city; }
    public String region() { return region; }
    public String country() { return country; }
    public double minLat() { return minLat; }
    public double maxLat() { return maxLat; }
    public double minLng() { return minLng; }
    public double maxLng() { return maxLng; }
}
