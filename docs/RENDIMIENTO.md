# Rendimiento de SEVB: qué se midió, qué se cambió y qué falta

Fecha de la medición: 5 y 6 de noviembre de 2026. Equipo de desarrollo (Windows 11, GPU NVIDIA), PostgreSQL/PostGIS 17 en Docker, 13 rutas (255 km, ≈ 17 000 muestras
cada 15 m), red peatonal de 85 862 nodos. **Todas las cifras son medidas reales de esa máquina**; en otro equipo cambian los milisegundos, no las proporciones.

## Reglas con las que se trabajó
- La base real del dueño **no se tocó**: las mediciones se hicieron contra una copia desechable (`spevb_perf`, creada con `pg_dump` de solo lectura).
- Ningún resultado de viaje cambió: la respuesta COMPLETA de `/api/viajes/planificar` (todas las opciones, tramos, puntos de subida y bajada, advertencias) de 80 pares
  A→B al azar es **idéntica** antes y después (`e2e/rendimiento/comparar_resultados.py`).
- Sin dependencias nuevas, sin Redis, sin cambios de API, sin tocar migraciones ni índices.

## 1. Diagnóstico (cómo se midió)
| Qué | Herramienta |
|---|---|
| Latencia de `POST /api/viajes/planificar` en frío (cada par es nuevo: la caché de 90 s no ayuda) y repetida | `e2e/rendimiento/medir_viajes.py` |
| Dónde se va el CPU del servidor | Java Flight Recorder (`jcmd <pid> JFR.start settings=profile …`) y `e2e/rendimiento/perfil_jfr.py` |
| Plan y tiempo de las consultas SQL | `EXPLAIN (ANALYZE, BUFFERS)` sobre la copia de la base |
| Carga inicial y JS sin usar | `e2e/rendimiento/medir_carga.py` (Chromium, móvil 390×800, CPU ×1 y ×4, sin caché) |
| Animación del bus (cuadros, memoria, DOM) | sondas de Playwright con `requestAnimationFrame` y el perfilador de CDP |

**Trampa de medición que costó una vuelta:** usar `http://localhost:…` desde Python en Windows suma ≈ 2 s a CADA solicitud (prueba IPv6 primero). Medir con `127.0.0.1`.
También: `mvn spring-boot:run` arranca la JVM con `-XX:TieredStopAtLevel=1` (solo C1); con el código ya optimizado eso cuesta ≈ 15 % más (155 ms contra 134 ms), no se cambió.

## 2. Servidor: de ≈ 535 ms a ≈ 121 ms por viaje (mediana; 4,1 veces más rápido)
Un viaje en frío (60 pares nuevos, JVM ya caliente):

| | media | mediana | p90 |
|---|---|---|---|
| Antes | 535 ms | 502 ms | 722 ms |
| Después (pasos 1 a 5) | 134 ms | 132 ms | 188 ms |
| Después, con los tramos agrupados (paso 6) | 115–165 ms (ruidosa) | 121 ms (112–129 en 6 corridas) | 162–307 ms |

El mismo viaje repetido sale de la caché de planes (90 s): ≈ 22 ms, sin cambio.

### Lo que se encontró y se cambió (ordenado por impacto)
1. **`RedPeatonal.ajustarVarios` era el 81,6 % del CPU de un viaje.** Para cada muestra de la ruta (miles por viaje) creaba un objeto por cada calle del
   vecindario, las metía en un `HashSet` y las ordenaba. Ahora una pasada guarda solo números (calle y distancia²) y la segunda crea únicamente las calles que
   pueden entrar (la más cercana + margen), con el mismo orden estable. Resultado idéntico (`RedPeatonalEquivalenciaTest`: 4 900 puntos × 7 configuraciones contra una copia
   literal del algoritmo anterior). Muestras de CPU de JFR para los mismos 60 viajes: 1 107 → 194.
