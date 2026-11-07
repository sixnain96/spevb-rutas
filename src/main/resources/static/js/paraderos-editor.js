'use strict';
/* Editor de paraderos (administración). Clic sobre la línea de la ruta = nuevo paradero, pegado al recorrido y ordenado por su posición.
   Se guardan aparte del trazado con PUT /api/editor/rutas/{id}/paraderos (el servidor vuelve a validar todo). */
(() => {
    const $ = (id) => document.getElementById(id);
    const E = () => window.SpevbEditor;
    if (!$('stopList')) return;
    const MAX = 200, SNAP_M = 80;
    let stops = [], mode = false, dirty = false, cargadaId = null, saving = false, seq = 0;
    const Geo = Spevb.Geo;
    let layer = null;       // grupo de marcadores: se crea cuando el editor (y su mapa) ya existe
    const status = (t, tipo = 'info') => { const el = $('stopStatus'); el.hidden = !t; el.textContent = t || ''; el.className = 'status ' + tipo; };
    const setDirty = (v) => { dirty = v; $('stopDirty').textContent = v ? 'Cambios pendientes' : 'Sin cambios'; $('stopDirty').className = 'pill ' + (v ? 'pending' : 'ok'); $('stopSave').disabled = !v || saving; };

    // ---- geometría: pegar un punto a la línea de la ruta y medir su posición a lo largo del recorrido.
    //      Es geometría pura en metros (Spevb.Geo): no depende del zoom del mapa ni de que la proyección del proveedor esté lista.
    //      `latlng` y el `latlng` devuelto son [lat, lng].
    function proyectar(latlng, lineaPts) {
        const pts = lineaPts || E().points();
        if (pts.length < 2) return null;
        const p = Geo.ll(latlng);
        if (!p) return null;
        let mejor = null, acumulado = 0;
        for (let i = 1; i < pts.length; i++) {
            const r = Geo.proyectarEnSegmento(p, pts[i - 1], pts[i]);
            const largo = Geo.distancia(pts[i - 1], pts[i]);
            if (!mejor || r.distanciaM < mejor.d) mejor = { d: r.distanciaM, latlng: r.punto, a: acumulado + largo * r.t };
            acumulado += largo;
        }
        mejor.metros = mejor.d;
        return mejor;
    }
    const orden = () => stops.sort((x, y) => x.a - y.a);

    // ---- pintado
    const icono = (n) => ({ className: 'stop-pin-wrap', html: '<span class="stop-pin">' + n + '</span>', size: [26, 26], anchor: [13, 13] });
    function pintarMapa() {
        if (!layer) return;
        layer.clear();
        const map = E().map;
        stops.forEach((s, i) => {
            const m = map.marker([s.lat, s.lng], { ...icono(i + 1), draggable: mode, keyboard: false, z: 700, tooltip: Spevb.text(s.nombre || ('Paradero ' + (i + 1))) }).addTo(layer);
            m.on('dragend', (ev) => {
                const r = proyectar(ev.latlng || m.getPosition());
                if (!r || r.metros > SNAP_M) { status('Suelta el paradero cerca de la línea de la ruta.', 'error'); pintarMapa(); return; }
                s.lat = r.latlng[0]; s.lng = r.latlng[1]; s.a = r.a; orden(); cambio();
            });
        });
    }
    function pintarLista() {
        const lista = $('stopList');
        lista.replaceChildren();
        $('stopCount').textContent = stops.length + (stops.length === 1 ? ' paradero' : ' paraderos');
        stops.forEach((s, i) => {
            const fila = document.createElement('div');
            fila.className = 'stop-row';
            const n = document.createElement('span'); n.className = 'stop-num'; n.textContent = String(i + 1);
            const input = document.createElement('input');
            input.value = s.nombre; input.maxLength = 160; input.placeholder = 'Nombre del paradero';
            input.setAttribute('aria-label', 'Nombre del paradero ' + (i + 1));
            input.addEventListener('input', () => { s.nombre = input.value; s.manual = true; setDirty(true); });
            input.addEventListener('change', pintarMapa);
            const ir = document.createElement('button'); ir.type = 'button'; ir.className = 'secondary stop-btn'; ir.textContent = 'Ver'; ir.title = 'Ver en el mapa'; ir.setAttribute('aria-label', 'Ver el paradero ' + (i + 1) + ' en el mapa');
            ir.onclick = () => E().map.setView([s.lat, s.lng], Math.max(E().map.getZoom(), 17));
            const del = document.createElement('button'); del.type = 'button'; del.className = 'secondary stop-btn danger'; del.textContent = '×'; del.title = 'Quitar'; del.setAttribute('aria-label', 'Quitar el paradero ' + (i + 1));
            del.onclick = () => { stops.splice(i, 1); cambio(); };
            fila.append(n, input, ir, del);
            lista.append(fila);
        });
    }
    function cambio() { setDirty(true); pintarLista(); pintarMapa(); }
    function modo(v) {
        mode = v;
        $('stopAdd').setAttribute('aria-pressed', String(v));
        $('stopAdd').textContent = v ? 'Terminar de agregar' : 'Agregar paraderos';
        E().map.el.classList.toggle('adding-stops', v);
        if (v) E().setDrawing(false);
        pintarMapa();
        status(v ? 'Haz clic sobre la línea de la ruta para poner un paradero.' : '', 'info');
    }

    // ---- carga desde la ruta abierta en el editor
    function cargar() {
        const r = E() && E().route();
        if (!layer && E()) layer = E().map.group().addTo(E().map);
        const sinRuta = !r || r.id == null;
        $('stopAdd').disabled = sinRuta; $('stopFit').disabled = sinRuta; $('stopClear').disabled = sinRuta;
        $('stopHint').dataset.nueva = String(!!(r && r.id == null));
        if (mode) modo(false);
        cargadaId = r ? r.id : null;
        stops = sinRuta ? [] : (r.paraderos || []).map((p) => {
            const lat = p.ubicacion.coordinates[1], lng = p.ubicacion.coordinates[0];
            const pr = proyectar([lat, lng]);
            return { id: p.id, nombre: p.nombre, lat, lng, a: pr ? pr.a : p.secuencia * 1e6, manual: true };
        });
        orden(); setDirty(false); pintarLista(); pintarMapa();
        status(sinRuta && r ? 'Guarda primero la ruta para poder agregarle paraderos.' : '', 'info');
    }
    window.addEventListener('spevb-editor-ruta', () => setTimeout(cargar, 0));
    window.addEventListener('spevb-editor-dibujo', (e) => { if (e.detail.activo && mode) modo(false); });
    window.addEventListener('beforeunload', (e) => { if (dirty) { e.preventDefault(); e.returnValue = ''; } });

    // ---- revisión tras cambiar la geometría de la ruta: NO mueve nada sin confirmación y NO borra paraderos.
    //      Compara la distancia de cada paradero a la línea GUARDADA y a la línea ACTUAL del editor (con los cambios sin guardar).
    const LEJOS_M = 30;      // el servidor exige que cada paradero quede a ≤ 30 m del recorrido al guardar (ParaderoService.DISTANCIA_MAXIMA_M)
    let revision = [], revTimer = null;
    function lineaGuardada() {
        const r = E() && E().route();
        return r && r.recorrido && r.recorrido.coordinates ? r.recorrido.coordinates.map((c) => [c[1], c[0]]) : [];
    }
    function revisar(mostrarSiempre) {
        const r = E() && E().route();
        const box = $('stopRevision'), lista = $('stopRevisionList');
        lista.replaceChildren();
        if (!r || r.id == null || !stops.length || E().points().length < 2) {
            revision = []; box.hidden = !mostrarSiempre; $('stopAjustarTodos').disabled = true;
            if (mostrarSiempre) { box.textContent = stops.length ? 'Necesitas un recorrido de al menos 2 puntos.' : 'Esta ruta no tiene paraderos.'; box.className = 'status info'; }
            return;
        }
        const vieja = lineaGuardada();
        revision = stops.map((s) => {
            const nueva = proyectar([s.lat, s.lng]);
            const ant = vieja.length > 1 ? proyectar([s.lat, s.lng], vieja) : null;
            return { s, antes: ant ? ant.metros : null, ahora: nueva.metros, destino: nueva.latlng, a: nueva.a, lejos: nueva.metros > LEJOS_M };
        });
        const lejos = revision.filter((x) => x.lejos);
        $('stopAjustarTodos').disabled = !lejos.length;
        if (!lejos.length && !mostrarSiempre) { box.hidden = true; return; }
        box.hidden = false;
        box.className = 'status ' + (lejos.length ? 'error' : 'success');
        box.textContent = lejos.length ? lejos.length + (lejos.length === 1 ? ' paradero quedó lejos' : ' paraderos quedaron lejos') + ' del recorrido actual (más de ' + LEJOS_M + ' m). No se movieron: elige «Ajustar» en cada uno y luego guarda los paraderos.'
            : 'Todos los paraderos están a ' + LEJOS_M + ' m o menos del recorrido actual.';
        (mostrarSiempre ? revision : lejos).forEach((x) => {
            const fila = document.createElement('div'); fila.className = 'stop-row';
            const t = document.createElement('span'); t.className = 'stop-name';
            t.textContent = x.s.nombre + ': ' + (x.antes == null ? '—' : Math.round(x.antes) + ' m') + ' → ' + Math.round(x.ahora) + ' m';
            fila.append(t);
            if (x.lejos) {
                const b = document.createElement('button'); b.type = 'button'; b.className = 'secondary stop-btn'; b.textContent = 'Ajustar';
                b.setAttribute('aria-label', 'Ajustar el paradero ' + x.s.nombre + ' a la nueva línea');
                b.onclick = () => { ajustar(x); };
                fila.append(b);
            }
            lista.append(fila);
        });
    }
    function ajustar(x) {
        if (!stops.includes(x.s)) return;
        x.s.lat = x.destino[0]; x.s.lng = x.destino[1]; x.s.a = x.a;      // se conservan id, nombre y nota; solo cambia la posición (en memoria, sin guardar)
        orden(); cambio(); revisar(false);
        status('Paradero «' + x.s.nombre + '» ajustado a la línea. Pulsa «Guardar paraderos» para confirmarlo.', 'success');
    }
    $('stopRevisar').onclick = () => revisar(true);
    $('stopAjustarTodos').onclick = () => { revision.filter((x) => x.lejos).forEach((x) => { if (stops.includes(x.s)) { x.s.lat = x.destino[0]; x.s.lng = x.destino[1]; x.s.a = x.a; } }); orden(); cambio(); revisar(false); status('Paraderos lejanos ajustados a la línea. Pulsa «Guardar paraderos» para confirmarlo.', 'success'); };
    window.addEventListener('spevb-editor-geometria', () => { clearTimeout(revTimer); revTimer = setTimeout(() => revisar(false), 350); });

    // ---- agregar con clic
    function iniciar() {
        E().map.on('click', async (e) => {
            if (!mode || E().drawing() || E().busy() || !e.latlng) return;
            if (stops.length >= MAX) return status('El máximo es de ' + MAX + ' paraderos por ruta.', 'error');
            const r = proyectar(e.latlng);
            if (!r) return status('La ruta necesita al menos dos puntos de recorrido.', 'error');
            if (r.metros > SNAP_M) return status('Haz clic más cerca de la línea de la ruta (a menos de ' + SNAP_M + ' m).', 'error');
            const por_defecto = 'Paradero ' + (++seq + stops.length);
            const s = { id: null, nombre: por_defecto, lat: r.latlng[0], lng: r.latlng[1], a: r.a, manual: false };
            stops.push(s); orden(); cambio(); status('Paradero agregado. Puedes cambiarle el nombre en la lista.', 'success');
            // Sugerencia de nombre: la dirección o calle más cercana (si el servicio responde y la persona no lo cambió).
            try {
                const lugar = await Spevb.api('/api/lugares/reverso?lat=' + s.lat + '&lng=' + s.lng, { timeout: 6000 });
                const nombre = (lugar && lugar.nombre || '').trim();
                if (nombre && !/^punto en mapa/i.test(nombre) && !s.manual && stops.includes(s)) { s.nombre = nombre.slice(0, 160); pintarLista(); pintarMapa(); }
            } catch { /* se queda el nombre por defecto */ }
        });
    }
    Spevb.esperar('editor').then(iniciar);      // el mapa del editor se crea de forma asíncrona
    $('stopAdd').onclick = () => { if (!E().route() || E().route().id == null) return; modo(!mode); };
    $('stopFit').onclick = () => { const caja = Geo.limites(stops.map((s) => [s.lat, s.lng])); if (caja) E().map.fitBounds(caja, { padding: 60, maxZoom: 18 }); };
    $('stopClear').onclick = () => { if (stops.length && confirm('¿Quitar todos los paraderos de esta ruta? Se aplica al guardar.')) { stops = []; cambio(); } };

    // ---- guardar
    $('stopSave').onclick = async () => {
        const r = E().route();
        if (!r || r.id == null || saving) return;
        const token = $('editorToken').value;
        if (token.length < 32) return status('Introduce la clave de administración (arriba, en «Guardar y documentar»), o entra como administrador.', 'error');
        if (stops.some((s) => !s.nombre.trim() || s.nombre.trim().length < 2)) return status('Todos los paraderos necesitan un nombre (mínimo 2 letras).', 'error');
        saving = true; $('stopSave').disabled = true; status('Guardando paraderos…');
        try {
            const guardada = await Spevb.api('/api/editor/rutas/' + r.id + '/paraderos', {
                method: 'PUT', headers: { 'Content-Type': 'application/json', 'X-Editor-Token': token },
                body: JSON.stringify({ paraderos: stops.map((s) => ({ nombre: s.nombre.trim(), lat: Number(s.lat.toFixed(7)), lng: Number(s.lng.toFixed(7)) })) }),
            });
            r.paraderos = guardada.paraderos;
            saving = false; cargar();
            status('Guardados ' + guardada.paraderos.length + ' paraderos. Ya aparecen en el explorador.', 'success');
        } catch (err) { saving = false; setDirty(true); status(err.message, 'error'); }
    };
})();
