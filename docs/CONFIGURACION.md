# Matriz de configuración · SEVB 4.13.1

Una sola fuente de verdad por modo de arranque. Los valores reales nunca van en este documento ni en el ZIP: viven en tu `.env` (que está en `.gitignore`, `.dockerignore` y nunca entra al paquete de entrega).

## Modos de arranque

| Modo | Comando | Dónde se leen las variables |
|---|---|---|
| **Desarrollo (PowerShell)** | `.\start.ps1` (o `-NoDatabase`) | `.env` → variables del proceso. El script toma la lista de variables de `src/main/resources/application.properties`, **no modifica `.env` y no pisa lo que ya definiste en tu sesión** |
| **Docker local** | `docker compose -f docker-compose.yml -f compose.app.yml up -d --build` | `.env` (lo lee Compose) → `environment:` de `compose.app.yml` |
| **Producción** | `docker compose -f compose.prod.yml up -d --build` | `.env` del servidor → `environment:` de `compose.prod.yml` |

Valores seguros por defecto en los tres: `EDITOR_ENABLED=false`, `SERVER_ADDRESS=127.0.0.1` (en contenedor la aplicación escucha en `0.0.0.0` *dentro* de la red de Docker, pero solo se publica en `127.0.0.1:8080` o detrás de Caddy), `DEMO_DATA=false` en producción.

Leyenda de las columnas de modo: ✅ configurable desde `.env`; 🔒 valor fijado por el archivo (se explica); — no aplica.

## Matriz

