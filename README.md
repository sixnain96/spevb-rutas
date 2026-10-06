# SPEVB – Módulo de rutas V3 · Villavicencio

SPEVB es un proyecto académico para registrar y consultar recorridos de buses de **Villavicencio, Meta**. Esta versión concentra el sistema en una sola ciudad y añade, frente a la V2, estas piezas:

- búsqueda de direcciones reales mediante un proveedor compatible con Nominatim/OpenStreetMap;
- catálogo local persistente para guardar direcciones o lugares que no aparezcan en el proveedor;
- creación y edición de rutas punto a punto, guardadas en PostgreSQL/PostGIS con historial de versiones;
- planificador de viaje: compara el origen y el destino con todas las rutas guardadas y muestra siempre la más cercana, con el camino aproximado desde A hasta el bus y desde el bus hasta B.

Tecnologías: Java 21, Spring Boot 4.1.1, PostgreSQL 17 + PostGIS 3.5, JDBC y Leaflet 1.9.4.

> Los recorridos que dibuje el equipo pueden representar conocimiento de campo, pero no deben presentarse como información oficial si no existe evidencia de validación. El sistema permite marcar una revisión y guardar su fuente, pero esa marca no sustituye la certificación de una autoridad u operador.

## 1. Arranque rápido desde IntelliJ IDEA

Requisitos: Java 21, Maven y Docker Desktop.

1. Abre la carpeta que contiene `pom.xml` en IntelliJ.
2. Copia `.env.example` como `.env` (si ya tienes un `.env` de antes, consérvalo). El archivo `.env` nunca se sube al repositorio.
3. En `.env` define una contraseña propia y una clave de editor de 32 caracteres o más. Nominatim viene configurado por defecto; si conservas un `.env` antiguo con `GEOCODER_URL=` vacío, `start.ps1` también aplica estos valores automáticamente:

```env
DB_PASSWORD=TU_CLAVE_DE_POSTGRES
EDITOR_ENABLED=true
EDITOR_TOKEN=UNA_CLAVE_LARGA_DE_AL_MENOS_32_CARACTERES
GEOCODER_URL=https://nominatim.openstreetmap.org/search
GEOCODER_REVERSE_URL=https://nominatim.openstreetmap.org/reverse
GEOCODER_USER_AGENT=SPEVB-Rutas-USTA/3.0
```

4. Abre Docker Desktop.
5. En la Terminal de IntelliJ ejecuta:

```powershell
docker compose up -d
```

6. Cuando `spevb-postgis` aparezca como `healthy`, ejecuta:

```powershell
.\start.ps1 -NoDatabase
```

7. Abre:

- Explorador: `http://localhost:8080`
- Editor de recorridos: `http://localhost:8080/editor.html`
- Catálogo de direcciones: `http://localhost:8080/lugares.html`
- Estado de aplicación y base de datos: `http://localhost:8080/api/estado`

También puedes usar simplemente `./start.ps1`; el script levanta PostGIS y luego Spring Boot.

## 2. La base no se borra al cerrar el proyecto

Los recorridos, direcciones e historial se guardan en el volumen Docker `spevb-rutas-v3_spevb_pgdata`. El archivo `docker-compose.yml` fija el nombre del proyecto Docker como `spevb-rutas-v3`, por lo que el mismo volumen se sigue usando aunque copies el código a otra carpeta.

Para detener normalmente:

```powershell
docker compose stop
```

Para volver a iniciar:

```powershell
docker compose up -d
```

**No uses `docker compose down -v` si quieres conservar los datos.** La opción `-v` elimina el volumen de PostgreSQL.

Antes de cambios importantes puedes crear un respaldo lógico:

```powershell
.\backup.ps1
```

El archivo queda en `backups/`. Para restaurar uno:

```powershell
.\restore.ps1 -File .\backups\spevb-AAAAMMDD-HHMMSS.sql
```

## 3. Direcciones reales de Villavicencio

La búsqueda trabaja en dos niveles:

1. **Catálogo local SPEVB.** Busca lugares guardados manualmente, direcciones consultadas anteriormente, extremos de rutas y paraderos.
2. **Proveedor geográfico.** Si hacen falta resultados, consulta Nominatim restringiendo la búsqueda al área de Villavicencio. Los endpoints pueden reemplazarse por otro proveedor compatible o por una instancia propia.

El servidor normaliza abreviaturas comunes como `Cra`, `Cr`, `Cll`, `Cl`, `Av`, `Diag`, `Tv` y añade `Villavicencio, Meta, Colombia` a la búsqueda cuando el usuario no lo escribió.

Los resultados externos se guardan en la tabla `lugares` como referencias **no confirmadas**, para que consultas posteriores puedan resolverse localmente. Un lugar creado por el equipo desde `/lugares.html` se guarda como **confirmado localmente**.

### Importante sobre cobertura

Nominatim busca sobre datos de OpenStreetMap. Si una casa, nomenclatura o calle no está registrada allí, el sistema no puede inventar su dirección. Por eso existe el catálogo local: seleccionas el punto exacto en el mapa, escribes el nombre o referencia y lo guardas en PostgreSQL.

El servicio público de Nominatim es para uso moderado. SPEVB no hace autocompletado en cada tecla, limita las llamadas a una por aproximadamente 1,1 segundos, usa identificación de aplicación y conserva caché. Para un despliegue con muchos usuarios conviene un proveedor dedicado o una instancia propia.

## 4. Agregar una dirección que no aparece

Entra a `http://localhost:8080/lugares.html`.

