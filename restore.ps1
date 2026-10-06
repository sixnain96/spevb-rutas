param([Parameter(Mandatory=$true)][string]$File)
$ErrorActionPreference = 'Stop'
Set-Location -LiteralPath $PSScriptRoot

$resolved = Resolve-Path -LiteralPath $File -ErrorAction Stop
if (-not (docker ps --format '{{.Names}}' | Select-String -SimpleMatch 'spevb-postgis')) {
    throw 'El contenedor spevb-postgis no está en ejecución.'
}

Write-Warning 'Restaurar reemplazará los datos actuales de SPEVB por el contenido del respaldo.'
$confirm = Read-Host 'Escribe RESTAURAR para continuar'
if ($confirm -ne 'RESTAURAR') { Write-Host 'Operación cancelada.'; exit 0 }

$containerFile = '/tmp/spevb-restore.sql'
docker cp $resolved.Path "spevb-postgis:$containerFile"
if ($LASTEXITCODE -ne 0) { throw 'No se pudo copiar el respaldo al contenedor.' }
docker exec spevb-postgis psql -v ON_ERROR_STOP=1 -U spevb -d spevb -f $containerFile
if ($LASTEXITCODE -ne 0) { throw 'La restauración falló. Revisa el archivo SQL.' }
docker exec spevb-postgis rm -f $containerFile | Out-Null
Write-Host 'Base de datos restaurada correctamente.'
