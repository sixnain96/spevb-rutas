# UX móvil 4.14 · coordinación de superficies, «Volver al inicio», solo Google y A/B fiable

Rama de trabajo: `ux-movil-solo-google` (desde `main`, commit `771de2f`). Punto de recuperación: etiqueta **`recuperacion-pre-ux-movil`**.
**No se hizo commit, publicación ni despliegue**: todo está en el árbol de trabajo para que lo revises.
Evidencia visual: `docs/pruebas/capturas-4.14/`. Pruebas nuevas: `e2e/ux414_e2e.py` (navegador real, 33 casos), `SoloGoogleConfiguracionTest`, y las de Google actualizadas.

## 1. Causas encontradas

Lo que **se reprodujo** con el mismo recorrido (planear → iniciar viaje → abrir menú) en el commit anterior, 320 × 568, contra el stub:

| # | Causa confirmada | Evidencia |
|---|---|---|
| C1 | **Instrucción en una columna estrecha.** El título, la insignia de la ruta y tres botones de 44 px compartían una sola fila (`.viaje-cab`): el texto quedaba en **79 px de 300 (26 %)**; en tableta, 135 de 480. | `antes_movil320x568_1_viaje.png` |
| C2 | **Tarjeta y planificador a la vez.** Al iniciar, el planificador seguía asomado (227 px) bajo la tarjeta (317 px): **544 de 568 px (96 %)** de la pantalla. | mismas capturas |
| C3 | **Paneles que se abren sin pedirlo.** `sheet.js` abría el panel a pantalla completa con cualquier `focus` del campo, también el que no viene de un gesto (el navegador lo restaura al volver a la app, un script, una animación). Reproducido: `focus()` programático → panel `full`. | `ux414_e2e` · foco programático |
| C4 | **Gestos mal separados.** El arrastre del asa escuchaba `pointermove/pointerup` en `window` **sin `pointerId`** (un puntero ajeno movía el panel) y `pointercancel` se trataba como «soltar» (con empuje por velocidad). Reproducido en ambos casos. | `ux414_e2e` · gestos |
| C5 | **Proveedores mezclados.** `LugarService` combinaba base propia + ArcGIS + Google + Nominatim, con cupos por fuente; `start.ps1` y los compose activaban Nominatim y las importaciones automáticas por defecto; `reverso` consultaba Nominatim. | `LugarService` anterior, `compose*.yml`, `start.ps1` |
| C6 | **«Volver al inicio» contradictorio.** La casita del viaje pedía «tocar otra vez» con un aviso que desaparecía a los 4 s; la marca del planificador **recargaba la página** (`href="/"`); la casita del mapa solo movía el mapa. | código anterior |

Causas **confirmadas por lectura del código** (no reproducidas en un dispositivo; sus pruebas están en `ux414_e2e` y pasan con la versión nueva):

- **C7. A/B no reconocidos al tocar una sugerencia.** La opción dependía de `click` y la lista se cerraba 160 ms después de perder el foco: con el orden *pointerdown → blur → (el teclado cambia el layout) → click* la opción podía desaparecer antes de procesarse. Además `choose()` quitaba el foco a **cualquier** campo (aunque la persona ya hubiera pasado al otro) y el `finally` de una búsqueda vieja volvía a habilitar el botón de otra más reciente. Había una sola generación por campo para sugerencias, búsqueda y reverso, y un detalle fallido caía en una lista de «coincidencias» distintas de lo elegido.
- **C8. Bloqueo de scroll asimétrico.** `extras.js` quitaba `modal-open` del `body` al cerrar el reporte **aunque otro diálogo siguiera abierto**; cada módulo manejaba `menu-open`/`modal-open` por su cuenta.
- **C9. Inalcanzable sin cabecera.** Si el planificador se oculta (p. ej. para ceder el sitio al viaje) el botón del menú, que vive en su cabecera, quedaba inaccesible.

**Hipótesis sin confirmar — «el menú se bloquea durante el viaje».** En el commit anterior, con ratón/rueda emulados, el menú **sí recibía toques y se desplazaba** (`antes_*_2_menu_en_viaje.png`, tabla de «comprobaciones» abajo): la tarjeta (z-index 2600) vive dentro de `.app` (`position: fixed`, pila propia), así que **no tapaba** el menú (z-index 1400 en el `body`). Por eso **no puedo afirmar la causa del bloqueo que viste en el teléfono**. Candidatos plausibles que se endurecieron: el menú no tenía `touch-action: pan-y` ni `overscroll-behavior: contain` propios; los arrastres sin `pointerId`/sin liberar en `pointercancel`; el bloqueo de scroll asimétrico (C8). **Necesita confirmarse en un teléfono físico** (sección 8).

