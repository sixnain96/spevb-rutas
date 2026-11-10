'use strict';
/* Coordinador de superficies (4.14). ÚNICA fuente de verdad de qué ocupa la pantalla en el celular.

   Superficies:
     · principal  → 'planificador' (A/B + resultados) o 'viaje' (tarjeta del viaje). Solo UNA es la principal a la vez.
     · overlay    → 'menu' (menú lateral) o un diálogo modal (ayuda, cuenta, reportes, confirmación). Reciben toda la interacción mientras están abiertos.
     · Las listas de sugerencias NO son superficies: pertenecen a su campo y a su panel.
   Qué se guarda: cuál es la principal, cuál CEDIÓ espacio y por qué, si hay viaje, qué overlay está abierto, a dónde vuelve el foco y la causa de cada cambio.

   Ceder espacio es PRESENTACIÓN: no destruye el proceso que representa. El GPS, la fase, los avisos y los campos A/B siguen vivos aunque la tarjeta del
   viaje o el planificador estén ocultos. Ocultar, minimizar, cerrar una vista temporal, cancelar, terminar el viaje y volver al inicio son acciones distintas:
     · ocultar/minimizar la tarjeta  → solo cambia cómo se ve (viaje.js, ui.modo); el viaje sigue.
     · cerrar el menú/diálogo         → devuelve la superficie que cedió, tal como estaba (sin reabrir lo que la persona ocultó).
     · terminar el viaje              → viaje.js (confirmación propia).
     · volver al inicio               → inicio.js (confirmación si hay viaje) y limpia SOLO el trabajo actual.
   Las aperturas automáticas (resultados, GPS, teclado, resize, animaciones, pistas) deben preguntar a puedeAbrir(causa): nunca abren por su cuenta mientras haya un
   overlay, nunca reabren lo que la persona ocultó y las actualizaciones ordinarias del GPS no reorganizan la pantalla. */
