# Auxiliar de los scripts de scripts/supabase: obtiene la contrasena de la base de Supabase sin escribirla en ningun archivo.
# Orden: 1) DB_PASSWORD de la sesion actual, 2) SUPABASE_DB_PASSWORD (sesion o variable de usuario guardada con guardar-clave.ps1), 3) la pide aqui (no se ve).
function Obtener-Clave {
    $v = [Environment]::GetEnvironmentVariable('DB_PASSWORD', 'Process')
    if (-not [string]::IsNullOrWhiteSpace($v)) { return $v }
    foreach ($ambito in 'Process', 'User') {
        $v = [Environment]::GetEnvironmentVariable('SUPABASE_DB_PASSWORD', $ambito)
        if (-not [string]::IsNullOrWhiteSpace($v)) { Write-Host 'Usando la contrasena guardada de Supabase (SUPABASE_DB_PASSWORD).'; return $v }
    }
    $seg = Read-Host 'Contrasena de la base de datos de Supabase (no se muestra; para no repetirla ejecuta scripts\supabase\guardar-clave.ps1)' -AsSecureString
    $bstr = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($seg)
    try { return [Runtime.InteropServices.Marshal]::PtrToStringBSTR($bstr) } finally { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($bstr) }
}
