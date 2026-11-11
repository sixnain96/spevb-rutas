# Continuidad del viaje (4.14) · mapa, reanudación, pantalla bloqueada y recálculo

Este documento explica **qué se encontró, qué se corrigió, cómo se probó y qué sigue pendiente** a partir del video de un trayecto real en la ruta KIRPAS
(las calles del mapa desaparecían «por bloques» mientras el punto azul, la línea roja y la distancia seguían avanzando; al recargar salía «El mapa todavía no está listo»).

> **Regla de honestidad.** Que pasen las pruebas con un Google *simulado* **no demuestra** que el fallo del video esté resuelto. Lo que sí se corrigió y probó es que el **viaje ya no depende del fondo del mapa**
> y que el fondo tiene detección y recuperación. La causa exacta de la pérdida de calles de Google **no se pudo confirmar** sin un teléfono y una clave reales (sección 5).

## 0. Versión y rama de trabajo (comprobado antes de editar)

| Lugar | Versión | Qué contiene |
|---|---|---|
| `main` / `771de2f` | `pom.xml` 4.13.1 | Leaflet; **sin** integración de Google Maps |
| `origin/main` / `82a016f` | `pom.xml` **4.13.5** | Capa de mapa con adaptadores Leaflet/Google (`js/mapa/*`), `modales.js`, `carga.js`, búsqueda con estados tipados |
| `target/spevb-rutas-4.13.5.jar` | 4.13.5 | Compilado desde esa línea (incluye `js/mapa/adaptador-google.js`) |
| Rama de trabajo anterior (`ux-movil-solo-google`) | 4.13.1 + mi UX | **Divergía** de 4.13.5 (21 archivos en conflicto) |

Decisión: se trabajó sobre **`origin/main` (4.13.5)** en la rama **`integracion-4.13.5`**; las mejoras de interfaz y de Google Maps de 4.13.5 se conservaron y mi trabajo previo de UX móvil se integró encima.
No se editó nada dentro de `target/`.

## 1. Problemas encontrados

Gravedad: **Crítica** = el pasajero pierde el viaje o queda sin guía · **Alta** = pierde información importante · **Media** = degrada la experiencia.
«Confirmado» = reproducido con herramientas de navegador o visible sin ambigüedad en el código; «Hipótesis» = plausible, no confirmado.

| # | Problema | Gravedad | Estado | Cómo reproducirlo |
|---|---|---|---|---|
| P1 | **Reanudar cerraba la tarjeta y *después* comprobaba el mapa**: `iniciar()` devolvía «El mapa todavía no está listo» y el viaje no arrancaba, con la pregunta ya cerrada. | Crítica | Confirmado · corregido | Guardar un viaje, recargar con el SDK de Google bloqueado, tocar «Reanudar» |
| P2 | **El viaje exigía el mapa para existir** (`iniciar()` abortaba sin mapa; `pintarPosicion` y `asegurarRecorrido` lo daban por hecho). | Crítica | Confirmado · corregido | Igual que P1; o Google tardando más de lo normal |
| P3 | **«Recalcular desde aquí» borraba el viaje antes de tener otro** (`finalizar()` primero, búsqueda después). Con el servidor caído, sin internet o sin opciones se perdía el viaje. | Crítica | Confirmado · corregido | Durante un viaje: cortar la red y tocar «Recalcular desde aquí» |
| P4 | **Reanudar perdía la hora original** (se guardaba `inicio`, pero `iniciar()` usaba `Date.now()`). | Media | Confirmado · corregido | Reanudar un viaje de hace 25 min: decía «iniciado hace 1 min» |
| P5 | **El avance solo se guardaba en cambios de fase/alertas**: nada al ocultar la página ni de forma periódica; la última posición no se guardaba. | Alta | Confirmado · corregido | Mover el teléfono, cerrar el navegador y reabrir |
| P6 | **Regreso de la pantalla bloqueada**: no se renovaba la escucha del GPS ni se marcaba la posición como antigua (los callbacks viejos podían pisar el estado). | Alta | Confirmado en código · corregido | Bloquear 1 min en pleno viaje y volver |
| P7 | **El fondo del mapa pierde calles «por bloques»** sin que Google lanzara un error observable: nadie se enteraba y no había forma de recuperarlo. | Alta | **Causa NO confirmada** · detección y recuperación implementadas | Solo en un teléfono real (ver §5) |
| P8 | **La recomendación y «Iniciar viaje» esperaban el detalle de la ruta** (`showOption` abortaba si `/api/rutas/{id}` fallaba o tardaba). | Media | Confirmado · corregido | Retener `/api/rutas/{id}` y planear |
| P9 | **Controles visibles sin manejadores**: el planificador solo existe cuando el mapa está listo, pero sus botones se veían activos. | Media | Confirmado · corregido | Con el SDK bloqueado, tocar «Buscar rutas» |
| P10 | **Menú principal de ≈ 6,5 pantallas a 320 px** (3 695 px de contenido). | Media | Confirmado · mejorado (3,2 pantallas) | Abrir el menú a 320 × 568 |
| P11 | Avisos de conexión que podían tapar una pregunta de bajada. | Alta | Hipótesis · evitado por diseño | Ver §3.4 |

