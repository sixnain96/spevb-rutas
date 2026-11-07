package co.edu.usta.spevb.controller;

import co.edu.usta.spevb.config.MapaConfig;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Configuración PÚBLICA del mapa del navegador: proveedor y, solo con Google, la clave de navegador y el Map ID. */
@RestController
@RequestMapping("/api/config")
public class MapaConfigController {
    private final MapaConfig config;

    public MapaConfigController(MapaConfig config) { this.config = config; }

    @GetMapping("/mapa")
    public MapaConfig.Publica mapa() { return config.publica(); }
}
