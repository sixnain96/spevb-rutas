# Pruebas · SEVB 4.13.1

| Qué | Cómo | Necesita |
|---|---|---|
| Suite Java (unitarias, controladores, versión, configuración, SW estático) | `mvn clean verify` | Java 21, Maven. **Sin** `-javaagent` manual: Maven carga el agente de Mockito (`maven-dependency-plugin` + `argLine`). |
| Migraciones con PostgreSQL/PostGIS real (`MigracionesPostgisTest`, 3 pruebas) | dentro de `mvn verify` | Docker (Testcontainers). **Sin Docker, o con `mvn clean verify "-Dsevb.sinDocker=true"`, se OMITEN** y aparecen como omitidas, no como aprobadas. Con Docker disponible se ejecutan solas. Integración opcional |
| Sintaxis de todos los JavaScript | `python e2e/js_syntax.py` | `pip install playwright`, Edge o Chrome |
| Navegador real + service worker (**sin Docker**, modo predeterminado) | `python e2e/run_e2e.py` | `pip install playwright`, Edge o Chrome. Arranca `e2e/stub_server.py`: frontend real con API simulada, datos fijos, sin Internet ni base de datos. Prueba el JavaScript, el service worker y las cabeceras CSP; **no** el backend Java |
| Navegador contra la aplicación real (opcional) | `SEVB_E2E_DB_URL=jdbc:postgresql://127.0.0.1:5432/sevb_e2e`, `SEVB_E2E_DB_USER`, `SEVB_E2E_DB_PASSWORD` y luego `python e2e/run_e2e.py` | `mvn -DskipTests package` y una base PostGIS **dedicada** de pruebas (nombre terminado en `_e2e` o `_test`, en este equipo). No usa Docker; nunca toca otra base |
| Paquete de entrega | `.\scripts\package-release.ps1` | PowerShell |
| Restauración de un respaldo | `.\scripts\verify-restore.ps1 <archivo>` | Docker (opcional; es una comprobación operativa, no parte de las pruebas) |


## Qué cubre el navegador (`e2e/sevb_e2e.py`)
Abrir la aplicación; «Solo revisadas» ausente; búsqueda de rutas; cambiar/intercambiar origen y destino; resultado directo; transbordos; «Baja y camina» y «Una sola ruta» (se fuerza la variante en la respuesta del servidor; la decisión de cuándo ocurre la prueba `TransbordoServiceTest`); paraderos apagados al inicio y activar/desactivar; puentes solo si intervienen en la caminata y `/api/puentes` sin campos extra; «Seguir bus» (el mapa sigue arrastrable, el recentrado automático no cancela, el arrastre con **ratón, toque y lápiz** cancela, «Soltar», no se reactiva ni se recentra); 2D ↔ sprites y respaldo CSS 3D con el sprite bloqueado; `prefers-reduced-motion`; GPS autorizado / rechazado / lento / no disponible; ancho móvil y escritorio sin desborde; sin errores de consola ni promesas rechazadas (`unhandledrejection`).

## Service worker (`e2e/sw_e2e.py`)
Navegación normal; con query string (no deja origen/destino/coordenadas/nombres en Cache Storage y la URL visible conserva sus parámetros); varias URL compartidas sin copias duplicadas; sin conexión (shell desde la clave canónica, `/api/*` sin interceptar, estáticos disponibles); conexión normal (la solicitud original llega al servidor); 404 no se guardan; actualización: se eliminan cachés antiguas y claves con parámetros.

## Limitaciones conocidas
Las pruebas de navegador no usan internet (las teselas externas se sustituyen por imágenes vacías). Los GPS reales y la calidad de las rutas con datos reales siguen necesitando prueba de campo.

## Seguridad de dependencias (sin Docker)
```powershell
mvn -B dependency:tree "-Dincludes=org.apache.tomcat.embed,tools.jackson.core"      # Tomcat 11.0.25 y Jackson 3.1.7
mvn -B org.cyclonedx:cyclonedx-maven-plugin:2.9.1:makeAggregateBom -DskipTests      # target/bom.json (CycloneDX)
python -I scripts/osv_scan.py target/bom.json                                       # API oficial de OSV; exit 1 si hay hallazgos, 2 si no pudo consultar
```

## Editor (4.10.1)
`python e2e/editor_e2e.py` (lo ejecuta `run_e2e.py`): selección del vértice 52 de 1.500, clic cerca de la línea, navegación, mover/deshacer/rehacer, insertar y eliminar, atajos, ruta de 10.000 puntos (tiempos medidos dentro del navegador), toque y lápiz, cambio de código con alias y favoritos, duplicado y conflicto 409, edición de puentes (cancelar, guardar, conflicto), revisión de paraderos, ajuste de un vértice y de la ruta completa, anclas y auditoría. En Java: `ReglasBusTest`, `RedVialBusTest`, `MapMatchingBusTest`, `ProveedorMatchingExternoTest`, `RedVialBusServiceTest`, `VialEditorApiTest`, `RutaCodigoAliasTest`, `PuenteEdicionTest`. Con `-Dsevb.sinDocker=true` las pruebas de migración PostGIS (ahora V1–V10) se omiten.

