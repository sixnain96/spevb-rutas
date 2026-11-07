param([Parameter(Mandatory=$true)][string]$File)
$ErrorActionPreference = 'Stop'
Set-Location -LiteralPath $PSScriptRoot

$resolved = Resolve-Path -LiteralPath $File -ErrorAction Stop
if (-not (docker ps --format '{{.Names}}' | Select-String -SimpleMatch 'spevb-postgis')) {
    throw 'El contenedor spevb-postgis no está en ejecución.'
}

Write-Warning 'Restaurar reemplazará los datos actuales de SEVB por el contenido del respaldo. Antes de hacerlo, comprueba el respaldo con scripts\verify-restore.ps1.'
$confirm = Read-Host 'Escribe RESTAURAR para continuar'
if ($confirm -ne 'RESTAURAR') { Write-Host 'Operación cancelada.'; exit 0 }

$origen = $resolved.Path
$temporal = $null
if ($origen.EndsWith('.enc')) {
    if ([string]::IsNullOrEmpty($env:BACKUP_PASSPHRASE)) { throw 'El respaldo está cifrado: define BACKUP_PASSPHRASE.' }
    $temporal = Join-Path ([System.IO.Path]::GetTempPath()) ("spevb-restore-" + [guid]::NewGuid().ToString('N') + '.sql')
    & openssl enc -d -aes-256-cbc -pbkdf2 -iter 600000 -in $origen -out $temporal -pass env:BACKUP_PASSPHRASE
    if ($LASTEXITCODE -ne 0) { Remove-Item -LiteralPath $temporal -Force -ErrorAction SilentlyContinue; throw 'No se pudo descifrar (¿frase incorrecta?).' }
    $origen = $temporal
}
$containerFile = '/tmp/spevb-restore.sql'
try {
    docker cp $origen "spevb-postgis:$containerFile"
    if ($LASTEXITCODE -ne 0) { throw 'No se pudo copiar el respaldo al contenedor.' }
    docker exec spevb-postgis psql -v ON_ERROR_STOP=1 -U spevb -d spevb -f $containerFile
    if ($LASTEXITCODE -ne 0) { throw 'La restauración falló. Revisa el archivo SQL.' }
    Write-Host 'Base de datos restaurada correctamente.'
} finally {
    docker exec spevb-postgis rm -f $containerFile 2>$null | Out-Null      # temporal del contenedor: siempre se elimina
    if ($temporal) { Remove-Item -LiteralPath $temporal -Force -ErrorAction SilentlyContinue }   # copia descifrada temporal creada por este script
}
