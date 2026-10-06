package co.edu.usta.spevb.service;

import co.edu.usta.spevb.config.CityArea;
import co.edu.usta.spevb.dto.CrearLugarRequest;
import co.edu.usta.spevb.dto.LugarResponse;
import co.edu.usta.spevb.repository.LugarRepository;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.*;

@Service
public class LugarService {
    private final LugarRepository repository;
    private final GeocodingService geocoder;
    private final CityArea cityArea;

    public LugarService(LugarRepository repository, GeocodingService geocoder, CityArea cityArea) {
        this.repository = repository;
        this.geocoder = geocoder;
        this.cityArea = cityArea;
    }

    public List<LugarResponse> buscar(String query) {
        List<LugarResponse> local = repository.buscarCatalogo(cityArea.normalizeAddress(query), 8);
        List<LugarResponse> merged = new ArrayList<>(local);
        if (geocoder.configurado() && local.size() < 5) {
            try {
                for (var result : geocoder.buscar(query)) {
                    repository.guardarExterno(result);
                    merged.add(toResponse(result));
                }
            } catch (ResponseStatusException ex) {
                if (local.isEmpty()) throw ex;
            }
        }
        return deduplicate(merged, 8);
    }

    public LugarResponse reverso(double lat, double lng) {
        cityArea.requireInside(lat, lng);
        var local = repository.masCercano(lat, lng, 45);
        if (local.isPresent()) return local.get();
        var external = geocoder.reverso(lat, lng);
        if (external != null) {
            repository.guardarExterno(external);
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
    public boolean geocodificadorActivo() { return geocoder.configurado(); }
    public boolean reversoActivo() { return geocoder.reversoConfigurado(); }

    private LugarResponse toResponse(GeocodingService.Resultado result) {
        return new LugarResponse(null, result.nombre(), result.direccion(), result.lat(), result.lng(),
                result.fuente(), false);
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