## 2. Cambios implementados y archivos

**Frontend**

| Archivo | Cambio |
|---|---|
| `js/superficies.js` (**nuevo**) | Coordinador: única fuente de verdad (principal, quién cedió, viaje, overlay, foco, causa). Menú, diálogos (observa `[hidden]` de `.modal-backdrop`), botón de menú flotante, `inert`, bloqueo de scroll **derivado** del estado, `puedeAbrir(causa)` para las aperturas automáticas y un registro (`registro()`) de cada transición con su causa. |
| `js/ui.js` | Queda como respaldo: el menú lo gobierna `superficies.js`. |
| `js/panel-ocultable.js` | Expone `Spevb.Panel` (`ocultar/mostrar(causa)`); distingue ocultar por la persona, por el viaje o automático; el foco solo se mueve si lo pidió un gesto; gestos con `pointerId`, cancelación y `blur`. |
| `js/sheet.js` | `focus → pantalla completa` **solo con gesto** y sin overlay encima; resultados no reorganizan si hay overlay/viaje/panel ocultado por la persona; arrastre con `pointerId`, `pointercancel`/`blur`/ocultar página **sin inercia**. |
| `js/viaje.js` | Tarjeta en filas; aviso importante con el menú abierto (misma tarjeta reducida a **una** pregunta o aviso); `sin_senal` ya no maximiza la tarjeta; margen tras volver del segundo plano; tarjeta y pastilla en `<body>`; gesto del asa siempre termina; descartar viaje pendiente de reanudar; `S.Viaje.pendiente/llego`. |
| `js/inicio.js` (**nuevo**) | «Volver al inicio» (un solo camino) con diálogo de confirmación si hay viaje. |
| `js/planner.js` | Estado por campo (`vacio · editando · consultando · sugerencias · resolviendo · confirmado · error`), generación/petición/indicador **por campo**, sesión de Places por campo, elección con `pointerup` + `click` (una sola vez, el arrastre no elige, se descarta el click fantasma), blur no cierra la lista mientras hay un dedo encima, foco solo se quita al campo confirmado, mensajes por estado con «Reintentar», sin base propia ni catastro, `Planner.estados()` y `detenerGps()`. |
| `js/siguiente.js` | «Ahora escribe B» no se muestra con un menú/diálogo abierto. |
| `css/viaje.css` | Tarjeta por filas, pie fijo, cesión animada, aviso urgente, texto completo en las herramientas A/B (rejilla de 2 columnas, sin «En el m…» ni «Mi ubica…»), `overflow-wrap`, estados de búsqueda, menú con `touch-action`/`overscroll`, reduced-motion. |
| `index.html`, `sw.js` | Scripts nuevos, botón «Volver al inicio» en el menú, el botón de la casita del mapa pasa a «Ver toda la ciudad» (otro icono: solo mueve el mapa). |

**Backend y configuración**: `LugarService`, `LugarController`, `GoogleDireccionesService` (sesiones y errores distinguidos), `RutaController` (`/api/estado`), `application.properties`, `compose.app.yml`, `compose.prod.yml`, `start.ps1`, `.env.example`, `docs/CONFIGURACION.md`, `docs/GOOGLE-MAPS.md`.

## 3. Política de prioridad de superficies

Una **superficie principal** expandida a la vez: `planificador` o `viaje`. Los **overlays** (menú, diálogos) reciben toda la interacción mientras están abiertos. Las listas de sugerencias **no** son superficies: pertenecen a su campo.

| Situación | Resultado |
|---|---|
| Abrir el menú | Lo de debajo (planificador, mapa, pastilla) queda `inert`; la tarjeta del viaje **cede** (animación corta, `visibility:hidden`); el foco entra al menú sin abrir teclado. |
| Cerrar el menú | Vuelve lo que cedió, **tal como estaba**: no reabre lo que la persona ocultó; el foco vuelve al botón que lo abrió (nunca a un campo de texto). |
| Abrir un diálogo (cuenta, ayuda, reporte, confirmación) desde el menú | El menú cede ante el diálogo y **vuelve** al cerrarlo. |
| Iniciar viaje | La tarjeta es la principal; el planificador se oculta con causa `viaje` (no pierde A/B ni lo escrito) y el menú sigue accesible con el botón flotante. |
| Terminar el viaje | El planificador vuelve **solo si lo ocultó el viaje**; si lo ocultó la persona, se respeta. |
| Ocultar/minimizar la tarjeta | Solo presentación; **el GPS no se detiene**; la pastilla «Viaje» la devuelve. |
| Aviso importante con otro menú abierto | La misma tarjeta se muestra reducida a **una** pregunta o aviso, arriba de todo; contestar no cierra el menú ni reinicia la fase. |
| Resultados llegan con un menú abierto / en viaje / panel ocultado por la persona | Esperan: no cambian alturas ni abren nada (queda en el registro como `rechazada:resultados`). |
| GPS, teclado, resize, animaciones, pistas, respuestas tardías | **Nunca** abren una superficie por su cuenta (`puedeAbrir` = no). |
| Foco de un campo | Abre el panel completo **solo si lo causó un gesto** de la persona. |

