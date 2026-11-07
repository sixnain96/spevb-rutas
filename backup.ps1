<#
.SYNOPSIS
  Respaldo de la base SEVB (pg_dump) con acceso RESTRINGIDO al usuario actual y cifrado opcional.
.PARAMETER Output   Ruta del archivo (por defecto backups\spevb-AAAAMMDD-HHMMSS.sql).
.PARAMETER Encrypt  Cifra el respaldo con AES-256 (openssl). Lee la frase de la variable de entorno BACKUP_PASSPHRASE (nunca se imprime);
                    el archivo queda como .sql.enc. Recomendado para cualquier respaldo que salga de este equipo o sea de producción.
.NOTES
  Los respaldos contienen datos personales (cuentas, historial, reportes, origen y destino de viajes): ver docs/DATOS-PERSONALES.md.
  Nunca se incluyen en el ZIP de entrega ni en Git. No se borra ningún respaldo automáticamente.
#>
param([string]$Output, [switch]$Encrypt)
$ErrorActionPreference = 'Stop'
Set-Location -LiteralPath $PSScriptRoot

if (-not (docker ps --format '{{.Names}}' | Select-String -SimpleMatch 'spevb-postgis')) {
    throw 'El contenedor spevb-postgis no está en ejecución.'
}
if ($Encrypt) {
    if ([string]::IsNullOrEmpty($env:BACKUP_PASSPHRASE)) { throw 'Define BACKUP_PASSPHRASE (frase larga) en tu sesión para cifrar. No la guardes en el repositorio.' }
    if (-not (Get-Command openssl -ErrorAction SilentlyContinue)) { throw 'Se necesita openssl en el PATH para cifrar (viene con Git for Windows).' }
}

# Carpeta de respaldos solo para el usuario actual (se quitan los permisos heredados).
$dir = Join-Path $PSScriptRoot 'backups'
New-Item -ItemType Directory -Force -Path $dir | Out-Null
& icacls.exe $dir /inheritance:r /grant:r "$($env:USERNAME):(OI)(CI)F" | Out-Null

if ([string]::IsNullOrWhiteSpace($Output)) {
    $stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
    $Output = Join-Path $dir "spevb-$stamp.sql"
} elseif (-not [System.IO.Path]::IsPathRooted($Output)) {
    $Output = Join-Path $PSScriptRoot $Output
}

$containerFile = '/tmp/spevb-backup.sql'
$terminado = $false
try {
    docker exec spevb-postgis pg_dump -U spevb -d spevb --clean --if-exists --no-owner --no-privileges -f $containerFile
    if ($LASTEXITCODE -ne 0) { throw 'No se pudo generar el respaldo dentro de PostgreSQL.' }
    docker cp "spevb-postgis:$containerFile" $Output
    if ($LASTEXITCODE -ne 0) { throw 'No se pudo copiar el respaldo al equipo.' }

    if ($Encrypt) {
        $cifrado = "$Output.enc"
        & openssl enc -aes-256-cbc -pbkdf2 -iter 600000 -salt -in $Output -out $cifrado -pass env:BACKUP_PASSPHRASE
        if ($LASTEXITCODE -ne 0) { Remove-Item -LiteralPath $cifrado -Force -ErrorAction SilentlyContinue; $terminado = $true; throw 'Falló el cifrado; el respaldo sin cifrar se conserva.' }
        Remove-Item -LiteralPath $Output -Force      # solo se borra la copia SIN cifrar que este mismo script acaba de crear
        $Output = $cifrado
    }
    & icacls.exe $Output /inheritance:r /grant:r "$($env:USERNAME):F" | Out-Null
    $terminado = $true
    Write-Host "Respaldo creado (acceso solo para $($env:USERNAME)): $Output"
    if (-not $Encrypt) { Write-Warning 'Sin cifrar. Para producción o para copiarlo fuera de este equipo usa -Encrypt.' }
} finally {
    # El temporal dentro del contenedor se elimina SIEMPRE, también si algo falló.
    docker exec spevb-postgis rm -f $containerFile 2>$null | Out-Null
    # Si el respaldo no terminó, no se deja un archivo parcial (solo el que este script creó en esta ejecución).
    if (-not $terminado -and $Output -and (Test-Path -LiteralPath $Output)) { Remove-Item -LiteralPath $Output -Force -ErrorAction SilentlyContinue }
}