2. **Caché de las calles de las muestras** (`ajustarVariosGuardado`, usada solo por `distanciasDeMuestras`): las muestras de las rutas son siempre las mismas y su calle más
   cercana también. Se recuerda por coordenadas exactas (bits) y parámetros. **Invalidación:** la red peatonal es inmutable; cuando cambia un paso o un puente, o llega una descarga
   nueva, se construye OTRA instancia y su caché nace vacía. Tope de 80 000 entradas (≈ 7 MB en uso normal).
3. **Rejillas de muestras en memoria** (`ViajeRepository.muestras` y `muestrasCompletas`, pasos 15 m y 50 m): la consulta de muestras costaba ≈ 128 ms en PostGIS en CADA viaje
   (decenas de miles de `ST_LineInterpolatePoint` sobre líneas largas). Ahora se calcula una vez con LA MISMA consulta y cada viaje solo filtra por radio. **Vigencia:** una «huella»
   barata (≈ 1,6 ms: versión y fecha de edición de las rutas, cantidad y posición de los paraderos) se compara en cada uso; también se vacía en `ViajeService.invalidarCache()`
   (edición de paraderos y pasos) y se recarga sola a los 10 minutos por si alguien edita la base a mano sin tocar esos campos. El filtro de radio usa el plano tangente del elipsoide
   WGS84 y difiere de la distancia geodésica de PostGIS **menos de 1 mm** (medido en `MuestrasPostgisTest` contra `ST_Distance`/`ST_DWithin` reales).
4. **Dijkstra con cola de arreglos** (`RedPeatonal.costosDesde`): `PriorityQueue` de objetos con comparador era el 25 % del CPU restante. Mismo algoritmo, mismas distancias
   (`RedPeatonalEquivalenciaTest` compara contra la versión con `PriorityQueue`, incluidos los `NaN`). La ganancia medida en tiempo real es pequeña (≈ 5 %), pero reduce basura y CPU bajo carga.
5. **Página principal:** se quitó el `preconnect` a `server.arcgisonline.com` (ArcGIS solo es respaldo del mapa; en una carga normal solo se contacta `tiles.openfreemap.org`, 12 solicitudes,
   y ninguna a ArcGIS): se abría una conexión TLS que no se usaba. Queda el `dns-prefetch`.

6. **Tramos del bus en una sola consulta** (`ViajeRepository.tramosEntre`): un viaje pedía ≈ 20 tramos uno por uno (uno por ruta y uno por variante «más rápida», cada uno con su
   ida y vuelta a la base). Ahora `ViajeService` pide los de todas las rutas juntos y los de las variantes en otra consulta, y `TransbordoService` los dos buses de cada transbordo en una.
   Misma expresión SQL por fila (`ST_LineSubstring`, `ST_AsGeoJSON`, `ST_Length`), así que `MuestrasPostgisTest` compara 160 pedidos al azar (ida, regreso, circulares, tramos de largo 0) con
   `tramoEntre` uno por uno y son idénticos. Medido con JFR: el tiempo esperando a la base por tramos bajó de ≈ 25 ms a ≈ 14 ms por viaje (el resto es geometría, no viajes de ida y vuelta);
   la mediana de un viaje, de ≈ 129 ms a ≈ 121 ms (−6 %, menos del 15 % que se esperaba). Si una ruta desaparece a mitad de viaje, su tramo llega `null` y esa ruta se muestra sin refinar
   (antes `tramoEntre` lanzaba una excepción).

### Lo que se evaluó y NO se tocó (con la razón)
- **Compartir el campo de distancias de Dijkstra entre `AbordajeService.optimizar` y `TransbordoService`** (ambos exploran desde los mismos A y B): ≈ 20–30 % del CPU que queda, pero exige guardar un campo de
  85 000 valores por origen; riesgo medio, medir antes.
