#!/usr/bin/env bash
# Restaura una copia:  ./deploy/restore.sh backups/spevb-AAAAMMDD-HHMMSS.sql.gz[.enc]   (reemplaza los datos actuales)
# Antes, compruébala con deploy/verify-restore.sh. Si está cifrada (.enc) define BACKUP_PASSPHRASE o BACKUP_PASSPHRASE_FILE.
set -euo pipefail
umask 077
# Esta restauración no crea temporales (todo va por tuberías); el trap deja constancia si se interrumpe.
trap 'echo "Restauración interrumpida o fallida: revisa la base antes de continuar." >&2' ERR
[ $# -eq 1 ] && [ -f "$1" ] || { echo "Uso: $0 archivo.sql.gz[.enc]" >&2; exit 1; }
read -r -p "Esto reemplaza la base actual. Escribe SI para continuar: " r; [ "$r" = "SI" ] || exit 1
if [ -n "${BACKUP_PASSPHRASE_FILE:-}" ]; then export BACKUP_PASSPHRASE="$(cat "$BACKUP_PASSPHRASE_FILE")"; fi
if [[ "$1" == *.enc ]]; then
  [ -n "${BACKUP_PASSPHRASE:-}" ] || { echo "Respaldo cifrado: define BACKUP_PASSPHRASE." >&2; exit 1; }
  openssl enc -d -aes-256-cbc -pbkdf2 -iter 600000 -in "$1" -pass env:BACKUP_PASSPHRASE | gunzip -c | docker exec -i spevb-postgis psql -U spevb -d spevb -v ON_ERROR_STOP=1 -q
else
  gunzip -c "$1" | docker exec -i spevb-postgis psql -U spevb -d spevb -v ON_ERROR_STOP=1 -q
fi
echo "Restaurada."
