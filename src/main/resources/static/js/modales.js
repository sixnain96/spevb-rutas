'use strict';
/* Controlador unico de ventanas modales y del menu lateral (4.13.5).
   Lo usan authModal, settingsModal, nameModal, reportModal, bienvenida, ayudaModal y el menu lateral. Una sola implementacion de:
   · foco inicial configurable, · trampa de Tab/Shift+Tab, · Escape (solo la ventana de arriba), · regreso del foco a quien la abrio,
   · fondo `inert` (sin clic, sin foco, fuera del arbol de accesibilidad), · un unico manejador global de teclado.
   La logica de la trampa de foco es la que ya usaba la guia (guia.js); ahora vive aqui y la guia la reutiliza.
   Interfaz: Spevb.Modal.abrir(el, opciones) / .cerrar(el, { devolver }) / .atrapar(evento, ventana) / .recordarAbridor() / .devolverFoco(el) / .abierta(el) */
(() => {
    const S = (window.Spevb = window.Spevb || {});
    const FOCO = 'button:not([hidden]):not([disabled]), summary, a[href], input:not([disabled]):not([type="hidden"]), select:not([disabled]), textarea:not([disabled]), [tabindex]:not([tabindex="-1"])';
    const pila = [];               // ventanas abiertas, la ultima es la de arriba
    const marcados = new Set();    // elementos a los que ESTE controlador puso inert (no se toca el inert que ya tuvieran por otra razon)

    const enfocables = (ventana) => [...ventana.querySelectorAll(FOCO)].filter((el) => !el.hidden && el.getClientRects().length > 0 && getComputedStyle(el).visibility !== 'hidden');

    function atrapar(e, ventana) {
        if (e.key !== 'Tab' || !ventana) return;
        const f = enfocables(ventana);
        if (!f.length) { e.preventDefault(); return; }
        const i = f.indexOf(document.activeElement);
        const sig = e.shiftKey ? (i <= 0 ? f.length - 1 : i - 1) : (i === f.length - 1 ? 0 : i + 1);
        e.preventDefault(); f[sig].focus({ preventScroll: true });
    }
    const recordarAbridor = () => { const a = document.activeElement; return a && a !== document.body ? a : null; };
    function devolverFoco(a) {
        if (a && document.contains(a) && !a.closest('[inert]') && a.getClientRects().length > 0) { try { a.focus({ preventScroll: true }); } catch { /* sin foco */ } }
    }

    /** Pone `inert` a todo lo que cuelga de <body> salvo la ventana de arriba, sus elementos exentos (data-modal-exento) y los avisos de estado. */
    function actualizarFondo() {
        const arriba = pila[pila.length - 1];
        marcados.forEach((el) => { if (!arriba || el === arriba.raiz) { el.inert = false; marcados.delete(el); } });
        if (!arriba) return;
        for (const el of document.body.children) {
            if (el === arriba.raiz || el.tagName === 'SCRIPT' || el.tagName === 'STYLE' || el.tagName === 'LINK' || el.hasAttribute('data-modal-exento')
                || el.id === 'spevbToasts' || el.id === 'spevbMapToasts' || el.id === 'coach' || arriba.exentos.includes(el)) continue;
            if (!el.inert) { el.inert = true; marcados.add(el); }
        }
        // Una ventana anidada dentro de un contenedor de <body> (el menu va dentro de <body>, no dentro de otra cosa): la raiz es siempre hija directa de <body>.
    }
    function sincronizarBody() {
        const hayModal = pila.some((v) => v.el.classList.contains('modal-backdrop')) || [...document.querySelectorAll('.modal-backdrop')].some((m) => !m.hidden);
        document.body.classList.toggle('modal-open', hayModal);
    }

    function abrir(el, o = {}) {
        if (!el) return;
        const previa = pila.findIndex((v) => v.el === el);
        if (previa >= 0) pila.splice(previa, 1);
        let raiz = el; while (raiz.parentElement && raiz.parentElement !== document.body) raiz = raiz.parentElement;
        const v = {
            el, raiz, tarjeta: o.tarjeta || el.querySelector('.modal-card') || el, escape: o.escape !== false, fondo: o.fondo === true,
            cerrar: o.cerrar || (() => cerrar(el)), alCerrar: o.alCerrar || null, ocultar: o.ocultar || null,
            exentos: o.exentos || [], abridor: o.abridor !== undefined ? o.abridor : recordarAbridor(),
        };
        pila.push(v);
        (o.mostrar || ((x) => { x.hidden = false; }))(el);
        sincronizarBody();
        actualizarFondo();
        const poner = () => {
            if (!pila.includes(v)) return;
            let destino = typeof o.foco === 'function' ? o.foco() : typeof o.foco === 'string' ? el.querySelector(o.foco) : o.foco;
            if (!destino || destino.getClientRects().length === 0) destino = enfocables(v.tarjeta)[0] || v.tarjeta;
            if (destino === v.tarjeta && !v.tarjeta.hasAttribute('tabindex')) v.tarjeta.setAttribute('tabindex', '-1');
            try { destino.focus({ preventScroll: true }); } catch { /* sin foco */ }
            if (o.seleccionar && destino.select) destino.select();
        };
        poner();                                       // ya visible: el foco entra de inmediato
        if (o.retardo) setTimeout(poner, o.retardo);   // pantallas con animacion de entrada
        return v;
    }

    function cerrar(el, { devolver = true } = {}) {
        const i = pila.findIndex((v) => v.el === el);
        const v = i >= 0 ? pila[i] : null;
        if (i >= 0) pila.splice(i, 1);
        if (v && v.ocultar) v.ocultar(el); else if (el.classList.contains('modal-backdrop') || !v) el.hidden = true;
        sincronizarBody();
        actualizarFondo();
        if (v && v.alCerrar) v.alCerrar();
        if (v && devolver) devolverFoco(v.abridor);
    }

    // Un unico manejador global: Escape y Tab los atiende solo la ventana de arriba.
    document.addEventListener('keydown', (e) => {
        const v = pila[pila.length - 1];
        if (!v || e.defaultPrevented) return;
        if (e.key === 'Escape' && v.escape) { e.preventDefault(); v.cerrar(); return; }
        if (e.key === 'Tab') atrapar(e, v.tarjeta);
    });
    // Toque en el fondo oscuro: solo en las ventanas que lo permiten.
    document.addEventListener('click', (e) => {
        const v = pila[pila.length - 1];
        if (v && v.fondo && e.target === v.el) v.cerrar();
    });

    S.Modal = { abrir, cerrar, atrapar, recordarAbridor, devolverFoco, FOCO, enfocables, abierta: (el) => pila.some((v) => v.el === el), hayAbierta: () => pila.length > 0 };
})();
