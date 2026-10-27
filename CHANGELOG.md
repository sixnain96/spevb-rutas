# Cambios — Transbordos y mapa estable en celular

- **Viajes con un transbordo (dos buses)** cuando no hay ruta directa buena o ahorran tiempo o caminata (ver `docs/TRANSBORDOS.md`). Camina → bus 1 → camina → bus 2 → camina, con 2 pasajes y una espera estimada de 8 min en el cambio. Se dibujan el tramo 1 (rojo), el tramo 2 (azul) y los tres tramos a pie por calles. Los viajes directos no cambian.
- **Corregido el mapa «buggueado» en celular al pulsar «Buscar rutas»:** el mapa se encuadraba dos veces seguidas (con animaciones que se pisaban). Ahora hay un solo encuadre (`Spevb.fitMap`), que cancela el anterior, cuenta el espacio realmente libre (panel inferior, botones, teclado) y se repite solo si el tamaño de la pantalla cambia justo después (teclado que se cierra, barra del navegador).
- Se cierra el teclado al elegir un punto o pulsar «Buscar rutas»; el panel se mide con el área realmente visible (`visualViewport`).
- **Celular de lado (apaisado):** usa el diseño de escritorio (tarjeta a la izquierda, mapa a toda la altura). Reproductor del bus compacto y sin solaparse en pantallas pequeñas.
- Pruebas: `docs/pruebas/transbordo_e2e.py` y `transbordo_demo.sql`.

---

# Cambios — Líneas punteadas animadas y mejor experiencia en celular

- **Líneas punteadas de caminata con movimiento muy sutil** (unos 7 px/s), en el sentido del trayecto: del origen al bus y del bus al destino. Se dibujan en SVG sobre un panel propio. La línea recta de respaldo (naranja) no se anima y «reducir movimiento» del sistema las deja quietas. El trazado por calles no cambió.
- **Reproductor del bus en celular:** compacto (de 111 a 75 px de alto), con la barra de avance bien dimensionada (heredaba el relleno y el alto de 44 px de los campos de texto) y sin tapar los botones flotantes.
- **Vista del mapa:** al fijar A y B se ajusta la vista para ver los dos; se deja espacio a la derecha para que los botones flotantes no tapen el pin B.
- Asa del panel y títulos de grupo con área de toque más cómoda.
- Pruebas: `docs/pruebas/lineas_e2e.py`.

---

# Cambios — Nuevo modelo del bus (solo aspecto)

- **Modelo 2D (ahora el predeterminado):** vista cenital de una buseta blanca de Villavicencio dibujada en SVG: trompa corta con faros y rejilla, parabrisas con letrero, tapas de ventilación, luces ámbar en el techo, espejos, ruedas y luces traseras. Las franjas laterales toman el color de la ruta.
- **Modelo 3D:** un volumen real hecho con caras CSS: laterales con ventanas corridas y pilares, puerta delantera derecha, frente con parabrisas, letrero, faros, rejilla y placa, trasera con luneta y luces, techo con tapas, ruedas con pasarruedas y espejos.
- El 3D sigue disponible con el botón «3D» y se recuerda en el navegador (clave nueva `spevb-bus-modelo`, por lo que todos empiezan en 2D). No cambia ninguna función: reproductor, estela, velocidad, rumbo, inclinación en curvas y seguimiento son los mismos.

---

# Cambios — GPS (computador y celular)

