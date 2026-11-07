# Editor de recorridos y puentes · SEVB 4.13.2

El editor (`/editor.html`, `/puentes.html`) es de administración: exige `EDITOR_ENABLED=true` + `EDITOR_TOKEN` (o sesión de administrador). **Nada se guarda solo**: todo ajuste, propuesta o auditoría es un cambio *pendiente* con Deshacer, «validada» se desmarca y solo «Guardar recorrido» / «Guardar cambios del puente» escribe en la base. Las rutas de miles de puntos conservan historial, borradores locales y control de versión (409 si otra persona guardó antes).

## 1. Seleccionar y editar vértices (hasta 10.000 puntos)
- **Clic sobre la línea** elige el vértice más cercano; si el clic cae entre dos vértices elige el *segmento* (para insertar). La búsqueda recorre los segmentos en memoria y mide en píxeles **solo al hacer clic**, nunca al mover el ratón.
- **Doble clic** sobre un segmento (o «Insertar en segmento elegido» / tecla `I`) inserta un vértice en el punto de la línea más cercano. Se distingue del clic simple con una espera de 260 ms.
- «Ir al vértice número» y los botones ⏮ −10 ‹ › +10 ⏭ (atajos `Inicio`, `Mayús+[`, `[`, `]`, `Mayús+]`, `Fin`; `Supr` elimina, `I` inserta, `Ctrl+Z` deshace). Los atajos **no actúan mientras se escribe** en un campo.
- Panel: «Vértice 52 de 1.500», latitud/longitud, distancia al anterior y al siguiente y distancia a la vía transitable más cercana (si hay clave y red vial lista).
- El vértice elegido se centra, se dibuja grande y se arrastra; con más de 500 puntos solo hay marcadores para extremos, vértice elegido y 5 vecinos a cada lado; el resto son hasta 300 puntos simplificados por zoom (Douglas-Peucker en píxeles, sin eventos). **Nunca se crean miles de marcadores.**
- Ratón, táctil y lápiz (probado con toque y lápiz reales por el protocolo del navegador).

## 2. Ajuste manual de un vértice a una vía para buses
«Ajustar este vértice a una vía para buses» busca hasta **3 candidatos** dentro del límite (por defecto 30 m, configurable de 5 a 100 m en pantalla), muestra la distancia de cada uno, dibuja la previsualización y espera confirmación. Si la vía más cercana está más lejos que el límite **no ajusta**: avisa y conserva el punto. Aplicar es un cambio pendiente con Deshacer.

## 3. Red vial para buses (`co.edu.usta.spevb.vial`) — separada de la peatonal
`RedVialBus` / `RedVialBusService` / `MapMatchingBus` **no reutilizan** `RedPeatonalService` ni su caché: tienen su propio grafo y su archivo (`data/red-vial-bus.json.gz`, variable `RED_VIAL_ARCHIVO`). Mensaje fijo: *«Vía transitable según la red vial disponible. Requiere revisión del equipo»* (jamás «ruta oficialmente autorizada»).

**Etiquetas OSM aceptadas** (`ReglasBus`): `highway` = motorway, trunk, primary, secondary, tertiary (y sus `_link`), unclassified, residential, living_street, road, service (si no es privada), busway, bus_guideway, y `track` solo con `bus=yes/designated`. Un acceso explícito para buses (`bus` o `psv` = yes/designated/official/permit) **prevalece** sobre `access=no`, `motor_vehicle=no` o `vehicle=no`.
**Rechazadas siempre**: footway, path, pedestrian, steps, cycleway, corridor, bridleway, platform, elevator, proposed, construction, raceway, puentes solo peatonales; `access`/`motor_vehicle`/`vehicle` = no|private sin permiso para bus; `bus=no`/`psv=no`; vías en construcción o privadas.

**Sentidos únicos**: `oneway=yes|true|1` (solo adelante), `oneway=-1|reverse` (solo atrás), `junction=roundabout|circular` implica un sentido; `oneway:bus` y `oneway:psv` tienen precedencia (`no` permite el contraflujo de un carril bus). El grafo es dirigido: el ajuste nunca recorre un sentido único al revés.

**Calzadas paralelas y separadores**: el ajuste es un HMM/Viterbi sobre el grafo: la continuidad del recorrido pesa más que la cercanía de un punto aislado (se prefiere seguir en la misma calzada con ruido de 8–10 m) y las transiciones solo existen por caminos legales dirigidos, así que no se salta sobre separadores ni entre vías no conectadas. Se penalizan giros en U y retrocesos artificiales, se detectan puntos atípicos, y los tramos sin vía se **conservan tal cual** (nunca una línea recta inventada).

**Proveedor externo opcional** (`BUS_MATCHING_URL`, vacío = apagado): cliente OSRM `match` con timeout, tope de respuesta, circuit breaker y fragmentos con solapamiento; no envía nunca el `EDITOR_TOKEN`; si falla se conserva la geometría. Está implementado y probado con un servidor local, pero **no está conectado** al flujo del editor por defecto (el ajuste usa la red propia).

