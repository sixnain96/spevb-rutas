'use strict';
/* «Modo viaje»: sigue a la persona en tiempo real, como una app de navegación, pero para tomar el bus.
   Camina al punto de subida → detecta que va en el bus (y pregunta) → avisa antes de bajar → detecta que bajó (y pregunta) → guía hasta B.
   La lógica (filtrar el GPS y decidir la fase) vive en viaje-nucleo.js y se prueba sin salir a la calle; este archivo es solo la interfaz:
   tarjeta del viaje, punto de la persona en el mapa, preguntas, avisos (pantalla, vibración y sonido), pantalla encendida y reanudación. */
(() => {
    const S = window.Spevb;
    const N = S && S.ViajeNucleo;
    const $ = (id) => document.getElementById(id);
    if (!S || !N || !S.Gps || !$('startTrip')) return;
    const CLAVE = 'spevb-viaje-curso';
    const MAX_EDAD_MS = 3 * 3600 * 1000;
    let sesion = null;

    const mapa = () => (S.maps && S.maps.map) || (window.SpevbExplorer && window.SpevbExplorer.map);
    const leer = () => { try { return JSON.parse(localStorage.getItem(CLAVE)); } catch { return null; } };
    const borrar = () => { try { localStorage.removeItem(CLAVE); } catch { /* sin almacenamiento */ } };
    /** Estado reanudable (4.14): el plan (con las caminatas cuando ya llegaron), el estado del núcleo, la hora ORIGINAL de inicio y solo la ÚLTIMA posición válida
     *  (nunca un historial de ubicaciones). `t` es cuándo se guardó: al reanudar, esa posición se presenta como antigua hasta recibir una lectura nueva. */
    const guardar = () => {
        if (!sesion) return;
        try {
            const sel = S.Planner && S.Planner.seleccion && S.Planner.seleccion.viaje;
            if (sel && sel.caminatas && !sesion.plan.caminatas && sel.destino && sel.destino.lat === sesion.plan.destino.lat && sel.destino.lng === sesion.plan.destino.lng) sesion.plan.caminatas = redondear(sel.caminatas);
            localStorage.setItem(CLAVE, JSON.stringify({ v: 2, plan: sesion.plan, estado: sesion.nucleo.serializar(), t: Date.now(), inicio: sesion.inicio, ultima: sesion.ultima || null }));
        } catch { /* sin almacenamiento */ }
    };
    const redondear = (plan) => JSON.parse(JSON.stringify(plan, (k, v) => (typeof v === 'number' && !Number.isInteger(v) ? Math.round(v * 1e6) / 1e6 : v)));

    // ------------------------------------------------------------------ tarjeta
    const tarjeta = document.createElement('section');
    tarjeta.id = 'viajeCard'; tarjeta.className = 'viaje-card'; tarjeta.hidden = true;
    tarjeta.setAttribute('role', 'region'); tarjeta.setAttribute('aria-label', 'Modo viaje');
    tarjeta.innerHTML = `
        <div class="viaje-tope">
            <div class="viaje-asa" id="viajeAsa" tabindex="0" role="group"
                 aria-label="Mover la tarjeta del viaje: arrástrala o usa las flechas del teclado; doble toque para devolverla a su sitio"><span class="viaje-agarre" aria-hidden="true"></span></div>
            <div class="viaje-ventana">
                <button type="button" id="viajeInicio" class="viaje-icono-boton" aria-label="Volver al inicio" title="Volver al inicio"><svg viewBox="0 0 24 24" width="22" height="22" aria-hidden="true"><path d="M4 11l8-7 8 7M6 10v10h4v-6h4v6h4V10" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/></svg></button>
                <button type="button" id="viajeMinimizar" class="viaje-icono-boton" aria-label="Minimizar la tarjeta del viaje" aria-expanded="true" title="Minimizar"><svg viewBox="0 0 24 24" width="22" height="22" aria-hidden="true"><path d="M6 10l6 6 6-6" fill="none" stroke="currentColor" stroke-width="2.6" stroke-linecap="round" stroke-linejoin="round"/></svg></button>
                <button type="button" id="viajeOcultar" class="viaje-icono-boton" aria-label="Ocultar la tarjeta del viaje (el viaje sigue; toca «Viaje» para volver a verla)" title="Ocultar"><svg viewBox="0 0 24 24" width="22" height="22" aria-hidden="true"><path d="M6 6l12 12M18 6L6 18" fill="none" stroke="currentColor" stroke-width="2.6" stroke-linecap="round"/></svg></button>
            </div>
        </div>
        <div class="viaje-cab">
            <span class="viaje-icono" aria-hidden="true"><svg viewBox="0 0 24 24" width="22" height="22"><path d="M12 3l7 17-7-4-7 4z" fill="currentColor"/></svg></span>
            <h2 class="viaje-titulo" id="viajeTitulo">Modo viaje</h2>
        </div>
        <div class="viaje-ruta">
            <span class="viaje-bus" id="viajeBus" hidden></span>
            <p class="viaje-mini" id="viajeMini" aria-hidden="true"></p>
        </div>
        <p class="viaje-instruccion" id="viajeInstruccion" aria-live="polite"></p>
        <div class="estados-viaje" id="viajeEstados" role="status" aria-live="polite" hidden></div>
        <div class="viaje-barra" id="viajeBarra" role="progressbar" aria-label="Avance de esta parte del viaje" aria-valuemin="0" aria-valuemax="100" aria-valuenow="0"><i></i></div>
        <div class="viaje-aviso" id="viajeAviso" role="alert" hidden></div>
        <div class="viaje-pregunta" id="viajePregunta" role="alertdialog" aria-labelledby="viajePreguntaTexto" hidden>
            <p id="viajePreguntaTexto"></p>
            <div class="viaje-botones">
                <button type="button" class="viaje-si" id="viajeSi">Sí</button>
                <button type="button" class="viaje-no" id="viajeNo">No</button>
            </div>
        </div>
        <div class="viaje-manual" id="viajeManual" hidden>
            <button type="button" id="viajeYaSubi" class="viaje-manual-boton" hidden>Ya me subí</button>
            <button type="button" id="viajeYaBaje" class="viaje-manual-boton" hidden>Ya me bajé</button>
        </div>
        <div class="viaje-pie">
            <button type="button" id="viajeSeguir" aria-pressed="true">Seguirme</button>
            <button type="button" id="viajeMas" aria-expanded="false" aria-controls="viajeMasPanel">Más</button>
            <button type="button" id="viajeTerminar" class="viaje-terminar">Terminar viaje</button>
        </div>
        <div class="viaje-mas" id="viajeMasPanel" hidden>
            <span class="viaje-lecturas"><b id="viajeDist">—</b><small>falta</small></span>
            <span class="viaje-lecturas"><b id="viajeEta">—</b><small>aprox.</small></span>
            <span class="viaje-lecturas"><b id="viajeVel">—</b><small>velocidad</small></span>
            <span class="viaje-lecturas"><b id="viajePrec">—</b><small>precisión</small></span>
            <button type="button" id="viajeRecuperarMapa">Recuperar mapa</button>
            <button type="button" id="viajeRecalcular">Recalcular desde aquí</button>
        </div>`;
    // La tarjeta y su pastilla viven en <body>: dentro de .app (position: fixed = su propia pila) nunca podrían verse sobre el menú, ni siquiera un aviso urgente.
    document.body.appendChild(tarjeta);
    const q = (id) => $(id);
    // Pastilla que queda cuando la tarjeta se oculta: el viaje sigue y un toque la devuelve.
    const pastilla = document.createElement('button');
    pastilla.type = 'button'; pastilla.id = 'viajePastilla'; pastilla.className = 'viaje-pastilla'; pastilla.hidden = true;
    pastilla.setAttribute('aria-label', 'Mostrar la tarjeta del viaje');
    pastilla.innerHTML = '<svg viewBox="0 0 24 24" width="18" height="18" aria-hidden="true"><path d="M12 3l7 17-7-4-7 4z" fill="currentColor"/></svg><span>Viaje</span><b id="viajePastillaDato"></b>';
    document.body.appendChild(pastilla);


    // ------------------------------------------------------------------ tarjeta: arrastrar, minimizar y ocultar
    // La tarjeta NO debe quedar fija tapando el mapa: se mueve con el dedo, el ratón o el teclado, se minimiza a una barra y se oculta a una pastilla.
    // Las preguntas y los avisos importantes la vuelven a abrir solos (no se puede perder el aviso de bajada por tenerla escondida).
    const CLAVE_UI = 'spevb-viaje-ui';
    const ui = { modo: 'completa', x: 0, y: 0 };
    try { Object.assign(ui, JSON.parse(localStorage.getItem(CLAVE_UI)) || {}); } catch { /* valores por defecto */ }
    if (!['completa', 'compacta', 'oculta'].includes(ui.modo)) ui.modo = 'completa';
    ui.x = Number.isFinite(ui.x) ? ui.x : 0; ui.y = Number.isFinite(ui.y) ? ui.y : 0;
    const guardarUi = () => { try { localStorage.setItem(CLAVE_UI, JSON.stringify(ui)); } catch { /* sin almacenamiento */ } };
    function aplicarPosicion() { tarjeta.style.transform = ui.x || ui.y ? 'translate(' + ui.x + 'px, ' + ui.y + 'px)' : ''; }
    /** Deja la tarjeta entera dentro de la pantalla (también tras girar el teléfono o cambiar el tamaño de la ventana). */
    function limitarPosicion() {
        if (tarjeta.hidden) return;
        if (tarjeta.dataset.cede === 'overlay' && !tarjeta.dataset.urgente) return;      // cedida (desplazada 18 px por la animación): medirla ahora movería la posición guardada
        const r = tarjeta.getBoundingClientRect();
        if (!r.width) return;
        const margen = 4, arriba = 56;      // arriba se respeta la barra de la aplicación
        let dx = 0, dy = 0;
        if (r.left < margen) dx = margen - r.left; else if (r.right > innerWidth - margen) dx = innerWidth - margen - r.right;
        if (r.top < arriba) dy = arriba - r.top; else if (r.bottom > innerHeight - margen) dy = innerHeight - margen - r.bottom;
        if (dx || dy) { ui.x += dx; ui.y += dy; aplicarPosicion(); }
    }
    function datoCorto() { const i = sesion && sesion.nucleo.info(); return i && i.distancia !== null ? fmtDist(i.distancia) : ''; }
    function ponerModo(modo, { recordar = true } = {}) {
        ui.modo = modo;
        tarjeta.dataset.modo = modo;
        const visible = !!sesion || tarjeta.dataset.fase === 'reanudar';
        tarjeta.hidden = !visible || modo === 'oculta';
        pastilla.hidden = !sesion || modo !== 'oculta';
        q('viajeMinimizar').setAttribute('aria-expanded', String(modo === 'completa'));
        q('viajeMinimizar').setAttribute('aria-label', modo === 'completa' ? 'Minimizar la tarjeta del viaje' : 'Expandir la tarjeta del viaje');
        q('viajeMinimizar').title = modo === 'completa' ? 'Minimizar' : 'Expandir';
        q('viajeMinimizar').firstElementChild.style.transform = modo === 'completa' ? '' : 'rotate(180deg)';
        if (recordar) guardarUi();
        requestAnimationFrame(limitarPosicion);
    }
    /** Una pregunta o un aviso urgente siempre se ven: abre la tarjeta aunque estuviera minimizada u oculta. */
    // Si un menú o diálogo está encima, la tarjeta NO se reabre a pantalla completa: el aviso importante sale en su propia presentación (ver sincronizarUrgente).
    // «Cedida»: hay un menú/diálogo encima o la persona trajo el planificador durante el viaje. En ambos casos un aviso importante sale reducido y arriba, sin maximizar la tarjeta.
    const cedida = () => tarjeta.dataset.cede === 'overlay' || tarjeta.dataset.cede === 'planificador';
    function mostrarCompleta() { if (cedida()) return sincronizarUrgente(); if (ui.modo !== 'completa') ponerModo('completa', { recordar: false }); }
    /** Un aviso importante (pregunta de subida/bajada, aviso próximo o urgente, error que pide acción) tiene que verse aunque la tarjeta haya cedido el sitio
     *  al menú o esté oculta: la misma tarjeta se muestra SOLO con esa pregunta/aviso, arriba de todo. Es UNA pila, no otra: nunca hay dos preguntas a la vez. */
    function sincronizarUrgente() {
        const aviso = q('viajeAviso'), preg = q('viajePregunta');
        const urgente = !!sesion && (!preg.hidden || (!aviso.hidden && (aviso.dataset.nivel === 'urgente' || aviso.dataset.nivel === 'alerta')));
        const antes = tarjeta.dataset.urgente;
        if (urgente) tarjeta.dataset.urgente = '1'; else delete tarjeta.dataset.urgente;
        if (antes !== tarjeta.dataset.urgente && S.Superficies) S.Superficies.aplicar();      // el coordinador exime a la tarjeta del `inert` del menú mientras haya un aviso importante
        pastilla.classList.toggle('con-aviso', urgente);
        if (urgente && cedida() && tarjeta.hidden) { tarjeta.hidden = false; tarjeta.dataset.forzada = '1'; }
        else if (tarjeta.dataset.forzada && !(urgente && cedida())) { delete tarjeta.dataset.forzada; ponerModo(ui.modo, { recordar: false }); }
    }
    window.addEventListener('spevb-superficies', () => { sincronizarUrgente(); requestAnimationFrame(limitarPosicion); });
    q('viajeMinimizar').addEventListener('click', () => ponerModo(ui.modo === 'completa' ? 'compacta' : 'completa'));
    q('viajeOcultar').addEventListener('click', () => { ponerModo('oculta'); pastilla.focus({ preventScroll: true }); });
    pastilla.addEventListener('click', () => { ponerModo('compacta'); q('viajeMinimizar').focus({ preventScroll: true }); });
    q('viajeMas').addEventListener('click', () => {
        const abierto = q('viajeMasPanel').hidden;
        q('viajeMasPanel').hidden = !abierto; q('viajeMas').setAttribute('aria-expanded', String(abierto));
        q('viajeMas').textContent = abierto ? 'Menos' : 'Más';
        requestAnimationFrame(limitarPosicion);
    });

    const asa = q('viajeAsa');
    let arrastre = null;
    asa.addEventListener('pointerdown', (e) => {
        if (e.button !== undefined && e.button > 0) return;
        arrastre = { id: e.pointerId, sx: e.clientX, sy: e.clientY, x: ui.x, y: ui.y };
        try { asa.setPointerCapture(e.pointerId); } catch { /* sin captura */ }
        tarjeta.classList.add('arrastrando');
        e.preventDefault();
    });
    asa.addEventListener('pointermove', (e) => {
        if (!arrastre || e.pointerId !== arrastre.id) return;
        ui.x = arrastre.x + (e.clientX - arrastre.sx); ui.y = arrastre.y + (e.clientY - arrastre.sy);
        aplicarPosicion();
    });
    const soltar = (e) => {
        if (!arrastre || (e && e.pointerId !== arrastre.id)) return;
        arrastre = null; tarjeta.classList.remove('arrastrando');
        limitarPosicion(); guardarUi();
    };
    asa.addEventListener('pointerup', soltar); asa.addEventListener('pointercancel', soltar);
    // El gesto termina SIEMPRE: al perder la captura del puntero, al perder el foco la ventana o al ocultarse la página (nunca queda «arrastrando» pegado).
    asa.addEventListener('lostpointercapture', soltar);
    window.addEventListener('blur', () => soltar());
    document.addEventListener('visibilitychange', () => { if (document.hidden) soltar(); });
    asa.addEventListener('dblclick', () => { ui.x = 0; ui.y = 0; aplicarPosicion(); guardarUi(); });
    asa.addEventListener('keydown', (e) => {
        const paso = e.shiftKey ? 60 : 20, mueve = { ArrowLeft: [-paso, 0], ArrowRight: [paso, 0], ArrowUp: [0, -paso], ArrowDown: [0, paso] }[e.key];
        if (e.key === 'Home') { ui.x = 0; ui.y = 0; } else if (mueve) { ui.x += mueve[0]; ui.y += mueve[1]; } else return;
        e.preventDefault(); aplicarPosicion(); limitarPosicion(); guardarUi();
    });
    window.addEventListener('resize', limitarPosicion);
    aplicarPosicion(); tarjeta.dataset.modo = ui.modo;

    // ------------------------------------------------------------------ sonido, vibración y voz
    let audio = null;
    const prepararAudio = () => { try { audio = audio || new (window.AudioContext || window.webkitAudioContext)(); if (audio.state === 'suspended') audio.resume(); } catch { audio = null; } };
    function pitido(veces) {
        if (!audio) return;
        try {
            for (let i = 0; i < veces; i++) {
                const o = audio.createOscillator(), g = audio.createGain(), t = audio.currentTime + i * 0.28;
                o.type = 'sine'; o.frequency.value = 880; g.gain.setValueAtTime(0.0001, t); g.gain.exponentialRampToValueAtTime(0.35, t + 0.02); g.gain.exponentialRampToValueAtTime(0.0001, t + 0.2);
                o.connect(g).connect(audio.destination); o.start(t); o.stop(t + 0.22);
            }
        } catch { /* sin audio */ }
    }
    const vibrar = (patron) => { try { navigator.vibrate && navigator.vibrate(patron); } catch { /* sin vibración */ } };
    const avisarPersona = (texto, { vibra, pitidos } = {}) => { if (vibra) vibrar(vibra); if (pitidos) pitido(pitidos); };

    // ------------------------------------------------------------------ pantalla encendida
    async function pedirPantalla() {
        try { if (navigator.wakeLock && sesion) { sesion.wake = await navigator.wakeLock.request('screen'); sesion.wake.addEventListener && sesion.wake.addEventListener('release', () => { if (sesion) sesion.wake = null; }); } } catch { /* opcional */ }
    }
    function soltarPantalla() { try { sesion && sesion.wake && sesion.wake.release(); } catch { /* nada */ } if (sesion) sesion.wake = null; }

    // ------------------------------------------------------------------ mapa
    /** El viaje NO depende del mapa: la capa de dibujo (posición, recorrido) se crea cuando el mapa está disponible y se reintenta sola mientras tanto. */
    function asegurarCapa() {
        if (!sesion || sesion.capa) return !!(sesion && sesion.capa);
        const m = mapa();
        if (!m || typeof m.group !== 'function') return false;
        try { sesion.capa = m.group().addTo(m); } catch { sesion.capa = null; return false; }
        sesion.mapaListo = true;
        asegurarRecorrido();
        if (sesion.ultima && !sesion.marcador) pintarPosicion({ lat: sesion.ultima.lat, lng: sesion.ultima.lng, precision: sesion.ultima.precision || 30 }, { sinSeguir: true });
        return true;
    }
    function crearMarcador(l) {
        const m = mapa(); if (!m || !sesion.capa || sesion.marcador) return;
        sesion.circulo = m.circle([l.lat, l.lng], l.precision, { color: '#1a73e8', weight: 1, fillColor: '#1a73e8', fillOpacity: 0.12, interactive: false }).addTo(sesion.capa);
        sesion.marcador = m.marker([l.lat, l.lng], {
            className: 'viaje-yo-wrapper', html: '<div class="viaje-yo"></div>', size: [26, 26], anchor: [13, 13],
            z: 1200, keyboard: false, interactive: false,
        }).addTo(sesion.capa);
    }
    function pintarPosicion(l, { sinSeguir = false } = {}) {
        if (!asegurarCapa()) return;      // sin mapa: la posición y las indicaciones de la tarjeta siguen; se dibujará cuando el mapa esté listo
        crearMarcador(l);
        if (!sesion.marcador) return;
        sesion.marcador.setPosition([l.lat, l.lng]); sesion.circulo.setPosition([l.lat, l.lng]).setRadius(Math.max(5, l.precision));
        const m = mapa();
        if (m && m.priorizarCaminata) m.priorizarCaminata([l.lat, l.lng]);      // con un viaje en curso solo se anima la caminata más cercana a la persona
        if (sesion.seguir && m && !sinSeguir) {
            // El zoom mínimo de 16 se impone UNA vez al empezar a seguir (o al reactivar «Seguirme»): después se respeta el zoom que la persona elija con el pellizco.
            const zoom = sesion.zoomSeguimiento ? m.getZoom() : Math.max(m.getZoom(), 16);
            sesion.zoomSeguimiento = true;
            sesion.moviendo = true;
            S.centrarEn(m, [l.lat, l.lng], zoom, { animate: !(S.Motion && S.Motion.reduced) });      // al centro del área LIBRE: nunca debajo de la tarjeta ni del panel
            setTimeout(() => { if (sesion) sesion.moviendo = false; }, 400);
        }
    }
    /** Si el recorrido no está dibujado por el planificador (viaje reanudado o plan borrado), se dibuja aquí. */
    function asegurarRecorrido() {
        const m = mapa(); if (!sesion || !m || sesion.capaRecorrido || S.Planner.seleccion) return;
        if (typeof m.group !== 'function') return;
        sesion.capaRecorrido = m.group().addTo(m);
        // Caminatas guardadas con el plan (si llegaron antes de guardar): línea punteada sobre las calles, como en el planificador.
        Object.values(sesion.plan.caminatas || {}).forEach((c) => {
            if (c && Array.isArray(c.coordinates) && c.coordinates.length > 1) m.polyline(S.Geo.desdeGeoJSON(c.coordinates), { color: '#2f3640', weight: 5, opacity: 0.9, dashArray: '1 10', interactive: false }).addTo(sesion.capaRecorrido);
        });
        sesion.plan.buses.forEach((b, i) => {
            const ll = S.Geo.desdeGeoJSON(b.tramo);
            m.polyline(ll, { color: '#fff', weight: 11, opacity: 0.95, interactive: false }).addTo(sesion.capaRecorrido);
            m.polyline(ll, { color: i ? '#1e5fbf' : '#e53935', weight: 7, opacity: 1, interactive: false }).addTo(sesion.capaRecorrido);
            m.dot([b.subida.lat, b.subida.lng], { radius: 7, color: '#fff', weight: 3, fillColor: '#176650', fillOpacity: 1, tooltip: 'Subes a la ruta ' + (b.etiqueta || b.codigo) }).addTo(sesion.capaRecorrido);
            m.dot([b.bajada.lat, b.bajada.lng], { radius: 7, color: '#fff', weight: 3, fillColor: '#c62828', fillOpacity: 1, tooltip: 'Bajas de la ruta ' + (b.etiqueta || b.codigo) }).addTo(sesion.capaRecorrido);
        });
        m.dot([sesion.plan.destino.lat, sesion.plan.destino.lng], { radius: 8, color: '#fff', weight: 3, fillColor: '#1e5fbf', fillOpacity: 1, tooltip: 'Tu destino' }).addTo(sesion.capaRecorrido);
    }

    // ------------------------------------------------------------------ pintar la tarjeta
    const fmtDist = (m) => (m === null || m === undefined ? '—' : m < 1000 ? m + ' m' : (m / 1000).toLocaleString('es-CO', { maximumFractionDigits: 1 }) + ' km');
    function pintarInfo(info) {
        q('viajeTitulo').textContent = info.titulo;
        q('viajeInstruccion').textContent = info.instruccion;
        q('viajeDist').textContent = fmtDist(info.distancia);
        q('viajeEta').textContent = info.etaMin === null ? '—' : info.etaMin + ' min';
        q('viajeVel').textContent = info.velKmh === null ? '—' : info.velKmh + ' km/h';
        q('viajePrec').textContent = info.precision === null ? '—' : '±' + info.precision + ' m';
        const pct = Math.round(info.progreso * 100);
        q('viajeBarra').setAttribute('aria-valuenow', String(pct));
        q('viajeBarra').firstElementChild.style.width = pct + '%';
        const chip = q('viajeBus');
        chip.hidden = !info.codigo || info.fase === 'iniciando' || info.fase === 'caminar_final' || info.fase === 'llegaste';
        chip.textContent = info.etiqueta || info.codigo || ''; if (/^#[0-9a-f]{6}$/i.test(info.color || '')) chip.style.setProperty('--viaje-bus', info.color);
        tarjeta.dataset.fase = info.fase;
        tarjeta.classList.toggle('sin-senal', !!info.sinSenal);
        // Confirmación manual (el bus puede ir lento y no alcanzar el umbral del detector): solo en la fase que corresponde y sin pregunta abierta.
        const sinPregunta = !info.pregunta && tarjeta.dataset.fase !== 'reanudar';
        q('viajeYaSubi').hidden = !(sinPregunta && (info.fase === 'caminar' || info.fase === 'esperando') && info.precision !== null);
        q('viajeYaBaje').hidden = !(sinPregunta && info.fase === 'en_bus');
        q('viajeManual').hidden = q('viajeYaSubi').hidden && q('viajeYaBaje').hidden;
        const dato = info.distancia === null ? '' : fmtDist(info.distancia) + (info.etaMin === null ? '' : ' · ' + info.etaMin + ' min');
        q('viajeMini').textContent = dato; q('viajePastillaDato').textContent = info.distancia === null ? '' : fmtDist(info.distancia);
    }
    let temporizadorAviso = null;
    function mostrarAviso(texto, { nivel = 'info', ms = 12000 } = {}) {
        const a = q('viajeAviso');
        a.textContent = texto; a.dataset.nivel = nivel; a.hidden = false;
        clearTimeout(temporizadorAviso);
        if (ms) temporizadorAviso = setTimeout(() => { a.hidden = true; sincronizarUrgente(); }, ms);
        sincronizarUrgente();
    }
    const ocultarAviso = () => { clearTimeout(temporizadorAviso); q('viajeAviso').hidden = true; sincronizarUrgente(); };
    function mostrarPregunta(id, texto) {
        q('viajePreguntaTexto').textContent = texto;
        q('viajePregunta').dataset.id = id; q('viajePregunta').hidden = false;
        q('viajeSi').textContent = id === 'subida' ? 'Sí, me subí' : 'Sí, ya me bajé';
        q('viajeNo').textContent = id === 'subida' ? 'Todavía no' : 'No, sigo en el bus';
        sincronizarUrgente();
    }
    const ocultarPregunta = () => { q('viajePregunta').hidden = true; delete q('viajePregunta').dataset.id; sincronizarUrgente(); };

    // ------------------------------------------------------------------ eventos del núcleo
    function manejar(eventos) {
        for (const ev of eventos) {
            if (!sesion) return;
            switch (ev.tipo) {
                case 'progreso': pintarPosicion(ev.lectura); pintarInfo(ev.info); break;
                case 'fase':
                    pintarInfo(ev.info); ocultarPregunta();
                    if (ev.fase !== 'llegaste' && ev.fase !== 'terminado') { ocultarAviso(); guardar(); }
                    if (ev.texto && !ev.inicial) { mostrarAviso(ev.texto, { nivel: 'ok', ms: 9000 }); }
                    if (ev.inicial && ev.distanciaAOrigen > 1500) {
                        mostrarAviso('Estás a ' + fmtDist(ev.distanciaAOrigen) + ' del punto A. Puedes seguir, o usar «Recalcular desde aquí» para un viaje que parta de donde estás.', { nivel: 'alerta', ms: 20000 });
                    }
                    break;
                case 'pregunta':
                    mostrarCompleta(); pintarInfo(ev.info); mostrarPregunta(ev.id, ev.texto); avisarPersona(ev.texto, { vibra: [200, 100, 200], pitidos: 1 });
                    break;
                case 'pregunta_retirada': ocultarPregunta(); break;
                case 'alerta': {
                    const fuerte = ev.nivel !== 'cerca';
                    if (fuerte) mostrarCompleta();
                    mostrarAviso(ev.texto, { nivel: ev.nivel === 'ahora' ? 'urgente' : 'alerta', ms: ev.nivel === 'ahora' ? 0 : 20000 });
                    avisarPersona(ev.texto, { vibra: ev.nivel === 'cerca' ? [250, 120, 250] : ev.nivel === 'preparate' ? [500, 200, 500, 200, 500] : [700, 200, 700, 200, 700], pitidos: fuerte ? (ev.nivel === 'ahora' ? 3 : 2) : 1 });
                    pintarInfo(ev.info); guardar();
                    break;
                }
                case 'se_paso': mostrarCompleta(); mostrarAviso(ev.texto, { nivel: 'urgente', ms: 0 }); avisarPersona(ev.texto, { vibra: [500, 200, 500], pitidos: 2 }); break;
                case 'desvio': case 'desvio_bus': mostrarAviso(ev.texto, { nivel: 'alerta', ms: 15000 }); avisarPersona(ev.texto, { vibra: [250] }); break;
                case 'senal_debil': mostrarAviso(ev.texto, { nivel: 'info', ms: 15000 }); break;
                // Aviso de señal: informa en la tarjeta (en su modo actual, también la compacta) pero NO la maximiza ni reorganiza la pantalla.
                case 'sin_senal': mostrarAviso(ev.texto, { nivel: 'alerta', ms: 0 }); pintarInfo(ev.info); estadoUbicacion(); break;
                case 'senal_recuperada': mostrarAviso(ev.texto, { nivel: 'ok', ms: 5000 }); break;
                case 'llego': llegada(); break;
                default: break;
            }
        }
    }

    function llegada() {
        mostrarCompleta();
        avisarPersona('¡Llegaste a tu destino!', { vibra: [300, 120, 300, 120, 600], pitidos: 3 });
        mostrarAviso('¡Llegaste a tu destino! Gracias por viajar con SEVB.', { nivel: 'ok', ms: 0 });
        q('viajeTerminar').textContent = 'Cerrar';
        q('viajeTerminar').dataset.llego = '1';
        try { window.dispatchEvent(new CustomEvent('spevb-viaje-llegada', { detail: { buses: sesion && sesion.plan && sesion.plan.buses ? sesion.plan.buses.length : 1, inicio: sesion ? sesion.inicio : Date.now(), resumen: S.Planner && S.Planner.seleccion ? S.Planner.seleccion.resumen || null : null } })); } catch { /* sin logros */ }
        detenerEscucha();
        borrar();
    }

    // ------------------------------------------------------------------ ubicación: estado visible, vigilancia (señal) y visibilidad
    const hace = (ms) => { const m = Math.max(0, Math.round(ms / 60000)); return m < 1 ? 'hace unos segundos' : m === 1 ? 'hace 1 min' : 'hace ' + m + ' min'; };
    /** Aviso «sin ubicación actual» (distinto de «sin internet» y «mapa incompleto»): dice desde cuándo es la última posición conocida; nunca se presenta como actual. */
    function estadoUbicacion() {
        if (!sesion || !S.MapaSalud) return;
        const sinLectura = sesion.antigua || (performance.now() - sesion.ultimaRecepcion > 20000);
        if (!sinLectura) return S.MapaSalud.poner('ubicacion', null);
        const t = sesion.ultima && sesion.ultima.t;
        S.MapaSalud.poner('ubicacion', { texto: 'Sin ubicación actual.' + (t ? ' Última posición conocida ' + hace(Date.now() - t) + '.' : ' Todavía no tengo una lectura.') });
    }
    function vigilar() {
        if (!sesion) return;
        const ms = performance.now() - sesion.ultimaRecepcion;
        if (!sesion.nucleo.info().precision && sesion.fase0 && ms > 25000 && !sesion.avisoInicial) {
            sesion.avisoInicial = true;
            mostrarAviso('Todavía no consigo tu ubicación. Sal a cielo abierto y revisa que el GPS del teléfono esté activado.', { nivel: 'alerta', ms: 0 });
        }
        manejar(sesion.nucleo.sinSenal(ms));
        estadoUbicacion();
        if (!sesion.capa) asegurarCapa();      // el mapa pudo llegar después: se dibuja ahora (sin tocar el viaje)
    }
    /** Suscribe UNA escucha de GPS. Cada suscripción tiene un número de generación: los callbacks de una escucha vieja (renovada al volver de la pantalla bloqueada) se ignoran. */
    function escucharGps() {
        if (!sesion) return;
        const gen = (sesion.genGps = (sesion.genGps || 0) + 1);
        try { sesion.gps && sesion.gps.detener(); } catch { /* nada */ }
        sesion.gps = null;
        const vigente = () => !!sesion && sesion.genGps === gen;
        sesion.gps = S.Gps.seguir({
            crudas: true, segundoPlano: true,
            onLectura: (l) => {
                if (!vigente()) return;
                const eventos = sesion.nucleo.procesar({ lat: l.lat, lng: l.lng, precision: l.precision, t: l.marcaTiempo, velocidad: l.velocidad });
                // «Recibí un evento del GPS» no es «tengo una posición válida»: las lecturas descartadas (imprecisas, repetidas, saltos) no
                // reinician el reloj de ausencia de señal, para que el seguimiento no parezca sano mientras solo llega ruido.
                if (!eventos.some((e) => e.tipo === 'descartada')) {
                    sesion.ultimaRecepcion = performance.now();
                    sesion.ultima = { lat: l.lat, lng: l.lng, precision: l.precision, t: Date.now() };      // solo la ÚLTIMA posición válida
                    if (sesion.antigua) { sesion.antigua = false; estadoUbicacion(); }      // llegó una lectura actual: la posición guardada deja de ser «antigua»
                }
                manejar(eventos);
            },
            onAviso: () => { if (vigente()) mostrarAviso('El GPS no responde por ahora; sigo intentando.', { nivel: 'info', ms: 6000 }); },
            onError: (e) => { if (vigente()) mostrarAviso(e.message + (e.ayuda ? ' ' + e.ayuda : ''), { nivel: 'urgente', ms: 0 }); },
        });
    }
    /** Volver a la app (pantalla bloqueada, otra app, página restaurada): el navegador puede haber pausado el GPS sin avisar. Se renueva la escucha UNA vez (sin duplicarla),
     *  se vuelve a pedir la pantalla encendida y se marca la última posición como antigua hasta recibir una lectura nueva. */
    function alVolver(motivo) {
        if (!sesion) return;
        const fuera = sesion.oculto ? performance.now() - sesion.oculto : 0;
        sesion.oculto = null;
        pedirPantalla();
        if (motivo === 'pageshow' || fuera > 15000) {
            sesion.ultimaRecepcion = performance.now();      // margen antes de declarar «sin señal»: el GPS pudo pausarse
            sesion.antigua = true; estadoUbicacion();
            escucharGps();                                   // renueva la escucha (la anterior queda invalidada por su generación)
            if (fuera > 15000) mostrarAviso('Volviste a la app. Estoy verificando tu posición: si el teléfono se bloqueó, el GPS pudo pausarse.', { nivel: 'info', ms: 10000 });
        }
        asegurarCapa();
    }
    function alVisibilidad() {
        if (!sesion) return;
        if (document.hidden) { sesion.oculto = performance.now(); guardar(); return; }      // al ocultar la página se guarda el avance
        alVolver('visible');
    }
    const alOcultarPagina = () => { if (sesion) guardar(); };
    const alMostrarPagina = (e) => { if (e.persisted) alVolver('pageshow'); };      // página restaurada desde la memoria (bfcache)
    window.addEventListener('pagehide', alOcultarPagina);
    window.addEventListener('pageshow', alMostrarPagina);

    // ------------------------------------------------------------------ iniciar / terminar
    function detenerEscucha() {
        if (!sesion) return;
        try { sesion.gps && sesion.gps.detener(); } catch { /* nada */ }
        sesion.gps = null;
        clearInterval(sesion.timer); clearInterval(sesion.timerGuardado);
        soltarPantalla();
        document.removeEventListener('visibilitychange', alVisibilidad);
        sesion.genGps = (sesion.genGps || 0) + 1;      // invalida cualquier callback pendiente de la escucha anterior
    }

    async function iniciar(planGuardado, estado, opciones = {}) {
        if (sesion) return false;
        const sel = S.Planner && S.Planner.seleccion;
        const plan = planGuardado || (sel && sel.viaje ? redondear(sel.viaje) : null);
        if (!plan) { S.toast('Primero calcula un viaje y elige una opción.', { tipo: 'info', ms: 4000 }); return false; }
        const prob = S.Gps.problema();
        if (prob) { S.toast(prob.message + (prob.ayuda ? ' ' + prob.ayuda : ''), { tipo: 'error', ms: 12000, clave: 'viaje' }); return false; }
        if ((await S.Gps.permiso()) === 'denied') { const e = S.Gps.errorGps({ code: 1 }); S.toast(e.message + ' ' + e.ayuda, { tipo: 'error', ms: 14000, clave: 'viaje' }); return false; }
        let nucleo;
        try { nucleo = N.crearViaje(plan); } catch (e) { S.toast(e.message, { tipo: 'error', ms: 6000, clave: 'viaje' }); return false; }
        if (estado) nucleo.restaurar(estado);
        // El viaje NO depende del mapa (Google puede tardar o fallar): arranca igual y la capa de dibujo se crea cuando el mapa está disponible.
        // Un viaje reanudado conserva la hora ORIGINAL de inicio y su última posición, que se marca como antigua hasta recibir una lectura nueva.
        const reanudado = !!opciones.reanudado;
        sesion = { plan, nucleo, capa: null, seguir: true, inicio: opciones.inicio || Date.now(), ultimaRecepcion: performance.now(), fase0: true, wake: null,
                   ultima: opciones.ultima || null, antigua: reanudado, genGps: 0 };
        asegurarCapa();
        prepararAudio();
        try { window.SpevbExplorer.bus.suspender(true); } catch { /* sin explorador */ }      // el bus de demostración se pausa: compite con tu posición real y gasta batería
        document.body.classList.add('viaje-activo');
        if (S.Superficies) S.Superficies.viajeIniciado();      // la tarjeta pasa a ser la superficie principal; el planificador cede (sin perder A/B)
        const alerta = $('alightAlert');
        if (alerta) { if (alerta.getAttribute('aria-pressed') === 'true') alerta.click(); alerta.disabled = true; alerta.title = 'El modo viaje ya te avisa antes de bajar.'; }
        $('startTrip').disabled = true; $('startTrip').querySelector('span').textContent = 'Viaje en curso';
        tarjeta.dataset.fase = 'iniciando';
        ponerModo(ui.modo === 'oculta' ? 'compacta' : ui.modo, { recordar: false });      // un viaje nuevo siempre empieza con la tarjeta a la vista
        q('viajeMasPanel').hidden = true; q('viajeMas').setAttribute('aria-expanded', 'false'); q('viajeMas').textContent = 'Más';
        q('viajeTerminar').textContent = 'Terminar viaje'; delete q('viajeTerminar').dataset.llego;
        q('viajeSeguir').setAttribute('aria-pressed', 'true');
        ocultarAviso(); ocultarPregunta(); pintarInfo(nucleo.info());
        window.dispatchEvent(new CustomEvent('spevb-focus-map'));          // el panel baja para dejar ver el mapa
        asegurarRecorrido();
        escucharGps();
        sesion.timer = setInterval(vigilar, 3000);
        sesion.timerGuardado = setInterval(guardar, 15000);      // el avance se guarda cada 15 s (y al ocultar la página): una recarga o un cierre del navegador no lo pierden
        document.addEventListener('visibilitychange', alVisibilidad);
        pedirPantalla();
        guardar();
        estadoUbicacion();
        window.dispatchEvent(new CustomEvent('spevb-viaje-modo', { detail: { activo: true } }));
        return true;
    }

    function finalizar({ borrarEstado = true } = {}) {
        if (!sesion) return;
        detenerEscucha();
        clearTimeout(temporizadorAviso);
        try { sesion.capa && sesion.capa.remove(); } catch { /* nada */ }
        if (S.MapaSalud) { S.MapaSalud.poner('ubicacion', null); }
        try { sesion.capaRecorrido && sesion.capaRecorrido.remove(); } catch { /* nada */ }
        { const mm = mapa(); if (mm && mm.priorizarCaminata) mm.priorizarCaminata(null); }
        sesion = null;
        if (borrarEstado) borrar();
        document.body.classList.remove('viaje-activo');
        try { window.SpevbExplorer.bus.suspender(false); } catch { /* sin explorador */ }
        tarjeta.hidden = true; pastilla.hidden = true; delete tarjeta.dataset.urgente; delete tarjeta.dataset.forzada; pastilla.classList.remove('con-aviso');
        if (S.Superficies) S.Superficies.viajeTerminado();
        const alerta = $('alightAlert'); if (alerta) { alerta.disabled = false; alerta.title = ''; }
        $('startTrip').disabled = false; $('startTrip').querySelector('span').textContent = 'Iniciar viaje';
        window.dispatchEvent(new CustomEvent('spevb-viaje-modo', { detail: { activo: false } }));
    }

    // ------------------------------------------------------------------ recálculo SEGURO
    // «Recalcular desde aquí» NUNCA borra el viaje actual antes de tener otro recorrido utilizable. Con el servidor caído, sin internet, con la búsqueda cancelada o sin opciones,
    // el viaje sigue exactamente como estaba. Usa el destino GUARDADO del viaje (no los campos del planificador, que pueden estar vacíos tras una reanudación).
    let recalculo = null;
    const MENSAJES_RECALCULO = {
        'sin-internet': 'Sin internet: no pude calcular un recorrido nuevo.', lento: 'La conexión está muy lenta: no pude calcular un recorrido nuevo.',
        error: 'El servidor no pudo calcular un recorrido ahora.', 'sin-opciones': 'No encontré otro recorrido utilizable desde donde estás.',
    };
    async function recorridoNuevo(origen, destino, signal) {
        if (!S.Planner || !S.Planner.viajeDe) throw new Error('El planificador todavía no está listo.');
        const data = await S.api('/api/viajes/planificar', { method: 'POST', signal, headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ origen, destino, soloValidadas: false }) });
        const A = { lat: origen.lat, lng: origen.lng }, B = { lat: destino.lat, lng: destino.lng };
        const op = (data.opciones || [])[0] || (data.alternativas || [])[0];
        if (op && op.puntoSubida && op.puntoBajada && op.tramo) return redondear(S.Planner.viajeDe(A, B, [op]));
        const t = (data.transbordos || [])[0];
        if (t && t.primerBus && t.segundoBus) return redondear(S.Planner.viajeDe(A, B, [t.primerBus, t.segundoBus]));
        return null;
    }
    function reposoRecalculo() { recalculo = null; q('viajeRecalcular').textContent = 'Recalcular desde aquí'; }
    async function recalcular() {
        if (!sesion) return;
        if (recalculo) {      // segundo toque = cancelar el cálculo; el viaje no cambió nada
            recalculo.cancelado = true; recalculo.abort.abort(); reposoRecalculo();
            return mostrarAviso('Cálculo cancelado. Tu viaje sigue como estaba.', { nivel: 'info', ms: 6000 });
        }
        const f = sesion.nucleo.filtro;
        const actual = f && f.lat !== null && !sesion.antigua && performance.now() - sesion.ultimaRecepcion < 30000;
        if (!actual) return mostrarAviso('Todavía no tengo tu ubicación actual para recalcular. Tu viaje sigue como estaba.', { nivel: 'info', ms: 7000 });
        const origen = { lat: f.lat, lng: f.lng }, destino = { lat: sesion.plan.destino.lat, lng: sesion.plan.destino.lng };
        const yo = recalculo = { abort: new AbortController(), cancelado: false };
        q('viajeRecalcular').textContent = 'Cancelar el cálculo';
        mostrarAviso('Calculando un recorrido nuevo desde donde estás… tu viaje actual sigue activo.', { nivel: 'info', ms: 0 });
        let nuevo = null, motivo = 'sin-opciones';
        try { nuevo = await recorridoNuevo(origen, destino, yo.abort.signal); }
        catch (err) { motivo = err && err.name === 'AbortError' ? 'cancelado' : err && err.offline ? 'sin-internet' : err && err.timeout ? 'lento' : 'error'; }
        if (recalculo !== yo || yo.cancelado || !sesion) return;      // otra cosa lo superó (cancelación, fin del viaje)
        reposoRecalculo();
        if (!nuevo) return mostrarAviso((MENSAJES_RECALCULO[motivo] || MENSAJES_RECALCULO['sin-opciones']) + ' Tu viaje sigue como estaba.', { nivel: 'alerta', ms: 12000 });
        // Hay un recorrido utilizable: se ofrece cambiar con UNA pregunta. Nada se borra hasta que la persona acepta.
        sesion.propuesta = nuevo;
        ocultarAviso();
        const bus = nuevo.buses && nuevo.buses[0];
        mostrarPregunta('recalculo', 'Encontré un recorrido nuevo desde donde estás' + (bus ? ' (ruta ' + (bus.etiqueta || bus.codigo) + ')' : '') + '. ¿Quieres cambiar a él?');
        q('viajeSi').textContent = 'Sí, cambiar'; q('viajeNo').textContent = 'No, seguir con el actual';
        mostrarCompleta();
    }
    async function aceptarRecalculo() {
        if (!sesion || !sesion.propuesta) return;
        const nuevo = sesion.propuesta;
        const respaldo = { plan: sesion.plan, estado: sesion.nucleo.serializar(), inicio: sesion.inicio, ultima: sesion.ultima };
        ocultarPregunta();
        finalizar({ borrarEstado: false });      // suelta GPS, capa y temporizadores del viaje viejo SIN borrar su estado guardado
        const ok = await iniciar(nuevo);
        if (ok) return;
        // El viaje nuevo no pudo arrancar: se recupera el anterior tal como estaba (misma fase, misma hora de inicio).
        await iniciar(respaldo.plan, respaldo.estado, { reanudado: true, inicio: respaldo.inicio, ultima: respaldo.ultima });
        mostrarAviso('No pude cambiar de recorrido. Sigues con el anterior.', { nivel: 'alerta', ms: 10000 });
    }
    function rechazarRecalculo() { if (sesion) delete sesion.propuesta; ocultarPregunta(); mostrarAviso('Sigues con tu recorrido actual.', { nivel: 'info', ms: 5000 }); }

    // ------------------------------------------------------------------ controles de la tarjeta
    $('startTrip').addEventListener('click', () => iniciar());
    q('viajeSi').addEventListener('click', () => { if (!sesion) return; if (q('viajePregunta').dataset.id === 'recalculo') return aceptarRecalculo(); manejar(sesion.nucleo.responder(q('viajePregunta').dataset.id, true)); });
    q('viajeNo').addEventListener('click', () => { if (!sesion) return; const id = q('viajePregunta').dataset.id; if (id === 'recalculo') return rechazarRecalculo(); manejar(sesion.nucleo.responder(id, false)); ocultarPregunta(); });
    q('viajeYaSubi').addEventListener('click', () => { if (sesion) { manejar(sesion.nucleo.confirmarSubida()); pintarInfo(sesion.nucleo.info()); } });
    q('viajeYaBaje').addEventListener('click', () => { if (sesion) { manejar(sesion.nucleo.confirmarBajada()); pintarInfo(sesion.nucleo.info()); } });
    q('viajeSeguir').addEventListener('click', () => {
        if (!sesion) return;
        sesion.seguir = !sesion.seguir; q('viajeSeguir').setAttribute('aria-pressed', String(sesion.seguir));
        const l = sesion.marcador && sesion.marcador.getPosition(); if (sesion.seguir && l && mapa()) { S.centrarEn(mapa(), l, Math.max(mapa().getZoom(), 16)); sesion.zoomSeguimiento = true; }
    });
    q('viajeRecalcular').addEventListener('click', recalcular);
    q('viajeRecuperarMapa').addEventListener('click', () => { if (S.MapaSalud) S.MapaSalud.recuperar({ manual: true }); else mostrarAviso('La recuperación del mapa no está disponible.', { nivel: 'info', ms: 5000 }); });
    let confirmarFin = null;
    // «Volver al inicio»: la casita pide una confirmación comprensible (diálogo de inicio.js) cuando hay un viaje activo; ya llegado, sale de una vez.
    // El trabajo de limpiar vive en inicio.js (un solo camino para planificación, resultados, viaje y menú).
    function volverAlInicio() {
        finalizar();
        try { S.Planner.limpiar && S.Planner.limpiar(); } catch { /* sin planificador */ }
        const m = mapa(); if (m) m.setView(S.center, 13, { animate: !(S.Motion && S.Motion.reduced) });
        window.dispatchEvent(new CustomEvent('spevb-ir-inicio'));
    }
    q('viajeInicio').addEventListener('click', () => { if (S.Inicio) S.Inicio.pedir('viaje'); else volverAlInicio(); });
    q('viajeTerminar').addEventListener('click', () => {
        if (!sesion) return;
        if (q('viajeTerminar').dataset.llego) return finalizar();
        if (confirmarFin) { clearTimeout(confirmarFin); confirmarFin = null; q('viajeTerminar').textContent = 'Terminar viaje'; return finalizar(); }
        q('viajeTerminar').textContent = '¿Seguro? Toca otra vez';
        confirmarFin = setTimeout(() => { confirmarFin = null; if (sesion) q('viajeTerminar').textContent = 'Terminar viaje'; }, 4000);
    });
    // El primer arrastre manual del mapa deja de seguirte (como en cualquier app de navegación); «Seguirme» lo vuelve a activar.
    // El mapa es asíncrono (Google): se espera al hito «explorador». dragstart solo lo provoca la persona, nunca setView/panTo.
    S.esperar('explorador').then((explorador) => {
        const m0 = (explorador && explorador.map) || mapa();
        if (m0) m0.on('dragstart', () => { if (sesion && sesion.seguir) { sesion.seguir = false; q('viajeSeguir').setAttribute('aria-pressed', 'false'); } });
    });
    // Si la persona cambia o borra el plan mientras viaja, el viaje sigue con su propia copia y se dibuja aparte.
    window.addEventListener('spevb-seleccion', () => { if (sesion) asegurarRecorrido(); });
    document.addEventListener('keydown', (e) => { if (e.key === 'Escape' && sesion && !q('viajePregunta').hidden && tarjeta.contains(document.activeElement)) q('viajeNo').click(); });

    // ------------------------------------------------------------------ reanudar un viaje interrumpido (recarga, cierre del navegador)
    let reanudarAbierta = false, descartarReanudar = () => {};
    function ofrecerReanudar() {
        const g = leer();
        if (!g || !g.plan || !g.estado || Date.now() - g.t > MAX_EDAD_MS || ['llegaste', 'terminado'].includes(g.estado.fase)) { if (g) borrar(); return; }
        reanudarAbierta = true;
        tarjeta.dataset.fase = 'reanudar'; ponerModo('completa', { recordar: false });
        q('viajeTitulo').textContent = 'Tienes un viaje en curso';
        const bus = g.plan.buses[Math.min(g.estado.leg || 0, g.plan.buses.length - 1)];
        q('viajeInstruccion').textContent = 'Ruta ' + (bus.etiqueta || bus.codigo) + ' · iniciado hace ' + Math.max(1, Math.round((Date.now() - (g.inicio || g.t)) / 60000)) + ' min.'
            + (g.ultima ? ' Última posición guardada ' + hace(Date.now() - (g.ultima.t || g.t)) + ' (antigua hasta recibir una lectura nueva).' : '');
        q('viajeBarra').hidden = true; q('viajeMini').hidden = true;
        q('viajePreguntaTexto').textContent = '¿Quieres continuarlo?';
        q('viajePregunta').dataset.id = 'reanudar'; q('viajePregunta').hidden = false;
        q('viajeSi').textContent = 'Reanudar'; q('viajeNo').textContent = 'Descartar';
        q('viajeTerminar').hidden = true; q('viajeMas').hidden = true; q('viajeSeguir').hidden = true;
        const restaurar = () => { q('viajeBarra').hidden = false; q('viajeMini').hidden = false; ['viajeTerminar', 'viajeMas', 'viajeSeguir'].forEach((i) => { q(i).hidden = false; }); };
        const si = q('viajeSi'), no = q('viajeNo');
        // Reanudar NO depende de Google Maps: el viaje arranca aunque el mapa tarde o haya fallado (la capa se dibuja cuando esté disponible). La tarjeta de la pregunta solo se cierra
        // cuando el viaje de verdad arrancó; si no (p. ej. GPS sin permiso), se queda con el motivo y el viaje guardado se conserva.
        const alSi = (e) => {
            if (q('viajePregunta').dataset.id !== 'reanudar') return;
            e.stopImmediatePropagation();
            if (si.disabled) return;
            si.disabled = true;
            iniciar(g.plan, g.estado, { reanudado: true, inicio: g.inicio, ultima: g.ultima || null }).then((ok) => {
                si.disabled = false;
                if (ok) { reanudarAbierta = false; restaurar(); }
                else mostrarAviso('No pude reanudar todavía. Revisa que el GPS esté activado y vuelve a tocar «Reanudar»: tu viaje sigue guardado.', { nivel: 'alerta', ms: 0 });
            }).catch(() => { si.disabled = false; mostrarAviso('No pude reanudar el viaje. Sigue guardado: vuelve a intentarlo.', { nivel: 'alerta', ms: 0 }); });
        };
        const alNo = (e) => { if (q('viajePregunta').dataset.id !== 'reanudar') return; e.stopImmediatePropagation(); reanudarAbierta = false; restaurar(); tarjeta.hidden = true; ocultarPregunta(); borrar(); };
        // «Volver al inicio» con la pregunta de reanudar abierta: se descarta el viaje pendiente (y su estado guardado) como si se hubiera contestado «Descartar».
        descartarReanudar = () => { if (!reanudarAbierta) return; reanudarAbierta = false; restaurar(); tarjeta.hidden = true; ocultarPregunta(); delete tarjeta.dataset.fase; borrar(); };
        si.addEventListener('click', alSi, true); no.addEventListener('click', alNo, true);
    }

    // «Iniciar viaje» es la acción principal tras buscar: aparece justo bajo «Buscar rutas» en cuanto hay una opción elegida, con su resumen.
    const barraInicio = $('startTripBar');
    const fmtM = (m) => (m < 1000 ? m + ' m' : (m / 1000).toLocaleString('es-CO', { maximumFractionDigits: 1 }) + ' km');
    function mostrarBarraInicio() {
        const sel = S.Planner && S.Planner.seleccion;
        barraInicio.hidden = !(sel && sel.viaje) && !sesion;
        const otras = document.querySelectorAll('#tripResults .trip-option').length - 1;
        $('verOpciones').hidden = !!sesion || !(sel && sel.viaje) || otras < 1;
        $('verOpciones').textContent = 'Ver otras opciones · ' + otras;
        if (sesion) { $('startTripInfo').textContent = 'Sigue las indicaciones de la tarjeta del mapa'; return; }
        if (!(sel && sel.viaje)) return;
        const r = sel.resumen;
        $('startTripInfo').textContent = 'Ruta ' + sel.codigos.map((c) => Spevb.rutaNombre(c)).join(' → ') + (r ? ' · ' + r.minutos + ' min · caminas ' + fmtM(r.caminaMetros) + (r.pasajes > 1 ? ' · 2 pasajes' : '') : '');
        // Si ir caminando directo tardaría lo mismo o menos, se dice aquí también (antes de tocar «Iniciar viaje»).
        if (r && r.caminarMejor) $('startTripInfo').textContent += ' · caminar directo ≈ ' + r.directoMin + ' min';
        $('startTripInfo').classList.toggle('walk-better', !!(r && r.caminarMejor));
    }
    window.addEventListener('spevb-seleccion', mostrarBarraInicio);
    window.addEventListener('spevb-viaje-modo', mostrarBarraInicio);
    $('verOpciones').addEventListener('click', () => window.dispatchEvent(new CustomEvent('spevb-ver-opciones')));

    S.Viaje = { iniciar, detener: finalizar, pendiente: () => !sesion && reanudarAbierta, descartarPendiente: () => descartarReanudar(),
        llego: () => !!(sesion && q('viajeTerminar').dataset.llego), activo: () => !!sesion, info: () => (sesion ? sesion.nucleo.info() : null), nucleo: () => (sesion ? sesion.nucleo : null) };
    ofrecerReanudar();
})();
