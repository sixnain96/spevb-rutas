'use strict';
/* Coordinador de superficies (4.14). Fuente de verdad de QUÉ ocupa la pantalla en el celular. Se apoya en lo que ya existe (4.13.5):
   el menú y los diálogos los abre, enfoca y cierra `modales.js` (Spevb.Modal: foco, Tab, Escape, fondo inert); el panel del planificador lo oculta
   `panel-ocultable.js` (Spevb.Panel); la tarjeta del viaje es de `viaje.js`. Este módulo NO compite con ellos: los OBSERVA, deriva un único estado y decide
   la PRESENTACIÓN de lo demás (la tarjeta cede, el aviso importante sale reducido y arriba, el planificador cede ante el viaje).

   Estado: principal ('planificador' | 'viaje'), overlay ('menu' | 'modal' | null), si hay viaje, quién cedió, el estado del planificador y su causa.
   Ceder espacio es PRESENTACIÓN: no destruye el proceso (GPS, fase, avisos y A/B siguen vivos). Ocultar, minimizar, cerrar una vista temporal, cancelar,
   terminar el viaje y volver al inicio son acciones distintas (ver docs/UX-MOVIL-4.14.md). Las aperturas automáticas (resultados, GPS, teclado, resize, pistas,
   respuestas tardías) deben preguntar a puedeAbrir(causa). */
