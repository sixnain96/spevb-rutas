'use strict';
/* Panel inferior arrastrable (móvil). Tres alturas: asomado, medio y completo.
   En escritorio el panel es una tarjeta flotante y este módulo no hace nada. */
(() => {
    const sheet = document.getElementById('sheet');
    const handle = document.getElementById('sheetHandle');
    if (!sheet || !handle) return;
    const mobile = window.matchMedia('(max-width: 559px), (max-width: 860px) and (min-height: 481px)');   // = Spevb.compactLayout
    const root = document.documentElement;
    const STATES = ['peek', 'half', 'full'];
    let state = 'peek', dragging = null;

    // Alto realmente visible: con el teclado abierto en iPhone la ventana no cambia pero el «visual viewport» sí.
    const viewH = () => Math.round(window.visualViewport?.height || window.innerHeight);
    const full = () => Math.round(viewH() * 0.94);
    const visible = (name) => name === 'peek' ? Math.min(268, Math.round(viewH() * 0.4))
        : name === 'half' ? Math.round(viewH() * 0.56) : full();

    function apply(name, animate = true) {
        state = name;
        sheet.dataset.state = name;
        sheet.style.transition = animate ? '' : 'none';
        sheet.style.height = full() + 'px';
        if (mobile.matches) {
            sheet.style.transform = 'translateY(' + (full() - visible(name)) + 'px)';
            // Los controles flotantes del mapa suben con el panel, sin tapar más de la mitad.
            root.style.setProperty('--sheet-visible', Math.min(visible(name), Math.round(viewH() * 0.56)) + 'px');
        } else {
            sheet.style.transform = '';
            sheet.style.height = '';
            root.style.setProperty('--sheet-visible', '0px');
        }
    }
    const go = (name) => { if (mobile.matches) apply(name); };

    let justDragged = false;
    handle.addEventListener('click', () => { if (!justDragged) go(state === 'peek' ? 'half' : state === 'half' ? 'full' : 'peek'); });
    handle.addEventListener('keydown', (e) => {
        if (e.key === 'ArrowUp') { e.preventDefault(); go(STATES[Math.min(2, STATES.indexOf(state) + 1)]); }
        if (e.key === 'ArrowDown') { e.preventDefault(); go(STATES[Math.max(0, STATES.indexOf(state) - 1)]); }
    });

    // Arrastre desde el asa o la cabecera.
    const dragZone = [handle, sheet.querySelector('.sheet-head')];
    dragZone.forEach((zone) => zone.addEventListener('pointerdown', (e) => {
        if (!mobile.matches || e.target.closest('a, button:not(#sheetHandle)')) return;
        dragging = { y: e.clientY, base: full() - visible(state), moved: false, t: performance.now(), lastY: e.clientY, v: 0 };
        zone.setPointerCapture(e.pointerId);
        sheet.style.transition = 'none';
    }));
    window.addEventListener('pointermove', (e) => {
        if (!dragging) return;
        const dy = e.clientY - dragging.y;
        if (Math.abs(dy) > 4) dragging.moved = true;
        const now = performance.now();
        dragging.v = (e.clientY - dragging.lastY) / Math.max(1, now - dragging.t);
        dragging.lastY = e.clientY; dragging.t = now;
        const y = Math.min(full() - visible('peek'), Math.max(0, dragging.base + dy));
        sheet.style.transform = 'translateY(' + y + 'px)';
    });
    const release = () => {
        if (!dragging) return;
        const current = Number((/translateY\(([-\d.]+)px\)/.exec(sheet.style.transform) || [0, dragging.base])[1]);
        const shown = full() - current - dragging.v * 180; // la velocidad empuja hacia el siguiente punto
        const target = STATES.reduce((best, name) => Math.abs(visible(name) - shown) < Math.abs(visible(best) - shown) ? name : best, 'peek');
        justDragged = dragging.moved;
        apply(target);
        dragging = null;
        setTimeout(() => { justDragged = false; }, 0);
    };
    window.addEventListener('pointerup', release);
    window.addEventListener('pointercancel', release);

    // Eventos de la aplicación: el panel se adapta a lo que la persona está haciendo.
    window.addEventListener('spevb-picking', () => go('peek'));          // para ver el mapa mientras elige un punto
    window.addEventListener('spevb-focus-map', () => go('peek'));
    window.addEventListener('spevb-point-selected', () => go('peek'));
    window.addEventListener('spevb-results', () => {
        go('half');
        // Con el panel a media altura, los resultados deben quedar a la vista y no el formulario.
        setTimeout(() => {
            const body = sheet.querySelector('.sheet-body'), results = document.getElementById('tripResults');
            if (!mobile.matches || !body || !results) return;
            const top = results.getBoundingClientRect().top - body.getBoundingClientRect().top + body.scrollTop - 6;
            body.scrollTo({ top, behavior: 'smooth' });
        }, 350);
    });
    ['tripOrigin', 'tripDestination'].forEach((id) =>
        document.getElementById(id)?.addEventListener('focus', () => go('full')));

    // Mientras hay una lista de sugerencias abierta, el panel reserva espacio para que no se corte.
    const syncSuggesting = () => sheet.classList.toggle('suggesting',
        !!sheet.querySelector('.place-results.suggestion-open'));
    const watcher = new MutationObserver(syncSuggesting);
    ['originResults', 'destinationResults'].forEach((id) => {
        const box = document.getElementById(id);
        if (box) watcher.observe(box, { attributes: true, attributeFilter: ['class'] });
    });

    mobile.addEventListener('change', () => apply(state, false));
    window.addEventListener('resize', () => apply(state, false));
    if (window.visualViewport) {
        // Teclado que se abre o se cierra: el panel se vuelve a medir y el campo enfocado queda a la vista.
        let vvTimer = null;
        window.visualViewport.addEventListener('resize', () => {
            clearTimeout(vvTimer);
            vvTimer = setTimeout(() => {
                apply(state, false);
                const f = document.activeElement;
                if (f && f.tagName === 'INPUT' && sheet.contains(f)) f.scrollIntoView({ block: 'center', behavior: 'smooth' });
            }, 120);
        });
    }
    apply('peek', false);
})();