| Variable | Propósito | Predeterminado | Obligatoria | Desarrollo | Docker local | Producción | Sensible |
|---|---|---|---|---|---|---|---|
| `DB_PASSWORD` | Clave del usuario PostgreSQL `spevb` | — | **Sí** | ✅ | ✅ | ✅ | **Sí** |
| `DB_URL` | URL JDBC de la base | `jdbc:postgresql://localhost:5432/spevb` | No | ✅ | 🔒 `…@postgres:5432/spevb` | 🔒 igual | No (sin clave) |
| `DB_USER` | Usuario de la base | `spevb` | No | ✅ | 🔒 `spevb` | 🔒 `spevb` | No |
| `SERVER_ADDRESS` | Interfaz donde escucha la aplicación | `127.0.0.1` | No | ✅ | 🔒 `0.0.0.0` (solo dentro del contenedor; puerto publicado en `127.0.0.1`) | 🔒 `0.0.0.0` (sin puertos publicados; entra Caddy) | No |
| `SERVER_PORT` | Puerto HTTP | `8080` | No | ✅ | 🔒 8080 (el externo se cambia en `ports:`) | 🔒 8080 | No |
| `EDITOR_ENABLED` | Activa `/api/editor/*` y las páginas de administración | `false` | No | ✅ | ✅ | ✅ | No |
| `EDITOR_TOKEN` | Clave de administración (≥ 32 caracteres) | vacía | Solo si `EDITOR_ENABLED=true` | ✅ | ✅ | ✅ | **Sí** |
| `LIMITE_SOLICITUDES` | Limitador de solicitudes por cliente | `true` | No | ✅ | ✅ | ✅ | No |
| `ADMIN_CORREOS` | Correos (coma) que administran entrando con Google | vacía | No | ✅ | ✅ | ✅ | Dato personal |
| `GOOGLE_CLIENT_ID` | ID de cliente de Google (público) | vacío (el botón no aparece) | No | ✅ | ✅ | ✅ | No (público) |
| `GOOGLE_JWKS_URL` | Claves públicas para verificar tokens de Google | `https://www.googleapis.com/oauth2/v3/certs` | No | ✅ | ✅ | ✅ | No |
| `DEMO_DATA` | Carga las rutas DEMO (`db/demo`) | `false` (aplicación) · `true` en `.env.example` y Docker local | No | ✅ | ✅ (`true` si no se define) | 🔒 `false` siempre | No |
| `GEOCODER_URL` | Nominatim (solo si alguien lo activa a propósito). **4.14: ningún flujo del pasajero lo consulta** | vacío (apagado) | No | ✅ | ✅ | ✅ | No |
| `GEOCODER_REVERSE_URL` | Geocodificación inversa de Nominatim (no usada por el pasajero desde 4.14) | vacío (apagado) | No | ✅ | ✅ | ✅ | No |
| `GEOCODER_USER_AGENT` | Identificación ante el proveedor | `SEVB-Rutas/4.10` (el mismo valor se usa si no se define) | No | ✅ | ✅ | ✅ | No |
| `VILLAVO_CATASTRO_URL` | Consulta de predios del catastro | URL pública del servicio | No | ✅ | ✅ | ✅ | No |
| `ARCGIS_API_KEY` | Credencial opcional de ArcGIS (solo desde Java) | vacía | No | ✅ | ✅ | ✅ | **Sí** |
| `ARCGIS_GEOCODE_URL` | Endpoint de ArcGIS | URL pública del servicio | No | ✅ | ✅ | ✅ | No |
| `GOOGLE_MAPS_API_KEY` | **Único proveedor de búsqueda de direcciones y lugares del pasajero (4.14)**: Places API (New) para autocompletar, buscar texto y resolver el lugar elegido. Solo desde Java; sin clave la búsqueda queda apagada y la interfaz lo informa | vacía | No | ✅ | ✅ | ✅ | **Sí** |
| `GOOGLE_MAPS_BROWSER_KEY` | Clave de **Maps JavaScript API** para el **navegador** (mapa base de Google). Es otra clave, distinta de la del servidor, y **pública por diseño**: restríngela por dominio. Vacía = mapa de respaldo (OpenFreeMap) | vacía | No | ✅ | ✅ | ✅ | Pública (restringir) |
| `GOOGLE_MAPS_URL` | Endpoint de Google Places (solo cambia en pruebas) | `https://places.googleapis.com` | No | ✅ | ✅ | ✅ | No |
| `DIRECCIONES_IMPORT_AL_ARRANCAR` | Importa el catastro al arrancar (la búsqueda del pasajero ya no usa esa base; la importación manual del administrador sigue) | `false` | No | ✅ | ✅ | ✅ | No |
| `DIRECCIONES_REFRESCO_DIAS` | Días entre refrescos del catastro | `30` | No | ✅ | ✅ | ✅ | No |
| `OVERPASS_URLS` | Servidores Overpass (coma, en orden) | tres servidores públicos | No | ✅ | ✅ | ✅ | No |
| `OSM_IMPORT_AL_ARRANCAR` | Importa lugares/direcciones de OpenStreetMap al arrancar (no afecta la red vial ni peatonal, que usan `OVERPASS_URLS`) | `false` | No | ✅ | ✅ | ✅ | No |
| `OSM_REFRESCO_DIAS` | Días entre refrescos de OSM | `30` | No | ✅ | ✅ | ✅ | No |
| `WALKING_URL` | OSRM peatonal de respaldo (vacío = línea recta) | `https://routing.openstreetmap.de/routed-foot/route/v1/foot` | No | ✅ | ✅ | ✅ | No |
| `RED_PEATONAL_HABILITADA` | Red de calles propia en memoria | `true` | No | ✅ | ✅ | ✅ | No |
| `RED_PEATONAL_ARCHIVO` | Archivo de caché de la red | `data/red-peatonal.bin.gz` | No | ✅ | ✅ (volumen `/app/data`) | ✅ (volumen) | No |
| `RED_PEATONAL_REFRESCO_DIAS` | Días entre descargas de la red | `60` | No | ✅ | ✅ | ✅ | No |
| `RED_VIAL_HABILITADA` | Red vial propia para buses (separada de la peatonal) | `true` | No | ✅ | ✅ | ✅ | No |
| `RED_VIAL_ARCHIVO` | Archivo de caché de la red vial de buses | `data/red-vial-bus.json.gz` | No | ✅ | ✅ (volumen `/app/data`) | ✅ (volumen) | No |
| `RED_VIAL_REFRESCO_DIAS` | Días entre descargas de la red vial | `60` | No | ✅ | ✅ | ✅ | No |
| `RED_VIAL_MAX_SNAP_METROS` | Distancia máxima para ajustar un punto a una vía | `30` | No | ✅ | ✅ | ✅ | No |
| `BUS_MATCHING_URL` | Servidor OSRM `/match` opcional (vacío = desactivado; nunca recibe credenciales) | vacío | No | ✅ | ✅ | ✅ | No |
| `CITY_MIN_LAT` / `CITY_MAX_LAT` | Límites de latitud de la ciudad | `3.95` / `4.35` | No | ✅ | ✅ | ✅ | No |
| `CITY_MIN_LNG` / `CITY_MAX_LNG` | Límites de longitud de la ciudad | `-73.85` / `-73.45` | No | ✅ | ✅ | ✅ | No |
| `DOMINIO` | Dominio público para HTTPS (Caddy) | — | **Sí en producción** | — | — | ✅ | No |
| `JAVA_OPTS` | Memoria de la JVM (se pasa como `JAVA_TOOL_OPTIONS`) | `-Xms512m -Xmx1536m -XX:+UseG1GC -XX:MaxGCPauseMillis=100` | No | — | — | ✅ | No |

