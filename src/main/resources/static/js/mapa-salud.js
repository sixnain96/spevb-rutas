'use strict';
/* Salud del mapa y estados visibles del viaje (4.14).
   Problema observado en un viaje real: el fondo del mapa pierde calles «por bloques» mientras el GPS, la línea y la distancia siguen avanzando. Google no avisa de
   esa degradación con un error, así que aquí se vigilan señales observables y se ofrece una salida comprensible, sin tocar el viaje:
     · internet: online/offline del navegador;
     · mapa:     el adaptador (adaptador-google.js / adaptador-leaflet.js) emite «salud» (tiles que no terminan de cargar, pérdida del contexto WebGL…);
     · ubicación: la publica viaje.js (última lectura vieja = «sin ubicación actual»).
   Los tres se ven DISTINTO (un solo aviso por tipo; no se acumulan) y ninguno oculta un aviso importante de bajada: dentro de la tarjeta del viaje van en su propia fila
   y, si hay una pregunta o aviso urgente, la fila pasa a una sola línea compacta.
   Recuperación: «Recuperar mapa» (manual, siempre disponible durante el viaje) y automática con reintentos LIMITADOS y espera progresiva (sin bucles):
     intento 1 = repintado suave · 2 = repintado suave tras espera · 3 = reconstruir el mapa conservando cámara, referencias y capas. Al volver internet se reintenta una vez.
   Qué NO hace: no cambia de proveedor en silencio, no toca el viaje, el GPS ni las capas (las conserva el adaptador al reconstruir). */
