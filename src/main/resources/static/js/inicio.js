'use strict';
/* «Volver al inicio» (4.14): UN solo camino desde el planificador, los resultados, el modo viaje y el menú principal.
   · Con un viaje activo (o uno pendiente de reanudar) pide una confirmación visible y comprensible: «¿Terminar el viaje y volver al inicio?»
     con «Seguir viajando» / «Terminar y volver al inicio». Nada de «tocar dos veces» sin explicación.
   · Sin viaje, limpia el trabajo actual de una vez.
   Limpia SOLO el trabajo actual (viaje, A y B, resultados, sugerencias, pistas, avisos, capas del mapa, búsquedas pendientes). La cuenta, las preferencias,
   los favoritos, el historial y los viajes guardados NO se tocan. Es idempotente: repetirla no produce errores, y las respuestas tardías de lo cancelado
   no restauran nada (cada módulo invalida sus operaciones pendientes con su propia generación). */
(() => {
    const S = window.Spevb;
    if (!S) return;
    const $ = (id) => document.getElementById(id);
    const reducido = () => !!(S.Motion && S.Motion.reduced);
    let modal = null, devolverA = null;

    function crearDialogo() {
        if (modal) return modal;
        modal = document.createElement('div');
        modal.id = 'inicioModal'; modal.className = 'modal-backdrop'; modal.hidden = true;
        modal.innerHTML = `
            <div class="modal-card inicio-card" role="alertdialog" aria-modal="true" aria-labelledby="inicioTitulo" aria-describedby="inicioTexto">
                <h2 id="inicioTitulo">¿Terminar el viaje y volver al inicio?</h2>
                <p id="inicioTexto" class="muted"></p>
                <div class="modal-actions inicio-acciones">
                    <button id="inicioSeguir" class="primary-plan-button" type="button">Seguir viajando</button>
                    <button id="inicioTerminar" class="secondary danger" type="button">Terminar y volver al inicio</button>
                </div>
            </div>`;
        document.body.appendChild(modal);
        $('inicioSeguir').addEventListener('click', () => cerrarDialogo());
        $('inicioTerminar').addEventListener('click', () => { cerrarDialogo(false); volver('confirmado'); });
        modal.addEventListener('click', (e) => { if (e.target === modal) cerrarDialogo(); });
        modal.addEventListener('keydown', (e) => {
            if (e.key === 'Escape') { e.stopPropagation(); cerrarDialogo(); }
            else if (e.key === 'Tab') {      // el foco no sale del diálogo
                const b = [$('inicioSeguir'), $('inicioTerminar')], i = b.indexOf(document.activeElement);
                e.preventDefault(); b[(i + (e.shiftKey ? b.length - 1 : 1)) % b.length].focus();
            }
        });
        return modal;
    }
    function abrirDialogo(pendiente) {
        crearDialogo();
        devolverA = document.activeElement;
        $('inicioTitulo').textContent = pendiente ? '¿Descartar el viaje pendiente y volver al inicio?' : '¿Terminar el viaje y volver al inicio?';
        $('inicioTexto').textContent = pendiente
            ? 'Tienes un viaje sin terminar. Si sigues, se descarta y se borran A y B. Tu cuenta, tus lugares y tus viajes guardados no se tocan.'
            : 'Se detiene el seguimiento y se borran A y B. Tu cuenta, tus lugares y tus viajes guardados no se tocan.';
        $('inicioSeguir').textContent = pendiente ? 'Volver sin descartar' : 'Seguir viajando';
        modal.hidden = false;
        $('inicioSeguir').focus({ preventScroll: true });      // la opción segura recibe el foco
    }
    function cerrarDialogo(devolverFoco = true) {
        if (!modal || modal.hidden) return;
        modal.hidden = true;
        if (devolverFoco && devolverA && devolverA.isConnected && !/^(INPUT|TEXTAREA|SELECT)$/.test(devolverA.tagName)) { try { devolverA.focus({ preventScroll: true }); } catch { /* nada */ } }
        devolverA = null;
    }

    const hayViaje = () => !!(S.Viaje && S.Viaje.activo() && !S.Viaje.llego());
    const hayPendiente = () => !!(S.Viaje && S.Viaje.pendiente && S.Viaje.pendiente());
    function hayTrabajo() {
        const p = S.Planner && S.Planner.puntos ? S.Planner.puntos() : {};
        return !!(p.origen || p.destino || ($('tripOrigin') && $('tripOrigin').value.trim()) || ($('tripDestination') && $('tripDestination').value.trim())
            || document.querySelector('#tripResults > *') || (S.Planner && S.Planner.seleccion));
    }

    /** Punto de entrada de los botones: confirma si hace falta y, si no, vuelve. */
    function pedir(origen = 'api') {
        if (hayViaje() || hayPendiente()) { abrirDialogo(!hayViaje() && hayPendiente()); return 'confirmacion'; }
        volver(origen);
        return 'limpio';
    }

    /** Hace el trabajo. Idempotente. Orden: primero se frena lo que corre (viaje, GPS, búsquedas), luego se limpia la vista. */
    function volver(causa = 'api') {
        const eraViaje = !!(S.Viaje && S.Viaje.activo());
        const habiaTrabajo = hayTrabajo() || eraViaje || hayPendiente();
        // 1) El viaje: se detiene su seguimiento GPS, el wake lock, los temporizadores y su capa del mapa; se borra su estado reanudable.
        try { S.Viaje && S.Viaje.detener(); } catch { /* ya terminado */ }
        try { S.Viaje && S.Viaje.descartarPendiente && S.Viaje.descartarPendiente(); } catch { /* nada pendiente */ }
        // 2) A y B, resultados, sugerencias, búsquedas pendientes, elegir en el mapa y rutas/capas dibujadas (cada operación se invalida por generación).
        try { S.Planner && S.Planner.limpiar && S.Planner.limpiar(); } catch { /* sin planificador */ }
        try { S.Planner && S.Planner.detenerGps && S.Planner.detenerGps(); } catch { /* sin GPS */ }
        try { window.SpevbExplorer && window.SpevbExplorer.clearRoute && window.SpevbExplorer.clearRoute(); } catch { /* sin ruta */ }
        // 3) Pistas, avisos y preguntas viejos fuera.
        try { S.Siguiente && S.Siguiente.quitar(); } catch { /* sin pista */ }
        ['estado', 'gps', 'pick', 'viaje'].forEach((c) => { try { S.toast && S.toast.cerrar(c); } catch { /* nada */ } });
        // 4) Solo la interfaz inicial: ningún menú ni diálogo encima y el planificador a la vista.
        cerrarDialogo(false);
        if (S.Superficies) S.Superficies.cerrarOverlays('inicio:' + causa);
        if (S.Panel) { S.Panel.mostrar('inicio'); }
        // 5) Mapa en una vista inicial apropiada y panel listo para buscar (sin abrir el teclado).
        const m = window.SpevbExplorer && window.SpevbExplorer.map;
        if (m) { try { m.stop && m.stop(); m.setView(S.center, 13, { animate: !reducido() }); } catch { /* mapa no listo */ } }
        const a = document.activeElement;
        if (a && /^(INPUT|TEXTAREA|SELECT)$/.test(a.tagName)) a.blur();
        window.dispatchEvent(new CustomEvent('spevb-ir-inicio'));
        const estado = $('tripStatus');
        if (estado) { estado.textContent = 'Marca A y B en el mapa o escribe una dirección.'; estado.className = 'status info'; }
        // 6) El foco va a un lugar coherente (el título del panel) sin abrir nada.
        const titulo = document.querySelector('#sheet .brand h1');
        if (titulo) { titulo.setAttribute('tabindex', '-1'); titulo.focus({ preventScroll: true }); }
        window.dispatchEvent(new CustomEvent('spevb-inicio', { detail: { causa, habiaTrabajo } }));
        if (habiaTrabajo && S.toast) S.toast('Listo: empezamos de nuevo.', { tipo: 'info', ms: 2500, clave: 'inicio' });
        return habiaTrabajo;
    }

    // ---- accesos: la marca del planificador (antes recargaba la página), el menú y la casita del viaje (viaje.js)
    document.querySelectorAll('.sheet-head .brand').forEach((a) => {
        a.setAttribute('aria-label', 'Volver al inicio');
        a.addEventListener('click', (e) => { e.preventDefault(); pedir('planificador'); });
    });
    document.querySelectorAll('.menu-links a[href="/"], #menuInicio').forEach((el) => el.addEventListener('click', (e) => { e.preventDefault(); pedir('menu'); }));

    S.Inicio = { pedir, volver, hayTrabajo, abierto: () => !!(modal && !modal.hidden) };
})();
