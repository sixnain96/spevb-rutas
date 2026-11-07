#!/usr/bin/env bash
# Copia diaria de la base (pg_dump comprimido) con acceso RESTRINGIDO (umask 077) y cifrado opcional. No borra copias automáticamente
# salvo la rotación ya existente (más de 14 días) dentro de la carpeta de respaldos; ajusta RETENCION_DIAS o pon 0 para no rotar.
#   Cifrado (recomendado en producción):  BACKUP_PASSPHRASE='frase larga' ./deploy/backup.sh      → archivo .sql.gz.enc
# Programar:  crontab -e  →  15 3 * * * BACKUP_PASSPHRASE_FILE=/root/.sevb-backup-pass /opt/spevb/deploy/backup.sh >> /var/log/spevb-backup.log 2>&1
set -euo pipefail
umask 077                                     # archivos 600 y carpetas 700: solo el propietario
DIR="${BACKUP_DIR:-$(cd "$(dirname "$0")/.." && pwd)/backups}"
RETENCION_DIAS="${RETENCION_DIAS:-14}"
mkdir -p "$DIR"; chmod 700 "$DIR"
if [ -n "${BACKUP_PASSPHRASE_FILE:-}" ]; then export BACKUP_PASSPHRASE="$(cat "$BACKUP_PASSPHRASE_FILE")"; fi
ARCHIVO="$DIR/spevb-$(date +%Y%m%d-%H%M%S).sql.gz"
LISTO=0
# Si algo falla (o se interrumpe) no queda ningún archivo parcial de ESTA ejecución.
limpiar() { if [ "$LISTO" -ne 1 ]; then rm -f "$ARCHIVO" "$ARCHIVO.enc"; fi; }
trap limpiar EXIT
docker exec spevb-postgis pg_dump -U spevb -d spevb --clean --if-exists --no-owner --no-privileges | gzip > "$ARCHIVO"
[ -s "$ARCHIVO" ] || { echo "Copia vacía: $ARCHIVO" >&2; rm -f "$ARCHIVO"; exit 1; }
if [ -n "${BACKUP_PASSPHRASE:-}" ]; then
  openssl enc -aes-256-cbc -pbkdf2 -iter 600000 -salt -in "$ARCHIVO" -out "$ARCHIVO.enc" -pass env:BACKUP_PASSPHRASE \
    || { rm -f "$ARCHIVO.enc"; LISTO=1; echo "Falló el cifrado; se conserva la copia sin cifrar." >&2; exit 1; }
  rm -f "$ARCHIVO"                            # solo la copia sin cifrar que este mismo script acaba de crear
  ARCHIVO="$ARCHIVO.enc"
else
  echo "AVISO: copia SIN cifrar. En producción define BACKUP_PASSPHRASE o BACKUP_PASSPHRASE_FILE." >&2
fi
chmod 600 "$ARCHIVO"
LISTO=1
if [ "$RETENCION_DIAS" -gt 0 ]; then find "$DIR" -name 'spevb-*.sql.gz*' -mtime +"$RETENCION_DIAS" -delete; fi
echo "OK $ARCHIVO ($(du -h "$ARCHIVO" | cut -f1))"
