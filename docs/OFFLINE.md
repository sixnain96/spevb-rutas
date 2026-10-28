# Uso con mala señal o sin conexión

Qué queda disponible cuando falla internet y cómo se guarda.

| Qué | Cómo se guarda | Sin conexión |
|---|---|---|
| La aplicación (HTML, CSS, JS, Leaflet, MapLibre) | El service worker (`sw.js`) la guarda al instalarse y la actualiza detrás | Abre al instante |
| Lista de rutas y detalle de cada recorrido | Se piden solas con buena señal (cada 6 h) y el SW deja una copia | Se ven y se dibujan |
| Mapa de Villavicencio (vectorial) | Menú → **Guardar mapa de Villavicencio** (≈ 5–6 MB, ~150 archivos) | Se ve completo hasta zoom 14 |
| Mapa que ya miraste (OpenStreetMap, Esri) | El SW guarda lo visto (máx. 1500 teselas, las más viejas se van) | Solo lo ya visto |
| Últimos viajes A→B (hasta 6) | `localStorage` (chips «Recientes» bajo el botón Buscar rutas) | Un toque repite el viaje con lo guardado, con aviso |

## Qué NO se puede sin conexión
- Calcular un viaje nuevo (usa el servidor y la base de datos). Se avisa con un mensaje claro y se sugiere un reciente.
- Buscar direcciones nuevas y obtener GPS no necesita internet, pero convertir GPS en dirección sí.
- El editor, pasos peatonales y direcciones (requieren servidor).

## Con señal mala (no ausente)
- Los pedidos al servidor se cortan a los 25 s (`Spevb.api`, error `timeout`) en vez de quedarse colgados.
- Las rutas usan «red primero con 6 s de espera»: si tarda, se muestra la última copia guardada.
- Estilo del mapa: red primero 3,5 s; si no llega, el guardado.

## Detalles técnicos
- Cachés: `spevb-v4.0.0` (app), `spevb-mapa-v1` (paquete fijo, no se poda), `spevb-teselas-v1` (lo visto), `spevb-datos-v1` (rutas).
- `/api/viajes`, `/api/caminata`, direcciones y el editor **nunca** pasan por el SW: lo guarda la página (`js/offline.js`).
- Al perder la red, si el mapa está guardado, se pasa solo al mapa «Moderno» y al volver la señal se regresa al elegido (sin cambiar la preferencia de la persona).
- Se pide `navigator.storage.persist()` para que el navegador no borre el paquete por falta de espacio.
- Las teselas de terceros se guardan solo si la respuesta es legible (CORS); las opacas no se guardan (ocuparían ≈ 7 MB cada una).
- Requiere HTTPS o `localhost` (igual que el GPS y la instalación como app).

## Probar
`SEVB_URL=http://localhost:8080/ python docs/pruebas/offline_e2e.py` (necesita internet para descargar el mapa): descarga el mapa, consulta un viaje, corta la red y verifica app, rutas, mapa, recientes y mensajes.
En Chrome también: DevTools → Network → Offline.
