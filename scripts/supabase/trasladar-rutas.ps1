# Lleva tus RUTAS (y lo que cuelga de ellas) de la base local a Supabase, SIEMPRE a traves de una COPIA:
#   1) copia la base local en un archivo (pg_dump, solo lectura: la original no se modifica) y la restaura en un contenedor DESECHABLE;
#   2) muestra cuantas filas hay de cada tabla en esa copia;
#   3) (con contrasena) comprueba que en Supabase esas tablas estan VACIAS y que Flyway ya llego a la V11; si no, se detiene sin escribir nada;
#   4) pide que escribas SI, copia los datos desde la COPIA a Supabase en una sola transaccion, ajusta las secuencias y compara conteos.
# No toca direcciones, lugares de OpenStreetMap, barrios, cuentas, sesiones, reportes ni confirmaciones (esos no se trasladan).
# Uso:  powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\supabase\trasladar-rutas.ps1 -SoloCopia     (sin contrasena: solo crea la copia y cuenta)
#       powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\supabase\trasladar-rutas.ps1                (traslada a Supabase; pide la contrasena)
param(
    [string]$DbUrl = 'jdbc:postgresql://aws-0-us-east-1.pooler.supabase.com:5432/postgres?sslmode=require',
    [string]$DbUser = 'postgres.xbsrvlvcxxmxaeixhlal',
    [string]$ContenedorLocal = 'spevb-postgis', [string]$UsuarioLocal = 'spevb', [string]$BaseLocal = 'spevb',
    [switch]$SoloCopia, [switch]$ConservarCopia,
    [switch]$SinPreguntar          # solo para ensayos automaticos contra una base desechable: omite el SI de confirmacion
)
# 'Continue' a proposito: en Windows PowerShell 5.1 cualquier texto que un programa externo (docker) escriba por stderr, aunque termine bien, se convertiria en error.
# Cada llamada externa se valida con su codigo de salida (funcion Nativo); los fallos propios se lanzan con throw.
$ErrorActionPreference = 'Continue'
Set-Location -LiteralPath (Join-Path $PSScriptRoot '..\..') -ErrorAction Stop
New-Item -ItemType Directory -Force -Path 'target' -ErrorAction Stop | Out-Null
$imagen = 'postgis/postgis:17-3.5'
$copia = 'sevb-copia-local'
$archivoCopia = 'target\copia-local.dump'
$archivoDatos = 'target\rutas-datos.dump'
# Tablas que se trasladan (padres antes que hijos) y las que tienen secuencia id (BIGSERIAL).
$tablas = @('rutas', 'ruta_revisiones', 'paraderos', 'ruta_aliases', 'pasos_peatonales', 'paraderos_buses', 'lugares')
$conSecuencia = @('rutas', 'ruta_revisiones', 'paraderos', 'pasos_peatonales', 'paraderos_buses', 'lugares')

function Docker-Ok { & docker info --format '{{.ServerVersion}}' 2>$null | Out-Null; return ($LASTEXITCODE -eq 0) }
function Nativo([scriptblock]$b) { $o = & $b 2>&1 | ForEach-Object { "$_" }; if ($LASTEXITCODE -ne 0) { throw ("Fallo: " + ($o -join ' | ')) }; return $o }
function EnCopia([string]$sql) { Nativo { docker exec $copia psql -U postgres -d copia -At -v ON_ERROR_STOP=1 -c $sql } }
function Limpiar-Copia { if (-not $ConservarCopia) { & docker rm -f $copia 2>&1 | Out-Null } }

if (-not (Docker-Ok)) { throw 'Docker no esta activo. Abre Docker Desktop y vuelve a ejecutar.' }
if (-not ((& docker ps --format '{{.Names}}') -contains $ContenedorLocal)) { throw "No encuentro el contenedor local '$ContenedorLocal' en marcha (la base local de SEVB)." }

# ---------- 1) copia: pg_dump de la original (solo lectura) -> contenedor desechable ----------
Write-Host '== 1) Copiando la base local (solo lectura sobre la original)...'
Nativo { docker exec $ContenedorLocal pg_dump -U $UsuarioLocal -d $BaseLocal -Fc --no-owner --no-privileges -f /tmp/copia-local.dump } | Out-Null
Nativo { docker cp "${ContenedorLocal}:/tmp/copia-local.dump" $archivoCopia } | Out-Null
Nativo { docker exec $ContenedorLocal rm -f /tmp/copia-local.dump } | Out-Null
& docker rm -f $copia 2>&1 | Out-Null
$claveCopia = -join ((1..24) | ForEach-Object { '{0:x}' -f (Get-Random -Maximum 16) })
Nativo { docker run -d --name $copia -e "POSTGRES_PASSWORD=$claveCopia" $imagen } | Out-Null
for ($i = 0; $i -lt 40; $i++) { Start-Sleep -Seconds 2; & docker exec $copia pg_isready -U postgres -d postgres 2>$null | Out-Null; if ($LASTEXITCODE -eq 0) { Start-Sleep -Seconds 4; break } }
Nativo { docker exec $copia psql -U postgres -d postgres -v ON_ERROR_STOP=1 -c 'CREATE DATABASE copia;' } | Out-Null
Nativo { docker cp $archivoCopia "${copia}:/tmp/copia-local.dump" } | Out-Null
# La restauracion puede avisar de objetos que ya trae la imagen (extension postgis): se ignoran los avisos y se verifican los conteos.
& docker exec $copia pg_restore -U postgres -d copia --no-owner --no-privileges /tmp/copia-local.dump 2>&1 | Out-Null