(() => {
    const S = window.Spevb || (window.Spevb = {});
    const ESPERAS_MS = [2000, 5000, 12000];       // espera progresiva antes de cada intento automático (máximo 3)
    const REGISTRO = [];                           // diagnóstico: transiciones recientes (para pruebas y soporte)
    const nota = (m, x) => { REGISTRO.push({ t: Math.round(performance.now()), m, x: x || '' }); if (REGISTRO.length > 60) REGISTRO.shift(); };

    // ---------------------------------------------------------------- estados visibles (un aviso por tipo)
    const TIPOS = {
        internet: { clase: 'sin-internet', icono: 'wifi-off', orden: 0 },
        ubicacion: { clase: 'sin-ubicacion', icono: 'gps', orden: 1 },
        mapa: { clase: 'mapa-incompleto', icono: 'mapa', orden: 2 },
    };
    const ICONOS = {
        'wifi-off': '<svg viewBox="0 0 24 24" width="18" height="18" aria-hidden="true"><path d="M3 3l18 18M5.2 9.6a11 11 0 0 1 3.4-1.9M10.8 5.1A11 11 0 0 1 21 9.6M8.3 13a6.5 6.5 0 0 1 2.2-1.3M13.7 11.2A6.5 6.5 0 0 1 15.7 13M12 18h.01" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"/></svg>',
        gps: '<svg viewBox="0 0 24 24" width="18" height="18" aria-hidden="true"><circle cx="12" cy="12" r="3.5" fill="none" stroke="currentColor" stroke-width="2"/><path d="M12 2v4M12 18v4M2 12h4M18 12h4M4 4l16 16" stroke="currentColor" stroke-width="2" stroke-linecap="round"/></svg>',
        mapa: '<svg viewBox="0 0 24 24" width="18" height="18" aria-hidden="true"><path d="M3 6l6-2 6 2 6-2v14l-6 2-6-2-6 2zM9 4v14M15 6v14" fill="none" stroke="currentColor" stroke-width="2" stroke-linejoin="round"/></svg>',
    };
    const actuales = {};                           // tipo → { texto, accion: { etiqueta, alHacer } | null }
    let contenedores = [];

    function ranura() {
        // Durante el viaje van dentro de la tarjeta (nunca flotando encima de una advertencia); fuera del viaje, en una barra pequeña arriba.
        const dentro = document.getElementById('viajeEstados');
        if (dentro && document.body.classList.contains('viaje-activo')) return dentro;
        let barra = document.getElementById('estadosPagina');
        if (!barra) {
            barra = document.createElement('div');
            barra.id = 'estadosPagina'; barra.className = 'estados-viaje estados-pagina'; barra.setAttribute('role', 'status'); barra.setAttribute('aria-live', 'polite');
            document.body.appendChild(barra);
        }
        return barra;
    }
    function pintar() {
        const destino = ranura();
        for (const el of document.querySelectorAll('.estados-viaje')) if (el !== destino) el.replaceChildren();      // nunca en dos sitios a la vez
        const lista = Object.keys(actuales).sort((a, b) => TIPOS[a].orden - TIPOS[b].orden);
        destino.hidden = !lista.length;
        const nodos = lista.map((tipo) => {
            const e = actuales[tipo], d = document.createElement('div');
            d.className = 'estado-pildora ' + TIPOS[tipo].clase; d.dataset.tipo = tipo;
            const ic = document.createElement('span'); ic.className = 'estado-icono'; ic.innerHTML = ICONOS[TIPOS[tipo].icono]; ic.setAttribute('aria-hidden', 'true');
            const tx = document.createElement('span'); tx.className = 'estado-texto'; tx.textContent = e.texto;      // texto, nunca HTML
            d.append(ic, tx);
            if (e.accion) {
                const b = document.createElement('button'); b.type = 'button'; b.className = 'estado-accion'; b.textContent = e.accion.etiqueta; b.disabled = !!e.accion.deshabilitada;
                b.addEventListener('click', e.accion.alHacer); d.append(b);
            }
            return d;
        });
        destino.replaceChildren(...nodos);
        window.dispatchEvent(new CustomEvent('spevb-estados', { detail: Object.fromEntries(lista.map((t) => [t, actuales[t].texto])) }));
    }
    /** Pone (o retira con null) el aviso de un tipo. Idempotente: repetir lo mismo no repinta. */
    function poner(tipo, datos) {
        if (!TIPOS[tipo]) return;
        const previo = actuales[tipo];
        if (!datos) { if (!previo) return; delete actuales[tipo]; pintar(); return; }
        if (previo && previo.texto === datos.texto && (!!previo.accion === !!datos.accion) && (!previo.accion || (previo.accion.etiqueta === datos.accion.etiqueta && !!previo.accion.deshabilitada === !!datos.accion.deshabilitada))) { previo.accion = datos.accion; return; }
        actuales[tipo] = datos; pintar();
    }

    // ---------------------------------------------------------------- recuperación del mapa
    const e = { mapa: null, intentos: 0, recuperando: false, temporizador: null, problema: null, apagado: false, desconectar: null };
    const mapaActual = () => (S.maps && S.maps.map) || null;

    function mensajeMapa() {
        if (e.recuperando) return 'Recuperando el mapa… (intento ' + Math.min(e.intentos + 1, ESPERAS_MS.length) + ' de ' + ESPERAS_MS.length + '). Tus indicaciones siguen activas.';
        if (e.intentos >= ESPERAS_MS.length) return 'No pude recuperar el mapa. Sigue con las indicaciones de la tarjeta; puedes intentarlo otra vez.';
        return 'El mapa se ve incompleto. Tu ubicación y las indicaciones siguen funcionando.';
    }
    function mostrarMapa() {
        if (!e.problema) return poner('mapa', null);
        poner('mapa', { texto: mensajeMapa(), accion: { etiqueta: 'Recuperar mapa', deshabilitada: e.recuperando, alHacer: () => recuperar({ manual: true }) } });
    }
    function limpiarTemporizador() { clearTimeout(e.temporizador); e.temporizador = null; }

    /** Un intento de recuperación. nivel: 1 = repintado suave, 2 = repintado suave (tras espera), 3 = reconstruir. Devuelve true si el mapa volvió a dibujar. */
    async function intentar(nivel) {
        const m = mapaActual();
        if (!m) return false;
        e.recuperando = true; mostrarMapa(); nota('intento', nivel);
        try {
            if (typeof m.recuperar !== 'function') return false;
            return !!(await m.recuperar(nivel >= 3 ? 'reconstruir' : 'suave'));
        } catch (err) {
            nota('error', (err && err.message) || String(err));
            return false;
        } finally { e.recuperando = false; }
    }
    function programarSiguiente() {
        limpiarTemporizador();
        if (e.apagado || !e.problema || e.intentos >= ESPERAS_MS.length) return mostrarMapa();
        if (navigator.onLine === false) return mostrarMapa();      // sin internet no se insiste: se espera al evento «online»
        const espera = ESPERAS_MS[e.intentos];
        e.temporizador = setTimeout(async () => {
            e.temporizador = null;
            if (e.apagado || !e.problema) return;
            const nivel = e.intentos + 1;
            const ok = await intentar(nivel);
            e.intentos += 1;
            if (ok) return resuelto('auto'); // volvió a dibujar
            programarSiguiente();
        }, espera);
        mostrarMapa();
    }
    function resuelto(como) { limpiarTemporizador(); e.problema = null; e.intentos = 0; e.recuperando = false; nota('resuelto', como); mostrarMapa(); }
    function problema(motivo) {
        if (e.apagado) return;
        nota('problema', motivo);
        const nuevo = !e.problema;
        e.problema = motivo || 'desconocido';
        if (nuevo) { e.intentos = 0; programarSiguiente(); } else mostrarMapa();
    }
    /** Acción manual «Recuperar mapa» (también desde «Más» en la tarjeta del viaje): reinicia el contador y prueba suave y, si hace falta, reconstruir. */
    async function recuperar({ manual = false } = {}) {
        if (e.recuperando) return false;
        limpiarTemporizador();
        e.problema = e.problema || (manual ? 'manual' : 'desconocido');
        let ok = await intentar(1);
        if (!ok) ok = await intentar(3);
        if (ok) { resuelto(manual ? 'manual' : 'auto'); return true; }
        e.intentos = ESPERAS_MS.length; mostrarMapa(); return false;      // tras una recuperación manual fallida no hay más intentos automáticos hasta otra señal
    }

    function conectar(m) {
        if (e.mapa === m) return;
        if (e.desconectar) e.desconectar();
        e.mapa = m;
        const alSalud = (ev) => { if (ev && ev.estado === 'ok') { if (e.problema) resuelto('senal'); } else problema(ev && ev.motivo); };
        if (typeof m.on === 'function') m.on('salud', alSalud);
        e.desconectar = () => { try { m.off && m.off('salud', alSalud); } catch { /* ya no existe */ } e.mapa = null; };
        nota('conectado');
    }
    function buscarMapa() { const m = mapaActual(); if (m) conectar(m); }

    // ---------------------------------------------------------------- internet
    function internet() {
        if (navigator.onLine === false) {
            poner('internet', { texto: 'Sin internet. El mapa puede verse incompleto; tus indicaciones guardadas siguen disponibles.' });
            nota('offline');
        } else {
            const estaba = !!actuales.internet;
            poner('internet', null);
            if (estaba) { nota('online'); if (e.problema) { e.intentos = 0; programarSiguiente(); } }      // al volver internet se intenta recuperar el fondo (sin bucle: el contador se reinicia una vez)
        }
    }
    window.addEventListener('offline', internet);
    window.addEventListener('online', internet);
    // Al volver a la página (pantalla bloqueada, otra app) el fondo puede haber perdido calles aunque no haya fallado nada visible: se pide un repintado suave UNA vez.
    document.addEventListener('visibilitychange', () => {
        if (document.hidden) return;
        const m = mapaActual();
        if (m && typeof m.recuperar === 'function' && document.body.classList.contains('viaje-activo') && !e.problema) { nota('regreso'); m.recuperar('suave').catch(() => {}); }
    });
    window.addEventListener('pageshow', (ev) => { if (ev.persisted) internet(); });
    window.addEventListener('spevb-viaje-modo', () => pintar());      // la ranura cambia de sitio al empezar o terminar el viaje
    internet();
    const sondeo = setInterval(() => { buscarMapa(); if (e.mapa) clearInterval(sondeo); }, 700);
    setTimeout(() => clearInterval(sondeo), 60000);

    S.MapaSalud = {
        poner, recuperar, problema, estado: () => ({ problema: e.problema, intentos: e.intentos, recuperando: e.recuperando, avisos: Object.fromEntries(Object.entries(actuales).map(([k, v]) => [k, v.texto])) }),
        registro: () => REGISTRO.slice(), conectar, detener() { e.apagado = true; limpiarTemporizador(); },
    };
})();
