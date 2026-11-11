# Google Maps en SEVB · única fuente de búsqueda de direcciones y lugares (4.14)

> **Decisión 4.14:** para el **pasajero**, autocompletado, búsqueda explícita (Enter / «Buscar»), detalle del lugar elegido y resolución de nombres usan **exclusivamente Google** (Places API «New»).
> ArcGIS, Nominatim, el catastro importado y la base propia de direcciones **no participan** en esas búsquedas, **no hay respaldo silencioso** y no se reservan cupos para fuentes secundarias.
> Sustituye la política anterior «Google → ArcGIS → otros». Sin `GOOGLE_MAPS_API_KEY` la búsqueda queda **apagada y la interfaz lo dice**; elegir en el mapa, el GPS del teléfono y las coordenadas escritas a mano siguen funcionando.

SEVB sigue usando su **mapa** (OpenFreeMap), sus **rutas, transbordos, caminata, PostgreSQL/PostGIS y redes vial/peatonal** propias. Eso no cambia (ver «Alcance» abajo).

## Qué hay

| Pieza | Qué hace | Necesita clave |
|---|---|---|
| `GoogleDireccionesService` | Autocompletar (`places:autocomplete`), búsqueda por texto (`places:searchText`) y detalle del lugar elegido (`places/{id}`) | Sí, `GOOGLE_MAPS_API_KEY` (solo servidor) |
| `LugarService` / `LugarController` | Contrato único de búsqueda: `GET /api/lugares/sugerencias` y `GET /api/lugares` devuelven siempre HTTP 200 con un **`estado`** (`OK`, `SIN_RESULTADOS`, `ERROR_SERVICIO`, `ERROR_CONFIGURACION`, `SIN_CONFIGURAR`, `CONSULTA_CORTA`) y `proveedor: "google"` | — |
| Botón **Google Maps** (herramientas del viaje) | Abre el mismo viaje A → B en Google Maps (transporte público) | No (enlace público) |

## Cómo se comporta (lo que debe poder verificarse)

- **Debounce** de 350 ms en el navegador, solo con ≥ 3 letras, sin texto en composición (IME/predictivo) y con **una petición por campo** (la anterior se cancela).
- **Sesiones de Places:** el navegador genera un token por campo (`sesion=`) y lo envía en cada autocompletado y en el detalle de la sugerencia elegida (así Google lo trata como **una** sesión). Al confirmar, borrar, intercambiar o volver al inicio la sesión se descarta. El servidor valida el formato (`[A-Za-z0-9_-]{8,64}`) y no reenvía tokens inválidos.
- **Campos solicitados** (`X-Goog-FieldMask`): `id, displayName, formattedAddress, location, types` (detalle) y `places.id, places.displayName, places.formattedAddress, places.location, places.types` (texto).
- **Restricción geográfica:** Colombia + caja de Villavicencio (`locationRestriction`), y cada coordenada final se **valida** contra esa caja; lo de fuera no se devuelve.
- **Precisión honesta:** el tipo de Google se traduce a `DIRECCION_PUNTUAL`, `VIA`, `SECTOR`, `CIUDAD` o `LUGAR`; una ciudad, barrio o calle se presenta como «zona amplia, no una dirección exacta», nunca como una puerta.
- **Elegir una sugerencia** resuelve **exactamente ese** `placeId` (no se exige id de la base local). Si Google no lo resuelve **no se elige otro lugar**: queda el texto escrito, el punto sin confirmar y un mensaje con «Reintentar» y «En el mapa».
- **Errores distinguidos:** sin resultados · servicio (red, cuota, caída → se puede reintentar) · configuración (401/403: clave o permisos → no sirve reintentar) · sin configurar. **Nunca** hay reintentos automáticos ni bucles.
- **Sin llamadas por GPS ni por fotograma del bus:** las lecturas GPS y el seguimiento del viaje no consultan Places ni geocodificación.
- **Punto elegido en el mapa:** `GET /api/lugares/reverso` valida que esté en la ciudad y le pone nombre con la base propia de lugares (≤ 45 m) o, si no hay, con sus coordenadas. No consulta ningún proveedor externo.

## Mapa base de Google (4.14)

En la **página del pasajero** el mapa se abre **siempre con Google Maps** cuando hay clave del navegador. Cómo está hecho (para no perder nada de lo existente):

