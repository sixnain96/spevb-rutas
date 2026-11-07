# Red peatonal, punto de subida óptimo y pasos propios

## Cómo se calcula lo que hay que caminar
1. **Red de calles propia.** SPEVB guarda en memoria las calles, andenes, senderos y escaleras de OpenStreetMap.
   - Primer arranque: carga al instante la semilla incluida (`src/main/resources/osm/red-peatonal-semilla.bin.gz`, casco urbano) y descarga en segundo plano la red completa de Overpass.
   - La descarga se guarda en `data/red-peatonal.bin.gz` y se renueva cada 60 días (`RED_PEATONAL_REFRESCO_DIAS`).
   - Estado: `GET /api/caminata/estado` (`LISTA`, nodos, aristas). Descargar de nuevo: botón en `/pasos.html` o `POST /api/editor/red-peatonal/actualizar`.
2. **Trayecto a pie** (`/api/caminata`): solo recorre la red; nunca cruza manzanas ni edificios. Orden: red propia → OSRM (`WALKING_URL`, respaldo) → línea recta naranja con aviso.
3. **Punto de subida y bajada óptimo**: cada ruta cercana se muestrea cada 15 m, se mide la caminata real por calles desde A y hasta B a cada punto (una exploración por extremo) y se elige el par que menos hace caminar.
   - Se respeta el sentido de la ruta. Si el sentido correcto obliga a caminar más de 600 m extra, se muestra la opción en sentido contrario con su advertencia.
   - Un paradero registrado se prefiere si no queda más de 25 m más lejos.
   - **Variante «más rápida»**: la misma ruta con otros puntos de subida y bajada si ahorra 2 min o más en total (caminar 4,8 km/h, bus 16 km/h; sin espera).
   - Un punto en medio de una manzana prueba a la vez las 4 calles más cercanas.
4. «Rutas cerca del origen» usa el mismo método exacto.

## Pasos peatonales propios (`/pasos.html`)
Corrigen OpenStreetMap sin esperar a que alguien lo edite. Requieren la clave de administración (`EDITOR_TOKEN`).
- **Paso nuevo**: camino que existe y falta en el mapa (atajo, puente, sendero). Debe empezar y terminar en una esquina o sobre una calle (≤ 25 m). La pantalla avisa si algún extremo quedó sin conectar.
- **Bloqueo**: calles del mapa que no se pueden recorrer. Las calles a menos de 6 m de la línea dejan de usarse.
- Se guardan en PostgreSQL (`pasos_peatonales`) y se reaplican en cada descarga de la red. Al guardar o borrar se recalcula la red y se vacían los cálculos guardados.
- API: `GET/POST /api/editor/pasos`, `DELETE /api/editor/pasos/{id}`. Calles de una zona: `GET /api/caminata/red?sur=&oeste=&norte=&este=` (máx. 0,03° por lado).

## Límites
- La calidad depende de OpenStreetMap: una calle o sendero que falte allí se corrige con un paso propio.
- No hay horarios ni frecuencias de bus registrados: el tiempo estimado no incluye la espera.

Datos de calles © colaboradores de OpenStreetMap (ODbL).