- Nuevo módulo `js/gps.js`: lectura de buena calidad (alta precisión, espera a que converja, descarta lecturas viejas), seguimiento en vivo con filtro de Kalman y filtro de saltos, calidad de la lectura, comprobación de permiso/HTTPS/ciudad y mensajes con ayuda según el dispositivo.
- Botón flotante «Seguir mi ubicación» (punto azul con círculo de precisión y flecha de rumbo); «Usar mi ubicación» muestra el avance de precisión y no usa lecturas de más de ±1 km.
- Corregido: en celulares los botones flotantes del mapa quedaban tapados por el panel inferior (`--sheet-visible` se redefinía en `body`).
- Corregido en celulares reales: «Elegir en el mapa» no registraba toques (Leaflet descarta como arrastre cualquier toque donde el dedo se mueve más de 3 px). Ahora se detecta el toque directamente y la tolerancia de Leaflet es de 14 px.
- **Elegir punto en el celular/tableta con pin central**: se mueve el mapa hasta dejar el pin donde se quiere y se pulsa «Confirmar» (no depende de acertar un toque). El toque directo sigue funcionando. La búsqueda del nombre del punto se abandona a los 8 s y se usan las coordenadas.
- El GPS escucha a la vez GPS y ubicación por red: aparece una primera lectura aproximada en segundos y se afina después.
- Avisos flotantes sobre el mapa (`Spevb.toast`) para errores, progreso del GPS y «Toca el mapa» (con botón Cancelar): la línea de estado queda al fondo del panel y podía estar tapada.
- Ver `docs/GPS.md` (incluye por qué el GPS necesita HTTPS en el celular) y `docs/pruebas/gps_e2e.py`.

---

# Cambios — Red peatonal propia y punto de subida óptimo

- **Red de calles propia** (`RedPeatonal`, `RedPeatonalService`): al primer arranque se descargan de OpenStreetMap las calles, andenes y senderos de Villavicencio (se guardan en `data/red-peatonal.bin.gz`). Los trayectos a pie solo recorren calles; ya no hay líneas rectas sobre manzanas ni edificios. OSRM queda como respaldo. Estado: `GET /api/caminata/estado`; actualización: `POST /api/editor/red-peatonal/actualizar`.
- **Subida y bajada óptimas:** cada ruta cercana se muestrea cada 15 m, se mide la caminata real por calles desde A y hasta B a cada muestra y se elige el par que menos hace caminar (sentido de la ruta respetado; descuento de 25 m si el punto es un paradero registrado). Antes solo se probaban 2 puntos por ruta elegidos en línea recta.
- Un punto en medio de una manzana prueba a la vez las 4 calles más cercanas en vez de pegarse a una sola.
- Sin trazado por calles, la línea de respaldo sale naranja y avisa que no es un camino real.
- **Semilla de calles reales incluida** (casco urbano, ≈ 37.000 nodos): la red funciona desde el primer arranque sin esperar a Overpass.
- **Variante «más rápida»** por ruta cuando ahorra 2 min o más; «Rutas cerca del origen» usa el mismo método exacto.
- **Pasos peatonales propios** (`/pasos.html`): pasos que faltan y bloqueos, guardados en PostgreSQL y aplicados a la red al instante. Ver `docs/RED-PEATONAL.md`.
- Arreglado `DireccionesHibridasServiceTest` (esperaba un patrón de búsqueda antiguo).

---

# Cambios V3.9 — Bus 3D, tramo rojo, móvil y algoritmo A→B

- **Bus en 3D** hecho con capas CSS (sin librerías): carrocería con franja de ventanas, techo con parabrisas y aire acondicionado, ruedas, faros con haz de luz y sombra. Gira con el rumbo, **se inclina en las curvas**, rebota suavemente y crece con el zoom. Botón «3D» para alternar con la vista plana (se recuerda; por defecto plana en equipos modestos).
- **Tramo del viaje en rojo con borde blanco** (antes naranja, que se confundía con las calles del mapa base).
- **Algoritmo A→B más preciso y rápido:**
  - Los **paraderos registrados** son candidatos de subida/bajada y se prefieren si no obligan a caminar más de 40 m extra; el resultado muestra «paradero X».
  - **Ensanchado ante barreras:** si caminar es más de 1,6 veces la línea recta (cerro, caño, manzana sin paso) se piden más candidatos, más juntos (cada 120 m).
  - **Planes en caché** 90 s y **cubeta de fichas** (3 consultas/s) hacia el servicio peatonal en lugar de una espera fija.
  - La mejor opción llega con **sus dos tramos a pie ya calculados por calles** (en paralelo): el navegador los dibuja sin más consultas ni el parpadeo de la línea recta.
