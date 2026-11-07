# Google Maps en SEVB

SEVB usa Google en **dos cosas distintas** que no deben confundirse. Cada una tiene su propia clave, su propio interruptor y su propio coste.

| | **A. Mapa de Google** (nuevo en 4.13) | **B. Direcciones de Google** (ya existía) |
|---|---|---|
| Qué hace | **Dibuja** el mapa del navegador (Maps JavaScript API) | **Sugiere direcciones** y ubica el punto elegido (Places API «New»), desde Java |
| Dónde corre | En el navegador de la persona | En el servidor |
| Interruptor | `MAP_PROVIDER=google` | `GOOGLE_MAPS_API_KEY` (y además exige `MAP_PROVIDER=google`, ver §9) |
| Clave | `GOOGLE_MAPS_BROWSER_API_KEY` (**pública**, restringida por sitio web) + `GOOGLE_MAPS_MAP_ID` | `GOOGLE_MAPS_API_KEY` (**secreta**, solo la lee el servidor) |
| Código | `js/mapa/adaptador-google.js` | `GoogleDireccionesService.java` |

Lo que Google **no** hace en SEVB, a propósito: no calcula rutas ni viajes (siguen siendo las rutas propias de PostGIS), no se le envía cada lectura del GPS
(el seguimiento usa la geolocalización del navegador), no usa Routes, Roads, Geocoding ni Geolocation API, y no se almacenan ni se sirven por proxy sus teselas.

Leaflet/MapLibre se conserva como **proveedor anterior** para comparar y revertir. El diseño de la capa está en [MAPA-CAPA.md](MAPA-CAPA.md).

## 1. Activar, probar y revertir (sin tocar la base de datos ni las preferencias)

```
# .env
MAP_PROVIDER=google
GOOGLE_MAPS_BROWSER_API_KEY=<clave de navegador, ver §2>
GOOGLE_MAPS_MAP_ID=<Map ID de tipo JavaScript, ver §2>
# GOOGLE_MAPS_JS_VERSION=quarterly        # predeterminada; ver §6
```

Reinicia la aplicación (`.\start.ps1`, o `docker compose … up -d` con los compose del proyecto, que ya transmiten estas variables). El proveedor se elige **al arrancar
el servidor** y la página lo lee de `GET /api/config/mapa`; no hay selector en la pantalla del pasajero. Para comparar: cambia la variable, reinicia y recarga.

**Revertir:** `MAP_PROVIDER=leaflet` (o borra la línea) y reinicia. No se modifica la base de datos, ni los favoritos, ni los viajes guardados, ni el «mapa guardado» de Leaflet
(se conserva en el navegador y vuelve a servir al regresar). El service worker no necesita limpiarse: el proveedor se lee en cada carga y el worker solo guarda los archivos
del proveedor en uso. Un viaje activo no cambia de proveedor a mitad de camino: el proveedor queda fijado mientras la página vive.

**Si Google falla no se cambia a Leaflet en silencio.** Se muestra un aviso dentro del mapa con el motivo y, cuando sirve de algo, el botón «Reintentar»:

| Situación | Mensaje al pasajero | ¿Reintentar? |
|---|---|---|
| Falta la clave o el Map ID | «aún no está configurado…» | No |
| Clave inválida/expirada | «la clave del mapa no es válida» | No |
| Sitio no autorizado (referrer) | «este sitio no está autorizado…» | No |
| API no habilitada | «el servicio de mapas no está activado…» | No |
| Facturación | «problema de facturación» | No |
| Cuota agotada | «límite de uso, intenta más tarde» | Sí |
| Sin red / SDK bloqueado / lento (20 s) | «no se pudo descargar el mapa…» | Sí |
| Map ID incorrecto | «falta el identificador de mapa…» | No |

Los detalles técnicos (código de Google, directiva de CSP bloqueada) salen en la consola y, con `?mapa-debug=1`, dentro del aviso.
Los códigos se leen de `window.gm_authFailure` y de los mensajes de consola de Google (`InvalidKeyMapError`, `RefererNotAllowedMapError`, …): no existe una API
que entregue el código, por lo que es una detección **de mejor esfuerzo** (verificada con el SDK real solo para `InvalidKeyMapError`).

