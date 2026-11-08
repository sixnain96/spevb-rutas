# Migraciones de base de datos (Flyway) · SEVB 4.13.4

Hasta la 4.9.5 la aplicación ejecutaba `schema.sql` y `data.sql` en **cada** arranque. Desde la 4.9.6 el esquema lo gestiona Flyway y hay un historial (`flyway_schema_history`) que dice qué cambios tiene cada instalación.

## Qué hay

| Archivo (`src/main/resources/db/migration`) | Contenido |
|---|---|
| `V1__esquema_base.sql` | PostGIS, `rutas`, `ruta_revisiones`, `paraderos`, `lugares` |
| `V2__direcciones_y_lugares_osm.sql` | `direcciones`, `barrios`, `direccion_confirmaciones`, `busquedas_sin_resultado`, `direcciones_importaciones`, `pois` (+ `pg_trgm`) |
| `V3__pasos_peatonales.sql` | `pasos_peatonales` (PASO y BLOQUEO) |
| `V4__puentes_peatonales.sql` | tipo `PUENTE` en `pasos_peatonales` |
| `V5__reportes.sql` | `reportes` |
| `V6__cuentas.sql` | `usuarios`, `sesiones`, `usuario_datos` |
| `V7__inicio_de_sesion_google.sql` | cuentas sin clave y `google_sub` |
| `V8__paraderos_del_sistema.sql` | `paraderos_buses` |
| `V9__ruta_aliases.sql` | `ruta_aliases`: código anterior de una ruta → `ruta_id` (4.10) |
| `V10__pasos_version.sql` | `version` y `actualizado_en` en `pasos_peatonales` para editar puentes con control optimista (4.10) |
| `V11__ruta_nombre_popular.sql` | `nombre_popular` en `rutas`: el nombre con que la gente conoce la ruta («Catama», «Kirpas»), opcional (4.14) |
| `db/demo/R__datos_demostrativos.sql` | rutas R001–R004 DEMO y 2 paraderos demo; **solo** con `DEMO_DATA=true` |

Las migraciones **no borran tablas ni datos y no recrean la base**: usan `CREATE … IF NOT EXISTS` y `ADD COLUMN IF NOT EXISTS`, justo lo que ya hacía `schema.sql`. Por eso son idempotentes y por eso una base existente se adopta sin riesgo. `schema.sql` y `data.sql` originales se conservan solo como fixtures de prueba en `src/test/resources/legacy/`.

## Base nueva

Arranca la aplicación con una base vacía: Flyway aplica V1…V11. Con `DEMO_DATA=false` (predeterminado de la aplicación y fijo en producción) no se crea ninguna ruta. Con `DEMO_DATA=true` (desarrollo) se cargan las cuatro rutas DEMO.

## Actualizar una base existente (instalada con 4.9.5 o anterior)

1. **Haz un respaldo y comprueba que se puede restaurar** (ver abajo).
2. Despliega la 4.13.4 (`docker compose … up -d --build` o `.\start.ps1`).
3. Flyway detecta una base con tablas pero sin historial, la marca como línea base (`baseline-version=0`) y aplica V1…V11. Como son idempotentes, no cambia ni borra datos; solo registra el historial y añade lo que falte.
4. Comprueba `/api/estado` (`version: 4.13.4`) y `SELECT version, success FROM flyway_schema_history ORDER BY installed_rank;`.

Si tenías las rutas DEMO en una base de producción (las creaba `data.sql` en cada arranque), **siguen allí**: ninguna migración borra datos. Si quieres retirarlas, hazlo tú, a mano y con respaldo previo, por ejemplo `DELETE FROM rutas WHERE demostrativa AND codigo IN ('R001','R002','R003','R004');` (las revisiones y paraderos de esas rutas se eliminan en cascada).

## Datos de demostración

Viven aparte (`db/demo`) y solo se cargan si `spevb.demo-data=true` (`DEMO_DATA=true`). `compose.prod.yml` fija `DEMO_DATA: "false"`. Si activas la demostración y luego la apagas, el arranque sigue funcionando (`repeatable:missing` se ignora) y no se borra nada.

## Rollback operativo

**Flyway Community no revierte migraciones.** El rollback de SEVB es por respaldo:

1. Detén la aplicación nueva (`docker compose … stop app`).
2. Restaura el respaldo hecho antes de actualizar (`restore.ps1` / `deploy/restore.sh`). Esto **reemplaza** la base actual, incluidos los cambios hechos después del respaldo.
3. Vuelve a desplegar la versión anterior del código.
4. Comprueba `/api/estado`.

Ninguna migración actual es destructiva, así que normalmente basta con volver al código anterior (la base 4.9.6 es compatible con 4.9.5, que ignora la tabla `flyway_schema_history`). Aun así, **no confíes en eso: respalda antes de cada actualización**. Una migración futura que borre o transforme datos debe documentar su propio rollback y probarse con `MigracionesPostgisTest`.

Nunca uses `docker compose down -v` (borra los volúmenes con los datos) ni `flyway clean` (está deshabilitado a propósito en el arranque).

## Pruebas

`MigracionesPostgisTest` (PostgreSQL/PostGIS real en un contenedor desechable, imagen `postgis/postgis:17-3.5`) comprueba: base nueva sin datos DEMO; base nueva con DEMO e idempotencia; **base existente** creada con el `schema.sql` antiguo con filas reales (ruta, paradero, lugar, usuario, reporte, puente con nota, paradero del sistema) que se conservan sin pérdida ni duplicados tras migrar; segunda ejecución sin cambios; y `validate`. Requieren Docker; sin Docker se **omiten** (aparecen como omitidas, no como aprobadas).
