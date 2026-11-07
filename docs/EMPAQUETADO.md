# Paquete de entrega · SEVB 4.13.3

```powershell
.\scripts\package-release.ps1                      # crea dist\SEVB-4.13.1.zip (+ .sha256) y lo inspecciona
.\scripts\package-release.ps1 -Verify dist\SEVB-4.13.1.zip   # solo inspecciona un ZIP existente
```

La versión sale de `pom.xml`. El script **no borra ni modifica nada** de tu carpeta de trabajo (tu `.env` local se queda donde está); solo lee el `.env` para saber qué valores NO deben aparecer en el paquete.

## Qué entra (lista blanca)
`src/`, `pom.xml`, `Dockerfile`, `.dockerignore`, `.gitignore`, `docker-compose.yml`, `compose.app.yml`, `compose.prod.yml`, `.env.example`, `README.md`, `CHANGELOG.md`, documentación (`docs/`, `CONTINUIDAD.md`, `ESTANDARES.md`, `LEEME_PRIMERO.txt`), pruebas (`src/test/`, `e2e/`, `docs/pruebas/`), recursos del frontend (`src/main/resources/static/`), modelo Blender y scripts (`design/`), `scripts/`, `deploy/`, `start.ps1`, `backup.ps1`, `restore.ps1`, `diagnostico.ps1`, `requests.http`, `LICENSE`/`LICENCIA.md`.

## Qué nunca entra (lista negra, aunque estuviera dentro de una carpeta permitida)
`.env` y `.env.*` (menos `.env.example`), `.idea/`, `.claude/` (incluye `settings.local.json`), `target/`, `dist/`, `data/`, respaldos (`backups/`, `*.sql.gz`, `*.dump`, `*.bak`), `logs/` y `*.log`, **cualquier ZIP/7z/rar/tar anidado**, bases locales (`*.db`, `*.sqlite`), claves y certificados (`*.pem`, `*.key`, `*.p12`, `*.jks`, `id_rsa*`), temporales (`*.tmp`, `*.swp`, `~`), `__pycache__`, `node_modules`, `.git` y los **respaldos históricos de las fases (`design/*/backup/`)**, que siguen en el proyecto de trabajo pero no viajan en el paquete.

## Inspección automática (falla el script si encuentra algo)
Después de crear el ZIP lo abre y comprueba: ninguna entrada prohibida; ninguna clave privada, token de GitHub/Slack/AWS/Google/ArcGIS, JWT ni asignación `*_PASSWORD/_TOKEN/_API_KEY/_SECRET` con valor real; **ninguno de los valores sensibles de tu `.env` local** (contraseñas, tokens, claves, correos de administración) aparece en algún archivo de texto; y que estén `pom.xml`, `src/`, `.env.example`, `Dockerfile`, `README.md` y `start.ps1`. Si algo falla, **borra el ZIP defectuoso** y sale con código 1. Los valores nunca se imprimen: solo el nombre de la variable o del patrón y el archivo.

## Reproducible
Las entradas llevan la misma fecha y el orden es alfabético; la raíz es `SEVB-4.13.3/`, con `/` como separador (válido en Linux y Windows).

## Credenciales ya compartidas
Si un ZIP anterior (por ejemplo `SEVB-V3.4-con-todas-las-mejoras.zip`) contenía `.env`, **rota esas credenciales fuera del proyecto** (base de datos, `EDITOR_TOKEN`, `ARCGIS_API_KEY`) y no reutilices ese archivo. Borrar el ZIP no las hace seguras.
