package co.edu.usta.spevb.service;

import co.edu.usta.spevb.config.CityArea;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/** ArcGIS y Nominatim con servidores simulados: estados tipados, «fuera de la ciudad», ayudas de «resultado utilizable» y política de Nominatim. */
class ProveedoresEstadosTest {
    private final CityArea area = new CityArea("Villavicencio", "Meta", "Colombia", 3.95, 4.35, -73.85, -73.45);
    private HttpServer servidor;
    private final AtomicInteger estado = new AtomicInteger(200);
    private final AtomicReference<String> cuerpo = new AtomicReference<>("{}");
    private final AtomicInteger llamadas = new AtomicInteger();
    private final AtomicReference<String> userAgent = new AtomicReference<>();
    private final java.util.List<Long> instantes = new java.util.concurrent.CopyOnWriteArrayList<>();
    private String url;

    @BeforeEach void arrancar() throws Exception {
        servidor = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        servidor.createContext("/", ex -> {
            llamadas.incrementAndGet();
            instantes.add(System.nanoTime());
            userAgent.set(ex.getRequestHeaders().getFirst("User-Agent"));
            ex.getRequestBody().readAllBytes();
            byte[] b = cuerpo.get().getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(estado.get(), b.length);
            ex.getResponseBody().write(b);
            ex.close();
        });
        servidor.start();
        url = "http://127.0.0.1:" + servidor.getAddress().getPort();
    }

    @AfterEach void parar() { servidor.stop(0); }

    private DireccionesHibridasService arcgis() {
        return new DireccionesHibridasService("", url + "/arcgis/findAddressCandidates", "token-de-prueba", JsonMapper.builder().build(), area);
    }

    // ------------------------------------------------------------------ ArcGIS
    @Test void arcgisClasificaCuotaClaveYRespuestasIlegibles() {
        estado.set(429);
        assertEquals(EstadoBusqueda.CUOTA, assertThrows(ProveedorException.class, () -> arcgis().buscarArcgis("Unicentro")).estado());
        estado.set(403);
        assertEquals(EstadoBusqueda.CLAVE, assertThrows(ProveedorException.class, () -> arcgis().buscarArcgis("Unicentro")).estado());
        estado.set(200);
        cuerpo.set("{\"error\":{\"code\":498,\"message\":\"Invalid token\"}}");
        assertEquals(EstadoBusqueda.CLAVE, assertThrows(ProveedorException.class, () -> arcgis().sugerirArcgis("Unicentro")).estado(), "ArcGIS responde 200 con error 498");
        cuerpo.set("{\"error\":{\"code\":500}}");
        assertEquals(EstadoBusqueda.RESPUESTA_INVALIDA, assertThrows(ProveedorException.class, () -> arcgis().buscarArcgis("Unicentro")).estado());
        cuerpo.set("<html>no es json</html>");
        assertEquals(EstadoBusqueda.RESPUESTA_INVALIDA, assertThrows(ProveedorException.class, () -> arcgis().buscarArcgis("Unicentro")).estado());
        estado.set(503);
        assertEquals(EstadoBusqueda.RED, assertThrows(ProveedorException.class, () -> arcgis().buscarArcgis("Unicentro")).estado());
        servidor.stop(0);
        assertEquals(EstadoBusqueda.RED, assertThrows(ProveedorException.class, () -> arcgis().buscarArcgis("Unicentro")).estado(), "servidor caído");
    }

    @Test void arcgisCuentaLosCandidatosFueraDeLaCiudadSinMezclarlosConSinCoincidencias() {
        cuerpo.set("{\"candidates\":[{\"address\":\"Unicentro Bogotá\",\"score\":95,\"location\":{\"x\":-74.07,\"y\":4.7},\"attributes\":{\"Addr_type\":\"POI\"}}]}");
        var r = arcgis().buscarArcgisResultado("Unicentro", "");
        assertTrue(r.lugares().isEmpty());
        assertEquals(1, r.fueraDeCiudad());
        cuerpo.set("{\"candidates\":[]}");
        assertEquals(0, arcgis().buscarArcgisResultado("Unicentro", "").fueraDeCiudad());
    }

