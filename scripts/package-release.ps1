<#
.SYNOPSIS
  Crea el paquete de entrega SEVB-<versión>.zip de forma reproducible y SEGURA, y lo inspecciona antes de darlo por bueno.

.DESCRIPTION
  - Parte de una lista BLANCA de carpetas/archivos (no de «todo menos lo prohibido») y además aplica una lista NEGRA dentro de ellos.
  - Nunca incluye: .env, .env.*, .idea/, .claude/, target/, data/, backups, logs/, ZIP anidados, bases locales, claves privadas,
    configuración del equipo (settings.local.json) ni temporales. NO borra ni modifica nada de tu carpeta de trabajo (tu .env local sigue ahí).
  - Después de crear el ZIP lo abre y FALLA (exit 1, y borra el ZIP malo) si encuentra algo prohibido o un secreto conocido.
    Los secretos NUNCA se imprimen: solo se informa el nombre de la variable o del patrón y el archivo del paquete.
  - La versión sale de pom.xml (única fuente). El ZIP se llama SEVB-<versión>.zip y su carpeta raíz SEVB-<versión>/.

.PARAMETER OutDir   Carpeta de salida (por defecto dist/ dentro del proyecto; está en .gitignore).
.PARAMETER Verify   Solo inspecciona un ZIP existente (ruta) y no crea nada.
.EXAMPLE
  .\scripts\package-release.ps1
  .\scripts\package-release.ps1 -Verify .\dist\SEVB-4.13.1.zip
#>
param(
    [string]$OutDir,
    [string]$Verify
)
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.IO.Compression
Add-Type -AssemblyName System.IO.Compression.FileSystem
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path

# ------------------------------------------------------------------ qué entra
$IncluirCarpetas = @('src', 'deploy', 'docs', 'design', 'e2e', 'scripts', 'branding-originales')
$IncluirArchivos = @('pom.xml', 'Dockerfile', '.dockerignore', '.gitignore', '.editorconfig', 'docker-compose.yml', 'compose.app.yml', 'compose.prod.yml',
    '.env.example', 'README.md', 'CHANGELOG.md', 'CONTINUIDAD.md', 'ESTANDARES.md', 'LEEME_PRIMERO.txt', 'LICENSE', 'LICENSE.md', 'LICENCIA.md',
    'start.ps1', 'backup.ps1', 'restore.ps1', 'diagnostico.ps1', 'requests.http')

# ------------------------------------------------------------------ qué NUNCA entra (se evalúa sobre la ruta relativa con «/»)
$Prohibido = @(
    '(^|/)\.env$', '(^|/)\.env\.(?!example$)[^/]*$', '(^|/)\.idea(/|$)', '(^|/)\.claude(/|$)', '(^|/)settings\.local\.json$',
    '(^|/)target[^/]*(/|$)', '(^|/)dist(/|$)', '(^|/)data(/|$)', '(^|/)backups?(/|$)', '(^|/)logs?(/|$)', '\.log$',
    '\.zip$', '\.7z$', '\.rar$', '\.tar(\.gz)?$', '\.tgz$', '\.sql\.gz$', '\.dump$', '\.bak$', '\.sqlite3?$', '\.db$', '\.mv\.db$',
    '\.(pem|key|p12|pfx|jks|keystore)$', '(^|/)id_(rsa|dsa|ecdsa|ed25519)', '\.(tmp|temp|swp|swo)$', '~$', '(^|/)__pycache__(/|$)', '\.pyc$',
    '(^|/)(Thumbs\.db|desktop\.ini|\.DS_Store)$', '(^|/)node_modules(/|$)', '(^|/)\.git(/|$)', '(^|/)hs_err_pid', '\.iml$')

# Los respaldos históricos de las fases (design/*/backup/**) siguen en el repositorio de trabajo, pero NO entran al paquete generado.

function Test-Prohibido([string]$rel) {
    foreach ($patron in $Prohibido) { if ($rel -match $patron) { return $true } }
    return $false
}

