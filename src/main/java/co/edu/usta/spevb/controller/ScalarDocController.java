package co.edu.usta.spevb.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Tag(name = "Documentación", description = "Endpoint de interfaz gráfica Scalar para la documentación de la API")
public class ScalarDocController {

    @GetMapping(value = {"/docs", "/scalar", "/docs/scalar"}, produces = MediaType.TEXT_HTML_VALUE)
    @Operation(summary = "Interfaz de documentación de la API con Scalar", description = "Retorna la interfaz interactiva de Scalar para explorar y probar la API REST.")
    public String scalarDoc() {
        return """
                <!DOCTYPE html>
                <html lang="es">
                <head>
                    <title>SPEVB Rutas - Documentación API (Scalar)</title>
                    <meta charset="utf-8" />
                    <meta name="viewport" content="width=device-width, initial-scale=1" />
                    <style>
                        body {
                            margin: 0;
                            padding: 0;
                        }
                    </style>
                </head>
                <body>
                    <script
                        id="api-reference"
                        data-url="/v3/api-docs">
                    </script>
                    <script src="https://cdn.jsdelivr.net/npm/@scalar/api-reference"></script>
                </body>
                </html>
                """;
    }
}
