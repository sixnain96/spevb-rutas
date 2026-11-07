# Fase B — sistema de movimiento

Cambios solo visuales y de animación; no se tocó ningún algoritmo (rutas, caminatas, transbordos, GPS, bus). Respaldos de los
archivos originales en `backup/`. Servidor de pruebas sin compilar: `python design/tools/serve_static_proxy.py` (puerto 8768, reenvía `/api/*` a 8080).

## Qué hay
- `static/css/motion.css` (nuevo): tokens `--motion-*` y `--ease-*`, entradas de pines A/B, cuerpo de tarjeta abierta, barra de la tarjeta elegida y
  bloques de movimiento reducido (sistema `prefers-reduced-motion` y `html[data-motion="reduce"]`).
- `static/js/motion.js` (nuevo): `Spevb.Motion.reduced`, atributo `html[data-motion]` y FLIP de tarjetas (solo `transform`, sin efecto con movimiento reducido).
- `home.css`, `panel-ocultable.css`, `styles.css`: duraciones a tokens; GPS y botón «Buscar ruta» pasan de animar `box-shadow` a un anillo con
  `transform`+`opacity`; escalonado de tarjetas de 60–240 ms a 25 ms (tope 100 ms); `:active` de botones de 0,92 a 0,96; sin `will-change` permanente;
  una sola definición de `@keyframes bus-pulse` (la de `bus3d.css`).
- `sheet.js`: `will-change` de la hoja solo durante el arrastre y su transición.
- `planner.js` (`drawPins`): cada pin se recrea solo si su punto cambió; el nuevo entra (`pin-in`) y al intercambiar A/B cada uno viaja desde su sitio (`pin-swap`).
- `index.html` (enlaces) y `sw.js` (`SHELL`, `spevb-v4.9.2`).
- Prueba: `src/test/java/.../config/MotionCssTest.java`.

## Pendiente para la Fase C (no tocado a propósito)
`walk-flow` (línea de caminata animada de forma continua), `bus-pulse` del halo, `latido` del aviso «Siguiendo el bus», `gps-ring` continuo
(debe pulsar solo mientras se adquiere la lectura), transición de `bottom` de `.map-fabs`, trazado animado de la ruta, cámara.
