"use strict";
/* Service worker de SEVB: la app funciona con mala señal o sin conexión.
   · App (HTML, CSS, JS y los archivos del proveedor de mapa elegido: Leaflet+MapLibre, o el adaptador de Google): se guarda al instalar; red primero (2,5 s) y, si no hay señal, la copia guardada.
       La clave de caché es siempre canónica (origen + ruta, SIN parámetros): «/?origen=…&destino=…» se guarda como «/», de modo que
       los viajes compartidos nunca dejan coordenadas ni nombres en Cache Storage ni duplican el HTML. La URL visible no cambia.
   · Rutas (/api/rutas y /api/rutas/{id}): red primero (con límite de espera) y, si falla o tarda, la última copia guardada.
   · Mapa: las teselas, estilos, íconos y fuentes se sirven primero de lo guardado y solo si no están se piden a la red.
       - MAPA   = paquete «Guardar mapa» (fijo, lo descarga la persona desde el menú).
       - TESELAS = lo que se fue viendo (se poda al llegar al máximo).
   · El cálculo de viajes (/api/viajes, /api/caminata, direcciones) nunca se intercepta: lo guarda la página como «recientes». */
/* «-mapa2» (CACHE_APP): la capa de mapa con adaptadores (Leaflet o Google) reemplazó a los scripts anteriores. Al activarse este worker se borran las cachés de app de
   versiones previas (solo esas: el mapa guardado, las teselas y las copias de rutas conservan sus nombres y no se tocan), así ningún worker viejo
   sigue sirviendo scripts incompatibles. Volver a Leaflet NO requiere cambiar esto: el proveedor se lee de /api/config/mapa en cada carga. */
const VERSION = "spevb-v@project.version@";
const CACHE_APP = VERSION + "-mapa2";
const MAPA = "spevb-mapa-v1";
const TESELAS = "spevb-teselas-v1";
const DATOS = "spevb-datos-v1";
const MAX_TESELAS = 1500;
const MAX_APP = 120;                 // tope de entradas de la caché de la app (el shell está protegido de la poda)
const MAX_DATOS = 200;               // tope de copias de /api/rutas
const ESPERA_RUTAS_MS = 6000;       // con mala señal: pasado este tiempo se usa la copia guardada
const ESPERA_ESTILO_MS = 3500;
const ESPERA_APP_MS = 2500;
const TIPOS_APP = /^(text\/(html|css|javascript|plain)|application\/(javascript|json|manifest\+json)|image\/|font\/|application\/font)/i;
const HOSTS_MAPA = new Set(["tiles.openfreemap.org", "server.arcgisonline.com", "tile.openstreetmap.org"]);

const SHELL = ["/", "/css/motion.css", "/css/styles.css", "/css/home.css", "/css/mapa.css", "/css/bus3d.css", "/css/panel-ocultable.css", "/css/viaje.css", "/css/guia.css",
    "/js/mapa/geo.js", "/js/mapa/nucleo.js",
    "/js/motion.js", "/js/common.js", "/js/modales.js", "/js/carga.js", "/js/superficies.js", "/js/inicio.js", "/js/iconos.js", "/js/rutas-nombres.js", "/js/offline.js", "/js/extras.js", "/js/paraderos-publicos.js", "/js/puentes-publicos.js", "/js/ajustes.js", "/js/confort.js", "/js/cuenta.js", "/js/espacio.js", "/js/atajos.js", "/js/logros.js", "/js/siempre.js", "/js/guia.js", "/js/siguiente.js", "/js/gps.js", "/js/viaje-nucleo.js", "/js/viaje.js", "/js/bus.js", "/js/app.js", "/js/planner.js", "/js/ui.js", "/js/sheet.js", "/js/panel-ocultable.js", "/js/seguir-bus.js", "/js/pwa.js",
    "/manifest.webmanifest", "/icons/icon-192.png", "/icons/icon-512.png",
    "/favicon.ico", "/icons/favicon-32.png", "/img/marca/sevb-icono-app-128.png", "/img/marca/sevb-cabeza-96.png", "/img/marca/sevb-mascota.png", "/img/marca/sevb-chiguiro-192.png", "/img/marca/sevb-chiguiro-360.png",
    "/img/bus/bus_32_base.webp", "/img/bus/bus_32_mask.webp"];

/* Archivos del proveedor de mapa elegido (se lee de /api/config/mapa): solo se guardan los del proveedor en uso, así en modo Google no se descargan
   Leaflet ni MapLibre y en modo Leaflet no se guarda nada de Google. Las bibliotecas de Google NUNCA se guardan: el SDK gestiona sus recursos. */
