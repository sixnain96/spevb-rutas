# CONTINUIDAD DEL PROYECTO SEVB / SPEVB (léelo primero)

> Archivo para retomar el trabajo sin perder contexto. Última actualización: **v4.13.5** (búsqueda directa a «Iniciar viaje» sin bajar el panel, servidor más rápido, bus de demostración que se pausa en el viaje, botones táctiles de 44 px; antes 4.11.3: «Iniciar viaje» como botón principal bajo «Buscar rutas»; joystick con velocidad ajustable, piloto automático, teletransporte con 3 clics y verificación del GPS del teléfono; antes 4.11.2: joystick de GPS para demostrar el modo viaje con el emulador y corrección de la velocidad 0 que informa Android; antes 4.11.1: tarjeta del modo viaje que se mueve, se minimiza y se oculta; antes 4.11.0 «Modo viaje»: seguimiento GPS en tiempo real con detección de subida y bajada, preguntas, alertas y reanudación; antes 4.10.3: corrección de estados obsoletos entre planificador y explorador, historial con los códigos reales, `?ruta=` limpio, service worker ante 5xx, scripts de Blender portables; antes 4.10.2: sección «Mejores opciones», lista de resultados estable en el celular y aviso de elegir punto junto a los controles del mapa; antes 4.10.1: botones para borrar A, B o ambos; 4.10.0: editor, red vial para buses, auditoría y puentes editables). Cuando algo cambie, **actualiza este archivo y `CHANGELOG.md`**.
> Para abrir un chat nuevo: pega el texto de `docs/PROMPT-NUEVO-CHAT.md` y adjunta este archivo.
> **Mapa visual y trampas ya conocidas: `docs/GRAFO-DEL-PROYECTO.md`** (arquitectura, cómo se elige dónde subir/bajar, direcciones, módulos JS, migraciones V1–V11, lo que no se debe repetir).
> No contiene claves. El `.env` real es privado y nunca se pega aquí, en chats ni en repositorios.

> **Trabajo en curso (sin publicar, rama `ux-movil-solo-google`): UX móvil 4.14.** Coordinador de superficies (`js/superficies.js`), «Volver al inicio» (`js/inicio.js`), búsqueda **solo Google** y A/B fiable. Léelo en `docs/UX-MOVIL-4.14.md` antes de tocar menús, tarjeta del viaje, planificador o búsqueda. Regla nueva: ningún módulo abre/cierra paneles por su cuenta; se pasa por `Spevb.Superficies` (`puedeAbrir(causa)`, `menu.abrir/cerrar`, `viajeIniciado/Terminado`) y `Spevb.Panel`.

---

## 0. REGLAS INNEGOCIABLES (del dueño del proyecto)

1. **No modificar funcionalidades existentes cuando se pide un cambio visual.** Cambio de logos, colores, tipografías, espacios o iconos = SOLO imágenes, referencias, metadatos de marca y CSS necesario. No tocar JavaScript de funciones, Java, APIs, base de datos, formularios, autenticación, navegación, eventos, cálculos, rutas, animaciones ni controles del mapa. Si se pide solo un cambio, hacer solo ese cambio.
2. **No perder funcionalidad nunca** («solo agregar, no dañar el sistema»). Antes de entregar: correr las pruebas (sección 9) y revisar el diff.
3. **Prioridad máxima histórica:** las líneas punteadas de caminata deben seguir **calles y zonas caminables reales** (como Google Maps), nunca cruzar manzanas, cerros o caños. No degradar esto.
4. El **modelo de bus 2D es el predeterminado** (el 3D es opcional). No cambiar los buses por mascota ni marcadores.
5. Experiencia **100 % responsive** (celular, tablet, computador, apaisado). Probar siempre en celular.
6. No hacer commits ni publicar sin que se pida. No agregar pantallas, banners, modales ni animaciones cuando solo se pide identidad visual.
7. Conservar los cambios locales que ya existan en el proyecto del usuario (su carpeta de IntelliJ). Trabajar sobre la última versión que entregue o suba el usuario.
8. Seguridad: nunca incluir `target/`, `.idea/`, `data/`, `backups/` ni el `.env` en ZIPs, salvo que el usuario pida expresamente el `.env` (es su propio archivo). No usar `docker compose down -v` (borra la base).
9. El usuario habla español (Colombia): responder siempre en español, claro y con comandos de PowerShell (usa Windows + Docker Desktop + IntelliJ).

