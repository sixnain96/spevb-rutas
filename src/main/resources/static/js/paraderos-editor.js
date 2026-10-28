'use strict';
/* Editor de paraderos (administración). Clic sobre la línea de la ruta = nuevo paradero, pegado al recorrido y ordenado por su posición.
   Se guardan aparte del trazado con PUT /api/editor/rutas/{id}/paraderos (el servidor vuelve a validar todo). */
(() => {
    const $ = (id) => document.getElementById(id);
    const E = () => window.SpevbEditor;
    if (!$('stopList')) return;
    const MAX = 200, SNAP_M = 80;
    let stops = [], mode = false, dirty = false, cargadaId = null, saving = false, seq = 0;
    const layer = L.layerGroup();
    let layerOn = false;
    const status = (t, tipo = 'info') => { const el = $('stopStatus'); el.hidden = !t; el.textContent = t || ''; el.className = 'status ' + tipo; };
    const setDirty = (v) => { dirty = v; $('stopDirty').textContent = v ? 'Cambios pendientes' : 'Sin cambios'; $('stopDirty').className = 'pill ' + (v ? 'pending' : 'ok'); $('stopSave').disabled = !v || saving; };

    // ---- geometría: pegar un punto a la línea de la ruta y medir su posición a lo largo del recorrido
    function proyectar(latlng) {
        const pts = E().points();
        if (pts.length < 2) return null;
        const map = E().map, z = map.getZoom();
        const p = map.project(latlng, z);
        let mejor = null, acumulado = 0;
        for (let i = 1; i < pts.length; i++) {
            const a = map.project(L.latLng(pts[i - 1]), z), b = map.project(L.latLng(pts[i]), z);
            const c = L.LineUtil.closestPointOnSegment(p, a, b);
            const d = p.distanceTo(c);
            const largo = L.latLng(pts[i - 1]).distanceTo(L.latLng(pts[i]));
            if (!mejor || d < mejor.d) {
                const t = a.distanceTo(b) ? a.distanceTo(c) / a.distanceTo(b) : 0;
                mejor = { d, latlng: map.unproject(c, z), a: acumulado + largo * t };
            }
            acumulado += largo;
        }
        mejor.metros = latlng.distanceTo(mejor.latlng);
        return mejor;
    }
    const orden = () => stops.sort((x, y) => x.a - y.a);

    // ---- pintado
    function icono(n) { return L.divIcon({ className: 'stop-pin-wrap', html: '<span class="stop-pin">' + n + '</span>', iconSize: [26, 26], iconAnchor: [13, 13] }); }
    function pintarMapa() {
        layer.clearLayers();
        stops.forEach((s, i) => {
            const m = L.marker([s.lat, s.lng], { icon: icono(i + 1), draggable: mode, keyboard: false, zIndexOffset: 700 }).addTo(layer).bindTooltip(Spevb.text(s.nombre || ('Paradero ' + (i + 1))));
            m.on('dragend', () => {
                const r = proyectar(m.getLatLng());
                if (!r || r.metros > SNAP_M) { status('Suelta el paradero cerca de la línea de la ruta.', 'error'); pintarMapa(); return; }
                s.lat = r.latlng.lat; s.lng = r.latlng.lng; s.a = r.a; orden(); cambio();
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
            input.addEventListener('input', () => { s.nombre = input.value; s.manual = true; setDirty(true); layer.eachLayer?.(() => {}); });
            input.addEventListener('change', pintarMapa);
            const ir = document.createElement('button'); ir.type = 'button'; ir.className = 'secondary stop-btn'; ir.textContent = '⌖'; ir.title = 'Ver en el mapa'; ir.setAttribute('aria-label', 'Ver el paradero ' + (i + 1) + ' en el mapa');
            ir.onclick = () => E().map.setView([s.lat, s.lng], Math.max(E().map.getZoom(), 17));
            const del = document.createElement('button'); del.type = 'button'; del.className = 'secondary stop-btn danger'; del.textContent = '✕'; del.title = 'Quitar'; del.setAttribute('aria-label', 'Quitar el paradero ' + (i + 1));
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
        E().map.getContainer().classList.toggle('adding-stops', v);
        if (v) E().setDrawing(false);
        pintarMapa();
        status(v ? 'Haz clic sobre la línea de la ruta para poner un paradero.' : '', 'info');
    }

    // ---- carga desde la ruta abierta en el editor
    function cargar() {
        const r = E() && E().route();
        if (!layerOn && E()) { layer.addTo(E().map); layerOn = true; }
        const sinRuta = !r || r.id == null;
        $('stopAdd').disabled = sinRuta; $('stopFit').disabled = sinRuta; $('stopClear').disabled = sinRuta;
        $('stopHint').dataset.nueva = String(!!(r && r.id == null));
        if (mode) modo(false);
        cargadaId = r ? r.id : null;
        stops = sinRuta ? [] : (r.paraderos || []).map((p) => {
            const lat = p.ubicacion.coordinates[1], lng = p.ubicacion.coordinates[0];
            const pr = proyectar(L.latLng(lat, lng));
            return { id: p.id, nombre: p.nombre, lat, lng, a: pr ? pr.a : p.secuencia * 1e6, manual: true };
        });
        orden(); setDirty(false); pintarLista(); pintarMapa();
        status(sinRuta && r ? 'Guarda primero la ruta para poder agregarle paraderos.' : '', 'info');
    }
    window.addEventListener('spevb-editor-ruta', () => setTimeout(cargar, 0));
    window.addEventListener('spevb-editor-dibujo', (e) => { if (e.detail.activo && mode) modo(false); });
    window.addEventListener('beforeunload', (e) => { if (dirty) { e.preventDefault(); e.returnValue = ''; } });

    // ---- agregar con clic
    function iniciar() {
        if (!E()) return setTimeout(iniciar, 100);
        E().map.on('click', async (e) => {
            if (!mode || E().drawing() || E().busy()) return;
            if (stops.length >= MAX) return status('El máximo es de ' + MAX + ' paraderos por ruta.', 'error');
            const r = proyectar(e.latlng);
            if (!r) return status('La ruta necesita al menos dos puntos de recorrido.', 'error');
            if (r.metros > SNAP_M) return status('Haz clic más cerca de la línea de la ruta (a menos de ' + SNAP_M + ' m).', 'error');
            const por_defecto = 'Paradero ' + (++seq + stops.length);
            const s = { id: null, nombre: por_defecto, lat: r.latlng.lat, lng: r.latlng.lng, a: r.a, manual: false };
            stops.push(s); orden(); cambio(); status('Paradero agregado. Puedes cambiarle el nombre en la lista.', 'success');
            // Sugerencia de nombre: la dirección o calle más cercana (si el servicio responde y la persona no lo cambió).
            try {
                const lugar = await Spevb.api('/api/lugares/reverso?lat=' + s.lat + '&lng=' + s.lng, { timeout: 6000 });
                const nombre = (lugar && lugar.nombre || '').trim();
                if (nombre && !/^punto en mapa/i.test(nombre) && !s.manual && stops.includes(s)) { s.nombre = nombre.slice(0, 160); pintarLista(); pintarMapa(); }
            } catch { /* se queda el nombre por defecto */ }
        });
    }
    iniciar();
    $('stopAdd').onclick = () => { if (!E().route() || E().route().id == null) return; modo(!mode); };
    $('stopFit').onclick = () => { if (stops.length) E().map.fitBounds(L.latLngBounds(stops.map((s) => [s.lat, s.lng])), { padding: [60, 60], maxZoom: 18 }); };
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
