## 4.13.5 · Mapa de Google: el panel del bus ya no tapa el mapa (zoom y arrastre con el panel oculto)
**Frontend; sin cambios en backend ni base.** Reportado: «cuando escondo el menú de rutas no deja hacer zoom ni nada».
- **Causa confirmada con el SDK REAL de Google** (390 × 844, dedos táctiles reales por CDP): al planificar una ruta aparece el control del bus (`.bus-panel`). Google monta los controles dentro del mapa y les escribe en línea `position:absolute; right:0; top:0`; con el `bottom` de nuestro CSS el panel se ESTIRABA: 378 × 282 px con el panel visible y **378 × 768 px con el panel oculto**. Aunque solo se viera un botón, esa caja transparente (z-index 900, `pointer-events:auto`) se quedaba el arrastre y el pellizco de casi todo el mapa. Con Leaflet no pasaba (otro montaje de controles).
- **Arreglo:** `css/mapa.css` fija el panel del bus (`position: fixed`, `top/right: auto`, `height: auto`) con Google; ahora mide su contenido (50 × 50 px colapsado). Verificado con el SDK real: con ruta y panel oculto el arrastre mueve el mapa y el pellizco hace zoom, también tras ocultar/mostrar varias veces.
- Pruebas: el SDK simulado (`google_fake.js`) ahora monta los controles dentro del mapa con los mismos estilos en línea que Google (así reproduce el defecto: falla sin el arreglo); `layout_panel_e2e.py` mide el panel del bus y comprueba qué elemento recibe el toque en cada punto del mapa; `panel_gestos_e2e.py` prueba arrastre y pellizco reales con el panel oculto (botón y deslizando).

## 4.13.4 · Panel: zoom de página ≠ teclado, arrastre desde toda la cabecera, sin zoom de página sobre el mapa
**Frontend; sin cambios en backend ni base.** Reportado con un video en Android Chrome (Google Maps): el panel quedaba pequeño y no se podía agrandar.
- **Zoom de página leído como teclado (causa confirmada).** Con un pellizco de página el visual viewport se encoge aunque no haya teclado; `sheet.js` lo interpretaba como un teclado de ~390 px (`--kb`), levantaba el panel y calculaba sus alturas con un alto falso. Ahora, con `visualViewport.scale > 1.05`, no hay teclado y el panel usa la ventana normal.
- **Arrastre desde toda la cabecera.** El logotipo (un enlace) bloqueaba el arrastre: ahora se arrastra desde cualquier parte salvo botones y campos, y el clic posterior a un arrastre no navega.
- **Sin zoom de página sobre el mapa.** `touch-action: none` en el área del mapa: el pellizco lo maneja el mapa, no el navegador (antes, un pellizco que empezaba sobre un marcador o rótulo HTML agrandaba toda la interfaz, como se ve en el video).
- Pruebas nuevas (`panel_gestos_e2e.py`): arrastre con dedo REAL (eventos táctiles de CDP) con y sin el buscador activo, zoom de página sin falso teclado y `touch-action`.

## 4.13.3 · Mapa y panel: cada gesto responde solo a lo suyo; caminatas animadas
**Frontend; sin cambios en backend, base ni contratos.** Rama `feature/panel-gestos`. La versión sube para que el teléfono descarte el código anterior (`spevb-v4.13.3-mapa2`).
- **Panel que quedaba casi a pantalla completa tras elegir A (corregido).** Causa confirmada: enfocar un campo llevaba el panel a «full» (94 %) y nada lo devolvía al confirmar el punto o salir del campo. Ahora `sheet.js` separa el estado **manual** (lo que la persona eligió con el asa, el arrastre o un botón), el ajuste **temporal** para escribir (sube a «half», y a «full» solo si el teclado virtual está realmente visible; se deshace al confirmar o salir) y el **efectivo**. Una ampliación manual deliberada se respeta. `sheet.dataset.motivo` registra el motivo de cada transición.
- **Arrastre del panel aislado.** Empieza solo en el asa o la cabecera con el botón principal / primer dedo; sigue únicamente a su `pointerId`, con los oyentes en la propia zona (no en `window`); `pointercancel`, pérdida de captura y pérdida de foco de la ventana lo cancelan y devuelven una posición coherente (sin «lanzamiento»); el clic posterior a un arrastre o gesto cancelado no vuelve a cambiar la altura. `panel-ocultable.js` aplica la misma regla.
- **«Ahora escribe B»** sigue siendo solo orientativo: no enfoca ni abre el teclado ni maximiza el panel.
- **Caminatas animadas con Google:** contorno fino, color propio por tema día/noche, símbolos que avanzan en el sentido real de cada tramo (A → primera subida, bajada → siguiente subida, última bajada → B) con un solo controlador compartido (pausa con la página oculta y durante gestos; con movimiento reducido: estática con flechas de sentido; con un viaje en curso solo se anima la más cercana a la persona; el cambio día/noche no reconstruye nada). Color de caminata unificado (`#1b2a33` de día, `#fff4b8` de noche).
- Pruebas nuevas: `e2e/panel_gestos_e2e.py` (12) y 3 pruebas de caminatas en `e2e/mapa_adaptadores_e2e.py`.

## 4.13.2 · Ajustes para celular: layout del mapa, búsqueda con Google primero y recomendación por acceso al primer bus
**Backend + frontend; sin migraciones ni cambios en la base de datos.** Rama `feature/google-ajustes`. La versión sube para que el teléfono descarte el código anterior (el service worker usa `spevb-v4.13.2-mapa2`).
- **Mapa reducido a una franja al ocultar el panel (corregido).** Causa confirmada: `--sheet-real` solo lo escribía `sheet.js` según peek/half/full y no sabía que `panel-ocultable.js` ocultaba el panel. Ahora `sheet.js` es la ÚNICA fuente de verdad (`--sheet-real`, `--sheet-visible`, `--sheet-hidden`), se recalcula con el evento `spevb-panel`, con `resize`, teclado, orientación y `visibilitychange`, y vale 0 con el panel oculto. El mapa conserva centro, zoom y elementos (no se recrea ni se reencuadra). La tarjeta del viaje, su pastilla y los avisos quedan por encima del botón «Buscar ruta»; con Google los botones no tapan el logotipo ni los créditos. `resize()` del adaptador Google ahora avisa también al SDK.
- **Recomendación por acceso al primer bus** ([docs/RECOMENDACION.md](docs/RECOMENDACION.md)): política única en el backend (`PoliticaRecomendacion`, `Recomendador`), `recomendacion` con ids, ranking, etiquetas y motivo en `/api/viajes/planificar`; el frontend ya no aplica orden propio. `TransbordoService` conserva por par de rutas también el candidato de subida más cercana y ya no descarta un transbordo por «camina hasta la segunda ruta» si reduce mucho la caminata inicial. Versión de la política en la caché del servidor y en «Recientes».
- **Búsqueda: Google primero** ([docs/DIRECCIONES.md](docs/DIRECCIONES.md)): cascada Google → ArcGIS → local (Nominatim solo en búsqueda explícita), estados tipados (`estado`, `proveedor`, `intentos`), presupuesto de tiempo y cortacircuito por proveedor, elección de sugerencia sin sustitución silenciosa (`eleccionFallida`), `sessionToken` de Places por búsqueda y máscara de campos mínima.
- **Dirección confirmada estable:** estados por campo A/B, cancelación de debounce y solicitudes al confirmar, sin consultas por foco/blur ni durante la composición de texto.
- **Rendimiento/cámara:** estela del bus que solo crece (se agregan puntos sin reconvertir), zoom manual respetado al seguir, marcas de tiempo de carga (`sevb-*`), guía de medición en `e2e/rendimiento/medir_mapa.py`.
- Pruebas nuevas: `RecomendadorTest`, `LugarServiceCascadaTest`, `ProveedoresEstadosTest`, `e2e/layout_panel_e2e.py`, `e2e/busqueda_e2e.py`. Expectativas antiguas cambiadas a propósito: ver docs/RECOMENDACION.md y el informe de la entrega.

## 4.13.2 · Capa de mapa con proveedor intercambiable (Leaflet | Google Maps) — para evaluar
**Frontend + configuración; sin cambios en la base de datos ni en los contratos de la API (solo se añade `GET /api/config/mapa`).** Rama `feature/google-maps`.
- **Capa de mapa propia** (`js/mapa/`, [docs/MAPA-CAPA.md](docs/MAPA-CAPA.md)): ningún módulo usa `L.*` ni `google.maps.*`; hablan con una interfaz pequeña que implementan `adaptador-leaflet.js` (anterior) y `adaptador-google.js` (nuevo). Solo se descarga el del proveedor elegido (Google no descarga Leaflet ni MapLibre y viceversa).
- **`MAP_PROVIDER=leaflet|google`** (+ `GOOGLE_MAPS_BROWSER_API_KEY`, `GOOGLE_MAPS_MAP_ID`, `GOOGLE_MAPS_JS_VERSION`), separadas de `GOOGLE_MAPS_API_KEY` (servidor, Places). Predeterminado `leaflet`: revertir es cambiar la variable y reiniciar.
- **Errores recuperables:** clave ausente/inválida, sitio no autorizado, API o facturación, cuota, red, bloqueo, Map ID; «Reintentar» sin recargar; nunca se cambia de proveedor en silencio.
- **CSP por proveedor** (`ContentSecurityPolicy.java`), **service worker** que solo guarda los archivos del proveedor en uso y no toca los dominios de Google, **«Guardar mapa» oculto con Google**, mapa que termina sobre el panel móvil para no tapar el logotipo de Google, «Seguirme» y bus centrados en el área libre (no bajo la tarjeta).
- **Direcciones de Google (Places)** solo con mapa de Google (condiciones de Google); con Leaflet quedan apagadas.
- Pruebas nuevas: `MapaConfigTest`, `GoogleDireccionesProveedorTest`, `e2e/mapa_adaptadores_e2e.py` (SDK **simulado**), `e2e/mapa_google_real_e2e.py` (SDK real, parcial). **No se probó con una clave real de Google ni en teléfonos físicos.** Detalle y pasos pendientes: [docs/GOOGLE-MAPS.md](docs/GOOGLE-MAPS.md).

## 4.13.1 · Modo viaje, aviso de «caminar es más rápido» (revisión)
**Solo frontend; sin cambios en el backend, la base ni las migraciones.**
- **Opción que no ahorra tiempo (captura de un celular: «Ruta HOSPITAL · 53 min · caminas 4,1 km»).** Cuando ninguna ruta registrada pasa cerca de A y de B, la opción «más cercana» puede obligar a caminar kilómetros para subir y bajarse lejos de B. No se pudo reproducir el punto de bajada exacto de la captura (hace falta el A/B y la base real; no se probó contra ella): lo que sí se comprobó es que el cálculo es el esperado (la ruta más cercana, con el punto de bajada que menos hace caminar) y que la interfaz no avisaba de que ir caminando directo habría tardado igual o menos. Ahora, si el tiempo de la opción es igual o mayor que caminar de A a B (línea recta × 1,3 a 80 m/min, solo como referencia), la tarjeta, el resumen de «Iniciar viaje» y el detalle lo dicen («caminar directo ≈ N min»). La opción no se oculta ni se cambia en silencio. Prueba: `resultados_e2e.py::si_caminar_directo_tarda_igual_o_menos_que_la_ruta_se_avisa_sin_ocultar_la_opcion`.
- **Avisos de bajada por recorrido, no por línea recta (`viaje-nucleo.js`).** Antes, un bus que pasaba a menos de 70 m en línea recta del punto de bajada (retorno, ruta en «U», calle paralela) disparaba «¡Bájate ahora!» y hasta «te pasaste» mucho antes de llegar. Ahora manda la distancia que falta por el tramo; la distancia directa solo cuenta si ya se recorrió casi todo (a menos de 1,5 km del final) y la persona está fuera del tramo. La posición sobre el tramo no puede adelantarse más de 200 m + 25 m/s × tiempo entre lecturas sin confirmarse en 3 lecturas seguidas (un salto del GPS hacia una parte posterior de la ruta no es avance). «Te pasaste» exige haber recorrido casi todo el tramo. Prueba nueva con un tramo en «U» (falla con el código anterior: avisos prematuros y un «se pasó» falso).
- **Textos de bajada.** «Te acercas a tu punto de bajada», «Prepárate para bajar: faltan unas N cuadras» y «Tu punto de bajada está aquí cerca. Baja cuando el bus se detenga.» (antes «¡Bájate ahora!», que sugería bajar en movimiento). Las expectativas de `viaje_e2e.py` se actualizaron por este cambio de texto deliberado.
- **«Ya me subí» / «Ya me bajé».** Botones manuales de 46 px en la tarjeta (solo en la fase que corresponde, con una ubicación válida y sin pregunta abierta), por si el bus va lento y el detector no se activa. Usan las mismas transiciones del núcleo (`confirmarSubida()` / `confirmarBajada()` validan la fase y no repiten estados; con transbordo pasan al segundo bus y reinician sus avisos).
- **Señal sana solo con posiciones válidas (`viaje.js`).** Las lecturas descartadas por el filtro (imprecisas, repetidas, saltos) ya no reinician el reloj de «sin señal»: si solo llega ruido, a los 20 s se avisa «Perdí tu ubicación». Prueba que falla con el comportamiento anterior.
- Pruebas: lógica 21 → 25, interfaz del viaje 25 → 27, resultados 19 → 20.

