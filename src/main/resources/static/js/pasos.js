'use strict';
(() => {
    const $ = (id) => document.getElementById(id);
    const status = (message, type = 'info') => {
        $('pasoStatus').textContent = message;
        $('pasoStatus').className = 'status ' + type;
    };
    let map;
    try { map = Spevb.createMap('pasosMap'); }
    catch (error) { status(error.message, 'error'); return; }

    const COLORS = { PASO: '#1a7f4b', BLOQUEO: '#c0392b' };
    const redLayer = L.layerGroup().addTo(map), savedLayer = L.layerGroup().addTo(map), draftLayer = L.layerGroup().addTo(map);
    const MIN_ZOOM_RED = 16;
    let points = [], redSeq = 0, redTimer = null;

    const headers = (json) => ({ ...(json ? { 'Content-Type': 'application/json' } : {}), 'X-Editor-Token': $('pasoToken').value });
    const tokenOk = () => {
        if ($('pasoToken').value.length < 32) { status('Introduce la clave de administración (EDITOR_TOKEN, 32 caracteres o más).', 'error'); return false; }
        return true;
    };

    // ---- calles conocidas (se piden por zona al acercar el mapa)
    async function loadRed() {
        const seq = ++redSeq;
        if (map.getZoom() < MIN_ZOOM_RED) { redLayer.clearLayers(); $('pasoSubtitulo').textContent = 'Acerca el mapa para ver las calles conocidas'; return; }
        const b = map.getBounds();
        try {
            const q = new URLSearchParams({ sur: b.getSouth(), oeste: b.getWest(), norte: b.getNorth(), este: b.getEast() });
            const data = await Spevb.api('/api/caminata/red?' + q);
            if (seq !== redSeq) return;
            redLayer.clearLayers();
            L.polyline(data.map((s) => [[s[1], s[0]], [s[3], s[2]]]), { color: '#6b7280', weight: 2, opacity: 0.8, interactive: false }).addTo(redLayer);
            $('pasoSubtitulo').textContent = data.length ? 'Calles conocidas en gris · pasos en verde · bloqueos en rojo' : 'Sin calles cargadas en esta zona (¿la red aún se está descargando?)';
        } catch (error) {
            if (seq === redSeq) $('pasoSubtitulo').textContent = error.message;
        }
    }
    map.on('moveend', () => { clearTimeout(redTimer); redTimer = setTimeout(loadRed, 250); });

    // ---- dibujo del trazo
    function drawDraft() {
        draftLayer.clearLayers();
        const color = COLORS[$('pasoTipo').value];
        if (points.length) L.polyline(points, { color, weight: 5, dashArray: '8 6' }).addTo(draftLayer);
        points.forEach((p, i) => L.circleMarker(p, { radius: i === 0 || i === points.length - 1 ? 6 : 4, color, weight: 2, fillColor: '#fff', fillOpacity: 1 }).addTo(draftLayer));
        $('pasoGuardar').disabled = points.length < 2;
    }
    map.on('click', (e) => { points.push([e.latlng.lat, e.latlng.lng]); drawDraft(); status(points.length + ' punto(s). Con 2 o más puedes guardar.'); });
    $('pasoDeshacer').onclick = () => { points.pop(); drawDraft(); };
    $('pasoLimpiar').onclick = () => { points = []; drawDraft(); };
    $('pasoTipo').onchange = () => {
        $('pasoAyuda').textContent = $('pasoTipo').value === 'PASO'
            ? 'Un paso debe empezar y terminar en una esquina o sobre una calle (a menos de 25 m). Haz clic en el mapa para añadir puntos.'
            : 'Traza una línea que CRUCE o siga la calle que no se puede recorrer: las calles a menos de 6 m de la línea dejan de usarse.';
        drawDraft();
    };

    // ---- guardado y lista
    $('pasoGuardar').onclick = async () => {
        if (!tokenOk()) return;
        if ($('pasoNombre').value.trim().length < 3) { status('Escribe un nombre de al menos 3 letras.', 'error'); return; }
        $('pasoGuardar').disabled = true;
        status('Guardando y actualizando la red de calles…');
        try {
            const res = await Spevb.api('/api/editor/pasos', {
                method: 'POST', headers: headers(true),
                body: JSON.stringify({ tipo: $('pasoTipo').value, nombre: $('pasoNombre').value.trim(), nota: $('pasoNota').value.trim(),
                    coordenadas: points.map((p) => [p[1], p[0]]) })
            });
            points = []; drawDraft(); $('pasoNombre').value = ''; $('pasoNota').value = '';
            await loadSaved(); loadRed();
            // El aviso del servidor (p. ej. «un extremo no quedó conectado») se muestra al final para que no lo tape la lista.
            status(res.mensaje, res.conectados === 2 || res.tipo === 'BLOQUEO' ? 'success' : 'error');
        } catch (error) {
            status(error.message, 'error');
            $('pasoGuardar').disabled = points.length < 2;
        }
    };

    async function loadSaved() {
        if (!tokenOk()) return;
        try {
            const list = await Spevb.api('/api/editor/pasos', { headers: headers(false) });
            savedLayer.clearLayers();
            $('pasoLista').replaceChildren();
            if (!list.length) { const p = document.createElement('p'); p.className = 'muted'; p.textContent = 'Todavía no hay pasos propios.'; $('pasoLista').append(p); }
            list.forEach((paso) => {
                const latlngs = paso.trazo.coordinates.map((c) => [c[1], c[0]]);
                const line = L.polyline(latlngs, { color: COLORS[paso.tipo], weight: 5, opacity: 0.9, dashArray: paso.tipo === 'BLOQUEO' ? '2 8' : null })
                    .bindTooltip(Spevb.text((paso.tipo === 'PASO' ? 'Paso: ' : 'Bloqueo: ') + paso.nombre)).addTo(savedLayer);
                const row = document.createElement('div');
                row.className = 'paso-row';
                const info = document.createElement('div');
                const title = document.createElement('strong'); title.textContent = (paso.tipo === 'PASO' ? '＋ ' : '✕ ') + paso.nombre;
                const small = document.createElement('small'); small.textContent = paso.metros + ' m' + (paso.nota ? ' · ' + paso.nota : '');
                info.append(title, document.createElement('br'), small);
                const see = document.createElement('button'); see.type = 'button'; see.className = 'secondary'; see.textContent = 'Ver';
                see.onclick = () => map.fitBounds(line.getBounds(), { maxZoom: 18, padding: [40, 40] });
                const del = document.createElement('button'); del.type = 'button'; del.className = 'secondary'; del.textContent = 'Borrar';
                del.onclick = async () => {
                    if (!confirm('¿Borrar «' + paso.nombre + '»? La red de calles se actualizará.')) return;
                    try { await Spevb.api('/api/editor/pasos/' + paso.id, { method: 'DELETE', headers: headers(false) }); await loadSaved(); loadRed(); status('Borrado.', 'success'); }
                    catch (error) { status(error.message, 'error'); }
                };
                row.append(info, see, del);
                $('pasoLista').append(row);
            });
            status(list.length + ' paso(s) guardado(s).', 'success');
        } catch (error) { status(error.message, 'error'); }
    }
    $('pasoCargar').onclick = loadSaved;

    $('redActualizar').onclick = async () => {
        if (!tokenOk()) return;
        try {
            await Spevb.api('/api/editor/red-peatonal/actualizar', { method: 'POST', headers: headers(true), body: '{}' });
            status('Descarga iniciada. Puede tardar varios minutos; mientras tanto se sigue usando la red actual.', 'success');
        } catch (error) { status('No se pudo iniciar (¿ya hay una descarga en curso?): ' + error.message, 'error'); }
    };

    $('pasoVistaGeneral').onclick = () => map.setView(Spevb.center, 13);
    drawDraft();
    loadRed();
})();
