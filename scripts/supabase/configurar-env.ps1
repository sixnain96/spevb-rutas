# Deja el .env apuntando a SUPABASE (DB_URL, DB_USER y DB_PASSWORD) para arrancar con  .\start.ps1 -NoDatabase  sin que nada pida la contrasena.
# - Pide la contrasena aqui (no se ve) y la escribe SOLO en el .env. No imprime ningun valor.
# - Cambia unicamente esas tres lineas (las crea si faltan); el resto del .env no se toca.
# - Antes guarda una copia del .env actual en .env.local.bak (git y docker la ignoran) para poder volver a la base local.
# Uso:      powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\supabase\configurar-env.ps1
# Volver:   powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\supabase\configurar-env.ps1 -RestaurarLocal
# Aviso: la carpeta del proyecto esta en OneDrive, asi que el .env se sincroniza con la nube. Nunca lo incluyas en un ZIP ni lo subas a Git.
param(
    [string]$DbUrl = 'jdbc:postgresql://aws-0-us-east-1.pooler.supabase.com:5432/postgres?sslmode=require',
    [string]$DbUser = 'postgres.xbsrvlvcxxmxaeixhlal',
    [string]$Archivo = '.env',
    [switch]$RestaurarLocal
)
$ErrorActionPreference = 'Stop'
Set-Location -LiteralPath (Join-Path $PSScriptRoot '..\..')
$copia = "$Archivo.local.bak"
if ($RestaurarLocal) {
    if (-not (Test-Path -LiteralPath $copia)) { throw "No existe ${copia}: no hay una copia de la configuracion local que restaurar." }
    Copy-Item -LiteralPath $copia -Destination $Archivo -Force
    Write-Host "Restaurado: $Archivo vuelve a la configuracion anterior (base local). La copia $copia se conserva."
    return
}
. (Join-Path $PSScriptRoot '_clave.ps1')
$clave = Obtener-Clave
if ([string]::IsNullOrWhiteSpace($clave)) { throw 'Sin contrasena no se puede configurar.' }
if ($clave -match '[\r\n]') { throw 'La contrasena no puede tener saltos de linea.' }
$utf8 = New-Object System.Text.UTF8Encoding($false)
$lineas = New-Object 'System.Collections.Generic.List[string]'
if (Test-Path -LiteralPath $Archivo) { $lineas.AddRange([string[]][IO.File]::ReadAllLines((Resolve-Path -LiteralPath $Archivo).Path)) }
if ((Test-Path -LiteralPath $Archivo) -and -not (Test-Path -LiteralPath $copia)) { Copy-Item -LiteralPath $Archivo -Destination $copia; Write-Host "Copia de seguridad del .env anterior: $copia" }
$nuevos = [ordered]@{ DB_URL = $DbUrl; DB_USER = $DbUser; DB_PASSWORD = $clave }
foreach ($k in $nuevos.Keys) {
    $valor = $nuevos[$k]; $hecho = $false
    for ($i = 0; $i -lt $lineas.Count; $i++) {
        if ($lineas[$i] -match ('^\s*' + [regex]::Escape($k) + '\s*=')) { $lineas[$i] = "$k=$valor"; $hecho = $true }
    }
    if (-not $hecho) { $lineas.Add("$k=$valor") }
    Write-Host "  $k actualizado"
}
$ruta = $ExecutionContext.SessionState.Path.GetUnresolvedProviderPathFromPSPath($Archivo)
[IO.File]::WriteAllLines($ruta, $lineas, $utf8)
Write-Host ''
Write-Host 'Listo. Ahora arranca con:  .\start.ps1 -NoDatabase   (si PowerShell bloquea scripts: powershell -NoProfile -ExecutionPolicy Bypass -File .\start.ps1 -NoDatabase)'
Write-Host 'Para volver a la base local:  configurar-env.ps1 -RestaurarLocal'