# ------------------------------------------------------------------ inspección del ZIP
$PatronesSecretos = @(
    @{ N = 'clave privada PEM';        R = '-----BEGIN (RSA |EC |DSA |OPENSSH |ENCRYPTED |PGP )?PRIVATE KEY' },
    @{ N = 'clave de acceso AWS';      R = '\bAKIA[0-9A-Z]{16}\b' },
    @{ N = 'token de GitHub';          R = '\bgh[pousr]_[A-Za-z0-9]{30,}\b' },
    @{ N = 'token de Slack';           R = '\bxox[abprs]-[A-Za-z0-9-]{10,}' },
    @{ N = 'clave de API de Google';   R = '\bAIza[0-9A-Za-z_\-]{35}\b' },
    @{ N = 'clave de ArcGIS';          R = '\bAA(PK|PT)[A-Za-z0-9_\-\.]{30,}' },
    @{ N = 'JWT';                      R = '\beyJ[A-Za-z0-9_\-]{20,}\.eyJ[A-Za-z0-9_\-]{10,}\.' },
    @{ N = 'asignación de secreto con valor real'; R = '(?im)^\s*(DB_PASSWORD|EDITOR_TOKEN|ARCGIS_API_KEY|POSTGRES_PASSWORD|[A-Z_]*SECRET|[A-Z_]*API_KEY)\s*[:=]\s*(?!CAMBIA|INTRODUCE|TU_|<|\$\{|""|''|\s*$)["'']?[A-Za-z0-9+/=_\-]{20,}' }
)
$ExtensionesDeTexto = '\.(properties|ya?ml|env|example|ps1|sh|md|txt|json|xml|java|js|py|sql|http|html|css|webmanifest|toml|conf|cfg|gitignore|dockerignore|editorconfig)$|(^|/)(Dockerfile|Caddyfile)$'

function Get-SecretosConocidos {
    # Valores del .env LOCAL del desarrollador que no deben aparecer en ningún archivo de texto del paquete. Nunca se imprimen.
    $conocidos = @()
    $envLocal = Join-Path $root '.env'
    if (-not (Test-Path -LiteralPath $envLocal)) { return $conocidos }
    foreach ($linea in Get-Content -LiteralPath $envLocal) {
        if ($linea -match '^\s*([A-Z][A-Z0-9_]*)\s*=\s*(.*?)\s*$') {
            $nombre = $Matches[1]; $valor = $Matches[2].Trim('"').Trim("'")
            $sensible = ($nombre -match 'PASSWORD|TOKEN|SECRET|KEY|CORREOS|CLAVE') -and ($nombre -notmatch 'GOOGLE_CLIENT_ID')
            if ($sensible -and $valor.Length -ge 6 -and $valor -notmatch '^(CAMBIA|INTRODUCE|TU_)') { $conocidos += [pscustomobject]@{ Nombre = $nombre; Valor = $valor } }
        }
    }
    return $conocidos
}

function Test-Paquete([string]$ruta) {
    $problemas = New-Object System.Collections.Generic.List[string]
    $conocidos = Get-SecretosConocidos
    $zip = [System.IO.Compression.ZipFile]::OpenRead($ruta)
    try {
        $total = 0
        foreach ($e in $zip.Entries) {
            if ($e.FullName.EndsWith('/')) { continue }
            $total++
            # Quita la carpeta raíz SEVB-x.y.z/ para evaluar la ruta relativa.
            $rel = ($e.FullName -replace '^[^/]+/', '')
            if (Test-Prohibido $rel) { $problemas.Add("archivo prohibido en el paquete: $rel") ; continue }
            if ($rel -match '(^|/)(\.env|settings\.local\.json)$' -or $rel -match '(^|/)(target|\.idea)/') { $problemas.Add("archivo prohibido en el paquete: $rel") }
            if ($rel -match $ExtensionesDeTexto -and $e.Length -lt 3MB) {
                $lector = New-Object System.IO.StreamReader($e.Open(), [System.Text.Encoding]::UTF8)
                try { $texto = $lector.ReadToEnd() } finally { $lector.Dispose() }
                foreach ($p in $PatronesSecretos) {
                    if ([regex]::IsMatch($texto, $p.R)) { $problemas.Add("posible $($p.N) en: $rel") }
                }
                foreach ($c in $conocidos) {
                    if ($texto.Contains($c.Valor)) { $problemas.Add("el valor de $($c.Nombre) (del .env local) aparece en: $rel") }
                }
            }
        }
        foreach ($necesario in @('pom.xml', 'src/', '.env.example', 'Dockerfile', 'README.md', 'start.ps1')) {
            if (-not ($zip.Entries | Where-Object { ($_.FullName -replace '^[^/]+/', '') -like "$necesario*" })) { $problemas.Add("falta en el paquete: $necesario") }
        }
    } finally { $zip.Dispose() }
    return [pscustomobject]@{ Problemas = $problemas; Archivos = $total }
}