## 4.13.1
**Corrección vista en un iPhone: «null is not an object (evaluating 'routes.filter')» al buscar rutas.** Si la lista general de rutas (`/api/rutas`) llegaba como algo que no es una lista (la página de aviso de un túnel como ngrok gratis, o una respuesta vacía), `routes` quedaba en `null` y «Buscar rutas» fallaba con un mensaje técnico aunque el cálculo del viaje estuviera bien.
- `app.js`: la lista solo se acepta si es un arreglo; si no, queda vacía, se muestra «El servidor no devolvió la lista de rutas…» con el botón de reintento y se reintenta sola a los 5 s. `renderList` ya no falla con `null`, así que buscar de A a B funciona aunque esa lista falle.
- `common.js`: las llamadas a la API envían `ngrok-skip-browser-warning` (solo mismo origen) para evitar la página de aviso de ngrok gratis.
- Prueba nueva `si_la_lista_de_rutas_llega_mal_buscar_de_A_a_B_sigue_funcionando` (falla con el código anterior con el mismo error, pasa con el arreglo). No se pudo comprobar en el iPhone real que la causa fuera el túnel; es la explicación que cuadra con el síntoma.
- **Prueba (sin cambio de comportamiento):** `MigracionesPostgisTest.baseExistenteCreadaConSchemaSqlSeAdoptaSinPerderDatos` contaba `ruta_aliases` (creada por V9, 4.10) en la base histórica 4.9.6, donde aún no existe. Ahora cuenta antes solo las tablas del esquema antiguo, comprueba que `ruta_aliases` se crea vacía tras migrar y sigue exigiendo que no se pierda ni duplique ninguna fila. Migraciones y código de la aplicación sin cambios.
- **Punto de subida y bajada más cercano de verdad (`RedPeatonalService.distancias`).** Al medir la caminata a cada punto de la ruta, cada punto se pegaba a UNA sola calle (la más cercana). Junto a vías de doble calzada o ramales sin conexión cercana (p. ej. el Anillo Vial en Serramonte) esa calle podía no tener camino corto hasta A, el punto se descartaba y el cálculo escogía un punto de subida mucho más lejos (en pruebas con datos reales: 1 568 m en lugar de 539 m; 1 345 m en lugar de 282 m). Ahora cada punto prueba hasta 5 calles cercanas (como ya hacía el origen y la línea punteada dibujada) y se queda con la que sí se alcanza caminando. Lo usan por igual los viajes directos, las bajadas y los transbordos. Prueba nueva `unPuntoPegadoAUnaCalzadaSinConexionCercaUsaLaCalleVecina` (con el código anterior da ≈ 3,9 km; ahora ≈ 0,56 km).
- **Pantalla principal más simple (solo CSS y orden del HTML; ningún `id` ni JavaScript cambió).** «Historial y viajes guardados», «Mis lugares», «Rutas cerca del origen» y la «Guía de colores» pasaron al menú lateral (☰). Campos y botones principales un poco más grandes (en pantallas bajas conservan su altura compacta).
- **Nombre popular de la ruta («Catama», «Kirpas», «la 876»).** En Villavicencio la gente no dice «R08» sino el nombre grande del cartel del bus. Nuevo campo opcional en el editor («Nombre popular», hasta 60 caracteres; migración `V11__ruta_nombre_popular.sql`, aditiva) y consulta pública `GET /api/rutas/nombres` (código → nombre; el cliente la guarda para usarla sin conexión). Donde antes se leía «R08» (insignias de los resultados, «Sube aquí · …», «Bájate de la ruta …», transbordos, tarjeta y avisos del modo viaje, «Iniciar viaje», buscador del explorador de rutas, WhatsApp) ahora se lee el nombre popular; si la ruta no tiene, se sigue viendo el código. Los códigos, favoritos, historial y reportes no cambian. `PUT /api/editor/rutas/{id}/nombre-popular` guarda o borra el nombre. Pruebas: `RutaServiceTest` (2), `MigracionesPostgisTest` (V11) y `e2e/mejoras414_e2e.py`.
- **Mapa de noche automático** (menú ☰ → Pantalla): de 6:30 p. m. a 5:30 a. m. el mapa se oscurece solo; se puede dejar «Siempre claro» o «Siempre oscuro». Solo CSS sobre el panel de teselas (`js/confort.js`).
- **Tamaño de la letra** (Normal / Grande / Muy grande) en el menú ☰, con botones «A». Se recuerda en el dispositivo.
- **Línea de tiempo en cada opción:** «🚶 4 min → 🚌 12 min → 🚶 2 min» (con «unas N cuadras» en las caminatas), también en los transbordos, con iconos propios.
- **«Ir a Casa» / «Ir al Trabajo»** (`js/atajos.js`): dos botones grandes en el menú ☰ → «Mi espacio» (no en la pantalla principal). La primera vez piden la dirección y la guardan solos en «Mis lugares»; después, un toque pone el destino.
- **«Mis viajes» (logros y racha)** en el menú ☰ (`js/logros.js`): cuenta los viajes terminados con el modo viaje, la racha de días seguidos y 8 logros sencillos. Se guarda en el dispositivo. El modo viaje avisa al llegar con el evento `spevb-viaje-llegada`.
- **Compartir por WhatsApp** (botón en las herramientas del viaje): abre WhatsApp con el mensaje ya escrito («Voy de … a … en la ruta Catama (unos 25 min)» y el enlace).
- **Nuevo chigüiro** (icono junto al nombre y mascota del menú; también en las páginas de administración). El favicon y los iconos de la app instalada no se tocaron.
- **Tarjeta del viaje** con letra y botones más grandes (sin pasar de un tercio de la pantalla); «Recientes» también pasó al menú ☰.
- **Google Maps (opcional, solo direcciones).** Proveedor nuevo `GoogleDireccionesService` (Places API «New»: autocompletar + detalle), **apagado por defecto**: se activa con `GOOGLE_MAPS_API_KEY` en el `.env`. Se suma a la base propia y a ArcGIS (se consulta solo si faltan opciones), la clave viaja en cabecera desde Java, no se guarda nada de Google y cada resultado dice «Google Maps». Botón **Google Maps** en las herramientas del viaje: abre A → B en Google Maps (enlace público, sin clave). Ver `docs/GOOGLE-MAPS.md` (incluye cuota gratis, costos y lo que falta confirmar de las condiciones de Google). Pruebas: `GoogleDireccionesServiceTest` (6), `LugarServiceGoogleTest` (4) y 2 de navegador. No probado contra Google real.
- **Corrección: la lista de sugerencias ya no queda tapada por los botones «Buscar / En el mapa / Mi ubicación».** Al escribir en A o en B, dos filas de botones se dibujaban encima de las opciones (z-index 6 de las herramientas contra 5 del campo). Ahora, con una lista abierta, los botones quedan detrás. Prueba nueva `la_lista_de_sugerencias_no_queda_tapada_por_los_botones…` (384×848 y 320×568, origen y destino; reproducía el fallo con 3 opciones tapadas).
- **Google siempre visible en las sugerencias y en «Buscar»/Enter.** Antes Google solo se consultaba si la base propia y ArcGIS daban menos de 6 opciones, así que casi nunca aparecía. Ahora, con clave, la lista (máx. 8) reserva cupo para cada fuente: base propia ≤ 3, Google ≤ 3, ArcGIS ≤ 2 (si una trae menos, ocupan el cupo las demás). Con Enter o «Buscar» también se consulta la búsqueda por texto de Google (hasta 5 lugares de Villavicencio). Sin clave nada cambia. Pruebas: `LugarServiceGoogleTest` y `GoogleDireccionesServiceTest`.
- **Corrección: al elegir una sugerencia ya no sale un segundo menú.** Al escoger una sugerencia (p. ej. «Aeropuerto Vanguardia» de Google), el servidor mezclaba otros resultados parecidos (colegios, alcaldía…) y el navegador abría una segunda lista de «coincidencias», sin estilo de menú desplegable, que se encimaba sobre los campos A y B y quedaba abierta. Ahora (1) una sugerencia elegida (Google o ArcGIS) devuelve **solo** ese lugar y el punto se ubica directamente; (2) los resultados de «Buscar»/Enter usan el **mismo menú desplegable** de las sugerencias (una sola lista a la vez, sin tapar nada, que se cierra al pasar al otro campo). Pruebas: `elegir_una_sugerencia_ubica_el_punto_y_no_abre_un_segundo_menu`, `buscar_con_enter_muestra_un_solo_menu…`, `LugarServiceGoogleTest` (2 más). Comprobado además con Google real en la aplicación.
- **Aprender a usar la app (pensado para personas mayores y niños).** Nueva `js/guia.js`: (1) **bienvenida de 3 pantallas** la primera vez («Dime de dónde sales y a dónde vas» → «Te digo qué bus tomar» → «Te acompaño en el viaje»), con «Saltar» y repetible desde el menú ☰ → Ayuda; (2) **ayuda en contexto**: botón «?» en la cabecera que explica lo que se ve en ese momento (inicio, un punto puesto, listos para buscar, resultados, viaje en curso); (3) **viaje de práctica** guiado sobre la app real con un ejemplo (Serramonte → cerca de la Terminal): llena A y B solos, pide tocar «Buscar rutas», muestra las opciones y presenta «Iniciar viaje» SIN iniciar el GPS; botón «Salir de la práctica» siempre a la vista; da el logro «Aprendiz».
- **«Tu viaje de siempre»** (`js/siempre.js`): si repites un viaje a horas parecidas (2 veces a esa hora ±2 h en 45 días, o 4 veces a cualquier hora), al abrir la app sale una tarjeta con un botón grande «Buscar este viaje» y «Ahora no» (oculta ese viaje por hoy). Los hábitos se guardan solo en el dispositivo.
- **Resumen de la semana** (`js/logros.js`): «En los últimos 7 días: N viajes · caminaste X km · unos Y min en total» en el menú ☰ → Mis viajes, y un aviso amable una vez por semana al abrir la app. Cada viaje terminado guarda una línea (fecha, minutos, metros, buses; máx. 100) en el dispositivo.
- **Lenguaje simple en el modo viaje** (`js/viaje-nucleo.js`): «paradero» en vez de «punto de subida», «el bus que dice «Catama» en el letrero», y **cuadras** en vez de metros («te faltan unas 3 cuadras», «falta una cuadra»); mensajes de desvío, GPS débil y «te pasaste» con pasos claros («toca «Recalcular desde aquí»»). Las preguntas «¿Vas en la ruta…?» / «¿Ya te bajaste?» no cambian.
- **Sin voz.** La voz del modo viaje (pregunta «¿Quieres que te hable?», ajuste del menú, botón «Voz» de la tarjeta y aviso hablado) se **retiró por pedido del profesor**: el modo viaje avisa solo con la tarjeta, vibración y pitidos.
- **Corrección:** con una lista de sugerencias abierta y el teclado en un celular pequeño, el panel no podía subir lo suficiente porque la pantalla principal quedó más corta tras mover bloques al menú; ahora reserva espacio debajo mientras hay lista (`.suggesting .sheet-body`). También se corrigió que `hidden` no ganaba a los estilos de botón en las ventanas nuevas. Pruebas: `e2e/aprender_e2e.py` (11).
- **Sin emojis (regla del profesor): iconos propios.** Nuevo `js/iconos.js` con dibujos SVG propios (caminar, bus, casa, maletín, pin, brújula, medalla, llama, estrella, sol, luna, candado, birrete, check, cruz, giro, mano, play, pausa…) que usan el color del texto. Reemplazan a todos los emojis: línea de tiempo, «Casa/Trabajo», logros, bienvenida, controles del bus, estrella de rutas favoritas, mensaje de WhatsApp y avisos del editor (el símbolo ☰ del menú tampoco aparece ya en textos). Prueba: `no_hay_emojis_en_la_pantalla_principal_ni_en_el_menu_ni_en_las_ventanas`.
- **Controles del bus: solo un círculo con el bus** (sin la palabra «Controles»); al tocarlo se abren los controles. Pausa/reanudar con iconos propios.
- **Pestañita «Ahora escribe B»** (`js/siguiente.js`): al poner A (o B) aparece una pestañita pequeña arriba que dice cuál falta («Ahora escribe B · a dónde vas»), SIN mover la pantalla y SIN abrir el teclado (con un solo punto puesto el panel ya no se pliega; con los dos sí, como antes). El campo que falta late suavemente unos segundos; tocarla solo muestra el campo. No sale durante la práctica ni en un viaje.
- **Tutorial rehecho con movimiento:** bienvenida con 3 ilustraciones SVG animadas propias (caen los puntos A y B y se dibuja el camino; la ruta se traza y el bus la recorre con «Ruta Catama»; el chigüiro acompaña y se marcan «Camina / Sube / Bájate»), pantallas que se deslizan (también con el dedo), puntos de progreso y texto que entra con animación. **Práctica guiada:** foco con ondas, mano que toca, el chigüiro como guía, barra de 5 pasos, la tarjeta se pone arriba o abajo para no tapar lo señalado, A y B se «escriben» letra por letra, el resto de la pantalla queda bloqueado (se acabó el fallo del menú), cierra el menú antes de empezar y termina con una celebración (palomita que se dibuja y confeti). Todo respeta «reducir movimiento».
- **Modo noche: la línea punteada de la caminata se ve.** Con el mapa oscuro (`html.mapa-noche`) la línea de caminata (`.walk-line`) pasa a un amarillo claro (`#fff4b8`, opaco y con sombra) y la de respaldo (`.walk-fallback`) a un durazno (`#ffae7a`); de día los colores no cambian. Solo CSS (`css/viaje.css`) y una prueba nueva en `e2e/mejoras414_e2e.py`.
- **Punto de subida/bajada: A y B lejos de las calles ya no obligan al método aproximado.** `RedPeatonalService` busca calle para el origen y el destino de la persona hasta 550 m (antes 250 m; constante `AJUSTE_EXTREMO_METROS`): un punto en un potrero o a la orilla de la vía quedaba sin calle, el cálculo exacto se rendía y el aproximado elegía por línea recta, bajando o subiendo más lejos de lo debido. Esa distancia se cuenta como caminata a campo traviesa. Pruebas nuevas en `RedPeatonalTest`.
- **Rendimiento: un viaje en frío pasa de ≈ 535 ms a ≈ 121 ms de mediana (4,1 veces) sin cambiar ningún resultado** (la respuesta completa de 80 pares A→B es idéntica antes y después). Detalle, mediciones y lo que NO se tocó: `docs/RENDIMIENTO.md`.
  - `RedPeatonal.ajustarVarios` (81,6 % del CPU de un viaje según JFR): dos pasadas con arreglos de números en vez de un objeto, un `HashSet` y un ordenamiento por cada calle del vecindario (mismo orden estable y mismas distancias).
  - Caché de la calle de cada muestra (`ajustarVariosGuardado`, solo en `distanciasDeMuestras`), ligada a la red peatonal inmutable: cuando cambia un paso, un puente o la descarga, se construye otra red y la caché nace vacía.
  - `ViajeRepository.muestras` / `muestrasCompletas`: rejillas de muestras en memoria (pasos de 15 m y 50 m) en lugar de recalcular miles de `ST_LineInterpolatePoint` en PostGIS en cada viaje (≈ 128 ms). Se validan con una huella de ≈ 1,6 ms (versión y fecha de las rutas, paraderos), se vacían en `ViajeService.invalidarCache()` y se recargan a los 10 min. El filtro de radio difiere de `ST_DWithin` menos de 1 mm (`MuestrasPostgisTest`).
  - `RedPeatonal.costosDesde` (Dijkstra) con cola de arreglos en vez de `PriorityQueue` de objetos.
  - `ViajeRepository.tramosEntre`: los tramos de todas las rutas (y los de las variantes «más rápida» y los dos buses de un transbordo) se piden en una sola consulta en vez de una por tramo (≈ 20 por viaje); `MuestrasPostgisTest` los compara con `tramoEntre` uno por uno.
  - `index.html`: se quitó el `preconnect` a ArcGIS (solo es respaldo del mapa; en una carga normal nunca se usa).
  - Pruebas nuevas: `RedPeatonalEquivalenciaTest` (contra copias literales de los algoritmos anteriores), `MuestrasPostgisTest` (contra PostGIS real; se omite sin Docker). Herramientas de medición en `e2e/rendimiento/`.

