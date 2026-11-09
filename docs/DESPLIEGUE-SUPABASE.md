# Despliegue con la base en Supabase

Variante de `docs/DOCKER-Y-OPERACION.md`: la aplicación y Caddy corren en el VPS y la base PostgreSQL/PostGIS vive en Supabase. El archivo es `compose.prod.supabase.yml` (igual que `compose.prod.yml` pero sin el contenedor de la base; `DB_URL` y `DB_USER` salen del `.env`). El estado de lo probado está al final, en «Qué está verificado».

## 1. Supabase (una vez)
- Proyecto en la región **East US (North Virginia)**, cerca del VPS.
- Al crearlo, desmarca «Enable Data API» y «Automatically expose new tables»: SEVB se conecta directo por JDBC y no usa esa API.
- Database → Extensions: activa **`postgis`** y **`pg_trgm`** (las migraciones V1 y V2 las piden).
- Guarda la contraseña de la base fuera del proyecto. No la compartas ni la pegues en chats ni en archivos.

## 2. Conexión (Connect → Session pooler)
Usa **Session pooler** (IPv4, puerto 5432). **No** uses «Transaction pooler» (6543): rompe las consultas preparadas de JDBC y de Flyway. Con IPv6 en el VPS también sirve la conexión directa.

Copia el servidor exacto de Connect: según el proyecto es `aws-0-…` o `aws-1-…pooler.supabase.com`. Con el prefijo equivocado Supabase responde «Tenant or user not found».

En el `.env` del VPS (nunca en Git ni en un ZIP):
```
DB_URL=jdbc:postgresql://HOST_DEL_POOLER:5432/postgres?sslmode=require
DB_USER=postgres.ID_DEL_PROYECTO
DB_PASSWORD=<la clave de la base>
DOMINIO=<nombre con https, p. ej. 203-0-113-7.sslip.io>
EDITOR_ENABLED=false
```
La contraseña no va dentro de la URL, así que no hace falta codificar caracteres especiales.

Con una `DB_URL` de Supabase la aplicación activa sola el perfil `supabase` (pool de hasta 10 conexiones con 5 siempre abiertas, comprobación periódica y plazos acotados; ver `docs/RENDIMIENTO-DB.md`). Cada copia de la aplicación puede usar hasta 10 conexiones del Session pooler, que son limitadas por proyecto: no levantes más copias de las necesarias contra el mismo proyecto.

### Guardar la conexión en el `.env` (para no escribir la contraseña en cada arranque)
`scripts/supabase/configurar-env.ps1` deja `DB_URL`, `DB_USER` y `DB_PASSWORD` en el `.env`: pide la contraseña (no se ve), cambia solo esas tres líneas y guarda antes una copia del `.env` anterior en `.env.local.bak` (git y docker la ignoran). Después `.\start.ps1 -NoDatabase` arranca contra Supabase sin pedir nada, y `compose.prod.supabase.yml` lee los mismos valores.
```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\supabase\configurar-env.ps1
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\supabase\configurar-env.ps1 -RestaurarLocal   # volver a la base local
```
Cuidado: si el proyecto está dentro de OneDrive, el `.env` (con la contraseña) se sincroniza con la nube. Nunca lo incluyas en un ZIP ni lo subas a Git. Alternativa que no guarda nada en la carpeta: `scripts/supabase/guardar-clave.ps1` (variable de usuario de Windows `SUPABASE_DB_PASSWORD`).

## 3. Probar antes de desplegar (en tu computador)
El script `scripts/supabase/probar-supabase.ps1` hace la prueba completa: revisa PostGIS antes de migrar, arranca la aplicación (Flyway V1 a V11), comprueba `/api/estado` y la página, mide la base y detiene la aplicación. Pide la contraseña al ejecutarse (no se ve ni se guarda). Deja el resultado en `target\supabase-informe.txt`, sin secretos.
```powershell
.\scripts\supabase\probar-supabase.ps1                      # importaciones apagadas: solo migraciones y tamaño base
.\scripts\supabase\probar-supabase.ps1 -ConImportaciones    # como en producción: importa direcciones y lugares y vigila el tamaño
.\scripts\supabase\informe.ps1                              # solo el informe de la base (necesita DB_URL, DB_USER y DB_PASSWORD en la sesión)
```

## 4. Arrancar en el servidor
```bash
docker compose -f compose.prod.supabase.yml up -d --build
docker compose -f compose.prod.supabase.yml logs -f app     # Flyway crea las tablas en Supabase la primera vez
```

## 5. Llevar tus rutas (de la base local a Supabase)
Siempre a través de una COPIA, nunca escribiendo sobre la original. El script `scripts/supabase/trasladar-rutas.ps1` hace todo:
1. Copia la base local con `pg_dump` (solo lectura: no modifica la original) y la restaura en un contenedor desechable.
2. Muestra cuántas filas de cada tabla se trasladarían.
3. Con la contraseña de Supabase, comprueba que Flyway ya llegó a la V11 y que las tablas de destino están **vacías**; si no, se detiene sin escribir nada.
4. Pide escribir `SI`, copia los datos desde la copia en **una sola transacción** (si algo falla no queda nada a medias), ajusta las secuencias y compara los conteos.