## 4. Ajustar una ruta completa (con comparación)
«Analizar y ajustar recorrido a las vías» **no toca `points`**: pide una propuesta (`POST /api/editor/vial/ajustar-ruta`) y la dibuja junto al original (naranja discontinua = original, verde = propuesta, amarillo = dudoso, rojo = sin vía). Resumen: puntos, longitud y diferencia %, desviación promedio/máxima, % ajustado, segmentos dudosos, puntos sin coincidencia, cambios de vía ambiguos, posibles giros ilegales. Acciones: aceptar todo, rechazar todo, revisar el siguiente problema, aceptar un tramo, conservar el original de un tramo y «aplicar tramos aceptados». Si la longitud cambia más de **15 %** o hay problemas hay que marcar «Revisé la comparación». Los **vértices bloqueados (🔒)** son anclas que no se mueven; inicio y final se conservan salvo confirmación explícita. Antes de aceptar se valida: dentro de Villavicencio, 2–10.000 puntos, sin duplicados consecutivos, sin NaN, sin saltos > 2 km. «Validada» nunca pasa a verdadera sola.

## 5. Auditoría de calidad de recorridos (solo lectura)
«Revisar calidad de recorridos» (`GET /api/editor/calidad`) lista todas las rutas activas con puntos, longitud, % sobre vías, desviación promedio/máxima, segmentos sospechosos, sentido posiblemente incorrecto y estado **correcta / requiere revisión / crítica**; permite abrir la ruta y saltar a su primer segmento problemático. No hay ajustes masivos ni guardado conjunto: cada ruta se revisa y aprueba por separado.

## 6. Metadatos y código de la ruta
Se editan código (p. ej. `RX009`), nombre, inicio, destino, sentido, descripción, color, fuente, nota, activa y validada. El **id nunca cambia**. Cambiar el código: formato `[A-Za-z0-9_-]{2,20}`, único (ni código ni alias de otra ruta), confirmación explícita mostrando anterior y nuevo, versión archivada, control optimista con `version` (409) y el código anterior queda como **alias** (`ruta_aliases`, migración V9): `GET /api/rutas/codigo/{codigo}` resuelve códigos vigentes y anteriores; al abrir la página principal los favoritos con código antiguo se actualizan al vigente. Los reportes históricos conservan el código con el que se crearon. Los paraderos usan `ruta_id` y no se ven afectados.

## 7. Paraderos tras corregir una geometría
Al cambiar la línea, el editor recalcula la distancia de cada paradero a la línea guardada y a la actual: informa «12 m → 48 m», **no mueve ni borra nada solo** y ofrece «Ajustar» por paradero (o los lejanos a la vez). Conserva id, nombre y nota; el cambio se confirma con «Guardar paraderos».

## 8. Puentes (peatonales)
En `/puentes.html` cada puente tiene «Editar»: cambiar nombre y nota, mover/insertar/eliminar vértices, ajustar los dos extremos a la red **peatonal** (no a la vial de buses), deshacer/rehacer, cancelar sin guardar y ver la geometría original (gris punteada) frente a la nueva. Se guarda con `PUT /api/editor/pasos/{id}` (migración V10: `version`, 409 si otra persona lo cambió), conserva el `id`, reconstruye la red peatonal e invalida las cachés de caminata y de viajes. La nota solo existe en el endpoint administrativo: `GET /api/puentes` sigue devolviendo únicamente `id`, `nombre`, `metros` y `trazo`.

## Endpoints nuevos
`GET /api/rutas/codigo/{codigo}` (público) · `PUT /api/editor/pasos/{id}` · `GET /api/editor/vial/estado` · `POST /api/editor/vial/candidatos` · `POST /api/editor/vial/ajustar-ruta` · `GET /api/editor/calidad` · `GET /api/editor/rutas/{id}/calidad`. `PUT /api/editor/rutas/{id}/recorrido` acepta además `codigo`, `activa` y `confirmarCambioCodigo` (opcionales: los clientes anteriores siguen funcionando).

## Limitaciones
Los umbrales de «dudoso» y de clasificación no están calibrados con recorridos reales; las pruebas usan redes sintéticas. La descarga de vías desde Overpass no se probó contra un servidor real. No se modelan restricciones de giro (relaciones OSM), horarios ni límites de peso. La auditoría y el ajuste dependen de que la red vial esté lista (si no, responden 503 y se conserva la geometría).

## Nombre popular de la ruta (4.14)

Además del código (`R008`) y del nombre descriptivo (`PORFIA-TERMINAL`), cada ruta puede tener un **nombre popular**: el que la gente usa y el que va en grande en el cartel del bus («Catama», «Kirpas», «la 876»). En el editor está justo debajo de «Nombre de la ruta» (hasta 60 caracteres). Al guardar la ruta se guarda también el nombre popular (solo si cambió).

- La aplicación lo usa en lugar del código en los resultados, el mapa, el modo viaje, WhatsApp y el buscador de rutas: «Súbete a la ruta Catama». Si la ruta no tiene nombre popular se sigue viendo el código.
- Vacío = sin nombre popular (se borra). Cambiar el código de la ruta no pierde el nombre popular (es un dato de la propia ruta).
- API: `GET /api/rutas/nombres` (público, código → nombre) y `PUT /api/editor/rutas/{id}/nombre-popular` con `{"nombrePopular": "Catama"}` (editor, con token).
- Base de datos: columna `rutas.nombre_popular` (migración `V11`, aditiva).