---

## 1. QUÉ ES

Planificador web de rutas de buses de **Villavicencio (Meta, Colombia)**: dado un punto A y un B, encuentra la ruta (o dos con un transbordo), dónde subir y bajar, y la caminata por calles; anima el recorrido del bus. Sirve en celular, tablet y computador, funciona sin conexión y tiene cuentas de usuario, administración de rutas y paraderos.

Nombre: la interfaz dice **«SPEVB»**; el dueño indica que el nombre oficial es **SEVB** (las imágenes de marca originales traen «SEVB»/«SEBV», ojo: el logo vertical dice «SEBV» y es incorrecto). Aún no se unificaron los textos; **no renombrar sin que lo pidan**.

Color de marca: verde **`#176650`** (`--brand`). Todos los logos/íconos se hicieron en ese verde.

---

## 2. ARQUITECTURA

**Backend:** Java 21, Spring Boot 4.1.1 (Jackson 3: paquete `tools.jackson`), JDBC (`JdbcTemplate`, sin JPA), Maven. Sin Spring Security (sesiones propias).
**Base de datos:** PostgreSQL 17 + PostGIS (imagen `postgis/postgis:17-3.5`). El esquema lo gestiona **Flyway V1–V10** (`src/main/resources/db/migration`, idempotentes: una base creada antes con `schema.sql` se adopta sin perder datos). `schema.sql` y `data.sql` **ya no controlan el arranque** (copias históricas en `src/test/resources/legacy/`). Los datos demostrativos (`db/demo`) solo se cargan con `DEMO_DATA=true`. Ver `docs/MIGRACIONES.md`.
**Frontend:** HTML + JavaScript plano (sin framework). Desde la 4.13 el mapa pasa por una **capa propia** (`js/mapa/`, ver docs/MAPA-CAPA.md; proveedor `MAP_PROVIDER=leaflet|google`, ningún módulo usa `L.*` ni `google.maps.*`). Con Leaflet: Leaflet 1.9 + MapLibre GL (mapa vectorial OpenFreeMap) vía `leaflet-maplibre-gl`; archivos en `src/main/resources/static/` (se sirven desde el JAR). PWA con service worker.
**Mapa base:** único «Moderno» (vectorial OpenFreeMap) para todos, sin selector de capas (v4.8.1); ráster Esri solo como respaldo si el vectorial tarda o no hay WebGL.
**Red peatonal propia:** grafo en memoria (arreglos CSR, A*, Dijkstra uno-a-muchos) construido con datos de OpenStreetMap (Overpass/API OSM, semilla incluida); se guarda en `data/red.bin.gz` (carpeta `data/`, no va en ZIPs). Los «pasos peatonales» propios (PASO/BLOQUEO) corrigen la red.

### Estructura relevante
```
SEVB-4.13.1/   (carpeta de trabajo; el nombre de la carpeta puede variar)
├─ CONTINUIDAD.md        ← este archivo
├─ CHANGELOG.md, README.md, LEEME_PRIMERO.txt, ESTANDARES.md
├─ pom.xml, Dockerfile, docker-compose.yml (solo BD), compose.app.yml (app+BD), compose.prod.yml (producción: Caddy+app+BD)
├─ start.ps1  (arranque local: lee .env, mvn spring-boot:run; admite -NoDatabase)
├─ backup.ps1, restore.ps1, diagnostico.ps1, deploy/{Caddyfile,backup.sh,restore.sh}
├─ branding-originales/  (5 imágenes originales, NO tocar)
├─ docs/                 (documentación por tema + docs/pruebas/*.py + docs/marca/preparar_imagenes.py)
└─ src/
   ├─ main/java/co/edu/usta/spevb/{config,controller,dto,exception,repository,service}
   ├─ main/resources/{application.properties, db/migration/V1..V8, db/demo/, static/}
   └─ test/java/...      (≈189 tests)
```