(() => {
    const S = window.Spevb || (window.Spevb = {});
    const doc = document, body = doc.body;
    const E = { principal: 'planificador', cedio: null, viaje: false, menu: false, modal: null, planificador: 'visible', ocultoPor: null, foco: null, causa: 'inicio' };
    const registro = [];
    // Mismo criterio que sheet.js (Spevb.compactLayout): en pantallas anchas el planificador es una tarjeta lateral y NO compite con la tarjeta del viaje: ahí no hay nada que ceder.
    const compacto = window.matchMedia('(max-width: 559px), (max-width: 860px) and (min-height: 481px)');
    let volverAlMenu = false, ultimoCierreMenu = -1e9;

    const $ = (id) => doc.getElementById(id);
    const hayModalAbierto = () => [...doc.querySelectorAll('.modal-backdrop')].some((m) => !m.hidden);
    const copia = () => ({ ...E, foco: !!E.foco, overlay: E.menu ? 'menu' : E.modal ? 'modal' : null });
    const nota = (causa, extra = '') => { E.causa = causa; registro.push({ t: Math.round(performance.now()), causa, principal: E.principal, menu: E.menu, modal: E.modal, viaje: E.viaje, extra }); if (registro.length > 80) registro.shift(); };

    // Elementos que NO deben recibir foco ni toques mientras un overlay está abierto (quedan «inert»). El aviso urgente del viaje se excluye a propósito.
    const DEBAJO = ['#sheet', '.map-area', '#panelShow', '#viajePastilla', '#siguiendoBus', '#menuFlotante'];
    function aplicar() {
        const overlay = E.menu || !!E.modal;
        const apilado = E.viaje && E.planificador === 'visible' && compacto.matches;      // celular con viaje y el planificador traído por la persona
        E.cedio = overlay ? (E.viaje ? 'viaje' : 'planificador') : (apilado ? 'viaje' : null);
        E.principal = E.viaje && (E.planificador !== 'visible' || !compacto.matches) ? 'viaje' : 'planificador';
        body.dataset.superficie = E.principal;
        body.dataset.overlay = E.menu ? 'menu' : E.modal ? 'modal' : '';
        body.classList.toggle('sup-overlay', overlay);
        body.classList.toggle('sup-menu', E.menu);
        // El bloqueo de scroll de la página es DERIVADO del estado (simétrico: cierra, cancela o falla y siempre se recalcula).
        body.classList.toggle('menu-open', E.menu);
        body.classList.toggle('modal-open', !!E.modal);
        DEBAJO.forEach((sel) => doc.querySelectorAll(sel).forEach((el) => {
            // Un viaje con el planificador oculto por el propio panel ya lo marca inert panel-ocultable.js: aquí solo se suma o se retira lo del overlay.
            if (el.id === 'sheet' && body.classList.contains('panel-oculto')) return;
            el.toggleAttribute('inert', overlay);
        }));
        const tarjeta = $('viajeCard');
        if (tarjeta) tarjeta.dataset.cede = overlay ? 'overlay' : (apilado ? 'planificador' : '');
        sincronizarFlotante();
        window.dispatchEvent(new CustomEvent('spevb-superficies', { detail: copia() }));
    }

    // ------------------------------------------------------------------ menú lateral
    const menu = $('sideMenu'), botonMenu = $('menuButton'), fondo = $('menuBackdrop');
    function ponerMenu(abrir, causa) {
        if (!menu || E.menu === abrir) return;
        E.menu = abrir;
        if (!abrir) ultimoCierreMenu = performance.now();
        if (abrir) {
            E.foco = doc.activeElement && doc.activeElement !== body ? doc.activeElement : botonMenu;
            if (window.Spevb.Siguiente) window.Spevb.Siguiente.quitar();
        }
        menu.classList.toggle('open', abrir);
        if (fondo) fondo.classList.toggle('hidden', !abrir);
        menu.setAttribute('aria-hidden', String(!abrir));
        menu.toggleAttribute('inert', !abrir);
        if (botonMenu) botonMenu.setAttribute('aria-expanded', String(abrir));
        nota(causa, abrir ? 'menu-abierto' : 'menu-cerrado');
        aplicar();
        if (abrir) {
            // El foco entra al menú (su título) sin abrir ningún teclado y sin desplazar la página.
            const cab = menu.querySelector('.side-menu-head h2') || menu;
            cab.setAttribute('tabindex', '-1'); cab.focus({ preventScroll: true });
        } else if (!E.modal) devolverFoco();
    }
    function devolverFoco() {
        const f = E.foco; E.foco = null;
        // Nunca se devuelve el foco a un campo de texto: abriría el teclado sin que la persona lo pida.
        if (f && f.isConnected && !f.hidden && !/^(INPUT|TEXTAREA|SELECT)$/.test(f.tagName) && !f.closest('[inert]')) { try { f.focus({ preventScroll: true }); } catch { /* sin foco */ } }
    }
    if (menu && botonMenu && fondo) {
        menu.toggleAttribute('inert', true);
        botonMenu.addEventListener('click', () => { volverAlMenu = false; ponerMenu(!E.menu, 'usuario:boton-menu'); });
        // Los demás módulos (cuenta, ayuda, atajos) cierran el menú con un click programático al abrir su diálogo: si ya hay un diálogo abierto, el menú
        // CEDIÓ ante él y debe volver al cerrarlo; si no lo hay, es un cierre normal y no se recuerda nada.
        const cerrar = $('closeMenu');
        if (cerrar) cerrar.addEventListener('click', () => { if (!hayModalAbierto()) volverAlMenu = false; ponerMenu(false, 'usuario:cerrar-menu'); });
        fondo.addEventListener('click', () => { volverAlMenu = false; ponerMenu(false, 'usuario:fondo'); });
        doc.addEventListener('keydown', (e) => { if (e.key === 'Escape' && E.menu && !E.modal) { volverAlMenu = false; ponerMenu(false, 'usuario:escape'); } });
    }

    // Con el planificador oculto (p. ej. durante un viaje) el botón del menú —que vive en su cabecera— quedaría inalcanzable: un botón flotante lo reemplaza.
    // Abre EL MISMO menú, por el mismo camino y con el mismo estado.
    const flotante = doc.createElement('button');
    flotante.type = 'button'; flotante.id = 'menuFlotante'; flotante.className = 'menu-flotante'; flotante.hidden = true;
    flotante.setAttribute('aria-label', 'Abrir menú'); flotante.setAttribute('aria-controls', 'sideMenu'); flotante.setAttribute('aria-expanded', 'false');
    flotante.innerHTML = '<svg viewBox="0 0 24 24" width="24" height="24" aria-hidden="true"><path d="M4 7h16M4 12h16M4 17h16" fill="none" stroke="currentColor" stroke-width="2.4" stroke-linecap="round"/></svg>';
    flotante.addEventListener('click', () => { volverAlMenu = false; ponerMenu(!E.menu, 'usuario:menu-flotante'); });
    (doc.querySelector('.app') || body).appendChild(flotante);
    const sincronizarFlotante = () => { flotante.hidden = E.planificador === 'visible'; flotante.setAttribute('aria-expanded', String(E.menu)); };

    // ------------------------------------------------------------------ diálogos modales (los observa: cualquier módulo los abre con `hidden`)
    function revisarModales() {
        const abierto = [...doc.querySelectorAll('.modal-backdrop')].find((m) => !m.hidden);
        const id = abierto ? abierto.id : null;
        if (id === E.modal) { // reconcilia una clase que otro módulo quitó o puso a mano
            if (body.classList.contains('modal-open') !== !!E.modal) body.classList.toggle('modal-open', !!E.modal);
            return;
        }
        const previo = E.modal;
        E.modal = id;
        if (id) {
            if (E.menu) { volverAlMenu = true; ponerMenuSinFoco(); }      // el menú cede ante el diálogo y vuelve cuando este se cierra
            else if (performance.now() - ultimoCierreMenu < 600) volverAlMenu = true;      // el módulo cerró el menú justo antes de abrir su diálogo
            else E.foco = E.foco || (doc.activeElement && doc.activeElement !== body ? doc.activeElement : botonMenu);
            nota('modal:abre', id);
        } else if (previo) {
            nota('modal:cierra', previo);
            if (volverAlMenu) { volverAlMenu = false; aplicar(); ponerMenu(true, 'modal:vuelve-al-menu'); return; }
            aplicar(); devolverFoco(); return;
        }
        aplicar();
    }
    function ponerMenuSinFoco() {
        const foco = E.foco; ponerMenu(false, 'modal:cede-menu'); E.foco = foco;
    }
    const observador = new MutationObserver(revisarModales);
    doc.querySelectorAll('.modal-backdrop').forEach((m) => observador.observe(m, { attributes: true, attributeFilter: ['hidden'] }));
    new MutationObserver(() => revisarModales()).observe(body, { attributes: true, attributeFilter: ['class'] });

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
    // Cambio de diseño en pleno viaje (girar el teléfono, cambiar el tamaño de la ventana): lo que cedió el VIAJE se acomoda al diseño nuevo; lo que ocultó la persona se respeta.
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
        menu: { abrir: (causa = 'api') => ponerMenu(true, causa), cerrar: (causa = 'api') => { volverAlMenu = false; ponerMenu(false, causa); }, abierto: () => E.menu },
        viajeIniciado, viajeTerminado, puedeAbrir,
        /** Cierra cualquier overlay (menú y diálogos que no sean una confirmación pendiente) sin devolver el menú. */
        cerrarOverlays(causa = 'api') { volverAlMenu = false; ponerMenu(false, causa); },
        aplicar,
    };
    nota('inicio'); aplicar();
})();