Se trasladan `rutas`, `ruta_revisiones`, `paraderos`, `ruta_aliases`, `pasos_peatonales` (incluye puentes y bloqueos), `paraderos_buses` y `lugares`. **No** se trasladan direcciones, puntos de OpenStreetMap ni barrios (ya los importa la aplicación), ni cuentas, sesiones, reportes ni confirmaciones (son datos personales: ver `docs/DATOS-PERSONALES.md`).
```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\supabase\trasladar-rutas.ps1 -SoloCopia   # sin contraseña: solo crea la copia y cuenta
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\supabase\trasladar-rutas.ps1              # traslada a Supabase (pide la contraseña y un SI)
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\supabase\iniciar-supabase.ps1             # arranca SEVB usando Supabase en lugar de la base local
```
Requiere Docker activo y el contenedor local `spevb-postgis` en marcha. Ensayado contra una base desechable: copia 155 filas (15 rutas, 51 revisiones, 2 paraderos, 1 alias, 9 pasos, 26 paraderos del sistema, 51 lugares), los conteos coinciden, la geometría y las secuencias quedan bien, y un segundo intento se niega a duplicar.

## 6. Límites y cuidados
- **Tamaño:** el plan gratis tiene 500 MB. La importación automática de direcciones y lugares (`DIRECCIONES_IMPORT_AL_ARRANCAR`, `OSM_IMPORT_AL_ARRANCAR`) llena tablas: si el informe se acerca al límite, ponlas en `false` en el `.env`.
- **Pausa:** el plan gratis pausa el proyecto tras una semana sin actividad (se reactiva a mano). Para 24/7 real hace falta un plan de pago.
- **Latencia:** cada consulta cruza la red hasta Supabase y planificar un viaje hace unas 20. Mantén la aplicación y la base en la misma región (Virginia): a 110 ms planificar tarda ≈ 2,6 s; en la misma región ≈ 0,2 s. Mídela con `informe.ps1`. Detalles, mediciones y ajustes descartados: `docs/RENDIMIENTO-DB.md`.
- **PostGIS en el esquema `extensions`:** Supabase instala PostGIS ahí. Si Flyway falla con «type geometry does not exist», el `search_path` de la conexión no incluye `extensions`. Solución mínima, una sola vez en el SQL Editor de Supabase: `ALTER ROLE postgres SET search_path = "$user", public, extensions;` (y volver a conectar).

## Qué está verificado
**Contra Supabase real** (proyecto vacío, East US, Session pooler `aws-0-us-east-1.pooler.supabase.com:5432`, SSL, usuario `postgres.<id>`; PostgreSQL 17.11, PostGIS 3.3.7), 8 de noviembre de 2026, con `probar-supabase.ps1` e importaciones apagadas:
- La conexión funciona y el `search_path` del rol ya incluye `extensions`; PostGIS y pg_trgm están en ese esquema y las consultas de geometría responden.
- Flyway aplicó V1 a V11 (11 migraciones, 18 tablas, todas con éxito) en unos 10 s; la aplicación arrancó en unos 30 s.
- `/api/estado` devolvió `baseDatos: OK`; la página, `/api/config/mapa` y `/api/rutas` respondieron 200.
- Tamaño de la base tras migrar, sin datos: **19,2 MB de 500 MB (3,8 %)**.

**Ensayo adicional** con una base PostGIS 17 desechable de Docker que imita a Supabase: sin `extensions` en el `search_path`, V1 falla con `type "geometry" does not exist` y Flyway revierte sin dejar la base a medias; el script lo detecta y muestra la solución (sección 6).

**Con las importaciones encendidas** (`-ConImportaciones`, mismo proyecto, 8 de noviembre de 2026): OpenStreetMap importó 1.851 lugares y 84 direcciones (unos 3 min) y el catastro importó 100.371 direcciones de 104.236 leídas (3.865 descartadas, unos 8 min), sin errores. El tamaño se estabilizó en **85,9 MB de 500 MB (17,2 %)**: `direcciones` 65 MB (≈100.450 filas), `pois` 1,7 MB, `barrios` 0,4 MB. Queda amplio margen: no hace falta apagar las importaciones.

**Sigue sin verificarse:** el crecimiento tras los refrescos periódicos (`DIRECCIONES_REFRESCO_DIAS` y `OSM_REFRESCO_DIAS`, cada 30 días: comprueba el tamaño con `informe.ps1` después del primer refresco), tus propias rutas y puntos guardados (se suman a esos 86 MB), la latencia desde el servidor de producción y el traslado de tus rutas (sección 5).