- **Tutorial rediseñado (bienvenida, práctica y ayuda), solo frontend.** Archivos: `js/guia.js` (reescrito), `css/guia.css` (nuevo, se precachea en `sw.js`), `js/iconos.js` (+ paradero, transbordo, mapa, reloj, bajar), `index.html`, `js/cuenta.js` (Escape y clic fuera de «bienvenida»/«ayuda» ahora los maneja `guia.js`: devuelve el foco y marca la bienvenida como vista), `js/siempre.js` y `js/espacio.js` (la búsqueda de práctica no entra al historial ni a «Tu viaje de siempre»), `css/viaje.css` (se quitaron las reglas de las 3 escenas viejas).
  - **Bienvenida de 5 escenas** SVG propias (qué es SEVB, buscar, elegir bus con transbordo, viaje en 6 hitos, practicar o explorar). Última escena con dos caminos: «Aprender con una práctica» y «Empezar a explorar». Teclado (flechas, Escape, Tab atrapado), deslizar, horizontal; la capibara solo sale en la 1 y la 5; solo se anima la escena visible.
  - **Práctica de 7 pasos** con campos y botones reales, «Atrás», confirmación visual, pasos 4 y 5 explicados de a una cosa, y el modo viaje solo demostrado (sin GPS, el botón real queda protegido). Guarda y devuelve tus A, B y resultados. Si el servicio falla, ofrece un ejemplo ilustrado marcado como simulación.
  - **Coach por eventos** (antes medía en cada cuadro): tarjeta que nunca tapa lo señalado, teclado virtual, giro y zonas seguras; Tab solo recorre lo señalado y la tarjeta.
  - **Ayuda «?» de 9 estados** (incluye buscando, sin rutas, ruta elegida y error/sin conexión), resalta la acción recomendada, ofrece una demostración y 7 «ayudas rápidas» que reutilizan las escenas. Cierre de la práctica discreto (no oscurece la pantalla).
  - Pruebas: `e2e/aprender_e2e.py` pasó a 30.

## 4.13.0
**Solo el trayecto que se recorre, paraderos y puentes a la vista, y botones de inicio.** Pedido tras mostrar la app a otras personas, que se confundían con la línea larga «sin marcar».
- **Solo el trayecto (`app.js`, `planner.js`):** al buscar de A a B (una ruta, un transbordo) ya no se dibuja la ruta entera ni sus rótulos «Inicio»/«Fin»; solo el tramo rojo (y azul) que se recorre, con «Sube aquí · M01» y «Bájate aquí», y la caminata punteada. El modo viaje ya dibujaba solo los tramos. En cambio el **explorador de rutas** (elegir una ruta de la lista) sigue mostrándola entera, y «Rutas cerca del origen» también (todavía no hay tramo). Opción nueva `completa` de `showRoute`.
- **Paraderos a la vista (`planner.js`):** se dibujan, con letrero azul y nombre, los paraderos de la ruta que caen sobre el tramo recorrido, y los registrados a ≤ 150 m de A y de B; si cerca de A hay uno y el punto de subida no lo es, el estado lo dice («Hay un paradero a 44 m de tu punto A: …»). Lejos de eso no se muestra nada. La regla del servidor no cambió (se prefiere un paradero real si no obliga a caminar más de 25 m extra).
- **Puentes peatonales:** (1) la red de calles ahora trata los puentes registrados como clase propia con costo 0,75 del largo, así que la caminata los prefiere si el rodeo es de hasta ≈ 33 % (antes eran un paso más, sin preferencia); la heurística de A* se ajustó para seguir siendo exacta. (2) Los puentes a ≤ 120 m de la caminata se ven con su nombre aunque no se crucen; los que sí se cruzan siguen destacados con el aviso «Cruza el puente peatonal …».
- **«Limpiar A y B»:** pasa de un texto gris arriba a la derecha a un botón con borde, de 46 px, junto a «Buscar rutas».
- **Casita en el modo viaje:** botón de casita en la tarjeta del viaje: en pleno viaje pide un segundo toque; termina el viaje, limpia A y B (sin abrir el teclado), devuelve el mapa a la vista de la ciudad y el panel a media altura.
- **Guía de colores:** pasa al final del panel (plegada) en vez de estar entre los botones de A→B.
- **Flujo y movimiento (`viaje.css`):** capa de movimiento inspirada en la fluidez de las apps de tránsito (solo ideas de movimiento; la identidad es de SEVB: verde, capibara, A verde / B naranja, color de cada ruta). La hoja del panel en celulares se asienta con una frenada larga; los botones se hunden al tocarlos y vuelven con rebote; las letras A/B hacen «pop» al marcar el punto; un punto viaja de A a B por el conector (2 pasadas); el botón de intercambiar da media vuelta; «Iniciar viaje» entra con rebote y late dos veces; las tarjetas de resultados suben una tras otra; los pines caen con rebote; las insignias de ruta tienen relieve. Solo `transform`/`opacity`, sin `will-change` fijo; con movimiento reducido las duraciones pasan a 1 ms y los bucles se apagan (`--motion-spring`, `--motion-sheet`, `--ease-spring`, `--ease-sheet`).
- **Pruebas:** Java `losPuentesRegistradosSePrefierenSiElRodeoEsPequeno`; navegador: `al_buscar_de_A_a_B_solo_se_dibuja_el_trayecto…`, `limpiar_A_y_B_es_un_boton_visible…`, `se_ven_los_paraderos_del_tramo…`, `la_casita_del_viaje…`, `el_flujo_de_movimiento_anima_y_se_apaga_con_movimiento_reducido`. El stub devuelve un paradero de prueba por zona. La prueba existente de «Limpiar A y B» con la lista de sugerencias abierta activa el botón por código (en pantalla ancha la lista lo cubre, como en cualquier autocompletado).

## 4.12.1
**Celulares: teclado, campos y menús.** La lógica de rutas, el servidor y el modo viaje no se tocaron; cambian la interfaz móvil y las pruebas. Se probó con simulación del teclado en 8 modelos (iPhone SE, 13, 15 Pro Max, Pixel 5, Galaxy S20 y Fold plegado, etc.); **no se probó en un iPhone real**.
- **Teclado (`sheet.js`, `viaje.css`):** el iPhone no encoge la página al abrir el teclado, solo el «visual viewport»; el panel ahora se apoya sobre el borde visible (variable `--kb`) y el campo enfocado sube al inicio del panel con su primera sugerencia a la vista. En Android la página encoge sola (`interactive-widget=resizes-content`). Ventanas y menú lateral también quedan sobre el teclado. Con poco espacio libre se oculta el encabezado del panel.
- **Campos:** las herramientas (Buscar, En el mapa, Mi ubicación) pasan a una fila de 44 px debajo del campo, con texto; antes flotaban encima y dejaban ≈ 110 px para escribir. La X de borrar queda dentro del campo.
- **Sin zoom en iOS:** todo campo de texto usa 16 px (la regla de 15 px en pantallas ≤ 380 px hacía que iOS ampliara la página).
- **Listas de sugerencias:** solo una abierta a la vez (al enfocar el otro campo se cierra la anterior; al salir del campo se cierra la suya); tocar una opción sigue funcionando. Antes podían quedar las dos abiertas y taparse.
- **Objetivos táctiles** ≥ 44 px en más controles (zoom de Leaflet, asa del panel, botones del bus, etc.).
- **Animación de la caminata restaurada (`motion.css`):** las líneas punteadas de A → parada y parada → B vuelven a moverse despacio en el sentido del trayecto (`@keyframes walk-flow`, solo `stroke-dashoffset`, 1,5 s por ciclo). El ciclo mide exactamente el periodo del patrón (11 px = `dashArray '1 10'`), así que empalma sin saltos; se pausa mientras se arrastra el mapa, no se anima la línea recta de respaldo y con movimiento reducido queda quieta. La lógica de `planner.js` no cambió. La prueba de Java que afirmaba lo contrario (`walkingLinesNoLongerAnimateContinuously`) se reescribió, y hay una prueba de navegador nueva (`las_lineas_punteadas_de_caminata_avanzan_en_el_sentido_del_trayecto`: el desplazamiento cambia, va de 0 a −11, se pausa con el mapa en movimiento y queda quieto con movimiento reducido).
- **Pruebas:** suite nueva `e2e/movil_e2e.py` (9 pruebas: teclado estilo iPhone y Android, visual viewport desplazado, campos cómodos, 16 px, ventanas y menú, una sola lista de sugerencias, sin choques ni desborde en 8 modelos, apaisado), ya incluida en `run_e2e.py`. La prueba de ruido de `joystick_e2e.py` era aleatoria (tomaba una lectura ruidosa como referencia) y ahora mide contra el promedio.
- **Documentación:** `docs/ARQUITECTURA.md` (monolito con REST/JSON síncrono; sin colas, Pub/Sub, Kafka ni WebSocket; el único «pub/sub» es el bus de eventos del navegador).

