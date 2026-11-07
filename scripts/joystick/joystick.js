'use strict';
/* Joystick de GPS para el emulador de Android (ver scripts/joystick_gps.py).
   Modelo de movimiento realista: el joystick fija una velocidad y un rumbo OBJETIVO; la velocidad real acelera y frena de forma gradual (como una
   persona o un bus) y la posición avanza con esa velocidad. Así SEVB ve velocidades verdaderas: a pie ≈ 1,4 m/s, bus ≈ 8 m/s. */
(() => {
    const M_LAT = 110574;
    const PRESETS = {
        pie: { id: 'pie', vmax: 1.4, acel: 1.6, freno: 3.0 },
        lento: { id: 'lento', vmax: 4.5, acel: 1.0, freno: 1.5 },
        bus: { id: 'bus', vmax: 8, acel: 1.0, freno: 1.3 },
    };
    const ORDEN = ['pie', 'lento', 'bus'];
    /** La velocidad máxima puede ser la de un preajuste o la que el usuario fije en el control deslizante; la aceleración depende de ella. */
    const presetActual = () => ({ vmax, acel: vmax < 2 ? 1.6 : 1.0, freno: vmax < 2 ? 3.0 : 1.3 });
    const ZONA_MUERTA = 0.12;

    /** Un paso de dt segundos. est = {lat, lng, v, rumbo}; ent = {x (este), y (norte)} en [-1, 1]; crucero = {activo, v}. Modifica est y crucero. */
    function avanzar(est, ent, preset, dt, crucero) {
        const mag = Math.min(1, Math.hypot(ent.x, ent.y));
        let objetivo;
        if (mag > ZONA_MUERTA) {
            est.rumbo = (Math.atan2(ent.x, ent.y) * 180 / Math.PI + 360) % 360;
            objetivo = mag * preset.vmax;
            if (crucero.activo) crucero.v = objetivo;
        } else objetivo = crucero.activo ? Math.min(crucero.v, preset.vmax) : 0;
        const dv = objetivo - est.v;
        est.v += Math.max(-preset.freno * dt, Math.min(preset.acel * dt, dv));
        if (est.v < 0.02) est.v = 0;
        const d = est.v * dt, r = est.rumbo * Math.PI / 180;
        est.lat += d * Math.cos(r) / M_LAT;
        est.lng += d * Math.sin(r) / (111320 * Math.cos(est.lat * Math.PI / 180));
        return est;
    }
    window.JoystickGps = { avanzar, PRESETS };

    const $ = (id) => document.getElementById(id);
    const test = new URLSearchParams(location.search).has('test');
    const est = { lat: 4.137, lng: -73.6266, v: 0, rumbo: 0 };
    const crucero = { activo: false, v: 0 };
    let presetId = 'pie', vmax = PRESETS.pie.vmax, ruido = 0, sinSenalHasta = 0, saltoPendiente = false, enviados = 0, enVuelo = false, intervalo = 1, ultimoT = 0;
    const entradas = { teclas: new Set(), pad: { x: 0, y: 0 }, mando: { x: 0, y: 0 } };
    const rastro = [];
    const piloto = { activo: false, ruta: [], i: 0 };
    const teleport = { n: 0, lat: 0, lng: 0, px: null, py: null, t: 0, marca: null };
    let mapa, marcador, trazo, rutaLinea;

    const gauss = () => Math.sqrt(-2 * Math.log(Math.random() || 1e-9)) * Math.cos(2 * Math.PI * Math.random());
    const mostrarError = (t) => { $('dError').hidden = !t; $('dError').textContent = t || ''; };

    // ------------------------------------------------------------------ entradas
    function entradaTeclado() {
        const k = entradas.teclas;
        const x = (k.has('d') || k.has('arrowright') ? 1 : 0) - (k.has('a') || k.has('arrowleft') ? 1 : 0);
        const y = (k.has('w') || k.has('arrowup') ? 1 : 0) - (k.has('s') || k.has('arrowdown') ? 1 : 0);
        const m = Math.hypot(x, y);
        return m ? { x: x / m, y: y / m } : { x: 0, y: 0 };
    }
    /** La entrada con más fuerza manda (no se suman: dos dispositivos a la vez no deben duplicar la velocidad). */
    function entradaActual() {
        const c = [entradaTeclado(), entradas.pad, entradas.mando];
        const e = c.reduce((a, b) => (Math.hypot(b.x, b.y) > Math.hypot(a.x, a.y) ? b : a), { x: 0, y: 0 });
        if (piloto.activo && Math.hypot(e.x, e.y) > 0.3) activarPiloto(false);       // tocar el joystick recupera el control
        return e;
    }

    function pintarVelMax() {
        $('velMax').value = String(Math.round(vmax * 3.6 * 2) / 2);
        $('dVelMax').textContent = (vmax * 3.6).toFixed(vmax * 3.6 < 10 ? 1 : 0) + ' km/h';
        document.querySelectorAll('[data-preset]').forEach((b) => b.setAttribute('aria-pressed', String(b.dataset.preset === presetId)));
    }
    function ponerPreset(id) {
        if (!PRESETS[id]) return;
        presetId = id; vmax = PRESETS[id].vmax;
        if (crucero.activo) crucero.v = Math.min(crucero.v, vmax);
        pintarVelMax();
    }
    /** Velocidad máxima en km/h (1–60), a mano: control deslizante, teclas + y −, o desde las pruebas. */
    function ponerVelMax(kmh) {
        const k = Math.max(1, Math.min(60, Number(kmh) || 1));
        presetId = 'custom'; vmax = k / 3.6;
        if (crucero.activo) crucero.v = Math.min(crucero.v, vmax);
        pintarVelMax();
    }
    function ponerCrucero(activo) {
        crucero.activo = activo; crucero.v = activo ? est.v : 0;
        $('crucero').setAttribute('aria-pressed', String(activo));
    }
    function parar() { activarPiloto(false); ponerCrucero(false); est.v = 0; pintar(); }
    const aM = (a, b) => { const kx = 111320 * Math.cos(a.lat * Math.PI / 180); return Math.hypot((b.lng - a.lng) * kx, (b.lat - a.lat) * M_LAT); };
    /** Piloto automático: el joystick «se maneja solo» siguiendo la ruta guía a la velocidad máxima que hayas fijado (cámbiala en vivo) y frena al final. */
    function activarPiloto(on) {
        if (on && !piloto.ruta.length) return;
        piloto.activo = on;
        if (on) {
            let mejor = 0, md = Infinity;
            piloto.ruta.forEach((p, i) => { const d = aM(est, p); if (d < md) { md = d; mejor = i; } });
            piloto.i = Math.min(mejor + 1, piloto.ruta.length - 1);
            ponerCrucero(false);
        }
        $('piloto').setAttribute('aria-pressed', String(on));
    }
    function entradaPiloto() {
        const R = piloto.ruta, fin = R[R.length - 1];
        const resto = aM(est, fin);
        if (resto < 4) { activarPiloto(false); est.v = 0; return { x: 0, y: 0 }; }
        while (piloto.i < R.length - 1 && aM(est, R[piloto.i]) < Math.max(8, est.v * 1.5)) piloto.i++;
        const p = presetActual();
        // Frenar a tiempo: nunca ir más rápido de lo que permite parar en lo que queda de ruta.
        const maxFrenando = Math.sqrt(2 * p.freno * Math.max(0, resto - 3));
        const fuerza = Math.max(0.15, Math.min(1, maxFrenando / vmax));
        const t = R[piloto.i], kx = 111320 * Math.cos(est.lat * Math.PI / 180), dx = (t.lng - est.lng) * kx, dy = (t.lat - est.lat) * M_LAT, m = Math.hypot(dx, dy) || 1;
        return { x: dx / m * fuerza, y: dy / m * fuerza };
    }
    function teletransportar(lat, lng) {
        est.lat = lat; est.lng = lng; est.v = 0; ponerCrucero(false);
        rastro.length = 0; activarPiloto(false); if (teleport.marca) { teleport.marca.remove(); teleport.marca = null; } pintar(true); enviar(true);
    }

    const pad = $('pad'), knob = $('knob');
    let arrastre = null;
    const vectorPad = (e) => {
        const r = pad.getBoundingClientRect(), R = r.width / 2;
        let x = (e.clientX - (r.left + R)) / R, y = -(e.clientY - (r.top + R)) / R;
        const m = Math.hypot(x, y);
        if (m > 1) { x /= m; y /= m; }
        return { x, y };
    };
    const moverKnob = (v) => { knob.style.transform = 'translate(' + (v.x * 66) + 'px, ' + (-v.y * 66) + 'px)'; };
    pad.addEventListener('pointerdown', (e) => { arrastre = e.pointerId; pad.setPointerCapture(e.pointerId); entradas.pad = vectorPad(e); moverKnob(entradas.pad); e.preventDefault(); });
    pad.addEventListener('pointermove', (e) => { if (arrastre === e.pointerId) { entradas.pad = vectorPad(e); moverKnob(entradas.pad); } });
    const soltarPad = (e) => { if (arrastre !== e.pointerId) return; arrastre = null; entradas.pad = { x: 0, y: 0 }; moverKnob(entradas.pad); };
    pad.addEventListener('pointerup', soltarPad); pad.addEventListener('pointercancel', soltarPad);

    document.addEventListener('keydown', (e) => {
        if (e.target && /^(INPUT|SELECT|TEXTAREA)$/.test(e.target.tagName) && e.key !== ' ') return;
        const k = e.key.toLowerCase();
        if (['w', 'a', 's', 'd', 'arrowup', 'arrowdown', 'arrowleft', 'arrowright'].includes(k)) { entradas.teclas.add(k); e.preventDefault(); }
        else if (k === ' ') { parar(); e.preventDefault(); }
        else if (k === 'c') ponerCrucero(!crucero.activo);
        else if (k === 'p') activarPiloto(!piloto.activo);
        else if (k === '+' || k === '=') ponerVelMax(vmax * 3.6 + 1);
        else if (k === '-' || k === '_') ponerVelMax(vmax * 3.6 - 1);
        else if (k === '1') ponerPreset('pie'); else if (k === '2') ponerPreset('lento'); else if (k === '3') ponerPreset('bus');
    });
    document.addEventListener('keyup', (e) => entradas.teclas.delete(e.key.toLowerCase()));
    window.addEventListener('blur', () => entradas.teclas.clear());

    // Control de juegos (Gamepad API): palanca izquierda y botones A, B, X, Y y los gatillos superiores.
    const botonesPrevios = [];
    function leerMando() {
        const pads = navigator.getGamepads ? navigator.getGamepads() : [];
        const g = [...pads].find((x) => x && x.connected);
        if (!g) { entradas.mando = { x: 0, y: 0 }; return; }
        const ax = g.axes[0] || 0, ay = -(g.axes[1] || 0), m = Math.hypot(ax, ay);
        entradas.mando = m > 0.15 ? { x: ax / Math.max(1, m), y: ay / Math.max(1, m) } : { x: 0, y: 0 };
        g.buttons.forEach((b, i) => {
            if (b.pressed && !botonesPrevios[i]) {
                if (i === 0) ponerCrucero(!crucero.activo); else if (i === 1) parar();
                else if (i === 2) ponerPreset('pie'); else if (i === 3) ponerPreset('bus');
                else if (i === 4) ponerPreset(ORDEN[Math.max(0, ORDEN.indexOf(presetId) - 1)]);
                else if (i === 5) ponerPreset(ORDEN[Math.min(ORDEN.length - 1, ORDEN.indexOf(presetId) + 1)]);
            }
            botonesPrevios[i] = b.pressed;
        });
    }
    window.addEventListener('gamepadconnected', (e) => { $('estadoEmu').dataset.mando = e.gamepad.id; });

    // ------------------------------------------------------------------ envío al emulador
    async function enviar(forzar = false) {
        if (Date.now() < sinSenalHasta && !forzar) return;
        if (enVuelo && !forzar) return;
        let lat = est.lat, lng = est.lng;
        if (ruido > 0) { lat += gauss() * ruido / M_LAT; lng += gauss() * ruido / (111320 * Math.cos(lat * Math.PI / 180)); }
        if (saltoPendiente) { lat += 600 / M_LAT; lng += 250 / (111320 * Math.cos(lat * Math.PI / 180)); saltoPendiente = false; }
        enVuelo = true;
        try {
            const r = await fetch('/api/fix', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ lat, lng }) });
            const j = await r.json();
            if (j.ok) { enviados++; mostrarError(null); } else mostrarError(j.error || 'El emulador no aceptó la posición.');
        } catch (e) { mostrarError('No se pudo hablar con el servidor del joystick (¿sigue abierto?).'); }
        enVuelo = false;
        $('dEnv').textContent = String(enviados);
    }

    // ------------------------------------------------------------------ mapa y lecturas
    function pintar(centrar) {
        $('dVel').textContent = (est.v * 3.6).toFixed(1);
        $('dRumbo').textContent = est.v > 0.05 ? Math.round(est.rumbo) + '°' : '—';
        $('dCoord').textContent = est.lat.toFixed(6) + ', ' + est.lng.toFixed(6);
        if (!mapa) return;
        marcador.setLatLng([est.lat, est.lng]);
        rastro.push([est.lat, est.lng]); if (rastro.length > 400) rastro.shift();
        trazo.setLatLngs(rastro);
        if (centrar || $('seguirMapa').checked) mapa.panTo([est.lat, est.lng], { animate: false });
    }
    function iniciarMapa() {
        mapa = L.map('mapa', { zoomControl: true }).setView([est.lat, est.lng], 17);
        L.tileLayer('https://tile.openstreetmap.org/{z}/{x}/{y}.png', { maxZoom: 19, attribution: '© OpenStreetMap' }).addTo(mapa);
        marcador = L.marker([est.lat, est.lng], { icon: L.divIcon({ className: 'joy-yo-wrapper', html: '<div class="joy-yo"></div>', iconSize: [26, 26], iconAnchor: [13, 13] }), keyboard: false }).addTo(mapa);
        trazo = L.polyline([], { color: '#1a73e8', weight: 4, opacity: .7 }).addTo(mapa);
        mapa.doubleClickZoom.disable();
        mapa.on('click', alClicMapa);
    }
    /** Teletransporte con TRES clics seguidos en el mismo punto (≤ 24 px entre ellos y ≤ 2,5 s entre un clic y el siguiente): un clic suelto no hace nada. */
    function alClicMapa(e) {
        const ahora = Date.now(), px = e.containerPoint.x, py = e.containerPoint.y;
        const mismo = teleport.px !== null && Math.hypot(px - teleport.px, py - teleport.py) <= 24 && ahora - teleport.t <= 2500;
        teleport.n = mismo ? teleport.n + 1 : 1;
        teleport.px = px; teleport.py = py; teleport.t = ahora; teleport.lat = e.latlng.lat; teleport.lng = e.latlng.lng;
        if (teleport.marca) teleport.marca.remove();
        if (teleport.n >= 3) { teleport.n = 0; teleport.px = null; teletransportar(e.latlng.lat, e.latlng.lng); return; }
        teleport.marca = L.marker(e.latlng, { icon: L.divIcon({ className: 'joy-objetivo-wrapper', html: '<div class="joy-objetivo">' + teleport.n + '/3</div>', iconSize: [30, 30], iconAnchor: [15, 15] }), interactive: false, keyboard: false })
            .addTo(mapa).bindTooltip('Toca ' + (3 - teleport.n) + ' vez(ces) más este punto para ir', { direction: 'top', offset: [0, -14], permanent: true });
        clearTimeout(teleport.reloj);
        teleport.reloj = setTimeout(() => { if (teleport.marca) { teleport.marca.remove(); teleport.marca = null; } teleport.n = 0; teleport.px = null; }, 2600);
    }
    async function cargarRuta() {
        try {
            const ruta = await (await fetch('/api/ruta')).json();
            if (!ruta.length || !mapa) return;
            rutaLinea = L.polyline(ruta, { color: '#e53935', weight: 6, opacity: .75, interactive: false }).addTo(mapa);
            const ini = ruta[0], fin = ruta[ruta.length - 1];
            L.circleMarker(ini, { radius: 7, color: '#fff', weight: 3, fillColor: '#176650', fillOpacity: 1 }).bindTooltip('Inicio de la ruta').addTo(mapa);
            L.circleMarker(fin, { radius: 7, color: '#fff', weight: 3, fillColor: '#c62828', fillOpacity: 1 }).bindTooltip('Fin de la ruta').addTo(mapa);
            piloto.ruta = ruta.reduce((acc, p) => { const q = { lat: p[0], lng: p[1] }; if (!acc.length || aM(acc[acc.length - 1], q) > 1.5) acc.push(q); return acc; }, []);
            $('piloto').hidden = false; $('piloto').onclick = () => activarPiloto(!piloto.activo);
            $('irRuta').hidden = false; $('irFin').hidden = false;
            $('irRuta').onclick = () => teletransportar(ini[0], ini[1]); $('irFin').onclick = () => teletransportar(fin[0], fin[1]);
        } catch (e) { /* sin ruta guía */ }
    }
    async function vigilarEmulador() {
        try {
            const j = await (await fetch('/api/estado')).json();
            const el = $('estadoEmu');
            if (j.modo === 'prueba') { el.textContent = 'Modo de prueba: no se envía a ningún emulador.'; el.classList.remove('mal'); }
            else if (j.dispositivos.length) { el.textContent = 'Emulador conectado: ' + j.dispositivos.join(', '); el.classList.remove('mal'); }
            else { el.textContent = 'No veo ningún emulador encendido. Enciéndelo en Android Studio (Device Manager › ▶).'; el.classList.add('mal'); }
        } catch (e) { /* el error se muestra al enviar */ }
    }

    async function vigilarTelefono() {
        const el = $('estadoTel');
        try {
            const j = await (await fetch('/api/telefono')).json();
            if (!j.telefono) { el.textContent = 'GPS del teléfono: sin lectura todavía.'; el.className = 'joy-tel'; return; }
            const d = j.enviado ? aM({ lat: j.telefono.lat, lng: j.telefono.lng }, { lat: j.enviado[0], lng: j.enviado[1] }) : null;
            el.textContent = d === null ? 'GPS del teléfono: ' + j.telefono.lat.toFixed(5) + ', ' + j.telefono.lng.toFixed(5)
                : (d <= 30 ? '✓ El GPS del teléfono recibió tu posición (diferencia ' + d.toFixed(1) + ' m).' : 'El GPS del teléfono va atrasado ' + Math.round(d) + ' m respecto a lo enviado.');
            el.className = 'joy-tel ' + (d === null || d <= 30 ? 'ok' : 'mal');
        } catch (e) { /* se mostrará al enviar */ }
    }

    // ------------------------------------------------------------------ bucle
    /** Un tick: lee las entradas, avanza dt segundos y envía la posición. En las pruebas lo llama `__joy.paso`. */
    async function tick(dt, entrada) {
        leerMando();
        const manual = entrada || entradaActual();
        avanzar(est, entrada || (piloto.activo ? entradaPiloto() : manual), presetActual(), dt, crucero);
        pintar();
        await enviar();
    }

    $('crucero').onclick = () => ponerCrucero(!crucero.activo);
    $('parar').onclick = parar;
    $('irInicio').onclick = () => teletransportar(inicio.lat, inicio.lng);
    document.querySelectorAll('[data-preset]').forEach((b) => { b.onclick = () => ponerPreset(b.dataset.preset); });
    $('ruido').oninput = () => { ruido = Number($('ruido').value); $('dRuido').textContent = ruido + ' m'; };
    $('salto').onclick = () => { saltoPendiente = true; };
    $('sinSenal').onclick = () => { sinSenalHasta = Date.now() + 20000; };

    let inicio = { lat: est.lat, lng: est.lng };
    (async () => {
        try {
            const c = await (await fetch('/api/config')).json();
            inicio = { lat: c.lat, lng: c.lng }; est.lat = c.lat; est.lng = c.lng; intervalo = c.intervalo || 1;
        } catch (e) { /* valores por defecto */ }
        iniciarMapa(); pintar(true); await cargarRuta(); vigilarEmulador(); setInterval(vigilarEmulador, 4000);
        $('velMax').oninput = () => ponerVelMax($('velMax').value); pintarVelMax();
        vigilarTelefono(); setInterval(() => { if (!document.hidden) vigilarTelefono(); }, 3000);
        await enviar(true);                                     // el teléfono empieza donde está el punto
        if (test) {
            window.__joy = {
                est, crucero, entradas, ponerPreset, ponerCrucero, parar, teletransportar, tick,
                get enviados() { return enviados; }, set ruido(v) { ruido = v; }, set salto(v) { saltoPendiente = v; }, get preset() { return presetId; }, get vmaxKmh() { return vmax * 3.6; }, entradaActual, ponerVelMax, piloto, activarPiloto, entradaPiloto, rastro, get objetivoTeletransporte() { return teleport.n; },
            };
            document.documentElement.dataset.listo = '1';
            return;
        }
        ultimoT = performance.now();
        setInterval(() => {
            const ahora = performance.now(), dt = Math.max(0.2, Math.min(3, (ahora - ultimoT) / 1000));
            ultimoT = ahora; tick(dt);
        }, intervalo * 1000);
        // El mando se lee más seguido que el envío para que los botones respondan al instante.
        const rap = () => { if (!document.hidden) leerMando(); requestAnimationFrame(rap); };
        requestAnimationFrame(rap);
    })();
})();
