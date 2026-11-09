# Prueba COMPLETA de SEVB contra una base en Supabase (o cualquier PostgreSQL/PostGIS vacio), de principio a fin:
#   1) conecta y revisa PostGIS/extensiones ANTES de migrar,  2) arranca la aplicacion (Flyway V1..V11),
#   3) comprueba /api/estado y la pagina,  4) revisa la base DESPUES (tablas, historial de Flyway, geometria, tamano),  5) detiene la aplicacion.
# La contrasena se pide aqui (no se ve al escribirla), vive solo en las variables de ESTE proceso y nunca se escribe en archivos ni en pantalla.
# Uso:   .\scripts\supabase\probar-supabase.ps1                       (importaciones de direcciones/lugares APAGADAS: base limpia, solo migraciones)
#        .\scripts\supabase\probar-supabase.ps1 -ConImportaciones      (como en produccion: importa direcciones y lugares y mide cuanto pesa)
# Resultado: pantalla + target\supabase-informe.txt (sin secretos).
param(
    [string]$DbUrl = 'jdbc:postgresql://aws-0-us-east-1.pooler.supabase.com:5432/postgres?sslmode=require',
    [string]$DbUser = 'postgres.xbsrvlvcxxmxaeixhlal',
    [switch]$ConImportaciones,
    [int]$EsperaSegundos = 420,
    [int]$Puerto = 8080
)
$ErrorActionPreference = 'Stop'
Set-Location -LiteralPath (Join-Path $PSScriptRoot '..\..')
New-Item -ItemType Directory -Force -Path 'target' | Out-Null
$informe = 'target\supabase-informe.txt'
$logApp = 'target\supabase-arranque.log'
$logErr = 'target\supabase-arranque.err.log'
Set-Content -LiteralPath $informe -Value "Informe de prueba contra la base de datos - $(Get-Date -Format 'yyyy-MM-dd HH:mm:ss')" -Encoding UTF8

# --- contrasena: de la sesion o pedida aqui, nunca escrita ---
. (Join-Path $PSScriptRoot '_clave.ps1')
$clave = Obtener-Clave
if ([string]::IsNullOrWhiteSpace($clave)) { throw 'Sin contrasena no se puede continuar.' }
$env:DB_URL = $DbUrl; $env:DB_USER = $DbUser; $env:DB_PASSWORD = $clave
if (-not $ConImportaciones) { $env:DIRECCIONES_IMPORT_AL_ARRANCAR = 'false'; $env:OSM_IMPORT_AL_ARRANCAR = 'false' }
$env:DEMO_DATA = 'false'

function Limpiar([string]$t) { if ($null -eq $t) { return '' }; $t.Replace($clave, '***') }
function Decir([string]$t) { $t = Limpiar $t; Write-Host $t; Add-Content -LiteralPath $informe -Value $t -Encoding UTF8 }
function Titulo([string]$t) { Decir ''; Decir ('=' * 8 + ' ' + $t) }
function Informe-Base([string]$momento) {
    Titulo "Base de datos $momento"
    $salida = & (Join-Path $PSScriptRoot 'informe.ps1') 2>&1 | ForEach-Object { "$_" }
    $codigo = $LASTEXITCODE
    $salida | ForEach-Object { Decir $_ }
    return $codigo
}
function Detener-App($proc) {
    if ($proc -and -not $proc.HasExited) { & taskkill /PID $proc.Id /T /F 2>&1 | Out-Null }
    Get-NetTCPConnection -LocalPort $Puerto -State Listen -ErrorAction SilentlyContinue | ForEach-Object { try { Stop-Process -Id $_.OwningProcess -Force -ErrorAction SilentlyContinue } catch {} }
}

Titulo 'Datos de la prueba'
$hostBd = if ($DbUrl -match '//([^:/]+)') { $Matches[1] } else { '?' }
Decir "Servidor: $hostBd  | usuario: $DbUser  | importaciones al arrancar: $(if ($ConImportaciones) { 'ENCENDIDAS' } else { 'apagadas' })"
if ($DbUrl -notmatch 'sslmode=(require|verify)') { Decir 'AVISO: la URL no exige SSL (?sslmode=require); Supabase lo necesita.' }
if (Get-NetTCPConnection -LocalPort $Puerto -State Listen -ErrorAction SilentlyContinue) { throw "El puerto $Puerto ya esta en uso: cierra la otra instancia de SEVB antes de probar." }

# --- 1) antes de migrar ---
$c1 = Informe-Base 'ANTES de migrar'
if ($c1 -ne 0) {
    Decir ''
    Decir 'No se pudo consultar la base. Causas habituales:'
    Decir ' - "Tenant or user not found": el servidor del Session pooler es otro (los proyectos nuevos usan aws-1-... en vez de aws-0-...). Copia el host exacto de Connect > Session pooler y pasalo con -DbUrl.'
    Decir ' - "password authentication failed": contrasena incorrecta (resetea la de la base en Supabase > Database > Settings).'
    Decir ' - Tiempo agotado: red o cortafuegos bloqueando el puerto 5432.'
    exit 1
}

