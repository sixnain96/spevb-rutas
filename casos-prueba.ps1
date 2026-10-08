<#
  Plan de calidad SEVB (Taller 5): ejecuta los casos de prueba contra la aplicación
  en ejecución y mide el tiempo de respuesta del planificador (percentil 95).

  Uso: con la aplicación encendida (.\start.ps1 -NoDatabase), en OTRA terminal:
      .\casos-prueba.ps1

  Los casos CP-10, CP-16 y CP-17 son automatizados: su resultado sale de "mvn verify".
  Los resultados se guardan también en target\casos-prueba-<fecha>.csv.
#>
param(
    [string]$Base = 'http://localhost:8080',
    [int]$Mediciones = 100
)

$ErrorActionPreference = 'Stop'

function Invocar {
    param([string]$Metodo, [string]$Ruta, [string]$Cuerpo = '', [hashtable]$Encabezados = $null)
    $p = @{ Uri = "$Base$Ruta"; Method = $Metodo; UseBasicParsing = $true }
    if ($Encabezados) { $p.Headers = $Encabezados }
    if ($Cuerpo) {
        $p.Body = [System.Text.Encoding]::UTF8.GetBytes($Cuerpo)
        $p.ContentType = 'application/json'
    }
    try {
        $r = Invoke-WebRequest @p
        $json = $null
        if ($r.Content) { try { $json = $r.Content | ConvertFrom-Json } catch { $json = $null } }
        return [pscustomobject]@{ Estado = [int]$r.StatusCode; Json = $json }
    } catch {
        $resp = $_.Exception.Response
        if ($null -eq $resp) { throw }
        return [pscustomobject]@{ Estado = [int]$resp.StatusCode; Json = $null }
    }
}

function Viaje {
    param([double]$OLat, [double]$OLng, [double]$DLat, [double]$DLng, $Caminata = $null)
    $o = [ordered]@{
        origen        = [ordered]@{ lat = $OLat; lng = $OLng }
        destino       = [ordered]@{ lat = $DLat; lng = $DLng }
        soloValidadas = $false
    }
    if ($null -ne $Caminata) { $o.maxCaminataMetros = $Caminata }
    return ($o | ConvertTo-Json -Compress)
}

function Estado {
    param($Respuesta, [int]$Esperado)
    return @{ Ok = ($Respuesta.Estado -eq $Esperado); Obtenido = "$($Respuesta.Estado)" }
}

# Puntos de prueba: inicio y fin de la ruta R002 (Galán -> Centro, sentido IDA).
$galan = @(4.1450, -73.6540)
$centro = @(4.1450, -73.6240)
$planificar = '/api/viajes/planificar'

try { Invocar 'GET' '/api/estado' | Out-Null }
catch {
    Write-Host 'No se pudo conectar con la aplicación. Enciéndela con .\start.ps1 -NoDatabase y vuelve a intentar.' -ForegroundColor Red
    exit 1
}