## 2. Qué se hizo (archivos)

**Viaje independiente del mapa** — `js/viaje.js`
- `iniciar()` ya **no** exige el mapa: arranca, y la capa de dibujo se crea cuando el mapa está disponible (`asegurarCapa()`, se reintenta con cada vigilancia de 3 s y al volver a la app). El viaje, la fase, las indicaciones y los avisos funcionan sin fondo.
- **Reanudar**: la pregunta solo se cierra **cuando el viaje de verdad arrancó**; si falla (p. ej. GPS sin permiso) se queda con el motivo y el viaje guardado se conserva. Se conserva la hora original y la última posición se marca como **antigua** hasta recibir una lectura nueva.
- **Estado guardado v2**: plan (con las caminatas por calles cuando ya llegaron), estado del núcleo, hora original y **solo la última posición válida** (nunca un historial). Se guarda **cada 15 s**, al ocultar la página (`visibilitychange`) y en `pagehide`.

**Recálculo seguro** — `js/viaje.js`, `js/planner.js`
- Pide un recorrido nuevo **sin tocar el viaje actual**, usando el **destino guardado del viaje** (funciona aunque el planificador esté vacío tras reanudar). Si falla (servidor, sin internet, lento, sin opciones, cancelado) el viaje queda **exactamente igual** y se explica.
- Si hay un recorrido utilizable se ofrece con **una sola pregunta**: «Sí, cambiar» / «No, seguir con el actual». Al aceptar, el viaje anterior se respalda y, si el nuevo no arranca, se **recupera el anterior** con su fase y hora.
- «Recalcular desde aquí» se convierte en «Cancelar el cálculo» mientras se calcula; una respuesta tardía no cambia nada.

**Pantalla bloqueada / regreso** — `js/viaje.js`
- La escucha del GPS tiene **generación**: al volver (más de 15 s fuera, o página restaurada de la memoria) se **renueva una sola vez**, la anterior queda invalidada (sus callbacks se ignoran) y no hay escuchas duplicadas. Se vuelve a pedir **Screen Wake Lock**.

**Mapa: salud y recuperación** — `js/mapa/adaptador-google.js`, `js/mapa/adaptador-leaflet.js`, `js/mapa-salud.js`
- El adaptador emite `salud` ante señales observables: teselas que no terminan de cargar (9 s tras mover la cámara, con internet), **pérdida del contexto WebGL**; y recupera (`recuperar('suave')` repinta; `recuperar('reconstruir')` crea un mapa nuevo **con la misma referencia del adaptador**, la cámara, las capas y los controles, liberando los listeners del mapa viejo).
- `mapa-salud.js`: aviso **«Recuperar mapa»**, reintentos **limitados (3)** con espera progresiva (2 s, 5 s, 12 s: repintado, repintado, reconstrucción), sin bucles; al volver internet se intenta una vez; botón manual en «Más» durante el viaje.
- Tres estados **visualmente distintos** y de un solo aviso por tipo: *sin internet* (azul), *sin ubicación actual* (ámbar, con «última posición conocida hace N min») y *mapa incompleto* (rojo). Van **dentro de la tarjeta**, en su fila, y **desaparecen de la vista reducida de un aviso urgente**: nunca tapan una pregunta de subida/bajada.

