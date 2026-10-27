package co.edu.usta.spevb.service;

import co.edu.usta.spevb.config.CityArea;
import co.edu.usta.spevb.dto.CrearLugarRequest;
import co.edu.usta.spevb.dto.LugarResponse;
import co.edu.usta.spevb.dto.SugerenciaResponse;
import co.edu.usta.spevb.repository.LugarRepository;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.*;

@Service
public class LugarService {
    private final LugarRepository repository;
    private final GeocodingService geocoder;
    private final CityArea cityArea;
    private final DireccionesHibridasService hibrido;
    private final DireccionesLocalesService locales;

    public LugarService(LugarRepository repository, GeocodingService geocoder, CityArea cityArea,
                        DireccionesHibridasService hibrido, DireccionesLocalesService locales) {
        this.repository = repository;
        this.geocoder = geocoder;
        this.cityArea = cityArea;
        this.hibrido = hibrido;
        this.locales = locales;
    }

    public List<LugarResponse> buscar(String query) {
        return buscarDetallado(query).lugares();
    }

    public record ResultadoBusqueda(List<LugarResponse> lugares, List<String> avisos,
                                    boolean catastroConfigurado, boolean arcgisConfigurado) {}

    public ResultadoBusqueda buscarDetallado(String query) {
        return buscarDetallado(query, "");
    }

    /** Primero la base propia (instantánea, sin cuota); ArcGIS sólo completa si hay clave y faltan opciones. */
    public List<SugerenciaResponse> sugerir(String query) {
        List<SugerenciaResponse> salida = new ArrayList<>();
        if (locales.disponible()) {
            for (var l : locales.sugerir(query, 6))
                salida.add(new SugerenciaResponse(l.texto(), l.detalle(), null, l.lat(), l.lng(), l.id() > 0 ? l.id() : null, l.fuente(), l.precision()));
        }
        if (salida.size() < 6 && hibrido.arcgisActivo()) {
            try {
                for (var s : hibrido.sugerirArcgis(query)) {
                    if (salida.size() >= 6) break;
                    salida.add(new SugerenciaResponse(s.texto(), "ArcGIS", s.magicKey(), null, null, null, "ArcGIS World Geocoding", null));
                }
            } catch (IllegalStateException ex) { /* ArcGIS opcional */ }
        }
        return List.copyOf(salida);
    }

    public boolean direccionesLocales() { return locales.disponible(); }
    public boolean confirmarDireccion(String consulta, double lat, double lng) { return locales.confirmar(consulta, lat, lng); }
    public void elegirDireccion(long id) { locales.elegir(id); }

    public boolean arcgisActivo() { return hibrido.arcgisActivo(); }

