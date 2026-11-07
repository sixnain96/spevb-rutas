param([switch]$NoDatabase)
$ErrorActionPreference = 'Stop'
Set-Location -LiteralPath $PSScriptRoot
if (-not (Test-Path -LiteralPath '.env')) {
    throw 'Copia .env.example a .env y configura DB_PASSWORD. Consulta README.md y docs/CONFIGURACION.md.'
}

# Variables que la aplicación reconoce: se leen de application.properties (marcadores ${NOMBRE:valor}), así este script
# nunca queda desactualizado respecto de la aplicación. Se añaden las que solo usa el arranque (ver docs/CONFIGURACION.md).
$reconocidas = [System.Collections.Generic.HashSet[string]]::new()
foreach ($m in [regex]::Matches((Get-Content -LiteralPath 'src/main/resources/application.properties' -Raw), '\$\{([A-Z][A-Z0-9_]*)')) {
    [void]$reconocidas.Add($m.Groups[1].Value)
}

# Lee asignaciones simples CLAVE=VALOR sin ejecutar el archivo como código. NO modifica .env y NO pisa variables que
# ya estén definidas en tu sesión de PowerShell (lo que definas a mano tiene prioridad sobre .env).
foreach ($line in Get-Content -LiteralPath '.env') {
    if ($line -match '^\s*([A-Z][A-Z0-9_]*)\s*=\s*(.*?)\s*$') {
        $taskName = $Matches[1]
        $taskValue = $Matches[2].Trim('"').Trim("'")
        if ($reconocidas.Contains($taskName) -and -not [Environment]::GetEnvironmentVariable($taskName, 'Process')) {
            [Environment]::SetEnvironmentVariable($taskName, $taskValue, 'Process')
        }
    }
}
# Valores seguros si .env no los define (el editor y el acceso desde la red deben pedirse de forma explícita).
if ([string]::IsNullOrWhiteSpace($env:EDITOR_ENABLED)) { $env:EDITOR_ENABLED = 'false' }
if ([string]::IsNullOrWhiteSpace($env:SERVER_ADDRESS)) { $env:SERVER_ADDRESS = '127.0.0.1' }
# La búsqueda de direcciones de Villavicencio está habilitada por defecto.
if ([string]::IsNullOrWhiteSpace($env:GEOCODER_URL)) { $env:GEOCODER_URL = 'https://nominatim.openstreetmap.org/search' }
if ([string]::IsNullOrWhiteSpace($env:GEOCODER_REVERSE_URL)) { $env:GEOCODER_REVERSE_URL = 'https://nominatim.openstreetmap.org/reverse' }
if ([string]::IsNullOrWhiteSpace($env:GEOCODER_USER_AGENT)) { $env:GEOCODER_USER_AGENT = 'SEVB-Rutas/4.10' }

if ([string]::IsNullOrWhiteSpace($env:DB_PASSWORD) -or $env:DB_PASSWORD -eq 'CAMBIA_ESTA_CLAVE_ANTES_DE_ARRANCAR') {
    throw 'Define una contraseña propia en DB_PASSWORD dentro de .env.'
}
if ($env:EDITOR_ENABLED -eq 'true' -and ([string]::IsNullOrWhiteSpace($env:EDITOR_TOKEN) -or $env:EDITOR_TOKEN.Length -lt 32)) {
    throw 'Para activar la edición define EDITOR_TOKEN con al menos 32 caracteres.'
}
if ($env:EDITOR_ENABLED -eq 'true') { Write-Warning 'El editor/administración está ACTIVADO (EDITOR_ENABLED=true).' }
if ($env:SERVER_ADDRESS -notin @('127.0.0.1', 'localhost', '::1')) { Write-Warning "La aplicación escuchará en $($env:SERVER_ADDRESS): será accesible desde la red." }
if ($env:DEMO_DATA -eq 'true') { Write-Host 'DEMO_DATA=true: se cargarán las rutas de demostración (solo desarrollo).' }

if (-not $NoDatabase) {
    docker compose up -d --wait
    if ($LASTEXITCODE -ne 0) { throw 'No se pudo iniciar PostGIS. Comprueba que Docker Desktop esté iniciado.' }
}
mvn spring-boot:run
exit $LASTEXITCODE