## 2. Pasos de Google Cloud que debe hacer el propietario

SEVB no activa facturación, no crea claves ni cambia permisos de ninguna cuenta externa. Todo esto lo hace una persona con acceso a la cuenta:

1. **Proyecto.** Crea (o elige) un proyecto de Google Cloud para el *navegador*. Recomendación oficial: separar el uso de cliente y de servidor en proyectos distintos, y una clave por aplicación y por entorno.
2. **Facturación.** Maps JavaScript API exige una cuenta de facturación activa (pide tarjeta).
3. **Habilitar** «Maps JavaScript API». (Para §9 en el proyecto del servidor: «Places API (New)».)
4. **Clave de navegador** (Credenciales → Crear credencial → Clave de API):
   * *Restricción de aplicación:* «Sitios web (referentes HTTP)».
   * Para el **sitio publicado**: `https://tudominio.com/*` y, si usas subdominios, `https://*.tudominio.com/*`.
   * Para **pruebas locales**: `http://localhost/*` (sin puerto cubre cualquier puerto; **no hay un patrón `localhost:*` documentado**, compruébalo en tu consola) y, si pruebas desde el celular por la red local, `http://IP-DE-TU-PC:8080/*` o el dominio del túnel que uses. Para HTTPS usa `https://…`.
   * Usa **claves distintas** para desarrollo, pruebas y producción, cada una con sus propios sitios.
   * *Restricción de API:* solo «Maps JavaScript API». No hace falta Map Tiles ni Places en el navegador.
   * La clave se verá en las solicitudes del SDK: su protección son estas restricciones, no ocultarla.
5. **Map ID** (Map Management → Crear ID de mapa): tipo **JavaScript**, Raster o Vector. Los marcadores avanzados lo exigen. Para una prueba rápida de desarrollo puede usarse `DEMO_MAP_ID` (no sirve para estilos en la nube).
6. **Control de gasto.** Un **presupuesto solo avisa, no corta el gasto.** Para limitar de verdad: Cuotas → baja el límite diario de la API (al agotarse las solicitudes fallan el resto del día). Google propone dividir el umbral gratuito mensual del SKU entre 31. No encontré documentado el nombre exacto de una cuota «cargas de mapa por día»: revisa en tu página de Cuotas qué métrica ofrece tu proyecto. Por defecto la API tiene 30 000 cargas/min por proyecto y 300/min por IP.

## 3. Qué APIs y consumo introduce (consultado el 6-nov-2026; los precios cambian: revísalos antes de decidir)

* **Maps JavaScript API → «Dynamic Maps» (Essentials).** Se factura la *carga de mapa exitosa* (con o sin Map ID). Umbral gratuito: **10 000 cargas al mes**; luego ≈ 7,00 USD por 1 000 hasta 100 000 (precio escalonado a la baja). Fuente: <https://developers.google.com/maps/billing-and-pricing/pricing>.
* Que cada creación de mapa equivalga a una carga y que recentrar, hacer zoom, marcadores o polilíneas **no** facturen aparte es una **inferencia** (la documentación de la API JS no lo dice con esas palabras): mídelo en tu consola de facturación durante la prueba.
* SEVB crea **una sola instancia** de `Map` por pantalla y la reutiliza al actualizar el GPS, cambiar tarjetas, elegir otra ruta, abrir paneles o recentrar (probado con el SDK simulado). Recargar la página sí es una carga nueva.
* **Places (§9)** es otro producto con otro precio (Autocomplete por petición, Place Details por SKU de campos). No lo activa el mapa.
* Bibliotecas que se importan: `maps`, `marker`, `core`. Nada de `places`, `routes`, `geocoding`, `geometry` ni `drawing`.

## 4. Documentación oficial consultada y decisiones