const POR_PROVEEDOR = {
    leaflet: ["/js/mapa/adaptador-leaflet.js", "/vendor/leaflet/leaflet.css", "/vendor/leaflet/leaflet.js",
        "/vendor/leaflet/images/marker-icon.png", "/vendor/leaflet/images/marker-icon-2x.png", "/vendor/leaflet/images/marker-shadow.png",
        "/vendor/leaflet/images/layers.png", "/vendor/leaflet/images/layers-2x.png",
        "/vendor/maplibre/maplibre-gl.css", "/vendor/maplibre/maplibre-gl-csp.js", "/vendor/maplibre/maplibre-gl-csp-worker.js", "/vendor/maplibre/leaflet-maplibre-gl.js"],
    google: ["/js/mapa/adaptador-google.js"],
};
async function archivosDelProveedor() {
    try {
        const r = await fetch("/api/config/mapa", { cache: "no-store" });
        const c = await r.json();
        return POR_PROVEEDOR[c.proveedor] || POR_PROVEEDOR.leaflet;
    } catch (e) { return POR_PROVEEDOR.leaflet; }
}

self.addEventListener("install", (event) => {
    // Un archivo que falle no debe impedir la instalación: se guarda todo lo que se pueda.
    event.waitUntil(caches.open(CACHE_APP).then(async (cache) => {
        const extra = await archivosDelProveedor();
        return Promise.allSettled(SHELL.concat(extra).map((u) => cache.add(u)));
    }).then(() => self.skipWaiting()));
});

self.addEventListener("activate", (event) => {
    const vigentes = new Set([CACHE_APP, MAPA, TESELAS, DATOS]);
    event.waitUntil(caches.keys().then((keys) => Promise.all(keys.filter((k) => !vigentes.has(k)).map((k) => caches.delete(k))))
        .then(limpiarClavesConParametros)
        .then(() => self.clients.claim()));
});

self.addEventListener("fetch", (event) => {
    const request = event.request, url = new URL(request.url);
    if (request.method !== "GET") return;
    if (url.origin === location.origin) {
        if (url.pathname.startsWith("/api/")) {
            // /api/config/mapa: el proveedor de mapa; con mala señal se usa el último conocido (la página tiene además su propia copia).
            if (/^\/api\/(rutas(\/\d+)?|config\/mapa)$/.test(url.pathname)) event.respondWith(redPrimero(request, DATOS, ESPERA_RUTAS_MS));
            return;
        }
        event.respondWith(aplicacion(request, url));
    } else if (HOSTS_MAPA.has(url.hostname)) {
        event.respondWith(mapa(request, url));
    }
    // Cualquier otro origen (en particular maps.googleapis.com y gstatic.com) NO se intercepta ni se guarda: los términos de Google no permiten
    // almacenar sus teselas ni su código fuera de lo que gestiona el propio SDK.
});

// ------------------------------------------------------------------ app
/** Clave canónica de un recurso de la app: origen + ruta, sin parámetros ni fragmento; «/index.html» equivale a «/». */
function claveCanonica(url) {
    const ruta = url.pathname === "/index.html" ? "/" : url.pathname;
    return new Request(url.origin + ruta);
}

/** Solo se guarda una respuesta correcta (200), propia, sin redirecciones y de un tipo de contenido esperado. */
function guardable(respuesta, esNavegacion) {
    if (!respuesta || respuesta.status !== 200 || respuesta.type !== "basic" || respuesta.redirected) return false;
    const tipo = respuesta.headers.get("Content-Type") || "";
    return esNavegacion ? /^text\/html/i.test(tipo) : TIPOS_APP.test(tipo);
}

/** Elimina de la caché de la app las entradas con parámetros que dejó una versión anterior del worker. */
async function limpiarClavesConParametros() {
    const cache = await caches.open(CACHE_APP);
    for (const clave of await cache.keys()) if (new URL(clave.url).search) await cache.delete(clave);
}

async function aplicacion(request, url) {
    const cache = await caches.open(CACHE_APP);
    const clave = claveCanonica(url);
    const esNavegacion = request.mode === "navigate";
    let guardado = await cache.match(clave);
    // Sin conexión, cualquier navegación a una página sin copia propia recibe el shell principal («/»).
    const respaldoNavegacion = async () => guardado || (esNavegacion ? cache.match(new Request(url.origin + "/")) : undefined);
    // Las bibliotecas del mapa casi no cambian: primero la copia guardada.
    if (url.pathname.startsWith("/vendor/") && guardado) return guardado;
    // El resto (HTML, CSS, JS propios): red primero con poca espera, para que una versión nueva se vea en la primera recarga;
    // sin conexión o con señal mala (> 2,5 s) se usa la copia guardada. La solicitud original (con sus parámetros) va a la red.
    const red = fetch(request).then((respuesta) => {
        if (guardable(respuesta, esNavegacion)) cache.put(clave, respuesta.clone()).then(() => podarApp(cache));
        return respuesta;
    });
    guardado = await respaldoNavegacion();
    if (!guardado) return red.catch(() => Response.error());
    const limite = new Promise((_, rechazar) => setTimeout(() => rechazar(new Error("lenta")), ESPERA_APP_MS));
    red.catch(() => {});
    try { return await Promise.race([red, limite]); } catch (e) { return guardado; }
}

