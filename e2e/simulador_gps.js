/* Simulador de recorridos GPS para las pruebas del «Modo viaje» (solo pruebas; no se publica con la aplicación).
   Genera lecturas como las de un teléfono (una por segundo, con ruido, saltos y lecturas malas) siguiendo un plan real:
   caminar → esperar → bus → bajar → caminar. También sustituye navigator.geolocation por uno controlable (window.__geo). */
(() => {
    const N = () => Spevb.ViajeNucleo;
    const d = (a, b) => N().dist(a, b);
    const lerp = (a, b, m) => { const D = d(a, b); if (D === 0) return { lat: a.lat, lng: a.lng }; const f = Math.min(1, m / D); return { lat: a.lat + (b.lat - a.lat) * f, lng: a.lng + (b.lng - a.lng) * f }; };
    const ll = (c) => ({ lat: c[1], lng: c[0] });

    /** Aleatorio determinista (mulberry32): la misma semilla produce siempre el mismo recorrido. */
    function azar(semilla) { let a = semilla >>> 0; return () => { a = (a + 0x6D2B79F5) >>> 0; let t = a; t = Math.imul(t ^ (t >>> 15), t | 1); t ^= t + Math.imul(t ^ (t >>> 7), t | 61); return ((t ^ (t >>> 14)) >>> 0) / 4294967296; }; }
    const gauss = (r) => Math.sqrt(-2 * Math.log(r() || 1e-9)) * Math.cos(2 * Math.PI * r());

    /** Recorre una polilínea [{lat,lng}] con velocidad vFn(metros recorridos) y una lectura cada dt segundos. st.t es el reloj simulado (ms). */
    function recorrer(pts, vFn, dt, st, tipo) {
        const out = []; let seg = 0, pos = { lat: pts[0].lat, lng: pts[0].lng }, hecho = 0;
        while (seg < pts.length - 1) {
            let avance = Math.max(0.01, vFn(hecho)) * dt; hecho += avance;
            while (avance > 0 && seg < pts.length - 1) {
                const D = d(pos, pts[seg + 1]);
                if (D <= avance) { avance -= D; pos = { lat: pts[seg + 1].lat, lng: pts[seg + 1].lng }; seg++; } else { pos = lerp(pos, pts[seg + 1], avance); avance = 0; }
            }
            st.t += dt * 1000; out.push({ lat: pos.lat, lng: pos.lng, precision: 5, t: st.t, velocidad: null, tipo });
        }
        return out;
    }
    function quieto(pos, segundos, dt, st, tipo) {
        const out = [];
        for (let s = 0; s < segundos; s += dt) { st.t += dt * 1000; out.push({ lat: pos.lat, lng: pos.lng, precision: 5, t: st.t, velocidad: null, tipo }); }
        return out;
    }
    /** Bus: arranca y frena suavemente (como un bus de verdad) y cruza el resto del tramo a vBus. */
    const perfilBus = (vBus, total) => (m) => { const sube = 0.5 + Math.sqrt(2 * 1.0 * m), baja = 0.5 + Math.sqrt(2 * 1.2 * Math.max(0, total - m)); return Math.max(0.5, Math.min(vBus, sube, baja)); };

    /**
     * @param plan  { origen, destino, buses:[{subida, bajada, tramo}] }
     * @param op    { vCamina, vBus, esperaSeg, paradaSeg, dt, desvioInicio:{lat,lng}, semaforos:[{avance(m), seg}], transbordoEsperaSeg, empezarEn:'origen'|{lat,lng} }
     * @returns lecturas con .tipo: 'caminar' | 'espera' | 'bus' | 'semaforo' | 'parada' | 'camina_cambio' | 'camina_final'
     */
    function viaje(plan, op = {}) {
        const o = { vCamina: 1.35, vBus: 7, esperaSeg: 40, paradaSeg: 12, dt: 1, semaforos: [], transbordoEsperaSeg: 30, ...op };
        const st = { t: op.t0 || 1700000000000 }; const out = [];
        let pos = o.empezarEn ? { ...o.empezarEn } : { ...plan.origen };
        plan.buses.forEach((b, i) => {
            const subida = { ...b.subida }, bajada = { ...b.bajada };
            if (d(pos, subida) > 1) out.push(...recorrer([pos, subida], () => o.vCamina, o.dt, st, i === 0 ? 'caminar' : 'camina_cambio'));
            out.push(...quieto(subida, i === 0 ? o.esperaSeg : o.transbordoEsperaSeg, o.dt, st, 'espera'));
            const pts = b.tramo.map(ll), pre = N().prepararLinea(b.tramo);
            const paradas = (o.semaforos || []).slice().sort((x, y) => x.avance - y.avance);
            let desde = 0;
            const trozos = paradas.map((s) => s.avance).concat([pre.total]);
            trozos.forEach((hasta, k) => {
                const sub = recortar(pts, pre, desde, hasta);
                if (sub.length > 1) out.push(...recorrer(sub, perfilBus(o.vBus, hasta - desde), o.dt, st, 'bus'));
                if (k < paradas.length) out.push(...quieto(sub[sub.length - 1], paradas[k].seg, o.dt, st, 'semaforo'));
                desde = hasta;
            });
            out.push(...quieto(bajada, o.paradaSeg, o.dt, st, 'parada'));
            pos = bajada;
        });
        out.push(...recorrer([pos, { ...plan.destino }], () => o.vCamina, o.dt, st, 'camina_final'));
        return out;
    }
    function recortar(pts, pre, desde, hasta) {
        const r = [];
        const en = (m) => { for (let i = 0; i < pre.acum.length - 1; i++) if (m <= pre.acum[i + 1]) { const f = (m - pre.acum[i]) / Math.max(1e-6, pre.acum[i + 1] - pre.acum[i]); return { lat: pts[i].lat + (pts[i + 1].lat - pts[i].lat) * f, lng: pts[i].lng + (pts[i + 1].lng - pts[i].lng) * f }; } return pts[pts.length - 1]; };
        r.push(en(desde));
        for (let i = 0; i < pre.acum.length; i++) if (pre.acum[i] > desde && pre.acum[i] < hasta) r.push(pts[i]);
        r.push(en(hasta));
        return r;
    }

    /** Ruido de teléfono: sigma (m) por lectura, saltos puntuales (m), lecturas malas (precisión 250 m) y huecos (se quitan lecturas). */
    function ruido(lecturas, { sigma = 6, semilla = 1, saltos = [], malas = [], huecos = [], saltoM = 700 } = {}) {
        const r = azar(semilla), kx = 111320 * Math.cos(4.15 * Math.PI / 180), ky = 110574;
        const out = [];
        lecturas.forEach((l, i) => {
            if (huecos.some(([a, b]) => i >= a && i < b)) return;
            let dx = gauss(r) * sigma, dy = gauss(r) * sigma, precision = Math.max(4, sigma * 1.5);
            if (saltos.includes(i)) { dx += saltoM; dy += saltoM * 0.4; precision = Math.max(precision, 12); }
            if (malas.includes(i)) { dx += gauss(r) * 90; dy += gauss(r) * 90; precision = 250; }
            out.push({ ...l, lat: l.lat + dy / ky, lng: l.lng + dx / kx, precision });
        });
        return out;
    }

    /** Ejecuta el núcleo con una lista de lecturas y responde las preguntas como diga `resp` ({subida:true|false|[false,true,...], bajada:true, demora:n lecturas}). */
    function correr(plan, lecturas, resp = {}, cfg) {
        const v = N().crearViaje(plan, { cfg });
        const eventos = [], cuenta = {}; let pendiente = null;
        const respuestas = { subida: [].concat(resp.subida === undefined ? true : resp.subida), bajada: [].concat(resp.bajada === undefined ? true : resp.bajada) };
        const demora = resp.demora || 0;
        const usar = (lista) => { for (const e of lista) { eventos.push({ ...e, i: cuenta.i, info: undefined, lectura: undefined }); if (e.tipo === 'pregunta' && !resp.ignorar) pendiente = { id: e.id, en: cuenta.i + demora }; } };
        lecturas.forEach((l, i) => {
            cuenta.i = i;
            usar(v.procesar(l));
            if (pendiente && i >= pendiente.en) {
                const lista = respuestas[pendiente.id]; const si = lista.length > 1 ? lista.shift() : lista[0];
                const id = pendiente.id; pendiente = null;
                usar(v.responder(id, si));
            }
        });
        return { eventos, info: v.info(), v };
    }
    const secuencia = (eventos) => eventos.filter((e) => ['fase', 'pregunta', 'alerta', 'se_paso', 'senal_recuperada', 'pregunta_retirada', 'desvio', 'desvio_bus', 'llego'].includes(e.tipo))
        .map((e) => (e.tipo === 'fase' ? 'fase:' + e.fase : e.tipo === 'pregunta' ? 'pregunta:' + e.id : e.tipo === 'alerta' ? 'alerta:' + e.nivel : e.tipo));

    // ------------------------------------------------------------------ geolocalización falsa controlable (para las pruebas de interfaz)
    function instalarGeo() {
        const cbs = new Map(); let n = 0, ultima = null;
        const pos = (r) => ({ coords: { latitude: r.lat, longitude: r.lng, accuracy: r.precision || 8, speed: r.velocidad ?? null, heading: null, altitude: null, altitudeAccuracy: null }, timestamp: r.t });
        const geo = {
            watchPosition(ok, err) { const id = ++n; cbs.set(id, { ok, err }); window.__geo.escuchas = cbs.size; return id; },
            clearWatch(id) { cbs.delete(id); window.__geo.escuchas = cbs.size; },
            getCurrentPosition(ok, err) { if (ultima) ok(pos(ultima)); else if (err) err({ code: 2, message: 'sin lectura' }); },
        };
        Object.defineProperty(navigator, 'geolocation', { configurable: true, value: geo });
        window.__geo = {
            escuchas: 0, total: 0,
            emitir(r) { ultima = r; window.__geo.total++; cbs.forEach((c) => c.ok(pos(r))); },
            error(codigo) { cbs.forEach((c) => c.err && c.err({ code: codigo, message: 'error simulado' })); },
        };
        window.__geo.cbs = cbs;
    }

    /** Emite lecturas por la geolocalización falsa desde `desde`; se detiene cuando se cumple `parar` ('pregunta' | 'aviso' | 'fase:<x>' | 'alerta:<nivel>') y devuelve el índice siguiente. */
    async function alimentar(lecturas, desde, parar) {
        const cond = () => {
            if (!parar) return false;
            const card = document.getElementById('viajeCard');
            if (parar === 'pregunta') return !document.getElementById('viajePregunta').hidden;
            if (parar === 'aviso') return !document.getElementById('viajeAviso').hidden;
            if (parar.startsWith('fase:')) return card.dataset.fase === parar.slice(5);
            if (parar.startsWith('alerta:')) { const a = document.getElementById('viajeAviso'); return !a.hidden && a.dataset.nivel === parar.slice(7); }
            return false;
        };
        let i = desde;
        for (; i < lecturas.length; i++) {
            window.__geo.emitir(lecturas[i]);
            if (cond()) { i++; break; }
            if (i % 20 === 0) await new Promise((r) => setTimeout(r, 0));
        }
        return i;
    }

    window.SimGps = { viaje, ruido, correr, secuencia, recorrer, quieto, instalarGeo, azar, alimentar };
})();