    public ResultadoBusqueda buscarDetallado(String query, String magicKey) {
        List<String> avisos = new ArrayList<>();
        List<LugarResponse> local = repository.buscarCatalogo(cityArea.normalizeAddress(query), 8);
        List<LugarResponse> merged = new ArrayList<>();
        // Los puntos comprobados por el equipo no se sustituyen por predicciones externas.
        local.stream().filter(LugarResponse::confirmado).forEach(merged::add);
        boolean externoFallo = false;
        // Base propia (catastro importado + confirmaciones de usuarios): rápida, sin cuota y sin red.
        boolean baseLocal = locales.disponible();
        if (baseLocal) {
            List<LugarResponse> propios = locales.buscar(query, 8);
            merged.addAll(propios);
            var estimadas = java.util.Set.of("INTERPOLADA", "VECINA_APROXIMADA", "VIA_APROXIMADA");
            if (propios.stream().anyMatch(p -> estimadas.contains(p.precision()))
                    && propios.stream().noneMatch(p -> "PREDIO_APROXIMADO".equals(p.precision())
                    || "CONFIRMADA_POR_USUARIOS".equals(p.precision())))
                avisos.add("Esa numeración no aparece tal cual en el catastro: la posición se estimó con las direcciones vecinas. Compruébala en el mapa.");
        } else {
            try {
                merged.addAll(hibrido.buscarCatastro(query));
            } catch (IllegalStateException ex) {
                externoFallo = true;
                avisos.add("El catastro de Villavicencio no respondió correctamente. Comprueba la URL y la conexión a Internet.");
            }
        }

        // Sólo consultar servicios externos cuando la nomenclatura no se resolvió con un predio real.
        boolean tienePredio = merged.stream().anyMatch(p -> "PREDIO_APROXIMADO".equals(p.precision())
                || "CONFIRMADA_POR_USUARIOS".equals(p.precision()));
        if ((!tienePredio || (magicKey != null && !magicKey.isBlank())) && hibrido.arcgisActivo()) {
            try { merged.addAll(hibrido.buscarArcgis(query, magicKey)); }
            catch (IllegalStateException ex) {
                externoFallo = true;
                avisos.add("ArcGIS no respondió. Comprueba que la clave API tenga permiso para geocodificación.");
            }
        }
        // Nominatim nunca se consulta por cada tecla, sólo con botón o Enter.
        // Para una dirección completa se busca incluso si existe un resultado local vago.
        boolean direccionNumerada = cityArea.normalizeAddress(query).matches(
                "(?i)^(Carrera|Calle|Avenida|Diagonal|Transversal)\\s+.*\\d.*");
        boolean direccionPuntual = merged.stream().anyMatch(p -> "DIRECCION_PUNTUAL".equals(p.precision()));
        if (!tienePredio && !direccionPuntual && geocoder.configurado()
                && (direccionNumerada || merged.size() < 3)) {
            try {
                for (var result : geocoder.buscar(query)) merged.add(toResponse(result));
            } catch (ResponseStatusException ex) {
                externoFallo = true;
                avisos.add("OpenStreetMap/Nominatim no respondió correctamente.");
            }
        }
        // Incluye lugares no confirmados sólo como sugerencias, sin sobreponerlos al catastro.
        local.stream().filter(p -> !p.confirmado()).forEach(merged::add);
        if (merged.isEmpty() && externoFallo)
            avisos.add("No se pudo comprobar la dirección en las fuentes externas. No se asignó ningún marcador arbitrario.");
        return new ResultadoBusqueda(deduplicate(merged, 8), List.copyOf(avisos),
                hibrido.catastroActivo() || baseLocal, hibrido.arcgisActivo());
    }

    public LugarResponse reverso(double lat, double lng) {
        cityArea.requireInside(lat, lng);
        var local = repository.masCercano(lat, lng, 45);
        if (local.isPresent()) return local.get();
        var external = geocoder.reverso(lat, lng);
        if (external != null) {
            return toResponse(external);
        }
        return new LugarResponse(null,
                "Punto en mapa: " + String.format(Locale.ROOT, "%.6f, %.6f", lat, lng),
                null, lat, lng, "Punto seleccionado manualmente", false);
    }

    public LugarResponse crear(CrearLugarRequest request) {
        cityArea.requireInside(request.lat(), request.lng());
        return repository.crear(request);
    }

    public List<LugarResponse> listar(String query, int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 500));
        return repository.listar(query, safeLimit);
    }

    public void eliminar(long id) {
        if (!repository.eliminar(id)) throw new IllegalArgumentException("El lugar ya no existe.");
    }

    public long contar() { return repository.contar(); }
    public boolean geocodificadorActivo() { return hibrido.tieneProveedores() || geocoder.configurado(); }
    public boolean reversoActivo() { return geocoder.reversoConfigurado(); }

    private LugarResponse toResponse(GeocodingService.Resultado result) {
        return new LugarResponse(null, result.nombre(), result.direccion(), result.lat(), result.lng(),
                result.fuente(), false, result.precision());
    }

    private List<LugarResponse> deduplicate(List<LugarResponse> values, int max) {
        LinkedHashMap<String, LugarResponse> unique = new LinkedHashMap<>();
        for (LugarResponse value : values) {
            String key = normalize(value.nombre()) + "|" + String.format(Locale.ROOT, "%.5f|%.5f", value.lat(), value.lng());
            unique.putIfAbsent(key, value);
            if (unique.size() == max) break;
        }
        return List.copyOf(unique.values());
    }

    private String normalize(String value) {
        return java.text.Normalizer.normalize(value == null ? "" : value, java.text.Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "").toLowerCase(Locale.ROOT).strip();
    }
}