Las filas `CITY_*` se escriben como una sola fila por pareja; en el código son cuatro variables separadas (`CITY_MIN_LAT`, `CITY_MAX_LAT`, `CITY_MIN_LNG`, `CITY_MAX_LNG`) y las cuatro llegan a todos los modos.

## Excepciones justificadas (las comprueba la prueba automática)

| Variable | Dónde se fija | Motivo |
|---|---|---|
| `SERVER_PORT` | `compose.*` | El contenedor siempre escucha en 8080; el puerto externo se decide con `ports:` (local) o Caddy (producción). |
| `DB_URL`, `DB_USER` | `compose.*` | Apuntan al servicio `postgres` de la red interna de Docker. Un destino distinto exigiría otro archivo compose. |

`ConfiguracionArranqueTest` falla si aparece una variable nueva en `application.properties` que no esté en `.env.example`, en esta matriz y en ambos compose (salvo las excepciones anteriores).

## Reglas de seguridad de la configuración

- `.env` nunca se copia a la imagen (`.dockerignore`), al repositorio (`.gitignore`) ni al ZIP de entrega (`scripts/package-release.ps1` lo verifica).
- Ningún secreto llega al navegador: `ARCGIS_API_KEY`, `GOOGLE_MAPS_API_KEY`, `EDITOR_TOKEN` y `DB_PASSWORD` solo los lee el servidor. `GOOGLE_CLIENT_ID` es público por diseño.
- Si un `.env` o un ZIP con credenciales se compartió alguna vez, **las credenciales que contenía deben rotarse fuera de este proyecto**: la contraseña de la base, `EDITOR_TOKEN`, `ARCGIS_API_KEY` y cualquier otra. Ver `docs/CAMBIAR-CLAVES.md`. Rotar es necesario aunque el ZIP ya se haya borrado.

## Búsqueda de direcciones: solo Google (4.14)

- Autocompletado, búsqueda explícita y detalle del lugar elegido usan **solo** Google Places API (New) con `GOOGLE_MAPS_API_KEY` (servidor). ArcGIS, Nominatim y la base propia de direcciones **no participan** en las búsquedas del pasajero y no hay respaldo silencioso: si Google falla, la interfaz conserva el texto y ofrece reintentar o elegir en el mapa.
- Siguen disponibles, porque no son buscadores de texto: elegir el punto en el mapa, el GPS del teléfono, las coordenadas escritas a mano, «Mis lugares» y los favoritos.
- Siguen activos los motores propios de ruteo: rutas, PostgreSQL/PostGIS, red peatonal y vial (Overpass para esas redes), caminatas y paraderos.
- Las pantallas administrativas de lugares reutilizan el mismo buscador (Google). La importación manual del catastro/OSM sigue en el panel de administración; la automática está apagada por defecto.
- Las teselas de mapa de respaldo de Esri (`server.arcgisonline.com`) son un mapa base, no una búsqueda de direcciones.
