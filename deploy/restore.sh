#!/usr/bin/env bash
# Restaura una copia:  ./deploy/restore.sh backups/spevb-AAAAMMDD-HHMMSS.sql.gz   (reemplaza los datos actuales)
set -euo pipefail
[ $# -eq 1 ] && [ -f "$1" ] || { echo "Uso: $0 archivo.sql.gz" >&2; exit 1; }
read -r -p "Esto reemplaza la base actual. Escribe SI para continuar: " r; [ "$r" = "SI" ] || exit 1
gunzip -c "$1" | docker exec -i spevb-postgis psql -U spevb -d spevb -v ON_ERROR_STOP=1 -q
echo "Restaurada."