Acciones distintas (no se usa la misma para todo): ocultar · minimizar · cerrar vista temporal · cancelar · terminar viaje · volver al inicio.

## 4. «Volver al inicio»

Accesos (todos pasan por `Spevb.Inicio.pedir()`): la **marca SPEVB** del planificador (antes recargaba la página; sirve en planificación y resultados), el botón **«Volver al inicio»** al principio del menú, el enlace «Planear viaje» del menú y la **casita** de la tarjeta del viaje.

- **Con viaje activo** (o uno pendiente de reanudar): diálogo «¿Terminar el viaje y volver al inicio?» con **«Seguir viajando»** (recibe el foco; Escape también cancela) y **«Terminar y volver al inicio»**.
- **Sin viaje:** limpia el trabajo actual de una vez («Listo: empezamos de nuevo.»).
- **Limpia:** viaje (GPS, wake lock, temporizadores, capa, estado reanudable), A y B, marcadores, resultados, sugerencias, búsquedas/detalles/reversos pendientes (cada uno por su generación), «elegir en el mapa», seguimiento de «mi ubicación», rutas dibujadas, pistas, avisos y preguntas, menús y diálogos; restaura controles; mapa a la vista inicial; cierra el teclado; foco al título del panel sin abrir nada.
- **No toca:** cuenta, preferencias, favoritos, historial, viajes guardados (verificado en la prueba).
- **Idempotente** (repetirla no da errores) y una respuesta tardía no restaura A, B ni el viaje (probado).

## 5. Evidencia de que solo Google participa

1. **Código:** `LugarService` solo depende de `GoogleDireccionesService` (+ `LugarRepository` para el CRUD de lugares y `reverso` local) y `DireccionesLocalesService` solo para los endpoints de confirmación heredados; `SoloGoogleConfiguracionTest` falla si `LugarService`/`LugarController` mencionan ArcGIS, Nominatim, `hibrido`, `geocoder` o catastro.
2. **Configuración por defecto:** `GEOCODER_URL`/`GEOCODER_REVERSE_URL` vacíos (Nominatim apagado), `DIRECCIONES_IMPORT_AL_ARRANCAR=false`, `OSM_IMPORT_AL_ARRANCAR=false` en `application.properties`, ambos compose, `start.ps1` y `.env.example` (probado). Las redes vial y peatonal (Overpass), PostGIS y el ruteo siguen activos (probado).
3. **Navegador:** `ux414_e2e` registra **todas** las peticiones durante una sesión completa (sugerencias, búsqueda por texto, elección, mapa, coordenadas) y falla si alguna va a `nominatim`, `geocode`, `arcgis…/GeocodeServer`, `findAddressCandidates`, etc. Solo se llama a `/api/lugares*` propio.
4. **Sin respaldo silencioso:** `LugarServiceGoogleTest` — si Google falla devuelve `ERROR_SERVICIO`/`ERROR_CONFIGURACION` con lista vacía y no consulta nada más.
5. El service worker nunca intercepta `/api/lugares*`: sin cachés viejas.

## 6. Corrección de A/B

Reglas nuevas, cada una con prueba:

