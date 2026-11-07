package co.edu.usta.spevb.service;

import co.edu.usta.spevb.config.CityArea;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class DireccionesHibridasServiceTest {
    private HttpServer server;
    private final AtomicReference<String> requestBody = new AtomicReference<>();
    private String url;
    private CityArea area;

    @BeforeEach void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/catastro", exchange -> {
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] json = ("""
                {"features":[
                  {"attributes":{"OBJECTID":42,"DIRECCION":"KR 27 NORTE 5A-50"},"centroid":{"x":-73.6201,"y":4.1402}},
                  {"attributes":{"OBJECTID":43,"DIRECCION":"CLL 27 NORTE 5A-50"},"centroid":{"x":-73.625,"y":4.141}},
                  {"attributes":{"OBJECTID":44,"DIRECCION":"KR 27 NORTE 5A-52"},"centroid":{"x":-73.626,"y":4.141}},
                  {"attributes":{"OBJECTID":45,"DIRECCION":"KR 27 NORTE 5A-50"},"centroid":{"x":-75,"y":4.141}},
                  {"attributes":{"OBJECTID":67897,"DIRECCION":"K 48 19 15SUR EL TESORO"},"centroid":{"x":-73.6204,"y":4.1025}},
                  {"attributes":{"OBJECTID":68567,"DIRECCION":"K 48 19 16SUR CS 41 BR MONTECARLO"},"centroid":{"x":-73.6205,"y":4.1031}},
                  {"attributes":{"OBJECTID":106,"DIRECCION":"C 48 52 19 BR CHAPINERITO"},"centroid":{"x":-73.6333,"y":4.1513}},
                  {"attributes":{"OBJECTID":68568,"DIRECCION":"K 48 21 03SUR MZ 19 CS 05"},"centroid":{"x":-73.639,"y":4.101}}
                ]}
                """).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, json.length);
            exchange.getResponseBody().write(json);
            exchange.close();
        });
        server.createContext("/arcgis", exchange -> {
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] json = """
                {"candidates":[
                  {"address":"Calle 15 # 40-10, Villavicencio","score":98,"location":{"x":-73.62,"y":4.14},"attributes":{"Addr_type":"PointAddress"}},
                  {"address":"Bogotá","score":100,"location":{"x":-74.07,"y":4.61},"attributes":{"Addr_type":"PointAddress"}},
                  {"address":"Indeterminado","score":30,"location":{"x":-73.62,"y":4.14},"attributes":{"Addr_type":"StreetName"}},
                  {"address":"Unicentro Villavicencio Centro Comercial","score":87.84,"location":{"x":-73.6346,"y":4.14152},"attributes":{"Addr_type":"POI"}},
                  {"address":"Villavicencio, Meta","score":83.64,"location":{"x":-73.6345,"y":4.1442},"attributes":{"Addr_type":"Locality"}}
                ]}
                """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, json.length);
            exchange.getResponseBody().write(json);
            exchange.close();
        });
        server.createContext("/arcgis/suggest", exchange -> {
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] json = """
                {"suggestions":[
                  {"text":"Unicentro Villavicencio Centro Comercial", "magicKey":"validMagicKey001", "isCollection":false},
                  {"text":"Villavicencio, Meta", "magicKey":"validMagicKey002", "isCollection":false},
                  {"text":"Unicentro Villavicencio Centro Comercial", "magicKey":"validMagicKey003", "isCollection":false},
                  {"text":"Unicentro Centro Comercial", "magicKey":"validMagicKey004", "isCollection":true}
                ]}
                """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, json.length);
            exchange.getResponseBody().write(json);
            exchange.close();
        });
        server.start();
        url = "http://127.0.0.1:" + server.getAddress().getPort();
        area = new CityArea("Villavicencio", "Meta", "Colombia", 3.95, 4.35, -73.85, -73.45);
    }

    @AfterEach void close() { server.stop(0); }

    @Test void encuentraPredioPeroDescartaCalleIncorrectaNumeroEquivocadoYFueraDeCiudad() {
        var service = new DireccionesHibridasService(url + "/catastro", url + "/arcgis", "",
                JsonMapper.builder().build(), area);
        var results = service.buscarCatastro("Cra 27 Norte # 5A-50");
        assertEquals(1, results.size());
        assertEquals("PREDIO_APROXIMADO", results.getFirst().precision());
        assertEquals(-73.6201, results.getFirst().lng(), 0.000001);
        assertFalse(results.getFirst().confirmado());
        assertTrue(requestBody.get().contains("returnCentroid=true"));
        String decoded = java.net.URLDecoder.decode(requestBody.get(), StandardCharsets.UTF_8);
        // La letra de la placa se separa con comodín: el catastro la guarda tanto como «5A» como «5 A».
        assertTrue(decoded.contains("LIKE '%27%5%A%50%'"), decoded);
        assertFalse(decoded.contains("LIKE ''%"), decoded);
        assertEquals(results, service.buscarCatastro("Cra 27 Norte # 5A-50"));
    }


    @Test void aceptaPrefijosCatastralesKCySufijoSurSinConfundirNumeroDeCasa() {
        var service = new DireccionesHibridasService(url + "/catastro", url + "/arcgis", "",
                JsonMapper.builder().build(), area);
        var casa = service.buscarCatastro("Carrera 48 #19-15 Sur, Villavicencio");
        assertEquals(1, casa.size());
        assertEquals("K 48 19 15SUR EL TESORO", casa.getFirst().direccion());
        assertEquals("PREDIO_APROXIMADO", casa.getFirst().precision());
        assertTrue(service.buscarCatastro("Carrera 48 #19-05 Sur, Villavicencio").isEmpty());
        var calle = service.buscarCatastro("Calle 48 #52-19, Villavicencio");
        assertEquals(1, calle.size());
        assertEquals("C 48 52 19 BR CHAPINERITO", calle.getFirst().direccion());
        // El número de lote 05 no puede sustituir la placa de una casa.
        assertTrue(service.buscarCatastro("Carrera 48 #21-05 Sur, Villavicencio").isEmpty());
    }

    @Test void noBuscaEnCatastroUnBarrioSinNumero() {
        var service = new DireccionesHibridasService(url + "/catastro", url + "/arcgis", "",
                JsonMapper.builder().build(), area);
        assertTrue(service.buscarCatastro("Barrio Galán").isEmpty());
    }

    @Test void sugerenciasRelevantesNoIncluyenCiudadGenericaNiDuplicados() {
        var service = new DireccionesHibridasService("", url + "/arcgis/findAddressCandidates", "clave-prueba",
                JsonMapper.builder().build(), area);
        var result = service.sugerirArcgis("Unicentro Villavicencio");
        assertEquals(1, result.size());
        assertEquals("Unicentro Villavicencio Centro Comercial", result.getFirst().texto());
        assertEquals("validMagicKey001", result.getFirst().magicKey());
        // Esri recibe el texto parcial, sin añadir automáticamente el nombre de ciudad.
        assertTrue(requestBody.get().contains("text=Unicentro+Villavicencio"));
        assertTrue(requestBody.get().contains("countryCode=COL"));
        assertFalse(requestBody.get().contains("+Meta%2C+Colombia"));
    }

    @Test void buscaPoiSinDevolverCentrosGenericosDeCiudad() {
        var service = new DireccionesHibridasService("", url + "/arcgis/findAddressCandidates", "clave-prueba",
                JsonMapper.builder().build(), area);
        var resultados = service.buscarArcgis("Unicentro Villavicencio", "validMagicKey001");
        assertEquals(1, resultados.size());
        assertEquals("Unicentro Villavicencio Centro Comercial", resultados.getFirst().nombre());
        assertEquals("LUGAR", resultados.getFirst().precision());
        assertTrue(requestBody.get().contains("magicKey=validMagicKey001"));
        assertTrue(requestBody.get().contains("forStorage=false"));
    }

    @Test void noConfundeCalleConUnaDireccionExactaNiAceptaPuntosFueraDeCiudad() {
        var service = new DireccionesHibridasService("", url + "/arcgis/findAddressCandidates", "clave-prueba",
                JsonMapper.builder().build(), area);
        var resultados = service.buscarArcgis("Avenida 40 #26C-10, Villavicencio");
        assertTrue(resultados.isEmpty());
    }

    @Test void filtraCandidatosArcgisYNoSolicitaAlmacenamiento() {
        var service = new DireccionesHibridasService("", url + "/arcgis", "clave-prueba",
                JsonMapper.builder().build(), area);
        var results = service.buscarArcgis("Calle 15 # 40-10");
        assertEquals(1, results.size());
        assertEquals("DIRECCION_PUNTUAL", results.getFirst().precision());
        assertFalse(results.getFirst().confirmado());
        assertTrue(requestBody.get().contains("forStorage=false"));
        assertTrue(requestBody.get().contains("clave-prueba"));
    }
}