- **`RedPeatonal.camino` (A\*) con `HashMap<Integer,…>`** y `AbordajeService.mejorDeRuta` (doble bucle por ruta): ≈ 29 % y 9 % del CPU que queda; cambiar la heurística o el orden podría cambiar empates entre caminos de igual costo.
- **Índices:** `rutasCerca` y `planificar` ya usan los índices GiST existentes (≈ 2 ms cada una); no se añadió ninguno ni se borró ninguno.
- **Caché de planes (90 s) y `LimiteGeneralFilter`:** correctos como están; el límite de 400 solicitudes por minuto por IP sí frena a un medidor rápido: para medir se apagó solo en la instancia de prueba (`SPEVB_LIMITE_HABILITADO=false`).

## 3. Navegador (carga inicial, mapa, animación)
Medido en Chromium móvil (390×800) sin caché, con gzip:

| | CPU ×1 | CPU ×4 (gama media) |
|---|---|---|
| Primer contenido (FCP = LCP) | 204 ms | 508 ms |
| `DOMContentLoaded` / carga | 253 / 362 ms | 1 038 / 1 332 ms |
| Solicitudes / bytes por la red | 55 / 660 KB | 79 / 660 KB |
| Tareas largas (> 50 ms) | 1 (68 ms) | 9 (1 241 ms en total) |
| Salto de diseño (CLS) | 0,0001 | 0,02 |

- **El peso es el motor del mapa vectorial** (MapLibre: 249 KB + 125 KB del worker por la red, 63 % de su JS sin ejecutar al cargar y ≈ 876 ms de CPU con el procesador ×4). Es el mapa por defecto y ya se descarga
  después de pintar la pantalla; no hay una forma segura de quitárselo sin cambiar lo que ve la gente.
- **Cargar módulos secundarios «perezosamente» (guía, logros, siempre, atajos, siguiente…) NO se hizo:** con el perfilador, cada uno cuesta menos de 5 ms (≈ 10 ms todos juntos en un equipo normal);
  diferirlos retrasaría tarjetas que se ven al abrir (viaje de siempre, atajos) y movería el diseño por una ganancia que no se puede verificar.
- Lo más caro de JS propio al cargar: `bus.js` (≈ 10 ms reales al armar el bus 3D) y `sheet.js` (≈ 10 ms por lecturas de tamaño). Pequeño; no se tocó.
- **Animación del bus:** con 8 s de recorrido, `bus.js` gasta 27 ms de CPU en total (62 ms con CPU ×4), sin crecimiento de nodos DOM (860 → 860) ni de memoria (el montón sube y baja con el recolector).
  No hay nada que arreglar con evidencia.

## 4. PWA y `sw.js` (revisión, sin cambios)
La estrategia ya es la adecuada y no se cambió para no mezclar versiones: `/vendor/` primero de la copia guardada (con caché HTTP de 30 días), HTML/CSS/JS propios red primero con 2,5 s de espera y copia
guardada, datos de rutas red primero con 6 s, teselas primero de lo guardado, claves de caché sin parámetros (no se guardan coordenadas) y `/api/viajes`, `/api/caminata` y direcciones nunca se interceptan
(no se guardan datos personales). La caché lleva la versión del proyecto, así que una versión nueva reinstala todo el conjunto a la vez.

## 5. Cómo repetir las mediciones
```powershell
python e2e/rendimiento/medir_viajes.py http://127.0.0.1:8080 60 7
python e2e/rendimiento/comparar_resultados.py guardar antes.json 80 123 http://127.0.0.1:8080   # con el código anterior
python e2e/rendimiento/comparar_resultados.py comparar antes.json 80 123 http://127.0.0.1:8080  # con el código nuevo: debe decir IDÉNTICOS
python e2e/rendimiento/medir_carga.py http://127.0.0.1:8080/ 4 5
```
Para medir sin estorbos: una copia de la base (`pg_dump` de solo lectura a otra base), `SPEVB_LIMITE_HABILITADO=false` y la caché de planes (90 s) en frío usando pares nuevos.
