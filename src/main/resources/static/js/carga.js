'use strict';
/* Estado de carga y recuperacion (4.13.5): busqueda de direcciones, calculo del viaje y carga de una ruta.
   Spevb.Carga.iniciar({ contenedor, boton, texto, ancla, etapa, alCancelar, alReintentar }) devuelve { fin(), activo }.
   · aria-busy en el contenedor correcto y texto «Buscando…» (o el que se indique) en el boton mientras dura.
   · Progreso indeterminado ligero (barra CSS; sin movimiento con prefers-reduced-motion o ajuste de movimiento reducido).
   · A los 2,5 s explica la etapa; a los 8 s ofrece Cancelar y Reintentar (si se dieron las funciones).
   · fin() restaura boton, texto, aria-busy y foco en exito, error, aborto o tiempo agotado. Llamarlo varias veces es inofensivo. */
(() => {
    const S = (window.Spevb = window.Spevb || {});
    const T_ETAPA = 2500, T_ACCIONES = 8000;

    function iniciar({ contenedor, boton = null, texto = 'Buscando…', ancla = null, etapa = 'Sigue en marcha: estamos consultando los recorridos. Puede tardar unos segundos con señal lenta.',
        alCancelar = null, alReintentar = null, foco = null } = {}) {
        const teniaFoco = foco !== null ? foco : !!boton && document.activeElement === boton;
        let activo = true;
        let bloque = null, tEtapa = null, tAcciones = null;
        const previo = { aria: contenedor ? contenedor.getAttribute('aria-busy') : null };
        if (contenedor) contenedor.setAttribute('aria-busy', 'true');
        if (boton) {
            previo.html = boton.innerHTML; previo.label = boton.getAttribute('aria-label'); previo.title = boton.getAttribute('title'); previo.et = boton.dataset.etiqueta;
            boton.dataset.cargando = '1';
            const conIcono = !!boton.querySelector('svg');
            if (!conIcono) boton.textContent = texto;
            else {
                if (boton.dataset.etiqueta !== undefined) boton.dataset.etiqueta = texto;
                boton.setAttribute('aria-label', texto); boton.setAttribute('title', texto);
            }
        }
        bloque = document.createElement('div');
        bloque.className = 'carga-estado';
        bloque.innerHTML = '<span class="carga-barra" aria-hidden="true"></span><p class="carga-etapa" role="status" aria-live="polite"></p><div class="carga-acciones" hidden></div>';
        const parrafo = bloque.querySelector('.carga-etapa'), acciones = bloque.querySelector('.carga-acciones');
        const ref = ancla || null;
        if (ref && ref.parentNode) ref.parentNode.insertBefore(bloque, ref); else if (contenedor) contenedor.append(bloque);

        tEtapa = setTimeout(() => { if (activo) parrafo.textContent = typeof etapa === 'function' ? etapa() : etapa; }, T_ETAPA);
        tAcciones = setTimeout(() => {
            if (!activo || (!alCancelar && !alReintentar)) return;
            parrafo.textContent = 'Está tardando más de lo normal. Puedes seguir esperando, cancelar o intentarlo de nuevo.';
            const crear = (clase, etiqueta, fn) => {
                const b = document.createElement('button');
                b.type = 'button'; b.className = clase; b.textContent = etiqueta;
                b.addEventListener('click', () => { handle.fin(); fn(); });
                return b;
            };
            if (alCancelar) acciones.append(crear('secondary', 'Cancelar', alCancelar));
            if (alReintentar) acciones.append(crear('secondary', 'Reintentar', alReintentar));
            acciones.hidden = false;
        }, T_ACCIONES);

        const handle = {
            get activo() { return activo; },
            fin() {
                if (!activo) return;
                activo = false;
                clearTimeout(tEtapa); clearTimeout(tAcciones);
                const teniaEnBloque = bloque && bloque.contains(document.activeElement);
                if (bloque) bloque.remove();
                if (contenedor) { if (previo.aria === null) contenedor.removeAttribute('aria-busy'); else contenedor.setAttribute('aria-busy', previo.aria); }
                if (boton) {
                    delete boton.dataset.cargando;
                    if (previo.html !== undefined && !boton.querySelector('svg') && boton.innerHTML !== previo.html) boton.innerHTML = previo.html;
                    if (previo.et !== undefined) boton.dataset.etiqueta = previo.et;
                    if (previo.label === null || previo.label === undefined) boton.removeAttribute('aria-label'); else boton.setAttribute('aria-label', previo.label);
                    if (previo.title === null || previo.title === undefined) boton.removeAttribute('title'); else boton.setAttribute('title', previo.title);
                    // Foco: solo si la persona lo tenia en el boton (o en el bloque que desaparece) y no lo movio a otra parte.
                    // El botón puede seguir deshabilitado unos instantes (lo reactiva quien lo deshabilitó): se reintenta hasta que pueda recibir el foco.
                    if (teniaFoco || teniaEnBloque) {
                        let intentos = 0;
                        const devolver = () => {
                            const a = document.activeElement;
                            if (a && a !== document.body && a !== boton) return;              // la persona ya movió el foco a otra parte
                            if (boton.disabled || !document.contains(boton)) { if (++intentos < 20) setTimeout(devolver, 50); return; }
                            try { boton.focus({ preventScroll: true }); } catch { /* sin foco */ }
                        };
                        devolver();
                    }
                }
            },
        };
        return handle;
    }

    /** Acciones de recuperacion para el pasajero: reintentar, revisar A y B, buscar rutas cercanas y reportar el problema. */
    function accionesRecuperacion({ reintentar = null, revisar = null, cercanas = null, reportar = null } = {}) {
        const caja = document.createElement('div');
        caja.className = 'recuperar-acciones';
        const crear = (clase, texto, fn) => { const b = document.createElement('button'); b.type = 'button'; b.className = clase; b.textContent = texto; b.addEventListener('click', fn); caja.append(b); return b; };
        if (reintentar) crear('primary-plan-button', 'Reintentar', reintentar);
        if (revisar) crear('secondary', 'Revisar A y B', revisar);
        if (cercanas) crear('secondary', 'Buscar rutas cercanas', cercanas);
        if (reportar) crear('text-button', 'Reportar el problema', reportar);
        return caja;
    }

    /** Detalle tecnico: solo en la consola; en pantalla unicamente con ?debug=1 (sin datos sensibles: solo el mensaje del error). */
    const depurando = () => { try { return new URLSearchParams(location.search).get('debug') === '1'; } catch { return false; } };
    function detalleTecnico(error, contexto) {
        try { console.warn('[SEVB] ' + contexto + ':', error && error.message ? error.message : error); } catch { /* sin consola */ }
        return depurando() && error && error.message ? ' (' + String(error.message).slice(0, 200) + ')' : '';
    }

    S.Carga = { iniciar, accionesRecuperacion, detalleTecnico, depurando };
})();