- **Móvil:** aplicación instalable (manifiesto + iconos) con **service worker** que guarda la app y Leaflet/MapLibre (abre al instante en visitas siguientes y sin conexión la parte estática); en redes 2G/3G, con ahorro de datos o poca memoria se usa el mapa ligero y menos teselas; fotogramas limitados a ≈ 30 en equipos modestos; objetivos táctiles de 44 px; sin retardo de toque ni «tirar para recargar».

---

# Cambios V3.8 — Interfaz estilo app de mapas

- **Rediseño completo de la pantalla principal**, con identidad propia (verde SPEVB, chips de ruta con el color de cada línea, pines con punta): mapa a pantalla completa y, encima, una tarjeta flotante con origen/destino unidos por un conector, botón «Buscar rutas», chip «Solo revisadas» y resultados.
- **Resultados tipo itinerario:** cada ruta muestra su código, el tiempo estimado (caminata a 4,8 km/h + bus a 16 km/h, sin espera), una barra de tramos caminar–bus–caminar y, al abrirla, los pasos «Camina → Ruta → Camina» con la caminata por calles.
- **Móvil:** el panel es una **hoja inferior arrastrable** con tres alturas (asomado, medio, completo). Sube al escribir, baja al elegir un punto en el mapa, queda a media altura al mostrar resultados y el mapa se ajusta al espacio libre. Controles flotantes que suben con la hoja, campos de 16 px (sin zoom automático en iOS), áreas táctiles de 34–46 px y márgenes seguros (`env(safe-area-inset-bottom)`).
- Controles del mapa: zoom abajo a la derecha, selector de capas arriba a la derecha, botones flotantes «Ver recorrido» y «Vista general», etiqueta con la ruta activa y reproductor del bus como píldora inferior.
- Pines A/B con la punta en el punto exacto; inicio/fin de ruta como puntos pequeños; etiquetas de inicio/fin ocultas en móvil para no tapar el mapa.
- Nuevo `js/sheet.js`; estilos en `css/home.css` (sólo afectan a la página principal). Sin cambios en el backend.

---

# Cambios V3.6 — Base propia de direcciones

- **Base de direcciones en PostGIS** (`direcciones`, `barrios`): se importa sola del catastro municipal (≈ 100.000 predios) al primer arranque y se refresca cada 30 días. Endpoint de estado `GET /api/direcciones/estado` y administración `POST /api/editor/direcciones/importar-catastro`.
- **Intérprete de nomenclatura colombiana** (`DireccionParser`): `Carrera 48 #19-15 Sur`, `cra 48 no 19 15`, `Calle 17 Sur #39-03`, formato catastral (`C 17SUR 39 03 MZ B CS 10 URB …`), BIS, letras, esquinas (`Calle 40 con Carrera 25`) y manzana/casa. Interpreta el 96,3 % de los predios del catastro.
- **Búsqueda y autocompletado locales** (10–30 ms, sin cuota ni clave): coincidencia exacta, interpolación entre vecinas, esquinas, barrios y puntos confirmados. ArcGIS y Nominatim pasan a ser respaldo.
- **Aprendizaje:** confirmaciones de usuarios (3 coherentes ≈ 55 m ⇒ primer resultado), popularidad de sugerencias y lista de búsquedas sin resultado. Límite por IP y validaciones.
- Medido con datos reales: predio correcto en los 3 primeros a ≤ 40 m en el 93,7 % de 300 direcciones al azar escritas de 3 maneras; interpolación con mediana ≈ 1 m (p90 6 m). Ver `docs/DIRECCIONES.md`.
- **OpenStreetMap:** ≈ 1.800 lugares con nombre (colegios, universidades, parques, comercios, barrios…) y direcciones con número de puerta, importados de Overpass con varios servidores de respaldo, más una instantánea incluida como semilla. Autocompletado y búsqueda por nombre («Unicentro», «Terminal», «Universidad…»).
- **Administración:** en `/lugares.html`, estado de la base, botones para actualizar catastro/OSM, lista de búsquedas sin resultado para ubicarlas y corrección de direcciones por el equipo (prioridad sobre el catastro).
- Interfaz: las sugerencias locales se ubican sin otra consulta; una única coincidencia exacta se fija directamente; etiquetas de precisión para cada tipo de resultado.

