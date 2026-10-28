# SEVB V3.3 — Prueba con direcciones catastrales reales

## Qué cambió

En la capa `U_TERRENO` de Villavicencio, las direcciones pueden estar escritas como:
- `K 48 19 15SUR EL TESORO` → Carrera 48 #19-15 Sur
- `K 48 19 16SUR CS 41 BR MONTECARLO` → Carrera 48 #19-16 Sur
- `C 48 52 19 BR CHAPINERITO` → Calle 48 #52-19

La V3.2 no interpretaba correctamente `K`/`C` ni separaba `SUR` del número. V3.3 corrige ambas operaciones y evita confundir la placa con números posteriores de manzana o lote.

## Arranque sin borrar datos

1. Descomprimir V3.3 en nueva carpeta.
2. Copiar el `.env` funcional desde V3.2, sin pegar sus contraseñas en chats ni subirlas al repositorio.
3. Iniciar Docker Desktop y ejecutar:

```powershell
docker compose up -d
.\start.ps1 -NoDatabase
```

4. Visitar `http://localhost:8080`.
5. En origen buscar `Carrera 48 #19-15 Sur, Villavicencio`.
6. En API abrir:

`http://localhost:8080/api/lugares?q=Carrera%2048%20%2319-15%20Sur%2C%20Villavicencio`

7. Verificar `fuente` = Catastro Villavicencio y `precision` = `PREDIO_APROXIMADO`. Cuidado: la geometría publicada es **centro de predio**, no acceso/puerta.
8. Probar `Carrera 48 #19-05 Sur, Villavicencio`. La dirección oficial de USTA Loma Linda no apareció en las 93 filas devueltas por el filtro `%48%19%`; no inventar la coordenada si no la encuentra otra fuente.

## Cómo mejorar la cobertura

Si una dirección legítima no aparece en el catastro, activa ArcGIS Geocoding en `.env` con `ARCGIS_API_KEY=...`, respetando permisos, uso y límites de la cuenta. El código tiene integrada la búsqueda/sugerencias de ArcGIS, pero su cobertura efectiva de Villavicencio requiere pruebas con la clave real.

## Atención

- Si la API no devuelve nada para 19-15, envía el JSON de `/api/lugares` sin contraseñas y revisamos la geometría/respuesta del servicio.
- No ejecutar `docker compose down -v` porque elimina el volumen de la base de datos.
- No almacenar resultados de ArcGIS sin permiso específico (`forStorage`), ni su clave en el navegador.
