'use strict';
/* Capa de mapa de SEVB (núcleo, independiente del proveedor).
   ─ Qué hace: lee la configuración pública (/api/config/mapa), carga SOLO el adaptador del proveedor elegido (leaflet | google),
     crea mapas con él y ofrece las piezas comunes (grupos, emisor de eventos, errores clasificados y estado recuperable).
   ─ Qué NO hace: no guarda estado del viaje, no calcula rutas, no decide subidas ni bajadas, no conoce el modelo del editor.
   ─ Contrato que cumple cada adaptador (MapaSEVB): ver docs/MAPA-CAPA.md.
   El proveedor se elige al cargar la página y no cambia mientras la página vive (así un viaje activo nunca pierde su mapa por un cambio
   automático). Si el proveedor falla se muestra el error y un botón para reintentar; NO se sustituye en silencio por otro mapa. */
(() => {
    const S = window.Spevb || (window.Spevb = {});
    const RAIZ = '/js/mapa/';
    const CLAVE_CACHE = 'spevb-mapa-config';
    const adaptadores = {};
    const cargados = new Map();

    // ------------------------------------------------------------ utilidades
    /** Carga un script una sola vez por documento (varias llamadas con la misma URL comparten la promesa). */
    function cargarScript(src) {
        if (cargados.has(src)) return cargados.get(src);
        const p = new Promise((ok, fallo) => {
            const el = document.createElement('script');
            el.src = src; el.async = false;
            el.onload = ok;
            el.onerror = () => { cargados.delete(src); fallo(Object.assign(new Error('No se pudo cargar ' + src), { codigo: 'red' })); };
            document.head.append(el);
        });
        cargados.set(src, p);
        return p;
    }
    /** Añade una hoja de estilos una sola vez; la promesa se cumple al cargar (o al fallar: sin estilos el mapa sigue siendo utilizable). */
    function cargarCss(href) {
        const previo = document.querySelector('link[data-mapa-css="' + href + '"]');
        if (previo) return previo._listo;
        const el = document.createElement('link');
        el.rel = 'stylesheet'; el.href = href; el.dataset.mapaCss = href;
        el._listo = new Promise((ok) => { el.onload = ok; el.onerror = ok; });
        document.head.append(el);
        return el._listo;
    }
    const leerCache = () => { try { return JSON.parse(localStorage.getItem(CLAVE_CACHE)); } catch { return null; } };
    const guardarCache = (c) => { try { localStorage.setItem(CLAVE_CACHE, JSON.stringify(c)); } catch { /* sin almacenamiento */ } };
    const depurando = () => { try { return /(^|[?&])mapa-debug=1/.test(location.search) || localStorage.getItem('spevb-mapa-debug') === '1'; } catch { return false; } };

    // ------------------------------------------------------------ errores clasificados
    const MENSAJES = {
        'sin-configuracion': { titulo: 'El mapa de Google aún no está configurado', texto: 'Falta la clave del mapa en el servidor. Avisa a quien administra la aplicación.', reintentar: false },
        'clave-invalida': { titulo: 'No se pudo abrir el mapa', texto: 'La clave del mapa no es válida. Avisa a quien administra la aplicación.', reintentar: false },
        'sitio-no-autorizado': { titulo: 'No se pudo abrir el mapa', texto: 'Este sitio no está autorizado para usar el mapa de Google. Avisa a quien administra la aplicación.', reintentar: false },
        'api-deshabilitada': { titulo: 'No se pudo abrir el mapa', texto: 'El servicio de mapas no está activado para este proyecto. Avisa a quien administra la aplicación.', reintentar: false },
        'facturacion': { titulo: 'No se pudo abrir el mapa', texto: 'El servicio de mapas tiene un problema de facturación. Avisa a quien administra la aplicación.', reintentar: false },
        'cuota': { titulo: 'El mapa llegó a su límite de uso', texto: 'Intenta de nuevo más tarde. Las rutas y los datos siguen disponibles.', reintentar: true },
        'red': { titulo: 'No se pudo descargar el mapa', texto: 'Revisa tu conexión a internet e inténtalo de nuevo.', reintentar: true },
        'tiempo': { titulo: 'El mapa tarda demasiado', texto: 'La conexión está muy lenta. Puedes intentar de nuevo.', reintentar: true },
        'bloqueado': { titulo: 'El mapa fue bloqueado', texto: 'Tu navegador o una extensión bloqueó el mapa de Google. Desactiva el bloqueo para este sitio e inténtalo de nuevo.', reintentar: true },
        'map-id': { titulo: 'No se pudo abrir el mapa', texto: 'Falta el identificador de mapa (Map ID) o no es válido. Avisa a quien administra la aplicación.', reintentar: false },
        'webgl': { titulo: 'No se pudo dibujar el mapa', texto: 'Tu dispositivo no permite dibujar este mapa. Prueba con otro navegador.', reintentar: false },
        'desconocido': { titulo: 'No se pudo abrir el mapa', texto: 'Ocurrió un problema inesperado con el mapa. Puedes intentar de nuevo.', reintentar: true },
    };
    /** Crea un Error con código estable (ver MENSAJES) y detalle técnico (solo para diagnóstico de desarrollo). */
    function errorMapa(codigo, detalle) {
        const m = MENSAJES[codigo] || MENSAJES.desconocido;
        const e = new Error(m.texto);
        e.codigo = MENSAJES[codigo] ? codigo : 'desconocido'; e.titulo = m.titulo; e.reintentable = m.reintentar; e.detalle = detalle || '';
        return e;
    }

    // ------------------------------------------------------------ configuración
    /** Pide la configuración pública. Con mala señal usa la última conocida; sin ninguna, el proveedor anterior (leaflet). */
    async function cargarConfig() {
        const control = new AbortController();
        const t = setTimeout(() => control.abort(), 6000);
        try {
            const r = await fetch('/api/config/mapa', { signal: control.signal, headers: { Accept: 'application/json' } });
            if (!r.ok) throw new Error('HTTP ' + r.status);
            const c = await r.json();
            if (!c || (c.proveedor !== 'leaflet' && c.proveedor !== 'google')) throw new Error('configuración inválida');
            guardarCache(c);
            return { ...c, origen: 'servidor' };
        } catch (e) {
            const previa = leerCache();
            if (previa && (previa.proveedor === 'leaflet' || previa.proveedor === 'google')) return { ...previa, origen: 'copia' };
            return { proveedor: 'leaflet', origen: 'predeterminado' };
        } finally { clearTimeout(t); }
    }

    let promesaListo = null;
    const Mapa = {
        config: null,
        proveedor: null,
        depurando,
        cargarScript,
        cargarCss,
        errorMapa,
        MENSAJES,
        /** Se cumple cuando el adaptador del proveedor ya está cargado (no cuando hay un mapa). */
        get listo() {
            if (!promesaListo) {
                promesaListo = (async () => {
                    const config = await cargarConfig();
                    Mapa.config = config;
                    Mapa.proveedor = config.proveedor;
                    document.documentElement.dataset.mapa = config.proveedor;           // para el CSS propio de cada proveedor
                    await cargarScript(RAIZ + 'adaptador-' + config.proveedor + '.js');
                    if (!adaptadores[config.proveedor]) throw errorMapa('desconocido', 'adaptador no registrado');
                    return config;
                })();
                promesaListo.catch(() => { promesaListo = null; });                     // un fallo de red al cargar el adaptador se puede reintentar
            }
            return promesaListo;
        },
        registrar(nombre, adaptador) { adaptadores[nombre] = adaptador; },
        /** Crea un mapa en el elemento #id. Rechaza con un Error clasificado (codigo, titulo, reintentable, detalle). */
        async crear(id, opciones = {}) {
            const config = await Mapa.listo;
            const contenedor = document.getElementById(id);
            if (!contenedor) throw errorMapa('desconocido', 'no existe #' + id);
            try {
                const mapa = await adaptadores[config.proveedor].crear(contenedor, opciones, config);
                quitarEstadoError(contenedor);
                if (Mapa.despuesDeCrear) Mapa.despuesDeCrear(mapa);
                (S.maps ||= {})[id] = mapa;                                            // acceso para diagnóstico y pruebas
                return mapa;
            } catch (e) {
                throw e && e.codigo ? e : errorMapa('desconocido', (e && e.message) || String(e));
            }
        },
    };

    // ------------------------------------------------------------ estado recuperable en pantalla
    function quitarEstadoError(contenedor) {
        contenedor.querySelectorAll(':scope > .mapa-error').forEach((n) => n.remove());
        contenedor.classList.remove('mapa-con-error');
    }
    /** Muestra dentro del contenedor un mensaje breve y, si procede, el botón «Reintentar». Los detalles técnicos solo con ?mapa-debug=1. */
    function mostrarError(contenedor, error, reintentar) {
        quitarEstadoError(contenedor);
        contenedor.classList.add('mapa-con-error');
        const caja = document.createElement('div');
        caja.className = 'mapa-error'; caja.setAttribute('role', 'alert');
        const h = document.createElement('strong'); h.textContent = error.titulo || MENSAJES.desconocido.titulo;
        const p = document.createElement('p'); p.textContent = error.message;
        caja.append(h, p);
        if (error.reintentable && reintentar) {
            const b = document.createElement('button');
            b.type = 'button'; b.className = 'primary-plan-button'; b.textContent = 'Reintentar';
            b.addEventListener('click', () => { b.disabled = true; b.textContent = 'Reintentando…'; reintentar(); });
            caja.append(b);
        }
        if (depurando() && error.detalle) {
            const d = document.createElement('pre'); d.className = 'mapa-error-detalle'; d.textContent = '[' + error.codigo + '] ' + error.detalle;
            caja.append(d);
        }
        contenedor.append(caja);
        console.warn('[SEVB mapa]', error.codigo, error.detalle || error.message);
    }

    /** Crea el mapa de la página y llama a `iniciar(mapa)` UNA sola vez cuando esté listo.
        Si falla: muestra el estado recuperable, llama a `alFallar(error)` y, con «Reintentar», vuelve a intentar sin recargar la página. */
    function conMapa(id, iniciar, { alFallar, opciones } = {}) {
        let iniciado = false;
        const intentar = () => {
            Mapa.crear(id, opciones).then((mapa) => {
                if (iniciado) return;
                iniciado = true;
                iniciar(mapa);
            }).catch((error) => {
                const el = document.getElementById(id);
                if (el) mostrarError(el, error, intentar);
                if (alFallar) alFallar(error);
            });
        };
        intentar();
    }

    // ------------------------------------------------------------ hitos entre módulos
    /* Un módulo publica «explorador» o «editor» cuando su mapa ya existe; los demás esperan con S.esperar(nombre). Así ningún módulo
       supone que el mapa está disponible de inmediato (con Google la creación es asíncrona). */
    const hitos = {};
    const hito = (n) => hitos[n] || (hitos[n] = (() => { let ok; const p = new Promise((r) => { ok = r; }); return { p, ok }; })());
    const publicar = (nombre, valor) => hito(nombre).ok(valor);
    const esperar = (nombre) => hito(nombre).p;

    // ------------------------------------------------------------ piezas comunes para adaptadores
    /** Emisor mínimo de eventos: on(tipo, fn) devuelve una función que lo desuscribe. */
    class Emisor {
        constructor() { this._h = new Map(); }
        on(tipo, fn) {
            String(tipo).split(/\s+/).filter(Boolean).forEach((t) => { if (!this._h.has(t)) this._h.set(t, new Set()); this._h.get(t).add(fn); });
            return () => this.off(tipo, fn);
        }
        off(tipo, fn) { String(tipo).split(/\s+/).filter(Boolean).forEach((t) => { const s = this._h.get(t); if (s) s.delete(fn); }); }
        emitir(tipo, ev) {
            const s = this._h.get(tipo);
            if (!s) return;
            [...s].forEach((fn) => { try { fn(ev); } catch (e) { console.error('[SEVB mapa] error en el manejador de «' + tipo + '»', e); } });
        }
        limpiarEventos() { this._h.clear(); }
    }
    /** Elemento dibujable (marcador, línea, círculo). El adaptador implementa _mostrar(mapa), _ocultar() y _liberar(). */
    class Elemento extends Emisor {
        constructor(mapa) { super(); this.mapa = mapa; this.padre = null; this.visible = false; this.liberado = false; }
        addTo(padre) {
            if (this.liberado) return this;
            if (this.padre && this.padre !== padre) this.padre._sacar(this);
            this.padre = padre;
            padre._meter(this);
            return this;
        }
        /** Lo retira del mapa y libera sus listeners: ya no se puede volver a añadir. */
        remove() {
            if (this.padre) this.padre._sacar(this);
            this.padre = null;
            this._liberarTodo();
            return this;
        }
        _liberarTodo() {
            if (this.liberado) return;
            this._ocultarSiVisible();
            this.liberado = true;
            this._liberar();
            this.limpiarEventos();
        }
        /** Descripción neutra del elemento para diagnóstico y pruebas ({tipo, color, tooltip}); no depende del DOM del proveedor. */
        describir() { return { tipo: this.tipo || 'elemento', color: this.colorPrincipal ? this.colorPrincipal() : null, peso: this.pesoPrincipal ? this.pesoPrincipal() : null, tooltip: this.textoTooltip || '' }; }
        _mostrarSiHace(m) { if (!this.visible && !this.liberado) { this.visible = true; this._mostrar(m); } }
        _ocultarSiVisible() { if (this.visible) { this.visible = false; this._ocultar(); } }
        _mostrar() {} _ocultar() {} _liberar() {}
    }
    /** Grupo de elementos (y de otros grupos): se muestra y oculta entero. clear() libera todos los hijos. */
    class Grupo extends Elemento {
        constructor(mapa) { super(mapa); this.hijos = new Set(); this.esGrupo = true; }
        _meter(el) { this.hijos.add(el); if (this.visible) el._mostrarSiHace(this.mapa); }
        _sacar(el) { this.hijos.delete(el); el._ocultarSiVisible(); }
        add(el) { return el.addTo(this); }
        has(el) { return this.hijos.has(el); }
        /** Retira sin destruir: el elemento o grupo se puede volver a añadir con addTo. (remove() sí lo destruye.) */
        quitar(el) { if (this.hijos.has(el)) { this._sacar(el); el.padre = null; } return this; }
        get size() { return this.hijos.size; }
        clear() { [...this.hijos].forEach((h) => { this.hijos.delete(h); h.padre = null; h._liberarTodo(); }); return this; }
        /** Hojas visibles (recorre subgrupos): para diagnóstico y pruebas. */
        elementos() { const r = []; this.hijos.forEach((h) => { if (h.esGrupo) r.push(...h.elementos()); else if (h.visible) r.push(h.describir()); }); return r; }
        _mostrar(m) { this.hijos.forEach((h) => h._mostrarSiHace(m)); }
        _ocultar() { this.hijos.forEach((h) => h._ocultarSiVisible()); }
        _liberar() { this.clear(); }
    }
    /** Contenido de texto seguro (nunca HTML) para tooltips y popups: acepta texto o un Node ya construido. */
    function contenidoSeguro(c) {
        if (c instanceof Node) return c;
        const n = document.createElement('span');
        n.textContent = String(c ?? '');
        return n;
    }
    /** '8 6' | [8, 6] | null → [8, 6] | null */
    function dash(d) {
        if (!d) return null;
        const a = (Array.isArray(d) ? d : String(d).split(/[\s,]+/)).map(Number).filter((x) => Number.isFinite(x) && x >= 0);
        return a.length >= 2 ? [a[0], a[1]] : a.length === 1 ? [a[0], a[0]] : null;
    }
    const reducido = () => !!(S.Motion && S.Motion.reduced) || !!window.matchMedia?.('(prefers-reduced-motion: reduce)').matches;

    Object.assign(Mapa, { Emisor, Elemento, Grupo, contenidoSeguro, dash, mostrarError, quitarEstadoError, reducido });
    Object.assign(S, { Mapa, conMapa, publicar, esperar });
})();