$casos = @(
    @{ Id = 'CP-01'; Caso = 'Caminata de 199 m (debajo del mínimo)'; Esperado = '400'
       Prueba = { Estado (Invocar 'POST' $planificar (Viaje $galan[0] $galan[1] $centro[0] $centro[1] 199)) 400 } }
    @{ Id = 'CP-02'; Caso = 'Caminata de 200 m (mínimo permitido)'; Esperado = '200'
       Prueba = { Estado (Invocar 'POST' $planificar (Viaje $galan[0] $galan[1] $centro[0] $centro[1] 200)) 200 } }
    @{ Id = 'CP-03'; Caso = 'Caminata de 3000 m (máximo permitido)'; Esperado = '200'
       Prueba = { Estado (Invocar 'POST' $planificar (Viaje $galan[0] $galan[1] $centro[0] $centro[1] 3000)) 200 } }
    @{ Id = 'CP-04'; Caso = 'Caminata de 3001 m (encima del máximo)'; Esperado = '400'
       Prueba = { Estado (Invocar 'POST' $planificar (Viaje $galan[0] $galan[1] $centro[0] $centro[1] 3001)) 400 } }
    @{ Id = 'CP-05'; Caso = 'Origen y destino dentro de Villavicencio'; Esperado = '200'
       Prueba = { Estado (Invocar 'POST' $planificar (Viaje $galan[0] $galan[1] $centro[0] $centro[1])) 200 } }
    @{ Id = 'CP-06'; Caso = 'Origen fuera de Villavicencio (Bogotá)'; Esperado = '400'
       Prueba = { Estado (Invocar 'POST' $planificar (Viaje 4.6097 -74.0817 $centro[0] $centro[1])) 400 } }
    @{ Id = 'CP-07'; Caso = 'Latitud inválida (95)'; Esperado = '400'
       Prueba = { Estado (Invocar 'POST' $planificar (Viaje 95 $galan[1] $centro[0] $centro[1])) 400 } }
    @{ Id = 'CP-08'; Caso = 'Ruta IDA en el sentido del trazado (Galán -> Centro)'; Esperado = 'R002 en opciones directas'
       Prueba = {
           $r = Invocar 'POST' $planificar (Viaje $galan[0] $galan[1] $centro[0] $centro[1])
           $directa = @($r.Json.opciones | Where-Object { $_.codigo -eq 'R002' -and $_.sentidoPermitido })
           $alterna = @($r.Json.alternativas | Where-Object { $_.codigo -eq 'R002' })
           if ($directa.Count -gt 0) { $obt = 'R002 en opciones directas' }
           elseif ($alterna.Count -gt 0) { $obt = 'R002 en alternativas' }
           else { $obt = "R002 no aparece (estado $($r.Estado))" }
           @{ Ok = ($directa.Count -gt 0); Obtenido = $obt } } }
    @{ Id = 'CP-09'; Caso = 'Ruta IDA en sentido contrario (Centro -> Galán)'; Esperado = 'R002 en alternativas, sentido no permitido'
       Prueba = {
           $r = Invocar 'POST' $planificar (Viaje $centro[0] $centro[1] $galan[0] $galan[1])
           $alterna = @($r.Json.alternativas | Where-Object { $_.codigo -eq 'R002' -and -not $_.sentidoPermitido })
           $directa = @($r.Json.opciones | Where-Object { $_.codigo -eq 'R002' })
           if ($alterna.Count -gt 0) { $obt = 'R002 en alternativas, sentido no permitido' }
           elseif ($directa.Count -gt 0) { $obt = 'R002 en opciones directas' }
           else { $obt = "R002 no aparece (estado $($r.Estado))" }
           @{ Ok = ($alterna.Count -gt 0); Obtenido = $obt } } }
    @{ Id = 'CP-11'; Caso = 'Consultar una ruta inexistente (id 999999)'; Esperado = '404'
       Prueba = { Estado (Invocar 'GET' '/api/rutas/999999') 404 } }
    @{ Id = 'CP-12'; Caso = 'Rutas cercanas con radio de 99 m'; Esperado = '400'
       Prueba = { Estado (Invocar 'GET' "/api/rutas/cercanas?lat=$($galan[0])&lng=$($galan[1])&radioMetros=99") 400 } }
    @{ Id = 'CP-13'; Caso = 'Rutas cercanas con radio de 100 m'; Esperado = '200'
       Prueba = { Estado (Invocar 'GET' "/api/rutas/cercanas?lat=$($galan[0])&lng=$($galan[1])&radioMetros=100") 200 } }
    @{ Id = 'CP-14'; Caso = 'Crear ruta sin clave de editor'; Esperado = '401'
       Prueba = { Estado (Invocar 'POST' '/api/editor/rutas' '{}') 401 } }
    @{ Id = 'CP-15'; Caso = 'Crear ruta con clave incorrecta'; Esperado = '401'
       Prueba = { Estado (Invocar 'POST' '/api/editor/rutas' '{}' @{ 'X-Editor-Token' = 'clave-incorrecta-de-prueba-0000000000' }) 401 } }
)

