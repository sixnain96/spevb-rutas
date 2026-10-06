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

    const shapes = L.layerGroup().addTo(map), vertices = L.layerGroup().addTo(map);
    let route = null, points = [], line, selectedIndex = -1, drawing = false, dirty = false, busy = false;
    let undo = [], redo = [], generation = 0, loading = null, draftTimer;
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
        };
    }
    function applyFields(value) {
        $('editorCode').value = value.codigo || '';
        $('editorName').value = value.nombre || '';
        $('editorOrigin').value = value.origen || '';
        $('editorDestination').value = value.destino || '';
        $('editorDirection').value = value.sentido || 'IDA';
        $('editorDescription').value = value.descripcion || '';
        $('editorColor').value = /^#[0-9a-f]{6}$/i.test(value.color || '') ? value.color : '#176650';
        $('editorSource').value = value.fuente || '';
        $('validationNote').value = value.notaValidacion || '';
        $('editorValidated').checked = !!value.validada;
        $('editorCode').disabled = !isNew();
    }
    function setDirty(value) {
        dirty = value;
        $('dirtyBadge').textContent = dirty ? 'Cambios pendientes' : 'Sin cambios';
        $('dirtyBadge').className = 'pill ' + (dirty ? 'pending' : 'ok');
    }
    function remember() {
        undo.push(clone(points));
        if (undo.length > 50) undo.shift();
        redo = [];
    }
    function changed(geometryChanged = true) {
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
                routeId: route.id, version: route.version ?? 0, points, ...fields(),
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
        shapes.clearLayers(); vertices.clearLayers(); line = null;
        const color = /^#[0-9a-f]{6}$/i.test($('editorColor').value) ? $('editorColor').value : '#176650';
        if (points.length) {
            L.polyline(points, { color: '#fff', weight: 10 }).addTo(shapes);
            line = L.polyline(points, { color, weight: 6 }).addTo(shapes);
            let meters = 0;
            for (let i = 1; i < points.length; i++) meters += L.latLng(points[i - 1]).distanceTo(points[i]);
            $('editorDistance').textContent = (meters / 1000).toLocaleString('es-CO', { maximumFractionDigits: 2 }) + ' km';
            const indices = points.length <= 500 ? points.map((_, i) => i) : [...new Set([0, points.length - 1, selectedIndex].filter((i) => i >= 0 && i < points.length))];
            indices.forEach((i) => {
                const icon = i === 0 ? Spevb.endpoint('start') : i === points.length - 1 ? Spevb.endpoint('end') : L.divIcon({
                    className: 'vertex-wrapper', html: '<span class="vertex' + (i === selectedIndex ? ' selected' : '') + '"></span>', iconSize: [20, 20], iconAnchor: [10, 10],
                });
                const marker = L.marker(points[i], { draggable: !busy, icon, bubblingMouseEvents: false }).addTo(vertices).bindTooltip(Spevb.text('Punto ' + (i + 1)));
                marker.on('click', () => { selectedIndex = i; redraw(); });
                marker.on('dragstart', remember);
                marker.on('dragend', (e) => {
                    if (busy) return;
                    const p = e.target.getLatLng(); points[i] = [p.lat, p.lng]; selectedIndex = i; changed();
                });
            });
        } else $('editorDistance').textContent = '0 km';
        $('pointCount').textContent = points.length + (points.length === 1 ? ' punto' : ' puntos');
        $('vertexIndex').max = Math.max(points.length, 1);
        $('vertexIndex').value = selectedIndex >= 0 && selectedIndex < points.length ? selectedIndex + 1 : '';
        $('undoPoint').disabled = busy || !undo.length;
        $('redoPoint').disabled = busy || !redo.length;
        $('versionLabel').textContent = route ? (isNew() ? 'Nueva ruta' : 'Versión ' + route.version) : 'Sin ruta';
        $('drawMode').textContent = drawing ? 'Dibujo activo · Haz clic en el mapa para agregar puntos' : (route ? 'Edición · Arrastra los puntos para ajustar el trazado' : 'Selecciona una ruta o crea una nueva');
        $('startDraw').textContent = drawing ? 'Pausar dibujo' : 'Agregar puntos';
        $('startDraw').setAttribute('aria-pressed', String(drawing));
        $('reloadRoute').disabled = busy || !route || isNew();
        $('loadHistory').disabled = busy || !route || isNew();
    }
    function lock(value) {
        busy = value;
        $('editFields').disabled = value || !route;
        $('editorRoute').disabled = value;
        $('newRoute').disabled = value;
        if (route) $('editorCode').disabled = value || !isNew();
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
        points = []; selectedIndex = -1; undo = []; redo = []; drawing = true; setDirty(false);
        applyFields(route); $('editorRoute').value = ''; $('revisionList').replaceChildren();
        $('editorMapTitle').textContent = 'Nueva ruta · Villavicencio';
        $('draftBanner').classList.toggle('hidden', !getDraft());
        lock(false); redraw(); fit(); msg('Nueva ruta lista. Completa los datos y dibuja al menos dos puntos.', 'success');
    }
    async function loadRoute(id) {
        clearTimeout(draftTimer); if (dirty) storeDraft(); loading?.abort();
        const controller = new AbortController(); loading = controller; const current = ++generation;
        lock(true); drawing = false; msg('Cargando versión actual…');
        try {
            if (!id) { route = null; points = []; setDirty(false); $('editorMapTitle').textContent = 'Villavicencio'; $('revisionList').replaceChildren(); $('draftBanner').classList.add('hidden'); redraw(); return; }
            const loaded = await Spevb.api('/api/rutas/' + id, { signal: controller.signal });
            if (current !== generation) return;
            Spevb.geometry(loaded.recorrido); route = loaded; points = loaded.recorrido.coordinates.map((c) => [c[1], c[0]]);
            applyFields(loaded); selectedIndex = -1; undo = []; redo = []; setDirty(false); $('revisionList').replaceChildren();
            $('editorMapTitle').textContent = loaded.codigo + ' · ' + loaded.nombre;
            $('draftBanner').classList.toggle('hidden', !getDraft()); redraw(); fit();
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
    $('startDraw').onclick = () => { if (!route || busy) return; drawing = !drawing; redraw(); };
    map.on('click', (e) => {
        if (!drawing || busy || !route) return;
        if (points.length >= 10000) { msg('El límite es de 10.000 puntos.', 'error'); return; }
        const p = [e.latlng.lat, e.latlng.lng], previous = points.at(-1);
        if (previous && previous[0].toFixed(7) === p[0].toFixed(7) && previous[1].toFixed(7) === p[1].toFixed(7)) return;
        remember(); points.push(p); selectedIndex = points.length - 1; changed();
    });
    function undoAction() { if (busy || !undo.length) return; redo.push(clone(points)); points = undo.pop(); selectedIndex = -1; changed(); }
    function redoAction() { if (busy || !redo.length) return; undo.push(clone(points)); points = redo.pop(); selectedIndex = -1; changed(); }
    $('undoPoint').onclick = undoAction; $('redoPoint').onclick = redoAction;
    document.addEventListener('keydown', (e) => {
        if (e.target.matches('input,textarea,select') || !route || busy) return;
        if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === 'z') { e.preventDefault(); e.shiftKey ? redoAction() : undoAction(); }
    });
    $('vertexIndex').addEventListener('input', () => {
        const i = Number($('vertexIndex').value) - 1; selectedIndex = Number.isInteger(i) && i >= 0 && i < points.length ? i : -1;
        if (selectedIndex >= 0) map.panTo(points[selectedIndex]); redraw();
    });
    $('deletePoint').onclick = () => {
        if (selectedIndex < 0) { msg('Selecciona un punto para eliminarlo.'); return; }
        remember(); points.splice(selectedIndex, 1); selectedIndex = Math.min(selectedIndex, points.length - 1); changed();
    };
    $('insertPoint').onclick = () => {
        if (selectedIndex < 0 || selectedIndex >= points.length - 1) { msg('Selecciona un punto que tenga un siguiente vértice.'); return; }
        if (points.length >= 10000) { msg('El límite es de 10.000 puntos.', 'error'); return; }
        const a = points[selectedIndex], b = points[selectedIndex + 1]; remember();
        points.splice(selectedIndex + 1, 0, [(a[0] + b[0]) / 2, (a[1] + b[1]) / 2]); selectedIndex++; changed();
    };
    $('reverseDraw').onclick = () => {
        if (busy) return;
        if (points.length < 2) { msg('Necesitas al menos dos puntos para invertir el sentido.'); return; }
        remember(); points.reverse();
        if (selectedIndex >= 0) selectedIndex = points.length - 1 - selectedIndex;
        const origin = $('editorOrigin').value; $('editorOrigin').value = $('editorDestination').value; $('editorDestination').value = origin;
        changed();
        msg('Sentido invertido y origen/destino intercambiados. Revisa los datos y guarda el recorrido.', 'success');
    };
    $('clearDraw').onclick = () => {
        if (!points.length || !confirm('¿Limpiar el trazado? Puedes recuperarlo con Deshacer.')) return;
        remember(); points = []; selectedIndex = -1; changed();
    };
    $('fitEditor').onclick = fit;
    ['editorCode','editorName','editorOrigin','editorDestination','editorDescription','editorSource','validationNote'].forEach((id) => $(id).addEventListener('input', () => changed(false)));
    ['editorDirection','editorColor'].forEach((id) => $(id).addEventListener('change', () => changed(false)));
    $('editorValidated').addEventListener('change', () => changed(false));

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
            remember(); points = clone(draft.points); applyFields({ ...route, ...draft, validada: false }); selectedIndex = -1; changed(); $('draftBanner').classList.add('hidden'); fit(); msg('Borrador recuperado. Revisa el trazado antes de guardar.', 'success');
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
            clearTimeout(draftTimer); storeDraft(); lock(true); locked = true; drawing = false;
            msg(isNew() ? 'Creando ruta en PostgreSQL…' : 'Guardando nueva versión del recorrido…');
            const body = { ...data, recorrido: g, validada: $('editorValidated').checked };
            const saved = isNew()
                ? await Spevb.api('/api/editor/rutas', { method: 'POST', headers: { 'Content-Type': 'application/json', 'X-Editor-Token': token }, body: JSON.stringify(body) })
                : await Spevb.api('/api/editor/rutas/' + route.id + '/recorrido', { method: 'PUT', headers: { 'Content-Type': 'application/json', 'X-Editor-Token': token }, body: JSON.stringify({ ...body, version: route.version }) });
            route = saved; points = saved.recorrido.coordinates.map((c) => [c[1], c[0]]); applyFields(saved);
            setDirty(false); discardDraft(); undo = []; redo = []; selectedIndex = -1;
            await refreshRoutes(saved.id, false); $('editorMapTitle').textContent = saved.codigo + ' · ' + saved.nombre; redraw();
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
    loadRoutes();
})();