    // ------------------------------------------------------------------ ayudas de «resultado utilizable»
    @Test void reconoceDireccionesNumeradasCiudadesGenericasYCoincidenciaDeVia() {
        assertTrue(DireccionesHibridasService.esDireccionNumerada("Carrera 22 #38-10"));
        assertTrue(DireccionesHibridasService.esDireccionNumerada("Cra 22 38 10, Villavicencio"));
        assertFalse(DireccionesHibridasService.esDireccionNumerada("Unicentro"));
        assertFalse(DireccionesHibridasService.esDireccionNumerada("Carrera 22"), "una sola cifra: no es una placa");
        assertTrue(DireccionesHibridasService.esSoloCiudad("Villavicencio, Meta, Colombia"));
        assertFalse(DireccionesHibridasService.esSoloCiudad("Unicentro Villavicencio"));
        assertTrue(DireccionesHibridasService.direccionCoincide("Carrera 22 #38-10", "Cra. 22 #38-10, Villavicencio, Meta"));
        assertFalse(DireccionesHibridasService.direccionCoincide("Carrera 22 #38-10", "Calle 22 #38-10, Villavicencio"), "otra clase de vía");
        assertFalse(DireccionesHibridasService.direccionCoincide("Carrera 22 #38-10", "Cra. 23 #38-10, Villavicencio"), "otro número de vía");
    }

    // ------------------------------------------------------------------ Nominatim: política de uso
    private GeocodingService nominatim(String userAgent) {
        return new GeocodingService(url + "/search", url + "/reverse", userAgent, JsonMapper.builder().build(), area);
    }

    @Test void nominatimExigeUnaIdentificacionPropiaYNuncaUsaLaDeLaBibliotecaHttp() {
        assertThrows(IllegalArgumentException.class, () -> nominatim("Java"), "un User-Agent genérico no identifica a la aplicación");
        cuerpo.set("[]");
        nominatim("SEVB-Rutas/4.13 (contacto en el README)").buscar("Parque Los Libertadores");
        assertEquals("SEVB-Rutas/4.13 (contacto en el README)", userAgent.get());
    }

    @Test void nominatimNoSuperaUnaPeticionPorSegundoNiSiguienteQueEnDosBusquedasSeguidas() {
        assertTrue(GeocodingService.INTERVALO_MINIMO_NS >= 1_000_000_000L, "el máximo absoluto de la política es 1 petición por segundo");
        cuerpo.set("[]");
        var g = nominatim("SEVB-Rutas/4.13 (prueba)");
        g.buscar("Parque Uno de prueba");
        g.buscar("Parque Dos de prueba");
        assertTrue(instantes.size() >= 2);
        long separacionMs = (instantes.get(instantes.size() - 1) - instantes.get(instantes.size() - 2)) / 1_000_000;
        assertTrue(separacionMs >= 1000, "entre dos búsquedas hay al menos 1 s: " + separacionMs + " ms");
    }

    @Test void nominatimGuardaEnCacheLaMismaBusquedaSinVolverAConsultar() {
        cuerpo.set("[]");
        var g = nominatim("SEVB-Rutas/4.13 (prueba)");
        g.buscar("Parque Uno de prueba");
        int antes = llamadas.get();
        g.buscar("Parque Uno de prueba");
        assertEquals(antes, llamadas.get(), "caché permitida: la misma consulta no vuelve al servicio");
    }

    @Test void nominatimDistingueCuotaYRespuestaIlegible() {
        estado.set(429);
        var g = nominatim("SEVB-Rutas/4.13 (prueba)");
        var ex = assertThrows(ResponseStatusException.class, () -> g.buscar("Parque Cuota"));
        assertEquals(EstadoBusqueda.CUOTA, ProveedorException.clasificar(ex));
        estado.set(200);
        cuerpo.set("{\"no\":\"es una lista\"}");
        var ex2 = assertThrows(ResponseStatusException.class, () -> g.buscar("Parque Ilegible"));
        assertEquals(EstadoBusqueda.RESPUESTA_INVALIDA, ProveedorException.clasificar(ex2));
    }
}
