# Mapa más rápido y compacto (v4.3)

Cambios solo de rendimiento y presentación: ninguna función se quitó.

| Cambio | Efecto |
|---|---|
| Créditos compactos: se ve «Leaflet ⓘ» (≈ 68 px en vez de ≈ 360) y al tocar se despliegan OpenFreeMap, OpenMapTiles, OpenStreetMap, Esri… (se cierran solos a los 8 s) | Menos espacio ocupado. Los créditos siguen a un toque, como exigen las licencias ODbL/OpenMapTiles/Esri |
| El mapa ráster de respaldo solo aparece si el vectorial tarda más de 1,5 s (o falla) | Con buena conexión ya no se descargan dos mapas a la vez: ahorra datos y trabajo |
| Resolución del mapa vectorial limitada a 2× (1,5× en equipos modestos) | Pantallas de 3× pintan ≈ 55 % menos píxeles sin pérdida visible |
| Sin antialias de la malla, sin refresco de teselas vencidas, actualización menos frecuente en equipos modestos, fundido corto | Menos trabajo de GPU/CPU al mover el mapa |
| Se pausan las líneas punteadas animadas y el halo del bus mientras se arrastra o se hace zoom | El dedo manda: el movimiento es más fluido |
| Ya existente y se mantiene: motor vectorial cargado solo si se usa, teselas en caché (service worker), modo ráster ligero en equipos modestos, bus a 30 fps en equipos de gama baja | |

## Medición (celular simulado: CPU 4× más lenta, red 3G rápida, WebGL por software)
`docs/pruebas/rendimiento_mapa.py` (antes → después, arrastrar y hacer zoom durante ≈ 14 s):
- Cuadros dibujados: 156–335 → 687
- Cuadro «lento» (percentil 95): 580–2650 ms → 230 ms
- Tiempo bloqueando el hilo principal: 23–30 s → 15 s
Es una medición relativa en un entorno sin GPU: en un teléfono real las cifras absolutas serán mejores, pero la mejora debe notarse igual.

## Pruebas
`docs/pruebas/mapa_e2e.py` (créditos, respaldo ráster, resolución, pausa de animaciones, en celular, tablet y computador).

---

# Mediciones 4.13.2 (Leaflet real vs. Google con SDK SIMULADO)

Script: `e2e/rendimiento/medir_mapa.py` (resultados crudos en `docs/pruebas/rendimiento_4.13.2.json`). Condiciones: Chromium headless 157, CPU 4× más lenta, 390×844 a 2×, servidor simulado local (sin latencia de red ni teselas externas),
5 cargas frías y 5 calientes por proveedor y 3 corridas de interacciones (por eso **no se informan percentiles**: con menos de 5 repeticiones no significan nada; solo medianas).

**Qué compara y qué NO:** Leaflet es el motor real; Google es un SDK simulado que no dibuja. Por eso las cifras de Google miden solo el trabajo de SEVB alrededor del mapa; **no** miden la descarga del SDK real, las teselas, el dibujo, los gestos ni la memoria del SDK. Esas columnas son «no medido» hasta tener una clave real y un teléfono.

| Mediana | Leaflet (real) | Google (simulado) |
|---|---|---|
| Carga fría: hasta «mapa utilizable» | 1.361 ms | 583 ms (sin SDK real: no comparable) |
| Carga caliente: hasta «mapa utilizable» | 505 ms | 302 ms (idem) |
| JavaScript propio descargado | 2.055 KB | 518 KB (el SDK real lo descarga Google: no medido) |
| Solicitudes de red de la página | 57 | 50 |
| Tareas largas en la carga fría (suma) | 372 ms | ≈ 0 ms (idem) |
| Planificar A→B (hasta dibujar la opción recomendada) | 300 ms | 250 ms |
| Llamadas a la API al planificar | 6 | 6 |
| Llamadas a la API por escribir A y B | 0 | 0 |
| Elementos en el mapa tras la 1.ª y tras 10 búsquedas repetidas | 18 → 18 | 18 → 18 |
| Instancias de `Map` | 1 | 1 |
| Escuchas activas del SDK tras 10 búsquedas | — | 30 (estable) |
| Memoria JS (MB) al inicio → tras 10 búsquedas | 3,4 → 6,0 | 1,4 → 3,2 |
| Ráfaga de 40 movimientos de cámara: mediana de cuadro / máximo | 10 / 28 ms | 4 / 6 ms (no representativo: el simulado no dibuja) |

**Lectura honesta:** (1) la comparación útil de carga entre proveedores no existe todavía; lo que sí se ve es que el modo Google **no descarga Leaflet ni MapLibre** (518 KB frente a 2.055 KB propios) y que **nunca hay más de una instancia de mapa**; (2) la planificación no agrega solicitudes ni elementos al repetirla (18 → 18), y el cálculo de recomendación no dependió del proveedor; (3) los tiempos de cámara de Leaflet son del motor real con CPU 4× más lenta; (4) no se midió un antes/después de rendimiento de 4.13.1: el antes/después **estructural** está en las pruebas (layout: el mapa pasaba a medir 576 px con el panel oculto, ahora mide 844 px; estela del bus: se agregan solo los puntos nuevos en vez de reconvertir toda la polilínea).
Por medir con tu clave en un teléfono medio: tiempo de carga del SDK, primer mapa utilizable real, fluidez de pellizco/arrastre, bus animado, editor con 1.500 y 10.000 puntos y los cientos de polilíneas de `pasos.html`/`puentes.html`.
