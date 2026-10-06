# Prueba de aceptación SPEVB V3 con PostgreSQL/PostGIS

Realiza esta prueba sobre una copia o después de ejecutar `backup.ps1`. No uses `docker compose down -v` si necesitas conservar la información actual.

1. Inicia Docker Desktop y ejecuta `docker compose up -d`. Confirma que `spevb-postgis` esté `healthy`.
2. Ejecuta `mvn clean test`; todas las pruebas deben terminar correctamente.
3. Arranca la aplicación con `.\start.ps1 -NoDatabase`.
4. Abre `/api/estado`. Debe mostrar versión `3.0.0`, base `OK`, conteo de lugares y geocodificador activo.
5. Abre `/`. Busca una dirección conocida de Villavicencio. Confirma que no devuelve resultados de otra ciudad.
6. Abre `/lugares.html`, marca un punto que no aparezca en la búsqueda, guarda un nombre con `EDITOR_TOKEN` y vuelve a buscarlo desde `/`. Debe resolverse desde el catálogo local.
7. Reinicia Spring Boot y repite la búsqueda del lugar manual. Debe seguir disponible.
8. En `/editor.html` pulsa `+ Nueva`, crea una ruta de prueba con al menos tres puntos, déjala sin validar y guárdala. Debe aparecer después en el explorador.
9. Reinicia Spring Boot. La nueva ruta debe conservar geometría, metadatos y versión.
10. Edita un punto, guarda de nuevo y revisa el historial. Debe existir la versión anterior y la versión actual debe incrementarse.
11. Abre la misma ruta en dos pestañas. Guarda en una y después intenta guardar el borrador desactualizado en la otra. La segunda operación debe recibir `409`.
12. Selecciona un origen cercano a la ruta creada y pulsa `Ver rutas que pasan cerca del origen`. La ruta debe aparecer ordenada por distancia, con un punto proyectado sobre el recorrido.
13. Selecciona un destino posterior al origen en el sentido del trazado y pulsa `Encontrar rutas cercanas`. Comprueba que se muestra la ruta completa y se resalta el tramo usado.
14. Prueba coordenadas fuera del área de Villavicencio al crear un lugar o recorrido. La API debe responder `400` y no guardar cambios.
15. Con clave incorrecta, los endpoints `/api/editor/**` deben responder `401`; la consulta pública debe continuar funcionando.
16. Ejecuta `.\backup.ps1` y verifica que se cree un `.sql` dentro de `backups/`.

## Persistencia

Cerrar IntelliJ, detener Spring Boot o ejecutar `docker compose stop` no debe borrar información. La eliminación del volumen (`docker compose down -v`) sí destruye la base y no forma parte del flujo normal.
