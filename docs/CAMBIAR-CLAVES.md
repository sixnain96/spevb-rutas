# Cambiar las claves del proyecto

Las claves reales sólo van en `.env`, que está en `.gitignore`. Nunca se escriben en documentos,
código ni mensajes de commit. Si una clave quedó expuesta, reemplázala siguiendo estos pasos.

1. Genera dos valores aleatorios en PowerShell (ejecuta la línea dos veces, una para cada clave):

```powershell
$b = New-Object byte[] 24; [System.Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($b); -join ($b | ForEach-Object { $_.ToString('x2') })
```

2. Cambia la contraseña dentro de PostgreSQL. La contraseña de `.env` sólo se aplica al crear el
   volumen por primera vez, así que en una base existente hay que cambiarla con SQL:

```powershell
docker exec -it spevb-postgis psql -U spevb -d spevb -c "ALTER USER spevb PASSWORD 'NUEVA_CLAVE_DE_POSTGRES'"
```

3. Escribe la nueva contraseña en `DB_PASSWORD` y el otro valor en `EDITOR_TOKEN` dentro de `.env`.
4. Reinicia: `docker compose up -d` y luego `.\start.ps1 -NoDatabase`. Los datos se conservan.
5. Comprueba `http://localhost:8080/api/estado` y guarda algo desde el editor con la clave nueva.
