'use strict';
window.Spevb = Object.assign(window.Spevb || {}, (() => {
    const Geo = window.Spevb.Geo, Mapa = window.Spevb.Mapa;      // js/mapa/geo.js y js/mapa/nucleo.js se cargan antes
    const center = [4.142, -73.6266];
    const cityBounds = [[3.95, -73.85], [4.35, -73.45]];
    const cityBoundsCaja = Geo.desdePares(cityBounds);
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
    /** Píxeles del borde inferior del mapa que tapa el panel del celular. Con Google el contenedor del mapa termina justo encima del
        panel (así el logotipo y los créditos de Google quedan siempre visibles), por lo que no hay nada tapado dentro del mapa. */
    function coberturaPanel(map) {
        if (!compactLayout() || (map && map.capacidades && map.capacidades.mapaSobrePanel)) return 0;
        return parseFloat(getComputedStyle(document.documentElement).getPropertyValue('--sheet-visible')) || 0;
    }

    /** Márgenes {top, left, bottom, right} en píxeles para encuadrar sin que tapen el panel, la tarjeta del viaje ni los botones. */
    function fitPadding(map) {
        const size = map.getSize();
        if (compactLayout()) {
            const cover = coberturaPanel(map);
            // Siempre debe quedar un área útil (≥ 140 px de alto): con el teclado abierto o en pantallas bajas un margen
            // mayor que el mapa daría un zoom absurdo.
            const bottom = Math.max(24, Math.min(cover + 40, size.y - 60 - 140));
            return { top: 60, left: 30, bottom, right: 72 };
        }
        // Escritorio y celular de lado: la tarjeta flotante ocupa la izquierda; se encuadra a su derecha.
        const card = document.getElementById('sheet');
        const ancho = card ? Math.round(card.getBoundingClientRect().right + 24) : 440;
        const left = Math.min(ancho, Math.max(30, size.x - 70 - 200));
        return { top: 60, left, bottom: size.y < 520 ? 70 : 130, right: 70 };
    }
    /** Márgenes del área que SÍ se ve del mapa, teniendo en cuenta además la tarjeta del viaje (si está visible y movida o no): su posición real
        en pantalla, no una suposición. Los márgenes de fitPadding (panel, botones) se mantienen como mínimo. */
    function areaLibre(map) {
        const p = fitPadding(map);
        const tarjeta = document.getElementById('viajeCard');
        if (tarjeta && !tarjeta.hidden && tarjeta.getClientRects().length) {
            const m = map.el.getBoundingClientRect(), c = tarjeta.getBoundingClientRect();
            if (c.right > m.left && c.left < m.right && c.bottom > m.top && c.top < m.bottom) {
                if ((c.top + c.bottom) / 2 > (m.top + m.bottom) / 2) p.bottom = Math.max(p.bottom, Math.round(m.bottom - c.top + 12));
                else p.top = Math.max(p.top, Math.round(c.bottom - m.top + 12));
            }
        }
        // Siempre debe quedar un área útil: si los márgenes se comen el mapa, se reducen.
        const t = map.getSize();
        if (p.top + p.bottom > t.y - 100) { const sobra = p.top + p.bottom - (t.y - 100); const k = 1 - sobra / (p.top + p.bottom); p.top = Math.round(p.top * k); p.bottom = Math.round(p.bottom * k); }
        return p;
    }
    /** Centra una posición en el área libre del mapa (no debajo de la tarjeta ni del panel). Es un movimiento PROGRAMÁTICO. */
    function centrarEn(map, ll, zoom, opciones = {}) {
        map.setView(ll, zoom == null ? map.getZoom() : zoom, { animate: opciones.animate !== false, padding: areaLibre(map) });
    }
    /** Encuadra una caja {s,w,n,e} una sola vez por vez: cancela la animación anterior y, si en los siguientes segundos cambia el
        tamaño del mapa (teclado que se cierra, barra del navegador), vuelve a encuadrar con las medidas nuevas. */
    function fitMap(map, bounds, { maxZoom = 17 } = {}) {
        if (!Geo.esValida(bounds)) return;
        map.stop();
        map.fitBounds(bounds, { padding: fitPadding(map), maxZoom });
        map._ultimoEncuadre = { bounds, maxZoom, at: Date.now() };
    }
    /** Comportamiento común que se instala en TODO mapa recién creado (sin importar el proveedor). */
    function instalarComunes(map) {
        let refit = null;
        map.on('resize', () => {
            clearTimeout(refit);
            refit = setTimeout(() => {
                const last = map._ultimoEncuadre;
                if (last && Date.now() - last.at < 4000) { map.stop(); map.fitBounds(last.bounds, { padding: fitPadding(map), maxZoom: last.maxZoom, animate: false }); }
            }, 280);
        });
    }
    Mapa.despuesDeCrear = instalarComunes;

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
    /** Crea el mapa de la página (promesa). Para páginas completas usa Spevb.conMapa (incluye estado de error recuperable y reintento). */
    const createMap = (id, opciones) => Mapa.crear(id, opciones);
    /** Opciones de marcador para el inicio («A») y el fin («B») de una ruta o los extremos de un recorrido. */
    const endpoint = (kind) => ({
        className: 'endpoint-wrapper', size: [34, 34], anchor: [17, 17],
        html: '<div class="endpoint-marker ' + kind + '">' + (kind === 'start' ? 'A' : 'B') + '</div>',
    });
    return { api, text, toast, fitPadding, areaLibre, centrarEn, fitMap, compactLayout, COMPACT_LAYOUT, coberturaPanel, download, geometry, feature, createMap, endpoint, center, cityBounds, cityBoundsCaja };
})());