**Otras esperas y controles** — `js/planner.js`, `js/superficies.js`, `index.html`, `css/viaje.css`
- La opción elegida e «Iniciar viaje» esperan **como máximo 3 s** al detalle de la ruta (se sigue cargando en segundo plano).
- Los botones del planificador se ven **desactivados con el motivo** («Preparando el mapa…») hasta que existan sus manejadores.
- Menú: «Mis viajes y logros», «Pantalla y accesibilidad», «Mala señal o sin internet» y «Explorar recorridos» son plegables.

## 3. Mediciones antes → después

| Medida | Antes | Después |
|---|---|---|
| Alto del menú a 320 × 568 (contenido) | 3 695 px (6,5 pantallas) | 1 845 px (3,2 pantallas; con enlaces de administración visibles en el stub) |
| «Reanudar» sin SDK de Google | viaje no arranca, tarjeta cerrada | arranca; indicaciones visibles; mapa se dibuja cuando exista |
| «Recalcular» con servidor caído / sin internet / sin opciones | viaje terminado y perdido | viaje intacto + mensaje |
| Escuchas de GPS tras 3 bloqueos de pantalla | riesgo de acumular | siempre 1 |
| Recomendación con el detalle de la ruta colgado | no aparece | aparece (tope 3 s) |

## 4. Pruebas y qué simulan

- **Navegador real** (Chromium/Edge vía Playwright), frontend real. **Simulados:** el backend (`e2e/stub_server.py`), **el SDK de Google** (`e2e/harness/google_fake.js`: no dibuja nada), el **GPS** (`e2e/simulador_gps.js`), el bloqueo de pantalla (se fuerza `document.hidden` y se adelanta el reloj), la caída de red (rutas abortadas y `navigator.onLine`) y el contexto WebGL (se dispara el evento). **No se probó WebKit** (no está instalado en este equipo) ni dispositivos físicos.
- Nuevas: `e2e/continuidad414_e2e.py` (19 casos): recálculo (500, sin internet, sin opciones, cancelar, aceptar, rechazar, reanudado con planificador vacío), reanudar sin SDK, hora original y posición antigua, guardado periódico/`pagehide` sin historial, regreso de pantalla bloqueada sin duplicar escuchas, salud del mapa (teselas, reintentos limitados sin bucle, reconstrucción conservando referencia/cámara/capas/listeners, sin internet, WebGL), avisos de conexión que no tapan una pregunta, detalle de ruta colgado y controles sin manejadores.
- Tamaños: 320, 360, 375, 390, 430, 600, 768 y apaisado en `ux414_e2e` (tarjeta, controles, textos); 1280 en el resto.
- Resultados completos de la regresión: ver `docs/UX-MOVIL-4.14.md` §7 y `CHANGELOG.md`.

## 5. Lo que NO se pudo confirmar (y por qué)

1. **La causa exacta de las calles que desaparecen por bloques.** El video muestra que el GPS sigue pero no dice por qué falla el fondo. Hipótesis (sin confirmar): pérdida del contexto WebGL por presión de memoria/GPU en el teléfono, teselas vectoriales que no llegan con red lenta o tras suspender la página, o un error del SDK sin evento público. Las señales y la recuperación implementadas cubren esos casos **por observación**, pero solo un teléfono real con la clave real puede confirmar cuál ocurre y si la recuperación funciona.
2. **El comportamiento real del SDK de Google** con `tilesloaded`/`bounds_changed` en un mapa vectorial con Map ID (el doble de pruebas los emite como lo documenta la API, pero no es Google).
3. **WebKit / Safari de iPhone** (no probado). En iOS el teclado, las barras del navegador, `visibilitychange` y la pausa del GPS pueden diferir.

## 6. Pantalla bloqueada: qué puede una web/PWA y qué exige una app nativa

**Lo que sí hace la web/PWA (y quedó implementado):**
- **Screen Wake Lock** mientras la página está **visible** (Chrome de Android; Safari de iOS 16.4+). Se pide al iniciar y se vuelve a pedir al regresar. **Se pierde** si la persona bloquea el teléfono o cambia de app.
- Al volver: renueva el GPS, marca la posición como antigua, conserva el viaje (guardado cada 15 s y al ocultar la página) y se puede **reanudar** si pasaron menos de 3 h.

