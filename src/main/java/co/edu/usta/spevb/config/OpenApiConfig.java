package co.edu.usta.spevb.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.servers.Server;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI customOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("SPEVB – Módulo de Rutas API")
                        .version("2.0.0")
                        .description("API REST para la gestión y visualización de rutas de transporte público del SPEVB (Villavicencio). Documentada con Scalar API Reference.")
                        .contact(new Contact()
                                .name("Universidad Santo Tomás (USTA)")
                                .url("https://www.usta.edu.co")))
                .servers(List.of(
                        new Server().url("http://localhost:8080").description("Servidor Local")
                ));
    }
}