- **Leaflet sigue gobernando** gestos, rutas, caminatas punteadas animadas, buses, marcadores, paraderos y toda la optimización existente (red peatonal propia, recomendaciones, transbordos, viaje). El mapa de Google es solo la **capa de fondo** (`common.js`, `googleBase`), sin gestos propios, sincronizada con el centro y el zoom de Leaflet. Por eso no se reescribió ninguna capa.
- **Clave distinta de la del servidor:** `GOOGLE_MAPS_BROWSER_KEY` (Maps JavaScript API). Es **pública por diseño** (viaja al navegador por `GET /api/mapa/config`): **restríngela por dominio (HTTP referrer)** y solo a «Maps JavaScript API». La clave de Places del servidor (`GOOGLE_MAPS_API_KEY`) nunca sale del servidor. El servidor solo entrega una clave con formato plausible.
- **Respaldo (no silencioso para quien mantiene, transparente para el pasajero):** sin clave, sin conexión, si el script no carga en 9 s, o si Google rechaza la clave (`gm_authFailure`), el mapa vuelve al mapa anterior (OpenFreeMap, con su respaldo ráster) sin romper nada.
- Con Google de fondo se apaga la animación de zoom por CSS de Leaflet (desfasaría marcadores y rutas del fondo) y se agrega a los créditos «Mapa © Google · Condiciones». El logotipo de Google lo dibuja su propio mapa.
- **CSP:** se permiten `maps.googleapis.com` (script, conexión, imágenes), `maps.gstatic.com`, `fonts.gstatic.com/googleapis` e imágenes de `googleusercontent/ggpht`. **No** se añadió `'unsafe-eval'`: si en un navegador real Google exigiera más permisos, se verá un error de CSP en la consola y habrá que ajustar la política (no se pudo probar con Google real).
- Las páginas de administración y el editor siguen con el mapa anterior (solo la del pasajero usa Google, para no multiplicar cargas facturables).
- **Offline:** «Guardar mapa de Villavicencio» guarda teselas del mapa de respaldo, no de Google (sus condiciones no permiten guardar sus teselas). Sin conexión se usa ese mapa.
- **Costos:** cada carga de la página del pasajero es una carga de mapa de Google (facturable por encima de la cuota gratuita): pon un presupuesto con alertas.
- Pruebas: `e2e/mapa_google_e2e.py` (Google **simulado**: carga del script con la clave, creación del mapa, sincronización, rutas y marcadores sobre él, respaldo ante fallo, clave inválida, sin clave y sin conexión) y `MapaConfigControllerTest`. **No probado con la API real de Google.**

## Cómo activarlo

1. En Google Cloud crea un proyecto, **activa la facturación** y habilita **Places API (New)** (solo esa).
2. Crea una clave de API y **restríngela**: solo «Places API (New)» y, si puedes, a la IP de tu servidor.
3. En **Presupuestos y alertas** pon un tope con alertas por correo.
4. En `.env`: `GOOGLE_MAPS_API_KEY=tu-clave` y reinicia. La clave **solo la lee el servidor** y viaja en una cabecera: nunca llega al navegador, a la URL ni a los registros.

**Dos credenciales distintas, no mezclar:** la clave de **Places del servidor** (`GOOGLE_MAPS_API_KEY`) y, si algún día se usa un mapa de Google en el navegador, una **clave de mapa del navegador** con restricción por dominio (no existe hoy: el mapa es OpenFreeMap). `GOOGLE_CLIENT_ID` (inicio de sesión) es otra cosa y es público.

## Alcance de la desactivación (qué sí y qué no)

| Se apagó para el pasajero | Se conserva |
|---|---|
| ArcGIS (sugerencias y geocodificación) | Rutas propias, PostgreSQL/PostGIS, paraderos y pasos |
| Nominatim (búsqueda y reverso) → `GEOCODER_URL` vacío por defecto | Red peatonal y red vial para buses (Overpass para esas redes) |
| Catastro/base propia de direcciones en la búsqueda del pasajero | Caminatas propias, geometrías abiertas ya usadas |
| Importación automática de catastro y de lugares/direcciones OSM (`*_IMPORT_AL_ARRANCAR=false`) | Datos ya cargados (no se borra ninguna tabla) y la importación **manual** del administrador |
| Envío de «texto → punto» a la base de direcciones tras elegir en el mapa | Favoritos, «Mis lugares», historial y viajes guardados |

Pantallas administrativas (`lugares.html`, `paraderos.html`…): reutilizan el mismo buscador (ahora Google) para ubicar puntos; su CRUD de lugares y la importación manual no cambian. Si el equipo quiere otro tratamiento para administración, debe decidirse aparte.

Las teselas de respaldo de Esri (`server.arcgisonline.com`) son un **mapa base**, no una búsqueda de direcciones.

## Caché y datos

- El service worker **nunca** intercepta `/api/lugares*`: no hay cachés de búsquedas viejas que reintroduzcan resultados de otros proveedores. No se tocan favoritos, historial ni preferencias.
- **No se guarda nada de Google:** ni el texto ni las coordenadas que devuelve van a la base. Lo elegido pasa al planificador y, si la persona lo guarda en «Mis lugares», se guarda solo su punto con el nombre que ella escriba. Cada resultado dice su origen («Google Maps»), cada resultado dice «Google Maps» y nunca se marca como «verificado».
- **Pendiente de confirmar por el equipo (condiciones de Google):** Google exige mostrar los resultados de Places sobre **un mapa de Google** (o su atribución «Powered by Google» en el autocompletar sin mapa). Aquí el mapa es OpenFreeMap y se muestra el texto «Google Maps» en cada resultado, no el logotipo oficial. **Debe revisarse con las condiciones vigentes antes de publicar.** Mientras no se decida, no se almacena contenido de Google más allá de la sesión.

## Pruebas

- Java: `GoogleDireccionesServiceTest` (servidor simulado: clave en cabecera y no en la URL, token de sesión en autocompletar y detalle, errores de configuración vs servicio, ids inválidos descartados, solo Villavicencio), `LugarServiceGoogleTest` (solo Google, sin respaldo, estados, elegido-fuera-de-ciudad no se sustituye), `LugarApiTest` (contrato con `estado`/`proveedor`, sesión inválida descartada) y `SoloGoogleConfiguracionTest` (el código y la configuración por defecto no reintroducen otros proveedores ni importaciones automáticas; el ruteo propio sigue activo).
- Navegador (stub + Google simulado con rutas de Playwright; **sin** Internet ni claves): `e2e/ux414_e2e.py` — ver `docs/UX-MOVIL-4.14.md`.
- **No probado contra Google real** (sin clave en este entorno): ver «Limitaciones» en `docs/UX-MOVIL-4.14.md`.
