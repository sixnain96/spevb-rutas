# Arranca SEVB usando la base de Supabase (no la base local de Docker). Pide la contrasena aqui (no se ve) y NO la guarda en ningun archivo.
# Uso:  powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\supabase\iniciar-supabase.ps1
# Despues abre http://127.0.0.1:8080/   (Ctrl+C para detener).
param(
    [string]$DbUrl = 'jdbc:postgresql://aws-0-us-east-1.pooler.supabase.com:5432/postgres?sslmode=require',
    [string]$DbUser = 'postgres.xbsrvlvcxxmxaeixhlal'
)
$ErrorActionPreference = 'Stop'
Set-Location -LiteralPath (Join-Path $PSScriptRoot '..\..')
. (Join-Path $PSScriptRoot '_clave.ps1')
$clave = Obtener-Clave
if ([string]::IsNullOrWhiteSpace($clave)) { throw 'Sin contrasena no se puede arrancar.' }
$env:DB_URL = $DbUrl; $env:DB_USER = $DbUser; $env:DB_PASSWORD = $clave
$hostBd = if ($DbUrl -match '//([^:/]+)') { $Matches[1] } else { '?' }
Write-Host "SEVB con la base de datos en $hostBd (Supabase). No se usa la base local."
& (Join-Path $PWD 'start.ps1') -NoDatabase
exit $LASTEXITCODE
