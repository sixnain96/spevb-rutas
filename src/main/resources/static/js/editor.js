'use strict';
(() => {
    const $ = (id) => document.getElementById(id);
    const msg = (text, type = 'info') => {
        $('editorStatus').textContent = text;
        $('editorStatus').className = 'status ' + type;
    };
    let map;
    try { map = Spevb.createMap('editorMap'); }
    catch (error) { msg(error.message, 'error'); return; }

    const shapes = L.layerGroup().addTo(map), vertices = L.layerGroup().addTo(map), samples = L.layerGroup().addTo(map);
    const G = Spevb.EditorGeo;
    map.doubleClickZoom.disable();      // el doble clic sobre un segmento inserta un vértice (ver más abajo)
    const proj = (p) => { const q = map.latLngToContainerPoint(p); return [q.x, q.y]; };
    const MAX_MUESTRAS = 300, TOL_SIMPLIFICAR_PX = 7;
    let route = null, points = [], line, selectedIndex = -1, selectedSegment = null, drawing = false, dirty = false, busy = false;
    let undo = [], redo = [], generation = 0, loading = null, draftTimer;
    let anchors = new Set();         // vértices bloqueados: el ajuste automático no los mueve (índices; se desplazan al insertar/eliminar)
    const clone = (value) => structuredClone(value);
    const isNew = () => route && route.id == null;
    const key = () => route ? (isNew() ? 'spevb:draft:new' : 'spevb:draft:' + route.id) : 'spevb:draft:none';

    function geometry() {
        return { type: 'LineString', coordinates: points.map((p) => [Number(p[1].toFixed(7)), Number(p[0].toFixed(7))]) };
    }
    function fields() {
        return {
            codigo: $('editorCode').value.trim().toUpperCase(),
            nombre: $('editorName').value,
            origen: $('editorOrigin').value,
            destino: $('editorDestination').value,
            sentido: $('editorDirection').value,
            descripcion: $('editorDescription').value,
            color: $('editorColor').value,
            fuente: $('editorSource').value,
            notaValidacion: $('validationNote').value,
            activa: $('editorActive').checked,
        };
    }
    function applyFields(value) {
        $('editorCode').value = value.codigo || '';
        $('editorName').value = value.nombre || '';
        $('editorPopular').value = Spevb.rutaPopular(value.codigo);
        $('editorOrigin').value = value.origen || '';
        $('editorDestination').value = value.destino || '';
        $('editorDirection').value = value.sentido || 'IDA';
        $('editorDescription').value = value.descripcion || '';
        $('editorColor').value = /^#[0-9a-f]{6}$/i.test(value.color || '') ? value.color : '#176650';
        $('editorSource').value = value.fuente || '';
        $('validationNote').value = value.notaValidacion || '';
        $('editorValidated').checked = !!value.validada;
        $('editorActive').checked = value.activa !== false;
    }
    function setDirty(value) {
        dirty = value;
        $('dirtyBadge').textContent = dirty ? 'Cambios pendientes' : 'Sin cambios';
        $('dirtyBadge').className = 'pill ' + (dirty ? 'pending' : 'ok');
    }
    const estado = () => ({ p: clone(points), a: [...anchors] });
    const restaurar = (e) => { points = e.p; anchors = new Set(e.a); };
    function remember() {
        undo.push(estado());
        if (undo.length > 50) undo.shift();
        redo = [];
    }
    function changed(geometryChanged = true) {
        if (geometryChanged) window.dispatchEvent(new CustomEvent('spevb-editor-geometria'));
        if (geometryChanged) {
            $('editorValidated').checked = false;
            $('validationNote').value = '';
        }
        setDirty(true);
        redraw();
        clearTimeout(draftTimer);
        draftTimer = setTimeout(storeDraft, 300);
    }
    function storeDraft() {
        if (!route || !dirty) return;
        try {
            localStorage.setItem(key(), JSON.stringify({
                routeId: route.id, version: route.version ?? 0, points, anclas: [...anchors], ...fields(),
                validada: $('editorValidated').checked, savedAt: new Date().toISOString(),
            }));
        } catch { msg('El navegador no pudo guardar el borrador local. Exporta el GeoJSON para conservarlo.', 'error'); }
    }
    function getDraft() {
        try { return JSON.parse(localStorage.getItem(key())); } catch { return null; }
    }
    function discardDraft() {
        try { localStorage.removeItem(key()); } catch { /* storage disabled */ }
        $('draftBanner').classList.add('hidden');
    }
    function fit() {
        if (line?.getBounds().isValid()) map.fitBounds(line.getBounds(), { padding: [40, 40], maxZoom: 18 });
        else map.setView(Spevb.center, 13);
    }
    function redraw() {
        shapes.clearLayers(); vertices.clearLayers(); samples.clearLayers(); line = null;
        const color = /^#[0-9a-f]{6}$/i.test($('editorColor').value) ? $('editorColor').value : '#176650';
        if (points.length) {
            L.polyline(points, { color: '#fff', weight: 10 }).addTo(shapes);
            line = L.polyline(points, { color, weight: 6 }).addTo(shapes);
            let meters = 0;
            for (let i = 1; i < points.length; i++) meters += L.latLng(points[i - 1]).distanceTo(points[i]);
            $('editorDistance').textContent = (meters / 1000).toLocaleString('es-CO', { maximumFractionDigits: 2 }) + ' km';
            // Hasta 500 puntos: un marcador por vértice. Con más (hasta 10.000) NUNCA se crean miles de marcadores: extremos, vértice
            // elegido y vecinos como marcadores arrastrables, y el resto como puntos simplificados según el zoom (canvas, sin eventos).
            const n = points.length, grande = n > 500;
            const marcados = new Set(grande ? [0, n - 1] : points.map((_, i) => i));
            anchors.forEach((i) => { if (grande && i < n) marcados.add(i); });
            if (grande && selectedIndex >= 0) G.vecinos(selectedIndex, n, 5).forEach((i) => marcados.add(i));
            if (grande) {
                G.limitar(G.simplificar(points, proj, TOL_SIMPLIFICAR_PX, marcados), MAX_MUESTRAS).forEach((i) => {
                    if (!marcados.has(i)) L.circleMarker(points[i], { radius: 3, weight: 1, color, fillColor: '#fff', fillOpacity: 1, interactive: false, className: 'vertex-sample' }).addTo(samples);
                });
            }
            [...marcados].sort((x, y) => x - y).forEach((i) => {
                const sel = i === selectedIndex, vecino = grande && !sel && i !== 0 && i !== n - 1;
                const icon = i === 0 ? Spevb.endpoint('start') : i === n - 1 ? Spevb.endpoint('end') : L.divIcon({
                    className: 'vertex-wrapper', html: '<span class="vertex' + (sel ? ' selected' : vecino ? ' vecino' : '') + (anchors.has(i) ? ' anclado' : '') + '"></span>',
                    iconSize: sel ? [32, 32] : [20, 20], iconAnchor: sel ? [16, 16] : [10, 10],
                });
                const marker = L.marker(points[i], { draggable: !busy, icon, bubblingMouseEvents: false, zIndexOffset: sel ? 1000 : 0 }).addTo(vertices).bindTooltip(Spevb.text('Punto ' + (i + 1)));
                marker.on('click', () => diferir(() => selectIndex(i, false)));
                marker.on('dblclick', dobleClic);
                marker.on('dragstart', remember);
                marker.on('dragend', (e) => {
                    if (busy) return;
                    const p = e.target.getLatLng(); points[i] = [p.lat, p.lng]; selectedIndex = i; selectedSegment = null; changed();
                });
            });
            if (selectedSegment) L.circleMarker(selectedSegment.punto, { radius: 8, color: '#553b14', fillColor: '#efb64c', fillOpacity: 1, weight: 3, interactive: false }).addTo(vertices);
        } else $('editorDistance').textContent = '0 km';
        $('pointCount').textContent = points.length + (points.length === 1 ? ' punto' : ' puntos');
        $('vertexIndex').max = Math.max(points.length, 1);
        $('vertexIndex').value = selectedIndex >= 0 && selectedIndex < points.length ? selectedIndex + 1 : '';
        updateInfo();
        $('insertAtSegment').disabled = busy || !selectedSegment;
        $('undoPoint').disabled = busy || !undo.length;
        $('redoPoint').disabled = busy || !redo.length;
        $('versionLabel').textContent = route ? (isNew() ? 'Nueva ruta' : 'Versión ' + route.version) : 'Sin ruta';
        $('drawMode').textContent = drawing ? 'Dibujo activo · Haz clic en el mapa para agregar puntos' : (route ? 'Edición · Arrastra los puntos para ajustar el trazado' : 'Selecciona una ruta o crea una nueva');
        $('startDraw').textContent = drawing ? 'Pausar dibujo' : 'Agregar puntos';
        $('startDraw').setAttribute('aria-pressed', String(drawing));
        $('reloadRoute').disabled = busy || !route || isNew();
        $('loadHistory').disabled = busy || !route || isNew();
    }
    /** Tras insertar (delta 1) o eliminar (delta -1) en la posición `desde`, los índices anclados a partir de ahí se desplazan. */
    function desplazarAnclas(desde, delta) { anchors = new Set([...anchors].map((i) => (i >= desde ? i + delta : i))); }
    const fmtM = (m) => (m == null ? '—' : m >= 1000 ? (m / 1000).toLocaleString('es-CO', { maximumFractionDigits: 2 }) + ' km' : Math.round(m).toLocaleString('es-CO') + ' m');
    let vertexRoadText = '';
    function updateInfo() {
        const box = $('vertexInfo');
        if (!points.length) { box.textContent = route ? 'Aún no hay vértices.' : 'Selecciona una ruta.'; return; }
        if (selectedSegment) {
            box.textContent = 'Segmento ' + (selectedSegment.index + 1) + '–' + (selectedSegment.index + 2) + ' elegido. Usa «Insertar en segmento elegido» o doble clic para agregar un vértice ahí.';
            return;
        }
        const d = G.infoVertice(points, selectedIndex);
        if (!d) { box.textContent = 'Haz clic sobre la línea para elegir un vértice.'; return; }
        box.replaceChildren();
        const b = (t) => { const e = document.createElement('b'); e.textContent = t; return e; };
        box.append('Vértice ', b(d.numero.toLocaleString('es-CO')), ' de ', b(d.total.toLocaleString('es-CO')), document.createElement('br'),
            d.lat.toFixed(6) + ', ' + d.lng.toFixed(6), document.createElement('br'),
            'Anterior: ' + fmtM(d.anterior) + ' · Siguiente: ' + fmtM(d.siguiente));
        const via = document.createElement('div'); via.id = 'vertexRoad'; via.textContent = vertexRoadText; box.append(via);
    }
    function selectIndex(i, center = true) {
        if (i < 0 || i >= points.length) return;
        selectedIndex = i; selectedSegment = null; vertexRoadText = '';
        if (center) map.setView(points[i], Math.max(map.getZoom(), 16), { animate: false });
        redraw();
        window.dispatchEvent(new CustomEvent('spevb-editor-vertice', { detail: { index: i, punto: points[i] } }));
    }
    function navegar(accion) { if (route && !busy) selectIndex(G.mover(selectedIndex, points.length, accion)); }
    // Un clic simple se aplica con una pequeña espera para poder distinguirlo del doble clic (que inserta un vértice): así el marcador
    // del vértice recién elegido no se interpone entre los dos clics.
    let clickTimer = null;
    const diferir = (fn) => { clearTimeout(clickTimer); clickTimer = setTimeout(fn, 260); };
    function tolerancia(e) { return (e && e.originalEvent && e.originalEvent.pointerType === 'touch') || navigator.maxTouchPoints > 0 ? 30 : 22; }
    function insertarEn(segmento) {
        if (busy || !route || !segmento) return;
        if (points.length >= 10000) { msg('El límite es de 10.000 puntos.', 'error'); return; }
        remember();
        const nuevo = G.insertar(points, segmento.index, segmento.punto);
        desplazarAnclas(nuevo, 1);
        selectedSegment = null; selectedIndex = nuevo; changed();
        msg('Vértice insertado (' + (nuevo + 1) + '). Puedes arrastrarlo; los cambios solo se guardan al pulsar Guardar.', 'success');
    }

    function lock(value) {
        busy = value;
        $('editFields').disabled = value || !route;
        $('editorRoute').disabled = value;
        $('newRoute').disabled = value;
        if (route) $('editorCode').disabled = value;
        redraw();
    }
    async function refreshRoutes(selectedId = route?.id, announce = false) {
        const routes = await Spevb.api('/api/rutas');
        $('editorRoute').replaceChildren(new Option('Selecciona una ruta', ''));
        routes.forEach((r) => $('editorRoute').add(new Option(r.codigo + ' · ' + r.nombre, r.id)));
        $('editorRoute').disabled = busy;
        if (selectedId) $('editorRoute').value = String(selectedId);
        if (announce) msg(routes.length ? 'Selecciona una ruta o crea una nueva.' : 'Todavía no hay rutas; crea la primera.', 'success');
        return routes;
    }
    async function loadRoutes() {
        $('retryEditor').classList.add('hidden');
        try { await refreshRoutes(null, true); }
        catch (error) { msg(error.message, 'error'); $('retryEditor').classList.remove('hidden'); }
    }
    function startNew() {
        clearTimeout(draftTimer);
        if (dirty) storeDraft();
        route = { id: null, codigo: '', nombre: '', origen: '', destino: '', sentido: 'IDA', descripcion: '', color: '#176650', fuente: 'Recorrido registrado manualmente por el equipo SPEVB', validada: false, version: 0 };
        points = []; anchors = new Set(); selectedIndex = -1; undo = []; redo = []; drawing = true; setDirty(false);
        applyFields(route); $('editorRoute').value = ''; $('revisionList').replaceChildren();
        $('editorMapTitle').textContent = 'Nueva ruta · Villavicencio';
        $('draftBanner').classList.toggle('hidden', !getDraft());
        lock(false); redraw(); fit(); window.dispatchEvent(new CustomEvent('spevb-editor-ruta')); msg('Nueva ruta lista. Completa los datos y dibuja al menos dos puntos.', 'success');
    }
    async function loadRoute(id) {
        clearTimeout(draftTimer); if (dirty) storeDraft(); loading?.abort();
        const controller = new AbortController(); loading = controller; const current = ++generation;
        lock(true); drawing = false; msg('Cargando versión actual…');
        try {
            if (!id) { window.dispatchEvent(new CustomEvent('spevb-editor-ruta')); route = null; points = []; setDirty(false); $('editorMapTitle').textContent = 'Villavicencio'; $('revisionList').replaceChildren(); $('draftBanner').classList.add('hidden'); redraw(); return; }
            const loaded = await Spevb.api('/api/rutas/' + id, { signal: controller.signal });
            if (current !== generation) return;
            Spevb.geometry(loaded.recorrido); route = loaded; points = loaded.recorrido.coordinates.map((c) => [c[1], c[0]]);
            applyFields(loaded); selectedIndex = -1; anchors = new Set(); undo = []; redo = []; setDirty(false); $('revisionList').replaceChildren();
            $('editorMapTitle').textContent = loaded.codigo + ' · ' + loaded.nombre;
            $('draftBanner').classList.toggle('hidden', !getDraft()); redraw(); fit(); window.dispatchEvent(new CustomEvent('spevb-editor-ruta'));
            msg(loaded.advertencias.length ? loaded.advertencias.join(' ') : 'Ruta cargada. Los cambios solo se guardan al pulsar Guardar.', loaded.advertencias.length ? 'info' : 'success');
        } catch (error) { if (error.name !== 'AbortError' && current === generation) msg(error.message, 'error'); }
        finally { if (current === generation) lock(false); }
    }
    function mayLeave() { return !dirty || confirm('Hay cambios sin guardar. Se conservará un borrador local. ¿Quieres continuar?'); }

    $('editorRoute').addEventListener('change', () => {
        const id = $('editorRoute').value;
        if (!mayLeave()) { $('editorRoute').value = route?.id || ''; return; }
        loadRoute(id);
    });
    $('newRoute').onclick = () => { if (mayLeave()) startNew(); };
    $('retryEditor').onclick = loadRoutes;
    $('startDraw').onclick = () => { if (!route || busy) return; drawing = !drawing; redraw(); window.dispatchEvent(new CustomEvent('spevb-editor-dibujo', { detail: { activo: drawing } })); };
    map.on('click', (e) => {
        if (busy || !route) return;
        if (!drawing) {      // modo edición: elegir el vértice (o el segmento) más cercano. Solo al hacer clic, nunca al mover el ratón.
            const ll = [e.latlng.lat, e.latlng.lng], tol = tolerancia(e);
            diferir(() => {
                const sel = G.seleccionar(points, ll, proj, tol);
                if (!sel) { if (selectedIndex >= 0 || selectedSegment) { selectedIndex = -1; selectedSegment = null; redraw(); } return; }
                if (sel.tipo === 'vertice') selectIndex(sel.index, false);
                else { selectedIndex = -1; selectedSegment = { index: sel.index, punto: sel.punto }; redraw(); }
            });
            return;
        }
        if (points.length >= 10000) { msg('El límite es de 10.000 puntos.', 'error'); return; }
        const p = [e.latlng.lat, e.latlng.lng], previous = points.at(-1);
        if (previous && previous[0].toFixed(7) === p[0].toFixed(7) && previous[1].toFixed(7) === p[1].toFixed(7)) return;
        remember(); points.push(p); selectedIndex = points.length - 1; changed();
    });
    // Doble clic sobre un segmento (también si cae sobre el marcador de un vértice vecino, que lo taparía): inserta un vértice en el punto de
    // la línea más cercano al clic, salvo que el clic esté prácticamente encima de un vértice existente (< 6 px).
    function dobleClic(e) {
        clearTimeout(clickTimer);
        if (drawing || busy || !route) return;
        const ll = e.latlng && e.originalEvent && !e.target.getLatLng ? e.latlng : map.mouseEventToLatLng(e.originalEvent);
        const p = [ll.lat, ll.lng], v = G.verticeCercano(points, p, proj);
        if (v && v.distPx < 6) return;
        const seg = G.segmentoCercano(points, p, proj);
        if (seg && seg.distPx <= tolerancia(e)) insertarEn({ index: seg.index, punto: seg.punto });
    }
    map.on('dblclick', dobleClic);
    map.on('zoomend', () => { if (points.length > 500) redraw(); });
    $('insertAtSegment').onclick = () => insertarEn(selectedSegment);
    [['vxFirst', 'primero'], ['vxBack10', 'menos10'], ['vxPrev', 'anterior'], ['vxNext', 'siguiente'], ['vxFwd10', 'mas10'], ['vxLast', 'ultimo']]
        .forEach(([id, accion]) => { $(id).onclick = () => navegar(accion); });
    function undoAction() { if (busy || !undo.length) return; redo.push(estado()); restaurar(undo.pop()); selectedIndex = -1; changed(); }
    function redoAction() { if (busy || !redo.length) return; undo.push(estado()); restaurar(redo.pop()); selectedIndex = -1; changed(); }
    $('undoPoint').onclick = undoAction; $('redoPoint').onclick = redoAction;
    document.addEventListener('keydown', (e) => {
        if (e.target.matches('input,textarea,select') || !route || busy) return;
        if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === 'z') { e.preventDefault(); e.shiftKey ? redoAction() : undoAction(); return; }
        if (e.ctrlKey || e.metaKey || e.altKey) return;
        const k = e.key, acciones = { '[': 'anterior', ']': 'siguiente', '{': 'menos10', '}': 'mas10', Home: 'primero', End: 'ultimo' };
        if (acciones[k]) { e.preventDefault(); navegar(acciones[k]); }
        else if (k === 'Delete') { e.preventDefault(); $('deletePoint').click(); }
        else if (k === 'i' || k === 'I') { e.preventDefault(); selectedSegment ? insertarEn(selectedSegment) : $('insertPoint').click(); }
    });
    $('vertexIndex').addEventListener('input', () => {
        const i = Number($('vertexIndex').value) - 1;
        if (Number.isInteger(i) && i >= 0 && i < points.length) selectIndex(i); else { selectedIndex = -1; redraw(); }
    });
    $('deletePoint').onclick = () => {
        if (selectedIndex < 0) { msg('Selecciona un punto para eliminarlo.'); return; }
        remember(); anchors.delete(selectedIndex); desplazarAnclas(selectedIndex + 1, -1); points.splice(selectedIndex, 1); selectedIndex = Math.min(selectedIndex, points.length - 1); changed();
    };
    $('insertPoint').onclick = () => {
        if (selectedIndex < 0 || selectedIndex >= points.length - 1) { msg('Selecciona un punto que tenga un siguiente vértice.'); return; }
        if (points.length >= 10000) { msg('El límite es de 10.000 puntos.', 'error'); return; }
        const a = points[selectedIndex], b = points[selectedIndex + 1]; remember();
        desplazarAnclas(selectedIndex + 1, 1); points.splice(selectedIndex + 1, 0, [(a[0] + b[0]) / 2, (a[1] + b[1]) / 2]); selectedIndex++; changed();
    };
    $('reverseDraw').onclick = () => {
        if (busy) return;
        if (points.length < 2) { msg('Necesitas al menos dos puntos para invertir el sentido.'); return; }
        remember(); points.reverse(); anchors = new Set([...anchors].map((i) => points.length - 1 - i));
        if (selectedIndex >= 0) selectedIndex = points.length - 1 - selectedIndex;
        const origin = $('editorOrigin').value; $('editorOrigin').value = $('editorDestination').value; $('editorDestination').value = origin;
        changed();
        msg('Sentido invertido y origen/destino intercambiados. Revisa los datos y guarda el recorrido.', 'success');
    };
    $('clearDraw').onclick = () => {
        if (!points.length || !confirm('¿Limpiar el trazado? Puedes recuperarlo con Deshacer.')) return;
        remember(); points = []; anchors = new Set(); selectedIndex = -1; changed();
    };
    $('fitEditor').onclick = fit;
    ['editorCode','editorName','editorOrigin','editorDestination','editorDescription','editorSource','validationNote'].forEach((id) => $(id).addEventListener('input', () => changed(false)));
    ['editorDirection','editorColor'].forEach((id) => $(id).addEventListener('change', () => changed(false)));
    $('editorValidated').addEventListener('change', () => changed(false));
    $('editorActive').addEventListener('change', () => changed(false));

    $('importFile').addEventListener('change', async (e) => {
        const file = e.target.files[0]; if (!file || !route || busy) return;
        const current = generation, routeId = route.id;
        try {
            if (file.size > 2_000_000) throw new Error('El archivo supera 2 MB.');
            const g = Spevb.geometry(JSON.parse(await file.text()));
            if (busy || current !== generation || route.id !== routeId) return;
            if (points.length && !confirm('¿Reemplazar los puntos con el archivo importado? Puedes deshacerlo.')) return;
            remember(); points = g.coordinates.map((c) => [c[1], c[0]]); selectedIndex = -1; changed(); fit(); msg('Importados ' + points.length + ' puntos.', 'success');
        } catch (error) { msg(error.message, 'error'); }
        finally { e.target.value = ''; }
    });
    $('exportDraw').onclick = () => {
        if (!route) return;
        try {
            const g = Spevb.geometry(geometry());
            Spevb.download({ type: 'Feature', properties: { ...fields(), validada: false, version: route.version ?? 0, borrador: dirty }, geometry: g }, (fields().codigo || 'ruta') + '-borrador.geojson');
        } catch (error) { msg(error.message, 'error'); }
    };
    $('recoverDraft').onclick = () => {
        const draft = getDraft(); if (!draft || !route) return;
        try {
            if (!Array.isArray(draft.points) || draft.points.length > 10000 || !draft.points.every((p) => Array.isArray(p) && p.length === 2 && p.every(Number.isFinite))) throw new Error('El borrador local contiene puntos inválidos.');
            if (!isNew() && draft.version !== route.version) { msg('El borrador pertenece a otra versión. Expórtalo antes de sobrescribir la ruta actual.', 'error'); return; }
            remember(); points = clone(draft.points); anchors = new Set(Array.isArray(draft.anclas) ? draft.anclas.filter((i) => Number.isInteger(i) && i >= 0 && i < points.length) : []); applyFields({ ...route, ...draft, validada: false }); selectedIndex = -1; changed(); $('draftBanner').classList.add('hidden'); fit(); msg('Borrador recuperado. Revisa el trazado antes de guardar.', 'success');
        } catch (error) { msg(error.message, 'error'); }
    };
    $('discardDraft').onclick = discardDraft;
    $('reloadRoute').onclick = () => { if (route?.id && mayLeave()) loadRoute(route.id); };

    function validateBeforeSave(data, g) {
        if (!/^[A-Za-z0-9_-]{2,20}$/.test(data.codigo)) throw new Error('El código debe tener entre 2 y 20 caracteres: letras, números, guion o guion bajo.');
        if (!data.nombre.trim()) throw new Error('Escribe el nombre de la ruta.');
        if (!data.origen.trim() || !data.destino.trim()) throw new Error('Completa inicio y destino.');
        Spevb.geometry(g);
        if ($('editorValidated').checked && (!data.fuente.trim() || !data.notaValidacion.trim())) throw new Error('Para validar, registra fuente y evidencia de revisión.');
        const token = $('editorToken').value;
        if (token.length < 32) throw new Error('Introduce la clave de administración de al menos 32 caracteres.');
        return token;
    }

    $('saveDraw').onclick = async () => {
        if (busy || !route) return;
        let locked = false;
        try {
            const g = Spevb.geometry(geometry()), data = fields(), token = validateBeforeSave(data, g);
            const cambiaCodigo = !isNew() && data.codigo !== String(route.codigo || '').toUpperCase();
            if (cambiaCodigo && !confirm('¿Cambiar el código de la ruta?\n\nCódigo anterior: ' + route.codigo + '\nCódigo nuevo: ' + data.codigo +
                '\n\nEl código anterior seguirá resolviendo hacia esta misma ruta (favoritos e historiales no se pierden). Se archiva la versión actual.')) return;
            clearTimeout(draftTimer); storeDraft(); lock(true); locked = true; drawing = false;
            msg(isNew() ? 'Creando ruta en PostgreSQL…' : 'Guardando nueva versión del recorrido…');
            const body = { ...data, recorrido: g, validada: $('editorValidated').checked };
            const saved = isNew()
                ? await Spevb.api('/api/editor/rutas', { method: 'POST', headers: { 'Content-Type': 'application/json', 'X-Editor-Token': token }, body: JSON.stringify(body) })
                : await Spevb.api('/api/editor/rutas/' + route.id + '/recorrido', { method: 'PUT', headers: { 'Content-Type': 'application/json', 'X-Editor-Token': token }, body: JSON.stringify({ ...body, version: route.version, confirmarCambioCodigo: cambiaCodigo }) });
            // Nombre popular (opcional): se guarda aparte; solo si cambió, para no tocar el servidor sin necesidad.
            const popular = $('editorPopular').value.trim();
            if (popular !== Spevb.rutaPopular(saved.codigo)) {
                await Spevb.api('/api/editor/rutas/' + saved.id + '/nombre-popular', { method: 'PUT', headers: { 'Content-Type': 'application/json', 'X-Editor-Token': token }, body: JSON.stringify({ nombrePopular: popular }) });
                Spevb.fijarNombreRuta(saved.codigo, popular);
            }
            route = saved; points = saved.recorrido.coordinates.map((c) => [c[1], c[0]]); applyFields(saved);
            setDirty(false); discardDraft(); undo = []; redo = []; selectedIndex = -1;
            await refreshRoutes(saved.id, false); $('editorMapTitle').textContent = saved.codigo + ' · ' + saved.nombre; redraw(); window.dispatchEvent(new CustomEvent('spevb-editor-ruta'));
            msg('Guardado en PostgreSQL. ' + saved.codigo + ' quedó en versión ' + saved.version + ' y permanecerá disponible al reiniciar el proyecto.', 'success');
        } catch (error) { msg(error.message, 'error'); }
        finally { if (locked) lock(false); }
    };

    $('loadHistory').onclick = async () => {
        if (!route?.id || busy) return;
        const current = generation; lock(true); msg('Consultando historial…');
        try {
            const revisions = await Spevb.api('/api/editor/rutas/' + route.id + '/historial', { headers: { 'X-Editor-Token': $('editorToken').value } });
            if (current !== generation) return;
            $('revisionList').replaceChildren();
            revisions.forEach((revision) => {
                const button = document.createElement('button'); button.type = 'button'; button.className = 'secondary';
                button.textContent = 'Recuperar v' + revision.version + ' · ' + new Date(revision.archivadoEn).toLocaleString('es-CO');
                button.onclick = () => {
                    if (busy || !mayLeave()) return;
                    try {
                        const g = Spevb.geometry(revision.datos.recorrido); remember(); points = g.coordinates.map((p) => [p[1], p[0]]);
                        applyFields({ ...route, ...revision.datos, codigo: route.codigo, validada: false }); selectedIndex = -1; changed(); fit();
                        msg('Versión histórica recuperada como borrador. Guardar creará una nueva versión.', 'success');
                    } catch (error) { msg(error.message, 'error'); }
                };
                $('revisionList').append(button);
            });
            msg(revisions.length ? 'Elige una revisión para recuperarla como borrador.' : 'Todavía no hay versiones anteriores.');
        } catch (error) { msg(error.message, 'error'); }
        finally { if (current === generation) lock(false); }
    };

    window.addEventListener('beforeunload', (e) => {
        if (dirty) { clearTimeout(draftTimer); storeDraft(); e.preventDefault(); e.returnValue = ''; }
    });
    // Interfaz para el editor de paraderos (paraderos-editor.js): lee la ruta cargada y pausa el dibujo del trazado.
    window.SpevbEditor = {
        map,
        route: () => route,
        points: () => points,
        isNew,
        busy: () => busy,
        drawing: () => drawing,
        setDrawing(v) { if (drawing !== !!v) { drawing = !!v; redraw(); } },
        // --- usado por editor-ajuste.js (propuestas de ajuste a vías): siempre cambios PENDIENTES, con Deshacer, y «revisada» se desmarca
        selected: () => selectedIndex,
        select: (i) => selectIndex(i),
        setVertexInfo(t) { vertexRoadText = t || ''; updateInfo(); },
        aplicarPunto(i, p) { if (busy || i < 0 || i >= points.length) return; remember(); points[i] = [p[0], p[1]]; selectedIndex = i; selectedSegment = null; changed(); },
        aplicarPuntos(nuevos) { if (busy) return; remember(); points = nuevos.map((p) => [p[0], p[1]]); anchors = new Set(); selectedIndex = -1; selectedSegment = null; changed(); fit(); },
        anclas: () => [...anchors].sort((x, y) => x - y),
        alternarAncla(i) { if (i < 0 || i >= points.length) return false; const on = !anchors.has(i); on ? anchors.add(i) : anchors.delete(i); redraw(); storeDraft(); return on; },
    };
    loadRoutes();
})();
