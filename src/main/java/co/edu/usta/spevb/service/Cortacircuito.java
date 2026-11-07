package co.edu.usta.spevb.service;

import java.util.function.LongSupplier;

/**
 * Cortacircuito de un proveedor: tras {@link #FALLOS_PARA_ABRIR} fallos SEGUIDOS deja de consultarlo durante {@link #PAUSA_MS}; pasada la pausa
 * deja pasar UNA consulta de prueba (las demás siguen en pausa hasta conocer su resultado): si sale bien se cierra, si falla se reabre otra pausa.
 * Así no se insiste en cada pulsación contra un servicio caído ni se espera su plazo cada vez. El reloj es inyectable para probarlo sin dormir.
 */
public final class Cortacircuito {
    public static final int FALLOS_PARA_ABRIR = 3;
    public static final long PAUSA_MS = 30_000;

    private final LongSupplier reloj;
    private int fallosSeguidos;
    private long abiertoHasta;          // 0 = cerrado
    private boolean probando;

    public Cortacircuito() { this(System::currentTimeMillis); }
    public Cortacircuito(LongSupplier relojMs) { this.reloj = relojMs; }

    /** ¿Se puede consultar ahora? Con el circuito abierto y la pausa cumplida concede UNA sola prueba. */
    public synchronized boolean permitir() {
        if (abiertoHasta == 0) return true;
        if (reloj.getAsLong() < abiertoHasta) return false;
        if (probando) return false;
        probando = true;
        return true;
    }

    /** El proveedor respondió (aunque sea sin coincidencias): se cierra el circuito. */
    public synchronized void exito() { fallosSeguidos = 0; abiertoHasta = 0; probando = false; }

    public synchronized void fallo() {
        fallosSeguidos++;
        boolean eraPrueba = probando;
        probando = false;
        if (eraPrueba || fallosSeguidos >= FALLOS_PARA_ABRIR) abiertoHasta = reloj.getAsLong() + PAUSA_MS;
    }

    public synchronized boolean abierto() { return abiertoHasta != 0; }
}
