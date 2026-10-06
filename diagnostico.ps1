$ErrorActionPreference = 'Stop'
Write-Host '1/3 Comprobando contenedor PostGIS...' -ForegroundColor Cyan
$container = docker ps --filter "name=spevb-postgis" --format "{{.Status}}"
if (-not $container) { throw 'spevb-postgis no está ejecutándose. Usa: docker compose up -d' }
Write-Host "PostGIS: $container" -ForegroundColor Green

Write-Host '2/3 Comprobando API...' -ForegroundColor Cyan
$estado = Invoke-RestMethod -Uri 'http://localhost:8080/api/estado' -Method Get
$estado | ConvertTo-Json -Depth 6

Write-Host '3/3 Probando el cálculo espacial con la ruta DEMO Galán...' -ForegroundColor Cyan
$body = @{
    origen = @{ lat = 4.1450; lng = -73.6540 }
    destino = @{ lat = 4.1450; lng = -73.6240 }
    maxCaminataMetros = 1000
    soloValidadas = $false
} | ConvertTo-Json -Depth 5
$resultado = Invoke-RestMethod -Uri 'http://localhost:8080/api/viajes/planificar' -Method Post -ContentType 'application/json' -Body $body
$resultado | ConvertTo-Json -Depth 10
Write-Host 'Diagnóstico terminado. Si aparece R002/Galán en opciones, el cálculo PostGIS funciona.' -ForegroundColor Green