## 4.12.0
**Búsqueda directa a «Iniciar viaje», servidor más rápido y menos gasto de batería.** Las rutas, los transbordos, la caminata por calles, ArcGIS, el editor y el modo viaje conservan su lógica. Este paquete incluye lo medido y probado; lo pendiente de esta línea de optimización (precisión adicional del GPS, joystick más rápido, calentamiento del servidor al arrancar) queda para la siguiente versión.
- **Al buscar se va directo a empezar:** el panel ya no baja solo ni deja una lista que hay que abrir. Queda compacto con el botón grande «Iniciar viaje», el resumen de la ruta elegida («Ruta M01 · 13 min · caminas 80 m») y «Ver otras opciones · N» (abre la lista a media altura). El ajuste «bajar el panel al buscar» pasa a la clave nueva `panelBajar`, **apagada por defecto**, porque la clave anterior (`panelAuto`) quedó guardada como «activada» en los celulares que ya usaron la app. Si no hay rutas, el panel sí se abre para mostrar el mensaje.
- **Servidor (`RedPeatonal`):** al medir se encontró que cada viaje nuevo perdía ≈ un tercio de su tiempo en colisiones del `HashMap` de la malla de calles (la clave se reducía a `x ^ y`: una sola cubeta llegaba a 84 celdas); ahora la clave usa una mezcla biyectiva (fmix64) y la distancia usa `sqrt` en vez de `Math.hypot`. Un viaje nuevo pasa de **383 a 248 ms de mediana** (mismo compilador, mismos 16 viajes), con el mismo resultado (prueba de equivalencia contra fuerza bruta: misma calle y misma distancia).
- **Bus de demostración (`bus.js`):** se pausa durante el viaje (sin marcador ni controles; resolvía además un choque real: en tableta la tarjeta del viaje tapaba los controles del bus) y vuelve al terminar; 30 fps en pantallas táctiles; la línea de avance solo se repinta ≤ 25 veces por segundo; la demostración se pausa tras 3 vueltas. Con el viaje activo el hilo principal pasó de 76 % a 15 % ocupado (CPU 4× más lenta).
- **Objetivos táctiles:** los controles que medían menos de 44 px (ocultar panel, logo, «Limpiar», favoritos, recientes, desplegables, controles del bus, asa del panel) crecen o amplían su área de toque.
- **Pruebas:** 3 de Java (`RedPeatonalEquivalenciaTest`: equivalencia con fuerza bruta y reparto del hash; la del hash falla con la clave anterior) y 2 de navegador nuevas en `viaje_e2e.py` (flujo directo y bus durante el viaje). Tres pruebas de `resultados_e2e.py` se adaptaron al panel compacto (abren la lista con «Ver otras opciones»; se conservan todas sus validaciones).

## 4.11.3
**«Iniciar viaje» como botón principal y joystick mucho más completo.** El planificador, las rutas, la caminata por calles, ArcGIS y el editor no se tocaron.
- **App (`index.html`, `viaje.js`, `viaje.css`):** «Iniciar viaje» deja de ser un chip escondido en la barra de herramientas: es un botón grande de ancho completo **justo debajo de «Buscar rutas»**, que aparece en cuanto hay una opción elegida, se queda fijo arriba del panel mientras se recorren los resultados (`position: sticky`), muestra la ruta («Ruta R002 · te guío con el GPS…») y durante el viaje queda como «Viaje en curso». La tarjeta del viaje ya se podía mover, minimizar y ocultar (4.11.1).
- **Joystick (`scripts/joystick/`, `joystick_gps.py`):** velocidad máxima **ajustable a mano** (control deslizante 1–60 km/h y teclas `+` / `-`, además de los preajustes) y **automática** (crucero y **piloto automático** que sigue la ruta guía a la velocidad fijada, cambiable en vivo, y frena al final; el joystick recupera el control al tocarlo); **teletransporte con 3 clics** seguidos en el mismo punto (un clic suelto ya no salta por error, con marca «1/3», «2/3»); y **verificación independiente** de que el Android del emulador recibió la posición (`/api/telefono`, `dumpsys location`).
- **Comprobación real:** con el emulador encendido, el Chrome del teléfono leyó por `navigator.geolocation` las coordenadas enviadas por el joystick con < 1 m de diferencia, en un contexto seguro (HTTPS). `docs/MODO-VIAJE.md` incluye las preguntas frecuentes sobre qué es igual y qué cambia con un teléfono real y la app alojada.
- **Pruebas:** `joystick_e2e.py` pasa de 13 a 17 (3 clics y casos que no deben teletransportar, deslizador y teclas de velocidad, piloto automático, comprobación del teléfono; la de un solo clic se sustituyó por la de tres clics, que es el cambio pedido) y `viaje_e2e.py` de 22 a 23 (el botón principal en tres tamaños de pantalla).

## 4.11.2
**Joystick de GPS para demostrar el Modo viaje con el emulador de Android, y corrección de la velocidad 0 que informa Android.** El planificador, las rutas, la caminata por calles, ArcGIS y el editor no se tocaron.
- **`scripts/joystick_gps.py` + `scripts/joystick/`:** un servidor local (solo `127.0.0.1`, solo biblioteca estándar) y una página con joystick de pantalla (ratón o dedo), teclado (`W A S D`, flechas, `1 2 3`, `C`, `Espacio`) y control de juegos (Gamepad API), mapa con la posición y el recorrido guía (`--ruta`), clic para teletransportarse y botones de velocidad (a pie 1,4 m/s, bus lento 4,5, bus 8). Envía una posición por segundo con `adb emu geo fix`. El movimiento acelera y frena de forma gradual (≈ 1 m/s² y 1,3 m/s²), con modo crucero, y permite probar ruido del GPS, un salto de 600 m y 20 s sin señal. `--prueba` simula el emulador.
- **Corrección en `viaje-nucleo.js`:** el emulador (y algunos teléfonos) informa `coords.speed = 0` aunque la posición se mueva (verificado con `dumpsys location`: `vel=0.0`); el filtro lo mezclaba con la velocidad calculada y restaba un 40 % (un bus lento a 4,5 m/s parecía ir a 2,7 m/s y nunca se detectaba la subida). Ahora solo usa la velocidad informada si es mayor que 0,3 m/s.
- **Pruebas nuevas:** `joystick_e2e.py` (13: servidor y validación, modelo de movimiento, teclado, joystick de pantalla con ratón y dedo, control de juegos, crucero, ruido/salto/sin señal, teletransporte, caída del servidor y dos **viajes completos conducidos con el joystick**, directo y con transbordo, que pasan por todas las fases) y 1 en `viaje_logica_e2e.py` (velocidad informada 0; falla con el comportamiento anterior). Probado además contra el emulador real.
- **Documentación:** `docs/MODO-VIAJE.md` explica la demostración con joystick paso a paso.

## 4.11.1
**La tarjeta del modo viaje ya no queda fija en medio del mapa.** Solo cambia la interfaz del modo viaje: el planificador, las rutas, la caminata por calles, ArcGIS, el editor y el resto de funciones no se tocaron.
- **Se puede mover:** franja de agarre en el borde superior de la tarjeta, con el dedo, el ratón o el teclado (flechas; doble toque o Inicio la devuelven a su sitio). No sale de la pantalla (se reajusta al girar el teléfono) y recuerda su posición (`localStorage`, clave `spevb-viaje-ui`).
- **Se puede minimizar y ocultar:** minimizada es una barra con el título y lo que falta; oculta deja una pastilla «Viaje · distancia» que la devuelve. El viaje sigue. Las preguntas y los avisos importantes la reabren solos.
- **Ocupa mucho menos:** la distancia y el tiempo pasan al encabezado; Voz, «Recalcular desde aquí» y las lecturas técnicas van en «Más». ≈ 30 % de la pantalla en 384×848 y < 40 % en 320×568 (antes cerca de la mitad).
- **Pruebas:** 5 nuevas en `viaje_e2e.py` (arrastre con ratón y con el dedo sin mover el mapa, posición recordada, doble clic y teclado, minimizar / ocultar / pastilla / reapertura por pregunta y por alerta, tamaño por defecto). Las 17 anteriores se conservan; las dos que usaban «Voz» y «Recalcular» abren primero «Más».

## 4.11.0
**Modo viaje: seguimiento en tiempo real con detección de subida y bajada.** Es una función nueva y aditiva: el planificador, las rutas, los transbordos, ArcGIS, el editor y el «Avisarme al bajar» siguen igual (este último se desactiva solo mientras el modo viaje está activo, porque lo cubre).
- **Fases:** caminar al punto de subida → esperar → detectar que va en el bus y **preguntar** → avisos de bajada a ≈ 500, 200 y 70 m (pantalla, vibración, sonido y voz opcional) → detectar que bajó y **preguntar** → caminar al transbordo o al destino → llegada. Nunca asume una subida o bajada.
- **Núcleo sin DOM (`js/viaje-nucleo.js`):** filtro del GPS (descarta precisión > 100 m, lecturas repetidas o desordenadas y saltos imposibles; un salto solo se acepta si 3 lecturas coinciden; suavizado tipo Kalman con ruido de proceso según la velocidad; velocidad medida en una ventana de 8 s) y máquina de fases. Todo el tiempo sale de la marca de tiempo de cada lectura, así que es determinista y se prueba sin salir a la calle.
- **Interfaz (`js/viaje.js`, `css/viaje.css`):** botón «Iniciar viaje» en la barra del resultado, tarjeta con instrucción, distancia, tiempo, velocidad y precisión, barra de avance, preguntas con botones de ≥ 44 px, punto de la persona en el mapa con «Seguirme» (se suelta al arrastrar el mapa), voz opcional, pantalla encendida (Wake Lock), aviso de pérdida de señal, de lejanía al punto A, de desvío y de «te pasaste de la parada», «Recalcular desde aquí», «Terminar viaje» con confirmación y **reanudación** tras recargar (guarda solo el recorrido y la fase, nunca la posición; 3 h). Accesible (región, alertdialog, aria-live, teclado) y sin desborde de 320×568 a escritorio.
- **Cambios de apoyo (compatibles):** `planner.js` agrega `viaje` (buses con subida, bajada y tramo) a `Planner.seleccion`; `gps.js` acepta `seguir({ crudas, segundoPlano })`; `sw.js` guarda los archivos nuevos en el shell.
- **Herramientas de prueba y demostración:** `scripts/gpx_viaje.py` genera un GPX del viaje (a 1 lectura por segundo, con velocidades reales y ruido opcional; con o sin transbordo) y `scripts/emulador_gps.py` lo envía al emulador de Android con `adb emu geo fix`; `docs/MODO-VIAJE.md` explica reglas, límites reales (pantalla bloqueada, iOS, precisión, HTTPS, batería) y la demostración con Android Studio (`adb reverse`, importar el GPX en *Extended controls › Location › Routes*).
- **Pruebas nuevas (navegador real, sin Docker):** `viaje_logica_e2e.py` (20: fases, falsos positivos, GPS ruidoso, saltos, huecos, semáforos, transbordo, filtro), `viaje_e2e.py` (17: interfaz, vibración, voz, pantalla, sin señal, permiso, recalcular, terminar, reanudar, accesibilidad, tamaños) y `gpx_e2e.py` (4: el GPX generado recorre todas las fases).

