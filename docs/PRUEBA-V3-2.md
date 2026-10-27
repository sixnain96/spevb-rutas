# Prueba en Windows: SEVB V3.2

1. Descomprime este ZIP en una carpeta **nueva**. Copia tu `.env` funcional desde V3.1 (no lo compartas).
2. **No ejecutes `docker compose down -v`**: elimina la base de datos. Si deseas, ejecuta `backup.ps1` desde el proyecto que ya funciona.
3. Ejecuta `docker compose up -d` y `./start.ps1 -NoDatabase`.
4. Abre `http://localhost:8080/api/lugares?q=Carrera%2048%20%2319-05%20Sur%2C%20Villavicencio`.
5. Revisa el JSON: `lugares`, `avisos`, `catastroConfigurado`, `arcgisConfigurado`.
6. Si se encuentra un predio, el campo `fuente` debe indicar `Catastro Villavicencio` y `precision` debe ser `PREDIO_APROXIMADO`; revisa el pin.
7. Si aparece `avisos: ["El catastro ... no respondió..."]`, comparte el aviso, no contraseñas.
8. Sin una clave `ARCGIS_API_KEY` el geocodificador profesional no se consulta; esto es deliberado.
9. Un predio es un polígono, no un punto de puerta. La nomenclatura puede no estar en la capa municipal de 2023.
10. Para medir calidad real usa al menos 20-30 direcciones conocidas con coordenadas independientes.

**Autocompletado opcional:** si configuras `ARCGIS_API_KEY` en `.env`, aparece el endpoint `/api/lugares/sugerencias?q=Carrera%2048` y el frontend ofrece sugerencias tras escribir 5 caracteres y esperar 550 ms. Al tocarlas, usa el `magicKey` correspondiente para confirmar las coordenadas mediante `findAddressCandidates`. Sin clave ArcGIS, la búsqueda continúa funcionando con catastro por botón. Nominatim público no admite autocompletado por tecla.

**Importante:** las sugerencias no certifican la precisión; hay que evaluar cada punto y `precision` con direcciones reales. Evita solicitar resultados a ArcGIS de forma masiva o guardarlos en PostgreSQL sin contratar el derecho de almacenamiento.