### Servicios clave (Java)
- `ViajeService` (planificador, optimizador de subida/bajada, variantes MENOS_CAMINATA / MAS_RAPIDA, caché de planes 90 s, `invalidarCache()`), `AbordajeService` (optimizador con ventaja de 25 m para paraderos), `TransbordoService` (un transbordo, espera estimada 8 min, pares por rejilla ≤ 250 m), `CaminataService` + `RedPeatonal`/`RedPeatonalService` (caminata por calles), `RutaService`, `PasoPeatonalService`.
- `ParaderoService` (paraderos de cada ruta, PUT atómico) y `ParaderoBusService` (paraderos de bus independientes de las rutas).
- `CuentaService`, `ClaveHash` (PBKDF2 600 000 it.), `DatosUsuario` (validación de datos sincronizados), `GoogleTokenVerifier` (verifica ID token de Google con JWKS, sin librerías).
- Filtros: `EditorSecurityFilter` (CSP, cabeceras, protege `/api/editor/**` con `X-Editor-Token` **o** sesión de administrador), `LimiteGeneralFilter` (límite por IP; se apaga con `LIMITE_SOLICITUDES=false`), `LimitadorSolicitudes`.
- `GlobalExceptionHandler`: errores JSON uniformes.

### Frontend clave (`static/js`)
`common.js` (API `Spevb.api`, toasts, mapa, `fitMap`), `app.js` (explorador, lista de rutas, estrellas favoritas), `planner.js` (planificador, líneas de caminata SVG animadas, tarjetas, transbordo, rótulos), `bus.js` + `css/bus3d.css` (bus 2D/3D, panel con botón «–»), `gps.js` (GPS: permisos, filtro de Kalman, seguimiento), `sheet.js` (panel inferior móvil), `offline.js` (mapa guardado, recientes), `cuenta.js` (cuentas + `Spevb.Datos` sincronizado), `espacio.js` (historial/guardados), `extras.js` (compartir, favoritos, avisar al bajar, reportar), `ajustes.js` (daltónico, panel del bus), `paraderos-publicos.js` (capa de paraderos), `admin.js`, `editor.js`, `paraderos-editor.js`, `paraderos-admin.js`, `pasos.js`, `places.js`, `reportes.js`, `pwa.js`, `ui.js`.
Páginas: `index.html` (principal), `editor.html`, `paraderos.html`, `puentes.html`, `pasos.html`, `lugares.html`, `reportes.html`.
Service worker `sw.js` (versión actual **spevb-v4.13.5** (se filtra desde `pom.xml`; clave de caché canónica `/`, sin parámetros de viaje)): app en caché (shell; red primero 2,5 s, v4.8.2), rutas red-primero con 6 s, teselas/estilos del mapa guardados, `/api/*` (salvo rutas) nunca interceptado.

---

## 3. FUNCIONALIDADES EXISTENTES (no romper)

**Planificación**
- Rutas directas A→B ordenadas por menor caminata; variante «más rápida» si ahorra tiempo; sentido contrario aparte.
- **Punto de subida/bajada óptimo** por red de calles (muestreo cada 15 m, caminata real, descuento de 25 m para paraderos).
- **Transbordo** (2 buses) cuando no hay directa buena o ahorra tiempo/caminata; cuadro «CAMBIO DE BUS», insignias ①②, rótulos «Sube aquí / Bájate aquí».
- Caminata **por calles** (líneas punteadas con animación sutil en el sentido del trayecto, pausada mientras se arrastra el mapa).
- (Retirado en 4.9.4: el modo «solo revisadas»; el backend conserva `soloValidadas` por compatibilidad.) Rutas cercanas al origen, búsqueda de direcciones (catastro, Nominatim, ArcGIS, catálogo local), elegir en el mapa (pin central + «Confirmar»), intercambiar A/B.
- **GPS**: «mi ubicación», seguimiento, precisión, manejo de permisos, requiere HTTPS o localhost.
- Bus animado 2D (predeterminado) y 3D, controles (pausa, velocidad, reiniciar, 3D, «Seguir bus», barra de avance); **ocultables** (botón «–» / interruptor del menú; en celular empiezan ocultos).
- **Accesibilidad:** colores para daltónicos (azul/naranja, 2.º tramo discontinuo), guía de colores.
- Mapa: créditos compactos «Leaflet ⓘ» (los demás créditos al tocar; no eliminar por licencias), respaldo ráster solo si el vectorial tarda > 1,5 s, resolución limitada a 2×.

**Uso sin conexión:** menú «Guardar mapa de Villavicencio» (≈ 5–6 MB), rutas guardadas, «Recientes» (últimos 6 viajes con resultado), avisos de conexión, instalación como app.

