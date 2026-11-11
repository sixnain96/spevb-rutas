/* SDK de Google Maps SIMULADO para pruebas del adaptador (e2e/mapa_adaptadores_e2e.py). NO es Google: no dibuja nada ni demuestra que una clave,
   la CSP, el Map ID o los gestos del mapa real funcionen. Solo permite comprobar la lógica de SEVB: carga única del SDK, importLibrary,
   conversiones de coordenadas, ciclo de vida de elementos y listeners, clasificación de errores y reintento.
   Modo (window.__gfake_modo, lo antepone la prueba): 'ok' | 'authfail' | 'sinmapid'. */
(() => {
    const modo = window.__gfake_modo || 'ok';
    const Geo = window.Spevb.Geo;
    const log = window.__gfake = { tiles: 'ok', modo, importaciones: [], map: null, marcadores: [], polilineas: [], circulos: [], info: null, escuchas: 0, mapas: 0, opciones: null };
    class MVCArray { constructor(a) { this.a = a || []; } push(x) { this.a.push(x); return this.a.length; } getArray() { return this.a; } removeAt(i) { this.a.splice(i, 1); } getLength() { return this.a.length; } }
    class Base {
        constructor() { this._l = {}; }
        addListener(t, fn) { (this._l[t] ||= new Set()).add(fn); log.escuchas++; return { remove: () => { if (this._l[t] && this._l[t].delete(fn)) log.escuchas--; } }; }
        _emitir(t, ev) { [...(this._l[t] || [])].forEach((f) => f(ev)); }
    }
    class LatLng { constructor(a, b) { this._a = a; this._b = b; } lat() { return this._a; } lng() { return this._b; } toJSON() { return { lat: this._a, lng: this._b }; } }
    class Point { constructor(x, y) { this.x = x; this.y = y; } }
    const lit = (c) => (c instanceof LatLng ? { lat: c.lat(), lng: c.lng() } : (typeof c.lat === 'function' ? { lat: c.lat(), lng: c.lng() } : { lat: c.lat, lng: c.lng }));
    const POS = { TOP_LEFT: 1, TOP_RIGHT: 3, BOTTOM_LEFT: 10, BOTTOM_RIGHT: 12, RIGHT_BOTTOM: 13 };
    class GMap extends Base {
        constructor(div, o) {
            super();
            this.div = div; this.o = { ...o }; this._c = lit(o.center); this._z = o.zoom; this.controls = {};
            // Como el SDK real: el control se monta DENTRO del mapa y Google le escribe en línea `position:absolute` y el borde de su esquina
            // (`right:0; top:0` en TOP_RIGHT). Eso estiraba el panel del bus con el `bottom` de nuestro CSS y tapaba el mapa (4.13.5).
            Object.values(POS).forEach((v) => {
                const l = new MVCArray();
                const empujar = l.push.bind(l), quitar = l.removeAt.bind(l);
                l.push = (el) => {
                    const a = v === POS.TOP_RIGHT ? 'right: 0px; top: 0px;' : v === POS.TOP_LEFT ? 'left: 0px; top: 0px;' : v === POS.BOTTOM_LEFT ? 'left: 0px; bottom: 0px;' : 'right: 0px; bottom: 0px;';
                    el.style.cssText += 'position: absolute; ' + a;
                    const caja = document.createElement('div'); caja.appendChild(el); div.appendChild(caja);
                    return empujar(el);
                };
                l.removeAt = (i) => { const el = l.a[i]; quitar(i); if (el && el.parentNode) el.parentNode.remove(); };
                this.controls[v] = l;
            });
            log.mapas++; log.map = this; log.opciones = o;
            div.dataset.fakeGoogle = '1';
            setTimeout(() => {
                if (modo === 'authfail') {
                    console.error('Google Maps JavaScript API error: RefererNotAllowedMapError\nhttps://developers.google.com/maps/documentation/javascript/error-messages#referer-not-allowed-map-error');
                    window.gm_authFailure && window.gm_authFailure();
                }
                this._emitir('mapcapabilities_changed', {});
                this._emitir('idle', {});
                if (log.tiles !== 'sin') this._emitir('tilesloaded', {});
            }, 15);
        }
        getZoom() { return this._z; } getCenter() { return new LatLng(this._c.lat, this._c.lng); }
        // 4.14: la cámara emite también bounds_changed y (si log.tiles !== 'sin') tilesloaded, como el SDK real cuando termina de cargar las teselas visibles.
        _camara() { setTimeout(() => { this._emitir('bounds_changed', {}); this._emitir('idle', {}); if (log.tiles !== 'sin') this._emitir('tilesloaded', {}); }, 0); }
        moveCamera(c) { if (c.center) this._c = lit(c.center); if (c.zoom != null) this._z = c.zoom; this._camara(); }
        panTo(c) { this._c = lit(c); this._camara(); }
        setCenter(c) { this._c = lit(c); this._camara(); }
        setZoom(z) { this._z = z; this._camara(); }
        panBy() { this._camara(); }
        setOptions(o) { Object.assign(this.o, o); }
        getMapCapabilities() { return { isAdvancedMarkersAvailable: modo !== 'sinmapid' }; }
        _w() { const e = 256 * 2 ** this._z; return { e, c: Geo.aMundo([this._c.lat, this._c.lng]) }; }
        getBounds() {
            const { e, c } = this._w(), w = this.div.clientWidth, h = this.div.clientHeight;
            const [n, o] = Geo.deMundo(c[0] - w / 2 / e, c[1] - h / 2 / e), [s, es] = Geo.deMundo(c[0] + w / 2 / e, c[1] + h / 2 / e);
            return { toJSON: () => ({ south: s, west: o, north: n, east: es }) };
        }
    }
    class OverlayView {
        setMap(m) { this._m = m; if (m) { this.onAdd && this.onAdd(); setTimeout(() => this.draw && this.draw(), 5); } else this.onRemove && this.onRemove(); }
        getProjection() {
            const m = this._m;
            const conv = (ll) => { const { e, c } = m._w(), w = Geo.aMundo([ll.lat, ll.lng]); return new Point(m.div.clientWidth / 2 + (w[0] - c[0]) * e, m.div.clientHeight / 2 + (w[1] - c[1]) * e); };
            return {
                fromLatLngToContainerPixel: (ll) => conv(lit(ll)),
                fromContainerPixelToLatLng: (pt) => { const { e, c } = m._w(); const [lat, lng] = Geo.deMundo(c[0] + (pt.x - m.div.clientWidth / 2) / e, c[1] + (pt.y - m.div.clientHeight / 2) / e); return new LatLng(lat, lng); },
            };
        }
    }
    class Marcador extends Base {
        constructor(o) {
            super();
            this._map = null; this._dom = {}; this.children = []; Object.assign(this, { position: o.position, zIndex: o.zIndex, gmpClickable: o.gmpClickable, gmpDraggable: o.gmpDraggable });
            log.marcadores.push(this);
            if (o.map) this.map = o.map;
        }
        set map(m) { this._map = m; }
        get map() { return this._map; }
        append(el) { this.children.push(el); }
        addEventListener(t, f) { (this._dom[t] ||= new Set()).add(f); log.escuchas++; }
        removeEventListener(t, f) { if (this._dom[t] && this._dom[t].delete(f)) log.escuchas--; }
        _disparar(t, ev) { [...(this._dom[t] || [])].forEach((f) => f(ev || { target: this })); }
    }
    class Polilinea extends Base {
        constructor(o) { super(); this.o = { ...o }; this._path = new MVCArray((o.path || []).map(lit)); this._map = o.map || null; log.polilineas.push(this); }
        setMap(m) { this._map = m; } getMap() { return this._map; }
        setPath(p) { this._path = new MVCArray([...p].map(lit)); } getPath() { return this._path; }
        setOptions(o) { Object.assign(this.o, o); }
    }
    class Circulo extends Base {
        constructor(o) { super(); this.o = { ...o }; this._map = o.map || null; log.circulos.push(this); }
        setMap(m) { this._map = m; } setCenter(c) { this.o.center = lit(c); } setRadius(r) { this.o.radius = r; } getRadius() { return this.o.radius; }
        getBounds() { const c = this.o.center, d = this.o.radius / 111320; return { toJSON: () => ({ south: c.lat - d, west: c.lng - d, north: c.lat + d, east: c.lng + d }) }; }
    }
    class Info {
        constructor() { log.info = this; this.abierta = false; }
        setContent(c) { this.contenido = c; } open(o) { this.abierta = true; this.ancla = o && o.anchor; } close() { this.abierta = false; }
    }
    const maps = { Map: GMap, Polyline: Polilinea, Circle: Circulo, InfoWindow: Info, OverlayView };
    const marker = { AdvancedMarkerElement: Marcador };
    const core = { Point, LatLng, ControlPosition: POS, ColorScheme: { LIGHT: 'LIGHT', DARK: 'DARK' }, event: { trigger: (m, t, ev) => { if (m && m._emitir) m._emitir(t, ev || {}); } } };
    const g = (window.google = window.google || {});
    g.maps = { importLibrary: async (n) => { log.importaciones.push(n); return { maps, marker, core }[n]; }, event: { trigger: (m, t, ev) => { if (m && m._emitir) m._emitir(t, ev || {}); } }, LatLng, Point, ...maps, ...marker };
})();