## 4.10.3
**Versión solo correctiva de 4.10.2: estados obsoletos, respuestas tardías, historial, URL, service worker, Blender y documentación.** No se agregó ninguna función ni se eliminó ninguna. El algoritmo de rutas, transbordos y caminata, ArcGIS, catastro, Nominatim, GPS y editor no se modificaron. Sin Docker.
- **Selección obsoleta al cambiar de ruta (`planner.js`):** al empezar a cargar cualquier recorrido el explorador emite `spevb-route-change` (ahora con `source`); el planificador responde con `setSeleccion(null)`, que emite `spevb-seleccion`, oculta `#tripTools` y detiene «Avisarme al bajar». Si la carga falla, la selección vieja no reaparece. Al cambiar de tarjeta dentro del planificador el evento es *transitorio*: el aviso se reanuda con la nueva opción (`extras.js`), como siempre.
- **Borrar A/B dejaba el bus y la ruta completa:** nueva operación `SpevbExplorer.clearRoute()` (aborta la petición, invalida la secuencia, `bus.stop()`, quita marcador/estela/controles, capas, paraderos, ficha, deshabilita «Encuadrar», limpia `?ruta=`; no mueve el mapa ni toca pines, GPS, favoritos, historial o viajes guardados). `clearPlan()` y `clearNearby()` la usan solo cuando el recorrido lo cargó el planificador (`source: 'planner'`): una ruta que la persona abrió por su cuenta no se toca.
- **Respuestas tardías:** `clearPlan()`/nuevo cálculo/`clearNearby()` abortan y vuelven obsoleta también la carga del explorador; `showOption`, `showTransfer` y `showNearby` comprueban vigencia antes y después de la petición. Un cálculo nuevo (incluso con los mismos A/B) limpia de inmediato selección, barra, aviso y recorrido; si falla o no hay rutas nada anterior reaparece.
- **Historial incorrecto:** `spevb-viaje` se emite una sola vez por cálculo nuevo, después de que `initialRecommendationCard.showOnMap()` terminó, comprobando vigencia y con los códigos reales de `Planner.seleccion` (antes usaba el primer transbordo aunque se mostrara una directa). Sin recomendación válida no se guarda nada.
- **`?ruta=` en la URL:** `showRoute(id, { fit, updateHistory, source })`; el planificador usa `updateHistory: false`. Abrir una ruta desde la lista o por enlace directo sigue escribiendo `?ruta=id`; calcular o limpiar un viaje retira un `ruta` obsoleto conservando los demás parámetros.
- **Service worker:** para `/api/rutas` y `/api/rutas/{id}`, una respuesta 500, 502, 503 o 504 usa la copia guardada (`X-Spevb-Cache: guardado`) si existe; 400, 401, 403 y 404 nunca se sustituyen; sin copia se devuelve el error original; no se guardan respuestas fallidas.
- **Blender:** `design/bus/scripts/make_sheets.py` y `sprites.py` ya no contienen rutas personales; calculan la raíz con `pathlib` (`BUS_ROOT`, `SEVB_BUS_ROOT`, `__file__`, carpeta del `.blend`, carpeta de trabajo). Geometría y sprites sin cambios. README corregido (`backup/static/` no viaja en el paquete).
- **Documentación:** comando PowerShell `mvn clean verify "-Dsevb.sinDocker=true"`, texto de caminata por calles en el README, encabezado antiguo «SEVB V3.4» convertido en sección, identificación única del geocodificador `SEVB-Rutas/4.10`, conteos de pruebas actualizados.
- **Pruebas nuevas:** `e2e/estados_e2e.py` (16, de las que 13 fallan con el código de 4.10.2), 3 de service worker en `sw_e2e.py` (más el gancho `/__fallo/<código>` del stub) y `EntregaPortableTest` (2, Java).

## 4.10.2
**Mejores opciones arriba, lista de resultados que ya no vuelve al inicio en el celular y aviso de «elegir punto» junto a los controles del mapa.** ArcGIS, catastro, Nominatim, GPS, algoritmo de rutas y transbordos, editor y `sw.js` **no se modificaron**. Sin Docker.
- **Mejores opciones (`planner.js`):** sección «Mejores opciones» con hasta 2 tarjetas cerradas y compactas: `opciones[0]` («Mejor en el sentido de la ruta») y `alternativas[0]` («Mejor en sentido contrario»), distinguidas por texto, ícono de dirección y color. Se respeta el orden del servidor. El resto va en grupos cerrados «Otras opciones en este sentido · N» y «Otras opciones en sentido contrario · N» (sin grupos vacíos; cada resultado aparece una sola vez). Si solo hay un sentido se muestra solo ese; si no hay directas, la mejor con transbordo es la recomendación. `initialRecommendationCard` (normal → contraria → transbordo) se dibuja en el mapa **sin abrir** la tarjeta, después de estabilizar el DOM.
- **Causa del salto al inicio:** el manejador de `spevb-results` (`sheet.js`) programaba a los 350 ms un `scrollTo` suave hacia el inicio de los resultados, que llegaba cuando la persona ya había empezado a desplazar; además `visualViewport.resize` llamaba a `scrollIntoView` suave, se abría automáticamente la primera tarjeta (el acordeón cerraba otras y movía alturas) y la parte inferior del cuerpo del panel quedaba fuera de pantalla.
- **Corrección:** estados explícitos (`pendiente`/`usuario`); pointerdown, touchstart, wheel, teclas de desplazamiento y scroll manual cancelan el posicionamiento automático; el posicionamiento es único e instantáneo y solo si la persona no interactuó; `apply()` y resize conservan `scrollTop` (limitado al máximo); `scrollIntoView` solo con un campo enfocado hace poco; el relleno inferior `--sheet-hidden` permite llegar al final de la lista; `.sheet-body` con `touch-action: pan-y` y `overscroll-behavior-y: contain`; el ocultamiento automático del panel se cancela al interactuar.
- **Aviso `pick`:** solo ese aviso se coloca en un contenedor aparte abajo a la izquierda del mapa (encima de «Controles» y del panel; sin tapar botones de la derecha, atribución ni pin central). `Spevb.toast()` acepta `ubicacion`, y al reutilizar una clave actualiza mensaje, tipo, acciones, clases y ubicación. Fondo verde oscuro, texto blanco 15 px, «Confirmar» primario y «Cancelar» secundario (≥ 44×44 px), Escape cancela. Los demás avisos no cambian.
- **Pruebas:** 13 nuevas en `e2e/resultados_e2e.py` (ver docs/PRUEBAS.md).

## 4.10.1
**Controles para borrar el origen (A) y el destino (B) del planificador.** ArcGIS, catastro, Nominatim, GPS, rutas, transbordos, mapa, editor, animaciones y service worker **no se modificaron**. Sin Docker.
- **Interfaz:** una X discreta (SVG, sin emojis) dentro de cada campo (`clearOrigin`, `clearDestination`) y un botón sutil «Limpiar A y B» (`clearTripPoints`) sobre los campos. Solo se activan si hay texto, un punto seleccionado o algo pendiente por cancelar; con el campo vacío no ocupan sitio. En pantallas táctiles miden 44×44 px; en teléfonos muy estrechos el texto conserva su ancho (≤ 420 px las demás herramientas ceden ancho y a ≤ 360 px bajan a una fila). Estados hover, `:focus-visible`, deshabilitado y movimiento reducido.
- **Lógica centralizada en `planner.js`:** `clearEndpoint(mode)`, `clearBothEndpoints()`, `invalidateEndpoint(mode)` y `syncClearButtons()` (se ejecuta desde `drawPins()`, así cubre escribir, elegir sugerencias o mapa, GPS, intercambiar, viaje compartido, viaje guardado y borrado manual). Borrar A limpia además las rutas cercanas; borrar B las conserva; «Limpiar A y B» cierra sugerencias, cancela la elección en el mapa (y oculta el pin central) y vacía resultados. No toca favoritos, historial, viajes guardados, rutas registradas ni el GPS.
- **Condición de carrera corregida:** `reversePoint()` ahora toma una generación por extremo (`lookups[mode]`) y registra su `AbortController` (`reverseRequests`); borrar, escribir, intercambiar o elegir otro punto la aborta y su respuesta tardía se ignora (antes podía volver a colocar un punto eliminado). El intento de «Usar mi ubicación» se invalida con `gpsUse.gen`: una lectura GPS antigua ya no vuelve a fijar A (el punto azul del GPS sigue en el mapa).
- **Borrar todo el texto con el teclado** equivale a pulsar la X (sin coordenada oculta ni marcador huérfano) y ya no ejecuta `clearPlan()` dos veces.
- **Pruebas:** 11 nuevas de navegador en `e2e/puntos_e2e.py` (borrar A, B y ambos; búsqueda, sugerencias, geocodificación inversa y GPS pendientes; intercambiar con solo A / solo B / ninguno; borrado con teclado; accesibilidad; ancho móvil y escritorio sin desborde; ArcGIS/sugerencias; favoritos y viajes guardados intactos). El stub ahora simula sugerencias, geocodificación inversa, búsqueda y rutas cercanas.

## 4.10.0
**Editor de recorridos y puentes** (ver `docs/EDITOR.md`). ArcGIS, búsqueda de direcciones, catastro, Nominatim, GPS, planificador, caminata, transbordos, animaciones y service worker **no se modificaron**. Sin Docker.
- **Vértices:** selección del vértice/segmento más cercano con un clic (O(n) solo al hacer clic), «Vértice 52 de 1.500», navegación (anterior/siguiente/±10/primero/último), doble clic para insertar, atajos que no actúan al escribir en formularios; con más de 500 puntos solo se dibujan extremos, vértice elegido, vecinos y hasta 300 puntos simplificados por zoom. Ratón, táctil y lápiz.
- **Red vial para buses** (`co.edu.usta.spevb.vial`, separada de la peatonal): reglas OSM (`ReglasBus`), grafo dirigido con sentidos únicos y glorietas, map matching HMM por tramos con anclas, proveedor OSRM opcional (`BUS_MATCHING_URL`) con timeout y circuit breaker. Variables nuevas: `RED_VIAL_HABILITADA`, `RED_VIAL_ARCHIVO`, `RED_VIAL_REFRESCO_DIAS`, `RED_VIAL_MAX_SNAP_METROS`, `BUS_MATCHING_URL`.
- **Ajuste a vías:** vértice individual (hasta 3 candidatos, límite 30 m configurable, previsualización, Deshacer) y ruta completa con comparación, resumen, aceptar/rechazar/por tramo, anclas 🔒 y revisión obligatoria si la longitud cambia > 15 %. Nada se aplica ni se guarda solo; «validada» se desmarca.
- **Auditoría de calidad** (`GET /api/editor/calidad`, solo lectura): estado correcta / requiere revisión / crítica por ruta y salto al primer problema.
- **Metadatos:** el código de una ruta existente es editable (confirmación, único, versión archivada, 409) con **alias** del código anterior (migración V9, `GET /api/rutas/codigo/{codigo}`); los favoritos antiguos se actualizan al vigente; también `activa`. El id nunca cambia.
- **Puentes:** botón «Editar» (mover/insertar/eliminar vértices, ajustar extremos a la red peatonal, deshacer/rehacer, cancelar, comparar con el original) y `PUT /api/editor/pasos/{id}` con versión (migración V10); `/api/puentes` sigue sin exponer la nota.
- **Paraderos:** tras cambiar la geometría se informa «distancia anterior → nueva» y se ofrece ajustar cada paradero; nunca se mueven ni se borran solos.
- **Pruebas:** 334 Java (4 omitidas sin Docker); navegador 20 de interfaz + 16 del editor + 7 del service worker contra el servidor stub (que ahora simula edición, red vial y auditoría).

## 4.9.7
Auditoría de 4.9.6 corregida **sin Docker** (no se ejecutó Docker, Compose ni Testcontainers). Sin funciones nuevas ni eliminadas.
- **Dependencias vulnerables:** Tomcat 11.0.24 → **11.0.25** (`tomcat.version`) y Jackson 3.1.5 → **3.1.7** (`jackson-bom.version`, la propiedad que realmente usa el BOM de Spring Boot 4.1.1; `jackson.version` se conserva por claridad). Spring Boot sigue en 4.1.1 y Java en 21. Prueba `DependenciasSegurasTest` (las versiones no pueden retroceder). SBOM CycloneDX (`target/bom.json`) y escaneo con la API oficial de OSV (`scripts/osv_scan.py`): 0 vulnerabilidades conocidas en los 59 componentes; con las versiones anteriores, OSV reportaba hallazgos para Tomcat 11.0.24 y Jackson 3.1.5.
- **E2E sin Docker:** `e2e/run_e2e.py` usa por defecto `e2e/stub_server.py` (frontend real + API simulada, datos fijos, sin Internet ni base de datos). El modo PostGIS local es opcional y exige `SEVB_E2E_DB_URL`, `SEVB_E2E_DB_USER` y `SEVB_E2E_DB_PASSWORD` con una base cuyo nombre termine en `_e2e` o `_test` en este equipo; nunca usa la base principal ni crea/destruye bases. Si el proceso Java muere al arrancar, falla de inmediato y muestra las últimas líneas de `target/e2e-app.log`. Los procesos hijos ya no heredan la entrada estándar (la causa de las ejecuciones colgadas).
- **Prueba de puentes determinista:** el stub publica dos puentes fijos (uno de 5 puntos sobre la caminata de prueba y otro lejano). La prueba comprueba que `/api/puentes` solo trae `id`, `nombre`, `metros` y `trazo`, que no se dibujan puentes al abrir, que los irrelevantes no aparecen y que el correcto aparece con su nombre. Ya no depende de que el servidor devuelva 3+ puntos.
- **Respaldos:** `backup.ps1`/`restore.ps1` limpian temporales con `try/finally` y `deploy/backup.sh` con `trap` (sin archivos parciales; si falla el cifrado se conserva la copia sin cifrar). Formato de respaldo sin cambios.
- **Paquete:** `design/*/backup/` ya no entra al ZIP (sigue en el proyecto de trabajo).
- **Documentación:** README, CONTINUIDAD y LEEME_PRIMERO actualizados (se corrigió información de V3.3, V3.4 y 4.9.0).