**Cuentas y datos personales (v4.4/4.5)**
- Registro/entrada con correo+contraseña (≥ 8 caracteres) **o Google** (botón «Continuar con Google»), cookie HttpOnly `spevb_sesion`, límites de intentos, defensa CSRF.
- Datos sincronizados PC↔celular (`usuario_datos`, JSONB): **lugares, rutas favoritas (★), viajes guardados, historial**. Sin cuenta funcionan en el dispositivo (localStorage). Al entrar se **unen** local y servidor; al salir se borra lo local del dispositivo.
- Ajustes de cuenta: cambiar/crear contraseña, cerrar todas las sesiones, eliminar cuenta.
- Cuenta de Google vinculada a un correo con contraseña previa: se anula la contraseña vieja, se cierran sesiones y se descartan nombre/datos (anti pre-secuestro).

**Extras (v4.1):** compartir viaje (`/?a=lat,lng&b=lat,lng&an=&bn=`), «Mis lugares», «Avisarme al bajar» (GPS: 600/220/60 m; vibración, sonido, wake lock), reportar problema (`POST /api/reportes`, lista en `/reportes.html`), botón instalar app, límite de solicitudes por IP.

**Administración**
- Administrador = correo en `ADMIN_CORREOS` **verificado con Google** (nunca por contraseña). Ve enlaces Editor / Paraderos de bus / Pasos / Lugares / Reportes; no escribe la clave. Sin `ADMIN_CORREOS`, los enlaces se ven para todos (como antes). Los demás siguen con `EDITOR_TOKEN` (requiere `EDITOR_ENABLED=true`).
- **Editor de recorridos** (dibujar, versiones/historial, importar/exportar GeoJSON) + sección 04 **paraderos por ruta** (clic sobre la línea, ajuste automático, orden por posición, máx. 200, ≤ 30 m de la línea).
- **Paraderos de bus del sistema** (`/paraderos.html`, v4.7): independientes de las rutas; clic en el mapa (zoom ≥ 15), editar/mover/desactivar/eliminar, búsqueda sin tildes, importar CSV/GeoJSON (≤ 500), muestra «Sirve a: R001 (3 m)». Capa pública en el mapa (zoom ≥ 14, interruptor «Paraderos»).
- Pasos peatonales (PASO/BLOQUEO), **puentes peatonales** (tipo PUENTE: `/puentes.html`, capa pública `/api/puentes`, `docs/PUENTES.md`), catálogo de lugares/direcciones.
- **UX móvil (v4.9):** el panel baja solo tras buscar solo si se activa el ajuste `panelBajar` (apagado por defecto desde la 4.12.0; antes `panelAuto`), botón «Ver rutas»; «Seguir bus» NO bloquea el mapa: cualquier arrastre manual (ratón, toque o lápiz) cancela el seguimiento y «Soltar» también

**Algoritmo de paraderos (regla del dueño):** un paradero cuenta si la ruta pasa a **≤ 30 m**; se prefiere si no obliga a caminar más de **≈ 25 m extra** que el mejor punto libre (a 5–12 m del óptimo gana siempre); si está más lejos, se usa el punto libre que menos camina. Igual al subir, bajar y transbordar (transbordo ≈ 24 m de ventaja).

**Identidad visual (v4.7.1/4.7.2):** mascota capibara + símbolos en verde `#176650`. Ver `docs/MARCA.md` y `docs/marca/preparar_imagenes.py`. Ubicaciones: cuadro de marca en la cabecera de las 6 páginas (ícono de app), favicon (`/favicon.ico`, `icons/favicon-32/48`), manifiesto e íconos (`icons/icon-192/512/maskable`, `apple-touch-icon`), cabeza en el formulario de entrar y pie de páginas admin, mascota + «SEVB» (texto HTML) al final del menú. El logo horizontal blanco está preparado pero sin uso (no hay superficies oscuras). El logo vertical original (dice «SEBV») NO se usa.

---

## 4. CONFIGURACIÓN (`.env`, privado)

Variables: `DB_PASSWORD` (debe coincidir con la contraseña de la base ya creada), `EDITOR_ENABLED`, `EDITOR_TOKEN` (≥ 32 caracteres), `GEOCODER_*`, `ARCGIS_API_KEY`, `CITY_*`, `GOOGLE_CLIENT_ID` (público: `882815845111-0qeho6d3f9m745gugasmitggr6u8dlt9.apps.googleusercontent.com`), `ADMIN_CORREOS` (el usuario usa `<correo del administrador>`), `SERVER_ADDRESS` (0.0.0.0 solo para ngrok en Docker), `LIMITE_SOLICITUDES`, `JAVA_OPTS`, `DOMINIO` (producción), `GOOGLE_JWKS_URL` (solo pruebas).
- **Seguridad pendiente de recordar al usuario:** las claves del ZIP original (DB, EDITOR_TOKEN, ARCGIS) se consideran expuestas → generar nuevas antes de publicar; el `client_secret` de Google que subió no se usa (recomendado rotarlo y borrar el JSON).
- `EDITOR_ENABLED=false` mientras haya un túnel abierto (el editor no funciona con `false`, ni siquiera para el administrador).