---

# Cambios V3.5 — Caminata por calles y bus animado

- Los tramos a pie (origen → subida y bajada → destino) siguen calles y senderos peatonales de OpenStreetMap (OSRM, perfil peatonal) en lugar de una línea recta. Endpoint nuevo `GET /api/caminata`, con caché y límite de concurrencia. Si el proveedor no responde, se conserva la línea recta de respaldo. Configurable con `WALKING_URL` (vacío = desactivado).
- Bus animado con controles (pausa, velocidad, reinicio, barra de avance, seguir bus).
- Pantalla principal rediseñada como herramienta: panel lateral con scroll propio y mapa a pantalla completa (en móvil, mapa arriba y panel debajo), origen/destino en dos filas compactas con iconos, y «Rutas que puedes tomar» como menú desplegable (acordeón: al abrir una opción se dibuja en el mapa). Barra superior oscura, esquinas rectas y chips de ruta con el color de cada línea. Estilos en `css/home.css`, sólo para la página principal.
- Algoritmo de subida/bajada en dos etapas (de grueso a fino): 8 rutas × 2 candidatos medidos por caminata real y, para las 2 mejores, puntos a ±35/70/110 m del elegido. Tiempos máximos de espera 3 s (grueso) y 2 s (fino). Guía para montar un OSRM propio en `docs/CAMINATA-OSRM.md`.
- Trazo más fiel: las líneas se simplifican menos al dibujar (`smoothFactor` 0,3–0,4) y la caminata suma los tramos cortos hasta la calle; las coordenadas guardadas no se modifican.
- Mejor punto para subir y bajar: en vez de proyectar en línea recta sobre el recorrido (que puede quedar tras un cerro, un caño o una manzana sin paso), se muestrean varios puntos candidatos por ruta (cada ~60 m, uno por cada tramo de 250 m) y se elige el par con menor caminata real por la red peatonal, respetando el sentido de la ruta. Una sola consulta «table» de OSRM por cálculo, con ritmo máximo de ~2,5 llamadas/s, manejo de HTTP 429 y retorno automático a línea recta. Aplica al planificador y a «rutas que pasan cerca del origen». No se modifica ninguna coordenada de las rutas.
- Optimización de la caminata: caché de 12 h con vencimiento, consultas idénticas simultáneas fusionadas en una sola llamada, caché negativa de 45 s y corte de circuito ante fallos repetidos; el navegador reutiliza la respuesta 1 h, precarga al pasar el cursor sobre una opción y reutiliza trayectos al cambiar de opción.
- Optimización de líneas de bus sin alterar coordenadas: líneas sin hit-testing, `smoothFactor` sólo al dibujar, estela en lienzo propio con crecimiento incremental, repintado sólo cuando hay cambios y caché de 30 s del detalle de ruta.
- Mapa vectorial moderno (MapLibre GL dentro de Leaflet, datos OpenStreetMap vía OpenFreeMap, sin clave): estilos «Moderno» (por defecto) y «Minimalista», más «Calles» y «Claro» (Esri) y «OpenStreetMap», con selector. El motor vectorial (≈1,4 MB, caché de 30 días) se descarga sólo al usarlo y, mientras llega o si no hay WebGL, se muestra el mapa claro de respaldo. La elección se recuerda en el navegador.
- Carga más rápida: compresión, caché de Leaflet, preconnect a los tiles.

