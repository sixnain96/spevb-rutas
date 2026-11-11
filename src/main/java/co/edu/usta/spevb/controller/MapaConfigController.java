package co.edu.usta.spevb.controller;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.regex.Pattern;

/**
 * Configuración PÚBLICA del mapa para el navegador: la clave de Google Maps JavaScript API.
 * Es una credencial DISTINTA de la del servidor (GOOGLE_MAPS_API_KEY, Places): esta viaja al navegador por diseño y por eso debe tener
 * restricción por dominio (HTTP referrer) y solo la API «Maps JavaScript API». Vacía = el mapa base de Google queda apagado y se usa el mapa de respaldo.
 */
@RestController
@RequestMapping("/api/mapa")
public class MapaConfigController {
    private static final Pattern CLAVE = Pattern.compile("[A-Za-z0-9_-]{20,80}");
    private final String claveNavegador;

    public MapaConfigController(@Value("${spevb.google-maps.browser-key:}") String claveNavegador) {
        String c = claveNavegador == null ? "" : claveNavegador.strip();
        this.claveNavegador = CLAVE.matcher(c).matches() ? c : "";      // una clave con formato raro nunca se entrega
    }

    @GetMapping("/config")
    public Map<String, Object> config() {
        return Map.of("googleMapsKey", claveNavegador, "proveedor", claveNavegador.isEmpty() ? "respaldo" : "google");
    }
}