---

## 5. CÓMO CORRER (Windows / PowerShell)

```powershell
cd <carpeta del proyecto, la de IntelliJ>          # debe contener start.ps1, .env, pom.xml
# Base de datos: con Docker Desktop (opcional) `docker compose up -d`; sin Docker usa tu PostgreSQL/PostGIS local y define DB_URL/DB_USER/DB_PASSWORD en .env
powershell -ExecutionPolicy Bypass -File .\start.ps1 -NoDatabase
# http://localhost:8080  (Ctrl+Shift+R para limpiar caché)
```
Alternativa sin Java/Maven: `docker compose -f compose.app.yml up -d --build`.
**Túneles** (en otra ventana): `cloudflared tunnel --url http://localhost:8080` (la dirección `trycloudflare.com` cambia; Google puede no aceptarla como origen). **ngrok** directo en Windows está bloqueado por «reputación binaria»: usar `docker run --rm -it -e NGROK_AUTHTOKEN=… ngrok/ngrok:latest http [--url=dominio.ngrok-free.app] host.docker.internal:8080` (requiere `SERVER_ADDRESS=0.0.0.0`).
Google: cada dirección `https://…` nueva debe agregarse en Google Cloud → cliente «SPEVB web» → Orígenes autorizados de JavaScript (ver `docs/GOOGLE.md`; proyecto `spevb-513002`, pantalla de consentimiento «En producción»).
Problema habitual del usuario: carpetas incompletas / sin `.env` (carpeta sincronizada en la nube, ZIP mal extraído). Su carpeta buena es la abierta en IntelliJ (tiene `.env`, `data/`, `target/`).
Producción 24/7 y copias: `docs/DESPLIEGUE.md` (VPS + Caddy HTTPS). **No usar `docker compose down -v`.**

---

## 6. HISTORIAL DE VERSIONES (resumen; detalle en `CHANGELOG.md`)

V3.x base → red peatonal propia y caminata por calles → punto óptimo de subida → GPS robusto → pin central (arreglo de «Elegir en el mapa» en móvil) → buses 2D/3D nuevos → líneas animadas + UX móvil → transbordos + arreglo del mapa «buggueado» al buscar → **4.0** sin conexión → **4.1** compartir/favoritos/avisos/reportes/límite IP/despliegue → **4.2** mapa rápido y créditos compactos → **4.4** cuentas, historial, guardados, favoritos → **4.5** Google → **4.6** cambio de bus visible, daltónicos, panel del bus ocultable, administradores, editor de paraderos por ruta → **4.7** paraderos de bus independientes + algoritmo con paraderos + prueba masiva → **4.7.1/4.7.2** identidad visual (verde `#176650`) → **4.8.0–4.8.2** mapa único/panel ocultable → **4.9.0** puentes peatonales (`docs/PUENTES.md`), panel que baja solo, «Seguir bus» fija el mapa; mapa único «Moderno» sin selector de capas; panel del planificador ocultable (`panel-ocultable.js/css`), logos fijos, aviso de Google en túneles.

---

## 7. ESTADO DE PRUEBAS (última ejecución completa)

`mvn clean verify "-Dsevb.sinDocker=true"`: 336 pruebas Java, 4 omitidas sin Docker (3 de `MigracionesPostgisTest` y `DireccionParserCorpusTest`) y 0 fallos. Navegador (sin Docker, `python e2e/run_e2e.py`): 20 de interfaz + 13 de resultados + 16 de estados obsoletos + 21 de lógica del modo viaje + 25 de interfaz del modo viaje + 4 de GPX + 17 del joystick + 11 de borrado de A/B + 16 del editor + 10 del service worker = 153. SBOM + OSV: 0 vulnerabilidades conocidas.

---

## 8. PENDIENTES / IDEAS

