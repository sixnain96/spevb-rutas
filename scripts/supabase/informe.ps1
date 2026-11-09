# Informe de SOLO LECTURA de la base a la que apuntan DB_URL / DB_USER / DB_PASSWORD de tu sesion (no imprime secretos).
# Uso: .\scripts\supabase\informe.ps1
$ErrorActionPreference = 'Stop'
$jar = Get-ChildItem (Join-Path $HOME '.m2\repository\org\postgresql\postgresql') -Recurse -Filter 'postgresql-*.jar' -ErrorAction SilentlyContinue |
    Where-Object { $_.Name -notmatch 'sources|javadoc' } | Sort-Object { [version]($_.Directory.Name -replace '[^0-9.]', '') } -Descending | Select-Object -First 1
if (-not $jar) { throw 'No encuentro el driver de PostgreSQL en ~/.m2. Ejecuta una vez: mvn -q dependency:resolve' }
& java -cp $jar.FullName (Join-Path $PSScriptRoot 'InformeSupabase.java')
exit $LASTEXITCODE
