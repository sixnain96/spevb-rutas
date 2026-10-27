'use strict';
/* Uso con mala señal o sin conexión.
   · «Guardar mapa»: descarga el mapa vectorial de Villavicencio (estilos, íconos, fuentes y teselas) al teléfono.
   · Rutas: se guardan solas (la lista y el detalle de cada recorrido) para poder verlas sin conexión.
   · Viajes recientes: el último resultado de cada A→B se guarda en el teléfono y se puede volver a ver sin conexión.
   · Avisos de conexión: se dice cuándo se está usando lo guardado y cuándo vuelve la señal.
   La app (HTML/JS/CSS) y lo visto del mapa los guarda el service worker (sw.js). */
(() => {
    const S = window.Spevb || (window.Spevb = {});
    const OFM = 'https://tiles.openfreemap.org';
    const CACHE_MAPA = 'spevb-mapa-v1';
    const K_MAPA = 'spevb-mapa-guardado', K_RECIENTES = 'spevb-recientes', K_RUTAS = 'spevb-rutas-guardadas';
    // Zonas: toda la región de trabajo hasta zoom 12 y el casco urbano a zoom 13–14 (más detalle no existe: el vector llega a 14).
    const REGION = { sur: 3.95, oeste: -73.85, norte: 4.35, este: -73.45 };
    const URBANA = { sur: 4.07, oeste: -73.71, norte: 4.20, este: -73.57 };
    const ZOOM_URBANO = 13;
    const RANGOS_FUENTES = ['0-255', '256-511', '8192-8447'];   // letras con tilde y signos de puntuación
    const ESTILOS = ['liberty', 'positron'];
    const MAX_RECIENTES = 6;

    const leer = (clave, vacio) => { try { return JSON.parse(localStorage.getItem(clave)) ?? vacio; } catch { return vacio; } };
    const escribir = (clave, valor) => { try { localStorage.setItem(clave, JSON.stringify(valor)); return true; } catch { return false; } };

    // ------------------------------------------------------------------ utilidades
    const enLinea = () => navigator.onLine !== false;
    const lon2x = (lon, z) => Math.floor(((lon + 180) / 360) * 2 ** z);
    const lat2y = (lat, z) => {
        const r = (lat * Math.PI) / 180;
        return Math.floor(((1 - Math.log(Math.tan(r) + 1 / Math.cos(r)) / Math.PI) / 2) * 2 ** z);
    };
    /** Teselas (x, y) que cubren una caja a un nivel de zoom. */
    function teselas(caja, z) {
        const x0 = lon2x(caja.oeste, z), x1 = lon2x(caja.este, z), y0 = lat2y(caja.norte, z), y1 = lat2y(caja.sur, z);
        const r = [];
        for (let x = x0; x <= x1; x++) for (let y = y0; y <= y1; y++) r.push([x, y]);
        return r;
    }
    const megas = (bytes) => (bytes / 1048576).toLocaleString('es-CO', { maximumFractionDigits: 1 }) + ' MB';
    const hace = (t) => {
        const min = Math.max(0, Math.round((Date.now() - t) / 60000));
        if (min < 1) return 'hace un momento';
        if (min < 60) return 'hace ' + min + ' min';
        if (min < 1440) return 'hace ' + Math.round(min / 60) + ' h';
        return 'hace ' + Math.round(min / 1440) + ' d';
    };

    // ------------------------------------------------------------------ mapa guardado
    const mapaGuardado = () => leer(K_MAPA, null);

    async function pedir(url, signal, intentos = 3) {
        let ultimo;
        for (let i = 0; i < intentos; i++) {
            try {
                const r = await fetch(url, { signal });
                if (r.ok) return r;
                if (r.status === 404) return null;           // esa tesela no existe (océano, vacío): no es un error
                ultimo = new Error('HTTP ' + r.status);
            } catch (e) {
                if (e.name === 'AbortError') throw e;
                ultimo = e;
            }
            await new Promise((ok) => setTimeout(ok, 400 * (i + 1)));
        }
        throw ultimo;
    }

    /**
     * Descarga el mapa vectorial para usarlo sin conexión. {@code onProgreso({hechos, total, bytes, fase})}.
     * Guarda en un paquete fijo (no se poda) y pide al navegador que no lo borre por falta de espacio.
     */
    async function descargarMapa({ onProgreso, signal } = {}) {
        if (!('caches' in window)) throw new Error('Este navegador no permite guardar el mapa.');
        if (!enLinea()) throw new Error('Necesitas conexión para descargar el mapa.');
        const cache = await caches.open(CACHE_MAPA);
        let bytes = 0, hechos = 0;
        const avisar = (fase, total) => onProgreso && onProgreso({ hechos, total, bytes, fase });

        // 1) estilos → íconos y fuentes que usan
        avisar('Preparando…', 0);
        let sprite = null, glyphs = null, rasterTpl = null, tilejson = OFM + '/planet';
        const pilas = new Set();
        for (const nombre of ESTILOS) {
            const url = OFM + '/styles/' + nombre;
            const r = await pedir(url, signal);
            await cache.put(url, r.clone());
            const estilo = await r.json();
            sprite = estilo.sprite || sprite;
            glyphs = estilo.glyphs || glyphs;
            rasterTpl = estilo.sources?.ne2_shaded?.tiles?.[0] || rasterTpl;
            tilejson = estilo.sources?.openmaptiles?.url || tilejson;
            (estilo.layers || []).forEach((capa) => {
                const f = capa.layout?.['text-font'];
                if (Array.isArray(f) && f.every((x) => typeof x === 'string')) pilas.add(f.join(','));
            });
        }
        // 2) TileJSON: dice dónde están las teselas (la versión va en la dirección)
        const rt = await pedir(tilejson, signal);
        await cache.put(tilejson, rt.clone());
        const tj = await rt.json();
        const plantilla = tj.tiles?.[0];
        const maxZoom = Math.min(14, tj.maxzoom ?? 14);
        if (!plantilla) throw new Error('El servicio de mapas no respondió como se esperaba.');

        // 3) lista de todo lo que hay que guardar
        const urls = [];
        if (sprite) ['.json', '.png', '@2x.json', '@2x.png'].forEach((s) => urls.push(sprite + s));
        if (glyphs) pilas.forEach((pila) => RANGOS_FUENTES.forEach((rango) =>
            urls.push(glyphs.replace('{fontstack}', encodeURIComponent(pila)).replace('{range}', rango))));
        for (let z = 0; z <= maxZoom; z++) {
            teselas(z >= ZOOM_URBANO ? URBANA : REGION, z).forEach(([x, y]) => urls.push(plantilla.replace('{z}', z).replace('{x}', x).replace('{y}', y)));
        }
        if (rasterTpl) for (let z = 0; z <= 6; z++) teselas(REGION, z).forEach(([x, y]) => urls.push(rasterTpl.replace('{z}', z).replace('{x}', x).replace('{y}', y)));
        const total = urls.length;
        avisar('Descargando el mapa…', total);

        // 4) descarga con pocas conexiones a la vez (no saturar una red lenta)
        let siguiente = 0;
        async function trabajador() {
            while (siguiente < urls.length) {
                const url = urls[siguiente++];
                try {
                    const r = await pedir(url, signal);
                    if (r) {
                        const copia = r.clone();
                        await cache.put(url, r);
                        bytes += Number(copia.headers.get('content-length')) || (await copia.blob()).size;
                    }
                } catch (e) {
                    if (e.name === 'AbortError') throw e;
                    throw new Error('Se interrumpió la descarga (' + (enLinea() ? 'el servicio no respondió' : 'se perdió la conexión') + '). Lo guardado hasta ahora se conserva: vuelve a intentar.');
                }
                hechos++;
                avisar('Descargando el mapa…', total);
            }
        }
        await Promise.all(Array.from({ length: 5 }, trabajador));
        const meta = { fecha: Date.now(), archivos: hechos, bytes };
        escribir(K_MAPA, meta);
        try { await navigator.storage?.persist?.(); } catch { /* opcional */ }
        return meta;
    }

    async function borrarMapa() {
        await caches.delete(CACHE_MAPA);
        try { localStorage.removeItem(K_MAPA); } catch { /* sin almacenamiento */ }
    }

    // ------------------------------------------------------------------ rutas guardadas
    let guardandoRutas = null;
    /** Pide la lista de rutas y el detalle de cada una: el service worker deja una copia para verlas sin conexión. */
    async function guardarRutas({ forzar = false } = {}) {
        if (!enLinea()) return null;
        const previo = leer(K_RUTAS, null);
        if (!forzar && previo && Date.now() - previo.fecha < 6 * 3600 * 1000) return previo;
        if (guardandoRutas) return guardandoRutas;
        guardandoRutas = (async () => {
            try {
                const lista = await (await fetch('/api/rutas')).json();
                const ids = lista.map((r) => r.id).slice(0, 120);
                let i = 0;
                await Promise.all(Array.from({ length: 4 }, async () => {
                    while (i < ids.length) { const id = ids[i++]; try { await fetch('/api/rutas/' + id); } catch { /* sigue con las demás */ } }
                }));
                const meta = { fecha: Date.now(), rutas: ids.length };
                escribir(K_RUTAS, meta);
                return meta;
            } catch { return null; } finally { guardandoRutas = null; }
        })();
        return guardandoRutas;
    }
    /** Guarda el detalle de rutas concretas (las de un resultado) para poder dibujarlas sin conexión. */
    function guardarDetalleRutas(ids) {
        if (!enLinea()) return;
        [...new Set(ids)].forEach((id) => { fetch('/api/rutas/' + id).catch(() => {}); });
    }

    // ------------------------------------------------------------------ viajes recientes
    const cerca = (p, q) => {
        const r = 6371000, rad = Math.PI / 180, dLat = (q.lat - p.lat) * rad, dLng = (q.lng - p.lng) * rad;
        const h = Math.sin(dLat / 2) ** 2 + Math.cos(p.lat * rad) * Math.cos(q.lat * rad) * Math.sin(dLng / 2) ** 2;
        return 2 * r * Math.asin(Math.sqrt(h)) < 25;
    };
    const punto = (p) => ({ lat: p.lat, lng: p.lng, nombre: p.nombre || '', direccion: p.direccion || null, fuente: p.fuente || '', confirmado: !!p.confirmado, precision: p.precision || null });

    const Recientes = {
        listar() { return leer(K_RECIENTES, []); },
        /** Guarda el resultado de un viaje A→B (reemplaza al anterior del mismo par). */
        guardar(a, b, data, soloValidadas) {
            let lista = Recientes.listar().filter((r) => !(cerca(r.a, a) && cerca(r.b, b)));
            lista.unshift({ t: Date.now(), a: punto(a), b: punto(b), soloValidadas: !!soloValidadas, data });
            lista = lista.slice(0, MAX_RECIENTES);
            // Si no cabe en el almacenamiento, se van soltando los más viejos.
            while (lista.length && !escribir(K_RECIENTES, lista)) lista.pop();
            window.dispatchEvent(new CustomEvent('spevb-recientes'));
        },
        buscar(a, b, soloValidadas) {
            return Recientes.listar().find((r) => cerca(r.a, a) && cerca(r.b, b) && !!r.soloValidadas === !!soloValidadas) || null;
        },
        borrar() { try { localStorage.removeItem(K_RECIENTES); } catch { /* nada */ } window.dispatchEvent(new CustomEvent('spevb-recientes')); },
    };

    // Trazados de caminata por calles ya calculados: sin señal se reutilizan para el mismo par de puntos.
    const K_CAMINATAS = 'spevb-caminatas', MAX_CAMINATAS = 40;
    const Caminatas = {
        guardar(clave, data) {
            if (!data || !Array.isArray(data.coordinates)) return;
            const todo = leer(K_CAMINATAS, {});
            delete todo[clave];
            todo[clave] = data;
            const claves = Object.keys(todo);
            claves.slice(0, Math.max(0, claves.length - MAX_CAMINATAS)).forEach((k) => delete todo[k]);
            while (Object.keys(todo).length && !escribir(K_CAMINATAS, todo)) delete todo[Object.keys(todo)[0]];
        },
        leer(clave) { return leer(K_CAMINATAS, {})[clave] || null; },
    };

    // ------------------------------------------------------------------ avisos de conexión
    let baseAnterior = null;
    const mapa = () => S.maps && S.maps.map;
    function alPerderConexion() {
        const guardado = mapaGuardado();
        S.toast('Sin conexión: se usan el mapa y los datos guardados.' + (guardado ? '' : ' Guarda el mapa desde el menú para verlo completo sin señal.'),
            { tipo: 'info', ms: 0, clave: 'conexion' });
        const m = mapa();
        // Con el mapa guardado, se pasa al mapa vectorial (es el que se descargó).
        if (guardado && m && m.activeBase && m.activeBase() && !/^(Moderno|Minimalista)$/.test(m.activeBase())) {
            baseAnterior = m.activeBase();
            m.useBase('Moderno');
        }
    }
    function alVolverConexion() {
        S.toast('Conexión recuperada.', { tipo: 'success', ms: 4000, clave: 'conexion' });
        const m = mapa();
        if (baseAnterior && m && m.useBase) { m.useBase(baseAnterior); baseAnterior = null; }
        guardarRutas();
    }
    window.addEventListener('offline', alPerderConexion);
    window.addEventListener('online', alVolverConexion);
    window.addEventListener('spevb-sin-red', () => { if (enLinea()) S.toast('Sin respuesta del servidor: la señal es débil o no hay internet.', { tipo: 'info', ms: 6000, clave: 'conexion' }); });
    let ultimoAviso = 0;
    window.addEventListener('spevb-datos-guardados', () => {
        if (Date.now() - ultimoAviso < 8000) return;
        ultimoAviso = Date.now();
        S.toast('Mostrando datos guardados en este teléfono.', { tipo: 'info', ms: 4000, clave: 'cache' });
    });

    // ------------------------------------------------------------------ menú «Uso sin conexión»
    function iniciarMenu() {
        const $ = (id) => document.getElementById(id);
        if (!$('offlineDownload')) return;
        let controlador = null;
        const pintar = () => {
            const m = mapaGuardado(), r = leer(K_RUTAS, null);
            $('offlineInfo').textContent = m
                ? 'Mapa guardado ' + hace(m.fecha) + ' · ' + megas(m.bytes) + (r ? ' · ' + r.rutas + ' rutas' : '')
                : 'El mapa todavía no está guardado en este teléfono.' + (r ? ' Rutas guardadas: ' + r.rutas + '.' : '');
            $('offlineDownload').textContent = m ? 'Actualizar el mapa guardado' : 'Guardar mapa de Villavicencio';
            $('offlineDelete').hidden = !m;
        };
        $('offlineDownload').addEventListener('click', async () => {
            if (controlador) { controlador.abort(); return; }              // segundo toque: cancelar
            controlador = new AbortController();
            const boton = $('offlineDownload'), barra = $('offlineProgress');
            boton.textContent = 'Cancelar descarga';
            barra.hidden = false; barra.value = 0; barra.max = 1;
            $('offlineInfo').textContent = 'Descargando… no cierres la aplicación.';
            try {
                const meta = await descargarMapa({
                    signal: controlador.signal,
                    onProgreso: ({ hechos, total, bytes, fase }) => {
                        barra.max = Math.max(1, total); barra.value = hechos;
                        $('offlineInfo').textContent = fase + (total ? ' ' + hechos + ' de ' + total + ' · ' + megas(bytes) : '');
                    },
                });
                await guardarRutas({ forzar: true });
                S.toast('Mapa guardado (' + megas(meta.bytes) + '). Ya puedes usarlo sin conexión.', { tipo: 'success', ms: 6000, clave: 'mapa' });
            } catch (e) {
                if (e.name === 'AbortError') S.toast('Descarga cancelada.', { tipo: 'info', ms: 3000, clave: 'mapa' });
                else S.toast(e.message || 'No se pudo guardar el mapa.', { tipo: 'error', ms: 9000, clave: 'mapa' });
            } finally {
                controlador = null;
                barra.hidden = true;
                pintar();
            }
        });
        $('offlineDelete').addEventListener('click', async () => {
            if (!confirm('¿Borrar el mapa guardado en este teléfono? Se libera el espacio.')) return;
            await borrarMapa();
            pintar();
        });
        pintar();
    }

    S.Offline = { enLinea, descargarMapa, borrarMapa, mapaGuardado, guardarRutas, guardarDetalleRutas, teselas, hace, megas, REGION, URBANA };
    S.Recientes = Recientes;
    S.Caminatas = Caminatas;

    document.addEventListener('DOMContentLoaded', () => {
        iniciarMenu();
        if (!enLinea()) alPerderConexion();
    });
    // Con señal y en un momento libre: se dejan guardadas las rutas para la próxima vez que falle la conexión.
    window.addEventListener('load', () => {
        const libre = window.requestIdleCallback || ((fn) => setTimeout(fn, 2500));
        libre(() => { guardarRutas(); });
    });
})();
