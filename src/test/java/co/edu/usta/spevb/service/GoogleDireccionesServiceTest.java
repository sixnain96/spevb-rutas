package co.edu.usta.spevb.service;

import co.edu.usta.spevb.config.CityArea;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/** Google es opcional: apagado sin clave y, con clave, habla con un servidor simulado (sin Internet ni gastos). */
class GoogleDireccionesServiceTest {
    private final CityArea area = new CityArea("Villavicencio", "Meta", "Colombia", 3.95, 4.35, -73.85, -73.45);
    private HttpServer servidor;
    private final List<String> pedidos = new ArrayList<>();
    private final AtomicReference<String> cabeceraClave = new AtomicReference<>();
    private final AtomicReference<String> cuerpoRecibido = new AtomicReference<>();
    private final AtomicReference<String> respuestaLugar = new AtomicReference<>();
    private final AtomicReference<String> mascaraCampos = new AtomicReference<>();
    private int estado = 200;

    @BeforeEach void arrancar() throws Exception {
        servidor = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        servidor.createContext("/", ex -> {
            pedidos.add(ex.getRequestMethod() + " " + ex.getRequestURI());
            cabeceraClave.set(ex.getRequestHeaders().getFirst("X-Goog-Api-Key"));
            mascaraCampos.set(ex.getRequestHeaders().getFirst("X-Goog-FieldMask"));
            cuerpoRecibido.set(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            String json = ex.getRequestURI().getPath().endsWith(":autocomplete")
                    ? "{\"suggestions\":[{\"placePrediction\":{\"placeId\":\"ChIJabcdefghij123\",\"text\":{\"text\":\"Unicentro Villavicencio, Villavicencio, Meta\"}}},"
                    + "{\"placePrediction\":{\"placeId\":\"mal id con espacios\",\"text\":{\"text\":\"Descartada\"}}},"
                    + "{\"placePrediction\":{\"placeId\":\"ChIJabcdefghij123\",\"text\":{\"text\":\"Unicentro Villavicencio, Villavicencio, Meta\"}}}]}"
                    : respuestaLugar.get();
            byte[] b = json.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(estado, b.length);
            ex.getResponseBody().write(b);
            ex.close();
        });
        servidor.start();
    }

    @AfterEach void parar() { servidor.stop(0); }

    private GoogleDireccionesService servicio(String clave) {
        return new GoogleDireccionesService(clave, "http://127.0.0.1:" + servidor.getAddress().getPort(), JsonMapper.builder().build(), area);
    }

    @Test void sinClaveEstaApagadoYNoLlamaANadie() {
        var g = servicio("");
        assertFalse(g.activo());
        assertTrue(g.sugerir("Unicentro").isEmpty());
        assertTrue(g.buscar("ChIJabcdefghij123").isEmpty());
        assertTrue(pedidos.isEmpty(), "apagado no hace ninguna llamada");
    }

    @Test void sugiereConLaClaveEnLaCabeceraNuncaEnLaUrlYDescartaLoInvalido() {
        var g = servicio("clave-de-prueba");
        var s = g.sugerir("Unicentro");
        assertEquals(1, s.size(), "sin repetidos y sin identificadores raros");
        assertEquals("ChIJabcdefghij123", s.get(0).lugarId());
        assertEquals("clave-de-prueba", cabeceraClave.get());
        assertTrue(pedidos.stream().noneMatch(p -> p.contains("clave-de-prueba")), "la clave no viaja en la URL");
        assertTrue(cuerpoRecibido.get().contains("\"co\""), "limitado a Colombia");
        assertTrue(g.sugerir("ab").isEmpty(), "con menos de 3 letras no consulta");
    }

    @Test void ubicaElLugarElegidoSoloDentroDeVillavicencioYMarcaLaFuente() {
        respuestaLugar.set("{\"id\":\"x\",\"displayName\":{\"text\":\"Unicentro\"},\"formattedAddress\":\"Cra. 33 #38-10, Villavicencio, Meta\","
                + "\"location\":{\"latitude\":4.1272,\"longitude\":-73.6203},\"types\":[\"shopping_mall\",\"point_of_interest\"]}");
        var g = servicio("k");
        var r = g.buscar("ChIJabcdefghij123");
        assertEquals(1, r.size());
        assertEquals("Unicentro", r.get(0).nombre());
        assertEquals("Google Maps", r.get(0).fuente());
        assertEquals("LUGAR", r.get(0).precision());
        assertFalse(r.get(0).confirmado(), "lo de Google nunca se da por verificado");

        respuestaLugar.set("{\"formattedAddress\":\"Bogotá\",\"location\":{\"latitude\":4.71,\"longitude\":-74.07},\"types\":[\"locality\"]}");
        assertTrue(g.buscar("ChIJabcdefghij123").isEmpty(), "fuera de la ciudad no se devuelve");
        assertTrue(g.buscar("../../etc/passwd").isEmpty(), "un identificador inválido no llega a Google");
    }

    @Test void labusquedaPorTextoDevuelveVariosLugaresDeVillavicencioYDescartaLosDeFuera() {
        respuestaLugar.set("{\"places\":[{\"id\":\"a\",\"displayName\":{\"text\":\"Unicentro\"},\"formattedAddress\":\"Cra. 33, Villavicencio\",\"location\":{\"latitude\":4.1272,\"longitude\":-73.6203},\"types\":[\"shopping_mall\"]},"
                + "{\"id\":\"b\",\"displayName\":{\"text\":\"Unicentro Bogotá\"},\"formattedAddress\":\"Bogotá\",\"location\":{\"latitude\":4.7,\"longitude\":-74.04},\"types\":[\"shopping_mall\"]}]}");
        var g = servicio("k");
        var r = g.buscarTexto("Unicentro");
        assertEquals(1, r.size(), "la de Bogotá queda fuera");
        assertEquals("Unicentro", r.get(0).nombre());
        assertTrue(pedidos.stream().anyMatch(p -> p.contains("places:searchText")));
        assertTrue(cuerpoRecibido.get().contains("\"textQuery\""));
        assertTrue(servicio("").buscarTexto("Unicentro").isEmpty());
    }

    @Test void siGoogleFallaSeAvisaSinMostrarLaClave() {
        estado = 403;
        var g = servicio("clave-secreta");
        var ex = assertThrows(IllegalStateException.class, () -> g.sugerir("Unicentro"));
        assertFalse(ex.getMessage().contains("clave-secreta"));
        assertEquals(EstadoBusqueda.CLAVE, ((ProveedorException) ex).estado(), "403 = problema de clave o permiso");
    }

    @Test void clasificaLaCuotaLaClaveInvalidaYLaRespuestaIlegible() {
        var g = servicio("k");
        estado = 429;
        assertEquals(EstadoBusqueda.CUOTA, assertThrows(ProveedorException.class, () -> g.sugerir("Unicentro")).estado());
        estado = 400;
        respuestaLugar.set("{\"error\":{\"message\":\"API key not valid.\",\"status\":\"INVALID_ARGUMENT\"}}");
        assertEquals(EstadoBusqueda.CLAVE, assertThrows(ProveedorException.class, () -> g.buscar("ChIJabcdefghij123")).estado());
        estado = 200;
        respuestaLugar.set("esto no es json");
        assertEquals(EstadoBusqueda.RESPUESTA_INVALIDA, assertThrows(ProveedorException.class, () -> g.buscar("ChIJabcdefghij123")).estado());
        servidor.stop(0);
        assertEquals(EstadoBusqueda.RED, assertThrows(ProveedorException.class, () -> g.sugerir("Unicentro")).estado(), "servidor caído = red");
    }

    @Test void elTokenDeSesionViajaEnElAutocompletadoYEnElDetalleSoloSiTieneLaFormaEsperada() {
        var g = servicio("k");
        String token = "0b1c2d3e-4f50-6172-8394-a5b6c7d8e9f0";
        g.sugerir("Unicentro", token);
        assertTrue(cuerpoRecibido.get().contains("\"sessionToken\":\"" + token + "\""), "el autocompletado lleva el token de la búsqueda");
        g.sugerir("Unicentro", "   mal token con espacios  ");
        assertFalse(cuerpoRecibido.get().contains("sessionToken"), "un token inválido se ignora: no se inventa uno compartido");
        g.sugerir("Unicentro");
        assertFalse(cuerpoRecibido.get().contains("sessionToken"));

        respuestaLugar.set("{\"formattedAddress\":\"Cra. 33 #38-10, Villavicencio, Meta\",\"location\":{\"latitude\":4.1272,\"longitude\":-73.6203},\"types\":[\"shopping_mall\"]}");
        g.resolver("ChIJabcdefghij123", "Unicentro Villavicencio, Cra. 33, Meta", token);
        assertTrue(pedidos.get(pedidos.size() - 1).contains("sessionToken=" + token), "el detalle cierra la sesión con el mismo token");
        g.resolver("ChIJabcdefghij123", null, null);
        assertFalse(pedidos.get(pedidos.size() - 1).contains("sessionToken"));
        assertNull(GoogleDireccionesService.sesionValida("x"));
        assertEquals(token, GoogleDireccionesService.sesionValida(token));
    }

    @Test void elDetallePideSoloCamposEssentialsYElNombreSaleDeLaSugerenciaElegida() {
        respuestaLugar.set("{\"formattedAddress\":\"Cra. 33 #38-10, Villavicencio, Meta\",\"location\":{\"latitude\":4.1272,\"longitude\":-73.6203},\"types\":[\"shopping_mall\"]}");
        var g = servicio("k");
        var r = g.resolver("ChIJabcdefghij123", "Unicentro Villavicencio, Cra. 33 #38-10, Meta", null);
        assertEquals("id,formattedAddress,location,types", mascaraCampos.get(), "sin displayName (nivel Pro): el detalle se factura en Essentials");
        assertFalse(mascaraCampos.get().contains("displayName"));
        assertEquals("Unicentro Villavicencio", r.lugares().get(0).nombre());
        assertEquals("Cra. 33 #38-10, Villavicencio, Meta", r.lugares().get(0).direccion());
        var sinNombre = g.resolver("ChIJabcdefghij123", null, null);
        assertEquals("Cra. 33 #38-10, Villavicencio, Meta", sinNombre.lugares().get(0).nombre(), "sin sugerencia, el nombre es la dirección");
        respuestaLugar.set("{\"places\":[]}");
        g.buscarTexto("Unicentro");
        assertTrue(mascaraCampos.get().contains("places.displayName") && mascaraCampos.get().contains("places.location"), "la búsqueda por texto sí pide el nombre");
    }

    @Test void cuentaLosLugaresDescartadosPorEstarFueraDeLaCiudad() {
        respuestaLugar.set("{\"formattedAddress\":\"Bogotá\",\"location\":{\"latitude\":4.71,\"longitude\":-74.07},\"types\":[\"locality\"]}");
        var r = servicio("k").resolver("ChIJabcdefghij123", null, null);
        assertTrue(r.lugares().isEmpty());
        assertEquals(1, r.fueraDeCiudad());
    }

    @Test void clasificaLaPrecisionSegunLosTiposDeGoogle() {
        var m = JsonMapper.builder().build();
        assertEquals("DIRECCION_PUNTUAL", GoogleDireccionesService.precision(m.readTree("[\"street_address\"]")));
        assertEquals("VIA", GoogleDireccionesService.precision(m.readTree("[\"route\"]")));
        assertEquals("SECTOR", GoogleDireccionesService.precision(m.readTree("[\"neighborhood\",\"political\"]")));
        assertEquals("CIUDAD", GoogleDireccionesService.precision(m.readTree("[\"locality\"]")));
        assertEquals("LUGAR", GoogleDireccionesService.precision(m.readTree("[\"restaurant\"]")));
    }

    @Test void unaUrlSinHttpsSeRechazaConClave() {
        assertThrows(IllegalArgumentException.class, () -> new GoogleDireccionesService("k", "http://ejemplo.com", JsonMapper.builder().build(), area));
        assertDoesNotThrow(() -> new GoogleDireccionesService("", "http://ejemplo.com", JsonMapper.builder().build(), area), "apagado no valida");
    }
}