**Del usuario / negocio**
- Reportar casos reales donde el algoritmo falla (A, B y ruta); con los datos de demostración no se reproducen. Pedir un ejemplo concreto.
- Cargar las **rutas reales** y **rutas de vuelta** (hay rutas de demostración R001–R004 y PRUEBA-*); lista de paraderos oficiales (CSV/GeoJSON) para importar.
- Publicar 24/7: contratar VPS (≈ 5–12 USD/mes, región EE. UU./Miami) + dominio; seguir `docs/DESPLIEGUE.md`; claves nuevas; `EDITOR_ENABLED=false`; copias automáticas.
- **Google `origin_mismatch` con otras cuentas:** causa = dirección de túnel cambiante; solución = dirección fija registrada (ngrok dominio gratis o VPS+dominio). Ver `docs/GOOGLE.md`.
- Unificar el nombre (SPEVB ↔ SEVB) cuando el dueño lo confirme; logo vertical con «SEVB» correcto (el original dice «SEBV»).
- Recuperar contraseña / verificar correo por email (requiere SMTP; no existe).
- Política de privacidad (el historial guarda origen/destino), horarios/tarifa (no hay datos; la tarifa se puede mostrar si la dan).

**Técnicos conocidos**
- La planificación sin rutas cerca muestra opciones de > 3 km de caminata (por diseño, siempre muestra las más cercanas).
- `mapa_e2e` (tablet) y otras pruebas pueden dar falsos fallos si el equipo está ocupado (el vectorial tarda > 1,5 s y entra el ráster); repetir sola antes de dudar.
- La prueba de Google requiere esperar ~60 s entre ejecuciones (el servidor limita descargas de claves JWKS).
- El editor de paraderos por ruta y el nuevo de paraderos de bus conviven; los paraderos de ruta a > 30 m de la línea ya no se usan para subir (siguen visibles).
- Posibles mejoras: importar paraderos desde OSM (Overpass) en servidor, favicon simplificado para 16 px, logo blanco si aparece una superficie oscura, tests de integración con base real.

---

## 9. CÓMO PROBAR ANTES DE ENTREGAR

1. `mvn -q -B -DskipTests package` (compila main y tests); `mvn -B clean verify` (334 pruebas, ver `docs/PRUEBAS.md`).
2. Navegador sin Docker: `python e2e/run_e2e.py` (servidor stub en `:18080`). Integración opcional con una base PostGIS local dedicada (`_e2e`/`_test`): variables `SEVB_E2E_DB_*`.
3. Referencia histórica: `docs/pruebas/*_e2e.py` (ver cabecera de cada archivo para variables: `SEVB_URL`, `EDITOR_TOKEN`, `CHROME_PATH`). Rutas de demostración de transbordo: `docs/pruebas/transbordo_demo.sql`.
4. Comparar el diff con la entrega anterior (`diff -rq`), confirmar que **solo cambió lo pedido**.
5. Capturas en celular (390×844) y escritorio (1280×800) cuando haya cambios visuales.
6. Empaquetar con `scripts/package-release.ps1` (ZIP sin `.env`, `target/`, `.idea/`, `data/`, respaldos ni `design/*/backup`; se inspecciona solo).

---

## 10. TRAMPAS CONOCIDAS (para no repetir errores)

- En Leaflet, `Layer` usa `this._on` como método: no sobrescribirlo (ya pasó).
- Dentro de un `<main>` con `display:grid` no insertar elementos nuevos (rompe el diseño): ponerlos antes del `main`.
- La caché de planes (90 s) puede devolver resultados viejos en pruebas: variar coordenadas o invalidar.
- `pkill`/`pgrep -f` con texto que aparece en el propio comando mata el shell: usar `pgrep -x java` o patrones anclados (`^bash …`).
- `localStorage`: claves `spevb-favoritos`, `spevb-rutas-fav`, `spevb-viajes-guardados`, `spevb-historial`, `spevb-recientes`, `spevb-ajustes`, `spevb-usuario`, `spevb-basemap`, etc. No renombrarlas (rompe datos guardados).
- Selector `.fav-row` lo comparten favoritos e historial: acotar con `#favList`.
- Los originales de marca tienen el fondo de cuadritos dentro de los píxeles: siempre limpiar con `docs/marca/preparar_imagenes.py`, nunca publicar tal cual ni envolver PNG en SVG.

---

## 11. ESTADO DESPUÉS DE LAS MEJORAS 4.14 (lo último; detalle en `CHANGELOG.md`, entrada 4.13.1)