| Tema | Decisión en SEVB |
|---|---|
| Carga | Etiqueta `script` con `loading=async` + `callback` y luego `importLibrary` (patrón oficial); una vez por documento, `language=es`, `region=CO`. Sin script en línea (compatible con la CSP). |
| Marcadores | `AdvancedMarkerElement` con contenido DOM propio (`append`), eventos `gmp-click` / `gmp-dragstart` / `gmp-dragend` (`click`/`addListener` están deprecados para marcadores). `google.maps.Marker` está **deprecado** (no retirado). |
| Puntos de radio en píxeles | Google no tiene círculos en píxeles (`Circle` usa metros): se usan marcadores avanzados con un círculo CSS. Con cientos de puntos conviene medir en el teléfono objetivo (no hay una cifra oficial de marcadores avanzados). |
| Líneas punteadas | `Polyline` con `icons` repetidos (puntos/guiones). No hay animación CSS de la caminata (diferencia: el punteado queda estático). |
| Editor | **No** se usa DrawingManager (retirado: la Drawing Library ya no existe en la versión actual). Se mantiene el modelo propio del editor con marcadores arrastrables y polilíneas no editables por Google. Terra Draw es la alternativa de terceros que Google menciona; no hizo falta. |
| Proyecciones | `OverlayView` + `MapCanvasProjection`; la capa espera al primer dibujo antes de devolver coordenadas en píxeles (`toPoint` devuelve `null` antes). |
| Encuadre | `fitBounds` de Google no admite `maxZoom`: la capa calcula centro y zoom (Mercator) con los márgenes del panel y la tarjeta. |
| Modo noche | `colorScheme` (LIGHT/DARK) **solo al crear** el mapa; no cambia después sin recargar (limitación de Google). El filtro CSS de Leaflet no aplica. |
| Mapa sobre el panel | En celular el contenedor del mapa termina justo encima del panel inferior, para que el **logotipo y los créditos de Google** nunca queden tapados. Nunca se ocultan ni se modifican. |

## 5. Modo viaje y GPS

El seguimiento sigue usando `navigator.geolocation`; cambiar de mapa no requiere la Geolocation API de Google. `viaje-nucleo.js` no sabe nada del SDK. El marcador de «mi posición»
se mueve con `setPosition`/`setRadius` sobre el mismo elemento (no se reconstruye el mapa por lectura). «Seguirme» y el seguimiento del bus centran en el **área libre** (no debajo de la tarjeta ni del panel).
Un recentrado automático no cancela el seguimiento (`dragstart` solo lo provoca la persona). El GPS del teléfono **no** dice dónde están los buses de la flota; el bus animado sigue siendo una demostración.
Google Maps JavaScript API no vuelve nativa a la PWA ni garantiza seguimiento con la pantalla bloqueada.

## 6. Versión del SDK (política)