- **La confirmación depende de una ubicación válida** (`puntoValido`: coordenadas finitas) asociada al `placeId` elegido; **no exige id de base de datos**.
- **Elegir es atómico y sin carreras:** se activa con `pointerup` sobre la misma opción (o `click`/teclado), **una sola vez**; un toque que desplaza la lista no elige; el click fantasma sobre lo que queda debajo se descarta; el blur del campo no cierra la lista mientras hay un dedo encima o la elección se está resolviendo.
- **Cada campo es independiente:** estado, generación, petición, indicador y sesión propios. Una respuesta con generación vieja se ignora (no confirma, no reabre la lista, no apaga el indicador de una más nueva). Cambiar de campo, borrar, intercambiar o volver al inicio invalida lo pendiente.
- **Si falla el detalle del elegido no se elige otro lugar:** el texto queda, A/B sin confirmar, mensaje con «Reintentar» y «En el mapa»; sin bucles.
- **Foco:** confirmar A no le quita el foco a B; volver a enfocar un punto confirmado no inicia otra búsqueda; una edición real lo invalida.
- Al confirmar: guarda el punto, actualiza texto y marcador, cierra y vacía la lista, cancela debounce y peticiones, cierra el teclado (solo del campo confirmado), habilita la planificación y **no maximiza** el planificador.

## 7. Pruebas y resultados

> Entorno: Windows 11, Edge (Chromium) con Playwright, frontend real, **backend simulado** (`e2e/stub_server.py`), **Google simulado** con rutas de Playwright, **GPS simulado** (`e2e/simulador_gps.js`), **SDK real de mapas** (Leaflet/MapLibre, con teselas externas bloqueadas). Los viewports son **emulaciones**, no un iPhone ni un Android físicos.

| Suite | Resultado |
|---|---|
| Java (`mvn test -Dsevb.sinDocker=true`) | **379 pruebas, 0 fallos**, 9 omitidas (migraciones PostGIS sin Docker) |
| `ux414_e2e.py` (nueva) | **36/36** |
| `sevb_e2e` · `estados` · `viaje_logica` · `gpx` · `joystick` | 20/20 · 16/16 · 25/25 · 4/4 · 17/17 |
| `viaje_e2e` · `aprender` · `mejoras414` · `resultados` · `movil` | 27/27 · 30/30 · 16/16 · 20/20 · 9/9 |
| `puntos_e2e` | 10/11: falla `movil_y_escritorio_sin_desborde_y_con_area_tactil_de_44px` («escritorio 1280: campo demasiado estrecho»). **Falla igual, con las mismas cifras, en el código anterior**: no es regresión de estos cambios. |
| `editor_e2e`, `sw_e2e` | **No se volvieron a ejecutar** (no tocan lo cambiado). |

Cada suite se corrió en su propio carril con su propio stub (correr todas contra un solo stub las satura y da fallos falsos).

**Pruebas heredadas que hubo que adaptar porque la política cambió a propósito:** «tocar la casita dos veces» → diálogo de confirmación (`viaje_e2e`); `fill()` sin gesto ya no abre el panel completo, hace falta tocar el campo (`resultados_e2e`); la tarjeta vive en `<body>` y no en `.app` (`movil_e2e`).
Corrección hecha durante la verificación: el planificador solo cede ante el viaje en el **diseño de celular** (en escritorio y apaisado conviven como antes); la transición de la tarjeta ya no frena su arrastre; las herramientas A/B (Buscar · En el mapa · Mi ubicación) van en **una** fila con texto completo (en 280 px bajan a dos líneas); la atribución de Google va en cada resultado (una fila aparte tapaba el campo B).

**Las pruebas nuevas no son vacías:** contra el código anterior fallan 30 de 31 (muchas por falta de la API nueva, pero también por comportamiento: el gesto cancelado movía el panel, un puntero ajeno lo arrastraba, un `focus()` sin gesto lo abría a pantalla completa, la instrucción quedaba en una columna estrecha).

**Cifras antes → después** (320 × 568, mismo recorrido): instrucción **79 px (26 %) → 275 px (90 %)** del ancho de la tarjeta; tarjeta + planificador **544 de 568 px (96 %) → solo la tarjeta, 329 px (58 %)**.


## 8. Limitaciones pendientes

