'use strict';
/* Ocultar el panel del planificador para ver el mapa completo (como Google Maps / Apple Maps).
   Solo agrega: el panel no se borra ni pierde lo escrito; se esconde y vuelve con el botón «Buscar ruta». */
(() => {
    const sheet = document.getElementById('sheet');
    const head = sheet && sheet.querySelector('.sheet-head');
    const menuButton = document.getElementById('menuButton');
    if (!sheet || !head || !menuButton) return;
    const body = document.body;
    const movil = window.matchMedia('(max-width: 559px), (max-width: 860px) and (min-height: 481px)');

    const chevron = '<svg viewBox="0 0 24 24" width="22" height="22" aria-hidden="true"><path d="M6 9l6 6 6-6" fill="none" stroke="currentColor" stroke-width="2.4" stroke-linecap="round" stroke-linejoin="round"/></svg>';
    const lupa = '<svg viewBox="0 0 24 24" width="20" height="20" aria-hidden="true"><circle cx="10.5" cy="10.5" r="6" fill="none" stroke="currentColor" stroke-width="2.4"/><path d="M15 15l5 5" stroke="currentColor" stroke-width="2.4" stroke-linecap="round"/></svg>';

    const ocultar = document.createElement('button');
    ocultar.id = 'panelHide'; ocultar.type = 'button'; ocultar.className = 'panel-hide';
    ocultar.setAttribute('aria-label', 'Ocultar el panel para ver el mapa'); ocultar.title = 'Ocultar el panel';
    ocultar.innerHTML = chevron;
    menuButton.parentNode.insertBefore(ocultar, menuButton);

    const mostrar = document.createElement('button');
    mostrar.id = 'panelShow'; mostrar.type = 'button'; mostrar.className = 'panel-show'; mostrar.hidden = true;
    mostrar.setAttribute('aria-label', 'Mostrar el panel para buscar una ruta');
    mostrar.innerHTML = lupa + '<span>Buscar ruta</span>';
    (document.querySelector('.app') || body).appendChild(mostrar);

    /** Ocultar el panel es solo PRESENTACIÓN: no borra ni cancela nada. `causa` dice quién lo pidió: 'usuario' (se respeta, no se reabre solo),
     *  'viaje' (el viaje cedió el sitio: se recupera al terminar), 'auto' (la opción «bajar el panel al buscar»), etc. */
    let ocultoPor = null;
    function poner(oculto, causa = 'usuario') {
        if (oculto === body.classList.contains('panel-oculto')) return;      // ya está así: nada que hacer (idempotente)
        ocultoPor = oculto ? causa : null;
        body.classList.toggle('panel-oculto', oculto);
        sheet.toggleAttribute('inert', oculto || body.classList.contains('sup-overlay'));
        sheet.setAttribute('aria-hidden', oculto ? 'true' : 'false');
        mostrar.hidden = !oculto;
        // El foco solo se mueve cuando la persona lo pidió con un gesto: un cambio automático no debe robarlo (p. ej. a quien escribe en un campo).
        if (causa === 'usuario') { if (oculto) mostrar.focus({ preventScroll: true }); else ocultar.focus({ preventScroll: true }); }
        window.dispatchEvent(new CustomEvent('spevb-panel', { detail: { oculto, causa } }));
    }
    ocultar.addEventListener('click', () => poner(true, 'usuario'));
    mostrar.addEventListener('click', () => poner(false, 'usuario'));
    window.Spevb = window.Spevb || {};
    window.Spevb.Panel = {
        ocultar: (causa = 'usuario') => poner(true, causa),
        mostrar: (causa = 'usuario') => poner(false, causa),
        oculto: () => body.classList.contains('panel-oculto'),
        ocultoPor: () => ocultoPor,
    };

    // Celular: deslizar hacia abajo el asa o la cabecera cuando el panel ya está asomado lo oculta.
    // El gesto pertenece al asa donde empezó (pointerId) y una cancelación nunca cuenta como «deslizar para ocultar».
    let inicio = null;
    [sheet.querySelector('#sheetHandle'), head].forEach((zona) => zona && zona.addEventListener('pointerdown', (e) => {
        if (!movil.matches || e.target.closest('button, a')) return;
        inicio = { y: e.clientY, estado: sheet.dataset.state, id: e.pointerId };
    }));
    window.addEventListener('pointerup', (e) => {
        if (!inicio || e.pointerId !== inicio.id) return;
        const { y, estado } = inicio; inicio = null;
        if (estado === 'peek' && e.clientY - y > 70) poner(true, 'usuario');
    });
    const cancelarGesto = (e) => { if (!inicio || !e || e.pointerId === undefined || e.pointerId === inicio.id) inicio = null; };
    window.addEventListener('pointercancel', cancelarGesto);
    window.addEventListener('blur', () => { inicio = null; });

    // Al llegar los resultados el panel se queda a la vista con «Iniciar viaje» (lo normal). Solo si la persona activó «Bajar el panel al buscar»
    // (celular) baja solo y deja el mapa libre, con un botón grande «Ver rutas» para volver.
    const etiqueta = mostrar.querySelector('span');
    const automatico = () => movil.matches && !!(window.Spevb && window.Spevb.Ajustes && window.Spevb.Ajustes.get('panelBajar') === true);
    let temporizador = null;
    window.addEventListener('spevb-results', () => {
        etiqueta.textContent = 'Ver rutas';
        mostrar.setAttribute('aria-label', 'Mostrar las rutas encontradas');
        clearTimeout(temporizador);
        const Sup = window.Spevb && window.Spevb.Superficies;
        if (automatico()) {
            temporizador = setTimeout(() => {
                if (body.classList.contains('panel-oculto') || (Sup && !Sup.puedeAbrir('resultados'))) return;
                poner(true, 'auto');
                mostrar.classList.add('pulsa');
                setTimeout(() => mostrar.classList.remove('pulsa'), 4500);
            }, 1100);
        // Los resultados reabren el panel SOLO si no hay un menú/diálogo abierto, no hay viaje y la persona no lo ocultó ella misma (botón «Ver rutas» queda disponible).
        } else if (body.classList.contains('panel-oculto') && ocultoPor !== 'usuario' && (!Sup || Sup.puedeAbrir('resultados'))) poner(false, 'resultados');
    });
    // Si la persona toca el panel antes de que baje solo, se respeta lo que está haciendo.
    const respetar = () => clearTimeout(temporizador);
    ['pointerdown', 'touchstart', 'wheel'].forEach((t) => sheet.addEventListener(t, respetar, { passive: true, capture: true }));
    window.addEventListener('spevb-resultados-interaccion', respetar);      // la persona ya está revisando los resultados: el panel no se oculta solo
    window.addEventListener('keydown', (e) => {
        if (e.key === 'Escape' && !body.classList.contains('panel-oculto') && sheet.contains(document.activeElement) && !movil.matches
            && document.activeElement.tagName !== 'INPUT') poner(true, 'usuario');
    });
})();