Write-Host '== 2) Filas en la COPIA (lo que se trasladaria):'
$cuentaCopia = [ordered]@{}
foreach ($t in $tablas) { $cuentaCopia[$t] = [int64]@(EnCopia "select count(*) from public.$t")[0]; '{0,-20} {1,8}' -f $t, $cuentaCopia[$t] | Write-Host }
$total = ($cuentaCopia.Values | Measure-Object -Sum).Sum
if ($total -eq 0) { Limpiar-Copia; throw 'La copia no tiene ninguna fila en esas tablas: no hay nada que trasladar.' }
if ($SoloCopia) { Write-Host "`nCopia lista en el contenedor '$copia' y en $archivoCopia (sin contrasena no se toca Supabase)."; if (-not $ConservarCopia) { Limpiar-Copia; Write-Host 'Contenedor de la copia eliminado.' }; exit 0 }

# ---------- 3) Supabase: contrasena y comprobaciones previas ----------
. (Join-Path $PSScriptRoot '_clave.ps1')
$clave = Obtener-Clave
if ([string]::IsNullOrWhiteSpace($clave)) { Limpiar-Copia; throw 'Sin contrasena no se puede continuar.' }
if ($DbUrl -notmatch '^jdbc:postgresql://([^:/]+):(\d+)/([^?]+)') { Limpiar-Copia; throw 'DbUrl no tiene el formato jdbc:postgresql://HOST:PUERTO/BASE' }
$hostBd = $Matches[1]; $puerto = $Matches[2]; $bd = $Matches[3]
$hostDocker = if ($hostBd -in '127.0.0.1', 'localhost') { 'host.docker.internal' } else { $hostBd }
$env:PGPASSWORD = $clave
$env:PGSSLMODE = if ($DbUrl -match 'sslmode=(\w[\w-]*)') { $Matches[1] } else { 'prefer' }
function Remoto([string]$sql) { Nativo { docker run --rm -e PGPASSWORD -e PGSSLMODE -e PGCONNECT_TIMEOUT=20 $imagen psql -h $hostDocker -p $puerto -U $DbUser -d $bd -At -v ON_ERROR_STOP=1 -c $sql } }
function Sin-Clave($t) { if ($t) { "$t".Replace($clave, '***') } }
try {
    Write-Host "== 3) Comprobando Supabase ($hostBd)..."
    $version = @(Remoto "select coalesce(max(version::int), 0) from public.flyway_schema_history where success")[0]
    if ([int]$version -lt 11) { throw "En Supabase Flyway esta en la version $version (se necesita 11). Arranca la aplicacion una vez contra Supabase (iniciar-supabase.ps1) para migrar." }
    foreach ($t in $tablas) {
        $n = [int64]@(Remoto "select count(*) from public.$t")[0]
        if ($n -gt 0) { throw "La tabla $t de Supabase ya tiene $n filas. Para no duplicar ni pisar nada, el traslado solo se hace sobre tablas VACIAS. No se escribio nada." }
    }
    Write-Host "   Flyway en V$version y las $($tablas.Count) tablas de destino estan vacias."

    # ---------- 4) confirmar y trasladar DESDE LA COPIA ----------
    Write-Host ''
    Write-Host "Se copiaran $total filas ($($tablas -join ', ')) desde la COPIA a Supabase, en una sola transaccion (si algo falla, no queda nada a medias)."
    $resp = if ($SinPreguntar) { 'SI' } else { Read-Host 'Escribe SI para continuar' }
    if ($resp -ne 'SI') { throw 'Cancelado: no se escribio nada en Supabase.' }
    $lista = ($tablas | ForEach-Object { '-t', "public.$_" })
    Nativo { docker exec $copia pg_dump -U postgres -d copia -Fc --data-only --no-owner --no-privileges @lista -f /tmp/rutas-datos.dump } | Out-Null
    Nativo { docker cp "${copia}:/tmp/rutas-datos.dump" $archivoDatos } | Out-Null
    $carpeta = (Resolve-Path 'target').Path
    $salida = & docker run --rm -e PGPASSWORD -e PGSSLMODE -v "${carpeta}:/d" $imagen pg_restore -h $hostDocker -p $puerto -U $DbUser -d $bd --single-transaction --data-only --no-owner --exit-on-error /d/rutas-datos.dump 2>&1 | ForEach-Object { "$_" }
    if ($LASTEXITCODE -ne 0) { throw ('Fallo al restaurar en Supabase (la transaccion se revirtio): ' + (Sin-Clave ($salida -join ' | '))) }
    foreach ($t in $conSecuencia) {
        Remoto "select setval(pg_get_serial_sequence('public.$t', 'id'), greatest((select coalesce(max(id), 1) from public.$t), 1)) where pg_get_serial_sequence('public.$t', 'id') is not null" | Out-Null
    }
    Write-Host '== Comparacion (copia vs Supabase):'
    $todoIgual = $true
    foreach ($t in $tablas) {
        $s = [int64]@(Remoto "select count(*) from public.$t")[0]
        $ok = ($s -eq $cuentaCopia[$t]); if (-not $ok) { $todoIgual = $false }
        '{0,-20} copia {1,6}   supabase {2,6}   {3}' -f $t, $cuentaCopia[$t], $s, $(if ($ok) { 'igual' } else { 'DISTINTO' }) | Write-Host
    }
    $tam = @(Remoto "select pg_size_pretty(pg_database_size(current_database()))")[0]
    Write-Host "Tamano de la base en Supabase: $tam"
    if ($todoIgual) { Write-Host 'LISTO: las rutas ya estan en Supabase. Arranca con iniciar-supabase.ps1 y revisa el mapa.' } else { Write-Warning 'Hay diferencias: revisa antes de usar.' }
} catch {
    Write-Host (Sin-Clave $_.Exception.Message) -ForegroundColor Red
    exit 1
} finally {
    $env:PGPASSWORD = $null
    Limpiar-Copia
}
