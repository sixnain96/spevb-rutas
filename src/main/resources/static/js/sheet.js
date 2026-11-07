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
    let state = 'peek', dragging = null, kbPx = 0;       // kbPx: alto del teclado virtual que levanta el panel (0 si no hay)
    const body = sheet.querySelector('.sheet-body');

    // ---- Desplazamiento de los resultados: el del USUARIO siempre tiene prioridad sobre cualquier posicionamiento automático.
    // El único movimiento automático es UNO, inicial, al llegar los resultados; se cancela con el primer toque, rueda, tecla o scroll manual.
    const auto = { pendiente: false, usuario: false, raf: 0, programatico: false };
    const soltarProgramatico = () => requestAnimationFrame(() => requestAnimationFrame(() => { auto.programatico = false; }));
    function cancelarAuto() { auto.pendiente = false; cancelAnimationFrame(auto.raf); }
    function marcarUsuario() {
        auto.usuario = true;
        cancelarAuto();
        window.dispatchEvent(new CustomEvent('spevb-resultados-interaccion'));       // panel-ocultable.js cancela su temporizador
    }
    if (body) {
        ['pointerdown', 'touchstart', 'wheel'].forEach((t) => body.addEventListener(t, marcarUsuario, { passive: true, capture: true }));
        body.addEventListener('keydown', (e) => { if (['PageDown', 'PageUp', 'ArrowDown', 'ArrowUp', 'Home', 'End', ' '].includes(e.key)) marcarUsuario(); });
        body.addEventListener('scroll', () => { if (!auto.programatico) marcarUsuario(); }, { passive: true });
    }
    // Un resultado NUEVO (la lista se vació y se volvió a llenar) reinicia la interacción: ahí sí corresponde un nuevo posicionamiento inicial.
    const caja = document.getElementById('tripResults');
    if (caja) {
        let vacia = true;
        new MutationObserver(() => {
            if (!caja.childElementCount) vacia = true;
            else if (vacia) { vacia = false; auto.usuario = false; auto.pendiente = false; }
        }).observe(caja, { childList: true });
    }
    /** Pone scrollTop sin que ese cambio cuente como del usuario (el evento scroll llega un cuadro después). */
    function fijarScroll(valor) {
        if (!body) return;
        const max = Math.max(0, body.scrollHeight - body.clientHeight), v = Math.max(0, Math.min(valor, max));
        if (Math.abs(body.scrollTop - v) < 1) return;
        auto.programatico = true;
        body.scrollTop = v;
        soltarProgramatico();
    }

    // Alto realmente visible: con el teclado abierto en iPhone la ventana no cambia pero el «visual viewport» sí.
    const viewH = () => Math.round(window.visualViewport?.height || window.innerHeight);
    const full = () => Math.round(viewH() * 0.94);
    const visible = (name) => name === 'peek' ? Math.min(268, Math.round(viewH() * 0.4))
        : name === 'half' ? Math.round(viewH() * 0.56) : full();

    // will-change solo mientras la hoja se mueve de verdad (arrastre o transición); después se retira para no mantener una capa de más.
    let wcTimer = 0;
    const moving = () => { sheet.style.willChange = 'transform'; clearTimeout(wcTimer); wcTimer = setTimeout(() => { sheet.style.willChange = ''; }, 420); };

    function apply(name, animate = true) {
        const leido = body ? body.scrollTop : 0;      // posición de lectura antes de medir de nuevo el panel
        state = name;
        sheet.dataset.state = name;
        sheet.style.transition = animate ? '' : 'none';
        if (animate && mobile.matches) moving();
        sheet.style.height = full() + 'px';
        // FUENTE DE VERDAD del layout (la única que escribe estas variables; panel-ocultable.js solo avisa con «spevb-panel»):
        //   --sheet-real    = píxeles del borde inferior de la pantalla que el panel ocupa de verdad (0 si está oculto; suma el teclado si lo levanta).
        //                      Con Google el contenedor del mapa termina justo encima (css/mapa.css), así sus créditos nunca quedan tapados.
        //   --sheet-visible = lo mismo pero con tope del 56 % (para botones y avisos flotantes); 0 si está oculto.
        //   --sheet-hidden  = lo que sobresale por debajo (relleno del cuerpo para que la lista llegue a la zona visible).
        const oculto = document.body.classList.contains('panel-oculto');
        if (mobile.matches) {
            sheet.style.transform = 'translateY(' + (full() - visible(name)) + 'px)';       // oculto: el CSS (!important) lo baja del todo; al mostrarlo vuelve a esta posición
            root.style.setProperty('--sheet-visible', oculto ? '0px' : Math.min(visible(name), Math.round(viewH() * 0.56)) + 'px');
            root.style.setProperty('--sheet-real', oculto ? '0px' : Math.min(window.innerHeight, visible(name) + kbPx) + 'px');
            root.style.setProperty('--sheet-hidden', Math.max(0, full() - visible(name)) + 'px');
        } else {
            sheet.style.transform = '';
            sheet.style.height = '';
            root.style.setProperty('--sheet-visible', '0px');
            root.style.setProperty('--sheet-real', '0px');
            root.style.setProperty('--sheet-hidden', '0px');
        }
        fijarScroll(leido);       // cambiar de tamaño (resize, barra del navegador, peek/half/full) no devuelve la lista al inicio
    }
    /* ESTADO DEL PANEL — tres cosas distintas que antes se mezclaban en una sola variable:
         manual    = lo que la PERSONA eligió (asa, arrastre, teclado en el asa, botones de la app como «Ver otras opciones»);
         temporal  = ajuste para poder escribir (campo enfocado / teclado visible): sube lo justo y se DESHACE al terminar de editar;
         state     = el efectivo (temporal || manual), el que se dibuja.
       Cualquier acción deliberada de la persona sobre el panel toma el control: borra el ajuste temporal y fija `manual`.
       `sheet.dataset.motivo` guarda el motivo de la última transición (asa, arrastre-asa, foco, teclado, resultados, punto-confirmado, gesto-cancelado…):
       sirve para depurar y para las pruebas. */
    let manual = 'peek', temporal = null;
    const motivo = (m) => { sheet.dataset.motivo = m; };
    function pedirEstado(name, porque) { manual = name; temporal = null; motivo(porque); apply(name); }
    function temporalmente(name, porque) {
        if (!mobile.matches || STATES.indexOf(name) <= STATES.indexOf(state)) return;      // ya hay espacio de sobra: no se toca
        temporal = name; motivo(porque); apply(name);
    }
    function terminarEdicion(porque) {
        if (!temporal) return;
        temporal = null; motivo(porque); apply(manual);        // vuelve a lo que la persona tenía (respeta una ampliación manual deliberada)
    }
    const go = (name, porque = 'app') => { if (mobile.matches) pedirEstado(name, porque); };

    let justDragged = false, tiempoClic = 0;
    handle.addEventListener('click', () => { if (!justDragged) go(state === 'peek' ? 'half' : state === 'half' ? 'full' : 'peek', 'asa'); });
    handle.addEventListener('keydown', (e) => {
        if (e.key === 'ArrowUp') { e.preventDefault(); go(STATES[Math.min(2, STATES.indexOf(state) + 1)], 'teclado-asa'); }
        if (e.key === 'ArrowDown') { e.preventDefault(); go(STATES[Math.max(0, STATES.indexOf(state) - 1)], 'teclado-asa'); }
    });

    // ARRASTRE DEL PANEL. Solo empieza con un pointerdown en el asa o la cabecera (botón principal del ratón / primer dedo); sigue ÚNICAMENTE a ese
    // puntero (pointerId) y escucha en la propia zona (con captura), no en window: así nada que ocurra sobre el mapa u otro puntero lo mueve.
    // Si el gesto se cancela (pointercancel, se pierde la captura, la ventana pierde el foco) se limpia y se vuelve a la posición coherente, sin lanzamiento.
    const dragZone = [handle, sheet.querySelector('.sheet-head')];
    function limpiarArrastre() { dragging = null; sheet.style.willChange = ''; }
    function cancelarArrastre(porque) {
        if (!dragging) return;
        const movio = dragging.moved, zona = dragging.zone;
        limpiarArrastre();
        // Un gesto cancelado con movimiento no es un clic: el clic que el navegador pueda generar al soltar después se ignora (hasta el próximo toque).
        justDragged = movio; clearTimeout(tiempoClic); tiempoClic = setTimeout(() => { justDragged = false; }, 8000);      // tope de seguridad
        if (movio) zona.addEventListener('pointerup', () => { clearTimeout(tiempoClic); tiempoClic = setTimeout(() => { justDragged = false; }, 50); }, { once: true });      // al soltar, el clic llega enseguida
        motivo('gesto-cancelado:' + porque);
        apply(state);                     // posición coherente: la del estado vigente
    }
    function terminarArrastre() {
        const current = Number((/translateY\(([-\d.]+)px\)/.exec(sheet.style.transform) || [0, dragging.base])[1]);
        const shown = full() - current - dragging.v * 180;       // la velocidad empuja hacia el siguiente punto
        const target = STATES.reduce((best, name) => Math.abs(visible(name) - shown) < Math.abs(visible(best) - shown) ? name : best, 'peek');
        justDragged = dragging.moved;          // el clic que el navegador genere al soltar tras un arrastre no cambia la altura de nuevo
        clearTimeout(tiempoClic); tiempoClic = setTimeout(() => { justDragged = false; }, 500);
        limpiarArrastre();
        pedirEstado(target, 'arrastre-asa');
    }
    dragZone.forEach((zone) => {
        zone.addEventListener('pointerdown', (e) => {
            if (!mobile.matches || e.target.closest('a, button:not(#sheetHandle)')) return;
            if (!e.isPrimary || (e.pointerType === 'mouse' && e.button !== 0)) return;
            if (dragging) cancelarArrastre('otro-gesto');
            clearTimeout(tiempoClic); justDragged = false;
            dragging = { id: e.pointerId, zone, y: e.clientY, base: full() - visible(state), moved: false, t: performance.now(), lastY: e.clientY, v: 0 };
            try { zone.setPointerCapture(e.pointerId); } catch { /* sin captura: se sigue con los eventos de la zona */ }
            sheet.style.transition = 'none';
            clearTimeout(wcTimer);
            sheet.style.willChange = 'transform';
        });
        zone.addEventListener('pointermove', (e) => {
            if (!dragging || e.pointerId !== dragging.id) return;      // otro puntero: no actualiza este arrastre
            const dy = e.clientY - dragging.y;
            if (Math.abs(dy) > 4) dragging.moved = true;
            const now = performance.now();
            dragging.v = (e.clientY - dragging.lastY) / Math.max(1, now - dragging.t);
            dragging.lastY = e.clientY; dragging.t = now;
            const y = Math.min(full() - visible('peek'), Math.max(0, dragging.base + dy));
            sheet.style.transform = 'translateY(' + y + 'px)';
        });
        zone.addEventListener('pointerup', (e) => { if (dragging && e.pointerId === dragging.id) terminarArrastre(); });
        zone.addEventListener('pointercancel', (e) => { if (dragging && e.pointerId === dragging.id) cancelarArrastre('pointercancel'); });
        zone.addEventListener('lostpointercapture', (e) => { if (dragging && e.pointerId === dragging.id) cancelarArrastre('captura-perdida'); });
    });
    window.addEventListener('blur', () => cancelarArrastre('ventana-sin-foco'));

    // Eventos de la aplicación: el panel se adapta a lo que la persona está haciendo.
    window.addEventListener('spevb-picking', () => go('peek', 'elegir-punto'));          // para ver el mapa mientras elige un punto
    window.addEventListener('spevb-focus-map', () => go('peek', 'ver-mapa'));
    // Con un solo punto puesto la pantalla NO se mueve (la pestañita «Ahora escribe B» avisa qué sigue); con los dos, el panel baja para ver el mapa.
    window.addEventListener('spevb-point-selected', () => {
        terminarEdicion('punto-confirmado');         // el ajuste para escribir se deshace: vuelve a la altura que la persona tenía
        const p = window.Spevb && window.Spevb.Planner && window.Spevb.Planner.puntos ? window.Spevb.Planner.puntos() : null;
        if (!p || (p.origen && p.destino)) go('peek', 'ambos-puntos');
    });
    /** Desplaza el cuerpo para que el elemento (por su posición NATURAL, no la fija de «sticky») quede arriba. */
    function alinearArriba(el, margen = 4) {
        if (!el || !body) return;
        fijarScroll(el.getBoundingClientRect().top - body.getBoundingClientRect().top + body.scrollTop - margen);
    }
    // Tras buscar, la prioridad es EMPEZAR: el panel queda compacto con el botón «Iniciar viaje» y la ruta elegida a la vista, y el mapa grande
    // con la ruta dibujada. Las demás rutas siguen ahí: «Ver otras opciones» (o arrastrar el panel hacia arriba) las muestra.
    // Si no hay rutas, sí se abre el panel para leer el mensaje. El posicionamiento se hace UNA vez y se cancela con el primer gesto de la persona.
    function irAlInicio(e) {
        const sinRutas = !!(e && e.detail && e.detail.sinRutas);
        go(sinRutas ? 'half' : 'peek', 'resultados');
        auto.pendiente = !auto.usuario;      // si la persona ya tocó la lista mientras el mapa dibujaba, no se posiciona nada
        cancelAnimationFrame(auto.raf);
        auto.raf = requestAnimationFrame(() => {
            auto.raf = requestAnimationFrame(() => {
                if (!auto.pendiente || auto.usuario || !mobile.matches || !body) return;
                auto.pendiente = false;
                const barra = document.getElementById('startTripBar');
                // El botón vive justo debajo de «Buscar rutas»: se alinea por el borde inferior de esa fila (su posición natural).
                const fila = document.querySelector('#planTrip')?.closest('.plan-actions');
                if (!sinRutas && barra && !barra.hidden && fila) alinearArriba({ getBoundingClientRect: () => ({ top: fila.getBoundingClientRect().bottom }) }, -3);      // sin dejar ver el borde del botón de arriba
                else alinearArriba(document.getElementById('tripResults'));
            });
        });
    }
    window.addEventListener('spevb-results', irAlInicio);
    window.addEventListener('spevb-ir-inicio', () => { go('half', 'ir-inicio'); if (body) fijarScroll(0); });      // casita del modo viaje: panel a media altura, arriba del todo
    // «Ver otras opciones»: abre el panel a media altura con la lista de rutas arriba (el comportamiento anterior, ahora bajo demanda).
    window.addEventListener('spevb-ver-opciones', () => {
        go('half', 'ver-opciones');
        marcarUsuario();
        requestAnimationFrame(() => requestAnimationFrame(() => alinearArriba(document.getElementById('tripResults'))));
    });
    // Enfocar un campo NO maximiza el panel por sí solo: sube a «half» (lo justo para escribir y ver sugerencias) y solo a «full» si el teclado
    // virtual está realmente visible (ver sincronizarTeclado). El ajuste es temporal: se deshace al confirmar el punto o al salir del campo.
    ['tripOrigin', 'tripDestination'].forEach((id) =>
        document.getElementById(id)?.addEventListener('focus', () => temporalmente(kbPx > 0 ? 'full' : 'half', 'foco')));

    // ---------------------------------------------------------------------------------------------------------------------------
    // TECLADO. En iPhone el teclado NO encoge la página: tapa su parte baja y el «visual viewport» es lo único que sabe cuánto queda a la vista;
    // el panel, anclado al borde de la página, quedaba debajo del teclado y el campo que se escribe no se veía. Aquí el panel se apoya sobre el
    // borde inferior de lo VISIBLE (--kb) y el campo enfocado se sube al inicio del panel para que el texto y sus sugerencias queden a la vista.
    // En Android (interactive-widget=resizes-content) la página sí encoge sola y --kb vale 0.
    // ---------------------------------------------------------------------------------------------------------------------------
    const vv = window.visualViewport;
    function alturaTeclado() {
        if (!vv) return 0;
        const kb = Math.round(window.innerHeight - (vv.offsetTop + vv.height));
        return kb > 70 ? kb : 0;          // por debajo de ≈ 70 px es la barra del navegador que se mueve, no un teclado
    }
    function ajustarSugerencias() {
        const borde = vv ? vv.offsetTop + vv.height : window.innerHeight;
        sheet.querySelectorAll('.place-results.suggestion-open').forEach((lista) => {
            lista.style.maxHeight = '';
            const arriba = lista.getBoundingClientRect().top;
            lista.style.maxHeight = Math.max(112, Math.min(300, Math.round(borde - arriba - 10))) + 'px';
        });
    }
    /** Sube el bloque del campo al inicio del cuerpo del panel (con un poco de aire): lo escrito y la lista de sugerencias quedan juntos y a la vista. */
    function mostrarCampo(campo) {
        if (!campo || !body || !sheet.contains(campo)) return;
        alinearArriba(campo.closest('.endpoint-block') || campo, 6);
        ajustarSugerencias();
    }
    let kbActual = -1;
    function sincronizarTeclado() {
        const kb = alturaTeclado();
        kbPx = kb;
        root.style.setProperty('--kb', kb + 'px');
        sheet.classList.toggle('teclado', kb > 0);
        sheet.classList.toggle('teclado-justo', kb > 0 && !!vv && vv.height < 420);      // con muy poco espacio visible, la cabecera cede su lugar al campo y a las sugerencias
        apply(state, false);                          // el alto visible cambió (teclado, barra del navegador): el panel se mide de nuevo y conserva su lectura
        const campo = document.activeElement;
        if (kb > 0 && campo && campo.tagName === 'INPUT' && sheet.contains(campo)) temporalmente('full', 'teclado');      // con el teclado visible hace falta más alto
        if (kb !== kbActual) {
            kbActual = kb;
            const f = document.activeElement;
            if (kb > 0 && f && f.tagName === 'INPUT' && sheet.contains(f)) requestAnimationFrame(() => mostrarCampo(f));
        } else ajustarSugerencias();
    }
    if (vv) {
        let vvTimer = null;
        const alCambiar = () => { clearTimeout(vvTimer); vvTimer = setTimeout(sincronizarTeclado, 60); };
        vv.addEventListener('resize', alCambiar);
        vv.addEventListener('scroll', alCambiar);          // iOS desplaza el visual viewport al abrir el teclado
    }
    sheet.addEventListener('focusin', (e) => {
        if (e.target.tagName !== 'INPUT') return;
        ultimoFoco = performance.now();
        // el teclado tarda en abrir: se reintenta cuando ya terminó de animarse (las posiciones cambian mientras sube)
        [80, 320, 700].forEach((ms) => setTimeout(() => { if (document.activeElement === e.target) { sincronizarTeclado(); mostrarCampo(e.target); } }, ms));
    });
    // Al cerrar el teclado iOS puede dejar la página desplazada: se vuelve al origen (el panel y el mapa nunca deben quedar corridos).
    sheet.addEventListener('focusout', () => setTimeout(() => { if (!sheet.contains(document.activeElement)) { terminarEdicion('fin-de-edicion'); window.scrollTo(0, 0); sincronizarTeclado(); } }, 120));

    // Campos de las ventanas (iniciar sesión, reportes, ajustes) y del menú lateral: al abrirse el teclado se desplazan dentro de su ventana hasta quedar a la vista.
    document.addEventListener('focusin', (e) => {
        const campo = e.target;
        if (!/^(INPUT|TEXTAREA|SELECT)$/.test(campo.tagName)) return;
        const cont = campo.closest('.modal-card, .side-menu');
        if (!cont) return;
        [120, 420, 800].forEach((ms) => setTimeout(() => {
            if (document.activeElement !== campo) return;
            const r = campo.getBoundingClientRect(), c = cont.getBoundingClientRect();
            const borde = Math.min(c.bottom, vv ? vv.offsetTop + vv.height : window.innerHeight) - 12;
            if (r.bottom > borde) cont.scrollTop += r.bottom - borde;
            else if (r.top < c.top + 8) cont.scrollTop -= c.top + 8 - r.top;
        }, ms));
    });

    // Mientras hay una lista de sugerencias abierta, el panel reserva espacio para que no se corte.
    const syncSuggesting = () => { sheet.classList.toggle('suggesting', !!sheet.querySelector('.place-results.suggestion-open')); requestAnimationFrame(ajustarSugerencias); };
    const watcher = new MutationObserver(syncSuggesting);
    ['originResults', 'destinationResults'].forEach((id) => {
        const box = document.getElementById(id);
        if (box) watcher.observe(box, { attributes: true, attributeFilter: ['class'], childList: true });
    });

    mobile.addEventListener('change', () => apply(state, false));
    // El panel se ocultó o se mostró (panel-ocultable.js): se recalculan las variables de layout; el estado (peek/half/full) se conserva.
    window.addEventListener('spevb-panel', () => apply(state, false));
    // Volver a la página desde segundo plano (o desde la caché de «atrás»): el alto visible y el teclado pudieron cambiar mientras no se veía.
    document.addEventListener('visibilitychange', () => { if (!document.hidden) { apply(state, false); sincronizarTeclado(); } });
    window.addEventListener('pageshow', (e) => { if (e.persisted) { apply(state, false); sincronizarTeclado(); } });
    window.addEventListener('resize', () => { apply(state, false); sincronizarTeclado(); });      // apply() conserva scrollTop
    let ultimoFoco = 0;
    apply('peek', false);
})();
