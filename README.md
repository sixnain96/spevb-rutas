# SPEVB – Módulo de rutas V2

Módulo académico con Java 21 + Spring Boot + PostgreSQL/PostGIS + Leaflet/OpenStreetMap.

## Mejoras V2

- Inicio y fin visibles con marcadores A/B y etiquetas permanentes.
- El inicio y el fin se calculan directamente desde `ST_StartPoint` y `ST_EndPoint` del LineString.
- Paraderos almacenados como `Point` en PostGIS.
- Campo de sentido, fuente y estado de validación.
- Línea con borde para distinguir el recorrido sobre el mapa.
- Editor local en `http://localhost:8080/editor.html` para reemplazar una geometría DEMO por un trazado denso calle por calle y guardarlo en PostGIS.
- El visor muestra exactamente la geometría almacenada: si el LineString sigue las calles, Leaflet seguirá las calles; no inventa atajos.

## Arranque

```powershell
docker compose up -d
```

Ejecutar `SpevbRutasApplication.java` desde IntelliJ y abrir:

- Visor: http://localhost:8080
- Editor: http://localhost:8080/editor.html
- API: http://localhost:8080/api/rutas

## Importante sobre exactitud

El software puede representar una ruta con precisión de coordenadas, pero la exactitud operacional depende de la geometría que se cargue. Para llamar una ruta “oficial vigente”, valida el recorrido con Secretaría de Movilidad/operador o con un levantamiento GPS autorizado. Un recorrido textual histórico puede servir como referencia, no como prueba de vigencia actual.

## Base ya existente

`schema.sql` usa `ALTER TABLE ... ADD COLUMN IF NOT EXISTS`, por lo que puede actualizar la base de la versión anterior sin borrar el volumen. Si quieres iniciar completamente limpio (solo si no necesitas conservar datos del prototipo):

```powershell
docker compose down -v
docker compose up -d
```
