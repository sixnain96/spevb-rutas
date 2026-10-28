# Despliegue 24/7 en un servidor (VPS)

Resultado: `https://tu-dominio` funcionando siempre, sin depender de tu computador. Caddy pone el HTTPS solo (necesario para GPS, instalación como app y modo sin conexión); la base PostGIS no es accesible desde internet.

## 1. Qué contratar
- **VPS** con 2 vCPU y **4 GB de RAM** (mínimo 2 GB), Ubuntu 24.04, en **EE. UU. (Miami o costa este)**: es lo más cercano a Colombia. Proveedores habituales: Hetzner (Ashburn), DigitalOcean (Nueva York), Vultr (Miami). Revisa el precio vigente; suele estar entre 5 y 12 USD/mes.
- **Dominio** (≈ 10–15 USD/año). En el DNS crea un registro **A**: `rutas` → IP del servidor.

## 2. Preparar el servidor (una vez)
```bash
ssh root@IP_DEL_SERVIDOR
apt update && apt -y upgrade
curl -fsSL https://get.docker.com | sh          # instala Docker y compose
ufw allow OpenSSH && ufw allow 80 && ufw allow 443 && ufw allow 443/udp && ufw --force enable
fallocate -l 2G /swapfile && chmod 600 /swapfile && mkswap /swapfile && swapon /swapfile && echo '/swapfile none swap sw 0 0' >> /etc/fstab
```

## 3. Subir y arrancar
Sube la carpeta del proyecto (sin `.env`) a `/opt/spevb` (por ejemplo con `scp -r` o `git clone`), y:
```bash
cd /opt/spevb
cp .env.example .env && nano .env
```
En `.env` pon como mínimo:
```
DB_PASSWORD=<clave larga y nueva>
DOMINIO=rutas.midominio.com
EDITOR_ENABLED=false
```
(Genera claves con `openssl rand -hex 24`. No reutilices las del ZIP original: ya se consideran expuestas.)
```bash
docker compose -f compose.prod.yml up -d --build
docker compose -f compose.prod.yml logs -f app      # espera «Started»; la primera vez tarda unos minutos
```
Abre `https://rutas.midominio.com`. El certificado se emite solo en el primer arranque (el DNS debe estar ya apuntando al servidor).

## 4. Copias de seguridad
```bash
crontab -e     # añade:
15 3 * * * /opt/spevb/deploy/backup.sh >> /var/log/spevb-backup.log 2>&1
```
Guarda 14 días en `/opt/spevb/backups`. **Descarga una copia a tu computador de vez en cuando** (si se pierde el servidor, se pierden también las copias locales) y prueba `deploy/restore.sh` una vez.

## 5. Actualizar la aplicación
```bash
cd /opt/spevb && (sube los archivos nuevos) && docker compose -f compose.prod.yml up -d --build
```
Los datos viven en volúmenes (`pgdata`, `app_data`): **nunca uses `docker compose down -v`** (borra la base).

## 6. Comprobaciones
- `https://tu-dominio/api/estado` responde.
- `https://tu-dominio/api/caminata/estado` dice `LISTA`.
- `docker compose -f compose.prod.yml ps` muestra todo «healthy/running».
- Reinicia el servidor (`reboot`): todo vuelve solo (`restart: unless-stopped`).

## 7. Si se queda corto
- Más RAM → sube `JAVA_OPTS` (`-Xmx`) y `shared_buffers` en `compose.prod.yml`.
- La carga pesada es la red peatonal en memoria (≈ cientos de MB); la base es pequeña.
- Una base administrada (Supabase/Neon) solo conviene si la app y la base quedan en la misma región; no es necesaria para empezar.
