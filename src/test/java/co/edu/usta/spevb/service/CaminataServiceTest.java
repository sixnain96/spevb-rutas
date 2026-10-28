package co.edu.usta.spevb.service;

import co.edu.usta.spevb.config.CityArea;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class CaminataServiceTest {
    private HttpServer server;
    private final AtomicInteger hits = new AtomicInteger();
    private volatile int status = 200;
    private volatile long delayMs = 0;

    private static final String OK = "{\"code\":\"Ok\",\"routes\":[{\"distance\":320.5,\"duration\":240.0,\"geometry\":{\"coordinates\":"
            + "[[-73.6266,4.1420],[-73.6270,4.1425],[-73.6280,4.1430]]}}]}";

    @BeforeEach void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            hits.incrementAndGet();
            try { Thread.sleep(delayMs); } catch (InterruptedException ignored) { }
            byte[] body = (status == 200 ? OK : "{}").getBytes();
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(status, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        server.setExecutor(Executors.newFixedThreadPool(8));
        server.start();
    }

    @AfterEach void stop() { server.stop(0); }

    private CaminataService service() {
        return new CaminataService("http://127.0.0.1:" + server.getAddress().getPort() + "/foot", "SPEVB-Test/1.0",
                JsonMapper.builder().build(), new CityArea("Villavicencio", "Meta", "Colombia", 3.95, 4.35, -73.85, -73.45));
    }

    @Test void returnsProviderGeometryUntouchedAndCachesIt() {
        var s = service();
        var first = s.ruta(4.142, -73.6266, 4.143, -73.628);
        assertTrue(first.isPresent());
        assertEquals(3, first.get().coordenadas().size());
        assertArrayEquals(new double[]{-73.6270, 4.1425}, first.get().coordenadas().get(1)); // coordenadas intactas
        assertEquals(320.5, first.get().metros());
        s.ruta(4.142, -73.6266, 4.143, -73.628);
        assertEquals(1, hits.get(), "la segunda consulta sale de la caché");
    }

    @Test void identicalSimultaneousRequestsShareOneProviderCall() throws Exception {
        delayMs = 300;
        var s = service();
        var pool = Executors.newFixedThreadPool(6);
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < 6; i++) futures.add(pool.submit(() -> s.ruta(4.142, -73.6266, 4.143, -73.628)));
        for (var f : futures) assertTrue(((java.util.Optional<?>) f.get(5, TimeUnit.SECONDS)).isPresent());
        pool.shutdownNow();
        assertEquals(1, hits.get());
    }

    @Test void failuresAreNegativeCachedAndOpenTheCircuit() {
        status = 500;
        var s = service();
        assertTrue(s.ruta(4.142, -73.6266, 4.143, -73.628).isEmpty());
        assertTrue(s.ruta(4.142, -73.6266, 4.143, -73.628).isEmpty());
        assertEquals(1, hits.get(), "el mismo fallo no se reintenta de inmediato");
        for (int i = 0; i < 6; i++) s.ruta(4.14 + i * 0.001, -73.62, 4.15, -73.63);
        int before = hits.get();
        s.ruta(4.2, -73.7, 4.21, -73.71);
        assertEquals(before, hits.get(), "con el circuito abierto no se llama al proveedor");
    }

    @Test void rejectsPointsOutsideTheCity() {
        assertThrows(IllegalArgumentException.class, () -> service().ruta(10, -73.6, 4.1, -73.6));
    }
}