(() => {
    const S = window.Spevb || (window.Spevb = {});
    const doc = document, body = doc.body;
    const E = { principal: 'planificador', cedio: null, viaje: false, menu: false, modal: null, planificador: 'visible', ocultoPor: null, causa: 'inicio' };
    const registro = [];
    // Mismo criterio que sheet.js: en pantallas anchas el planificador es una tarjeta lateral y NO compite con la tarjeta del viaje: ahí no hay nada que ceder.
    const compacto = window.matchMedia('(max-width: 559px), (max-width: 860px) and (min-height: 481px)');
    const $ = (id) => doc.getElementById(id);
    const menu = $('sideMenu'), botonMenu = $('menuButton');
    const copia = () => ({ ...E, overlay: E.menu ? 'menu' : E.modal ? 'modal' : null });
    const nota = (causa, extra = '') => { E.causa = causa; registro.push({ t: Math.round(performance.now()), causa, principal: E.principal, menu: E.menu, modal: E.modal, viaje: E.viaje, extra }); if (registro.length > 80) registro.shift(); };

    function aplicar() {
        const overlay = E.menu || !!E.modal;
        const apilado = E.viaje && E.planificador === 'visible' && compacto.matches;      // celular con viaje y el planificador traído por la persona
        E.cedio = overlay ? (E.viaje ? 'viaje' : 'planificador') : (apilado ? 'viaje' : null);
        E.principal = E.viaje && (E.planificador !== 'visible' || !compacto.matches) ? 'viaje' : 'planificador';
        body.dataset.superficie = E.principal;
        body.dataset.overlay = E.menu ? 'menu' : E.modal ? 'modal' : '';
        body.classList.toggle('sup-overlay', overlay);
        body.classList.toggle('sup-menu', E.menu);
        const tarjeta = $('viajeCard');
        if (tarjeta) {
            tarjeta.dataset.cede = overlay ? 'overlay' : (apilado ? 'planificador' : '');
            // modales.js pone `inert` a todo lo que cuelga de <body>: el aviso importante del viaje debe seguir siendo operable encima del menú.
            if (tarjeta.dataset.urgente) { tarjeta.setAttribute('data-modal-exento', ''); tarjeta.inert = false; } else tarjeta.removeAttribute('data-modal-exento');
        }
        sincronizarFlotante();
        window.dispatchEvent(new CustomEvent('spevb-superficies', { detail: copia() }));
    }

    // Con el planificador oculto (p. ej. durante un viaje) el botón del menú —que vive en su cabecera— quedaría inalcanzable: un botón flotante abre EL MISMO menú.
    const flotante = doc.createElement('button');
    flotante.type = 'button'; flotante.id = 'menuFlotante'; flotante.className = 'menu-flotante'; flotante.hidden = true;
    flotante.setAttribute('aria-label', 'Abrir menú'); flotante.setAttribute('aria-controls', 'sideMenu'); flotante.setAttribute('aria-expanded', 'false');
    flotante.innerHTML = '<svg viewBox="0 0 24 24" width="24" height="24" aria-hidden="true"><path d="M4 7h16M4 12h16M4 17h16" fill="none" stroke="currentColor" stroke-width="2.4" stroke-linecap="round"/></svg>';
    flotante.addEventListener('click', () => { if (botonMenu) botonMenu.click(); });      // por el mismo camino que el botón de la cabecera (ui.js + modales.js)
    (doc.querySelector('.app') || body).appendChild(flotante);
    function sincronizarFlotante() { flotante.hidden = E.planificador === 'visible'; flotante.setAttribute('aria-expanded', String(E.menu)); }

    // ------------------------------------------------------------------ menú (observado)
    function revisarMenu() {
        const abierto = !!menu && menu.classList.contains('open');
        if (abierto === E.menu) return;
        E.menu = abierto;
        if (abierto && S.Siguiente) S.Siguiente.quitar();
        nota(abierto ? 'menu:abre' : 'menu:cierra');
        aplicar();
    }
    if (menu) new MutationObserver(revisarMenu).observe(menu, { attributes: true, attributeFilter: ['class'] });

    // ------------------------------------------------------------------ diálogos (observados: cualquier módulo los abre con `hidden`)
    function revisarModales() {
        const abierto = [...doc.querySelectorAll('.modal-backdrop')].find((m) => !m.hidden);
        const id = abierto ? abierto.id : null;
        if (id === E.modal) return;
        const previo = E.modal;
        E.modal = id;
        if (id) nota('modal:abre', id);
        else if (previo) nota('modal:cierra', previo);      // el foco vuelve al botón que lo abrió (modales.js); el menú NO se reabre solo
        aplicar();
    }
    const obs = new MutationObserver(revisarModales);
    doc.querySelectorAll('.modal-backdrop').forEach((m) => obs.observe(m, { attributes: true, attributeFilter: ['hidden'] }));
    new MutationObserver((muts) => { for (const m of muts) for (const n of m.addedNodes) if (n.nodeType === 1 && n.classList && n.classList.contains('modal-backdrop')) { obs.observe(n, { attributes: true, attributeFilter: ['hidden'] }); revisarModales(); } }).observe(body, { childList: true });

    // ------------------------------------------------------------------ planificador y viaje
    const Panel = () => S.Panel;
    function viajeIniciado(causa = 'viaje:inicia') {
        E.viaje = true;
        // La tarjeta del viaje pasa a ser la superficie principal: el planificador cede (se oculta, sin perder lo escrito). Solo en celular/ventana angosta.
        if (E.planificador === 'visible' && compacto.matches && Panel()) Panel().ocultar('viaje');
        nota(causa); aplicar();
    }
    function viajeTerminado(causa = 'viaje:termina') {
        E.viaje = false;
        // Si el planificador lo ocultó el propio viaje, se recupera; si lo ocultó la persona, se respeta.
        if (E.ocultoPor === 'viaje' && Panel()) Panel().mostrar('viaje-terminado');
        nota(causa); aplicar();
    }
    // Cambio de diseño en pleno viaje (girar el teléfono): lo que cedió el VIAJE se acomoda; lo que ocultó la persona se respeta.
    const alCambiarDiseno = () => {
        if (!E.viaje || !Panel()) return aplicar();
        if (!compacto.matches && E.ocultoPor === 'viaje') Panel().mostrar('diseno');
        else if (compacto.matches && E.planificador === 'visible') Panel().ocultar('viaje');
        else aplicar();
    };
    if (compacto.addEventListener) compacto.addEventListener('change', alCambiarDiseno);
    window.addEventListener('spevb-panel', (e) => {
        const d = e.detail || {};
        E.planificador = d.oculto ? 'oculto' : 'visible';
        E.ocultoPor = d.oculto ? (d.causa || 'usuario') : null;
        nota('planificador:' + (d.oculto ? 'oculto' : 'visible'), d.causa || '');
        aplicar();
    });

    /** ¿Puede abrirse AHORA una superficie por una causa que no es un gesto directo de la persona? */
    function puedeAbrir(causa) {
        const overlay = E.menu || !!E.modal;
        let ok = true, motivo = '';
        if (['gps', 'teclado', 'resize', 'animacion', 'pista', 'respuesta-tardia'].includes(causa)) { ok = false; motivo = 'no abre por su cuenta'; }
        else if (overlay) { ok = false; motivo = 'hay un menú o diálogo abierto'; }
        else if (causa === 'resultados' && E.viaje) { ok = false; motivo = 'el viaje es la superficie principal'; }
        else if (causa === 'resultados' && E.planificador === 'oculto' && E.ocultoPor === 'usuario') { ok = false; motivo = 'la persona ocultó el panel'; }
        if (!ok) nota('rechazada:' + causa, motivo);
        return ok;
    }

    S.Superficies = {
        estado: copia, registro: () => registro.slice(),
        menu: { abierto: () => E.menu, abrir: () => { if (!E.menu && botonMenu) botonMenu.click(); }, cerrar: () => { if (E.menu && $('closeMenu')) $('closeMenu').click(); } },
        viajeIniciado, viajeTerminado, puedeAbrir,
        /** Cierra el menú (sin devolverlo luego). Los diálogos los cierra quien los abrió. */
        cerrarOverlays() { if (E.menu && $('closeMenu')) $('closeMenu').click(); },
        aplicar,
    };
    revisarMenu(); revisarModales(); nota('inicio'); aplicar();
})();
