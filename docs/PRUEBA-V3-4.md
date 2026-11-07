# SEVB V3.4 — Pruebas del buscador y conservación de funciones

## 1. Instalación segura desde V3.3

1. En la V3.3 funcionando, haz una copia de los datos con ` .\backup.ps1 `, siguiendo las instrucciones de `backup.ps1` y `docs/VERIFICACION-POSTGIS.md`.
2. Cierra la ejecución anterior de Spring Boot con `Ctrl+C` para liberar el puerto 8080.
3. Extrae V3.4 **en otra carpeta**; no borres V3.3.
4. Copia **solo** tu `.env` personal de V3.3 a la raíz de V3.4. Verifica que las claves privadas ya estén rotadas. **Nunca envíes ni publiques `.env`**.
5. Ejecuta `docker compose up -d` y, cuando PostgreSQL esté listo, ` .\start.ps1 -NoDatabase `.
6. Abre `http://localhost:8080` y usa `Ctrl+Shift+R` para evitar que el navegador cargue la versión antigua de JavaScript.

**No uses `docker compose down -v`** porque elimina los volúmenes con recorridos, lugares e historial.

## 2. Prueba el autocompletado nuevo

- En A escribe `Unice` y espera aproximadamente medio segundo. Debe aparecer `Unicentro Villavicencio Centro Comercial` si ArcGIS devuelve la sugerencia para Villavicencio.
- Selecciónala con clic **o** con flecha abajo y Enter. Si ArcGIS devuelve **un solo** resultado `LUGAR`, aparece el marcador directamente; si hay resultados ambiguos, elige uno del listado.
- Busca `Universidad Santo Tomás Villavicencio` como B. Comprueba que ambos marcadores están colocados antes de pulsar `Encontrar rutas cercanas`.
- Cambia A y B con el botón de flechas de intercambio; los marcadores deben seguir siendo los mismos y en sentido inverso.
- Cuando no se encuentren sugerencias, pulsa `Buscar dirección`: el catastro y la búsqueda manual siguen operativos.

## 3. Prueba direcciones por placa

- Prueba `Carrera 48 #19-15 Sur, Villavicencio`. La V3.3 ya identificó `K 48 19 15SUR EL TESORO` con tipo `PREDIO_APROXIMADO`; verifica nuevamente que aparezca con ese nivel de precisión en V3.4.
- Prueba `Carrera 48 #19-05 Sur` y `Avenida 40 #26C-10`. Es posible que ninguna fuente encuentre el **número de puerta** exacto. Si sólo ofrecen calle o barrio, el sistema **no** debe presentarlos como casa localizada.
- Utiliza `Elegir en mapa` para seleccionar coordenadas manuales; la búsqueda inversa puede cambiar el nombre, pero no la latitud ni longitud marcadas.

## 4. Revisión de funciones conservadas

- Editor `/editor.html`: visualizar, crear y modificar rutas con el token de editor.
- Catálogo `/lugares.html`: listar, crear y gestionar lugares manuales.
- Explorar recorridos, filtro por validación, lista de paraderos, mapa Leaflet y su leyenda.
- Origen A / Destino B por dirección, clic en mapa o geolocalización; intercambio A/B.
- `Encontrar rutas cercanas`, opciones, trayectos, sentido y distancias aproximadas calculadas desde PostGIS.
- Persistencia de PostgreSQL y rutas anteriores: comprueba los recorridos guardados antes y después.

**Importante:** el cálculo de distancia a las líneas de bus no equivale a una ruta peatonal real. Los recorridos registrados también deben validarse con información de transporte confiable.

## 5. Si hay problemas

- Si `arcgisConfigurado` es `false`: revisa que `ARCGIS_API_KEY` esté en `.env` y reinicia Java. No compartas la clave.
- Si `arcgisConfigurado` es `true` pero hay 0 resultados, no indica necesariamente un fallo de la API: puede ser falta de cobertura o filtrado por precisión. Prueba por nombre y revisa `avisos`.
- Si no hay sugerencias, sigue pudiendo buscar con el botón. ArcGIS `/suggest` es una ayuda, no una fuente de verdad absoluta.
- Si la aplicación no inicia por PostgreSQL, revisa credenciales y el volumen; no borres datos para solventar una contraseña.
- Puedes ejecutar `mvn verify` en IntelliJ con JDK 21. **En la preparación de este ZIP se verificó compilación de fuentes de producción, sintaxis JavaScript y simulaciones Java/navegador, pero no se hicieron pruebas en tu base de datos ni consultas en vivo con tu clave privada.**

## 6. Endpoints que se mantienen

- `/api/lugares?q=...`: resultados con fuente y precisión.
- `/api/lugares/sugerencias?q=...`: sugerencias ArcGIS, sin exponer token.
- `/api/lugares/reverso?lat=...&lng=...`: nombre informativo para una coordenada.
- `/api/viajes/planificar`: cálculo existente sobre las rutas guardadas.
- `/api/rutas`, `/api/rutas/{id}` y `/api/editor/*`: se mantienen.