## 4.9.6
### Fase de corrección y estabilización (sin funciones nuevas)
- **«Seguir bus» y arrastre del mapa:** `seguir-bus.js` ya no desactiva `map.dragging` ni `boxZoom`; el mapa sigue siendo arrastrable y el primer arrastre manual (`dragstart`, que escucha `bus.js`) cancela el seguimiento, desmarca la casilla y detiene el recentrado. Los recentrados automáticos (`panTo`/`setView`) no emiten `dragstart`. El chip «Soltar» y el umbral de 2 px no cambian. Prueba de navegador: `e2e/sevb_e2e.py` (con prueba de mutación: si se reintroduce `dragging.disable()` la prueba falla).
- **`GET /api/puentes` público sin notas internas:** nueva proyección `PuentePublicoResponse` (`id`, `nombre`, `metros`, `trazo`). El editor lee las notas de `/api/editor/pasos`. Prueba `PuenteControllerTest`.
- **Service worker:** las navegaciones se guardan con clave canónica (`/`), sin parámetros de origen/destino/coordenadas; la URL visible conserva sus parámetros; solo se guardan respuestas 200 propias, sin redirección y de tipo permitido; se podan las cachés dinámicas (app 120, datos 200, teselas 1.500); al activar se eliminan las cachés antiguas y las claves con parámetros que dejó una versión previa. `/api/*` sin cambios.
- **Versión única 4.9.6:** `pom.xml` → `/api/estado` (antes 4.6.0), service worker, manifest, `build-info`, JAR y ZIP. Prueba `VersionConsistenciaTest`.
- **Configuración de arranque unificada:** `docs/CONFIGURACION.md`; `start.ps1` toma las variables de `application.properties` y no pisa las de la sesión; `compose.app.yml` y `compose.prod.yml` transmiten todas. Prueba `ConfiguracionArranqueTest`. Valores seguros por defecto: `EDITOR_ENABLED=false`, `SERVER_ADDRESS=127.0.0.1`.
- **Flyway:** `db/migration/V1…V8` reemplazan a `schema.sql` (idempotentes: una base existente se adopta sin perder datos). Los datos demostrativos pasan a `db/demo` y solo se cargan con `DEMO_DATA=true`. Pruebas `MigracionesPostgisTest` (PostGIS real). Ver `docs/MIGRACIONES.md`.
- **Mockito/Byte Buddy:** el agente se carga desde Maven (`maven-dependency-plugin` + `argLine`), sin rutas absolutas y sin carga dinámica.
- **Respaldos y datos personales:** `backup.ps1` y `deploy/backup.sh` crean los archivos con acceso restringido y admiten cifrado; `scripts/verify-restore.ps1` comprueba la restauración en un contenedor temporal. `docs/DATOS-PERSONALES.md`.
- **Docker:** healthcheck de la aplicación, imágenes con versión específica, usuario no privilegiado, PostgreSQL sin puertos en producción, sin datos DEMO en producción.
- **Empaquetado:** `scripts/package-release.ps1` genera `SEVB-4.9.6.zip` sin `.env`, `target/`, `.idea/`, `data/`, respaldos, logs, ZIP anidados ni configuración local, y falla si los encuentra.
- **Contratos:** `docs/INVENTARIO-CONTRATOS.md` (generado por `scripts/inventario_contratos.py` contra la entrega anterior: 51 endpoints → 51, DTO 18 → 19 (nuevo `PuentePublicoResponse`), API JS 35 → 35, ninguna eliminación) y `ContratosPublicosTest` (ningún endpoint desaparece, la administración exige clave, el frontend no lleva secretos). Compatibilidad con datos guardados antiguos: prueba de navegador `datos_guardados_de_versiones_anteriores_siguen_funcionando`.
- **Documentación:** `design/bus/README.md` ya no dice que el modelo no está integrado; caras frente a triángulos; nombre oficial SEVB.
- **Error encontrado por la prueba de navegador y corregido:** `revelar()` de `planner.js` (animación que dibuja el tramo del viaje) trataba los `L.LatLng` como pares `[lat, lng]`; el primer cuadro lanzaba «Invalid LatLng object: (NaN, NaN)» y el tramo rojo quedaba reducido a 2 puntos (invisible) salvo con movimiento reducido. Ahora acepta ambos formatos; el algoritmo de rutas no cambió.
- **Pruebas de navegador reales** (Playwright + Edge/Chrome): `e2e/` (`run_e2e.py`, `sevb_e2e.py`, `sw_e2e.py`, `js_syntax.py`, `stub_server.py`).

### Accesibilidad (entrada original de 4.9.6)
- **Accesibilidad:** el anillo de foco global pasa de ámbar `#eaa73d` (2,1:1 contra blanco) a `#b86e00` (4,0:1) y la página tiene landmark principal (`role="main"` en el mapa). Prueba en `MotionCssTest`.
- Verificación final de integración (fases A–F): `design/fase-f/humo_api.py` (67 comprobaciones de API y seguridad) y `design/fase-f/humo_ui.js` (71 de interfaz). Service worker `spevb-v4.9.6`.

## 4.9.5
- **Transbordos inteligentes:** un segundo bus ya no se recomienda por un trayecto corto si caminar es razonable. Cada transbordo finalista se compara, con la caminata real por la red de calles, contra bajarse del primer bus y caminar hasta el destino (costo = minutos + penalización del transbordo + del segundo pasaje + caminata excesiva); se conserva si hay barrera, si no hay camino a pie o si de verdad ahorra tiempo. Si el primer bus es de unos cientos de metros se propone tomar solo la segunda ruta. Constantes documentadas en `TransbordoService`.
- Lo descartado sale, si le gana a lo directo, como opción **«Baja y camina»** o **«Una sola ruta»** con el texto «…aproximadamente … m: según los recorridos registrados, tomar otro bus no compensa la espera ni el segundo pasaje». Compatible: mismos campos de `ViajeResponse` (solo cambia `variante`).
- Pruebas: `TransbordoServiceTest` ampliado (26), `TransbordosPresentacionTest` (2) y 1 en `ViajeServiceTest`. Service worker `spevb-v4.9.5`.

## 4.9.4
- **Rutas registradas por el equipo = rutas utilizables:** se retira del uso público la casilla «Solo revisadas», el filtro «Con revisión registrada / Pendientes de validar» del navegador de rutas, la insignia «Pendiente de validar», el aviso «pendiente de validación oficial» y los textos «Recorrido por validar» / «Alguna de las rutas está por validar». «Buscar rutas» queda solo; la planificación considera todas las rutas activas y envía `soloValidadas=false`.
- **Se conserva lo interno:** columnas y campos `validada`, `fuente`, `nota_validacion`, `ruta_revisiones`, el parámetro `soloValidadas` (planificar y rutas cercanas) y la caché por ese campo, las herramientas de revisión del editor y `Planner.soloValidadas()` (devuelve `false`). Viajes recientes, historial y guardados hechos con el filtro antiguo siguen encontrándose (ya no distinguen viajes por ese campo).
- Backend: la revisión interna deja de desempatar entre rutas (`ViajeRepository`, `ViajeService.ORDEN_OPCIONES`, `RutaRepository`, `RutaService`); manda la caminata, luego el tiempo en bus y el código.
- Pruebas nuevas: `RutasPublicasTest` (5) y 2 en `ViajeServiceTest`. Service worker `spevb-v4.9.4`.

## 4.9.3
- **Paraderos contextuales:** desaparece la casilla «Paraderos» de la pantalla inicial y ya no se cargan los paraderos al abrir el mapa. Los puntos que importan (subir, cambio de bus, bajar) salen en el itinerario con su propio símbolo; los paraderos de una ruta se ven al pedirlos en sus detalles («Ver en el mapa»); la capa general queda como ajuste del menú («Mostrar paraderos del sistema»), con consulta cancelable, caché por zona, actualización por diferencias y tope de 300 marcadores.
- **Puentes solo cuando intervienen:** ya no se dibujan los 8 puentes ni se consultan cada 5 min. Se destaca (y se avisa «Cruza el puente peatonal …») únicamente el que una caminata calculada recorre de forma continua. El cálculo de la red no cambia.
- **Mapa:** el tramo del viaje se dibuja creciendo una sola vez (≈ 480 ms; el transbordo, bus 1 y luego bus 2); la caminata aparece después con un fundido y ya no se mueve de forma continua; la cámara de «Seguir bus» solo se mueve si el bus se aleja ≥ 2 px; el bucle de animación del bus se detiene en pausa y se reanuda al reproducir.
- **GPS:** muestra primero la última ubicación conocida (solo informativa), suelta la escucha por red cuando habla el GPS, no repinta lecturas dentro de la incertidumbre, pausa el seguimiento con la página oculta, pulsa solo mientras busca y el botón comunica buscando / encontrado / perdido / error.
- Pruebas nuevas: `MapaContextualTest` (7), `design/gps/gps_harness.html` (36 comprobaciones), ajustes en `docs/pruebas/paraderos_e2e.py` y `ux_puentes_e2e.py`. Service worker `spevb-v4.9.3`.

## 4.9.2
- **Sistema de movimiento** (`css/motion.css`, `js/motion.js`): tokens de duración y curvas, entradas de pines A/B (con viaje al intercambiar origen y destino), FLIP de tarjetas de resultados, escalonado corto, sin `box-shadow` animado ni `will-change` permanente, y modo de movimiento reducido verificable (`html[data-motion="reduce"]`). Prueba `MotionCssTest`. Service worker `spevb-v4.9.2`.

## 4.9.1
- **Nuevo modelo del bus** (solo visual; reproductor, estela, velocidades, barra, «Seguir bus», persistencia `spevb-bus-modelo` y `reduced-motion` sin cambios). Modelo maestro en `design/bus/` (Blender 5.2, 7.624 triángulos).
- **2D:** SVG cenital nuevo (40 nodos, sin gradientes ni `id`, contorno automático con colores muy claros u oscuros). El anterior queda como respaldo (`build2DBusClasico`) y se usa solo si el nuevo fallara.
- **3D:** sprites direccionales de 32 rumbos (`img/bus/bus_32_base.webp` + `bus_32_mask.webp`, 79,9 KB en total) que se descargan solo al activar el 3D, con la franja teñida por máscara. Si el equipo es modesto (`lowEnd`/`saveData`) o los sprites no cargan, el 3D usa el modelo CSS de antes, que ahora solo se construye cuando hace falta (en 2D se pasa de 119 a 50 nodos).
- Service worker `spevb-v4.9.1` (los dos WebP en `SHELL`).

## 4.9.0
- **Puentes peatonales** (administración): página `/puentes.html` (marcar inicio, final y puntos intermedios; los extremos se pegan a la calle; nombre; guardar/borrar), pasan a formar parte de la red de caminata y se dibujan en el mapa con icono (`docs/PUENTES.md`).
- **Panel que baja solo** al encontrar rutas en el celular (ajuste «Bajar el panel al buscar una ruta» en el menú, activado por defecto en celular): deja el mapa libre y muestra un botón «Ver rutas» que lo vuelve a abrir. El mapa se puede mover y acercar con las rutas mostradas.
- **«Seguir bus» fija el mapa** (no se arrastra) con un aviso flotante «Siguiendo el bus · Soltar», visible aunque los controles del bus estén ocultos. Solo se bloquea mientras se sigue el bus.
- Controles del bus: el botón para volver a abrirlos ahora dice «🚌 Controles».
- Animaciones suaves (entrada de tarjetas, botones, avisos); se apagan con «reducir movimiento». Menos recálculos al ocultar/mostrar el panel.
- Pruebas nuevas: `docs/pruebas/ux_puentes_e2e.py` (34/34). Service worker `spevb-v4.9.0`.

## 4.8.2
- Se borra por completo el botón de capas del mapa (también oculto por CSS por si queda una copia vieja).
- Service worker: ahora pide primero a la red (2,5 s) los archivos propios de la app; una versión nueva se ve en la primera recarga y no en la segunda. Sin conexión sigue usando lo guardado. `spevb-v4.8.2`.

## 4.8.1
- **Mapa único «Moderno»** para todas las personas: se quitó el selector de capas (ocupaba espacio) y se ignora la preferencia guardada. Los otros estilos quedan solo como respaldo interno (sin red / modo sin conexión). Cambio en `js/common.js`; service worker `spevb-v4.8.1`.

## 4.8.0
- Panel del planificador **ocultable** (celular y computador): botón de flecha en la cabecera, deslizar hacia abajo en el celular; queda el botón «Buscar ruta» para volver; lo escrito no se pierde; un resultado nuevo lo muestra solo. Archivos nuevos: `js/panel-ocultable.js`, `css/panel-ocultable.css`.
- Logos y zona de usuario con tamaños fijos (no se corren); tarjeta «Mi cuenta» en el menú.
- Google: en direcciones de túneles temporales se avisa en vez de mostrar `origin_mismatch` (ver `docs/GOOGLE.md`).
- Prueba nueva `docs/pruebas/panel_e2e.py` (13/13). Service worker `spevb-v4.8.0`. Sin cambios en Java ni en funciones existentes.