# Cambios V3.4 — Autocompletado y precisión (9 de octubre de 2026)

- Autocompletado real desde ArcGIS `/suggest` a partir de 3 caracteres, con espera de 450 ms para evitar consultas innecesarias. Nunca utiliza el Nominatim público por tecla.
- La consulta de sugerencias envía el texto parcial sin añadir automáticamente `Villavicencio, Meta, Colombia`; utiliza límites y proximidad para priorizar el área.
- Oculta coincidencias genéricas de ciudad y repeticiones cuando se está buscando un lugar concreto.
- Conserva el filtro por nomenclatura para no afirmar que una calle general es una puerta real. Las fuentes catastrales siguen identificándose como centros de predio aproximados.
- Al seleccionar una sugerencia, se utiliza su `magicKey` para solicitar las coordenadas. Si vuelve un único establecimiento o dirección puntual, el marcador aparece directamente; si hay varias coincidencias, el usuario escoge.
- Nuevas mejoras de teclado (flechas, Enter y Escape) y estados ARIA para sugerencias.
- Conserva mapa Leaflet/OpenStreetMap, editor, rutas, paraderos, PostGIS, selecciones manuales, geolocalización, intercambio A/B y cálculo de viajes sin cambios en el esquema SQL.
- La clave ArcGIS sólo se usa en Java; no se incluye en el ZIP, ni se copia al frontend, ni se almacenan resultados de ArcGIS en la base de datos.
- Pruebas agregadas con respuestas simuladas; consulta también `docs/PRUEBA-V3-4.md` para validación con proveedores reales.

---

## 4.1.0 — Compartir, favoritos, avisos al bajar, reportes y despliegue
- **Compartir viaje** (enlace que abre y busca solo), **Mis lugares** (favoritos), **Avisarme al bajar** (GPS: vibración, sonido y mensaje), **Reportar un problema** (con página de administración `/reportes.html`), botón **Instalar la aplicación**. Ver `docs/EXTRAS.md`.
- **Límite de solicitudes por IP** para publicar en internet (429 con mensaje claro).
- **Despliegue 24/7**: `compose.prod.yml` (Caddy + HTTPS + app + PostGIS sin puerto público), copias de seguridad (`deploy/backup.sh`, `restore.sh`) y `docs/DESPLIEGUE.md`.
- `/api/estado` muestra la versión real; mensajes de error de validación más claros en la lectura del cuerpo JSON.

## 4.0.0 — Uso sin conexión o con señal mala
- Nuevo service worker: la app abre sin internet; rutas guardadas (red primero con límite de espera); mapa guardado/visto.
- Menú «Uso sin conexión»: guardar/actualizar/borrar el mapa de Villavicencio (con progreso y cancelación).
- «Recientes»: los últimos 6 viajes A→B se guardan y se pueden repetir sin conexión (con aviso de resultado guardado).
- `Spevb.api` corta a los 25 s y distingue `offline`/`timeout`; avisos al perder y recuperar la conexión; cambio automático al mapa guardado.
- Solo se añade: ninguna función existente cambia. Ver `docs/OFFLINE.md`.

## V3.2 — Precisión de direcciones

- Corregido el filtro `LIKE` del catastro: tenía comillas duplicadas y ArcGIS REST rechazaba la consulta.
- Si el usuario escribe `#19-05`, Nominatim sólo puede devolver un inmueble con `address.house_number` compatible; calles sueltas no aparecen como su casa.
- ArcGIS profesional descarta candidatos que no respetan el tipo de vía y los números de la consulta.
- La API `/api/lugares` devuelve `avisos` y banderas de configuración, sin exponer credenciales.
- La interfaz informa si la fuente catastral no respondió y si sólo existe una posición aproximada.
- Autocompletado ArcGIS **opcional**: endpoint `/api/lugares/sugerencias` y `magicKey` sin exponer token; se activa sólo al configurar `ARCGIS_API_KEY` en `.env`.