1. Busca la dirección.
2. Si aparece, selecciónala para revisar el punto.
3. Si no aparece, pulsa **Elegir punto en mapa** y marca la ubicación exacta.
4. El sistema intenta obtener una dirección aproximada mediante geocodificación inversa.
5. Ajusta el nombre o la referencia si hace falta.
6. Escribe `EDITOR_TOKEN` y pulsa **Guardar lugar en PostgreSQL**.

Desde ese momento el lugar participa en las búsquedas del planificador, incluso si el proveedor externo no lo conocía.

## 5. Crear una ruta nueva desde cero

Entra a `http://localhost:8080/editor.html`.

1. Pulsa **+ Nueva**.
2. Escribe código, nombre, sentido, color, inicio y destino.
3. Deja una fuente clara, por ejemplo: `Recorrido registrado manualmente por el equipo SPEVB`.
4. Activa **Agregar puntos**.
5. Haz clic siguiendo el recorrido real, giro por giro. Cada clic se une al anterior.
6. Puedes mover un punto, insertar otro, eliminarlo, deshacer o importar un GeoJSON.
7. Si el recorrido todavía depende del conocimiento del equipo, **no marques validada**. Se guarda igualmente como ruta real del proyecto, pero queda pendiente de verificación oficial.
8. Escribe `EDITOR_TOKEN` y pulsa **Guardar recorrido en la base de datos**.

El backend guarda un `LineString` EPSG:4326 en PostGIS. Al editar una ruta existente, primero archiva la versión anterior en `ruta_revisiones`. Si dos pestañas intentan guardar sobre la misma versión, la segunda recibe `409 Conflict`.

## 6. Rutas cercanas y planificación

En el explorador puedes:

- buscar una dirección o un lugar;
- usar tu ubicación del navegador como origen;
- elegir origen o destino directamente en el mapa;
- pulsar **Encontrar rutas cercanas**: el sistema muestra siempre la ruta registrada más cercana, sin importar la distancia, y la dibuja de inmediato con líneas punteadas desde A hasta donde se toma el bus y desde donde se baja hasta B;
- tocar otra tarjeta para ver las demás opciones, ordenadas de la más cercana a la más lejana;
- pulsar **Ver rutas que pasan cerca del origen** para ver las rutas más cercanas a tu punto de salida.

Cada ruta se usa en el sentido en que fue dibujada. Si una ruta `IDA` o `REGRESO` sólo conecta A y B recorriéndola al revés, aparece como alternativa con advertencia y no como opción directa: registra la ruta de regreso o, si el trazado quedó al revés, corrígelo con **Invertir sentido** en el editor. Las rutas `CIRCULAR` pueden dar la vuelta completa.

La distancia entre el usuario y la línea se calcula con PostGIS sobre `geography`. El planificador utiliza `ST_LineLocatePoint`, `ST_LineInterpolatePoint`, `ST_LineSubstring` y `ST_Distance`.

Los enlaces punteados del mapa representan cercanía en línea recta. No son instrucciones para caminar y los puntos proyectados sobre la línea no son necesariamente paraderos autorizados.

## 7. Tablas principales

### `rutas`

Guarda código, nombre, inicio, destino, sentido, color, fuente, estado de revisión, número de versión y la geometría `LineString`.

### `ruta_revisiones`

Conserva las versiones anteriores en JSONB antes de cada modificación.

### `paraderos`

Guarda puntos asociados a una ruta.

### `lugares`

Guarda direcciones y referencias de Villavicencio como geometrías `Point`. Incluye fuente, estado de confirmación y, cuando proviene de OpenStreetMap, una referencia externa para evitar duplicados.

## 8. Variables de configuración

| Variable | Uso |
|---|---|
| `DB_PASSWORD` | Contraseña del usuario PostgreSQL `spevb` |
| `DB_URL` | Por defecto `jdbc:postgresql://localhost:5432/spevb` |
| `DB_USER` | Por defecto `spevb` |
| `EDITOR_ENABLED` | Activa o desactiva operaciones de administración |
| `EDITOR_TOKEN` | Clave de administración, mínimo 32 caracteres |
| `GEOCODER_URL` | Endpoint `/search` compatible con Nominatim |
| `GEOCODER_REVERSE_URL` | Endpoint `/reverse` compatible con Nominatim |
| `GEOCODER_USER_AGENT` | Identificación del proyecto ante el proveedor |
| `CITY_MIN_LAT`, `CITY_MAX_LAT` | Límite vertical del área de trabajo |
| `CITY_MIN_LNG`, `CITY_MAX_LNG` | Límite horizontal del área de trabajo |

La aplicación rechaza puntos de lugares, viajes o recorridos guardados fuera del área configurada para Villavicencio.

## 9. Cambiar las claves

Consulta `docs/CAMBIAR-CLAVES.md` para generar claves nuevas y cambiar la contraseña de PostgreSQL sin perder datos.

## 10. Pruebas

Ejecuta:

```powershell
mvn verify
```

Las pruebas cubren autenticación del editor, validación JSON, geometrías, servicios de rutas, planificación y API de lugares. La validación final contra PostGIS debe hacerse además con Docker ejecutándose; consulta `docs/VERIFICACION-POSTGIS.md`.

## 11. APIs

Consulta `docs/API.md` para ejemplos de creación de rutas, catálogo de lugares, geocodificación inversa, rutas cercanas y planificación.

La cartografía usa OpenStreetMap con su atribución correspondiente. La política del servicio público de Nominatim se encuentra en `https://operations.osmfoundation.org/policies/nominatim/`.
