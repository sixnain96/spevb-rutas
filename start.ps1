param([switch]$NoDatabase)
$ErrorActionPreference = 'Stop'
Set-Location -LiteralPath $PSScriptRoot
if (-not (Test-Path -LiteralPath '.env')) {
    throw 'Copia .env.example a .env y configura DB_PASSWORD. Consulta README.md.'
}
# Read simple KEY=VALUE assignments without executing the file as code.
foreach ($line in Get-Content -LiteralPath '.env') {
    if ($line -match '^\s*([A-Z][A-Z0-9_]*)\s*=\s*(.*?)\s*$') {
        $taskName = $Matches[1]
        $taskValue = $Matches[2].Trim('"').Trim("'")
        if ($taskName -in @('DB_PASSWORD','DB_URL','DB_USER','EDITOR_ENABLED','EDITOR_TOKEN','SERVER_PORT','SERVER_ADDRESS','GEOCODER_URL','GEOCODER_REVERSE_URL','GEOCODER_USER_AGENT','CITY_MIN_LAT','CITY_MAX_LAT','CITY_MIN_LNG','CITY_MAX_LNG')) {
            [Environment]::SetEnvironmentVariable($taskName,$taskValue,'Process')
        }
    }
}
# La búsqueda de direcciones de Villavicencio está habilitada por defecto.
# Si un .env antiguo dejó estas variables vacías, usamos los endpoints públicos configurados para el proyecto.
if ([string]::IsNullOrWhiteSpace($env:GEOCODER_URL)) { $env:GEOCODER_URL = 'https://nominatim.openstreetmap.org/search' }
if ([string]::IsNullOrWhiteSpace($env:GEOCODER_REVERSE_URL)) { $env:GEOCODER_REVERSE_URL = 'https://nominatim.openstreetmap.org/reverse' }
if ([string]::IsNullOrWhiteSpace($env:GEOCODER_USER_AGENT)) { $env:GEOCODER_USER_AGENT = 'SPEVB-Rutas-USTA/3.0' }

if ([string]::IsNullOrWhiteSpace($env:DB_PASSWORD) -or $env:DB_PASSWORD -eq 'CAMBIA_ESTA_CLAVE_ANTES_DE_ARRANCAR') {
    throw 'Define una contraseña propia en DB_PASSWORD dentro de .env.'
}
if ($env:EDITOR_ENABLED -eq 'true' -and ([string]::IsNullOrWhiteSpace($env:EDITOR_TOKEN) -or $env:EDITOR_TOKEN.Length -lt 32)) {
    throw 'Para activar la edición define EDITOR_TOKEN con al menos 32 caracteres.'
}
if (-not $NoDatabase) {
    docker compose up -d --wait
    if ($LASTEXITCODE -ne 0) { throw 'No se pudo iniciar PostGIS. Comprueba que Docker Desktop esté iniciado.' }
}
mvn spring-boot:run
exit $LASTEXITCODE
