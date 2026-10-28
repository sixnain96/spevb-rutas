# Viajes con transbordo (dos buses)

Cuando ninguna ruta sirve bien para ir de A a B, SPEVB combina **dos rutas**: camina → bus 1 → camina hasta otra ruta → bus 2 → camina.

## Cuándo se ofrecen
Solo si le ganan a la mejor opción directa:
- no hay ruta directa que sirva, o
- ahorran **5 min o más** (contando la espera del cambio), o
- evitan mucha caminata (la directa obliga a caminar más de 1,5 km y el transbordo menos del 60 % de eso, sin tardar más de 3 min extra).

Si una ruta directa ya es buena, no se muestra ningún transbordo. Si hay transbordos, salen **primero y abiertos**, y se selecciona el mejor.

## Cómo se calcula (`TransbordoService`)
1. Rutas a ≤ 2,5 km de A o de B; se muestrean cada 50 m (más sus paraderos).
2. Con la red de calles se mide la caminata desde A y hasta B a cada punto.
3. Por ruta se calcula, en una pasada, el mejor sitio donde subir para llegar a cada punto y el mejor sitio donde bajar a partir de cada punto (mínimo 120 m en bus, respetando el sentido de cada ruta).
4. Los cambios posibles son pares de puntos de rutas distintas a ≤ 250 m. Total = caminata + bus 1 + caminata del cambio + **espera de 8 min** + bus 2 + caminata.
5. Los 8 mejores pares se afinan con las caminatas reales por calles y los tramos exactos del bus; se muestran hasta 3, con rutas distintas entre sí.

Tiempos: caminar 80 m/min, bus 267 m/min (16 km/h). Los **8 min de espera** son una estimación (no hay horarios) y se avisa en pantalla. Se necesitan **dos pasajes**.

## Requisitos
- La red peatonal (`/api/caminata/estado` = `LISTA`): sin ella no se calculan transbordos (los directos siguen igual).
- Rutas completas, de ida y de vuelta. Con rutas faltantes o mal dibujadas los cambios serán pobres.
- No se calcula más de un transbordo.

## API
`POST /api/viajes/planificar` devuelve además `transbordos`: lista (≤ 3) con `minutos`, `caminataMetros`, `busKm`, `pasajes`, `esperaCambioMinutos`, `primerBus` y `segundoBus` (ruta, puntos de subida y bajada, tramo GeoJSON, km, paraderos), `caminataInicio`, `caminataCambio` y `caminataFin` (trayecto por calles) y `advertencia`.

## Probarlo
`docs/pruebas/transbordo_demo.sql` carga dos rutas de prueba que se cruzan (Avenida Catama y Carrera 18 Este, tramos reales de calles). Origen `4.1432, -73.6020` y destino `4.1548, -73.5834` solo se pueden unir con un transbordo. Prueba automática: `docs/pruebas/transbordo_e2e.py`. **Borra las rutas de prueba al terminar** (el comando está en el SQL).
