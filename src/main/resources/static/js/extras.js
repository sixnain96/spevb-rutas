'use strict';
/* Funciones añadidas al planificador (no cambian cómo se calcula nada):
   · Compartir viaje: enlace con A y B que abre la app y busca solo.
   · Mis lugares: favoritos (Casa, Trabajo, Universidad…) guardados en el teléfono para usarlos como A o B.
   · Avisarme al bajar: con el GPS, avisa (vibración, sonido y mensaje) al acercarte a donde debes bajarte.
   · Reportar un problema: ruta o caminata mal trazada, dirección equivocada, sugerencias. */
(() => {
    const $ = (id) => document.getElementById(id);
    const S = window.Spevb;
    if (!$('tripTools') || !S) return;
    const leer = (k, v) => { try { return JSON.parse(localStorage.getItem(k)) ?? v; } catch { return v; } };
    const escribir = (k, v) => { try { localStorage.setItem(k, JSON.stringify(v)); return true; } catch { return false; } };
    const P = () => S.Planner;
    let aviso = null;            // aviso al bajar activo: { gps, siguiente, avisado1, avisado2, wake }
    const hav = (a, b) => S.Gps ? S.Gps.distancia(a, b) : 0;

    // ------------------------------------------------------------------ barra de herramientas del resultado
    const barra = $('tripTools');
    const mostrarBarra = () => { barra.hidden = !P().seleccion; if (barra.hidden) detenerAviso(true); else if (aviso) reiniciarAviso(); };
    window.addEventListener('spevb-seleccion', mostrarBarra);

    // ------------------------------------------------------------------ compartir
    const num = (n) => Number(n).toFixed(6);
    function enlace() {
        const { origen, destino } = P().puntos();
        if (!origen || !destino) return null;
        const q = new URLSearchParams({ a: num(origen.lat) + ',' + num(origen.lng), b: num(destino.lat) + ',' + num(destino.lng) });
        if (origen.nombre && !/^\d/.test(origen.nombre) && !/^Punto en mapa/.test(origen.nombre)) q.set('an', origen.nombre.slice(0, 80));
        if (destino.nombre && !/^\d/.test(destino.nombre) && !/^Punto en mapa/.test(destino.nombre)) q.set('bn', destino.nombre.slice(0, 80));
        return location.origin + '/?' + q.toString();
    }
    $('shareTrip').onclick = async () => {
        const url = enlace();
        if (!url) return S.toast('Primero elige A y B.', { tipo: 'info', ms: 3000 });
        const { origen, destino } = P().puntos();
        const texto = 'Mi viaje en bus por Villavicencio: de ' + origen.nombre + ' a ' + destino.nombre;
        try {
            if (navigator.share) { await navigator.share({ title: 'SPEVB · Mi viaje', text: texto, url }); return; }
        } catch (e) { if (e.name === 'AbortError') return; }
        try { await navigator.clipboard.writeText(url); S.toast('Enlace copiado. Pégalo en WhatsApp o donde quieras.', { tipo: 'success', ms: 4000, clave: 'compartir' }); }
        catch { window.prompt('Copia este enlace:', url); }
    };
    // Al abrir un enlace compartido: se ponen A y B y se busca.
    function abrirCompartido() {
        const q = new URLSearchParams(location.search);
        const par = (v) => { const m = /^(-?\d+(?:\.\d+)?),(-?\d+(?:\.\d+)?)$/.exec(v || ''); return m ? { lat: +m[1], lng: +m[2] } : null; };
        const a = par(q.get('a')), b = par(q.get('b'));
        if (!a || !b) return;
        const ok = (p) => p.lat > 3.9 && p.lat < 4.4 && p.lng > -73.9 && p.lng < -73.4;
        history.replaceState(null, '', location.pathname);
        if (!ok(a) || !ok(b)) return S.toast('El enlace tiene puntos fuera de Villavicencio.', { tipo: 'error', ms: 6000 });
        const punto = (c, n) => ({ id: null, ...c, nombre: (n || '').slice(0, 80) || c.lat.toFixed(6) + ', ' + c.lng.toFixed(6), direccion: null, fuente: 'Enlace', confirmado: true });
        P().poner('origin', punto(a, q.get('an')));
        P().poner('destination', punto(b, q.get('bn')));
        setTimeout(() => P().buscar(), 500);
    }

    // ------------------------------------------------------------------ mis lugares (favoritos)
    const K_FAV = 'spevb-favoritos', MAX_FAV = 12;
    const favoritos = () => (S.Datos ? S.Datos.leer('lugares') : leer(K_FAV, []));
    const guardarFavoritos = (lista) => (S.Datos ? S.Datos.escribir('lugares', lista) : escribir(K_FAV, lista));
    function pintarFavoritos() {
        const lista = $('favList'), fav = favoritos();
        lista.replaceChildren();
        $('favCount').textContent = fav.length ? String(fav.length) : '';
        if (!fav.length) { const p = document.createElement('p'); p.className = 'fav-empty'; p.textContent = 'Aún no guardas lugares. Elige A o B y guárdalo aquí con un nombre.'; lista.append(p); return; }
        fav.forEach((f) => {
            const fila = document.createElement('div');
            fila.className = 'fav-row';
            const nombre = document.createElement('span');
            nombre.className = 'fav-name';
            nombre.textContent = f.nombre;
            const mk = (txt, etiqueta, fn, cls = '') => { const b = document.createElement('button'); b.type = 'button'; b.className = 'fav-btn ' + cls; b.textContent = txt; b.setAttribute('aria-label', etiqueta + ' ' + f.nombre); b.onclick = fn; return b; };
            const punto = () => ({ id: null, lat: f.lat, lng: f.lng, nombre: f.nombre, direccion: null, fuente: 'Favorito', confirmado: true });
            fila.append(nombre,
                mk('A', 'Usar como origen', () => P().poner('origin', punto())),
                mk('B', 'Usar como destino', () => P().poner('destination', punto())),
                mk('✕', 'Quitar', () => { guardarFavoritos(favoritos().filter((x) => x.id !== f.id)); }, 'fav-del'));
            lista.append(fila);
        });
    }
    function guardarFavorito(modo) {
        const p = P().puntos()[modo === 'origin' ? 'origen' : 'destino'];
        const nombre = $('favName').value.trim().slice(0, 40);
        if (!p) return S.toast('Primero elige el punto ' + (modo === 'origin' ? 'A' : 'B') + '.', { tipo: 'info', ms: 3000 });
        if (!nombre) { $('favName').focus(); return S.toast('Escribe un nombre (Casa, Trabajo, Universidad…).', { tipo: 'info', ms: 3500 }); }
        const lista = favoritos().filter((f) => f.nombre.toLowerCase() !== nombre.toLowerCase());
        if (lista.length >= MAX_FAV) return S.toast('Máximo ' + MAX_FAV + ' lugares. Quita alguno para añadir otro.', { tipo: 'info', ms: 4000 });
        lista.unshift({ id: Date.now().toString(36), nombre, lat: p.lat, lng: p.lng });
        guardarFavoritos(lista);
        $('favName').value = '';
        S.toast('«' + nombre + '» guardado.', { tipo: 'success', ms: 2500, clave: 'fav' });
    }
    $('favSaveA').onclick = () => guardarFavorito('origin');
    $('favSaveB').onclick = () => guardarFavorito('destination');
    window.addEventListener('spevb-datos', (e) => { if (e.detail.clave === 'lugares') pintarFavoritos(); });
    pintarFavoritos();

    // ------------------------------------------------------------------ avisarme al bajar
    const RADIO_CERCA = 600, RADIO_YA = 220, RADIO_LLEGO = 60;
    let audio = null;     // un solo contexto, creado al tocar el botón (iOS no deja crearlos sin un toque)
    function pitido(veces) {
        try {
            audio = audio || new (window.AudioContext || window.webkitAudioContext)();
            const ctx = audio;
            if (ctx.state === 'suspended') ctx.resume();
            for (let i = 0; i < veces; i++) {
                const o = ctx.createOscillator(), g = ctx.createGain();
                o.frequency.value = 880; o.connect(g); g.connect(ctx.destination);
                const t = ctx.currentTime + i * 0.35;
                g.gain.setValueAtTime(0.0001, t); g.gain.exponentialRampToValueAtTime(0.4, t + 0.02); g.gain.exponentialRampToValueAtTime(0.0001, t + 0.25);
                o.start(t); o.stop(t + 0.28);
            }
        } catch { /* sin audio */ }
    }
    const vibrar = (patron) => { try { navigator.vibrate && navigator.vibrate(patron); } catch { /* nada */ } };
    function pintarBoton() {
        const b = $('alightAlert');
        b.setAttribute('aria-pressed', String(!!aviso));
        b.querySelector('span').textContent = aviso ? 'Avisando… (toca para parar)' : 'Avisarme al bajar';
    }
    async function iniciarAviso() {
        const sel = P().seleccion;
        if (!sel || !S.Gps) return;
        const prob = S.Gps.problema();
        if (prob) return S.toast(prob.message || 'Este navegador no permite usar el GPS aquí.', { tipo: 'error', ms: 8000 });
        pitido(0);   // crea el contexto de audio dentro del toque: iOS lo exige
        aviso = { siguiente: 0, avisado1: false, avisado2: false, wake: null, gps: null };
        try { aviso.wake = await navigator.wakeLock?.request('screen'); } catch { /* opcional */ }
        aviso.gps = S.Gps.seguir({
            onLectura: (l) => alLeer(l),
            onAviso: () => S.toast('Buscando señal de GPS…', { tipo: 'info', ms: 3000, clave: 'aviso-gps' }),
            onError: (e) => { S.toast(e.message || 'No se pudo usar el GPS.', { tipo: 'error', ms: 8000 }); detenerAviso(true); },
        });
        S.toast('Te avisaré cuando te acerques a donde debes bajarte. Mantén abierta la app.', { tipo: 'success', ms: 5000, clave: 'aviso' });
        pintarBoton();
    }
    function alLeer(l) {
        const sel = P().seleccion;
        if (!aviso || !sel) return;
        const parada = sel.paradas[aviso.siguiente];
        if (!parada) return detenerAviso(true);
        const d = hav(l, parada);
        if (d <= RADIO_LLEGO) {
            vibrar([300, 120, 300]); pitido(2);
            S.toast('Llegaste a tu parada. ' + parada.texto + '.', { tipo: 'success', ms: 8000, clave: 'aviso' });
            aviso.siguiente++; aviso.avisado1 = aviso.avisado2 = false;
            if (aviso.siguiente >= sel.paradas.length) detenerAviso(true);
        } else if (d <= RADIO_YA && !aviso.avisado2) {
            aviso.avisado2 = aviso.avisado1 = true;
            vibrar([500, 200, 500, 200, 500]); pitido(3);
            S.toast('¡Prepárate! ' + parada.texto + ' (a ' + Math.round(d) + ' m).', { tipo: 'info', ms: 0, clave: 'aviso' });
        } else if (d <= RADIO_CERCA && !aviso.avisado1) {
            aviso.avisado1 = true;
            vibrar([250, 120, 250]); pitido(1);
            S.toast('Te acercas: ' + parada.texto + ' (a ' + Math.round(d) + ' m).', { tipo: 'info', ms: 9000, clave: 'aviso' });
        }
    }
    function detenerAviso(silencioso) {
        if (!aviso) return;
        try { aviso.gps && aviso.gps.detener(); } catch { /* nada */ }
        try { aviso.wake && aviso.wake.release(); } catch { /* nada */ }
        aviso = null;
        S.toast.cerrar && S.toast.cerrar('aviso-gps');
        if (!silencioso) S.toast('Aviso desactivado.', { tipo: 'info', ms: 2500, clave: 'aviso' });
        pintarBoton();
    }
    // Si se elige otra opción mientras avisa, se vuelve a empezar con la nueva.
    function reiniciarAviso() { detenerAviso(true); iniciarAviso(); }
    $('alightAlert').onclick = () => (aviso ? detenerAviso(false) : iniciarAviso());
    document.addEventListener('visibilitychange', async () => {
        if (aviso && document.visibilityState === 'visible' && !aviso.wake) { try { aviso.wake = await navigator.wakeLock?.request('screen'); } catch { /* nada */ } }
    });

    // ------------------------------------------------------------------ reportar un problema
    const modal = $('reportModal');
    const abrirReporte = () => {
        const sel = P().seleccion;
        $('reportRoute').textContent = sel ? 'Ruta(s): ' + sel.codigos.join(' + ') : 'Sin ruta seleccionada';
        modal.hidden = false;
        document.body.classList.add('modal-open');
        $('reportMessage').focus();
    };
    const cerrarReporte = () => { modal.hidden = true; document.body.classList.remove('modal-open'); };
    $('reportProblem').onclick = abrirReporte;
    $('reportCancel').onclick = cerrarReporte;
    modal.addEventListener('click', (e) => { if (e.target === modal) cerrarReporte(); });
    document.addEventListener('keydown', (e) => { if (e.key === 'Escape' && !modal.hidden) cerrarReporte(); });
    $('reportForm').onsubmit = async (e) => {
        e.preventDefault();
        const { origen, destino } = P().puntos();
        const sel = P().seleccion;
        const boton = $('reportSend');
        boton.disabled = true;
        try {
            await S.api('/api/reportes', {
                method: 'POST', headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({
                    categoria: $('reportType').value, mensaje: $('reportMessage').value,
                    rutaCodigo: sel ? sel.codigos.join('+') : null,
                    origenLat: origen?.lat, origenLng: origen?.lng, destinoLat: destino?.lat, destinoLng: destino?.lng,
                    contacto: $('reportContact').value || null,
                }),
            });
            $('reportMessage').value = '';
            cerrarReporte();
            S.toast('¡Gracias! Recibimos tu reporte y lo revisaremos.', { tipo: 'success', ms: 5000, clave: 'reporte' });
        } catch (err) {
            S.toast(err.offline || err.timeout ? 'Sin conexión: no se pudo enviar. Inténtalo cuando tengas señal.' : err.message, { tipo: 'error', ms: 7000, clave: 'reporte' });
        } finally { boton.disabled = false; }
    };

    // Al final, cuando todo lo anterior ya está definido: abre un enlace compartido si lo hay.
    if (P() && P().poner) abrirCompartido(); else window.addEventListener('spevb-planner-listo', abrirCompartido, { once: true });
})();
