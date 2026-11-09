# Comprueba, SIN mostrar ningún valor secreto, que la sesión de PowerShell tiene lo necesario para conectar con Supabase.
# Uso: .\scripts\supabase\comprobar-variables.ps1
$ErrorActionPreference = 'Continue'
foreach ($n in 'DB_URL', 'DB_USER', 'DB_PASSWORD') {
    $v = [Environment]::GetEnvironmentVariable($n, 'Process')
    '{0,-12} {1}' -f $n, $(if ([string]::IsNullOrWhiteSpace($v)) { 'NO definida en esta sesion' } else { 'definida' })
}
$url = [Environment]::GetEnvironmentVariable('DB_URL', 'Process')
if ($url -match '^jdbc:postgresql://([^:/]+):(\d+)/([^?]+)') {
    $servidor = $Matches[1]; $puerto = [int]$Matches[2]
    "Servidor     $servidor  puerto $puerto  base $($Matches[3])"
    "SSL exigido  $(if ($url -match 'sslmode=(require|verify)') { 'si' } else { 'NO (agrega ?sslmode=require)' })"
    if ($servidor -in 'localhost', '127.0.0.1', '::1') { Write-Warning 'DB_URL apunta a la base LOCAL, no a Supabase.' }
    $r = Test-NetConnection -ComputerName $servidor -Port $puerto -WarningAction SilentlyContinue
    "Conexion TCP $(if ($r.TcpTestSucceeded) { 'responde' } else { 'NO responde (red, firewall o puerto 5432 bloqueado)' })"
} elseif ($url) { Write-Warning 'DB_URL no tiene el formato jdbc:postgresql://HOST:PUERTO/BASE' }
$u = [Environment]::GetEnvironmentVariable('DB_USER', 'Process')
if ($u -and $u -notmatch '^postgres\.[a-z0-9]+$') { Write-Warning 'DB_USER del Session pooler debe ser postgres.<id-del-proyecto>.' }