$fecha = Get-Date -Format 'yyyy-MM-dd HH:mm'
$resultados = foreach ($c in $casos) {
    try { $res = & $c.Prueba } catch { $res = @{ Ok = $false; Obtenido = "Error: $($_.Exception.Message)" } }
    [pscustomobject]@{
        Id        = $c.Id
        Caso      = $c.Caso
        Esperado  = $c.Esperado
        Obtenido  = $res.Obtenido
        Resultado = $(if ($res.Ok) { 'Aprobado' } else { 'Fallido' })
    }
}
$resultados += [pscustomobject]@{ Id = 'CP-10'; Caso = 'Ruta CIRCULAR con destino antes del origen'; Esperado = 'Opción directa'; Obtenido = 'Ver mvn verify (ViajeServiceTest)'; Resultado = 'Automatizado' }
$resultados += [pscustomobject]@{ Id = 'CP-16'; Caso = 'Abreviaturas de dirección como palabras completas'; Esperado = 'Normalización correcta'; Obtenido = 'Ver mvn verify (CityAreaTest)'; Resultado = 'Automatizado' }
$resultados += [pscustomobject]@{ Id = 'CP-17'; Caso = 'Recorrido de 1 punto frente a 2 puntos'; Esperado = '1 punto rechazado, 2 aceptados'; Obtenido = 'Ver mvn verify (GeometriaValidatorTest)'; Resultado = 'Automatizado' }

Write-Host ""
Write-Host "=== Casos de prueba SEVB - $fecha ===" -ForegroundColor Cyan
$resultados | Sort-Object Id | Format-Table Id, Resultado, Esperado, Obtenido -AutoSize -Wrap | Out-String -Width 200 | Write-Host
$aprobados = @($resultados | Where-Object { $_.Resultado -eq 'Aprobado' }).Count
$fallidos = @($resultados | Where-Object { $_.Resultado -eq 'Fallido' }).Count
Write-Host "Ejecutados por este script: $($aprobados + $fallidos) | Aprobados: $aprobados | Fallidos: $fallidos | Automatizados (mvn verify): 3"

# --- Rendimiento: percentil 95 del planificador
$rutas = @((Invocar 'GET' '/api/rutas').Json).Count
$cuerpo = Viaje 4.0985 -73.6650 4.1520 -73.6150
$uri = "$Base$planificar"
1..3 | ForEach-Object { Invoke-RestMethod -Uri $uri -Method Post -ContentType 'application/json' -Body $cuerpo | Out-Null }
$tiempos = foreach ($i in 1..$Mediciones) {
    (Measure-Command { Invoke-RestMethod -Uri $uri -Method Post -ContentType 'application/json' -Body $cuerpo | Out-Null }).TotalMilliseconds
}
$orden = @($tiempos | Sort-Object)
$n = $orden.Count
$p50 = $orden[[math]::Ceiling(0.50 * $n) - 1]
$p95 = $orden[[math]::Ceiling(0.95 * $n) - 1]
Write-Host ""
Write-Host "=== Rendimiento del planificador - $fecha ===" -ForegroundColor Cyan
Write-Host ("Consultas: {0} | Rutas activas: {1}" -f $n, $rutas)
Write-Host ("Percentil 50: {0:N1} ms | Percentil 95: {1:N1} ms | Máximo: {2:N1} ms" -f $p50, $p95, $orden[$n - 1])

$carpeta = Join-Path $PSScriptRoot 'target'
New-Item -ItemType Directory -Force -Path $carpeta | Out-Null
$archivo = Join-Path $carpeta ("casos-prueba-{0}.csv" -f (Get-Date -Format 'yyyyMMdd-HHmm'))
$resultados | Sort-Object Id | Export-Csv -Path $archivo -NoTypeInformation -Encoding UTF8
Write-Host ""
Write-Host "Resultados guardados en $archivo"
