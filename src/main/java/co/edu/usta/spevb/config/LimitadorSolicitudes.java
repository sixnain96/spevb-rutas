package co.edu.usta.spevb.config;

import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Límite sencillo por clave (p. ej. IP) en ventana deslizante, para endpoints públicos de escritura. */
@Component
public class LimitadorSolicitudes {
    private final Map<String, Deque<Long>> historial = new ConcurrentHashMap<>();

    public boolean permitir(String clave, int maximo, Duration ventana) {
        long ahora = System.currentTimeMillis();
        long desde = ahora - ventana.toMillis();
        if (historial.size() > 10_000) historial.clear();
        Deque<Long> cola = historial.computeIfAbsent(clave, k -> new ArrayDeque<>());
        synchronized (cola) {
            while (!cola.isEmpty() && cola.peekFirst() < desde) cola.pollFirst();
            if (cola.size() >= maximo) return false;
            cola.addLast(ahora);
            return true;
        }
    }
}
