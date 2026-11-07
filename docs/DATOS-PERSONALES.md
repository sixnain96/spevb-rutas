# Datos personales, retención y borrado · SEVB 4.13.1

Este documento describe lo que el sistema **ya conserva** hoy. No añade funciones nuevas. Si alguien administra una instalación de SEVB, es responsable de informar a sus usuarias y usuarios y de cumplir la normativa aplicable (en Colombia, la Ley 1581 de 2012 de protección de datos personales).

> **Origen, destino e historial de viajes pueden ser datos sensibles.** Revelan dónde vive una persona, dónde trabaja o estudia, sus horarios y sus rutinas. Trátalos con el mismo cuidado que una contraseña: no los compartas, no los pegues en reportes públicos y protege los respaldos.

## Qué se conserva y dónde

| Dato | Dónde | Quién lo ve | Retención actual |
|---|---|---|---|
| Correo, nombre, hash PBKDF2 de la clave (nunca la clave), `google_sub`, último acceso | tabla `usuarios` | solo el servidor (el correo de administración también en `ADMIN_CORREOS`) | hasta que la persona elimine su cuenta |
| Sesiones: hash del token (no el token), navegador (`agente`), fechas | tabla `sesiones` | solo el servidor | **30 días** desde la última vez que se usó; se borran las vencidas al iniciar otra sesión; máximo **10** sesiones por cuenta |
| Lugares favoritos, viajes guardados e historial (origen y destino con coordenadas y nombres) | tabla `usuario_datos` (JSONB), solo con cuenta | solo la persona dueña | hasta que la persona lo borre o elimine su cuenta; el historial visible se limita a las últimas entradas |
| Los mismos datos **sin cuenta** | `localStorage` del navegador (`spevb-favoritos`, `spevb-recientes`, `spevb-caminatas`, historial y guardados; durante un viaje, `spevb-viaje-curso` con el recorrido y la fase —**nunca la posición**— hasta 3 h) | solo ese navegador | hasta que se borre el historial o los datos del sitio; recientes ≤ 6, caminatas ≤ 40 |
| Reportes: mensaje, ruta, **coordenadas de origen y destino**, contacto opcional, navegador | tabla `reportes` | administración (`/reportes.html`) | **sin plazo automático**: se conservan hasta que administración los borre |
| Confirmaciones de dirección (clave de dirección + punto) | `direccion_confirmaciones` | solo el servidor | sin plazo automático; **no** llevan identificador de la persona |
| Búsquedas sin resultado (texto) | `busquedas_sin_resultado` | administración | sin plazo automático; puede contener direcciones escritas por personas |
| Copia de mapas y rutas vistas | Cache Storage del navegador (service worker) | solo ese navegador | las teselas se podan a 1.500; la app a 120 entradas; **las URL de viajes compartidos ya no se guardan** (4.9.6) |
| Respaldos de la base | `backups/` o donde los pongas | quien administre el servidor | ver «Respaldos» |

El servidor **no** guarda direcciones IP en tablas. Los registros (logs) de la aplicación no incluyen coordenadas, tokens ni correos completos desde la 4.9.6 (`LogPrivado` enmascara correos como `p***@dominio`, pares de coordenadas y tokens largos en los mensajes de error). El proxy (Caddy) y Docker pueden tener sus propios registros de acceso con IP: configúralos según tu política.

## Terceros que reciben datos al usar la aplicación

- **Nominatim/OpenStreetMap, OSRM, Overpass, ArcGIS (si hay `ARCGIS_API_KEY`), catastro municipal:** reciben el texto buscado o las coordenadas consultadas, desde el servidor. Consulta sus políticas.
- **Google (solo si `GOOGLE_CLIENT_ID` está definido):** el botón de inicio de sesión.
- **Teselas del mapa (OpenFreeMap, OpenStreetMap, Esri):** el navegador las pide directamente y por ello esos servicios ven la zona que miras y la IP del navegador.

## Cómo eliminar datos

**Una persona (con cuenta):** menú → *Ajustes de la cuenta* → *Eliminar mi cuenta* (pide la contraseña). Borra la fila de `usuarios` y, en cascada, sus `sesiones` y `usuario_datos`. *Borrar historial* y los botones de cada lugar o viaje borran esas entradas.

**Una persona (sin cuenta):** *Borrar historial* en la página, o borrar los datos del sitio en el navegador (también vacía Cache Storage).

**Administración, a mano y con respaldo previo** (no hay borrado automático de datos reales):

```sql
-- una persona por correo
DELETE FROM usuarios WHERE lower(correo) = lower('persona@example.org');      -- sesiones y usuario_datos se borran en cascada
-- reportes antiguos (ejemplo: más de 365 días)
DELETE FROM reportes WHERE creado_en < NOW() - INTERVAL '365 days';
-- búsquedas sin resultado
TRUNCATE busquedas_sin_resultado;
```

Los respaldos ya hechos **siguen conteniendo** lo que borraste: bórralos o déjalos vencer según la política de retención.

## Tiempo de retención recomendado (para quien administre)

SEVB no impone plazos que no existían antes (no borra datos reales sola). Se recomienda documentar y aplicar: reportes ≤ 12 meses; respaldos diarios ≤ 14 días (la rotación de `deploy/backup.sh` ya borra a los 14 días; `RETENCION_DIAS=0` la apaga); cuentas inactivas según tu política. La retención de sesiones (30 días) y su máximo (10) sí están en el código.

## Respaldos

- `backup.ps1` y `deploy/backup.sh` crean los archivos con acceso **solo para el propietario** (ACL restringida en Windows, `umask 077` → 600/700 en Linux).
- **Cifrado opcional** (recomendado para producción o cualquier copia que salga del servidor): `backup.ps1 -Encrypt` o `BACKUP_PASSPHRASE=… deploy/backup.sh` (AES-256-CBC, PBKDF2 600.000 iteraciones, vía `openssl`). La frase **no** se guarda en el repositorio; usa `BACKUP_PASSPHRASE_FILE` con permisos 600 en el servidor.
- **Comprobar que se puede restaurar**, en un contenedor temporal que no toca la base real: `scripts\verify-restore.ps1 <archivo>` o `deploy/verify-restore.sh <archivo>` (imprime solo conteos).
- Los respaldos **nunca** entran al ZIP de entrega, a Git ni a la imagen de Docker (`.gitignore`, `.dockerignore`, `scripts/package-release.ps1`).
- Guarda una copia fuera del servidor y pruébala; si el servidor se pierde, se pierden también las copias locales.

## Credenciales expuestas

Si un ZIP o un `.env` con credenciales se compartió, **hay que rotar esas credenciales fuera de este proyecto** (contraseña de la base, `EDITOR_TOKEN`, `ARCGIS_API_KEY`, cualquier otra) aunque el archivo ya se haya borrado: ver `docs/CAMBIAR-CLAVES.md`. El paquete de 4.9.6 se genera sin `.env`.

## Modo viaje (4.11.0)
La posición del GPS se procesa **solo en el teléfono**: no se envía al servidor ni se guarda. Mientras dura un viaje se conserva en `localStorage` (`spevb-viaje-curso`) únicamente el recorrido elegido y la fase en que iba la persona, para poder reanudar tras recargar la página; se borra al llegar, al terminar el viaje o a las 3 horas.
