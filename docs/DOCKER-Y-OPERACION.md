# Docker y operación · SEVB 4.13.2

## Archivos

| Archivo | Para qué |
|---|---|
| `docker-compose.yml` | Solo PostGIS para desarrollo (puerto `127.0.0.1:5432`). Lo usa `start.ps1`. |
| `compose.app.yml` | Aplicación en Docker **local**; se combina con el anterior: `docker compose -f docker-compose.yml -f compose.app.yml up -d --build`. Publica `127.0.0.1:8080`. Carga datos DEMO salvo `DEMO_DATA=false`. |
| `compose.prod.yml` | Producción: Caddy (HTTPS) → app → PostGIS. **Solo Caddy publica puertos**; PostgreSQL no tiene `ports:`. `DEMO_DATA` fijo en `false`. |
| `Dockerfile` | Construye con `maven:3.9.11-eclipse-temurin-21` y ejecuta en `eclipse-temurin:21.0.8_9-jre-noble` como usuario `spevb` (no root), con `HEALTHCHECK` sobre `/api/estado`. |

Versiones de imagen fijas: `postgis/postgis:17-3.5`, `caddy:2.10-alpine`, las dos de Temurin/Maven anteriores. Para actualizar una imagen: cambia la etiqueta, `docker compose … pull` (o `build --pull`), prueba en local y despliega; si algo falla, vuelve a la etiqueta anterior.

## Comprobaciones de salud

- Aplicación: `HEALTHCHECK` del `Dockerfile` (cada 30 s, 120 s de gracia al arrancar; consulta también la base). `docker compose ps` debe mostrar `healthy`.
- PostGIS: `pg_isready` (ya estaba).
- Desde fuera: `https://tu-dominio/api/estado` → `version` `4.13.2`.

## Seguridad del contenedor

- Usuario no privilegiado `spevb` (`docker exec <app> id` debe mostrar uid distinto de 0).
- La imagen se construye solo con `pom.xml` y `src/`: no contiene `.env`, claves, `.idea/`, `.claude/`, respaldos ni documentos de desarrollo (y `.dockerignore` los excluye además). Comprobación:
  ```bash
  docker run --rm --entrypoint sh <imagen> -c "id; ls -a /app; find / -name '.env*' -not -path '/proc/*' 2>/dev/null"
  ```
- Las variables sensibles entran por entorno en tiempo de ejecución, nunca en la imagen.

## Actualizar

1. Respaldo y verificación: `deploy/backup.sh` + `deploy/verify-restore.sh` (o los `.ps1`).
2. Actualiza el código y `docker compose -f compose.prod.yml up -d --build`.
3. Flyway aplica las migraciones pendientes al arrancar (ver `docs/MIGRACIONES.md`).
4. Comprueba `docker compose ps` (healthy) y `/api/estado`.

## Rollback

1. `docker compose -f compose.prod.yml stop app`.
2. Si la versión nueva cambió el esquema de forma incompatible: restaura el respaldo (`deploy/restore.sh`). **Flyway no revierte migraciones.** Las migraciones de 4.9.6 no son destructivas.
3. Despliega el código/imagen anterior y comprueba.

Nunca uses `docker compose down -v` (borra los volúmenes `pgdata` y `app_data`).

## Producción: lista de comprobación sin desplegar

```bash
docker compose -f compose.prod.yml --env-file .env config -q      # valida sintaxis y variables obligatorias (DB_PASSWORD, DOMINIO)
docker compose -f compose.prod.yml config | grep -n "ports:"      # solo debe aparecer Caddy (80, 443)
docker compose -f compose.prod.yml config | grep -n "DEMO_DATA"   # "false"
```
