'use strict';
/* Administración de puentes peatonales: se marcan en el mapa, los extremos se pegan a la calle más cercana y se guardan
   como pasos de tipo PUENTE (la red de calles los usa para cruzar). La lista es pública; guardar y borrar piden administrador. */
(() => {
    const $ = (id) => document.getElementById(id);
    const status = (message, type = 'info') => { $('puenteStatus').textContent = message; $('puenteStatus').className = 'status ' + type; };
    let map;
    try { map = Spevb.createMap('puentesMap'); }
    catch (error) { status(error.message, 'error'); return; }

    const AZUL = '#1e5fbf', MIN_ZOOM_RED = 16, SNAP_METROS = 25;
    const redLayer = L.layerGroup().addTo(map), savedLayer = L.layerGroup().addTo(map), draftLayer = L.layerGroup().addTo(map);
    const G = Spevb.EditorGeo;
    const editLayer = L.layerGroup().addTo(map), originalLayer = L.layerGroup().addTo(map);
    map.doubleClickZoom.disable();      // en edición, el doble clic inserta un vértice
    const proj = (p) => { const q = map.latLngToContainerPoint(p); return [q.x, q.y]; };
    let points = [], segments = [], redSeq = 0, redTimer = null;
    let editando = null, original = [], undoS = [], redoS = [], selV = -1;     // editando: { id, version, nombre } mientras se edita un puente existente
    const copia = () => points.map((p) => [p[0], p[1]]);
    function recordar() { undoS.push(copia()); if (undoS.length > 100) undoS.shift(); redoS = []; }

    const headers = (json) => ({ ...(json ? { 'Content-Type': 'application/json' } : {}), 'X-Editor-Token': $('puenteToken').value });
    const tokenOk = () => {
        if ($('puenteToken').value.length < 32) { status('Introduce la clave de administración (EDITOR_TOKEN, 32 caracteres o más) o entra como administrador.', 'error'); return false; }
        return true;
    };

    // ---- calles conocidas
    async function loadRed() {
        const seq = ++redSeq;
        if (map.getZoom() < MIN_ZOOM_RED) { redLayer.clearLayers(); segments = []; $('puenteSubtitulo').textContent = 'Acerca el mapa para ver las calles'; return; }
        const b = map.getBounds();
        try {
            const q = new URLSearchParams({ sur: b.getSouth(), oeste: b.getWest(), norte: b.getNorth(), este: b.getEast() });
            const data = await Spevb.api('/api/caminata/red?' + q);
            if (seq !== redSeq) return;
            segments = data;
            redLayer.clearLayers();
            L.polyline(data.map((s) => [[s[1], s[0]], [s[3], s[2]]]), { color: '#6b7280', weight: 2, opacity: 0.8, interactive: false }).addTo(redLayer);
            $('puenteSubtitulo').textContent = data.length ? 'Calles en gris · puentes en azul' : 'Sin calles cargadas en esta zona (¿la red aún se está descargando?)';
            drawDraft();
        } catch (error) { if (seq === redSeq) $('puenteSubtitulo').textContent = error.message; }
    }
    map.on('moveend', () => { clearTimeout(redTimer); redTimer = setTimeout(loadRed, 250); });

    // ---- pegar un extremo a la calle más cercana (distancias en metros, proyección plana local)
    function snap(p) {
        const kx = Math.cos(p[0] * Math.PI / 180) * 111320, ky = 110574;
        let best = null, bestD = Infinity;
        for (const s of segments) {
            const ax = (s[0] - p[1]) * kx, ay = (s[1] - p[0]) * ky, bx = (s[2] - p[1]) * kx, by = (s[3] - p[0]) * ky;
            const dx = bx - ax, dy = by - ay, len2 = dx * dx + dy * dy;
            const t = len2 ? Math.max(0, Math.min(1, -(ax * dx + ay * dy) / len2)) : 0;
            const px = ax + t * dx, py = ay + t * dy, d = Math.hypot(px, py);
            if (d < bestD) { bestD = d; best = [p[0] + py / ky, p[1] + px / kx]; }
        }
        return best && bestD <= SNAP_METROS ? { punto: best, metros: bestD } : null;
    }
    // Primer y último punto se pegan a la calle; si no hay calle cerca se dejan donde se tocó y se avisa.
    function efectivos() {
        return points.map((p, i) => {
            if (i !== 0 && i !== points.length - 1) return { punto: p, pegado: null };
            const s = snap(p);
            return { punto: s ? s.punto : p, pegado: !!s };
        });
    }

    function drawDraft() {
        draftLayer.clearLayers();
        const ef = efectivos();
        if (ef.length) {
            L.polyline(ef.map((e) => e.punto), { color: '#fff', weight: 10, opacity: 0.9, interactive: false }).addTo(draftLayer);
            L.polyline(ef.map((e) => e.punto), { color: AZUL, weight: 6, dashArray: '10 7', interactive: false }).addTo(draftLayer);
        }
        ef.forEach((e, i) => {
            const extremo = i === 0 || i === ef.length - 1;
            L.circleMarker(e.punto, { radius: extremo ? 8 : 5, color: extremo ? (e.pegado ? '#1a7f4b' : '#c0392b') : AZUL, weight: 3, fillColor: '#fff', fillOpacity: 1, interactive: false }).addTo(draftLayer);
        });
        editLayer.clearLayers();
        if (editando) {
            points.forEach((p, i) => {
                const m = L.marker(p, { draggable: true, bubblingMouseEvents: false, icon: L.divIcon({ className: 'vertex-wrapper', iconSize: i === selV ? [28, 28] : [18, 18], iconAnchor: i === selV ? [14, 14] : [9, 9],
                    html: '<span class="vertex' + (i === selV ? ' selected' : '') + '"></span>' }) }).addTo(editLayer).bindTooltip(Spevb.text('Vértice ' + (i + 1)));
                m.on('click', () => { selV = i; drawDraft(); });
                m.on('dragstart', recordar);
                m.on('dragend', (ev) => { const q = ev.target.getLatLng(); points[i] = [q.lat, q.lng]; selV = i; drawDraft(); });
            });
            const largo = (arr) => Math.round(G.longitud(arr));
            $('puenteEditando').textContent = 'Editando «' + editando.nombre + '» · ' + points.length + ' vértices · largo ' + largo(original) + ' m → ' + largo(ef.map((e) => e.punto)) + ' m';
        }
        $('puenteRehacer').disabled = !redoS.length;
        $('puenteEliminarV').disabled = !editando || selV < 0 || points.length <= 2;
        const ini = ef[0], fin = ef.length > 1 ? ef[ef.length - 1] : null;
        const parte = (e, n) => e == null ? '' : (e.pegado ? n + ': conectado a la calle' : n + ': lejos de una calle');
        $('puenteConexion').textContent = [parte(ini, 'Inicio'), parte(fin, 'Final')].filter(Boolean).join(' · ');
        $('puenteGuardar').disabled = points.length < 2;
    }
    map.on('click', (e) => {
        if (editando) return;      // al editar un puente existente no se agregan puntos al final por accidente (usa doble clic sobre la línea)
        recordar(); points.push([e.latlng.lat, e.latlng.lng]); drawDraft();
        status(points.length === 1 ? 'Inicio marcado. Ahora toca el final del puente (o puntos intermedios).' : points.length + ' puntos. Toca el final del puente y guarda.');
    });
    $('puenteDeshacer').onclick = () => { if (!undoS.length) return; redoS.push(copia()); points = undoS.pop(); selV = -1; drawDraft(); };
    $('puenteRehacer').onclick = () => { if (!redoS.length) return; undoS.push(copia()); points = redoS.pop(); selV = -1; drawDraft(); };
    $('puenteLimpiar').onclick = () => {
        recordar();
        if (editando) { points = original.map((p) => [p[0], p[1]]); selV = -1; drawDraft(); status('Geometría original restaurada (puedes deshacerlo).'); return; }
        points = []; drawDraft(); status('Toca donde empieza el puente.');
    };
    map.on('dblclick', (e) => {
        if (!editando) return;
        const p = [e.latlng.lat, e.latlng.lng], seg = G.segmentoCercano(points, p, proj);
        if (!seg || seg.distPx > 24 || points.length >= 300) return;
        recordar(); selV = G.insertar(points, seg.index, seg.punto); drawDraft();
    });
    $('puenteEliminarV').onclick = () => { if (!editando || selV < 0 || points.length <= 2) return; recordar(); points.splice(selV, 1); selV = Math.min(selV, points.length - 1); drawDraft(); };
    $('puenteAjustarExt').onclick = () => {
        recordar();
        const ef = efectivos();
        points[0] = ef[0].punto; points[points.length - 1] = ef[ef.length - 1].punto;
        drawDraft();
        status(ef[0].pegado && ef[ef.length - 1].pegado ? 'Los dos extremos quedaron sobre la red peatonal.' : 'Algún extremo no tiene una calle a menos de ' + SNAP_METROS + ' m: se dejó donde estaba.', ef[0].pegado && ef[ef.length - 1].pegado ? 'success' : 'error');
    };
    function salirDeEdicion() {
        editando = null; original = []; undoS = []; redoS = []; selV = -1; points = [];
        originalLayer.clearLayers(); editLayer.clearLayers();
        $('puenteEdicion').hidden = true; $('puenteNombre').value = ''; $('puenteNota').value = '';
        $('puenteGuardar').textContent = 'Guardar puente'; drawDraft();
    }
    $('puenteCancelar').onclick = () => { salirDeEdicion(); status('Edición cancelada: no se guardó ningún cambio.', 'info'); };
    function empezarEdicion(pu) {
        if ((points.length || editando) && !confirm('Hay un trazado sin guardar. ¿Descartarlo y editar este puente?')) return;
        salirDeEdicion();
        editando = { id: pu.id, version: pu.version, nombre: pu.nombre };
        points = pu.trazo.coordinates.map((c) => [c[1], c[0]]);
        original = points.map((p) => [p[0], p[1]]);
        $('puenteNombre').value = pu.nombre; $('puenteNota').value = pu.nota || '';
        $('puenteGuardar').textContent = 'Guardar cambios del puente';
        $('puenteEdicion').hidden = false;
        L.polyline(original, { color: '#6b7280', weight: 5, dashArray: '2 8', opacity: 0.95, interactive: false }).addTo(originalLayer);
        const b = L.latLngBounds(original); if (b.isValid()) map.fitBounds(b, { maxZoom: 19, padding: [60, 60] });
        drawDraft();
        status('Editando «' + pu.nombre + '». Nada se guarda hasta pulsar «Guardar cambios del puente».', 'info');
    }

    // ---- guardar
    $('puenteGuardar').onclick = async () => {
        if (!tokenOk()) return;
        if ($('puenteNombre').value.trim().length < 3) { status('Escribe el nombre del puente (mínimo 3 letras).', 'error'); return; }
        $('puenteGuardar').disabled = true;
        status('Guardando y actualizando la red de calles…');
        try {
            const coordenadas = efectivos().map((e) => [e.punto[1], e.punto[0]]);
            const res = editando
                ? await Spevb.api('/api/editor/pasos/' + editando.id, { method: 'PUT', headers: headers(true),
                    body: JSON.stringify({ nombre: $('puenteNombre').value.trim(), nota: $('puenteNota').value.trim(), coordenadas, version: editando.version }) })
                : await Spevb.api('/api/editor/pasos', {
                    method: 'POST', headers: headers(true),
                    body: JSON.stringify({ tipo: 'PUENTE', nombre: $('puenteNombre').value.trim(), nota: $('puenteNota').value.trim(), coordenadas })
                });
            if (editando) salirDeEdicion(); else { points = []; undoS = []; redoS = []; drawDraft(); $('puenteNombre').value = ''; $('puenteNota').value = ''; }
            await loadSaved(); loadRed();
            status(res.mensaje, res.conectados === 2 ? 'success' : 'error');
        } catch (error) { status(error.message, 'error'); $('puenteGuardar').disabled = points.length < 2; }     // en un conflicto (409) la edición local se conserva
    };

    // ---- lista (pública) y borrado (administrador)
    async function loadSaved() {
        try {
            // El editor lee la lista administrativa (con notas); sin permiso, cae a la lista pública (sin notas).
            let list;
            try { list = (await Spevb.api('/api/editor/pasos?t=' + Date.now(), { headers: headers(false) })).filter((x) => x.tipo === 'PUENTE'); }
            catch { list = await Spevb.api('/api/puentes?t=' + Date.now()); }
            savedLayer.clearLayers();
            $('puenteLista').replaceChildren();
            if (!list.length) { const p = document.createElement('p'); p.className = 'muted'; p.textContent = 'Todavía no hay puentes guardados.'; $('puenteLista').append(p); }
            list.forEach((pu) => {
                const latlngs = pu.trazo.coordinates.map((c) => [c[1], c[0]]);
                const grupo = L.layerGroup().addTo(savedLayer);
                L.polyline(latlngs, { color: '#fff', weight: 10, opacity: 0.9, interactive: false }).addTo(grupo);
                const line = L.polyline(latlngs, { color: AZUL, weight: 6, opacity: 0.95 }).bindTooltip(Spevb.text('Puente: ' + pu.nombre)).addTo(grupo);
                const row = document.createElement('div');
                row.className = 'paso-row';
                const info = document.createElement('div');
                const title = document.createElement('strong'); title.textContent = '≈ ' + pu.nombre;
                const small = document.createElement('small'); small.textContent = pu.metros + ' m' + (pu.nota ? ' · ' + pu.nota : '');
                info.append(title, document.createElement('br'), small);
                const see = document.createElement('button'); see.type = 'button'; see.className = 'secondary'; see.textContent = 'Ver';
                see.onclick = () => map.fitBounds(line.getBounds(), { maxZoom: 18, padding: [40, 40] });
                const del = document.createElement('button'); del.type = 'button'; del.className = 'secondary'; del.textContent = 'Borrar';
                del.onclick = async () => {
                    if (!tokenOk()) return;
                    if (!confirm('¿Borrar el puente «' + pu.nombre + '»? Las caminatas dejarán de usarlo.')) return;
                    try { await Spevb.api('/api/editor/pasos/' + pu.id, { method: 'DELETE', headers: headers(false) }); await loadSaved(); loadRed(); status('Puente borrado.', 'success'); }
                    catch (error) { status(error.message, 'error'); }
                };
                const ed = document.createElement('button'); ed.type = 'button'; ed.className = 'secondary'; ed.textContent = 'Editar';
                if (pu.version == null) { ed.disabled = true; ed.title = 'Introduce la clave de administración y recarga la lista para editar.'; }
                ed.onclick = () => empezarEdicion(pu);
                row.append(info, see, ed, del);
                $('puenteLista').append(row);
            });
        } catch (error) { status(error.message, 'error'); }
    }

    $('puenteToken').addEventListener('change', () => loadSaved());
    $('puenteVistaGeneral').onclick = () => map.setView(Spevb.center, 13);
    window.SpevbPuentes = { map, puntos: () => points, editando: () => editando };      // para las pruebas automáticas
    drawDraft();
    loadRed();
    loadSaved();
})();
