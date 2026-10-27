#!/usr/bin/env bash
# Copia diaria de la base (pg_dump comprimido) y borrado de las de más de 14 días.
# Programar:  crontab -e  →  15 3 * * * /opt/spevb/deploy/backup.sh >> /var/log/spevb-backup.log 2>&1
set -euo pipefail
DIR="$(cd "$(dirname "$0")/.." && pwd)/backups"
mkdir -p "$DIR"
ARCHIVO="$DIR/spevb-$(date +%Y%m%d-%H%M%S).sql.gz"
docker exec spevb-postgis pg_dump -U spevb -d spevb --clean --if-exists --no-owner --no-privileges | gzip > "$ARCHIVO"
[ -s "$ARCHIVO" ] || { echo "Copia vacía: $ARCHIVO" >&2; rm -f "$ARCHIVO"; exit 1; }
find "$DIR" -name 'spevb-*.sql.gz' -mtime +14 -delete
echo "OK $ARCHIVO ($(du -h "$ARCHIVO" | cut -f1))"
