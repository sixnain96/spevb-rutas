# Verificación de direcciones — SPEVB V3.1

## Importante antes de comenzar

Esta versión conserva la base PostgreSQL/PostGIS de V3. Antes de tocar el proyecto haz una copia con `./backup.ps1` y guarda tu `.env`. La descarga distribuida **no incluye contraseñas ni `.env`**. No uses `docker compose down -v` porque eso eliminaría el volumen de la base.

La geocodificación catastral encuentra el *centroide del predio*, no necesariamente la entrada de la casa. Si el resultado dice `CALLE_APROXIMADA`, sólo es un punto sobre la calle. Verifica cada marcador en el mapa. Una puntuación alta de un proveedor tampoco demuestra que la puerta esté en el sitio correcto.

## Instalación en Windows / IntelliJ

1. Extrae el ZIP en una carpeta nueva para no perder el proyecto anterior.
2. Copia tu `.env` desde V3 a esta carpeta, sin compartirlo ni subirlo a GitHub.
3. En tu `.env`, agrega:

   ```env
   VILLAVO_CATASTRO_URL=https://services7.arcgis.com/c0V1cyhKZwmv8Gtf/ArcGIS/rest/services/U_TERRENO/FeatureServer/1/query
   # ARCGIS_API_KEY=TU_CLAVE_DE_ARCGIS_SI_DECIDES_USARLO
   ```

4. Abre Docker Desktop, luego en IntelliJ usa `docker compose up -d`.
5. Inicia con `./start.ps1 -NoDatabase` y espera a que Spring Boot muestre que está listo.
6. Abre `http://localhost:8080` y prueba el origen A y destino B por dirección escrita.
7. Si necesitas comprobar la búsqueda sin interfaz, abre en el navegador:

   `http://localhost:8080/api/lugares?q=Carrera%2027%20Norte%205A-50`

   Examina `lugares`, `lat`, `lng`, `fuente`, `precision`. Un arreglo vacío significa que **no se encontró**; no se debe ubicar un punto de forma arbitraria. El ejemplo no garantiza que ese inmueble esté en el catastro.

8. Ejecuta `mvn verify` en la terminal de IntelliJ. El test `DireccionesHibridasServiceTest` usa un servidor local simulado, por lo que no gasta cuotas comerciales.

## Plan de pruebas reales, mínimo 30 casos

Preparar una hoja con las columnas: `consulta`, `dirección de referencia`, `fuente`, `precisión informada`, `lat`, `lng`, `distancia real a referencia (m)`, `aceptable`, `observación`.

Prueba cinco grupos de seis direcciones: casas con número, direcciones con letras y orientación (Sur/Norte), instituciones o comercios, nomenclaturas inexistentes, barrios/sectores. Usa direcciones auténticas de Villavicencio que puedas ubicar independientemente (por ejemplo con visitas o una fuente institucional fiable). No registres domicilios privados en informes públicos.

Se considera éxito **sólo** cuando el marcador seleccionado coincide con el lugar real dentro del margen que el equipo haya definido antes de la prueba. Un centroide de predio se compara con el predio y NO con la entrada. Los fallos deben registrarse tal cual, sin corregirlos a posteriori ni inferir un 100% de cobertura.

## Diagnóstico rápido

- **`/api/estado` muestra CONFIGURADO:** significa que existe un proveedor definido, no que Internet esté funcionando.
- **No hay direcciones del catastro:** valida conectividad y la URL configurada; algunas respuestas pueden estar vacías según la nomenclatura disponible o los permisos del servicio.
- **Error del servicio externo:** confirma primero que `curl` o el navegador lleguen al servicio desde tu equipo y que la API key de ArcGIS tenga permiso para geocodificar.
- **La dirección aparece pero el pin no está en la puerta:** puede tratarse del centro de predio o una dirección interpolada. Selecciona manualmente la entrada y guarda un lugar verificado con el editor.
- **Bus no aparece aunque A y B estén bien ubicados:** revisa que las rutas estén dibujadas cerca de ambos puntos y que el sentido de circulación sea correcto; el geocodificador no crea recorridos.

## Fuentes de API y licenciamiento

- [Capa municipal U_TERRENO](https://services7.arcgis.com/c0V1cyhKZwmv8Gtf/ArcGIS/rest/services/U_TERRENO/FeatureServer/1)
- [ArcGIS Geocoding REST — findAddressCandidates](https://developers.arcgis.com/rest/geocode/find-address-candidates/)
- [Política de Nominatim](https://operations.osmfoundation.org/policies/nominatim/)

La implementación no habilita autocompletado automático porque el servidor público de Nominatim no lo permite y las búsquedas pagadas consumirían créditos. Para agregar sugerencias mientras escribes se deberá crear un endpoint separado que use las opciones de ArcGIS según el plan contratado.