# Cambios — Transbordos y mapa estable en celular

- **Viajes con un transbordo (dos buses)** cuando no hay ruta directa buena o ahorran tiempo o caminata (ver `docs/TRANSBORDOS.md`). Camina → bus 1 → camina → bus 2 → camina, con 2 pasajes y una espera estimada de 8 min en el cambio. Se dibujan el tramo 1 (rojo), el tramo 2 (azul) y los tres tramos a pie por calles. Los viajes directos no cambian.
- **Corregido el mapa «buggueado» en celular al pulsar «Buscar rutas»:** el mapa se encuadraba dos veces seguidas (con animaciones que se pisaban). Ahora hay un solo encuadre (`Spevb.fitMap`), que cancela el anterior, cuenta el espacio realmente libre (panel inferior, botones, teclado) y se repite solo si el tamaño de la pantalla cambia justo después (teclado que se cierra, barra del navegador).
- Se cierra el teclado al elegir un punto o pulsar «Buscar rutas»; el panel se mide con el área realmente visible (`visualViewport`).
- **Celular de lado (apaisado):** usa el diseño de escritorio (tarjeta a la izquierda, mapa a toda la altura). Reproductor del bus compacto y sin solaparse en pantallas pequeñas.
- Pruebas: `docs/pruebas/transbordo_e2e.py` y `transbordo_demo.sql`.

---

# Cambios — Líneas punteadas animadas y mejor experiencia en celular

- **Líneas punteadas de caminata con movimiento muy sutil** (unos 7 px/s), en el sentido del trayecto: del origen al bus y del bus al destino. Se dibujan en SVG sobre un panel propio. La línea recta de respaldo (naranja) no se anima y «reducir movimiento» del sistema las deja quietas. El trazado por calles no cambió.
- **Reproductor del bus en celular:** compacto (de 111 a 75 px de alto), con la barra de avance bien dimensionada (heredaba el relleno y el alto de 44 px de los campos de texto) y sin tapar los botones flotantes.
- **Vista del mapa:** al fijar A y B se ajusta la vista para ver los dos; se deja espacio a la derecha para que los botones flotantes no tapen el pin B.
- Asa del panel y títulos de grupo con área de toque más cómoda.
- Pruebas: `docs/pruebas/lineas_e2e.py`.

---

# Cambios — Nuevo modelo del bus (solo aspecto)

- **Modelo 2D (ahora el predeterminado):** vista cenital de una buseta blanca de Villavicencio dibujada en SVG: trompa corta con faros y rejilla, parabrisas con letrero, tapas de ventilación, luces ámbar en el techo, espejos, ruedas y luces traseras. Las franjas laterales toman el color de la ruta.
- **Modelo 3D:** un volumen real hecho con caras CSS: laterales con ventanas corridas y pilares, puerta delantera derecha, frente con parabrisas, letrero, faros, rejilla y placa, trasera con luneta y luces, techo con tapas, ruedas con pasarruedas y espejos.
- El 3D sigue disponible con el botón «3D» y se recuerda en el navegador (clave nueva `spevb-bus-modelo`, por lo que todos empiezan en 2D). No cambia ninguna función: reproductor, estela, velocidad, rumbo, inclinación en curvas y seguimiento son los mismos.

---

# Cambios — GPS (computador y celular)

- Nuevo módulo `js/gps.js`: lectura de buena calidad (alta precisión, espera a que converja, descarta lecturas viejas), seguimiento en vivo con filtro de Kalman y filtro de saltos, calidad de la lectura, comprobación de permiso/HTTPS/ciudad y mensajes con ayuda según el dispositivo.
- Botón flotante «Seguir mi ubicación» (punto azul con círculo de precisión y flecha de rumbo); «Usar mi ubicación» muestra el avance de precisión y no usa lecturas de más de ±1 km.
- Corregido: en celulares los botones flotantes del mapa quedaban tapados por el panel inferior (`--sheet-visible` se redefinía en `body`).
- Corregido en celulares reales: «Elegir en el mapa» no registraba toques (Leaflet descarta como arrastre cualquier toque donde el dedo se mueve más de 3 px). Ahora se detecta el toque directamente y la tolerancia de Leaflet es de 14 px.
- **Elegir punto en el celular/tableta con pin central**: se mueve el mapa hasta dejar el pin donde se quiere y se pulsa «Confirmar» (no depende de acertar un toque). El toque directo sigue funcionando. La búsqueda del nombre del punto se abandona a los 8 s y se usan las coordenadas.
- El GPS escucha a la vez GPS y ubicación por red: aparece una primera lectura aproximada en segundos y se afina después.
- Avisos flotantes sobre el mapa (`Spevb.toast`) para errores, progreso del GPS y «Toca el mapa» (con botón Cancelar): la línea de estado queda al fondo del panel y podía estar tapada.
- Ver `docs/GPS.md` (incluye por qué el GPS necesita HTTPS en el celular) y `docs/pruebas/gps_e2e.py`.

---

# Cambios — Red peatonal propia y punto de subida óptimo

- **Red de calles propia** (`RedPeatonal`, `RedPeatonalService`): al primer arranque se descargan de OpenStreetMap las calles, andenes y senderos de Villavicencio (se guardan en `data/red-peatonal.bin.gz`). Los trayectos a pie solo recorren calles; ya no hay líneas rectas sobre manzanas ni edificios. OSRM queda como respaldo. Estado: `GET /api/caminata/estado`; actualización: `POST /api/editor/red-peatonal/actualizar`.
- **Subida y bajada óptimas:** cada ruta cercana se muestrea cada 15 m, se mide la caminata real por calles desde A y hasta B a cada muestra y se elige el par que menos hace caminar (sentido de la ruta respetado; descuento de 25 m si el punto es un paradero registrado). Antes solo se probaban 2 puntos por ruta elegidos en línea recta.
- Un punto en medio de una manzana prueba a la vez las 4 calles más cercanas en vez de pegarse a una sola.
- Sin trazado por calles, la línea de respaldo sale naranja y avisa que no es un camino real.
- **Semilla de calles reales incluida** (casco urbano, ≈ 37.000 nodos): la red funciona desde el primer arranque sin esperar a Overpass.
- **Variante «más rápida»** por ruta cuando ahorra 2 min o más; «Rutas cerca del origen» usa el mismo método exacto.
- **Pasos peatonales propios** (`/pasos.html`): pasos que faltan y bloqueos, guardados en PostgreSQL y aplicados a la red al instante. Ver `docs/RED-PEATONAL.md`.
- Arreglado `DireccionesHibridasServiceTest` (esperaba un patrón de búsqueda antiguo).

---

# Cambios V3.9 — Bus 3D, tramo rojo, móvil y algoritmo A→B

- **Bus en 3D** hecho con capas CSS (sin librerías): carrocería con franja de ventanas, techo con parabrisas y aire acondicionado, ruedas, faros con haz de luz y sombra. Gira con el rumbo, **se inclina en las curvas**, rebota suavemente y crece con el zoom. Botón «3D» para alternar con la vista plana (se recuerda; por defecto plana en equipos modestos).
- **Tramo del viaje en rojo con borde blanco** (antes naranja, que se confundía con las calles del mapa base).
- **Algoritmo A→B más preciso y rápido:**
  - Los **paraderos registrados** son candidatos de subida/bajada y se prefieren si no obligan a caminar más de 40 m extra; el resultado muestra «paradero X».
  - **Ensanchado ante barreras:** si caminar es más de 1,6 veces la línea recta (cerro, caño, manzana sin paso) se piden más candidatos, más juntos (cada 120 m).
  - **Planes en caché** 90 s y **cubeta de fichas** (3 consultas/s) hacia el servicio peatonal en lugar de una espera fija.
  - La mejor opción llega con **sus dos tramos a pie ya calculados por calles** (en paralelo): el navegador los dibuja sin más consultas ni el parpadeo de la línea recta.
- **Móvil:** aplicación instalable (manifiesto + iconos) con **service worker** que guarda la app y Leaflet/MapLibre (abre al instante en visitas siguientes y sin conexión la parte estática); en redes 2G/3G, con ahorro de datos o poca memoria se usa el mapa ligero y menos teselas; fotogramas limitados a ≈ 30 en equipos modestos; objetivos táctiles de 44 px; sin retardo de toque ni «tirar para recargar».

---

# Cambios V3.8 — Interfaz estilo app de mapas

- **Rediseño completo de la pantalla principal**, con identidad propia (verde SPEVB, chips de ruta con el color de cada línea, pines con punta): mapa a pantalla completa y, encima, una tarjeta flotante con origen/destino unidos por un conector, botón «Buscar rutas», chip «Solo revisadas» y resultados.
- **Resultados tipo itinerario:** cada ruta muestra su código, el tiempo estimado (caminata a 4,8 km/h + bus a 16 km/h, sin espera), una barra de tramos caminar–bus–caminar y, al abrirla, los pasos «Camina → Ruta → Camina» con la caminata por calles.
- **Móvil:** el panel es una **hoja inferior arrastrable** con tres alturas (asomado, medio, completo). Sube al escribir, baja al elegir un punto en el mapa, queda a media altura al mostrar resultados y el mapa se ajusta al espacio libre. Controles flotantes que suben con la hoja, campos de 16 px (sin zoom automático en iOS), áreas táctiles de 34–46 px y márgenes seguros (`env(safe-area-inset-bottom)`).
- Controles del mapa: zoom abajo a la derecha, selector de capas arriba a la derecha, botones flotantes «Ver recorrido» y «Vista general», etiqueta con la ruta activa y reproductor del bus como píldora inferior.
- Pines A/B con la punta en el punto exacto; inicio/fin de ruta como puntos pequeños; etiquetas de inicio/fin ocultas en móvil para no tapar el mapa.
- Nuevo `js/sheet.js`; estilos en `css/home.css` (sólo afectan a la página principal). Sin cambios en el backend.

---

# Cambios V3.6 — Base propia de direcciones

- **Base de direcciones en PostGIS** (`direcciones`, `barrios`): se importa sola del catastro municipal (≈ 100.000 predios) al primer arranque y se refresca cada 30 días. Endpoint de estado `GET /api/direcciones/estado` y administración `POST /api/editor/direcciones/importar-catastro`.
- **Intérprete de nomenclatura colombiana** (`DireccionParser`): `Carrera 48 #19-15 Sur`, `cra 48 no 19 15`, `Calle 17 Sur #39-03`, formato catastral (`C 17SUR 39 03 MZ B CS 10 URB …`), BIS, letras, esquinas (`Calle 40 con Carrera 25`) y manzana/casa. Interpreta el 96,3 % de los predios del catastro.
- **Búsqueda y autocompletado locales** (10–30 ms, sin cuota ni clave): coincidencia exacta, interpolación entre vecinas, esquinas, barrios y puntos confirmados. ArcGIS y Nominatim pasan a ser respaldo.
- **Aprendizaje:** confirmaciones de usuarios (3 coherentes ≈ 55 m ⇒ primer resultado), popularidad de sugerencias y lista de búsquedas sin resultado. Límite por IP y validaciones.
- Medido con datos reales: predio correcto en los 3 primeros a ≤ 40 m en el 93,7 % de 300 direcciones al azar escritas de 3 maneras; interpolación con mediana ≈ 1 m (p90 6 m). Ver `docs/DIRECCIONES.md`.
- **OpenStreetMap:** ≈ 1.800 lugares con nombre (colegios, universidades, parques, comercios, barrios…) y direcciones con número de puerta, importados de Overpass con varios servidores de respaldo, más una instantánea incluida como semilla. Autocompletado y búsqueda por nombre («Unicentro», «Terminal», «Universidad…»).
- **Administración:** en `/lugares.html`, estado de la base, botones para actualizar catastro/OSM, lista de búsquedas sin resultado para ubicarlas y corrección de direcciones por el equipo (prioridad sobre el catastro).
- Interfaz: las sugerencias locales se ubican sin otra consulta; una única coincidencia exacta se fija directamente; etiquetas de precisión para cada tipo de resultado.

---

# Cambios V3.5 — Caminata por calles y bus animado

