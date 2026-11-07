'use strict';
/* Adaptador Google Maps JavaScript API de la capa de mapa de SEVB. Solo se carga cuando la configuración elige «google»; en ese modo
   no se descarga Leaflet ni MapLibre. Implementa el contrato de docs/MAPA-CAPA.md con la API oficial (Map, AdvancedMarkerElement,
   Polyline, Circle, InfoWindow y OverlayView para proyecciones). No usa teselas no documentadas, DrawingManager ni plugins.
   Reglas que respeta:
   · El SDK se carga UNA vez por documento (importLibrary) con idioma español y región Colombia.
   · Todo fallo se clasifica (clave, sitio, API, facturación, cuota, red, bloqueo, Map ID) y se muestra como estado recuperable.
   · No se toca el DOM interno de Google ni sus clases CSS; los marcadores usan contenido propio (elementos que crea SEVB).
   · No se llama a Routes, Roads, Places ni Geocoding desde aquí: este archivo SOLO dibuja el mapa. */
(() => {
    const S = window.Spevb, M = S.Mapa, Geo = S.Geo;
    const { Emisor, Elemento, Grupo, contenidoSeguro } = M;
    const TIEMPO_SDK_MS = 20000, TIEMPO_PRIMER_IDLE_MS = 12000;
    let gm = null;                       // { Map, Polyline, Circle, InfoWindow, OverlayView, AdvancedMarkerElement, Point, ControlPosition, event }
    let sdk = null;                      // promesa única de carga del SDK
    let ultimoCodigoGoogle = null;       // último código de error que Google registró en la consola (p. ej. RefererNotAllowedMapError)
    let violacionCsp = null;

    // ------------------------------------------------------------ clasificación de errores de Google
    /* Google informa los problemas de clave/proyecto de dos maneras: llama a window.gm_authFailure (sin decir cuál) y escribe en la consola
       «Google Maps JavaScript API error: <Código>». Para poder explicarle algo útil a la persona se lee ese código del mensaje de consola. */
    const CODIGOS = {
        // Códigos de https://developers.google.com/maps/documentation/javascript/error-messages
        MissingKeyMapError: 'sin-configuracion', InvalidKeyMapError: 'clave-invalida', ExpiredKeyMapError: 'clave-invalida', MalformedCredentialsMapError: 'clave-invalida',
        InvalidClientIdMapError: 'clave-invalida', DeletedApiProjectMapError: 'clave-invalida', ProjectDeniedMapError: 'clave-invalida', TOSViolationMapError: 'clave-invalida',
        RefererNotAllowedMapError: 'sitio-no-autorizado', RefererDeniedMapError: 'sitio-no-autorizado',
        ApiNotActivatedMapError: 'api-deshabilitada', ApiTargetBlockedMapError: 'api-deshabilitada', LegacyApiNotActivatedMapError: 'api-deshabilitada',
        BillingNotEnabledMapError: 'facturacion', ClientBillingNotEnabledMapError: 'facturacion', OverQuotaMapError: 'cuota',
        NotLoadingAPIFromGoogleMapsError: 'bloqueado',
    };
    const clasificar = (codigoGoogle) => CODIGOS[codigoGoogle] || null;
    function vigilarConsola() {
        if (console.error.__sevbMapa) return;
        const original = console.error;
        const envuelta = function (...args) {
            try {
                const texto = args.map((a) => (typeof a === 'string' ? a : '')).join(' ');
                const m = /Google Maps JavaScript API (?:error|warning): (\w+)/.exec(texto) || /(\w+MapError)\b/.exec(texto);
                if (m) { ultimoCodigoGoogle = m[1]; alErrorDeGoogle(m[1]); }
            } catch { /* nunca romper la consola */ }
            return original.apply(this, args);
        };
        envuelta.__sevbMapa = true;
        console.error = envuelta;
    }
    const escuchasDeError = new Set();
    function alErrorDeGoogle(codigo) { escuchasDeError.forEach((fn) => fn(codigo)); }
    window.gm_authFailure = () => { setTimeout(() => alErrorDeGoogle(ultimoCodigoGoogle || 'InvalidKeyMapError'), 0); };

    // ------------------------------------------------------------ carga del SDK (una sola vez)
    function cargarSdk(config) {
        if (sdk) return sdk;
        const g = config.google || {};
        // Mensaje exacto según lo que falte: la clave no está, o el Map ID no está o su formato es inválido (solo letras, números y «_»).
        if (!g.claveNavegador) return Promise.reject(M.errorMapa('sin-configuracion', 'GOOGLE_MAPS_BROWSER_API_KEY no está definida (o tiene un formato inválido) en el servidor'));
        if (!g.mapId) return Promise.reject(M.errorMapa('map-id', 'GOOGLE_MAPS_MAP_ID no está definido (o tiene un formato inválido: solo letras, números y «_») en el servidor; los marcadores avanzados lo exigen'));
        vigilarConsola();
        document.addEventListener('securitypolicyviolation', (e) => { if (/googleapis|gstatic|google\.com/.test(e.blockedURI || '')) violacionCsp = e.violatedDirective + ' → ' + e.blockedURI; });
        sdk = new Promise((ok, fallo) => {
            if (window.google && window.google.maps && window.google.maps.importLibrary) { ok(); return; }
            const nombre = '__sevbGoogleListo';
            const limite = setTimeout(() => fallo(M.errorMapa('tiempo', 'el SDK no respondió en ' + TIEMPO_SDK_MS + ' ms')), TIEMPO_SDK_MS);
            window[nombre] = () => { clearTimeout(limite); delete window[nombre]; ok(); };
            const q = new URLSearchParams({ key: g.claveNavegador, v: g.version || 'weekly', language: g.idioma || 'es', region: g.region || 'CO', loading: 'async', callback: nombre });
            const el = document.createElement('script');
            el.src = 'https://maps.googleapis.com/maps/api/js?' + q; el.async = true; el.dataset.mapaSdk = '1';
            el.onerror = () => {
                clearTimeout(limite);
                fallo(navigator.onLine === false ? M.errorMapa('red', 'sin conexión') : M.errorMapa(violacionCsp ? 'bloqueado' : 'red', violacionCsp || 'error de red o bloqueo del script del SDK'));
            };
            document.head.append(el);
        }).then(async () => {
            const [maps, marker, core] = await Promise.all([google.maps.importLibrary('maps'), google.maps.importLibrary('marker'), google.maps.importLibrary('core')]);
            M.marca('sevb-sdk-listo');
            gm = { Map: maps.Map, Polyline: maps.Polyline, Circle: maps.Circle, InfoWindow: maps.InfoWindow, OverlayView: maps.OverlayView,
                AdvancedMarkerElement: marker.AdvancedMarkerElement, Point: core.Point, LatLng: core.LatLng, ControlPosition: core.ControlPosition, event: core.event || google.maps.event, ColorScheme: core.ColorScheme };
            return gm;
        });
        // Un fallo de red o de tiempo se puede reintentar (se retira el script fallido); los de clave/proyecto no cambian con reintentar.
        sdk.catch((e) => { if (e && e.reintentable) { document.querySelectorAll('script[data-mapa-sdk]').forEach((n) => n.remove()); sdk = null; } });
        return sdk;
    }

    // ------------------------------------------------------------ utilidades visuales
    const rgba = (color, alfa) => {
        const m = /^#([0-9a-f]{6})$/i.exec(color || '');
        if (!m) return color || 'transparent';
        const n = parseInt(m[1], 16);
        return 'rgba(' + (n >> 16) + ',' + ((n >> 8) & 255) + ',' + (n & 255) + ',' + (alfa == null ? 1 : alfa) + ')';
    };
    let secuencia = 0;
    /** zIndex de polilíneas: nivel * 1e9 + orden de creación, así «borde blanco» y «línea de color» conservan el orden en que se crearon. */
    const zLinea = (nivel) => nivel * 1e9 + (++secuencia);
    /** Contenido de un marcador: caja del tamaño pedido, desplazada para que el punto `anchor` quede sobre la coordenada
        (AdvancedMarkerElement ancla por defecto el centro del borde inferior del contenido). */
    function crearContenido(o) {
        const size = o.size || [0, 0], anchor = o.anchor || [size[0] / 2, size[1] / 2];
        const c = document.createElement('div');
        c.className = (o.className || 'sevb-marker') + ' sevb-marcador';
        c.style.position = 'relative'; c.style.width = size[0] + 'px'; c.style.height = size[1] + 'px';
        c.style.transform = 'translate(' + (size[0] / 2 - anchor[0]) + 'px,' + (size[1] - anchor[1]) + 'px)';
        if (o.html instanceof Node) c.append(o.html); else if (o.html) c.innerHTML = o.html;      // html lo construye SEVB; los textos de datos van por tooltip/popup (texto)
        return c;
    }

    // ------------------------------------------------------------ tooltip propio (Google no trae tooltips para marcadores ni líneas)
    class Tooltip {
        constructor(contenedor) { this.contenedor = contenedor; this.el = null; this.t = null; }
        _asegurar() {
            if (this.el) return;
            this.el = document.createElement('div');
            this.el.className = 'mapa-tooltip'; this.el.setAttribute('role', 'tooltip'); this.el.hidden = true;
            this.contenedor.append(this.el);
        }
        mostrar(contenido, x, y, { desplazamiento = [0, -12], ms = 0 } = {}) {
            this._asegurar();
            this.el.replaceChildren(contenidoSeguro(contenido));
            this.el.hidden = false;
            const r = this.contenedor.getBoundingClientRect();
            this.el.style.left = Math.round(x - r.left + desplazamiento[0]) + 'px';
            this.el.style.top = Math.round(y - r.top + desplazamiento[1]) + 'px';
            clearTimeout(this.t);
            if (ms) this.t = setTimeout(() => this.ocultar(), ms);
        }
        ocultar() { clearTimeout(this.t); if (this.el) this.el.hidden = true; }
        destruir() { clearTimeout(this.t); if (this.el) this.el.remove(); this.el = null; }
    }

    // ------------------------------------------------------------ elementos
    const tactil = () => !!window.matchMedia?.('(pointer: coarse)').matches;
    class ElementoG extends Elemento {
        constructor(mapa) { super(mapa); this.n = null; this._escuchas = []; this._tip = null; this._popup = null; }
        _g(tipo, fn) { this._escuchas.push(this.n.addListener(tipo, fn)); }
        /** Eventos DOM de un marcador avanzado (gmp-click, gmp-dragstart, gmp-dragend): addListener/click están deprecados para marcadores. */
        _dom(tipo, fn) { this.n.addEventListener(tipo, fn); this._escuchas.push({ remove: () => this.n.removeEventListener(tipo, fn) }); }
        _liberar() { this._escuchas.forEach((e) => e.remove()); this._escuchas = []; if (this.mapa._tooltip) this.mapa._tooltip.ocultar(); }
        setTooltip(contenido, opciones = {}) { this._tip = contenido == null || contenido === '' ? null : { contenido, ...opciones }; this.textoTooltip = this._tip ? (contenido instanceof Node ? contenido.textContent : String(contenido)) : ''; return this; }
        colorPrincipal() { return (this._cam ? this._tema().color : (this._estilo && this._estilo.color)) || null; }
        pesoPrincipal() { return (this._estilo && this._estilo.weight) || null; }
        openTooltip() { return this; }
        setPopup(contenido) { this._popup = contenido; return this; }
        openPopup() { if (this._popup != null) this.mapa._abrirPopup(this, this._popup); return this; }
        getElement() { return null; }
        setClase() { return this; }
    }
    class MarcadorG extends ElementoG {
        constructor(mapa, ll, o = {}) {
            super(mapa); this.tipo = 'marcador';
            this._o = o;
            this.content = crearContenido(o);
            const interactivo = o.interactive !== false;
            if (!interactivo) this.content.style.pointerEvents = 'none';
            this.n = new gm.AdvancedMarkerElement({ position: Geo.aGoogle(ll), zIndex: o.z || 0, gmpDraggable: !!o.draggable, gmpClickable: interactivo || !!o.popup });
            this.n.append(this.content);           // contenido propio (el atributo `content` está deprecado en favor de append/children)
            if (o.tooltip != null) this.setTooltip(o.tooltip, o.tooltipOpciones);
            if (o.popup != null) this.setPopup(o.popup);
            if (interactivo) {
                this._dom('gmp-click', (e) => {
                    this._emitirPuntero('click', e);
                    if (this._popup != null) this.openPopup();
                    else if (this._tip && tactil()) this._mostrarTip(3500);
                });
                this.content.addEventListener('dblclick', (e) => { e.stopPropagation(); this._emitirPuntero('dblclick', { domEvent: e }); });
                this.content.addEventListener('mouseenter', () => { if (!tactil()) this._mostrarTip(0); });
                this.content.addEventListener('mouseleave', () => this.mapa._tooltip && this.mapa._tooltip.ocultar());
            }
            if (o.draggable) {
                this._dom('gmp-dragstart', () => this.emitir('dragstart', { latlng: this.getPosition(), originalEvent: null, target: this }));
                this._dom('gmp-dragend', () => this.emitir('dragend', { latlng: this.getPosition(), originalEvent: null, target: this }));      // la posición final se lee de marker.position
            }
        }
        _emitirPuntero(tipo, e) {
            const original = e && (e.domEvent || e) || null;
            const ll = (e && e.latLng && Geo.deGoogle(e.latLng)) || (original && original.clientX != null && this.mapa.eventLatLng(original)) || this.getPosition();
            this.emitir(tipo, { latlng: ll, originalEvent: original, target: this, detener() {} });
        }
        _mostrarTip(ms) {
            if (!this._tip) return;
            const r = this.content.getBoundingClientRect(), off = (this._o.tooltipOpciones && this._o.tooltipOpciones.offset) || [0, 0];
            this.mapa._tooltip.mostrar(this._tip.contenido, r.left + r.width / 2, r.top, { desplazamiento: [off[0], off[1] - 8], ms });
        }
        openTooltip() { this._mostrarTip(0); return this; }
        _mostrar(m) { this.n.map = m.n; }
        _ocultar() { this.n.map = null; }
        setPosition(ll) { this.n.position = Geo.aGoogle(ll); return this; }
        getPosition() { return Geo.deGoogle(this.n.position); }
        getElement() { return this.content; }
        setClase(clase, activa) { this.content.classList.toggle(clase, !!activa); return this; }
        setArrastrable(on) { this.n.gmpDraggable = !!on; return this; }
    }
    /** Punto de radio fijo en píxeles: marcador avanzado con un círculo CSS (Google solo tiene círculos de radio en metros). */
    class PuntoG extends MarcadorG {
        constructor(mapa, ll, o = {}) {
            const radio = o.radius ?? 5, peso = o.weight ?? 3, d = radio * 2 + peso;
            super(mapa, ll, { className: 'sevb-punto' + (o.className ? ' ' + o.className : ''), size: [d, d], interactive: o.interactive !== false, tooltip: o.tooltip, tooltipOpciones: o.tooltipOpciones, z: o.z });
            this.tipo = 'punto';
            this._estilo = { radius: radio, weight: peso, color: o.color, fillColor: o.fillColor, fillOpacity: o.fillOpacity, opacity: o.opacity };
            this.content.style.boxSizing = 'border-box'; this.content.style.borderRadius = '50%';
            this._pintar();
        }
        _pintar() {
            const s = this._estilo, d = s.radius * 2 + s.weight;
            this.content.style.width = this.content.style.height = d + 'px';
            this.content.style.transform = 'translate(0,' + (d / 2) + 'px)';        // centro del círculo sobre la coordenada
            this.content.style.border = s.weight + 'px solid ' + rgba(s.color || '#3388ff', s.opacity == null ? 1 : s.opacity);
            this.content.style.background = rgba(s.fillColor || s.color || '#3388ff', s.fillOpacity == null ? 0.2 : s.fillOpacity);
        }
        setStyle(s) { Object.keys(s).forEach((k) => s[k] !== undefined && (this._estilo[k] = s[k])); this._pintar(); return this; }
    }
    class CirculoG extends ElementoG {
        constructor(mapa, ll, radio, o = {}) {
            super(mapa); this.tipo = 'circulo'; this._estilo = { color: o.color };
            this.n = new gm.Circle({ center: Geo.aGoogle(ll), radius: radio, strokeColor: o.color, strokeWeight: o.weight ?? 1, strokeOpacity: o.opacity ?? 1,
                fillColor: o.fillColor || o.color, fillOpacity: o.fillOpacity ?? 0.2, clickable: !!o.interactive, zIndex: zLinea(o.z || 0) });
        }
        _mostrar(m) { this.n.setMap(m.n); }
        _ocultar() { this.n.setMap(null); }
        setPosition(ll) { this.n.setCenter(Geo.aGoogle(ll)); return this; }
        setRadius(r) { this.n.setRadius(r); return this; }
        getBounds() { const b = this.n.getBounds(); return b ? aCaja(b) : null; }
    }
    /** Convierte `dashArray` de Leaflet ('1 10', '8 6') en los símbolos repetidos de una Polyline de Google (no admite guiones nativos). */
    function opcionesDeTrazo(o, offsetPx = 0, flecha = false) {
        const peso = o.weight ?? 3, opacidad = o.opacity ?? 1, d = M.dash(o.dashArray);
        if (!d) return { strokeColor: o.color || '#3388ff', strokeOpacity: opacidad, strokeWeight: peso, icons: [] };
        const [trazo, hueco] = d, color = o.color || '#3388ff';
        const simbolo = trazo <= 2
            ? { path: 'M 0,0 m -1,0 a 1,1 0 1,0 2,0 a 1,1 0 1,0 -2,0', scale: Math.max(1, peso / 2), fillColor: color, fillOpacity: opacidad, strokeWeight: 0 }
            : { path: 'M 0,-1 0,1', scale: trazo / 2, strokeColor: color, strokeOpacity: opacidad, strokeWeight: peso };
        // offset en píxeles: desplazar los símbolos a lo largo del trazado (en el sentido de sus vértices) es la forma pública de animar una línea de Google.
        const iconos = [{ icon: simbolo, offset: Math.round(offsetPx * 10) / 10 + 'px', repeat: Math.max(2, trazo + hueco) + 'px' }];
        // Sin animación (movimiento reducido) el sentido se indica con flechas pequeñas repetidas; los símbolos giran con el trazado.
        if (flecha) iconos.push({ icon: { path: 'M 0,-1 L 1,1 L 0,0.45 L -1,1 z', scale: Math.max(3, peso * 0.9), fillColor: color, fillOpacity: Math.min(1, opacidad), strokeWeight: 0 }, offset: '55px', repeat: '110px' });
        return { strokeColor: color, strokeOpacity: 0, strokeWeight: peso, icons: iconos };
    }

    // ------------------------------------------------------------ caminatas: identidad propia, tema claro/oscuro y animación compartida
    /* Todas las caminatas se ven igual: línea discontinua con un color propio (distinto de las rutas de bus, de los pines A/B y de las alertas) y un
       contorno fino que mantiene el contraste sobre calles, parques y etiquetas. De noche usan una variante luminosa. El color NO viene de quien las crea. */
    const TEMA_CAMINATA = {
        dia: { color: '#1b2a33', halo: '#ffffff', haloOpacidad: 0.9 },
        noche: { color: '#fff4b8', halo: '#10161a', haloOpacidad: 0.7 },
        respaldo: { color: '#c2410c', halo: '#ffffff', haloOpacidad: 0.9 },       // línea recta aproximada (sin trazado por calles): no se anima
    };
    const esNoche = () => document.documentElement.classList.contains('mapa-noche');
    const VELOCIDAD_PX_S = 20, INTERVALO_MS = 66, MAX_ANIMADAS = 4;       // ≈ 15 fotogramas/s, movimiento suave y poco distractor, a lo sumo 4 líneas
    /** Controlador ÚNICO de las caminatas visibles: un solo bucle para todas (nada de un temporizador por guion). Solo anima lo que se ve y lo que importa. */
    const Anim = {
        lineas: new Set(), raf: 0, ultimo: 0, ref: null,
        agregar(l) { this.lineas.add(l); this.arrancar(); },
        quitar(l) { this.lineas.delete(l); if (!this.lineas.size) this.parar(); },
        arrancar() { if (!this.raf && this.lineas.size && !document.hidden) this.raf = requestAnimationFrame((t) => this.tick(t)); },
        parar() { cancelAnimationFrame(this.raf); this.raf = 0; },
        /** Las que se animan ahora: con un viaje en curso, solo la caminata más cercana a la persona; si no, las primeras visibles. */
        elegidas() {
            const vis = [...this.lineas].filter((l) => l.animable());
            if (this.ref && vis.length > 1) {
                let mejor = null, d = Infinity;
                vis.forEach((l) => { const x = l.distanciaA(this.ref); if (x < d) { d = x; mejor = l; } });
                return mejor ? [mejor] : [];
            }
            return vis.slice(0, MAX_ANIMADAS);
        },
        tick(ts) {
            this.raf = 0;
            if (!this.lineas.size || document.hidden) return;            // oculta la página: se detiene y visibilitychange la reanuda
            if (ts - this.ultimo >= INTERVALO_MS) {
                this.ultimo = ts;
                const el = this.elegidas(), set = new Set(el);
                const moviendo = [...this.lineas].some((l) => l.mapa && l.mapa.el.classList.contains('map-moving'));      // se pausa mientras se arrastra o se hace zoom
                this.lineas.forEach((l) => {
                    if (!set.has(l)) l.pintar(0);
                    else if (!moviendo) l.pintar((ts / 1000 * VELOCIDAD_PX_S) % l.periodo());
                });
            }
            this.arrancar();
        },
        refrescar() { this.lineas.forEach((l) => l.pintar(l._offset)); },
    };
    document.addEventListener('visibilitychange', () => { if (document.hidden) Anim.parar(); else Anim.arrancar(); });
    new MutationObserver(() => Anim.refrescar()).observe(document.documentElement, { attributes: true, attributeFilter: ['class'] });     // día/noche sin reconstruir nada
    window.matchMedia?.('(prefers-reduced-motion: reduce)').addEventListener?.('change', () => Anim.refrescar());
    class LineaG extends ElementoG {
        constructor(mapa, puntos, o = {}) {
            super(mapa); this.tipo = 'linea';
            this._estilo = { color: o.color, weight: o.weight, opacity: o.opacity, dashArray: o.dashArray };
            this._nivel = o.capa === 'caminata' ? 450 : (o.z || 0);
            this._cam = o.capa === 'caminata';
            const ruta = puntos.map(Geo.aGoogle);
            if (this._cam) {
                // Contorno fino detrás (se crea primero: queda debajo de la línea con el mismo nivel).
                this.halo = new gm.Polyline({ path: ruta, geodesic: false, clickable: false, zIndex: zLinea(this._nivel), strokeColor: '#fff', strokeOpacity: 0.9, strokeWeight: (o.weight ?? 5) + 3 });
                this._offset = 0; this._fallback = false; this._visible = false; this._ocultaPorMapa = false;
            }
            this.n = new gm.Polyline({ path: ruta, geodesic: false, clickable: o.interactive !== false, zIndex: zLinea(this._nivel), ...opcionesDeTrazo(this._estiloVisible()) });
            if (this._cam) this.pintar(0);
            if (o.tooltip != null) this.setTooltip(o.tooltip, { pegajoso: o.tooltipPegajoso, ...(o.tooltipOpciones || {}) });
            if (o.interactive !== false) {
                const emitir = (tipo) => (e) => this.emitir(tipo, { latlng: Geo.deGoogle(e.latLng), originalEvent: e.domEvent || null, target: this });
                this._g('click', (e) => { emitir('click')(e); if (this._tip && tactil() && e.domEvent) this._tipEn(e, 3500); });
                this._g('dblclick', emitir('dblclick'));
                this._g('mouseover', (e) => { if (!tactil()) this._tipEn(e, 0); });
                this._g('mousemove', (e) => { if (!tactil() && this._tip) this._tipEn(e, 0); });
                this._g('mouseout', () => this.mapa._tooltip && this.mapa._tooltip.ocultar());
            }
        }
        _tipEn(e, ms) {
            const de = e.domEvent, p = de && (de.changedTouches ? de.changedTouches[0] : de);
            if (!this._tip || !p || p.clientX == null) return;
            this.mapa._tooltip.mostrar(this._tip.contenido, p.clientX, p.clientY, { desplazamiento: [0, -14], ms });
        }
        // ---- caminatas (capa 'caminata'): estilo por tema, contorno y animación; ver el bloque «caminatas» arriba
        _tema() { return this._fallback ? TEMA_CAMINATA.respaldo : (esNoche() ? TEMA_CAMINATA.noche : TEMA_CAMINATA.dia); }
        _estiloVisible() {
            if (!this._cam) return this._estilo;
            const t = this._tema();
            return { ...this._estilo, color: t.color, dashArray: this._fallback ? '2 8' : (this._estilo.dashArray || '1 10'), opacity: this._estilo.opacity ?? 0.95 };
        }
        periodo() { const d = M.dash(this._estiloVisible().dashArray); return d ? Math.max(2, d[0] + d[1]) : 11; }
        animable() { return this._cam && this.visible && !this._fallback && !M.reducido() && this.mapa && this.mapa.capacidades.lineaAnimada; }
        distanciaA(ll) { let d = Infinity; const r = this.getPath(); for (let i = 0; i < r.length; i += Math.max(1, Math.floor(r.length / 24))) d = Math.min(d, Geo.distancia(ll, r[i])); return d; }
        /** Repinta la caminata con su tema y el desplazamiento de los símbolos (0 = quieta). Solo se actualiza lo que cambió. */
        pintar(offsetPx) {
            if (!this._cam || this.liberado) return;
            this._offset = offsetPx;
            const t = this._tema(), estatica = this._fallback || M.reducido();
            this.n.setOptions(opcionesDeTrazo(this._estiloVisible(), estatica ? 0 : offsetPx, M.reducido() && !this._fallback));
            if (this.halo) this.halo.setOptions({ strokeColor: t.halo, strokeOpacity: t.haloOpacidad, strokeWeight: (this._estilo.weight ?? 5) + 3 });
        }
        setClase(clase, activa) { if (this._cam && clase === 'walk-fallback') { this._fallback = !!activa; this.pintar(0); } return this; }
        _mostrar(m) { this.n.setMap(m.n); if (this.halo) this.halo.setMap(m.n); if (this._cam) Anim.agregar(this); }
        _ocultar() { this.n.setMap(null); if (this.halo) this.halo.setMap(null); if (this._cam) Anim.quitar(this); }
        _liberar() { super._liberar(); if (this._cam) Anim.quitar(this); if (this.halo) { this.halo.setMap(null); this.halo = null; } }
        /** `crece: true` = es el MISMO arreglo de la llamada anterior y solo se le agregaron puntos al final (estela del bus): se agregan solo los nuevos
            en vez de reconvertir los miles de puntos en cada actualización. Si el arreglo cambió de otra forma, se reconstruye completo. */
        setPath(puntos, o = {}) {
            const ruta = this.n.getPath();
            if (o.crece && puntos === this._arr && puntos.length >= this._n && ruta.getLength() === this._n) {
                for (let i = this._n; i < puntos.length; i++) ruta.push(Geo.aGoogle(puntos[i]));
            } else { const ruta2 = puntos.map(Geo.aGoogle); this.n.setPath(ruta2); if (this.halo) this.halo.setPath(ruta2); }
            this._arr = puntos; this._n = puntos.length;
            return this;
        }
        getPath() { return this.n.getPath().getArray().map(Geo.deGoogle); }
        setStyle(s) {
            // En una caminata el color lo decide el tema (día/noche/respaldo), no quien la dibuja: se ignora `color`.
            Object.keys(s).forEach((k) => s[k] !== undefined && !(this._cam && k === 'color') && (this._estilo[k] = s[k]));
            if (this._cam) this.pintar(this._offset); else this.n.setOptions(opcionesDeTrazo(this._estilo));
            return this;
        }
        getBounds() { return Geo.limites(this.getPath()); }
    }

    const aCaja = (b) => { const j = b.toJSON ? b.toJSON() : b; return { s: j.south, w: j.west, n: j.north, e: j.east }; };
    const aLiteral = (b) => ({ south: b.s, west: b.w, north: b.n, east: b.e });

    // ------------------------------------------------------------ encuadre (Google no admite maxZoom en fitBounds)
    /** Zoom y centro que encuadran una caja dentro de un área de `tam` píxeles con márgenes; pura, probada en e2e/mapa_geo_e2e.py. */
    function calcularEncuadre(b, tam, pad, { maxZoom = 21, minZoom = 0 } = {}) {
        const p = { top: pad.top || 0, left: pad.left || 0, bottom: pad.bottom || 0, right: pad.right || 0 };
        const w = Math.max(32, tam.x - p.left - p.right), h = Math.max(32, tam.y - p.top - p.bottom);
        const x = (lng) => (lng + 180) / 360;
        const y = (lat) => { const s = Math.sin(Math.max(-0.9999, Math.min(0.9999, lat * Math.PI / 180))); return 0.5 - Math.log((1 + s) / (1 - s)) / (4 * Math.PI); };
        const dx = Math.max(1e-9, x(b.e) - x(b.w)), dy = Math.max(1e-9, y(b.s) - y(b.n));
        const zoom = Math.max(minZoom, Math.min(maxZoom, Math.log2(Math.min(w / (256 * dx), h / (256 * dy)))));
        const escala = 256 * 2 ** zoom;
        // El centro del área libre no es el del contenedor: se desplaza la mitad de la diferencia de márgenes.
        const cx = (x(b.w) + x(b.e)) / 2 + (p.right - p.left) / 2 / escala, cy = (y(b.n) + y(b.s)) / 2 + (p.bottom - p.top) / 2 / escala;
        const lng = cx * 360 - 180, n = Math.PI - 2 * Math.PI * cy, lat = (180 / Math.PI) * Math.atan(0.5 * (Math.exp(n) - Math.exp(-n)));
        return { center: [lat, lng], zoom };
    }

    // ------------------------------------------------------------ el mapa
    const POSICIONES = { topleft: 'TOP_LEFT', topright: 'TOP_RIGHT', bottomleft: 'BOTTOM_LEFT', bottomright: 'BOTTOM_RIGHT' };
    class MapaGoogle extends Emisor {
        constructor(contenedor, opciones, config) {
            super();
            this.proveedor = 'google';
            this.el = contenedor;
            contenedor.classList.add('mapa-google');
            this.capacidades = { proveedor: 'google', mapaSinConexion: false, capasBase: false, lineaAnimada: true, modoNocheCss: false, mapaSobrePanel: true, marcadoresAvanzados: true };
            this._off = [];
            this._g = [];
            this._raiz = new Grupo(this); this._raiz.visible = true;
            this._tooltip = new Tooltip(contenedor);
            this._info = null;
            const b = S.cityBoundsCaja;
            const noche = document.documentElement.classList.contains('mapa-noche');
            const compacto = S.compactLayout();
            this.n = new gm.Map(contenedor, {
                center: Geo.aGoogle(opciones.centro || S.center), zoom: opciones.zoom ?? 13, minZoom: 11, maxZoom: 19,
                mapId: config.google.mapId,
                colorScheme: noche ? gm.ColorScheme.DARK : gm.ColorScheme.LIGHT,
                restriction: { latLngBounds: aLiteral(b), strictBounds: false },
                gestureHandling: 'greedy',
                disableDefaultUI: true, zoomControl: !compacto, zoomControlOptions: { position: gm.ControlPosition.RIGHT_BOTTOM },
                clickableIcons: false,                       // los puntos de interés de Google no deben abrir ventanas ni robar clics
                keyboardShortcuts: true, isFractionalZoomEnabled: true,
            });
            // El control de zoom se oculta en celular (como el de Leaflet): se escucha el cambio de diseño.
            const mq = window.matchMedia(S.COMPACT_LAYOUT || '(max-width: 559px), (max-width: 860px) and (min-height: 481px)');
            const alCambiarDiseno = () => this.n.setOptions({ zoomControl: !mq.matches });
            mq.addEventListener('change', alCambiarDiseno); this._off.push(() => mq.removeEventListener('change', alCambiarDiseno));
            const ro = new ResizeObserver(() => this.resize());
            ro.observe(contenedor); this._off.push(() => ro.disconnect());
            this._conectarEventos(contenedor);
        }
        _escuchar(tipo, fn) { this._g.push(this.n.addListener(tipo, fn)); }
        _conectarEventos(contenedor) {
            let calma = null, zoomAbierto = false;
            const mueve = () => { clearTimeout(calma); contenedor.classList.add('map-moving'); };
            const quieto = () => { clearTimeout(calma); calma = setTimeout(() => contenedor.classList.remove('map-moving'), 250); };
            const pt = (e, tipo) => this.emitir(tipo, { latlng: e && e.latLng ? Geo.deGoogle(e.latLng) : null, originalEvent: e && e.domEvent || null });
            this._escuchar('click', (e) => pt(e, 'click'));
            this._escuchar('dblclick', (e) => pt(e, 'dblclick'));
            // dragstart/dragend de Google solo los provoca la persona al arrastrar; panTo/setCenter/moveCamera NO los disparan.
            this._escuchar('dragstart', () => { mueve(); this.emitir('dragstart', { latlng: null, originalEvent: null }); });
            this._escuchar('dragend', () => { quieto(); this.emitir('dragend', { latlng: null, originalEvent: null }); });
            this._escuchar('zoom_changed', () => { if (!zoomAbierto) { zoomAbierto = true; mueve(); this.emitir('zoomstart', { latlng: null, originalEvent: null }); } });
            this._escuchar('idle', () => {
                quieto();
                if (zoomAbierto) { zoomAbierto = false; this.emitir('zoomend', { latlng: null, originalEvent: null }); }
                this.emitir('moveend', { latlng: null, originalEvent: null });
            });
        }
        /** Espera a que el mapa dibuje y a que la proyección esté disponible (antes no se pueden convertir coordenadas a píxeles). */
        _esperarProyeccion() {
            const mapa = this;
            return new Promise((ok) => {
                class Ayuda extends gm.OverlayView { onAdd() {} draw() { if (!mapa._proy) { mapa._proy = this.getProjection(); ok(); } } onRemove() {} }
                this._ayuda = new Ayuda();
                this._ayuda.setMap(this.n);
            });
        }
        // ---- vista
        setView(ll, zoom, o = {}) {
            const z = zoom == null ? this.n.getZoom() : zoom;
            const c = Geo.aGoogle(o.padding ? Geo.centroConMargen(ll, z, o.padding) : ll);
            if (o.animate !== false && z === this.n.getZoom()) this.n.panTo(c); else this.n.moveCamera({ center: c, zoom: z });
            return this;
        }
        panTo(ll, o = {}) { const c = Geo.aGoogle(ll); if (o.animate !== false) this.n.panTo(c); else this.n.moveCamera({ center: c }); return this; }
        fitBounds(b, o = {}) {
            if (!Geo.esValida(b)) return this;
            const p = typeof o.padding === 'number' ? { top: o.padding, left: o.padding, bottom: o.padding, right: o.padding } : (o.padding || {});
            const r = calcularEncuadre(b, this.getSize(), p, { maxZoom: o.maxZoom ?? 19, minZoom: 11 });
            this.n.moveCamera({ center: Geo.aGoogle(r.center), zoom: r.zoom });
            return this;
        }
        /** Con un viaje en curso: la caminata que se anima es la más cercana a esta posición (null = ya no hay viaje). */
        priorizarCaminata(ll) { Anim.ref = ll ? Geo.ll(ll) : null; }
        stop() { const c = this.n.getCenter(), z = this.n.getZoom(); if (c) this.n.moveCamera({ center: c, zoom: z }); return this; }
        getZoom() { return this.n.getZoom(); }
        getCenter() { return Geo.deGoogle(this.n.getCenter()); }
        getBounds() { const b = this.n.getBounds(); return b ? aCaja(b) : null; }
        getSize() { return { x: this.el.clientWidth, y: this.el.clientHeight }; }
        /** El contenedor cambió de tamaño: Google lo detecta solo, pero se le avisa por su evento oficial `resize` (sin recrear nada ni recentrar) y se
            avisa a los consumidores. Antes solo se emitía el evento propio, sin tocar al SDK. */
        resize() { try { gm.event.trigger(this.n, 'resize'); } catch { /* el SDK lo detecta por sí mismo */ } this.emitir('resize', { latlng: null, originalEvent: null }); return this; }
        setDobleClicZoom(on) { this.n.setOptions({ disableDoubleClickZoom: !on }); return this; }
        // ---- proyección (disponible tras el primer dibujo; hasta entonces devuelve null y el llamador debe tolerarlo)
        toPoint(ll) { if (!this._proy) return null; const q = Geo.ll(ll); if (!q) return null; const p = this._proy.fromLatLngToContainerPixel(new gm.LatLng(q[0], q[1])); return p ? { x: p.x, y: p.y } : null; }
        toLatLng(pt) { if (!this._proy) return null; return Geo.deGoogle(this._proy.fromContainerPixelToLatLng(new gm.Point(pt.x, pt.y))); }
        eventLatLng(ev) {
            if (!ev || !this._proy || ev.clientX == null) return null;
            const r = this.el.getBoundingClientRect();
            return this.toLatLng({ x: ev.clientX - r.left, y: ev.clientY - r.top });
        }
        // ---- elementos
        marker(ll, o) { return new MarcadorG(this, ll, o); }
        dot(ll, o) { return new PuntoG(this, ll, o); }
        circle(ll, radio, o) { return new CirculoG(this, ll, radio, o); }
        polyline(puntos, o) { return new LineaG(this, puntos, o); }
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
            const lista = this.n.controls[gm.ControlPosition[POSICIONES[posicion] || 'TOP_RIGHT']];
            lista.push(el);
            return () => { const a = lista.getArray(); const i = a.indexOf(el); if (i >= 0) lista.removeAt(i); };
        }
        _abrirPopup(el, contenido) {
            if (!this._info) this._info = new gm.InfoWindow();
            this._info.setContent(contenidoSeguro(contenido));
            this._info.open({ map: this.n, anchor: el.n });
        }
        destroy() {
            this._raiz.clear();
            this._g.forEach((e) => e.remove()); this._g = [];
            this._off.forEach((f) => f());
            if (this._ayuda) this._ayuda.setMap(null);
            if (this._info) this._info.close();
            this._tooltip.destruir();
            this.limpiarEventos();
        }
    }

    M.registrar('google', {
        async crear(contenedor, opciones, config) {
            await cargarSdk(config);
            let fallo = null;
            const alErrorTemprano = (codigo) => { fallo = fallo || codigo; };
            escuchasDeError.add(alErrorTemprano);
            let mapa;
            try {
                mapa = new MapaGoogle(contenedor, opciones, config);
                // Se espera al primer dibujo y a la proyección. Si Google reporta un problema de clave/proyecto antes, se rechaza con su causa.
                // Sin un Map ID válido los marcadores avanzados no cargan: Google lo informa con mapcapabilities_changed (no hay un código de error propio).
                mapa._escuchar('mapcapabilities_changed', () => {
                    const c = mapa.n.getMapCapabilities && mapa.n.getMapCapabilities();
                    if (c && c.isAdvancedMarkersAvailable === false) fallo = fallo || 'MapIdSinMarcadores';
                });
                // Se espera a la proyección Y al primer «idle» (primer dibujo): los avisos tempranos de Google (clave, sitio, Map ID) llegan antes de ese idle.
                await new Promise((ok, no) => {
                    let proy = false, idle = false;
                    const terminar = () => {
                        clearTimeout(t);
                        if (fallo) no(M.errorMapa(fallo === 'MapIdSinMarcadores' ? 'map-id' : (clasificar(fallo) || 'clave-invalida'),
                            fallo === 'MapIdSinMarcadores' ? 'isAdvancedMarkersAvailable=false: el Map ID no existe, no es de tipo JavaScript o no coincide con la clave/proyecto' : 'Google: ' + fallo));
                        else ok();
                    };
                    const t = setTimeout(ok, TIEMPO_PRIMER_IDLE_MS);                   // red lenta: la app sigue; el mapa se completa solo
                    const revisar = () => { if (proy && idle) terminar(); };
                    mapa._escuchar('idle', () => { idle = true; revisar(); });
                    mapa._esperarProyeccion().then(() => { proy = true; revisar(); });
                });
            } catch (e) {
                if (mapa) mapa.destroy();
                throw e;
            } finally { escuchasDeError.delete(alErrorTemprano); }
            // Problemas posteriores al primer dibujo (cuota agotada a mitad de sesión, etc.): estado visible sin perder el resto de la pantalla.
            const alErrorTardio = (codigo) => { if (contenedor.querySelector(':scope > .mapa-error')) return; M.mostrarError(contenedor, M.errorMapa(clasificar(codigo) || 'desconocido', 'Google: ' + codigo), null); };
            escuchasDeError.add(alErrorTardio);
            const destruir = mapa.destroy.bind(mapa);
            mapa.destroy = () => { escuchasDeError.delete(alErrorTardio); destruir(); };
            return mapa;
        },
    });
    // Para las pruebas: funciones puras y estado del adaptador.
    M.google = { calcularEncuadre, clasificar, opcionesDeTrazo, rgba, Anim, TEMA_CAMINATA };
})();