`GOOGLE_MAPS_JS_VERSION` (predeterminada **`quarterly`**: no recibe mejoras durante el trimestre, es la más predecible; `weekly` es el canal por defecto de Google; también admite una numerada como `3.66`).
Google actualiza los canales a mediados de febrero, mayo, agosto y noviembre; las versiones numeradas «se acaban eliminando» y cada versión puede retirar funciones deprecadas.
**Cómo actualizar:** cada trimestre revisa las notas de versión (<https://developers.google.com/maps/documentation/javascript/releases>), prueba el procedimiento de §10 con la nueva versión en un entorno de pruebas
(`GOOGLE_MAPS_JS_VERSION=weekly` o la numerada siguiente) y recién entonces úsala en producción. Si una actualización rompe algo, fija temporalmente la versión anterior (Google indica que suele dar 3 a 6 meses).

## 7. Content Security Policy

La CSP la fija el servidor según el proveedor (`ContentSecurityPolicy.java`; el servidor simulado de pruebas usa los mismos textos de `src/test/resources/csp/`):

* **Leaflet:** como antes (teselas de OpenFreeMap, Esri y OpenStreetMap). Sin dominios del SDK de Google (solo el botón de inicio de sesión).
* **Google:** se **quitan** los hosts de Leaflet/OpenFreeMap/Esri/OSM y se añaden los de la política oficial por lista de dominios (`*.googleapis.com`, `*.gstatic.com`, `*.google.com`, `*.ggpht.com`, `*.googleusercontent.com`, `fonts.googleapis.com`, `fonts.gstatic.com`, `blob:`). Sin `'unsafe-inline'` en `script-src` (SEVB no tiene scripts en línea).
* **`'unsafe-eval'` en `script-src` (solo en modo Google):** la política oficial de Google lo incluye y no explica el motivo. Está **solo** en modo Google. Si con una clave real el SDK funciona sin él, quítalo: es un permiso amplio que se añadió por seguir la guía oficial, no porque se haya comprobado que sea imprescindible.
* No se usan nonces (SEVB no tiene scripts en línea y el HTML se entrega estático y cacheado; un nonce fijo no sirve).
* Verificado con el SDK **real** (clave inválida): el script se carga sin violaciones de CSP. **No verificado** con una clave real: el comportamiento completo de teselas, fuentes y trabajadores del mapa.

## 8. PWA y mapa sin conexión

* En modo Google **no existe «Guardar mapa»** y el menú «Uso sin conexión» lo explica: los términos de Google prohíben almacenar sus teselas o su contenido fuera del uso normal (cláusula 3.2.3), por lo que SEVB no cachea los dominios de Google ni los sirve por proxy, y deja que el SDK gestione sus recursos.
* Sí siguen sin internet: el código propio (caché versionada), los datos propios de rutas, el estado del viaje y la reanudación, el GPS y los avisos. Solo el dibujo del mapa necesita conexión. Perder internet no destruye el plan ni el viaje.
* Si el SDK nunca llegó a cargarse, se muestra el aviso «No se pudo descargar el mapa» y el resto de la interfaz textual sigue disponible.
* El service worker subió la versión de su caché de app (`-mapa2`): al activarse borra solo las cachés de app anteriores (no el mapa guardado, teselas, rutas ni favoritos), así ningún worker viejo sirve scripts incompatibles.

## 9. Direcciones de Google (Places) y atribución

* **Regla nueva:** las sugerencias de Google **solo se usan cuando el mapa es de Google** (`MAP_PROVIDER=google` y `GOOGLE_MAPS_API_KEY` definida). Con Leaflet quedan **apagadas**, aunque haya clave: los términos de Google piden mostrar los resultados de Places sobre un mapa de Google y no mezclarlos con mapas de otros proveedores (cláusula 3.2.3(e) y las específicas de Places).
* **Prioridad y facturación (4.13.2):** con el mapa de Google, las sugerencias de Google son el **primer** proveedor (ArcGIS y el índice local son respaldo; ver [DIRECCIONES.md](DIRECCIONES.md)). **El autocompletado de Google se cobra** (no es gratuito, como decía un comentario anterior): con token de sesión por búsqueda, las 12 primeras solicitudes se facturan como «Autocomplete Requests», las siguientes no tienen cargo y el detalle que cierra la sesión se factura por el nivel más alto de los campos pedidos; SEVB pide solo campos Essentials en ese detalle. Comprueba precios vigentes en <https://developers.google.com/maps/billing-and-pricing/pricing> y las reglas de sesión en <https://developers.google.com/maps/documentation/places/web-service/session-pricing>.
* La búsqueda propia (base local, OpenStreetMap, ArcGIS opcional) no cambia. El botón «Google Maps» (enlace público) no cambia.
* Cada resultado dice su origen y nunca se marca como verificado. **No se guarda nada de Google en PostgreSQL.**
* **Pendiente de decisión del propietario (condiciones de Google):** si la persona guarda en «Mis lugares» un punto que vino de Google, su latitud/longitud queda en el navegador de esa persona. Los términos permiten conservar lat/lng de Places hasta 30 días y el `place_id` sin límite. Hoy el favorito no caduca. Antes de producción consulta los términos vigentes o a un abogado; renombrar un resultado de Google no lo convierte en contenido propio.
* Origen de los datos: las **rutas propias** (PostGIS) y los datos abiertos (OpenStreetMap, catastro) tienen sus propias condiciones y atribuciones; el contenido de Google, las suyas. Las atribuciones propias de los datos superpuestos se mantienen donde ya estaban.

## 10. Pruebas: qué está verificado y qué no

| Nivel | Qué | Estado |
|---|---|---|
| Unitarias del adaptador / geometría | `e2e/mapa_adaptadores_e2e.py` (`geo_*`, contrato Leaflet) | Verificado |
| SDK **simulado** (`e2e/harness/google_fake.js`) | carga única, `importLibrary`, conversiones `[lng,lat]`↔`{lat,lng}`, ciclo de vida, errores y reintento, diseño 320–430 px/horizontal/escritorio | Verificado (no prueba Google real) |
| SDK **real**, parcial (`SEVB_E2E_GOOGLE_REAL=1 python e2e/mapa_google_real_e2e.py`) | el SDK carga bajo la CSP, una sola vez, sin Leaflet; clasificación del error real de clave inválida | Verificado |
| SDK real con **clave y Map ID reales** | dibujo del mapa, marcadores avanzados, gestos, proyección, CSP completa, rendimiento | **Pendiente: lo debe hacer el propietario** |
| Teléfonos físicos | gestos táctiles, arrastre de marcadores, «Seguirme», logotipo visible | **Pendiente** |

**Prueba con tu clave** (≈ 15 min): 1) crea la clave de navegador y el Map ID (§2) con `http://localhost/*` autorizado; 2) `MAP_PROVIDER=google`, clave y Map ID en `.env`; reinicia; 3) abre `http://localhost:8080`, comprueba que el mapa se dibuja y que abajo a la izquierda se ven el logotipo de Google y los créditos;
4) elige A y B, busca rutas, arrastra el mapa, inicia un viaje de práctica; 5) abre el editor (`/editor.html`) y mueve un vértice; 6) con la consola abierta confirma que no hay errores de CSP; 7) opcional: `SEVB_E2E_GOOGLE_REAL=1 SEVB_E2E_GOOGLE_KEY=… SEVB_E2E_GOOGLE_MAP_ID=… python e2e/mapa_google_real_e2e.py`; 8) desde el celular, abre la URL de tu PC (autorizada en la clave) o un túnel HTTPS y repite 3–5; 9) revisa en Google Cloud → Facturación que solo aparezca «Dynamic Maps».