- **Dónde se trabaja y se arranca:** SIEMPRE desde `<carpeta-del-proyecto>` (la carpeta `SEVB-V3.4` es vieja: no conoce la migración V11 y Flyway no la deja arrancar con esta base).
- **Base de datos:** ya tiene V11 (`rutas.nombre_popular`, aditiva). Antes de actualizar otra instalación: `backup.ps1`.
- **Google (opcional):** la clave `GOOGLE_MAPS_API_KEY` está en el `.env` del dueño (privado; nunca se imprime). Sin la clave todo sigue igual. Pendiente: confirmar las condiciones de Google sobre resultados en un mapa que no sea de Google (`docs/GOOGLE-MAPS.md`) y revisar el cobro de verificación de 98 000 COP de la prueba gratis (aparece como «pago manual pendiente», saldo 0).
- **Pendiente del dueño:** poner el **nombre popular** de cada ruta en el editor (Catama, Kirpas…); mientras tanto la app muestra el código.
- **Lo hecho:** punto de subida/bajada por calles reales (varias calles por punto), nombre popular, mapa de noche automático, letra grande, línea de tiempo, Casa/Trabajo en el menú, logros, resumen semanal, «Tu viaje de siempre», bienvenida de 3 pantallas, ayuda «?», viaje de práctica, lenguaje simple en el modo viaje (la voz se QUITÓ por pedido del profesor), pestañita «Ahora escribe B», tutorial animado, iconos propios sin emojis, controles del bus en círculo, Google opcional para direcciones, un solo menú de sugerencias, nuevo chigüiro.
- **Ideas pendientes** (lista de 31 ideas acordada con el dueño; ya hechas: 1, 3, 4, 6, 9, 15, 16, 17, 18, 21): modo «Fácil» de una pregunta por pantalla, compartir con un familiar, avisos de reportes de la comunidad, prueba de usabilidad con 5 personas reales, horarios y fotos de paraderos (necesitan datos reales).
- **Reglas del profesor (nuevas):** NADA de emojis (todo icono es propio, SVG, en `js/iconos.js`) y NADA de voz.
- **Versión:** sigue en 4.13.1 (pom). Subir a 4.14.0 es un paso aparte (hay una prueba que exige que pom, README, service worker y manifest coincidan).
- **Rendimiento (6-nov-2026, detalle en `docs/RENDIMIENTO.md`):** un viaje en frío bajó de ≈ 535 ms a ≈ 121 ms (mediana) con resultados idénticos. Piezas nuevas que hay que RESPETAR: `RedPeatonal.ajustarVariosGuardado` (caché ligada a la red inmutable; usarla solo con muestras fijas de rutas, vía `distanciasDeMuestras`), las rejillas de muestras de `ViajeRepository` (huella + `invalidarMuestras()`; si se añade una tabla que cambie las muestras, súmala a `huella()`), y `RedPeatonal.Monticulo`; los tramos del bus se piden juntos con `ViajeRepository.tramosEntre` (las pruebas de `ViajeServiceTest`/`TransbordoServiceTest` lo resuelven sobre `tramoEntre`). Para medir: copia desechable de la base (`pg_dump` de solo lectura a `spevb_perf`), `SPEVB_LIMITE_HABILITADO=false`, `127.0.0.1` (no `localhost`) y `e2e/rendimiento/`.
- **Tutorial 4.15:** `guia.js` + `guia.css`. Modos de `#bienvenida`: `data-modo="bienvenida"|"leccion"`. La práctica se prueba con `Spevb.Guia.practica({ejemplo, pausa:60})` (pausa ≤ 200 = automática). `document.body.dataset.sinRegistro` evita historial/hábitos durante la práctica y su restauración.
- **Pruebas (6-nov-2026):** `mvn clean verify "-Dsevb.sinDocker=true"` (en una copia limpia): 370 pruebas, 0 fallos (9 omitidas: 8 de PostGIS que piden Docker —con Docker pasan las 8— y 1 del corpus de direcciones); `python e2e/js_syntax.py`: 43 archivos sin error; navegador `python e2e/run_e2e.py`: 13 grupos, 203 pruebas, 0 fallos (corrida anterior a agrupar `tramosEntre`; ese cambio es solo Java y el navegador de pruebas usa `stub_server.py`, no el servidor Java). Para correr `mvn clean` sin borrar el `target/` de una app en marcha, hacerlo en una copia de la carpeta.