# --- 2) arrancar la aplicacion (Flyway) ---
Titulo 'Arranque de la aplicacion (Flyway V1 a V11)'
Remove-Item -LiteralPath $logApp, $logErr -ErrorAction SilentlyContinue
$proc = Start-Process -FilePath 'powershell.exe' -ArgumentList @('-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', ('"' + (Join-Path $PWD 'start.ps1') + '"'), '-NoDatabase') `
    -WorkingDirectory $PWD -PassThru -WindowStyle Hidden -RedirectStandardOutput $logApp -RedirectStandardError $logErr
$listo = $false; $limite = (Get-Date).AddSeconds($EsperaSegundos); $estado = $null
try {
    while ((Get-Date) -lt $limite) {
        Start-Sleep -Seconds 3
        if ($proc.HasExited) { break }
        try { $r = Invoke-WebRequest -Uri "http://127.0.0.1:$Puerto/api/estado" -UseBasicParsing -TimeoutSec 4; if ($r.StatusCode -eq 200) { $listo = $true; $estado = $r.Content; break } } catch { }
    }
    $lineas = @(); foreach ($f in $logApp, $logErr) { if (Test-Path $f) { $lineas += Get-Content -LiteralPath $f -ErrorAction SilentlyContinue } }
    $clave_ = $lineas | Where-Object { $_ -match 'Flyway|Migrating|migrat|Successfully applied|Schema .* version|geometry|PostGIS|ERROR|Caused by|APPLICATION FAILED|Started |HikariPool|FATAL' }
    Decir '--- Lineas relevantes del registro (contrasena enmascarada) ---'
    $clave_ | Select-Object -First 60 | ForEach-Object { Decir $_ }
    if (-not $listo -and ($lineas -match 'type "geometry" does not exist')) {
        Decir ''
        Decir 'DIAGNOSTICO: PostGIS esta instalado en el esquema "extensions" y la conexion no lo ve (search_path sin "extensions"). Flyway revirtio V1 sin dejar nada a medias.'
        Decir 'SOLUCION MINIMA (una sola vez, en Supabase > SQL Editor):  ALTER ROLE postgres SET search_path = "$user", public, extensions;   y vuelve a ejecutar esta prueba.'
    }
    if (-not $listo -and ($lineas -match 'permission denied|must be owner|must be superuser')) {
        Decir ''
        Decir 'DIAGNOSTICO: el usuario no tiene permisos para crear objetos o extensiones. Activa postgis y pg_trgm desde Database > Extensions y usa el usuario postgres.<id-del-proyecto>.'
    }
    if (-not $listo) {
        Decir ''
        Decir "La aplicacion NO respondio en $EsperaSegundos s (o se cerro). Ultimas 25 lineas del registro:"
        $lineas | Select-Object -Last 25 | ForEach-Object { Decir $_ }
    } else {
        # --- 3) estado y pagina ---
        Titulo 'Comprobaciones HTTP'
        Decir "GET /api/estado -> 200"; Decir (Limpiar $estado)
        $pag = Invoke-WebRequest -Uri "http://127.0.0.1:$Puerto/" -UseBasicParsing -TimeoutSec 20
        $titulo = if ($pag.Content -match '<title>([^<]*)</title>') { $Matches[1] } else { '(sin titulo)' }
        Decir "GET /  -> $($pag.StatusCode)  titulo: $titulo  tamano: $($pag.Content.Length) bytes"
        foreach ($ruta in '/api/config/mapa', '/api/rutas') {
            try { $x = Invoke-WebRequest -Uri "http://127.0.0.1:$Puerto$ruta" -UseBasicParsing -TimeoutSec 20; Decir "GET $ruta -> $($x.StatusCode)  ($($x.Content.Length) bytes)" }
            catch { Decir "GET $ruta -> FALLO: $($_.Exception.Message)" }
        }
        if ($ConImportaciones) {
            Decir ''
            Decir 'Importaciones encendidas: se deja la aplicacion unos minutos y se mide el tamano cada 60 s (se detiene si pasa de 400 MB).'
            $fin = (Get-Date).AddMinutes(10)
            while ((Get-Date) -lt $fin) {
                Start-Sleep -Seconds 60
                $o = & (Join-Path $PSScriptRoot 'informe.ps1') 2>&1 | ForEach-Object { "$_" }
                $linea = $o | Where-Object { $_ -match 'MB de 500' } | Select-Object -First 1
                Decir "$(Get-Date -Format 'HH:mm:ss')  $linea"
                if ($linea -match '^([\d.,]+) MB' -and [double]($Matches[1] -replace ',', '.') -ge 400) { Decir 'Se detiene la prueba: la base ya pasa de 400 MB.'; break }
            }
        }
    }
} finally {
    Detener-App $proc
}

# --- 4) despues de migrar ---
Start-Sleep -Seconds 2
$c2 = Informe-Base 'DESPUES de migrar'
Titulo 'Resumen'
Decir "Aplicacion respondio: $(if ($listo) { 'SI' } else { 'NO' })   |   consulta posterior a la base: $(if ($c2 -eq 0) { 'ok' } else { 'fallo' })"
Decir "Informe guardado en: $informe  (no contiene la contrasena)"
exit $(if ($listo -and $c2 -eq 0) { 0 } else { 1 })