## 11. Equivalencias y diferencias inevitables

| Antes (Leaflet) | Ahora con Google | Diferencia |
|---|---|---|
| Capas base y mapa vectorial propio | Mapa de Google (Map ID) | Otro estilo; sin selector de capas |
| «Guardar mapa» | No disponible | Términos de Google |
| Caminata punteada animada (CSS) | Punteada estática | Las polilíneas de Google no se animan por CSS |
| Modo noche por filtro CSS | `colorScheme` al abrir la página | No cambia sin recargar |
| `circleMarker` | Marcador avanzado circular | Con cientos de puntos puede pesar más |
| Tooltips nativos | Tooltip propio (texto) | Igual contenido |
| Controles de zoom propios | Control de zoom de Google (se oculta en celular) | Otro aspecto |
| Créditos «Leaflet / OSM / Esri» | Logotipo y créditos de Google | Obligatorios, siempre visibles |

## 12. Rendimiento: qué se pudo medir y qué no

**No hay una comparación de rendimiento entre proveedores**: el mapa de Google solo se puede medir con una clave y un Map ID reales, y no se midió en teléfonos. Lo único medido es el código que SEVB entrega por proveedor (bytes sin comprimir, de los archivos del proyecto):

| | Leaflet | Google |
|---|---|---|
| Capa propia (`geo.js` + `nucleo.js`) | 23 KB | 23 KB |
| Adaptador | 21 KB | 32 KB |
| Biblioteca | Leaflet 148 KB; MapLibre (solo si se usa el mapa vectorial) 1,4 MB | SDK de Google: lo descarga Google (no medido) |

Lo que sí está verificado (con el SDK simulado y con Leaflet real): una sola instancia de mapa por pantalla, sin acumulación de elementos ni de listeners al repetir búsquedas o crear y borrar 200–300 elementos. Por medir con tu clave, en un teléfono medio: tiempo hasta mapa utilizable, fluidez al desplazar, consumo con el bus animado, editor con 1.500 y 10.000 puntos (la prueba de 10.000 puntos solo se ejecutó con Leaflet) y los cientos de polilíneas por segmento de `pasos.html`/`puentes.html`.
