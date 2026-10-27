'use strict';
window.Spevb = (() => {
    const center = [4.142, -73.6266];
    const cityBounds = [[3.95, -73.85], [4.35, -73.45]];
    /* Pedido al servidor. Con mala señal no se queda esperando para siempre: pasado {@code timeout} (25 s) falla con
       {@code error.timeout}; sin red falla con {@code error.offline}. Así cada pantalla puede usar lo guardado. */
    async function api(url, options = {}) {
        const { timeout = 25000, signal, ...init } = options;
        const control = new AbortController();
        let agotado = false;
        const timer = setTimeout(() => { agotado = true; control.abort(); }, timeout);
        if (signal) signal.aborted ? control.abort() : signal.addEventListener('abort', () => control.abort(), { once: true });
        let response;
        try {
            response = await fetch(url, {
                ...init,
                signal: control.signal,
                headers: { Accept: 'application/json', ...init.headers },
            });
        } catch (e) {
            clearTimeout(timer);
            if (e.name === 'AbortError' && !agotado) throw e;     // cancelado a propósito
            const error = new Error(agotado
                ? 'La conexión está muy lenta y el servidor no respondió.'
                : 'Sin conexión a internet.');
            error.timeout = agotado;
            error.offline = !agotado;
            window.dispatchEvent(new CustomEvent('spevb-sin-red'));
            throw error;
        }
        let data;
        try {
            data = await response.json();
        } catch {
            data = null;
        } finally {
            clearTimeout(timer);
        }
        if (!response.ok) {
            const error = new Error(
                data?.message ||
                    'No se pudo completar la operación (HTTP ' + response.status + ').',
            );
            error.status = response.status;
            throw error;
        }
        if (response.headers.get('X-Spevb-Cache') === 'guardado') window.dispatchEvent(new CustomEvent('spevb-datos-guardados'));
        return data;
    }
    /* Aviso flotante sobre el mapa (visible aunque el panel inferior esté cerrado). Con {@code clave} se reutiliza el mismo
       aviso; {@code ms: 0} lo deja fijo hasta cerrarlo. Un toque lo cierra. */
    const toasts = new Map();
    function toast(message, { tipo = 'info', ms = 6000, clave = null, accion = null } = {}) {
        let box = document.getElementById('spevbToasts');
        if (!box) {
            box = document.createElement('div');
            box.id = 'spevbToasts';
            document.body.append(box);
        }
        let item = clave ? toasts.get(clave) : null;
        if (!item) {
            const el = document.createElement('div');
            const label = document.createElement('span');
            el.append(label);
            el.addEventListener('click', () => cerrar(clave ? toasts.get(clave) : item));
            box.append(el);
            item = { el, label, timer: null, clave };
            if (clave) toasts.set(clave, item);
        }
        item.el.className = 'spevb-toast ' + tipo;
        item.el.setAttribute('role', tipo === 'error' ? 'alert' : 'status');
        item.label.textContent = message;
        item.el.querySelectorAll('button').forEach((b) => b.remove());
        [].concat(accion || []).forEach((a) => {
            const button = document.createElement('button');
            button.type = 'button';
            button.textContent = a.texto;
            button.addEventListener('click', (e) => { e.stopPropagation(); a.alHacer(); if (a.cerrar !== false) cerrar(item); });
            item.el.append(button);
        });
        clearTimeout(item.timer);
        if (ms > 0) item.timer = setTimeout(() => cerrar(item), ms);
        return item;
    }
    function cerrar(item) {
        if (!item) return;
        clearTimeout(item.timer);
        item.el.remove();
        if (item.clave && toasts.get(item.clave) === item) toasts.delete(item.clave);
    }
    toast.cerrar = (clave) => cerrar(toasts.get(clave));

    /* Márgenes para encuadrar algo en el mapa sin que lo tapen el panel y los botones flotantes.
       Escritorio: el panel flotante ocupa la izquierda. Celular: el panel inferior (--sheet-visible) y la columna de botones. */
    /** Diseño de celular (panel inferior). Los celulares de lado (poca altura) usan el diseño de escritorio. Debe coincidir con home.css. */
    const COMPACT_LAYOUT = '(max-width: 559px), (max-width: 860px) and (min-height: 481px)';
    const compactLayout = () => window.matchMedia(COMPACT_LAYOUT).matches;

    function fitPadding(map) {
        const size = map.getSize();
        if (compactLayout()) {
            const cover = parseFloat(getComputedStyle(document.documentElement).getPropertyValue('--sheet-visible')) || 0;
            // Siempre debe quedar un área útil (≥ 140 px de alto): con el teclado abierto o en pantallas bajas un margen
            // mayor que el mapa daría un zoom absurdo.
            const bottom = Math.max(24, Math.min(cover + 40, size.y - 60 - 140));
            return { paddingTopLeft: [30, 60], paddingBottomRight: [72, bottom] };
        }
        // Escritorio y celular de lado: la tarjeta flotante ocupa la izquierda; se encuadra a su derecha.
        const card = document.getElementById('sheet');
        const ancho = card ? Math.round(card.getBoundingClientRect().right + 24) : 440;
        const left = Math.min(ancho, Math.max(30, size.x - 70 - 200));
        return { paddingTopLeft: [left, 60], paddingBottomRight: [70, size.y < 520 ? 70 : 130] };
    }
    /** Encuadra los límites una sola vez por vez: cancela la animación anterior y, si en los siguientes segundos cambia el
        tamaño del mapa (teclado que se cierra, barra del navegador), vuelve a encuadrar con las medidas nuevas. */
    function fitMap(map, bounds, { maxZoom = 17 } = {}) {
        if (!bounds || !bounds.isValid()) return;
        map.stop();
        map.fitBounds(bounds, { ...fitPadding(map), maxZoom });
        map._lastFit = { bounds, maxZoom, at: Date.now() };
    }

    function text(value) {
        const node = document.createElement('span');
        node.textContent = String(value ?? '');
        return node;
    }
    function download(data, name) {
        const url = URL.createObjectURL(
            new Blob([JSON.stringify(data, null, 2)], { type: 'application/geo+json' }),
        );
        const anchor = document.createElement('a');
        anchor.href = url;
        anchor.download = name;
        anchor.click();
        setTimeout(() => URL.revokeObjectURL(url), 1000);
    }
    function geometry(value) {
        let g = value;
        if (g?.type === 'FeatureCollection') {
            if (g.features?.length !== 1) throw new Error('Importa una sola ruta por archivo.');
            g = g.features[0];
        }
        if (g?.type === 'Feature') g = g.geometry;
        if (
            g?.type !== 'LineString' ||
            !Array.isArray(g.coordinates) ||
            g.coordinates.length < 2 ||
            g.coordinates.length > 10000
        )
            throw new Error('Se requiere un LineString de 2 a 10.000 puntos.');
        let previous;
        for (const p of g.coordinates) {
            if (
                !Array.isArray(p) ||
                p.length !== 2 ||
                !p.every(Number.isFinite) ||
                Math.abs(p[0]) > 180 ||
                Math.abs(p[1]) > 90
            )
                throw new Error('Coordenadas inválidas: usa [longitud, latitud] en WGS84.');
            if (previous && p[0] === previous[0] && p[1] === previous[1])
                throw new Error('Hay puntos consecutivos duplicados.');
            previous = p;
        }
        if (g.crs) throw new Error('No se admite un CRS alternativo. Usa WGS84.');
        return { type: 'LineString', coordinates: g.coordinates.map((p) => [...p]) };
    }
    function feature(route) {
        return {
            type: 'Feature',
            properties: {
                codigo: route.codigo,
                nombre: route.nombre,
                origen: route.origen,
                destino: route.destino,
                fuente: route.fuente,
                validada: route.validada,
                version: route.version,
            },
            geometry: route.recorrido,
        };
    }
    let vectorEngine = null;
    function loadVectorEngine() {
        if (vectorEngine) return vectorEngine;
        vectorEngine = new Promise((resolve, reject) => {
            const probe = document.createElement('canvas');
            if (!(probe.getContext('webgl2') || probe.getContext('webgl'))) return reject(new Error('Sin WebGL'));
            const base = '/vendor/maplibre/';
            const css = document.createElement('link');
            css.rel = 'stylesheet';
            css.href = base + 'maplibre-gl.css';
            document.head.append(css);
            const script = (src) => new Promise((ok, fail) => {
                const el = document.createElement('script');
                el.src = src;
                el.onload = ok;
                el.onerror = () => fail(new Error('No se pudo cargar ' + src));
                document.head.append(el);
            });
            script(base + 'maplibre-gl-csp.js')
                .then(() => {
                    window.maplibregl.setWorkerUrl(base + 'maplibre-gl-csp-worker.js');
                    return script(base + 'leaflet-maplibre-gl.js');
                })
                .then(resolve, reject);
        });
        return vectorEngine;
    }
    function createMap(id) {
        if (!window.L)
            throw new Error(
                'No se pudo cargar el mapa. Comprueba tu conexión y recarga la página.',
            );
        const map = L.map(id, { preferCanvas: true, clickTolerance: 14, maxBounds: cityBounds, maxBoundsViscosity: 0.45, minZoom: 11, maxZoom: 19, zoomControl: false }).setView(center, 13);
        L.control.zoom({ position: 'bottomright', zoomInTitle: 'Acercar', zoomOutTitle: 'Alejar' }).addTo(map);
        // Equipos o redes modestas (ahorro de datos, 2G/3G, poca memoria): menos teselas en memoria y, por defecto,
        // el mapa raster ligero en lugar del vectorial (≈ 1,4 MB de motor).
        const link = navigator.connection || {};
        const modest = !!link.saveData || /^(slow-2g|2g|3g)$/.test(link.effectiveType || '')
            || (navigator.deviceMemory || 4) <= 2 || (navigator.hardwareConcurrency || 8) <= 3;
        const ESRI = 'https://server.arcgisonline.com/ArcGIS/rest/services/';
        const esriCredit = 'Mapa &copy; <a href="https://www.esri.com">Esri</a>, HERE, Garmin y colaboradores de OpenStreetMap';
        const tile = (url, options) =>
            L.tileLayer(url, { crossOrigin: true, keepBuffer: modest ? 1 : 3, updateWhenIdle: modest, updateWhenZooming: false, ...options });
        const claro = () =>
            L.layerGroup([
                tile(ESRI + 'Canvas/World_Light_Gray_Base/MapServer/tile/{z}/{y}/{x}', {
                    maxNativeZoom: 16, maxZoom: 19, attribution: esriCredit,
                }),
                tile(ESRI + 'Canvas/World_Light_Gray_Reference/MapServer/tile/{z}/{y}/{x}', {
                    maxNativeZoom: 16, maxZoom: 19,
                }),
            ]);
        // Mapa vectorial (MapLibre GL) dentro de Leaflet: se descarga sólo cuando se usa y, mientras
        // llega, se muestra el mapa claro de respaldo. Sin WebGL o sin red el respaldo se conserva.
        const VectorBase = L.Layer.extend({
            initialize(styleUrl) { this._style = styleUrl; },
            onAdd(target) {
                this._placeholder = claro().addTo(target);
                loadVectorEngine().then(() => {
                    if (!this._placeholder) return;
                    this._gl = L.maplibreGL({ style: this._style }).addTo(target);
                    this._gl.getMaplibreMap().once('load', () => this._dropPlaceholder());
                }).catch(() => { /* se mantiene el mapa claro */ });
            },
            onRemove(target) {
                if (this._gl) target.removeLayer(this._gl);
                this._gl = null;
                this._dropPlaceholder();
            },
            _dropPlaceholder() {
                if (this._placeholder) this._placeholder.remove();
                this._placeholder = null;
            },
        });
        const OFM = 'https://tiles.openfreemap.org/styles/';
        const baseLayers = {
            'Moderno': new VectorBase(OFM + 'liberty'),
            'Minimalista': new VectorBase(OFM + 'positron'),
            'Calles': tile(ESRI + 'World_Street_Map/MapServer/tile/{z}/{y}/{x}', {
                maxNativeZoom: 19, maxZoom: 19, attribution: esriCredit,
            }),
            'Claro': claro(),
            'OpenStreetMap': tile('https://tile.openstreetmap.org/{z}/{x}/{y}.png', {
                maxZoom: 19,
                attribution: '&copy; <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a>',
            }),
        };
        let saved = null;
        try { saved = localStorage.getItem('spevb-basemap'); } catch { /* sin almacenamiento */ }
        baseLayers[baseLayers[saved] ? saved : modest ? 'Claro' : 'Moderno'].addTo(map);
        L.control.layers(baseLayers, null, { position: 'topright', collapsed: true }).addTo(map);
        let activeName = Object.keys(baseLayers).find((n) => map.hasLayer(baseLayers[n]));
        let automatic = false;       // cambios hechos por la app (p. ej. al quedarse sin red) no cambian la preferencia de la persona
        map.on('baselayerchange', (e) => {
            activeName = e.name;
            if (automatic) return;
            try { localStorage.setItem('spevb-basemap', e.name); } catch { /* sin almacenamiento */ }
        });
        map.activeBase = () => activeName;
        map.useBase = (name) => {
            const next = baseLayers[name];
            if (!next || name === activeName) return;
            automatic = true;
            Object.values(baseLayers).forEach((l) => { if (l !== next && map.hasLayer(l)) map.removeLayer(l); });
            next.addTo(map);
            map.fire('baselayerchange', { layer: next, name });
            automatic = false;
        };
        let reported = false;
        const onTileError = () => {
            if (reported) return;
            reported = true;
            const note = document.createElement('div');
            note.className = 'map-notice';
            note.textContent =
                'La cartografía no está disponible. El recorrido puede seguir consultándose.';
            document.getElementById(id).appendChild(note);
        };
        Object.values(baseLayers).forEach((layer) => {
            if (layer instanceof VectorBase) return;
            (layer.getLayers ? layer.getLayers() : [layer]).forEach((t) => t.on('tileerror', onTileError));
        });
        new ResizeObserver(() => map.invalidateSize()).observe(document.getElementById(id));
        // Si el tamaño cambia poco después de un encuadre (se cierra el teclado, aparece/desaparece la barra del navegador),
        // se repite el encuadre con las medidas finales para que el mapa no quede mal puesto.
        let refitTimer = null;
        map.on('resize', () => {
            clearTimeout(refitTimer);
            refitTimer = setTimeout(() => {
                const last = map._lastFit;
                if (last && Date.now() - last.at < 4000) { map.stop(); map.fitBounds(last.bounds, { ...fitPadding(map), maxZoom: last.maxZoom, animate: false }); }
            }, 280);
        });
        (window.Spevb.maps ||= {})[id] = map;   // acceso para diagnóstico y pruebas automáticas
        return map;
    }
    const endpoint = (kind) =>
        L.divIcon({
            className: 'endpoint-wrapper',
            iconSize: [34, 34],
            iconAnchor: [17, 17],
            html:
                '<div class="endpoint-marker ' +
                kind +
                '">' +
                (kind === 'start' ? 'A' : 'B') +
                '</div>',
        });
    return { api, text, toast, fitPadding, fitMap, compactLayout, download, geometry, feature, createMap, endpoint, center, cityBounds };
})();