## Borrar A y B (4.10.1)
`python e2e/puntos_e2e.py` (lo ejecuta `run_e2e.py`): borrar solo A, solo B y ambos; búsqueda manual, sugerencias, geocodificación inversa y lectura GPS **pendientes** (las respuestas se retienen con Playwright y se liberan después de borrar para comprobar que no restauran el punto); intercambiar con solo A, solo B o ninguno; borrar el texto con el teclado; etiquetas, orden de tabulación, Escape y Enter; ancho de escritorio, 390 px y 320 px sin desborde y con X de 44×44 px en pantallas táctiles; favoritos, historial y viajes guardados intactos.

## Mejores opciones, desplazamiento móvil y aviso de elegir punto (4.10.2)
`e2e/resultados_e2e.py` (13 pruebas, navegador real): dos recomendaciones compactas (sentido normal / contrario) sin duplicados, grupos secundarios cerrados con su cantidad, selección inicial determinista y dibujada una sola vez sin abrir tarjetas, un solo sentido o solo transbordos, estabilidad del `scrollTop` en 384×848 con gestos táctiles reales (CDP), resize/visualViewport y apertura de tarjetas, cancelación del posicionamiento automático ante toque o respuesta asíncrona, panel automático, movimiento reducido, 320×568 a 430×932 sin desborde, aviso `pick` abajo a la izquierda sin tapar controles ni pin, Confirmar/Cancelar, teclado/Escape y escritorio.

## Estados obsoletos, respuestas tardías y service worker ante 5xx (4.10.3)
`python e2e/estados_e2e.py` (16 pruebas, lo ejecuta `run_e2e.py`): selección y aviso de bajada al abrir otra ruta, carga fallida, cambio de tarjeta, borrar A / B / ambos (bus, capas, ficha, URL, pines, centro y zoom, favoritos, historial), ruta retenida liberada tras borrar, recalcular los mismos puntos con el cálculo retenido, cálculo fallido o sin rutas, historial con directas + transbordos / solo transbordo / solo contraria / sin recomendación, `?ruta=` y enlaces compartidos. `python e2e/sw_e2e.py` suma 3 pruebas: 5xx con copia, 404/400/401/403 sin copia vieja y error original sin copia, y claves sin parámetros. Java: `EntregaPortableTest` (rutas personales en Blender e identificación única del geocodificador).
Conteo de navegador: 20 + 13 + 16 + 11 + 16 + 10 = **86** (antes 67). En PowerShell el comando Java es `mvn clean verify "-Dsevb.sinDocker=true"` (sin comillas falla).

## Modo viaje (4.11.0)
Tres suites nuevas, todas sin Docker y sin salir a la calle (detalle en `docs/MODO-VIAJE.md`): `python e2e/viaje_logica_e2e.py` (21, lógica y filtro con recorridos GPS simulados), `python e2e/viaje_e2e.py` (25, interfaz con un `navigator.geolocation` controlable) y `python e2e/gpx_e2e.py` (4, generador de GPX y emulador por línea de comandos). Las ejecuta `run_e2e.py`. Desde la 4.13.1 también corre `python e2e/movil_e2e.py` (9, teclado de iPhone/Android simulado, campos, 16 px, ventanas, listas de sugerencias y ausencia de choques en 8 modelos de celular; el iPhone se simula con un `visualViewport` falso, no es un dispositivo real).
Conteo de navegador: 20 + 13 + 16 + 21 + 25 + 4 + 17 + 11 + 16 + 10 = **153**.

## UX móvil 4.14 (`e2e/ux414_e2e.py`)

`python e2e/ux414_e2e.py` (lo ejecuta `run_e2e.py`; 36 casos, navegador real sobre el stub, **Google simulado** con rutas de Playwright, GPS simulado): prioridad de menús (menú desde planificación, menú durante un viaje con scroll y GPS intacto, diálogo desde el menú y regreso, tarjeta oculta que no se reabre, resultados con el menú abierto, aviso importante con el menú abierto, aperturas repetidas sin bloqueos), gestos (cancelar el asa sin inercia, otro puntero), A/B con Google (toque, teclado, *pointerdown→blur→pointerup*, desplazamiento que no elige, doble toque, cambiar de campo, respuestas retenidas y liberadas, borrar/intercambiar mientras carga, fallo del detalle con «Reintentar», reenfocar, coordenadas y mapa), solo Google (sin resultados, error, lento, sin configurar, **ninguna petición a ArcGIS/Nominatim**), «Volver al inicio» (desde resultados, cancelar y confirmar en viaje, pendientes, repetido, recarga, menú; sin tocar favoritos/historial/viajes guardados) y diseño (8 tamaños incluidos tabletas y apaisado con nombres largos, texto ampliado, modo oscuro con contraste ≥ 4,5, tarjeta con tope de altura y pie alcanzable, ventana estrecha con ratón, teclado). En Java: `SoloGoogleConfiguracionTest`, `LugarServiceGoogleTest`, `GoogleDireccionesServiceTest`, `LugarApiTest`. Detalle y límites: `docs/UX-MOVIL-4.14.md`.
