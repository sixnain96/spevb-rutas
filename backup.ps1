param([string]$Output)
$ErrorActionPreference = 'Stop'
Set-Location -LiteralPath $PSScriptRoot

if (-not (docker ps --format '{{.Names}}' | Select-String -SimpleMatch 'spevb-postgis')) {
    throw 'El contenedor spevb-postgis no está en ejecución.'
}

New-Item -ItemType Directory -Force -Path 'backups' | Out-Null
if ([string]::IsNullOrWhiteSpace($Output)) {
    $stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
    $Output = Join-Path $PSScriptRoot "backups\spevb-$stamp.sql"
} elseif (-not [System.IO.Path]::IsPathRooted($Output)) {
    $Output = Join-Path $PSScriptRoot $Output
}

$containerFile = '/tmp/spevb-backup.sql'
docker exec spevb-postgis pg_dump -U spevb -d spevb --clean --if-exists --no-owner --no-privileges -f $containerFile
if ($LASTEXITCODE -ne 0) { throw 'No se pudo generar el respaldo dentro de PostgreSQL.' }
docker cp "spevb-postgis:$containerFile" $Output
if ($LASTEXITCODE -ne 0) { throw 'No se pudo copiar el respaldo al equipo.' }
docker exec spevb-postgis rm -f $containerFile | Out-Null
Write-Host "Respaldo creado: $Output"