**Pendiente:** probar el catastro en vivo desde Windows (este entorno no tiene salida DNS).

# V3.1 — direcciones de Villavicencio (octubre 2026)
- Consulta a U_TERRENO con centroide o geometría de predio, normalización de abreviaturas y filtros contra coincidencias falsas.
- ArcGIS World Geocoding opcional vía clave de API en servidor; sin almacenamiento automático.
- Campos `precision` y `fuente` para mostrar que algunos marcadores son aproximados.
- La selección manual conserva la coordenada original tras la geocodificación inversa.
- Pruebas locales con respuestas simuladas y documentación de validación de 30 direcciones.
- No se modifica el volumen PostGIS ni el planificador de recorridos.

# Changelog

## V3 (3.0.0)

Cambios frente a la V2 publicada en `main`.

### Planificador de viaje (nuevo)

- Compara el origen A y el destino B con todas las rutas activas, sin límite de distancia: la primera opción es siempre la ruta registrada más cercana.
- Dibuja automáticamente la mejor opción: línea punteada desde A hasta el punto de subida, tramo del bus y línea punteada desde el punto de bajada hasta B.
- Respeta el sentido de circulación: las rutas `IDA` y `REGRESO` se usan en el sentido en que fueron dibujadas; un viaje al revés aparece como alternativa con advertencia. Las rutas `CIRCULAR` pueden dar la vuelta completa.
- «Ver rutas que pasan cerca del origen» devuelve las 8 rutas más cercanas y dibuja la primera.

### Direcciones y lugares (nuevo)

- Búsqueda de direcciones de Villavicencio con Nominatim/OpenStreetMap, acotada al área de la ciudad.
- Normalización de nomenclatura colombiana (`Cra`, `Cll`, `Av`, `Diag`, `Tv`, `No.`, `5 a 50`…) sin alterar palabras completas como `Avenida`, `Norte` o `Clínica`.
- Catálogo local persistente (`lugares`) para registrar puntos que no aparecen en el proveedor.

### Editor de recorridos

- Creación de rutas nuevas, no sólo edición de las DEMO.
- Historial de versiones en `ruta_revisiones` y control de concurrencia (`409 Conflict`).
- Botón «Invertir sentido» para corregir rutas dibujadas al revés.
- Se rechazan geometrías inválidas, fuera de Villavicencio o con más de 10.000 puntos.

### Seguridad y operación

- Operaciones de edición protegidas con `EDITOR_TOKEN`, cabeceras de seguridad y límite de tamaño.
- Las claves se leen de `.env`, que está en `.gitignore`; `docs/CAMBIAR-CLAVES.md` explica cómo reemplazarlas.
- `Dockerfile`, `compose.app.yml`, `backup.ps1`, `restore.ps1`, `start.ps1` y `diagnostico.ps1`.

### Pruebas

- Nueve clases de prueba en `src/test` (la V2 no tenía pruebas automáticas).

### Pendiente

- Trazar sobre las calles las 4 rutas aprobadas (Porfía, Galán, Catama y Álamos); hoy son geometrías DEMO.
- Validación oficial de rutas: pendiente de respuesta de las entidades públicas.
- Transbordos entre rutas y caminos peatonales por la red vial.


## 3.3 — Normalización catastral de Villavicencio
- Reconoce K como CARRERA y C como CALLE en los registros catastrales.
- Divide placa y orientación pegadas (`15SUR` → `15 SUR`).
- Evita falsos positivos cuando un número coincide con manzana o lote, no con placa.
- Nuevos tests de regresión basados en filas reales de U_TERRENO.
- No inventa coordenadas para `Carrera 48 #19-05 Sur` si el predio no está disponible.
