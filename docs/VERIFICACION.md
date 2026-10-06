# Verificación técnica de SPEVB V3

## Qué debe ejecutarse antes de integrar a `main`

Según la Definition of Done de `ESTANDARES.md`:

```powershell
mvn clean test
docker compose up -d
docker ps
.\start.ps1 -NoDatabase
```

`mvn clean test` debe terminar sin errores, `spevb-postgis` debe aparecer como `healthy` y la
aplicación debe responder en `http://localhost:8080/api/estado` con la versión `3.0.0`.
Después se completa la prueba de aceptación de `docs/VERIFICACION-POSTGIS.md`.

## Pruebas automáticas incluidas

Nueve clases de prueba en `src/test`: 52 métodos `@Test` y una prueba parametrizada con 11
entradas. El número exacto de pruebas ejecutadas es el que reporta Maven al final de `mvn clean test`.

Cubren:

- normalización de direcciones colombianas (`CityAreaTest`);
- validación de geometrías GeoJSON;
- versión optimista e historial de recorridos;
- reglas de validación y fuente;
- límites espaciales de Villavicencio;
- planificador de viaje: ruta más cercana sin límite de distancia y sentido de circulación;
- búsqueda geográfica configurable;
- endpoints de rutas, viajes y lugares;
- autenticación, tamaño y tipo de contenido del editor.

## Límites que deben tenerse presentes

- Nominatim/OpenStreetMap no garantiza que exista cada nomenclatura de Villavicencio. La pantalla
  `/lugares.html` cubre ese caso permitiendo registrar el punto manualmente.
- El planificador calcula viajes directos sobre los recorridos almacenados; no calcula transbordos.
- Las líneas punteadas son distancias en línea recta, no caminos peatonales.
- La cercanía a una línea no equivale a un paradero autorizado.
- Las rutas dibujadas manualmente permanecen "pendientes de validar" hasta contar con evidencia.