**Lo que una PWA NO garantiza (no se afirma lo contrario):**
- **Ubicación continua con la pantalla bloqueada o en segundo plano.** `navigator.geolocation` normalmente **se suspende** cuando la página no está visible; una PWA instalada **no cambia** eso de forma fiable ni en iOS ni en Android.
- **Avisos de bajada con la pantalla bloqueada**: la vibración no existe en iOS, el audio en segundo plano está limitado, y las notificaciones push **no incluyen ubicación** (en iOS solo funcionan en una PWA instalada y requieren permiso).
- **Teléfono apagado**: no hay seguimiento; solo se puede **retomar al encenderlo** (con «Reanudar»).

**Alcance de una implementación nativa (pendiente, no hecha):**
- *Android*: servicio en primer plano de tipo `location` con notificación persistente, `FusedLocationProviderClient`, permiso de ubicación en segundo plano (o servicio en primer plano), geocercas para «próxima parada / bájate», notificaciones de alta prioridad y wake lock parcial.
- *iOS*: `CLLocationManager` con `allowsBackgroundLocationUpdates` y `UIBackgroundModes: location`, permiso «Siempre» (o «Al usar» con indicador), notificaciones locales para los avisos y, opcionalmente, Live Activity.
- *Reutilizable*: la lógica de fases, preguntas y avisos (`viaje-nucleo.js`) es pura y se puede portar; **la interfaz de seguimiento, los permisos, los servicios y las notificaciones habría que construirlos**. Pendiente: toda esa app (p. ej. con Capacitor o nativa).

## 7. Cómo comprobarlo en un iPhone y un Android durante un trayecto real

Requisitos: la app en **HTTPS**, `MAP_PROVIDER=google` con `GOOGLE_MAPS_BROWSER_API_KEY` y `GOOGLE_MAPS_MAP_ID` reales, y los datos del sitio borrados (para traer los archivos nuevos). Abre la consola remota si puedes (Safari → Mac; Chrome → `chrome://inspect`).

1. **Fondo del mapa (lo del video).** Inicia un viaje y recorre un trayecto con datos móviles. Observa si las calles desaparecen. Si pasa: ¿aparece el aviso **«El mapa se ve incompleto»** con el botón **«Recuperar mapa»**? Tócalo: ¿vuelven las calles sin perder el punto azul, la línea ni la tarjeta? Anota la hora y, si puedes, el contenido de `Spevb.MapaSalud.registro()` y los errores de consola.
2. **Corte de internet:** activa el modo avión 20 s en pleno viaje. Debe salir **«Sin internet»** (azul), el punto y las indicaciones siguen. Al volver la red, el fondo se intenta recuperar solo.
3. **Sin ubicación actual:** bloquea el teléfono 1 min y vuelve. Debe decir **«Sin ubicación actual · última posición conocida hace…»** (ámbar) hasta recibir una lectura nueva; comprueba que el GPS **sí vuelve**. (En iOS es posible que el GPS se pause con la pantalla bloqueada: es el límite de la web.)
4. **Reanudar:** en pleno viaje recarga la página (o cierra el navegador y ábrelo). Debe ofrecer «Reanudar», conservar la hora de inicio y las indicaciones aunque el mapa tarde.
5. **Recalcular:** sal de la ruta, toca «Más» → «Recalcular desde aquí» con y sin internet. Sin internet debe decir que no pudo y **conservar el viaje**. Con internet debe preguntar «¿Quieres cambiar a él?».
6. **Aviso de bajada con otros avisos:** con el modo avión activo, comprueba que la pregunta/aviso de **bajada se ve completa** y no queda tapada.
7. Repite en 320 px o con letra «muy grande» si tu teléfono lo permite.

Envíame: modelo y versión del sistema, navegador, si el fallo se repitió, la hora, y capturas o video corto.

## 8. Pendiente

- Confirmar en un teléfono real la **causa** de la pérdida de calles y que «Recuperar mapa» la resuelve (§5, §7).
- Probar **WebKit** (Safari) y un Android físico; no hay WebKit en este equipo.
- **Seguimiento con la pantalla bloqueada**: requiere una app nativa (§6).
- Los avisos de conexión y de mapa aparecen en la tarjeta del viaje; **fuera del viaje** se muestran en una barra pequeña arriba. No se midió su aspecto en todos los tamaños.
- El menú sigue siendo largo para administradores (enlaces de administración, «Rutas cerca del origen» y la tarjeta de ruta).
