<#
.SYNOPSIS
  Comprueba que un respaldo SE PUEDE RESTAURAR, en un contenedor PostGIS TEMPORAL (no toca tu base ni tu volumen de datos).
.PARAMETER File  Respaldo .sql o .sql.enc (este último requiere BACKUP_PASSPHRASE).
.DESCRIPTION
  Crea un contenedor desechable, restaura el respaldo, verifica las tablas principales y los conteos, y destruye el contenedor.
  Sale con código 1 si algo falla. Imprime solo conteos, nunca datos personales.
#>
param([Parameter(Mandatory=$true)][string]$File)
$ErrorActionPreference = 'Stop'
$ruta = (Resolve-Path -LiteralPath $File).Path
if (-not (Get-Command docker -ErrorAction SilentlyContinue)) { throw 'Se necesita Docker.' }

$nombre = 'sevb-verify-' + [guid]::NewGuid().ToString('N').Substring(0, 8)
$clave = [guid]::NewGuid().ToString('N')
$temporal = $null
try {
    $sql = $ruta
    if ($ruta.EndsWith('.enc')) {
        if ([string]::IsNullOrEmpty($env:BACKUP_PASSPHRASE)) { throw 'Respaldo cifrado: define BACKUP_PASSPHRASE.' }
        $temporal = Join-Path ([System.IO.Path]::GetTempPath()) "$nombre.sql"
        & openssl enc -d -aes-256-cbc -pbkdf2 -iter 600000 -in $ruta -out $temporal -pass env:BACKUP_PASSPHRASE
        if ($LASTEXITCODE -ne 0) { throw 'No se pudo descifrar el respaldo.' }
        $sql = $temporal
    }
    docker run -d --rm --name $nombre -e POSTGRES_DB=spevb -e POSTGRES_USER=spevb -e "POSTGRES_PASSWORD=$clave" postgis/postgis:17-3.5 | Out-Null
    $listo = $false
    for ($i = 0; $i -lt 60 -and -not $listo; $i++) {
        Start-Sleep -Seconds 2
        docker exec $nombre psql -U spevb -d spevb -tAc 'select 1' 2>$null | Out-Null
        $listo = ($LASTEXITCODE -eq 0)
    }
    if (-not $listo) { throw 'El contenedor temporal no arrancó.' }
    docker cp $sql "${nombre}:/tmp/r.sql"
    docker exec $nombre psql -v ON_ERROR_STOP=1 -q -U spevb -d spevb -f /tmp/r.sql | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'La restauración FALLÓ en el entorno temporal.' }

    $consulta = "select 'rutas', count(*) from rutas union all select 'paraderos', count(*) from paraderos union all select 'lugares', count(*) from lugares union all select 'usuarios', count(*) from usuarios union all select 'reportes', count(*) from reportes union all select 'pasos_peatonales', count(*) from pasos_peatonales order by 1"
    $conteos = docker exec $nombre psql -U spevb -d spevb -tA -F ' = ' -c $consulta
    if ($LASTEXITCODE -ne 0) { throw 'Faltan tablas principales en el respaldo restaurado.' }
    Write-Host 'Conteos tras restaurar (solo cantidades):'
    $conteos | ForEach-Object { Write-Host "  $_" }
    $extension = docker exec $nombre psql -U spevb -d spevb -tAc "select count(*) from pg_extension where extname='postgis'"
    if ($extension.Trim() -ne '1') { throw 'PostGIS no quedó activo tras restaurar.' }
    Write-Host 'COMPROBACIÓN CORRECTA: el respaldo se restaura en un entorno temporal.' -ForegroundColor Green
    exit 0
} catch {
    Write-Host "FALLA: $($_.Exception.Message)" -ForegroundColor Red
    exit 1
} finally {
    docker rm -f $nombre 2>$null | Out-Null
    if ($temporal) { Remove-Item -LiteralPath $temporal -Force -ErrorAction SilentlyContinue }
}
