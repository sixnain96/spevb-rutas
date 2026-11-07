package co.edu.usta.spevb.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.flyway.autoconfigure.FlywayConfigurationCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.List;

/** Los datos demostrativos viven en {@code db/demo} y solo se cargan en desarrollo o pruebas ({@code DEMO_DATA=true}). */
@Configuration
public class DemoDataFlywayConfig {
    static final String UBICACION_MIGRACIONES = "classpath:db/migration";
    static final String UBICACION_DEMO = "classpath:db/demo";

    @Bean
    FlywayConfigurationCustomizer ubicacionesFlyway(@Value("${spevb.demo-data:false}") boolean demo) {
        return (configuracion) -> {
            List<String> ubicaciones = new ArrayList<>(List.of(UBICACION_MIGRACIONES));
            if (demo) ubicaciones.add(UBICACION_DEMO);
            configuracion.locations(ubicaciones.toArray(String[]::new));
        };
    }
}
