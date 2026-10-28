'use strict';
/* Administración de paraderos de bus (independientes de las rutas): clic en el mapa para agregar, tocar uno para editarlo o moverlo,
   búsqueda por nombre e importación masiva (GeoJSON / CSV). Todo se valida otra vez en el servidor. */
(() => {
    const $ = (id) => document.getElementById(id);
    const status = (m, t = 'info') => { $('paStatus').textContent = m; $('paStatus').className = 'status ' + t; };
    let map;
    try { map = Spevb.createMap('paradasMap'); } catch (e) { status(e.message, 'error'); return; }

    const MIN_ZOOM = 13;
    const rutasLayer = L.layerGroup().addTo(map), parLayer = L.layerGroup().addTo(map);
    const markers = new Map();                 // id → marker
    let agregando = false, sel = null, draft = null, seq = 0, timer = null;   // sel = {id|null, lat, lng}
    const headers = (json) => ({ ...(json ? { 'Content-Type': 'application/json' } : {}), 'X-Editor-Token': $('paToken').value });
    const claveOk = () => { if ($('paToken').value.length < 32) { status('Introduce la clave de administración (o entra como administrador).', 'error'); return false; } return true; };
    const estiloNormal = { radius: 7, color: '#fff', weight: 2, fillColor: '#1e5fbf', fillOpacity: 1 };
    const estiloSel = { radius: 10, color: '#fff', weight: 3, fillColor: '#e08a00', fillOpacity: 1 };

    // ---- rutas de fondo (para ubicar el paradero sobre la línea)
    async function cargarRutas() {
        try {
            const lista = await Spevb.api('/api/rutas');
            for (const r of lista.slice(0, 60)) {
                try {
                    const d = await Spevb.api('/api/rutas/' + r.id);
                    L.polyline(d.recorrido.coordinates.map((c) => [c[1], c[0]]), { color: /^#[0-9a-f]{6}$/i.test(r.color) ? r.color : '#176650', weight: 5, opacity: 0.55, interactive: false })
                        .addTo(rutasLayer).bindTooltip(Spevb.text(r.codigo));
                } catch { /* una ruta con problema no detiene a las demás */ }
            }
        } catch { /* sin rutas: se puede igual */ }
    }

    // ---- paraderos de la zona visible
    async function cargarZona() {
        const mySeq = ++seq;
        if (map.getZoom() < MIN_ZOOM) { parLayer.clearLayers(); markers.clear(); $('paConteo').textContent = 'Acerca el mapa'; $('paSubtitulo').textContent = 'Acerca el mapa para ver los paraderos'; return; }
        const b = map.getBounds();
        try {
            const q = new URLSearchParams({ sur: b.getSouth(), oeste: b.getWest(), norte: b.getNorth(), este: b.getEast(), limite: 3000 });
            const datos = await Spevb.api('/api/paraderos?' + q);
            if (mySeq !== seq) return;
            parLayer.clearLayers(); markers.clear();
            datos.forEach((p) => pintar(p));
            $('paConteo').textContent = datos.length + (datos.length === 1 ? ' paradero a la vista' : ' paraderos a la vista');
            $('paSubtitulo').textContent = 'Rutas en colores · paraderos en azul';
        } catch (e) { if (mySeq === seq) status(e.message, 'error'); }
    }
    function pintar(p) {
        const m = L.circleMarker([p.lat, p.lng], { ...(sel && sel.id === p.id ? estiloSel : estiloNormal) }).addTo(parLayer).bindTooltip(Spevb.text(p.nombre));
        m.on('click', (e) => { L.DomEvent.stopPropagation(e); elegir(p.id); });
        markers.set(p.id, m);
    }
    map.on('moveend', () => { clearTimeout(timer); timer = setTimeout(cargarZona, 250); });

    // ---- formulario
    function formulario(modo, datos) {
        $('paForm').disabled = !modo;
        $('paModo').className = 'pa-modo ' + (modo || '');
        if (!modo) { $('paModo').textContent = 'Nada seleccionado. Toca un paradero del mapa o activa «Agregar paraderos».'; $('paNombre').value = $('paNota').value = ''; $('paCoord').textContent = ''; $('paRutas').textContent = ''; $('paEliminar').hidden = true; return; }
        $('paModo').textContent = modo === 'nuevo' ? 'Paradero nuevo: ponle nombre y guárdalo. Puedes arrastrar el punto para afinarlo.' : 'Editando el paradero #' + datos.id + '. Arrastra el punto para moverlo.';
        $('paNombre').value = datos.nombre || ''; $('paNota').value = datos.nota || ''; $('paActivo').checked = datos.activo !== false;
        $('paEliminar').hidden = modo !== 'editar';
        coord(); rutasQueSirve(datos.rutas);
    }
    const coord = () => { $('paCoord').textContent = sel ? 'Ubicación: ' + sel.lat.toFixed(6) + ', ' + sel.lng.toFixed(6) : ''; };
    function rutasQueSirve(rutas) {
        const el = $('paRutas');
        if (!rutas) { el.textContent = ''; return; }
        el.replaceChildren();
        if (!rutas.length) { el.textContent = 'Ninguna ruta pasa a menos de 30 m de aquí: el planificador no lo usará hasta que pase una.'; return; }
        const b = document.createElement('b'); b.textContent = 'Sirve a: ';
        el.append(b, document.createTextNode(rutas.map((r) => r.codigo + ' (' + r.metros + ' m)').join(', ')));
    }
    function limpiarBorrador() { if (draft) { map.removeLayer(draft); draft = null; } }
    function cancelar() {
        limpiarBorrador(); const id = sel && sel.id; sel = null; formulario(null);
        if (id && markers.get(id)) markers.get(id).setStyle(estiloNormal);
        cargarZona();
    }

    // ---- agregar con clic
    function modo(v) {
        agregando = v;
        $('paAgregar').setAttribute('aria-pressed', String(v));
        $('paAgregar').textContent = v ? 'Terminar de agregar' : 'Agregar paraderos';
        map.getContainer().classList.toggle('adding-paradas', v);
        if (v) status('Haz clic en el mapa donde está el paradero.', 'info');
    }
    $('paAgregar').onclick = () => modo(!agregando);
    map.on('click', (e) => {
        if (!agregando) return;
        if (map.getZoom() < 15) return status('Acerca más el mapa (zoom 15 o más) para ubicar el paradero con precisión.', 'error');
        limpiarBorrador();
        sel = { id: null, lat: e.latlng.lat, lng: e.latlng.lng };
        draft = L.marker(e.latlng, { draggable: true, zIndexOffset: 1000 }).addTo(map);
        draft.on('dragend', async () => { const p = draft.getLatLng(); sel.lat = p.lat; sel.lng = p.lng; coord(); await vecinas(); });
        formulario('nuevo', { nombre: '', nota: '', activo: true });
        $('paNombre').focus();
        vecinas();
    });
    async function vecinas() {       // qué rutas pasan cerca del punto (antes de guardar)
        try {
            const r = await Spevb.api('/api/lugares/reverso?lat=' + sel.lat + '&lng=' + sel.lng, { timeout: 6000 });
            const n = (r && r.nombre || '').trim();
            if (n && !/^punto en mapa/i.test(n) && !$('paNombre').value) $('paNombre').placeholder = 'Sugerencia: ' + n;
        } catch { /* sin sugerencia */ }
    }

    // ---- elegir uno existente
    async function elegir(id) {
        if (!claveOk() && !$('paToken').hidden) return;
        try {
            const p = await Spevb.api('/api/editor/paraderos/' + id, { headers: headers() });
            limpiarBorrador();
            if (sel && sel.id && markers.get(sel.id)) markers.get(sel.id).setStyle(estiloNormal);
            sel = { id: p.id, lat: p.lat, lng: p.lng };
            const m = markers.get(id); if (m) m.setStyle(estiloSel);
            draft = L.marker([p.lat, p.lng], { draggable: true, zIndexOffset: 1000 }).addTo(map);
            draft.on('dragend', () => { const q = draft.getLatLng(); sel.lat = q.lat; sel.lng = q.lng; coord(); });
            formulario('editar', p);
            modo(false);
            status('Paradero «' + p.nombre + '» abierto.', 'info');
        } catch (e) { status(e.message, 'error'); }
    }

    // ---- guardar / eliminar
    $('paGuardar').onclick = async () => {
        if (!sel || !claveOk()) return;
        const nombre = $('paNombre').value.trim();
        if (nombre.length < 2) { status('Escribe el nombre del paradero (mínimo 2 letras).', 'error'); $('paNombre').focus(); return; }
        const cuerpo = JSON.stringify({ nombre, nota: $('paNota').value, lat: Number(sel.lat.toFixed(7)), lng: Number(sel.lng.toFixed(7)), activo: $('paActivo').checked });
        $('paGuardar').disabled = true;
        try {
            const nuevo = sel.id == null;
            const p = await Spevb.api(nuevo ? '/api/editor/paraderos' : '/api/editor/paraderos/' + sel.id, { method: nuevo ? 'POST' : 'PUT', headers: headers(true), body: cuerpo });
            const sirve = p.rutas && p.rutas.length ? ' Sirve a: ' + p.rutas.map((r) => r.codigo).join(', ') + '.' : ' Ninguna ruta pasa a menos de 30 m todavía.';
            status('Guardado «' + p.nombre + '».' + sirve, 'success');
            limpiarBorrador(); sel = null; formulario(null);
            await cargarZona(); buscar();
            if (nuevo && agregando) status('Guardado «' + p.nombre + '».' + sirve + ' Haz clic para agregar otro.', 'success');
        } catch (e) { status(e.message, 'error'); }
        finally { $('paGuardar').disabled = false; }
    };
    $('paNombre').addEventListener('keydown', (e) => { if (e.key === 'Enter') { e.preventDefault(); $('paGuardar').click(); } });
    $('paCancelar').onclick = cancelar;
    $('paEliminar').onclick = async () => {
        if (!sel || !sel.id || !claveOk()) return;
        if (!confirm('¿Eliminar este paradero? Dejará de usarse en los cálculos.')) return;
        try { await Spevb.api('/api/editor/paraderos/' + sel.id, { method: 'DELETE', headers: headers() }); status('Paradero eliminado.', 'success'); limpiarBorrador(); sel = null; formulario(null); await cargarZona(); buscar(); }
        catch (e) { status(e.message, 'error'); }
    };
    $('paVista').onclick = () => map.setView(Spevb.center, 13);

    // ---- búsqueda
    let tBuscar = null;
    async function buscar() {
        if ($('paToken').value.length < 32) return;
        try {
            const lista = await Spevb.api('/api/editor/paraderos?q=' + encodeURIComponent($('paBuscar').value) + '&limite=60', { headers: headers() });
            const cont = $('paLista'); cont.replaceChildren();
            if (!lista.length) { const p = document.createElement('p'); p.className = 'hint'; p.textContent = 'No hay paraderos con ese nombre.'; cont.append(p); return; }
            lista.forEach((p) => {
                const b = document.createElement('button'); b.type = 'button'; b.className = 'pa-fila' + (p.activo ? '' : ' inactivo');
                const t = document.createElement('span'); const n = document.createElement('strong'); n.textContent = p.nombre;
                const s = document.createElement('small'); s.textContent = p.lat.toFixed(5) + ', ' + p.lng.toFixed(5) + (p.activo ? '' : ' · desactivado');
                t.append(n, s); b.append(t);
                b.onclick = () => { map.setView([p.lat, p.lng], Math.max(map.getZoom(), 17)); setTimeout(() => elegir(p.id), 400); };
                cont.append(b);
            });
        } catch (e) { status(e.message, 'error'); }
    }
    $('paBuscar').addEventListener('input', () => { clearTimeout(tBuscar); tBuscar = setTimeout(buscar, 300); });
    $('paToken').addEventListener('change', buscar);

    // ---- importar GeoJSON / CSV
    function leerArchivo(texto, nombreArchivo) {
        const salida = [];
        if (/\.csv$/i.test(nombreArchivo)) {
            const filas = texto.replace(/^﻿/, '').split(/\r?\n/).filter((l) => l.trim());
            const sep = (filas[0].match(/;/g) || []).length > (filas[0].match(/,/g) || []).length ? ';' : ',';
            const cab = filas[0].split(sep).map((c) => c.trim().toLowerCase());
            const ix = (...n) => cab.findIndex((c) => n.includes(c));
            const iN = ix('nombre', 'name'), iLa = ix('lat', 'latitud', 'latitude'), iLo = ix('lng', 'lon', 'long', 'longitud', 'longitude'), iNo = ix('nota', 'note');
            if (iN < 0 || iLa < 0 || iLo < 0) throw new Error('El CSV necesita las columnas nombre, lat y lng.');
            filas.slice(1).forEach((f) => { const c = f.split(sep); salida.push({ nombre: (c[iN] || '').trim().replace(/^"|"$/g, ''), nota: iNo >= 0 ? (c[iNo] || '').trim() : '', lat: parseFloat(c[iLa]), lng: parseFloat(c[iLo]) }); });
        } else {
            const g = JSON.parse(texto);
            const feats = g.type === 'FeatureCollection' ? g.features : g.type === 'Feature' ? [g] : [];
            feats.forEach((f) => {
                if (!f.geometry || f.geometry.type !== 'Point') return;
                const pr = f.properties || {};
                salida.push({ nombre: String(pr.nombre || pr.name || '').trim(), nota: String(pr.nota || pr.note || '').trim(), lng: f.geometry.coordinates[0], lat: f.geometry.coordinates[1] });
            });
        }
        return salida;
    }
    $('paArchivo').addEventListener('change', async (e) => {
        const f = e.target.files[0]; if (!f) return;
        try {
            if (!claveOk()) return;
            if (f.size > 2_000_000) throw new Error('El archivo supera 2 MB.');
            const lista = leerArchivo(await f.text(), f.name);
            const buenos = lista.filter((p) => p.nombre.length >= 2 && Number.isFinite(p.lat) && Number.isFinite(p.lng));
            if (!buenos.length) throw new Error('No se encontró ningún paradero válido (nombre, lat y lng).');
            if (!confirm('Se importarán ' + buenos.length + ' paraderos' + (lista.length > buenos.length ? ' (' + (lista.length - buenos.length) + ' filas sin nombre o sin coordenadas se omiten)' : '') + '. ¿Continuar?')) return;
            let creados = 0, repetidos = 0;
            for (let i = 0; i < buenos.length; i += 500) {
                const r = await Spevb.api('/api/editor/paraderos/lote', { method: 'POST', headers: headers(true), body: JSON.stringify({ paraderos: buenos.slice(i, i + 500) }) });
                creados += r.creados; repetidos += r.repetidos;
            }
            status('Importados ' + creados + ' paraderos' + (repetidos ? ' (' + repetidos + ' ya existían)' : '') + '.', 'success');
            await cargarZona(); buscar();
        } catch (err) { status(err.message, 'error'); }
        finally { e.target.value = ''; }
    });

    cargarRutas(); cargarZona();
    setTimeout(buscar, 1200);       // si el administrador ya tiene sesión (clave automática) se llena la lista
})();
