#!/usr/bin/env bash
# Comprueba que una copia SE PUEDE RESTAURAR en un contenedor PostGIS TEMPORAL (no toca la base real). Imprime solo conteos.
#   ./deploy/verify-restore.sh backups/spevb-AAAAMMDD-HHMMSS.sql.gz[.enc]
set -euo pipefail
umask 077
[ $# -eq 1 ] && [ -f "$1" ] || { echo "Uso: $0 archivo.sql.gz[.enc]" >&2; exit 1; }
NOMBRE="sevb-verify-$$"; CLAVE="$(head -c 16 /dev/urandom | od -An -tx1 | tr -d ' \n')"
limpiar() { docker rm -f "$NOMBRE" >/dev/null 2>&1 || true; }; trap limpiar EXIT
if [ -n "${BACKUP_PASSPHRASE_FILE:-}" ]; then export BACKUP_PASSPHRASE="$(cat "$BACKUP_PASSPHRASE_FILE")"; fi
docker run -d --rm --name "$NOMBRE" -e POSTGRES_DB=spevb -e POSTGRES_USER=spevb -e POSTGRES_PASSWORD="$CLAVE" postgis/postgis:17-3.5 >/dev/null
for _ in $(seq 1 60); do docker exec "$NOMBRE" psql -U spevb -d spevb -tAc 'select 1' >/dev/null 2>&1 && break; sleep 2; done
if [[ "$1" == *.enc ]]; then
  [ -n "${BACKUP_PASSPHRASE:-}" ] || { echo "Respaldo cifrado: define BACKUP_PASSPHRASE." >&2; exit 1; }
  openssl enc -d -aes-256-cbc -pbkdf2 -iter 600000 -in "$1" -pass env:BACKUP_PASSPHRASE | gunzip -c
else gunzip -c "$1"; fi | docker exec -i "$NOMBRE" psql -U spevb -d spevb -v ON_ERROR_STOP=1 -q >/dev/null
docker exec "$NOMBRE" psql -U spevb -d spevb -tA -F ' = ' -c "select 'rutas',count(*) from rutas union all select 'paraderos',count(*) from paraderos union all select 'lugares',count(*) from lugares union all select 'usuarios',count(*) from usuarios union all select 'reportes',count(*) from reportes union all select 'pasos_peatonales',count(*) from pasos_peatonales order by 1"
[ "$(docker exec "$NOMBRE" psql -U spevb -d spevb -tAc "select count(*) from pg_extension where extname='postgis'")" = "1" ] || { echo "FALLA: PostGIS no quedó activo" >&2; exit 1; }
echo "COMPROBACIÓN CORRECTA: la copia se restaura en un entorno temporal."
