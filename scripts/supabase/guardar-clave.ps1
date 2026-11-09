# Guarda UNA vez la contrasena de Supabase como variable de TU usuario de Windows (SUPABASE_DB_PASSWORD) para no escribirla en cada arranque.
# No va a ningun archivo del proyecto (la carpeta esta en OneDrive y se sincroniza) ni toca el .env: la base local sigue funcionando igual.
# Uso:    powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\supabase\guardar-clave.ps1
# Borrar: powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\supabase\guardar-clave.ps1 -Borrar
param([switch]$Borrar)
$ErrorActionPreference = 'Stop'
if ($Borrar) {
    [Environment]::SetEnvironmentVariable('SUPABASE_DB_PASSWORD', $null, 'User')
    Write-Host 'Contrasena guardada: borrada de tu usuario de Windows. Los scripts volveran a pedirla.'
    return
}
$seg = Read-Host 'Contrasena de la base de datos de Supabase (no se muestra)' -AsSecureString
$bstr = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($seg)
try { $clave = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($bstr) } finally { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($bstr) }
if ([string]::IsNullOrWhiteSpace($clave)) { throw 'No escribiste nada: no se guardo.' }
[Environment]::SetEnvironmentVariable('SUPABASE_DB_PASSWORD', $clave, 'User')
Write-Host 'Guardada en tu usuario de Windows como SUPABASE_DB_PASSWORD (solo tu cuenta la ve; no esta en el proyecto ni en OneDrive).'
Write-Host 'Si la cambias en Supabase, vuelve a ejecutar este script. Para quitarla: -Borrar.'
