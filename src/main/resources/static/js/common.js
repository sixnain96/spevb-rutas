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
                headers: { Accept: 'application/json', 'ngrok-skip-browser-warning': '1', ...init.headers },      // el segundo evita la página de aviso de ngrok (gratis) en las llamadas a la API
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
    /** Contenedor de avisos. `ubicacion: 'map-controls'` usa un contenedor aparte (abajo a la izquierda del mapa en celular); el resto, el de siempre. */
    function contenedorToasts(ubicacion) {
        const id = ubicacion === 'map-controls' ? 'spevbMapToasts' : 'spevbToasts';
        let box = document.getElementById(id);
        if (!box) {
            box = document.createElement('div');
            box.id = id;
            if (ubicacion === 'map-controls') box.className = 'map-action-toast-container';
            document.body.append(box);
        }
        return box;
    }
    let observadorBus = null;
    /** El aviso de acción sobre el mapa va justo encima del panel de controles del bus cuando este está visible (si no, sobre el panel inferior). */
    function ubicarToastMapa() {
        const box = document.getElementById('spevbMapToasts');
        if (!box) return;
        const bus = document.querySelector('.bus-panel');
        if (bus && window.ResizeObserver && !observadorBus) { observadorBus = new ResizeObserver(ubicarToastMapa); observadorBus.observe(bus); }
        if (bus && !bus.hidden && bus.getClientRects().length) {
            const r = bus.getBoundingClientRect();
            box.style.setProperty('--map-toast-bottom', Math.max(0, Math.round(window.innerHeight - r.top + 8)) + 'px');
        } else box.style.removeProperty('--map-toast-bottom');
    }
    window.addEventListener('resize', ubicarToastMapa);
    // El panel inferior y los controles del bus se mueven con transiciones: al terminar se vuelve a medir para quedar justo encima.
    document.addEventListener('transitionend', () => { if (document.querySelector('#spevbMapToasts .spevb-toast')) ubicarToastMapa(); }, true);
    window.addEventListener('spevb-ajustes', ubicarToastMapa);
    function toast(message, { tipo = 'info', ms = 6000, clave = null, accion = null, ubicacion = null } = {}) {
        const box = contenedorToasts(ubicacion);
        let item = clave ? toasts.get(clave) : null;
        if (!item) {
            const el = document.createElement('div');
            const label = document.createElement('span');
            el.append(label);
            el.addEventListener('click', () => { if (!item.fijo) cerrar(clave ? toasts.get(clave) : item); });
            box.append(el);
            item = { el, label, timer: null, clave, fijo: false };
            if (clave) toasts.set(clave, item);
        }
        // Al reutilizar un aviso por su clave se actualiza TODO (clases, ubicación, acciones, temporizador): no queda nada del estado anterior.
        item.fijo = ubicacion === 'map-controls';              // un aviso de acción sobre el mapa no se cierra al tocar su texto (solo con sus botones)
        item.el.className = 'spevb-toast ' + tipo + (ubicacion === 'map-controls' ? ' map-action-toast' : '');
        if (item.el.parentNode !== box) box.append(item.el);
        item.el.setAttribute('role', tipo === 'error' ? 'alert' : 'status');
        item.label.textContent = message;
        item.el.querySelectorAll('.toast-actions').forEach((b) => b.remove());
        const acciones = document.createElement('span');
        acciones.className = 'toast-actions';
        [].concat(accion || []).forEach((a) => {
            const button = document.createElement('button');
            button.type = 'button';
            button.textContent = a.texto;
            if (a.clase) button.className = a.clase;
            if (a.etiqueta) button.setAttribute('aria-label', a.etiqueta);
            button.addEventListener('click', (e) => { e.stopPropagation(); a.alHacer(); if (a.cerrar !== false) cerrar(item); });
            acciones.append(button);
        });
        if (acciones.childElementCount) item.el.append(acciones);
        if (ubicacion === 'map-controls') ubicarToastMapa();
        clearTimeout(item.timer);
        if (ms > 0) item.timer = setTimeout(() => cerrar(item), ms);
        return item;
    }
    function cerrar(item) {
        if (!item) return;
        clearTimeout(item.timer);
        item.el.remove();
        item.el.className = 'spevb-toast';
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
    // ---- Mapa base de Google (4.14): Maps JavaScript API como CAPA BASE dentro de Leaflet. Leaflet sigue gobernando gestos, rutas, caminatas punteadas, buses,
    // marcadores y toda la optimización existente; el mapa de Google solo dibuja el fondo y se sincroniza con el centro y el zoom de Leaflet (sin gestos propios).
    // La clave del navegador (distinta de la del servidor) llega de /api/mapa/config. Sin clave, sin red o si Google falla, se usa el mapa de respaldo (OpenFreeMap).
    let googleCarga = null;
    function cargarGoogleMaps(key) {
        if (window.google && window.google.maps && window.google.maps.Map) return Promise.resolve();
        if (googleCarga) return googleCarga;
        googleCarga = new Promise((resolve, reject) => {
            const fallar = (m) => { clearTimeout(t); googleCarga = null; reject(new Error(m)); };
            const t = setTimeout(() => fallar('Google Maps tardó demasiado'), 9000);
            window.__spevbGoogleListo = () => { clearTimeout(t); resolve(); };
            window.gm_authFailure = () => { window.dispatchEvent(new CustomEvent('spevb-google-mapa-fallo', { detail: 'clave' })); };      // clave inválida o sin permiso para este dominio
            const sc = document.createElement('script');
            sc.src = 'https://maps.googleapis.com/maps/api/js?key=' + encodeURIComponent(key) + '&v=weekly&loading=async&language=es&region=CO&callback=__spevbGoogleListo';
            sc.async = true; sc.onerror = () => fallar('No se pudo cargar Google Maps');
            document.head.appendChild(sc);
        });
        return googleCarga;
    }
    const googleBase = () => L.Layer.extend({
        initialize(key) { this._key = key; },
        onAdd(target) {
            this._activa = true; this._target = target;
            const div = this._div = document.createElement('div');
            div.className = 'google-base'; div.setAttribute('aria-hidden', 'true');
            target.getContainer().insertBefore(div, target.getContainer().firstChild);
            // La animación de zoom por CSS de Leaflet movería marcadores y rutas mientras el fondo de Google salta al final: se apaga mientras Google es el fondo.
            this._zoomAnim = target.options.zoomAnimation; target.options.zoomAnimation = false; target._zoomAnimated = false;
            this._credito = ' <a href="https://www.google.com/intl/es/help/terms_maps/" target="_blank" rel="noopener">Mapa © Google · Condiciones</a>';
            if (target.attributionControl) target.attributionControl.addAttribution(this._credito);
            cargarGoogleMaps(this._key).then(() => {
                if (!this._activa) return;
                const c = target.getCenter();
                this._g = new window.google.maps.Map(div, {
                    center: { lat: c.lat, lng: c.lng }, zoom: target.getZoom(), disableDefaultUI: true, gestureHandling: 'none', keyboardShortcuts: false,
                    clickableIcons: false, draggable: false, scrollwheel: false, disableDoubleClickZoom: true, backgroundColor: '#e4e9de',
                });
                target.on('move zoom viewreset resize', this._sync, this);
                this._sync();
                this.fire('load');
            }).catch(() => { if (this._activa) this.fire('fallo'); });
        },
        onRemove(target) {
            this._activa = false;
            target.off('move zoom viewreset resize', this._sync, this);
            if (target.attributionControl) target.attributionControl.removeAttribution(this._credito);
            if (this._div) this._div.remove();
            this._g = null;
            target.options.zoomAnimation = this._zoomAnim;
            target._zoomAnimated = !!(L.DomUtil.TRANSITION && L.Browser.any3d && !L.Browser.mobileOpera && this._zoomAnim);
        },
        _sync() {
            const g = this._g; if (!g) return;
            const c = this._target.getCenter(), z = this._target.getZoom();
            if (typeof g.moveCamera === 'function') g.moveCamera({ center: { lat: c.lat, lng: c.lng }, zoom: z });
            else { g.setCenter({ lat: c.lat, lng: c.lng }); g.setZoom(z); }
        },
    });

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
                this._activa = true;
                // El mapa ráster de respaldo solo aparece si el vectorial tarda: así no se descargan dos mapas a la vez
                // (ahorra datos y trabajo al celular). Si el motor vectorial falla, el respaldo entra de inmediato.
                this._timer = setTimeout(() => this._showPlaceholder(target), 1500);
                loadVectorEngine().then(() => {
                    if (!this._activa) return;
                    this._gl = L.maplibreGL({
                        style: this._style,
                        // Una pantalla de 3× no necesita 9 píxeles por cada uno: con 2× se ve igual de nítido y pinta menos.
                        pixelRatio: Math.min(window.devicePixelRatio || 1, modest ? 1.5 : 2),
                        fadeDuration: 100,
                        antialias: false,
                        refreshExpiredTiles: false,
                        updateInterval: modest ? 60 : 32,
                    }).addTo(target);
                    this._gl.getMaplibreMap().once('load', () => { clearTimeout(this._timer); this._dropPlaceholder(); });
                }).catch(() => { if (this._activa) this._showPlaceholder(target); });
            },
            onRemove(target) {
                this._activa = false;
                clearTimeout(this._timer);
                if (this._gl) target.removeLayer(this._gl);
                this._gl = null;
                this._dropPlaceholder();
            },
            _showPlaceholder(target) {
                if (!this._activa || this._placeholder || (this._gl && this._gl.getMaplibreMap().loaded())) return;
                this._placeholder = claro().addTo(target);
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
        // Mapa único para todas las personas: el «Moderno». Ya no hay selector de capas (ocupaba espacio en pantalla) y se
        // ignora la preferencia que hubiera guardado antes. Los demás estilos quedan solo como respaldo interno (useBase).
        let activeName = null;
        const activar = (name) => { baseLayers[name].addTo(map); activeName = name; };
        if (document.body.classList.contains('home-sevb') && navigator.onLine !== false) {
            // Página del pasajero: SIEMPRE Google si hay clave. Se espera brevemente la configuración (≤ 1,5 s) para no descargar el mapa de respaldo en vano.
            let decidido = false;
            const respaldo = () => { if (decidido) return; decidido = true; activar('Moderno'); };
            const espera = setTimeout(respaldo, 1500);
            api('/api/mapa/config').then((cfg) => {
                clearTimeout(espera);
                if (decidido) return;
                decidido = true;
                if (!cfg || !cfg.googleMapsKey) { activar('Moderno'); return; }
                const G = googleBase();
                const capa = baseLayers.Google = new G(cfg.googleMapsKey);
                let cambiado = false;
                const volver = () => {
                    if (cambiado || !map.hasLayer(capa)) return;
                    cambiado = true;
                    map.removeLayer(capa); activar('Moderno'); map.fire('baselayerchange', { layer: baseLayers.Moderno, name: 'Moderno' });
                };
                capa.on('fallo', volver);
                window.addEventListener('spevb-google-mapa-fallo', volver);
                activar('Google');
            }).catch(() => { clearTimeout(espera); respaldo(); });
        } else activar('Moderno');
        let automatic = false;       // cambios hechos por la app (p. ej. al quedarse sin red) no cambian la preferencia de la persona
        map.on('baselayerchange', (e) => {
            activeName = e.name;
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
        // Créditos compactos: solo «Leaflet» y un ⓘ; al tocar se despliegan los demás (OpenFreeMap, OpenStreetMap, Esri…).
        // Siguen a un toque de distancia, como exigen las licencias de los datos.
        const credits = map.attributionControl;
        if (credits) {
            credits.setPrefix('<a href="https://leafletjs.com" target="_blank" rel="noopener">Leaflet</a>');
            credits._update = function () {
                if (!this._map) return;
                const list = Object.keys(this._attributions).filter((k) => this._attributions[k]);
                this._container.innerHTML = this.options.prefix + (list.length
                    ? ' <span class="attr-toggle" aria-hidden="true">ⓘ</span><span class="attr-more"> · ' + list.join(', ') + '</span>' : '');
            };
            credits._update();
            const box = credits.getContainer();
            box.setAttribute('role', 'button');
            box.setAttribute('aria-label', 'Créditos del mapa');
            let closeTimer = null;
            const setOpen = (open) => { box.classList.toggle('open', open); clearTimeout(closeTimer); if (open) closeTimer = setTimeout(() => setOpen(false), 8000); };
            box.addEventListener('click', (e) => {
                const link = e.target.closest && e.target.closest('a');
                if (link && box.classList.contains('open')) return;          // ya desplegados: los enlaces funcionan
                e.preventDefault();
                setOpen(!box.classList.contains('open'));
            });
            map.on('click dragstart zoomstart', () => setOpen(false));
        }
        // Mientras la persona arrastra o hace zoom se pausan las animaciones decorativas (líneas punteadas, halo del bus):
        // el celular dedica todo a mover el mapa y no se entrecorta.
        const mapBox = map.getContainer();
        let calmTimer = null;
        const moving = () => { clearTimeout(calmTimer); mapBox.classList.add('map-moving'); };
        const still = () => { clearTimeout(calmTimer); calmTimer = setTimeout(() => mapBox.classList.remove('map-moving'), 250); };
        map.on('dragstart zoomstart', moving);
        map.on('dragend zoomend', still);

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