- Los tramos a pie (origen → subida y bajada → destino) siguen calles y senderos peatonales de OpenStreetMap (OSRM, perfil peatonal) en lugar de una línea recta. Endpoint nuevo `GET /api/caminata`, con caché y límite de concurrencia. Si el proveedor no responde, se conserva la línea recta de respaldo. Configurable con `WALKING_URL` (vacío = desactivado).
- Bus animado con controles (pausa, velocidad, reinicio, barra de avance, seguir bus).
- Pantalla principal rediseñada como herramienta: panel lateral con scroll propio y mapa a pantalla completa (en móvil, mapa arriba y panel debajo), origen/destino en dos filas compactas con iconos, y «Rutas que puedes tomar» como menú desplegable (acordeón: al abrir una opción se dibuja en el mapa). Barra superior oscura, esquinas rectas y chips de ruta con el color de cada línea. Estilos en `css/home.css`, sólo para la página principal.
- Algoritmo de subida/bajada en dos etapas (de grueso a fino): 8 rutas × 2 candidatos medidos por caminata real y, para las 2 mejores, puntos a ±35/70/110 m del elegido. Tiempos máximos de espera 3 s (grueso) y 2 s (fino). Guía para montar un OSRM propio en `docs/CAMINATA-OSRM.md`.
- Trazo más fiel: las líneas se simplifican menos al dibujar (`smoothFactor` 0,3–0,4) y la caminata suma los tramos cortos hasta la calle; las coordenadas guardadas no se modifican.
- Mejor punto para subir y bajar: en vez de proyectar en línea recta sobre el recorrido (que puede quedar tras un cerro, un caño o una manzana sin paso), se muestrean varios puntos candidatos por ruta (cada ~60 m, uno por cada tramo de 250 m) y se elige el par con menor caminata real por la red peatonal, respetando el sentido de la ruta. Una sola consulta «table» de OSRM por cálculo, con ritmo máximo de ~2,5 llamadas/s, manejo de HTTP 429 y retorno automático a línea recta. Aplica al planificador y a «rutas que pasan cerca del origen». No se modifica ninguna coordenada de las rutas.
- Optimización de la caminata: caché de 12 h con vencimiento, consultas idénticas simultáneas fusionadas en una sola llamada, caché negativa de 45 s y corte de circuito ante fallos repetidos; el navegador reutiliza la respuesta 1 h, precarga al pasar el cursor sobre una opción y reutiliza trayectos al cambiar de opción.
- Optimización de líneas de bus sin alterar coordenadas: líneas sin hit-testing, `smoothFactor` sólo al dibujar, estela en lienzo propio con crecimiento incremental, repintado sólo cuando hay cambios y caché de 30 s del detalle de ruta.
- Mapa vectorial moderno (MapLibre GL dentro de Leaflet, datos OpenStreetMap vía OpenFreeMap, sin clave): estilos «Moderno» (por defecto) y «Minimalista», más «Calles» y «Claro» (Esri) y «OpenStreetMap», con selector. El motor vectorial (≈1,4 MB, caché de 30 días) se descarga sólo al usarlo y, mientras llega o si no hay WebGL, se muestra el mapa claro de respaldo. La elección se recuerda en el navegador.
- Carga más rápida: compresión, caché de Leaflet, preconnect a los tiles.

# Cambios V3.4 — Autocompletado y precisión (9 de octubre de 2026)

- Autocompletado real desde ArcGIS `/suggest` a partir de 3 caracteres, con espera de 450 ms para evitar consultas innecesarias. Nunca utiliza el Nominatim público por tecla.
- La consulta de sugerencias envía el texto parcial sin añadir automáticamente `Villavicencio, Meta, Colombia`; utiliza límites y proximidad para priorizar el área.
- Oculta coincidencias genéricas de ciudad y repeticiones cuando se está buscando un lugar concreto.
- Conserva el filtro por nomenclatura para no afirmar que una calle general es una puerta real. Las fuentes catastrales siguen identificándose como centros de predio aproximados.
- Al seleccionar una sugerencia, se utiliza su `magicKey` para solicitar las coordenadas. Si vuelve un único establecimiento o dirección puntual, el marcador aparece directamente; si hay varias coincidencias, el usuario escoge.
- Nuevas mejoras de teclado (flechas, Enter y Escape) y estados ARIA para sugerencias.
- Conserva mapa Leaflet/OpenStreetMap, editor, rutas, paraderos, PostGIS, selecciones manuales, geolocalización, intercambio A/B y cálculo de viajes sin cambios en el esquema SQL.
- La clave ArcGIS sólo se usa en Java; no se incluye en el ZIP, ni se copia al frontend, ni se almacenan resultados de ArcGIS en la base de datos.
- Pruebas agregadas con respuestas simuladas; consulta también `docs/PRUEBA-V3-4.md` para validación con proveedores reales.

---

## 4.7.0 — Paraderos de bus independientes y mejor elección del punto de subida
- **Paraderos de bus del sistema**, independientes de las rutas: página `/paraderos.html` (clic en el mapa, editar, mover, desactivar, buscar, importar GeoJSON/CSV) y capa pública en el mapa.
- El planificador usa esos paraderos (y los de cada ruta) para subir y bajar: preferencia si no obligan a caminar más de ≈ 25 m extra; un paradero a más de 30 m de la línea de la ruta no cuenta. Los planes en caché se invalidan al cambiar paraderos.
- Prueba masiva del algoritmo (`docs/pruebas/algoritmo_fuzz.py`): sin sube-después-de-bajar, sin transbordos inválidos y sin puntos fuera de la línea.

## 4.6.0 — Cambio de bus visible, daltónicos, administradores y editor de paraderos
- Cuadro **«Cambio de bus»** y insignias ①/② por tramo; rótulos «Sube/Bájate aquí» que no se salen de la pantalla; **modo para daltónicos** y **guía de colores**; **controles del bus ocultables** (ocultos por defecto en el celular).
- **Administradores** por correo verificado con Google (`ADMIN_CORREOS`): los enlaces de administración solo los ven ellos y no necesitan escribir la clave.
- **Editor de paraderos** en el editor de rutas (clic sobre la línea, ajuste al recorrido, orden automático, guardado aparte y validado en el servidor).
- Menú más ordenado (espacios de cuenta, pantalla y sin conexión). Ver `docs/ADMIN-Y-PARADEROS.md`.

## 4.5.0 — Entrar con Google
- Botón **Continuar con Google** (opcional, con `GOOGLE_CLIENT_ID`): el servidor verifica la firma del token de Google, crea o vincula la cuenta, y las cuentas de Google pueden crear una contraseña después. Ver `docs/GOOGLE.md`.

## 4.4.0 — Cuentas de usuario, historial, viajes guardados y rutas favoritas
- **Crear cuenta / entrar / salir** (PBKDF2, sesión por cookie HttpOnly, límites y defensa CSRF), ajustes (cambiar contraseña, cerrar todas las sesiones, eliminar cuenta).
- **Historial**, **viajes guardados**, **lugares** y **rutas favoritas (★)** que se sincronizan entre PC y celular; sin cuenta siguen funcionando en el dispositivo. Ver `docs/CUENTAS.md`.

## 4.2.0 — Mapa más rápido y créditos compactos
- Créditos del mapa: solo «Leaflet ⓘ»; al tocar se despliegan los demás. Mapa ráster de respaldo solo si el vectorial tarda. Resolución limitada a 2×, menos trabajo de GPU, animaciones decorativas en pausa mientras se mueve el mapa. Ver `docs/RENDIMIENTO-MAPA.md`.

## 4.1.0 — Compartir, favoritos, avisos al bajar, reportes y despliegue
- **Compartir viaje** (enlace que abre y busca solo), **Mis lugares** (favoritos), **Avisarme al bajar** (GPS: vibración, sonido y mensaje), **Reportar un problema** (con página de administración `/reportes.html`), botón **Instalar la aplicación**. Ver `docs/EXTRAS.md`.
- **Límite de solicitudes por IP** para publicar en internet (429 con mensaje claro).
- **Despliegue 24/7**: `compose.prod.yml` (Caddy + HTTPS + app + PostGIS sin puerto público), copias de seguridad (`deploy/backup.sh`, `restore.sh`) y `docs/DESPLIEGUE.md`.
- `/api/estado` muestra la versión real; mensajes de error de validación más claros en la lectura del cuerpo JSON.

## 4.0.0 — Uso sin conexión o con señal mala
- Nuevo service worker: la app abre sin internet; rutas guardadas (red primero con límite de espera); mapa guardado/visto.
- Menú «Uso sin conexión»: guardar/actualizar/borrar el mapa de Villavicencio (con progreso y cancelación).
- «Recientes»: los últimos 6 viajes A→B se guardan y se pueden repetir sin conexión (con aviso de resultado guardado).
- `Spevb.api` corta a los 25 s y distingue `offline`/`timeout`; avisos al perder y recuperar la conexión; cambio automático al mapa guardado.
- Solo se añade: ninguna función existente cambia. Ver `docs/OFFLINE.md`.

## V3.2 — Precisión de direcciones

- Corregido el filtro `LIKE` del catastro: tenía comillas duplicadas y ArcGIS REST rechazaba la consulta.
- Si el usuario escribe `#19-05`, Nominatim sólo puede devolver un inmueble con `address.house_number` compatible; calles sueltas no aparecen como su casa.
- ArcGIS profesional descarta candidatos que no respetan el tipo de vía y los números de la consulta.
- La API `/api/lugares` devuelve `avisos` y banderas de configuración, sin exponer credenciales.
- La interfaz informa si la fuente catastral no respondió y si sólo existe una posición aproximada.
- Autocompletado ArcGIS **opcional**: endpoint `/api/lugares/sugerencias` y `magicKey` sin exponer token; se activa sólo al configurar `ARCGIS_API_KEY` en `.env`.

**Pendiente:** probar el catastro en vivo desde Windows (este entorno no tiene salida DNS).

# V3.1 — direcciones de Villavicencio (octubre 2026)
- Consulta a U_TERRENO con centroide o geometría de predio, normalización de abreviaturas y filtros contra coincidencias falsas.
- ArcGIS World Geocoding opcional vía clave de API en servidor; sin almacenamiento automático.
- Campos `precision` y `fuente` para mostrar que algunos marcadores son aproximados.
- La selección manual conserva la coordenada original tras la geocodificación inversa.
- Pruebas locales con respuestas simuladas y documentación de validación de 30 direcciones.
- No se modifica el volumen PostGIS ni el planificador de recorridos.

# Changelog

## V3 (3.0.0)

Cambios frente a la V2 publicada en `main`.

### Planificador de viaje (nuevo)

- Compara el origen A y el destino B con todas las rutas activas, sin límite de distancia: la primera opción es siempre la ruta registrada más cercana.
- Dibuja automáticamente la mejor opción: línea punteada desde A hasta el punto de subida, tramo del bus y línea punteada desde el punto de bajada hasta B.
- Respeta el sentido de circulación: las rutas `IDA` y `REGRESO` se usan en el sentido en que fueron dibujadas; un viaje al revés aparece como alternativa con advertencia. Las rutas `CIRCULAR` pueden dar la vuelta completa.
- «Ver rutas que pasan cerca del origen» devuelve las 8 rutas más cercanas y dibuja la primera.

### Direcciones y lugares (nuevo)

- Búsqueda de direcciones de Villavicencio con Nominatim/OpenStreetMap, acotada al área de la ciudad.
- Normalización de nomenclatura colombiana (`Cra`, `Cll`, `Av`, `Diag`, `Tv`, `No.`, `5 a 50`…) sin alterar palabras completas como `Avenida`, `Norte` o `Clínica`.
- Catálogo local persistente (`lugares`) para registrar puntos que no aparecen en el proveedor.

### Editor de recorridos

- Creación de rutas nuevas, no sólo edición de las DEMO.
- Historial de versiones en `ruta_revisiones` y control de concurrencia (`409 Conflict`).
- Botón «Invertir sentido» para corregir rutas dibujadas al revés.
- Se rechazan geometrías inválidas, fuera de Villavicencio o con más de 10.000 puntos.

### Seguridad y operación

- Operaciones de edición protegidas con `EDITOR_TOKEN`, cabeceras de seguridad y límite de tamaño.
- Las claves se leen de `.env`, que está en `.gitignore`; `docs/CAMBIAR-CLAVES.md` explica cómo reemplazarlas.
- `Dockerfile`, `compose.app.yml`, `backup.ps1`, `restore.ps1`, `start.ps1` y `diagnostico.ps1`.

### Pruebas

- Nueve clases de prueba en `src/test` (la V2 no tenía pruebas automáticas).

### Pendiente

- Trazar sobre las calles las 4 rutas aprobadas (Porfía, Galán, Catama y Álamos); hoy son geometrías DEMO.
- Validación oficial de rutas: pendiente de respuesta de las entidades públicas.
- Transbordos entre rutas y caminos peatonales por la red vial.


## 3.3 — Normalización catastral de Villavicencio
- Reconoce K como CARRERA y C como CALLE en los registros catastrales.
- Divide placa y orientación pegadas (`15SUR` → `15 SUR`).
- Evita falsos positivos cuando un número coincide con manzana o lote, no con placa.
- Nuevos tests de regresión basados en filas reales de U_TERRENO.
- No inventa coordenadas para `Carrera 48 #19-05 Sur` si el predio no está disponible.
