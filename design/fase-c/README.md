# Fase C — mapa, paraderos contextuales, puentes y GPS

Respaldos de los archivos originales en `backup/`. Pruebas: `src/test/java/.../config/MapaContextualTest.java`, `design/gps/gps_harness.html`
(`/__gps_harness.html` en `design/tools/serve_static_proxy.py`) y `design/bus/test/harness.html` (bus, sin cambios de comportamiento).

## Cambios
- `index.html`: sin casilla «Paraderos»; ajuste `paraderosMapa` en el menú; botón «Ver en el mapa» en las paradas de una ruta.
- `app.js`: la capa de paraderos de la ruta (`stops`) ya no se añade sola; `showRouteStops()`.
- `paraderos-publicos.js` (reescrito): solo con el ajuste; `AbortController`, debounce, caché por cuadrícula de 0,01°, diferencias, límite 300.
- `puentes-publicos.js` (reescrito): `Spevb.Puentes.alRecorrer/usados/cargar`; un puente «se cruza» si un tramo seguido del trayecto a ≤ 12 m cubre ≥ 70 % de su largo.
- `planner.js`: puntos del itinerario con símbolo (`punto('sube'|'baja'|'cambio')`), `revelar()` (tramo creciendo una vez), notas de puente en la tarjeta,
  estados del botón de ubicación, pulso solo al adquirir, `acomodarRotulos` sin trabajo si no hay viaje, `streetWalk` no pide si la línea ya no está.
- `gps.js`: última ubicación conocida solo informativa, `soltarRed`, supresión de repintados dentro de la incertidumbre, pausa con la página oculta. API sin cambios.
- `bus.js`: el bucle `requestAnimationFrame` se detiene en pausa (`kick()` lo reanuda); la cámara de «Seguir bus» usa un umbral de 2 px.
- `home.css`, `motion.css`: símbolos, `walk-reveal` en lugar de `walk-flow`, estados del botón de ubicación, `.gps-dot.adquiriendo`.
- `sw.js`: `spevb-v4.9.3`.

## No tocado a propósito
Cálculo de rutas, caminatas, transbordos y puentes en el backend; `/api/puentes` y `/api/paraderos` (siguen igual); editor y páginas de administración.
