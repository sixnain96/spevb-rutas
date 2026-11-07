package co.edu.usta.spevb.vial;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static co.edu.usta.spevb.vial.Sinteticas.*;
import static org.junit.jupiter.api.Assertions.*;

class ProveedorMatchingExternoTest {
    private HttpServer server;
    private final AtomicInteger llamadas = new AtomicInteger();
    private final List<String> rutas = new CopyOnWriteArrayList<>();
    private final List<String> cabeceras = new CopyOnWriteArrayList<>();
    private final AtomicReference<Responder> comportamiento = new AtomicReference<>();

    interface Responder { void responder(com.sun.net.httpserver.HttpExchange ex, String ruta) throws Exception; }

    @BeforeEach void arrancar() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            llamadas.incrementAndGet();
            rutas.add(ex.getRequestURI().getRawPath());
            ex.getRequestHeaders().forEach((k, v) -> cabeceras.add(k.toLowerCase(Locale.ROOT)));
            try { comportamiento.get().responder(ex, ex.getRequestURI().getRawPath()); }
            catch (Exception e) { ex.sendResponseHeaders(500, -1); }
            finally { ex.close(); }
        });
        server.start();
    }

    @AfterEach void parar() { server.stop(0); }

    private String url() { return "http://127.0.0.1:" + server.getAddress().getPort(); }

    private static void json(com.sun.net.httpserver.HttpExchange ex, int codigo, String cuerpo) throws IOException {
        byte[] b = cuerpo.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(codigo, b.length);
        ex.getResponseBody().write(b);
    }

    /** Responde un emparejamiento cuya geometría es exactamente la lista de coordenadas pedidas en la URL. */
    private static final Responder ECO = (ex, ruta) -> {
        String coords = ruta.substring(ruta.lastIndexOf('/') + 1);
        StringBuilder g = new StringBuilder();
        for (String par : coords.split(";")) { if (g.length() > 0) g.append(','); g.append('[').append(par).append(']'); }
        json(ex, 200, "{\"code\":\"Ok\",\"matchings\":[{\"confidence\":0.9,\"geometry\":{\"type\":\"LineString\",\"coordinates\":[" + g + "]}}]}");
    };

    private static List<double[]> recorrido(int n) {
        List<double[]> r = new ArrayList<>();
        for (int i = 0; i < n; i++) r.add(p(i * 10.0, 0));
        return r;
    }

    private ProveedorMatchingExterno cliente(int fallos, Duration timeout, AtomicLong reloj) {
        return new ProveedorMatchingExterno(new ProveedorMatchingExterno.Config(url(), timeout, 100_000, fallos, Duration.ofSeconds(30), 100, 10, 30),
                reloj::get);
    }

    @Test void desactivadoSinUrlNuncaHaceUnaPeticion() {
        for (String u : new String[]{"", "   ", null, "ftp://x"}) {
            var c = new ProveedorMatchingExterno(u);
            assertFalse(c.activo(), String.valueOf(u));
            assertTrue(c.ajustar(recorrido(5)).isEmpty());
        }
        assertEquals(0, llamadas.get());
    }

    @Test void ajustaYDevuelveLatLngEnOrdenConLaRutaOsrmCorrecta() {
        comportamiento.set(ECO);
        var c = new ProveedorMatchingExterno(url());
        assertTrue(c.activo());
        var pts = recorrido(6);
        var r = c.ajustar(pts).orElseThrow();
        assertEquals(6, r.size());
        for (int i = 0; i < 6; i++) { assertEquals(pts.get(i)[0], r.get(i)[0], 1e-6); assertEquals(pts.get(i)[1], r.get(i)[1], 1e-6); }
        assertTrue(rutas.get(0).startsWith("/match/v1/driving/"), rutas.get(0));
        // OSRM espera lng,lat
        assertTrue(rutas.get(0).contains(String.format(Locale.ROOT, "%.6f,%.6f", pts.get(0)[1], pts.get(0)[0])));
    }

    @Test void nuncaEnviaAuthorizationNiTokenDelEditorNiCookies() {
        comportamiento.set(ECO);
        new ProveedorMatchingExterno(url()).ajustar(recorrido(4));
        assertFalse(cabeceras.isEmpty());
        for (String h : cabeceras) {
            assertNotEquals("authorization", h);
            assertNotEquals("x-editor-token", h);
            assertNotEquals("cookie", h);
            assertFalse(h.contains("token") || h.contains("auth"), h);
        }
    }

    @Test void tiempoAgotadoDevuelveVacioSinColgarse() {
        comportamiento.set((ex, r) -> { Thread.sleep(1500); ECO.responder(ex, r); });
        var c = cliente(3, Duration.ofMillis(300), new AtomicLong());
        long t0 = System.nanoTime();
        assertTrue(c.ajustar(recorrido(5)).isEmpty());
        assertTrue((System.nanoTime() - t0) / 1_000_000 < 1400, "debe cortar por timeout");
    }

    @Test void fallosDelServidorRespuestasMalasOEnormesDevuelvenVacio() {
        var c = cliente(100, Duration.ofSeconds(2), new AtomicLong());
        comportamiento.set((ex, r) -> json(ex, 500, "{}"));
        assertTrue(c.ajustar(recorrido(5)).isEmpty());
        comportamiento.set((ex, r) -> json(ex, 200, "esto no es json"));
        assertTrue(c.ajustar(recorrido(5)).isEmpty());
        comportamiento.set((ex, r) -> json(ex, 200, "{\"code\":\"Ok\",\"pad\":\"" + "x".repeat(200_000) + "\"}"));
        assertTrue(c.ajustar(recorrido(5)).isEmpty(), "supera el tope de tamaño de respuesta");
        comportamiento.set((ex, r) -> json(ex, 200, "{\"code\":\"Ok\",\"matchings\":[{\"geometry\":{\"coordinates\":[]}}]}"));
        assertTrue(c.ajustar(recorrido(5)).isEmpty());
    }

    @Test void sinEmparejamientoContinuoNoSeInventaNada() {
        var c = cliente(1, Duration.ofSeconds(2), new AtomicLong());
        comportamiento.set((ex, r) -> json(ex, 400, "{\"code\":\"NoMatch\"}"));
        assertTrue(c.ajustar(recorrido(5)).isEmpty());
        comportamiento.set((ex, r) -> json(ex, 200, "{\"code\":\"Ok\",\"matchings\":[{\"geometry\":{\"coordinates\":[[1,1],[2,2]]}},{\"geometry\":{\"coordinates\":[[3,3],[4,4]]}}]}"));
        assertTrue(c.ajustar(recorrido(5)).isEmpty(), "dos emparejamientos = recorrido partido");
        assertEquals(ProveedorMatchingExterno.Circuito.CERRADO, c.circuito(), "un NoMatch no es una caída del servicio");
    }

    @Test void interruptorDeCircuitoSeAbreTrasNFallosYReintentaPasadoElTiempo() {
        AtomicLong reloj = new AtomicLong(1_000);
        var c = cliente(3, Duration.ofSeconds(2), reloj);
        comportamiento.set((ex, r) -> json(ex, 500, "{}"));
        for (int i = 0; i < 3; i++) assertTrue(c.ajustar(recorrido(5)).isEmpty());
        assertEquals(3, llamadas.get());
        assertEquals(ProveedorMatchingExterno.Circuito.ABIERTO, c.circuito());
        for (int i = 0; i < 5; i++) assertTrue(c.ajustar(recorrido(5)).isEmpty());
        assertEquals(3, llamadas.get(), "con el circuito abierto no se llama al servidor");
        reloj.addAndGet(31_000);
        assertTrue(c.ajustar(recorrido(5)).isEmpty(), "el reintento falla");
        assertEquals(4, llamadas.get(), "pasado el tiempo se prueba una vez");
        assertEquals(ProveedorMatchingExterno.Circuito.ABIERTO, c.circuito());
        assertTrue(c.ajustar(recorrido(5)).isEmpty());
        assertEquals(4, llamadas.get(), "y vuelve a quedar abierto");
        reloj.addAndGet(31_000);
        comportamiento.set(ECO);
        assertTrue(c.ajustar(recorrido(5)).isPresent(), "el servidor se recuperó");
        assertEquals(ProveedorMatchingExterno.Circuito.CERRADO, c.circuito());
        assertTrue(c.ajustar(recorrido(5)).isPresent());
    }

    @Test void divideLosRecorridosGrandesEnFragmentosConSolapeYConservaElOrden() {
        comportamiento.set(ECO);
        var c = new ProveedorMatchingExterno(url());
        var pts = recorrido(250);
        var r = c.ajustar(pts).orElseThrow();
        assertTrue(llamadas.get() >= 3, "250 puntos con tope de 100 y solape de 10 requieren al menos 3 peticiones: " + llamadas.get());
        assertEquals(pts.size(), r.size(), "tras empalmar queda cada punto una sola vez");
        double previo = -Double.MAX_VALUE;
        for (int i = 0; i < r.size(); i++) {
            assertEquals(pts.get(i)[1], r.get(i)[1], 1e-6, "orden conservado en " + i);
            assertTrue(r.get(i)[1] > previo);
            previo = r.get(i)[1];
        }
    }

    @Test void siLosFragmentosNoEmpalmanNoDevuelveNada() {
        AtomicInteger n = new AtomicInteger();
        comportamiento.set((ex, ruta) -> {
            if (n.getAndIncrement() == 0) ECO.responder(ex, ruta);
            else json(ex, 200, "{\"code\":\"Ok\",\"matchings\":[{\"geometry\":{\"coordinates\":[[0,0],[0.1,0.1]]}}]}");
        });
        assertTrue(new ProveedorMatchingExterno(url()).ajustar(recorrido(250)).isEmpty());
    }
}
