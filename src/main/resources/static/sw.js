"use strict";
/* Service worker de SPEVB: la app funciona con mala señal o sin conexión.
   · App (HTML, CSS, JS, Leaflet, MapLibre): se guarda al instalar; abre al instante y se actualiza detrás.
   · Rutas (/api/rutas y /api/rutas/{id}): red primero (con límite de espera) y, si falla o tarda, la última copia guardada.
   · Mapa: las teselas, estilos, íconos y fuentes se sirven primero de lo guardado y solo si no están se piden a la red.
       - MAPA   = paquete «Guardar mapa» (fijo, lo descarga la persona desde el menú).
       - TESELAS = lo que se fue viendo (se poda al llegar al máximo).
   · El cálculo de viajes (/api/viajes, /api/caminata, direcciones) nunca se intercepta: lo guarda la página como «recientes». */
const VERSION = "spevb-v4.1.0";
const MAPA = "spevb-mapa-v1";
const TESELAS = "spevb-teselas-v1";
const DATOS = "spevb-datos-v1";
const MAX_TESELAS = 1500;
const ESPERA_RUTAS_MS = 6000;       // con mala señal: pasado este tiempo se usa la copia guardada
const ESPERA_ESTILO_MS = 3500;
const HOSTS_MAPA = new Set(["tiles.openfreemap.org", "server.arcgisonline.com", "tile.openstreetmap.org"]);

const SHELL = ["/", "/css/styles.css", "/css/home.css", "/css/bus3d.css", "/vendor/leaflet/leaflet.css", "/vendor/leaflet/leaflet.js",
    "/vendor/leaflet/images/marker-icon.png", "/vendor/leaflet/images/marker-icon-2x.png", "/vendor/leaflet/images/marker-shadow.png",
    "/vendor/leaflet/images/layers.png", "/vendor/leaflet/images/layers-2x.png",
    "/vendor/maplibre/maplibre-gl.css", "/vendor/maplibre/maplibre-gl-csp.js", "/vendor/maplibre/maplibre-gl-csp-worker.js", "/vendor/maplibre/leaflet-maplibre-gl.js",
    "/js/common.js", "/js/offline.js", "/js/extras.js", "/js/gps.js", "/js/bus.js", "/js/app.js", "/js/planner.js", "/js/ui.js", "/js/sheet.js", "/js/pwa.js",
    "/manifest.webmanifest", "/icons/icon-192.png", "/icons/icon-512.png"];

self.addEventListener("install", (event) => {
    // Un archivo que falle no debe impedir la instalación: se guarda todo lo que se pueda.
    event.waitUntil(caches.open(VERSION).then((cache) => Promise.allSettled(SHELL.map((u) => cache.add(u)))).then(() => self.skipWaiting()));
});

self.addEventListener("activate", (event) => {
    const vigentes = new Set([VERSION, MAPA, TESELAS, DATOS]);
    event.waitUntil(caches.keys().then((keys) => Promise.all(keys.filter((k) => !vigentes.has(k)).map((k) => caches.delete(k))))
        .then(() => self.clients.claim()));
});

self.addEventListener("fetch", (event) => {
    const request = event.request, url = new URL(request.url);
    if (request.method !== "GET") return;
    if (url.origin === location.origin) {
        if (url.pathname.startsWith("/api/")) {
            if (/^\/api\/rutas(\/\d+)?$/.test(url.pathname)) event.respondWith(redPrimero(request, DATOS, ESPERA_RUTAS_MS));
            return;
        }
        event.respondWith(aplicacion(request, url));
    } else if (HOSTS_MAPA.has(url.hostname)) {
        event.respondWith(mapa(request, url));
    }
});

// ------------------------------------------------------------------ app
async function aplicacion(request, url) {
    const cache = await caches.open(VERSION);
    const guardado = await cache.match(request, { ignoreSearch: true });
    // Las bibliotecas del mapa casi no cambian: primero la copia guardada. El resto: copia guardada ya y actualización detrás.
    const biblioteca = url.pathname.startsWith("/vendor/");
    const red = fetch(request).then((respuesta) => {
        if (respuesta.ok && respuesta.type === "basic") cache.put(request, respuesta.clone());
        return respuesta;
    }).catch(() => guardado || Response.error());
    return biblioteca && guardado ? guardado : (guardado || red);
}

// ------------------------------------------------------------------ rutas (datos)
/** Red primero; si falla o tarda más que {@code espera}, la última copia guardada (marcada con X-Spevb-Cache). */
async function redPrimero(request, nombre, espera) {
    const cache = await caches.open(nombre);
    const red = fetch(request).then((respuesta) => {
        if (respuesta.ok) cache.put(request, respuesta.clone());
        return respuesta;
    });
    const limite = new Promise((_, rechazar) => setTimeout(() => rechazar(new Error("lenta")), espera));
    try {
        return await Promise.race([red, limite]);
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
    if (++desdePoda >= 40) { desdePoda = 0; podar(cache); }
}

async function podar(cache) {
    const claves = await cache.keys();
    const sobran = claves.length - MAX_TESELAS;
    for (let i = 0; i < sobran; i++) await cache.delete(claves[i]);     // las más antiguas primero (orden de inserción)
}