/** Mantiene la caché de la app acotada: borra las entradas más antiguas que no pertenezcan al shell instalado. */
async function podarApp(cache) {
    const claves = await cache.keys();
    let sobran = claves.length - MAX_APP;
    if (sobran <= 0) return;
    const protegidas = new Set(SHELL);
    for (const clave of claves) {
        if (sobran <= 0) break;
        if (protegidas.has(new URL(clave.url).pathname)) continue;
        await cache.delete(clave);
        sobran--;
    }
}

// ------------------------------------------------------------------ rutas (datos)
/** Red primero; si falla o tarda más que {@code espera}, la última copia guardada (marcada con X-Spevb-Cache). */
const ERRORES_SERVIDOR = new Set([500, 502, 503, 504]);
async function redPrimero(request, nombre, espera) {
    const cache = await caches.open(nombre);
    const red = fetch(request).then((respuesta) => {
        if (respuesta.ok) cache.put(request, respuesta.clone()).then(() => podar(cache, MAX_DATOS));
        return respuesta;
    });
    const limite = new Promise((_, rechazar) => setTimeout(() => rechazar(new Error("lenta")), espera));
    try {
        const respuesta = await Promise.race([red, limite]);
        // Un error del servidor (500, 502, 503, 504) que llega rápido no es «falla de red»: si hay una copia válida se usa esa.
        // 400, 401, 403 y 404 son respuestas legítimas y nunca se sustituyen por datos viejos; sin copia se conserva el error original.
        if (ERRORES_SERVIDOR.has(respuesta.status)) {
            const copia = await cache.match(request);
            if (copia) return marcarGuardado(copia);
        }
        return respuesta;
    } catch (e) {
        const guardado = await cache.match(request);
        if (guardado) return marcarGuardado(guardado);
        try { return await red; } catch (e2) { /* sin red y sin copia */ }
        return new Response(JSON.stringify({ status: 503, message: "Sin conexión y todavía no hay datos guardados de esta ruta." }),
            { status: 503, headers: { "Content-Type": "application/json" } });
    }
}

function marcarGuardado(respuesta) {
    const cabeceras = new Headers(respuesta.headers);
    cabeceras.set("X-Spevb-Cache", "guardado");
    return new Response(respuesta.body, { status: respuesta.status, statusText: respuesta.statusText, headers: cabeceras });
}

// ------------------------------------------------------------------ mapa
async function buscarMapa(request) {
    for (const nombre of [MAPA, TESELAS]) {
        const cache = await caches.open(nombre);
        const r = await cache.match(request, { ignoreVary: true });
        if (r) return r;
    }
    return null;
}

async function mapa(request, url) {
    const esEstilo = url.hostname === "tiles.openfreemap.org" && (url.pathname.startsWith("/styles/") || url.pathname === "/planet");
    if (esEstilo) {
        // Estilo y TileJSON cambian (la versión de las teselas va en su dirección): red primero; con mala señal, lo guardado.
        const red = fetch(request).then((respuesta) => {
            if (respuesta.ok) caches.open(TESELAS).then((c) => c.put(request, respuesta.clone()));
            return respuesta;
        });
        const limite = new Promise((_, rechazar) => setTimeout(() => rechazar(new Error("lenta")), ESPERA_ESTILO_MS));
        try { return await Promise.race([red, limite]); }
        catch (e) {
            const guardado = await buscarMapa(request);
            if (guardado) return guardado;
            return red.catch(() => Response.error());
        }
    }
    // Teselas, íconos y fuentes: primero lo guardado; si no está, la red (y se guarda lo visto).
    const guardado = await buscarMapa(request);
    if (guardado) return guardado;
    try {
        const respuesta = await fetch(request);
        // Solo respuestas con CORS (no opacas): una opaca ocupa ≈ 7 MB de cuota cada una.
        if (respuesta.ok && respuesta.type !== "opaque") guardarTesela(request, respuesta.clone());
        return respuesta;
    } catch (e) {
        return Response.error();
    }
}

let desdePoda = 0;
async function guardarTesela(request, respuesta) {
    const cache = await caches.open(TESELAS);
    await cache.put(request, respuesta);
    if (++desdePoda >= 40) { desdePoda = 0; podar(cache, MAX_TESELAS); }
}

async function podar(cache, maximo) {
    const claves = await cache.keys();
    const sobran = claves.length - maximo;
    for (let i = 0; i < sobran; i++) await cache.delete(claves[i]);     // las más antiguas primero (orden de inserción)
}