if ($Verify) {
    $r = Test-Paquete (Resolve-Path -LiteralPath $Verify).Path
    if ($r.Problemas.Count) { $r.Problemas | ForEach-Object { Write-Host "FALLA: $_" -ForegroundColor Red }; exit 1 }
    Write-Host "Inspección correcta: $($r.Archivos) archivos, sin .env, target/, .idea/, settings.local.json ni secretos conocidos." -ForegroundColor Green
    exit 0
}

# ------------------------------------------------------------------ construcción
$pom = Get-Content -LiteralPath (Join-Path $root 'pom.xml') -Raw
$m = [regex]::Match($pom, '<artifactId>spevb-rutas</artifactId>\s*<version>([^<]+)</version>')
if (-not $m.Success) { throw 'No se pudo leer la versión de pom.xml.' }
$version = $m.Groups[1].Value
if (-not $OutDir) { $OutDir = Join-Path $root 'dist' }
New-Item -ItemType Directory -Force -Path $OutDir | Out-Null
$destino = Join-Path (Resolve-Path $OutDir).Path "SEVB-$version.zip"
$carpetaRaiz = "SEVB-$version"
if (Test-Path -LiteralPath $destino) { Remove-Item -LiteralPath $destino -Force }

$archivos = New-Object System.Collections.Generic.List[string]
foreach ($c in $IncluirCarpetas) {
    $dir = Join-Path $root $c
    if (-not (Test-Path -LiteralPath $dir)) { continue }
    Get-ChildItem -LiteralPath $dir -Recurse -File -Force | ForEach-Object { $archivos.Add($_.FullName) }
}
foreach ($a in $IncluirArchivos) {
    $p = Join-Path $root $a
    if (Test-Path -LiteralPath $p -PathType Leaf) { $archivos.Add((Resolve-Path -LiteralPath $p).Path) }
}

$omitidos = 0; $incluidos = 0
$fs = [System.IO.File]::Open($destino, [System.IO.FileMode]::CreateNew)
$zip = New-Object System.IO.Compression.ZipArchive($fs, [System.IO.Compression.ZipArchiveMode]::Create)
try {
    foreach ($f in ($archivos | Sort-Object -Unique)) {
        $rel = $f.Substring($root.Length).TrimStart('\', '/').Replace('\', '/')
        if (Test-Prohibido $rel) { $omitidos++; continue }
        $entrada = $zip.CreateEntry("$carpetaRaiz/$rel", [System.IO.Compression.CompressionLevel]::Optimal)
        $entrada.LastWriteTime = [DateTimeOffset]::new(2000, 1, 1, 0, 0, 0, [TimeSpan]::Zero)   # reproducible: misma fecha en todas las entradas
        $origen = [System.IO.File]::OpenRead($f)
        try { $salida = $entrada.Open(); try { $origen.CopyTo($salida) } finally { $salida.Dispose() } } finally { $origen.Dispose() }
        $incluidos++
    }
} finally { $zip.Dispose(); $fs.Dispose() }

$r = Test-Paquete $destino
if ($r.Problemas.Count) {
    Remove-Item -LiteralPath $destino -Force
    $r.Problemas | ForEach-Object { Write-Host "FALLA: $_" -ForegroundColor Red }
    Write-Host 'El paquete NO se entrega: se eliminó el ZIP defectuoso.' -ForegroundColor Red
    exit 1
}
$hash = (Get-FileHash -LiteralPath $destino -Algorithm SHA256).Hash.ToLower()
Set-Content -LiteralPath "$destino.sha256" -Value "$hash  $(Split-Path $destino -Leaf)" -Encoding ascii
$tam = [math]::Round((Get-Item -LiteralPath $destino).Length / 1MB, 2)
Write-Host "Paquete creado: $destino ($tam MB, $incluidos archivos; $omitidos omitidos por la lista negra)" -ForegroundColor Green
Write-Host "SHA-256: $hash"
Write-Host 'Inspección automática correcta: sin .env, target/, .idea/, settings.local.json, ZIP anidados ni secretos conocidos.' -ForegroundColor Green
Write-Host 'Recuerda: si un .env o un ZIP con credenciales se compartió antes, esas credenciales deben rotarse (docs/CAMBIAR-CLAVES.md).'
