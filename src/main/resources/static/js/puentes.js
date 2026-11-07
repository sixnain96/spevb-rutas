'use strict';
/* Administración de puentes peatonales: se marcan en el mapa, los extremos se pegan a la calle más cercana y se guardan
   como pasos de tipo PUENTE (la red de calles los usa para cruzar). La lista es pública; guardar y borrar piden administrador. */
(() => {
    const $ = (id) => document.getElementById(id);
    const status = (message, type = 'info') => { $('puenteStatus').textContent = message; $('puenteStatus').className = 'status ' + type; };
    // El mapa se crea de forma asíncrona: el resto arranca cuando está listo (una sola vez) y, si falla, hay estado recuperable con «Reintentar».
    Spevb.conMapa('puentesMap', (map) => {

    const AZUL = '#1e5fbf', MIN_ZOOM_RED = 16, SNAP_METROS = 25;
    const redLayer = map.group().addTo(map), savedLayer = map.group().addTo(map), draftLayer = map.group().addTo(map);
    // TODO(capa): la capa no tiene «multilínea»: cada calle conocida es una polilínea (centenares por zona; con Leaflet van a canvas).
    const dibujarRed = (data, capa) => data.forEach((s) => map.polyline([[s[1], s[0]], [s[3], s[2]]], { color: '#6b7280', weight: 2, opacity: 0.8, interactive: false }).addTo(capa));
    const G = Spevb.EditorGeo;
    const editLayer = map.group().addTo(map), originalLayer = map.group().addTo(map);
    map.setDobleClicZoom(false);      // en edición, el doble clic inserta un vértice
    const proj = (p) => { const q = map.toPoint(p); return q ? [q.x, q.y] : [0, 0]; };      // toPoint es null solo antes del primer dibujo del mapa
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
        if (map.getZoom() < MIN_ZOOM_RED) { redLayer.clear(); segments = []; $('puenteSubtitulo').textContent = 'Acerca el mapa para ver las calles'; return; }
        const b = map.getBounds();
        if (!b) return;
        try {
            const q = new URLSearchParams({ sur: b.s, oeste: b.w, norte: b.n, este: b.e });
            const data = await Spevb.api('/api/caminata/red?' + q);
            if (seq !== redSeq) return;
            segments = data;
            redLayer.clear();
            dibujarRed(data, redLayer);
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
        draftLayer.clear();
        const ef = efectivos();
        if (ef.length) {
            map.polyline(ef.map((e) => e.punto), { color: '#fff', weight: 10, opacity: 0.9, interactive: false }).addTo(draftLayer);
            map.polyline(ef.map((e) => e.punto), { color: AZUL, weight: 6, dashArray: '10 7', interactive: false }).addTo(draftLayer);
        }
        ef.forEach((e, i) => {
            const extremo = i === 0 || i === ef.length - 1;
            map.dot(e.punto, { radius: extremo ? 8 : 5, color: extremo ? (e.pegado ? '#1a7f4b' : '#c0392b') : AZUL, weight: 3, fillColor: '#fff', fillOpacity: 1, interactive: false }).addTo(draftLayer);
        });
        editLayer.clear();
        if (editando) {
            points.forEach((p, i) => {
                const m = map.marker(p, { draggable: true, className: 'vertex-wrapper', size: i === selV ? [28, 28] : [18, 18], anchor: i === selV ? [14, 14] : [9, 9],
                    html: '<span class="vertex' + (i === selV ? ' selected' : '') + '"></span>', tooltip: Spevb.text('Vértice ' + (i + 1)) }).addTo(editLayer);
                m.on('click', () => { selV = i; drawDraft(); });
                m.on('dragstart', recordar);
                m.on('dragend', (ev) => { const q = ev.latlng || m.getPosition(); points[i] = [q[0], q[1]]; selV = i; drawDraft(); });
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
        recordar(); points.push([e.latlng[0], e.latlng[1]]); drawDraft();
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
        const p = [e.latlng[0], e.latlng[1]], seg = G.segmentoCercano(points, p, proj);
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
        originalLayer.clear(); editLayer.clear();
        $('puenteEdicion').hidden = true; $('puenteNombre').value = ''; $('puenteNota').value = '';
        $('puenteGuardar').textContent = 'Guardar puente'; drawDraft();
    }
    $('puenteCancelar').onclick = () => { salirDeEdicion(); status('Edición cancelada: no se guardó ningún cambio.', 'info'); };
    function empezarEdicion(pu) {
        if ((points.length || editando) && !confirm('Hay un trazado sin guardar. ¿Descartarlo y editar este puente?')) return;
        salirDeEdicion();
        editando = { id: pu.id, version: pu.version, nombre: pu.nombre };
        points = Spevb.Geo.desdeGeoJSON(pu.trazo.coordinates);
        original = points.map((p) => [p[0], p[1]]);
        $('puenteNombre').value = pu.nombre; $('puenteNota').value = pu.nota || '';
        $('puenteGuardar').textContent = 'Guardar cambios del puente';
        $('puenteEdicion').hidden = false;
        map.polyline(original, { color: '#6b7280', weight: 5, dashArray: '2 8', opacity: 0.95, interactive: false }).addTo(originalLayer);
        const b = Spevb.Geo.limites(original); if (b) map.fitBounds(b, { maxZoom: 19, padding: 60 });
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
            savedLayer.clear();
            $('puenteLista').replaceChildren();
            if (!list.length) { const p = document.createElement('p'); p.className = 'muted'; p.textContent = 'Todavía no hay puentes guardados.'; $('puenteLista').append(p); }
            list.forEach((pu) => {
                const latlngs = Spevb.Geo.desdeGeoJSON(pu.trazo.coordinates);
                const grupo = map.group().addTo(savedLayer);
                map.polyline(latlngs, { color: '#fff', weight: 10, opacity: 0.9, interactive: false }).addTo(grupo);
                const line = map.polyline(latlngs, { color: AZUL, weight: 6, opacity: 0.95, tooltip: Spevb.text('Puente: ' + pu.nombre) }).addTo(grupo);
                const row = document.createElement('div');
                row.className = 'paso-row';
                const info = document.createElement('div');
                const title = document.createElement('strong'); title.textContent = '≈ ' + pu.nombre;
                const small = document.createElement('small'); small.textContent = pu.metros + ' m' + (pu.nota ? ' · ' + pu.nota : '');
                info.append(title, document.createElement('br'), small);
                const see = document.createElement('button'); see.type = 'button'; see.className = 'secondary'; see.textContent = 'Ver';
                see.onclick = () => { const b = line.getBounds(); if (b) map.fitBounds(b, { maxZoom: 18, padding: 40 }); };
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
    }, { alFallar: (error) => status(error.message, 'error') });
})();