- **No probado en dispositivos físicos** ni en Safari de iPhone ni en Chrome de Android. En iOS real el teclado (`visualViewport`), las barras del navegador y la captura de punteros pueden diferir de la emulación.
- **No probado contra Google real:** no hay clave en este entorno; el contrato de la API se probó con servidores simulados. Hay que probar una clave real (cuota, restricciones y que el autocompletado devuelva lo esperado para Villavicencio).
- **Mapa de Google:** se agregó como capa base (ver `docs/GOOGLE-MAPS.md`) pero **solo se probó con Google simulado**; hace falta una clave de navegador real (`GOOGLE_MAPS_BROWSER_KEY`) y revisar que la política CSP no bloquee algo en un navegador real.
- **Condiciones de Google:** con el mapa de Google de fondo la condición de mostrar Places sobre un mapa de Google queda cubierta cuando el mapa de Google está activo; sin él (respaldo) aplica lo siguiente: Places se muestra sobre un mapa que **no** es de Google; cada resultado dice «Google Maps» pero no se muestra el logotipo oficial. **Revisar con las condiciones vigentes antes de publicar** (ver `docs/GOOGLE-MAPS.md`).
- **Puntos elegidos en el mapa** se nombran con la base propia de lugares o con sus coordenadas; **no** hay geocodificación inversa de Google (usaría otra API y otra autenticación). Es una decisión pendiente si se quiere el nombre de la calle.
- **«Menú bloqueado durante el viaje»:** causa **no confirmada** (sección 1); se aplicaron defensas y hay pruebas de comportamiento, pero hay que verificarlo en un teléfono real.
- **Grabación en vídeo:** `playwright` necesita descargar `ffmpeg` (`playwright install ffmpeg`) y no se descargó sin tu permiso; se entrega una **secuencia de cuadros** (`despues_secuencia_menu_en_viaje_360x640.png`). El script para grabar el vídeo está descrito al final de esta página.
- Las **pantallas de administración** (`lugares.html`, `paraderos.html`…) reutilizan el buscador, ahora de Google; su tratamiento aparte queda por decidir. Los servicios `DireccionesHibridasService`, `GeocodingService` y los importadores siguen en el código (para uso administrativo manual) pero **no** participan en el flujo del pasajero.
- La nueva política deja sin uso `POST /api/lugares/confirmaciones|elecciones` desde el navegador; los endpoints siguen por compatibilidad.
- `movil_e2e.py` (suite móvil anterior) ya se atascaba esperando el mapa **antes** de estos cambios en este equipo (ver resultados).

## 9. Cómo probar en Safari de iPhone y Chrome de Android

Necesitas la app en **HTTPS** (la ubicación del navegador lo exige): tu dominio, o un túnel temporal (`cloudflared tunnel --url http://localhost:8080` con la app arrancada con `.\start.ps1`). Con `GOOGLE_MAPS_API_KEY` en `.env` para probar la búsqueda real.

1. **Recarga limpia:** Safari → Ajustes → Safari → Avanzado → Datos de sitios web → borrar el del dominio; Chrome Android → ⋮ → Información del sitio → Borrar datos. (Así el service worker trae los archivos nuevos.)
2. **Búsqueda:** escribe en A («Unicentro»), **toca** una sugerencia (con el teclado abierto): A debe quedar con su marcador y el teclado cerrarse; igual con B. Prueba con 3G lento (Chrome DevTools remoto / Network Link Conditioner) y apaga la red un instante para ver «Google no respondió… Reintentar».
3. **Menú durante el viaje:** busca una ruta, «Iniciar viaje»; el planificador se oculta y aparece el botón ☰ arriba a la derecha. Ábrelo, **desplaza el menú con el dedo** (arriba y abajo), abre «Entrar» y ciérralo (debe volver al menú), cierra el menú: la tarjeta vuelve con la fase actual.
4. **Aviso con el menú abierto:** con el menú abierto espera/provoca una pregunta de subida o bajada (o usa el simulador de GPS del proyecto): debe aparecer **una sola** pregunta arriba, legible.
5. **Segundo plano:** bloquea el teléfono 1 minuto en pleno viaje y vuelve: no debe abrirse el planificador ni maximizarse la tarjeta; el viaje continúa.
6. **Volver al inicio:** casita de la tarjeta → diálogo → «Seguir viajando» no cambia nada; «Terminar y volver al inicio» limpia todo; recarga: no ofrece reanudar.
7. **Pequeño y ampliado:** letra «muy grande» en el menú y/o zoom del navegador al 200 %; nombres largos; modo horizontal.

Para una **grabación de vídeo** de la emulación: `playwright install ffmpeg` y crear el contexto con `record_video_dir` (ver `e2e/ux414_e2e.py`, función `movil`).

## 10. Cómo revertir

Nada está confirmado en Git: el estado anterior es el commit `771de2f` / etiqueta `recuperacion-pre-ux-movil`.

```bash
git stash push -u -m "ux-movil-solo-google"     # guarda todo y deja el árbol como estaba (reversible con git stash pop)
git switch main
```

Si luego haces commit y quieres deshacerlo: `git revert <commit>` o `git switch main` (la rama conserva el trabajo). Configuración: basta con volver a definir `GEOCODER_URL`, `GEOCODER_REVERSE_URL` y las dos variables `*_IMPORT_AL_ARRANCAR` si algún día se quisiera reactivar lo apagado (no se recomienda).
