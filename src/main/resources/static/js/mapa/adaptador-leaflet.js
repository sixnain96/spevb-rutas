'use strict';
/* Adaptador Leaflet (+ MapLibre/OpenFreeMap) de la capa de mapa de SEVB. Es el proveedor ANTERIOR, conservado para poder comparar y
   revertir con MAP_PROVIDER=leaflet. Solo se carga cuando la configuración elige «leaflet»: en modo Google ni Leaflet ni MapLibre se
   descargan. Implementa el contrato de docs/MAPA-CAPA.md; fuera de este archivo ningún módulo usa `L.*`. */
(() => {
    const S = window.Spevb, M = S.Mapa, Geo = S.Geo;
    const { Emisor, Elemento, Grupo, contenidoSeguro } = M;
    const RAIZ_VENDOR = '/vendor/';
    let preparado = null;

    function preparar() {
        if (!preparado) {
            preparado = M.cargarCss(RAIZ_VENDOR + 'leaflet/leaflet.css');
            preparado = Promise.resolve(preparado).then(() => M.cargarScript(RAIZ_VENDOR + 'leaflet/leaflet.js'))
                .then(() => { if (!window.L) throw M.errorMapa('red', 'Leaflet no definió L'); });
            preparado.catch(() => { preparado = null; });
        }
        return preparado;
    }

    // ------------------------------------------------------------ motor vectorial (MapLibre dentro de Leaflet), solo si se usa
    let motorVectorial = null;
    function cargarMotorVectorial() {
        if (motorVectorial) return motorVectorial;
        motorVectorial = new Promise((resolve, reject) => {
            const probe = document.createElement('canvas');
            if (!(probe.getContext('webgl2') || probe.getContext('webgl'))) return reject(new Error('Sin WebGL'));
            const base = RAIZ_VENDOR + 'maplibre/';
            M.cargarCss(base + 'maplibre-gl.css');
            M.cargarScript(base + 'maplibre-gl-csp.js')
                .then(() => { window.maplibregl.setWorkerUrl(base + 'maplibre-gl-csp-worker.js'); return M.cargarScript(base + 'leaflet-maplibre-gl.js'); })
                .then(resolve, reject);
        });
        motorVectorial.catch(() => { motorVectorial = null; });
        return motorVectorial;
    }

    // ------------------------------------------------------------ conversiones
    const aCaja = (lb) => (lb && lb.isValid() ? { s: lb.getSouth(), w: lb.getWest(), n: lb.getNorth(), e: lb.getEast() } : null);
    const deCaja = (b) => L.latLngBounds([[b.s, b.w], [b.n, b.e]]);
    const aPar = (p) => (p ? [p.lat, p.lng] : null);
    /** Quita las claves undefined: Leaflet copia todas las opciones y un `color: undefined` dibujaría el trazo como «undefined». */
    const limpiar = (o) => { Object.keys(o).forEach((k) => o[k] === undefined && delete o[k]); return o; };

    // ------------------------------------------------------------ elementos
    /** Base de los elementos de Leaflet: reenvía tooltips, popups y eventos del objeto nativo `this.n`. */
    class ElementoL extends Elemento {
        constructor(mapa) { super(mapa); this.n = null; this._tip = false; }
        _enlazar(tipos) {
            this.n.on(tipos, (e) => {
                const original = e.originalEvent || null;
                // Arrastres: la posición es la del marcador; clics: la del puntero.
                const latlng = /^drag/.test(e.type) && this.getPosition ? this.getPosition() : (original ? this.mapa.eventLatLng(original) : null);
                this.emitir(e.type, { latlng: latlng || (e.latlng ? aPar(e.latlng) : null), originalEvent: original, target: this,
                    detener: () => { if (original) L.DomEvent.stopPropagation(e); } });
            });
        }
        colorPrincipal() { return (this.n.options && this.n.options.color) || null; }
        pesoPrincipal() { return (this.n.options && this.n.options.weight) || null; }
        colorPrincipal() { return (this.n.options && this.n.options.color) || null; }
        pesoPrincipal() { return (this.n.options && this.n.options.weight) || null; }
        _mostrar(m) { this.n.addTo(m.n); }
        _ocultar() { this.n.remove(); }
        _liberar() { this.n.off(); }
        setTooltip(contenido, opciones = {}) {
            if (contenido == null || contenido === '') { if (this._tip) { this.n.unbindTooltip(); this._tip = false; } return this; }
            const nodo = contenidoSeguro(contenido);
            this.textoTooltip = nodo.textContent;
            const o = { direction: opciones.direccion, offset: opciones.offset, sticky: !!opciones.pegajoso, permanent: !!opciones.permanente, className: opciones.clase };
            Object.keys(o).forEach((k) => o[k] === undefined && delete o[k]);
            if (this._tip) this.n.setTooltipContent(nodo); else { this.n.bindTooltip(nodo, o); this._tip = true; }
            return this;
        }
        openTooltip() { if (this._tip) this.n.openTooltip(); return this; }
        setPopup(contenido) { this.n.bindPopup(contenidoSeguro(contenido)); return this; }
        openPopup() { this.n.openPopup(); return this; }
        getElement() { return this.n.getElement ? this.n.getElement() : null; }
        setClase(clase, activa) { const el = this.getElement(); if (el) el.classList.toggle(clase, !!activa); return this; }
    }

    class MarcadorL extends ElementoL {
        constructor(mapa, ll, o = {}) {
            super(mapa); this.tipo = 'marcador';
            const size = o.size || [0, 0], anchor = o.anchor || [size[0] / 2, size[1] / 2];
            this.n = L.marker(ll, {
                icon: L.divIcon(limpiar({ className: (o.className || 'sevb-marker') + ' sevb-marcador', html: o.html || '', iconSize: size, iconAnchor: anchor, tooltipAnchor: o.tooltipAnchor })),
                zIndexOffset: o.z || 0,
                interactive: o.interactive !== false, keyboard: !!o.keyboard, draggable: !!o.draggable,
            });
            this._enlazar('click dblclick dragstart dragend');
            if (o.tooltip != null) this.setTooltip(o.tooltip, o.tooltipOpciones);
            if (o.popup != null) this.setPopup(o.popup);
        }
        setPosition(ll) { this.n.setLatLng(ll); return this; }
        getPosition() { return aPar(this.n.getLatLng()); }
        setArrastrable(on) { if (on) this.n.dragging && this.n.dragging.enable(); else this.n.dragging && this.n.dragging.disable(); return this; }
    }
    /** Punto de radio fijo en píxeles (circleMarker de Leaflet). */
    class PuntoL extends ElementoL {
        constructor(mapa, ll, o = {}) {
            super(mapa); this.tipo = 'punto';
            this.n = L.circleMarker(ll, limpiar({ radius: o.radius ?? 5, color: o.color, weight: o.weight, fillColor: o.fillColor, fillOpacity: o.fillOpacity, opacity: o.opacity, interactive: o.interactive !== false, className: o.className }));
            this._enlazar('click dblclick');
            if (o.tooltip != null) this.setTooltip(o.tooltip, o.tooltipOpciones);
        }
        setStyle(s) { this.n.setStyle(limpiar({ color: s.color, weight: s.weight, fillColor: s.fillColor, fillOpacity: s.fillOpacity, opacity: s.opacity })); if (s.radius != null) this.n.setRadius(s.radius); return this; }
        setPosition(ll) { this.n.setLatLng(ll); return this; }
        getPosition() { return aPar(this.n.getLatLng()); }
    }
    /** Círculo con radio en metros (precisión del GPS). */
    class CirculoL extends ElementoL {
        constructor(mapa, ll, radio, o = {}) {
            super(mapa); this.tipo = 'circulo';
            this.n = L.circle(ll, limpiar({ radius: radio, color: o.color, weight: o.weight, fillColor: o.fillColor, fillOpacity: o.fillOpacity, opacity: o.opacity, interactive: !!o.interactive }));
        }
        setPosition(ll) { this.n.setLatLng(ll); return this; }
        setRadius(r) { this.n.setRadius(r); return this; }
        getBounds() { return aCaja(this.n.getBounds()); }
    }
    class LineaL extends ElementoL {
        constructor(mapa, puntos, o = {}) {
            super(mapa); this.tipo = 'linea';
            const caminata = o.capa === 'caminata';
            this.n = L.polyline(puntos, limpiar({
                color: o.color, weight: o.weight, opacity: o.opacity, dashArray: o.dashArray || null, lineCap: o.lineCap, lineJoin: o.lineJoin,
                interactive: o.interactive !== false, smoothFactor: o.smoothFactor, className: caminata ? ((o.className ? o.className + ' ' : '') + 'walk-line') : o.className,
                renderer: caminata ? mapa._rendererCaminata() : (o.lienzo === 'propio' ? mapa._lienzoPropio() : undefined),
            }));
            this._enlazar('click dblclick');
            if (o.tooltip != null) this.setTooltip(o.tooltip, { pegajoso: o.tooltipPegajoso, ...(o.tooltipOpciones || {}) });
        }
        setPath(puntos) { this.n.setLatLngs(puntos); return this; }
        getPath() { return this.n.getLatLngs().map(aPar); }
        setStyle(s) { this.n.setStyle(limpiar({ color: s.color, weight: s.weight, opacity: s.opacity, dashArray: s.dashArray === undefined ? undefined : (s.dashArray || null) })); return this; }
        getBounds() { return aCaja(this.n.getBounds()); }
    }

    // ------------------------------------------------------------ el mapa
    class MapaLeaflet extends Emisor {
        constructor(contenedor, opciones, config) {
            super();
            this.proveedor = 'leaflet';
            this.el = contenedor;
            this.capacidades = { proveedor: 'leaflet', mapaSinConexion: true, capasBase: true, lineaAnimada: true, modoNocheCss: true };
            this._off = [];
            this._raiz = new Grupo(this); this._raiz.visible = true;
            this._creditosAbiertos = null;
            const centro = opciones.centro || S.center;
            this.n = L.map(contenedor, { preferCanvas: true, clickTolerance: 14, maxBounds: deCaja(S.cityBoundsCaja), maxBoundsViscosity: 0.45, minZoom: 11, maxZoom: 19, zoomControl: false })
                .setView(centro, opciones.zoom ?? 13);
            L.control.zoom({ position: 'bottomright', zoomInTitle: 'Acercar', zoomOutTitle: 'Alejar' }).addTo(this.n);
            this._capasBase(contenedor);
            const ro = new ResizeObserver(() => this.n.invalidateSize());
            ro.observe(contenedor); this._off.push(() => ro.disconnect());
            this._creditos();
            // Mientras la persona arrastra o hace zoom se pausan las animaciones decorativas: el celular dedica todo a mover el mapa.
            let calma = null;
            const mueve = () => { clearTimeout(calma); contenedor.classList.add('map-moving'); };
            const quieto = () => { clearTimeout(calma); calma = setTimeout(() => contenedor.classList.remove('map-moving'), 250); };
            this.n.on('dragstart zoomstart', mueve); this.n.on('dragend zoomend', quieto);
            ['click', 'dblclick'].forEach((t) => this.n.on(t, (e) => this.emitir(t, { latlng: aPar(e.latlng), originalEvent: e.originalEvent })));
            ['moveend', 'zoomend', 'zoomstart', 'dragstart', 'dragend', 'resize'].forEach((t) => this.n.on(t, (e) => this.emitir(t, { latlng: null, originalEvent: e.originalEvent || null })));
        }
        // ---- capas base y créditos (código movido desde common.js; comportamiento idéntico)
        _capasBase(contenedor) {
            const link = navigator.connection || {};
            const modest = !!link.saveData || /^(slow-2g|2g|3g)$/.test(link.effectiveType || '')
                || (navigator.deviceMemory || 4) <= 2 || (navigator.hardwareConcurrency || 8) <= 3;
            const ESRI = 'https://server.arcgisonline.com/ArcGIS/rest/services/';
            const esriCredit = 'Mapa &copy; <a href="https://www.esri.com">Esri</a>, HERE, Garmin y colaboradores de OpenStreetMap';
            const tile = (url, options) => L.tileLayer(url, { crossOrigin: true, keepBuffer: modest ? 1 : 3, updateWhenIdle: modest, updateWhenZooming: false, ...options });
            const claro = () => L.layerGroup([
                tile(ESRI + 'Canvas/World_Light_Gray_Base/MapServer/tile/{z}/{y}/{x}', { maxNativeZoom: 16, maxZoom: 19, attribution: esriCredit }),
                tile(ESRI + 'Canvas/World_Light_Gray_Reference/MapServer/tile/{z}/{y}/{x}', { maxNativeZoom: 16, maxZoom: 19 }),
            ]);
            const mapa = this;
            const VectorBase = L.Layer.extend({
                initialize(styleUrl) { this._style = styleUrl; },
                onAdd(target) {
                    this._activa = true;
                    // El ráster de respaldo solo aparece si el vectorial tarda: así no se descargan dos mapas a la vez.
                    this._timer = setTimeout(() => this._showPlaceholder(target), 1500);
                    cargarMotorVectorial().then(() => {
                        if (!this._activa) return;
                        this._gl = L.maplibreGL({
                            style: this._style, pixelRatio: Math.min(window.devicePixelRatio || 1, modest ? 1.5 : 2),
                            fadeDuration: 100, antialias: false, refreshExpiredTiles: false, updateInterval: modest ? 60 : 32,
                        }).addTo(target);
                        this._gl.getMaplibreMap().once('load', () => { clearTimeout(this._timer); this._dropPlaceholder(); });
                    }).catch(() => { if (this._activa) this._showPlaceholder(target); });
                },
                onRemove(target) {
                    this._activa = false; clearTimeout(this._timer);
                    if (this._gl) target.removeLayer(this._gl);
                    this._gl = null; this._dropPlaceholder();
                },
                _showPlaceholder(target) {
                    if (!this._activa || this._placeholder || (this._gl && this._gl.getMaplibreMap().loaded())) return;
                    this._placeholder = claro().addTo(target);
                },
                _dropPlaceholder() { if (this._placeholder) this._placeholder.remove(); this._placeholder = null; },
            });
            const OFM = 'https://tiles.openfreemap.org/styles/';
            const bases = {
                'Moderno': new VectorBase(OFM + 'liberty'),
                'Minimalista': new VectorBase(OFM + 'positron'),
                'Calles': tile(ESRI + 'World_Street_Map/MapServer/tile/{z}/{y}/{x}', { maxNativeZoom: 19, maxZoom: 19, attribution: esriCredit }),
                'Claro': claro(),
                'OpenStreetMap': tile('https://tile.openstreetmap.org/{z}/{x}/{y}.png', { maxZoom: 19, attribution: '&copy; <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a>' }),
            };
            // Mapa único: el «Moderno». Los demás estilos quedan solo como respaldo interno (usarBase), p. ej. sin conexión.
            bases.Moderno.addTo(this.n);
            this._bases = bases;
            this._baseActiva = 'Moderno';
            let avisado = false;
            const alErrorDeTesela = () => {
                if (avisado) return; avisado = true;
                const nota = document.createElement('div');
                nota.className = 'map-notice';
                nota.textContent = 'La cartografía no está disponible. El recorrido puede seguir consultándose.';
                contenedor.appendChild(nota);
            };
            Object.values(bases).forEach((capa) => {
                if (capa instanceof VectorBase) return;
                (capa.getLayers ? capa.getLayers() : [capa]).forEach((t) => t.on('tileerror', alErrorDeTesela));
            });
        }
        baseActiva() { return this._baseActiva; }
        usarBase(nombre) {
            const siguiente = this._bases[nombre];
            if (!siguiente || nombre === this._baseActiva) return;
            Object.values(this._bases).forEach((l) => { if (l !== siguiente && this.n.hasLayer(l)) this.n.removeLayer(l); });
            siguiente.addTo(this.n);
            this._baseActiva = nombre;
        }
        _creditos() {
            // Créditos compactos: solo «Leaflet» y un ⓘ; al tocar se despliegan los demás. Siguen a un toque de distancia (licencias de los datos).
            const credits = this.n.attributionControl;
            if (!credits) return;
            credits.setPrefix('<a href="https://leafletjs.com" target="_blank" rel="noopener">Leaflet</a>');
            credits._update = function () {
                if (!this._map) return;
                const list = Object.keys(this._attributions).filter((k) => this._attributions[k]);
                this._container.innerHTML = this.options.prefix + (list.length
                    ? ' <span class="attr-toggle" aria-hidden="true">ⓘ</span><span class="attr-more"> · ' + list.join(', ') + '</span>' : '');
            };
            credits._update();
            const box = credits.getContainer();
            box.setAttribute('role', 'button'); box.setAttribute('aria-label', 'Créditos del mapa');
            let cierre = null;
            const abrir = (open) => { box.classList.toggle('open', open); clearTimeout(cierre); if (open) cierre = setTimeout(() => abrir(false), 8000); };
            box.addEventListener('click', (e) => {
                const enlace = e.target.closest && e.target.closest('a');
                if (enlace && box.classList.contains('open')) return;
                e.preventDefault(); abrir(!box.classList.contains('open'));
            });
            this.n.on('click dragstart zoomstart', () => abrir(false));
        }
        /** Lienzo (canvas) aparte para una línea que se repinta muy seguido (la estela del bus): no obliga a redibujar las demás capas. */
        _lienzoPropio() { return this._lienzo || (this._lienzo = L.canvas({ padding: 0.2 })); }
        _rendererCaminata() {
            // Las líneas punteadas de caminata se dibujan en SVG (el mapa usa canvas, que no admite animación CSS) y se mueven muy despacio.
            if (!this._walk) { this.n.createPane('walkPane').style.zIndex = 450; this._walk = L.svg({ pane: 'walkPane', padding: 0.3 }); }
            return this._walk;
        }
        // ---- vista
        setView(ll, zoom, o = {}) {
            const z = zoom == null ? this.n.getZoom() : zoom;
            // `padding` = márgenes tapados {top,left,bottom,right}: la posición queda en el centro del área libre, no del contenedor.
            this.n.setView(o.padding ? Geo.centroConMargen(ll, z, o.padding) : ll, z, { animate: o.animate !== false });
            return this;
        }
        panTo(ll, o = {}) { this.n.panTo(ll, { animate: o.animate !== false }); return this; }
        fitBounds(b, o = {}) {
            if (!Geo.esValida(b)) return this;
            const p = o.padding, op = { maxZoom: o.maxZoom, animate: o.animate !== false };
            if (typeof p === 'number') op.padding = [p, p];
            else if (p) { op.paddingTopLeft = [p.left || 0, p.top || 0]; op.paddingBottomRight = [p.right || 0, p.bottom || 0]; }
            this.n.fitBounds(deCaja(b), op);
            return this;
        }
        stop() { this.n.stop(); return this; }
        getZoom() { return this.n.getZoom(); }
        getCenter() { return aPar(this.n.getCenter()); }
        getBounds() { return aCaja(this.n.getBounds()); }
        getSize() { const s = this.n.getSize(); return { x: s.x, y: s.y }; }
        resize() { this.n.invalidateSize(); return this; }
        setDobleClicZoom(on) { on ? this.n.doubleClickZoom.enable() : this.n.doubleClickZoom.disable(); return this; }
        // ---- proyección
        toPoint(ll) { const p = this.n.latLngToContainerPoint(ll); return { x: p.x, y: p.y }; }
        toLatLng(pt) { return aPar(this.n.containerPointToLatLng([pt.x, pt.y])); }
        eventLatLng(ev) { return ev ? aPar(this.n.mouseEventToLatLng(ev)) : null; }
        // ---- elementos
        marker(ll, o) { return new MarcadorL(this, ll, o); }
        dot(ll, o) { return new PuntoL(this, ll, o); }
        circle(ll, radio, o) { return new CirculoL(this, ll, radio, o); }
        polyline(puntos, o) { return new LineaL(this, puntos, o); }
        group() { return new Grupo(this); }
        /** Elementos visibles del mapa (neutro respecto del proveedor): para diagnóstico y pruebas. */
        elementos() { return this._raiz.elementos(); }
        _meter(el) { this._raiz._meter(el); }
        _sacar(el) { this._raiz._sacar(el); }
        add(el) { return el.addTo(this); }
        quitar(el) { this._raiz.quitar(el); return this; }
        has(el) { return this._raiz.has(el); }
        addControl(el, posicion = 'topright') {
            el.classList.add('mapa-control');
            const Ctrl = L.Control.extend({ onAdd() { return el; } });
            const ctrl = new Ctrl({ position: posicion }).addTo(this.n);
            L.DomEvent.disableClickPropagation(el); L.DomEvent.disableScrollPropagation(el);
            return () => ctrl.remove();
        }
        // ---- eventos del mapa (emisor propio; los de Leaflet se reenvían en el constructor)
        destroy() {
            this._raiz.clear();
            this._off.forEach((f) => f());
            this.n.remove();
            this.limpiarEventos();
        }
    }

    // La caja de la ciudad en el formato de la capa (la define common.js con los límites de SEVB).
    M.registrar('leaflet', {
        async crear(contenedor, opciones, config) {
            await preparar();
            S.cityBoundsCaja = S.cityBoundsCaja || Geo.desdePares(S.cityBounds);
            return new MapaLeaflet(contenedor, opciones, config);
        },
    });
})();
