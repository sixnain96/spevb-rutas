'use strict';
(() => {
    const $ = (id) => document.getElementById(id);
    const status = (message, type = 'info') => {
        $('placeStatus').textContent = message;
        $('placeStatus').className = 'status ' + type;
    };
    let map;
    try { map = Spevb.createMap('placesMap'); }
    catch (error) { status(error.message, 'error'); return; }

    const selectedLayer = L.layerGroup().addTo(map), catalogLayer = L.layerGroup().addTo(map);
    let picking = false, selected = null, lookupSeq = 0, pendingText = null;

    const latLng = (p) => [p.lat, p.lng];
    function setPicking(value) {
        picking = value;
        $('pickPlace').setAttribute('aria-pressed', String(value));
        map.getContainer().classList.toggle('picking-location', value);
        $('pickPlace').textContent = value ? 'Haz clic en el mapa…' : 'Elegir punto en mapa';
    }
    function fill(place, center = true) {
        selected = place;
        $('placeName').value = place.nombre || '';
        $('placeAddress').value = place.direccion || place.nombre || '';
        $('placeLat').value = Number(place.lat).toFixed(7);
        $('placeLng').value = Number(place.lng).toFixed(7);
        if (place.fuente) $('placeSource').value = place.fuente;
        selectedLayer.clearLayers();
        L.marker(latLng(place)).addTo(selectedLayer).bindTooltip(Spevb.text(place.nombre || 'Punto seleccionado')).openTooltip();
        if (center) map.setView(latLng(place), 17);
        $('placeMapSubtitle').textContent = place.nombre || 'Punto seleccionado';
        setPicking(false);
    }
    function renderResults(results) {
        $('placeResults').replaceChildren();
        results.forEach((place) => {
            const button = document.createElement('button');
            button.type = 'button'; button.className = 'place-option rich-place';
            const title = document.createElement('strong'); title.textContent = place.nombre;
            const meta = document.createElement('small');
            meta.textContent = (place.confirmado ? 'Confirmado localmente · ' : '') + (place.fuente || 'Ubicación') + (place.precision === 'PREDIO_APROXIMADO' ? ' · Centro del predio, no entrada' : '');
            button.append(title, meta); button.onclick = () => fill(place);
            $('placeResults').append(button);
        });
    }
    async function search() {
        const query = $('placeSearch').value.trim(), current = ++lookupSeq;
        if (query.length < 3) { status('Escribe al menos 3 caracteres.', 'error'); return; }
        $('searchPlace').disabled = true; status('Buscando únicamente en Villavicencio…');
        try {
            const data = await Spevb.api('/api/lugares?q=' + encodeURIComponent(query));
            if (current !== lookupSeq) return;
            renderResults(data.lugares);
            status(data.lugares.length ? 'Selecciona la coincidencia correcta.' : 'No hubo coincidencias. Marca el punto en el mapa y guárdalo en el catálogo local.', data.lugares.length ? 'success' : 'info');
        } catch (error) { if (current === lookupSeq) status(error.message, 'error'); }
        finally { $('searchPlace').disabled = false; }
    }
    async function reverse(lat, lng) {
        status('Buscando la referencia del punto…');
        try {
            const place = await Spevb.api('/api/lugares/reverso?lat=' + encodeURIComponent(lat) + '&lng=' + encodeURIComponent(lng));
            fill({ ...place, lat, lng });
            status('Punto listo. Ajusta el nombre si hace falta y guárdalo.', 'success');
        } catch (error) {
            fill({ nombre: 'Punto en mapa', direccion: '', lat, lng, fuente: 'Registrado manualmente por el equipo SPEVB' });
            status(error.message + ' Puedes guardar el punto con una referencia manual.', 'info');
        }
    }
    $('searchPlace').onclick = search;
    $('placeSearch').addEventListener('keydown', (e) => { if (e.key === 'Enter') { e.preventDefault(); search(); } });
    $('pickPlace').onclick = () => { setPicking(!picking); status(picking ? 'Haz clic exactamente donde quieres registrar el lugar.' : 'Selección cancelada.'); };
    map.on('click', (e) => {
        if (!picking) return;
        if (pendingText) {
            // Se está ubicando una búsqueda pendiente: se conserva el texto buscado y sólo se fija el punto.
            const place = { nombre: pendingText, direccion: pendingText, lat: e.latlng.lat, lng: e.latlng.lng };
            fill(place, false);
            status('Punto fijado para «' + pendingText + '». Pulsa «Guardar lugar / corregir dirección».', 'success');
            return;
        }
        reverse(e.latlng.lat, e.latlng.lng);
    });
    $('resetPlaceMap').onclick = () => map.setView(Spevb.center, 13);

    $('useCurrentPlace').onclick = async () => {
        $('useCurrentPlace').disabled = true;
        try {
            status('Buscando tu ubicación… Si el navegador lo pide, elige «Permitir».');
            const fix = await Spevb.Gps.obtener({ objetivo: 40, onProgreso: (l) => status('Mejorando la precisión… ±' + Math.round(l.precision) + ' m') });
            if (!Spevb.Gps.dentroDeCiudad(fix.lat, fix.lng)) { status('Tu ubicación está fuera de Villavicencio. Puedes elegir el punto en el mapa.', 'error'); return; }
            if (fix.precision > 1000) { status('Solo se obtuvo una ubicación muy aproximada (±' + Math.round(fix.precision) + ' m). Elige el punto en el mapa.', 'error'); return; }
            await reverse(fix.lat, fix.lng);
            status('Ubicación tomada con precisión ' + Spevb.Gps.calidad(fix.precision).texto + ' (±' + Math.round(fix.precision) + ' m). Comprueba el punto antes de guardar.', fix.precision > 150 ? 'info' : 'success');
        } catch (error) {
            const e = error.codigo ? error : Spevb.Gps.errorGps(error);
            status(e.message + (e.ayuda ? ' ' + e.ayuda : ''), 'error');
        } finally {
            $('useCurrentPlace').disabled = false;
        }
    };

    $('savePlace').onclick = async () => {
        const lat = Number($('placeLat').value), lng = Number($('placeLng').value), token = $('placeToken').value;
        if (!$('placeName').value.trim()) { status('Escribe un nombre para el lugar.', 'error'); return; }
        if (!Number.isFinite(lat) || !Number.isFinite(lng)) { status('Selecciona coordenadas válidas.', 'error'); return; }
        if (token.length < 32) { status('Introduce la clave de administración de al menos 32 caracteres.', 'error'); return; }
        $('savePlace').disabled = true; status('Guardando lugar…');
        try {
            const saved = await Spevb.api('/api/editor/lugares', {
                method: 'POST', headers: { 'Content-Type': 'application/json', 'X-Editor-Token': token },
                body: JSON.stringify({ nombre: $('placeName').value.trim(), direccion: $('placeAddress').value.trim(), lat, lng, fuente: $('placeSource').value.trim() }),
            });
            // Si el texto es una dirección completa (vía, cruce y placa) también se corrige en la base de direcciones.
            let corregida = false;
            for (const texto of [$('placeAddress').value.trim(), $('placeName').value.trim()]) {
                if (!texto || corregida) continue;
                try {
                    const r = await Spevb.api('/api/editor/direcciones/corregir', {
                        method: 'POST', headers: { 'Content-Type': 'application/json', 'X-Editor-Token': token },
                        body: JSON.stringify({ texto, lat, lng }),
                    });
                    corregida = !!r.ok;
                } catch { /* no es una dirección completa o el servicio no respondió */ }
            }
            fill(saved);
            status(corregida ? 'Guardado. La dirección corregida será el primer resultado en las búsquedas de SPEVB.' : 'Lugar guardado en PostgreSQL. Ya aparecerá en las búsquedas de SPEVB.', 'success');
            pendingText = null;
            if (corregida) loadPending();
            await loadCatalog();
        } catch (error) { status(error.message, 'error'); }
        finally { $('savePlace').disabled = false; }
    };

    async function loadCatalog() {
        const token = $('placeToken').value;
        if (token.length < 32) { status('Introduce la clave de administración para consultar el catálogo completo.', 'error'); return; }
        const query = $('catalogFilter').value.trim();
        $('loadCatalog').disabled = true; status('Cargando catálogo local…');
        try {
            const data = await Spevb.api('/api/editor/lugares?q=' + encodeURIComponent(query) + '&limit=300', { headers: { 'X-Editor-Token': token } });
            $('catalogList').replaceChildren(); catalogLayer.clearLayers();
            data.lugares.forEach((place) => {
                const row = document.createElement('div'); row.className = 'catalog-row';
                const info = document.createElement('button'); info.type = 'button'; info.className = 'catalog-place';
                const title = document.createElement('strong'); title.textContent = place.nombre;
                const small = document.createElement('small'); small.textContent = (place.direccion || 'Sin dirección escrita') + ' · ' + (place.confirmado ? 'confirmado localmente' : 'referencia externa');
                info.append(title, small); info.onclick = () => fill(place);
                const del = document.createElement('button'); del.type = 'button'; del.className = 'text-button danger'; del.textContent = 'Eliminar';
                del.onclick = async () => {
                    if (!confirm('¿Eliminar «' + place.nombre + '» del catálogo local?')) return;
                    try {
                        await Spevb.api('/api/editor/lugares/' + place.id, { method: 'DELETE', headers: { 'X-Editor-Token': token } });
                        await loadCatalog(); status('Lugar eliminado del catálogo.', 'success');
                    } catch (error) { status(error.message, 'error'); }
                };
                row.append(info, del); $('catalogList').append(row);
                L.circleMarker(latLng(place), { radius: place.confirmado ? 6 : 4, color: place.confirmado ? '#176650' : '#6c7880', fillOpacity: 0.85 }).bindTooltip(Spevb.text(place.nombre)).addTo(catalogLayer);
            });
            status(data.lugares.length + ' lugares mostrados de ' + data.total + ' guardados.', 'success');
        } catch (error) { status(error.message, 'error'); }
        finally { $('loadCatalog').disabled = false; }
    }
    $('loadCatalog').onclick = loadCatalog;

    // ---- estado de la base de direcciones y pendientes ----
    const adminHeaders = () => ({ 'Content-Type': 'application/json', 'X-Editor-Token': $('placeToken').value });
    async function loadPending() {
        if ($('placeToken').value.length < 32) { $('dirEstado').textContent = 'Introduce la clave de administración de al menos 32 caracteres.'; $('dirEstado').className = 'status error'; return; }
        try {
            const [estado, pend] = await Promise.all([
                Spevb.api('/api/direcciones/estado'),
                Spevb.api('/api/editor/direcciones/sin-resultado?limite=40', { headers: adminHeaders() }),
            ]);
            $('dirEstado').textContent = estado.direcciones.toLocaleString('es-CO') + ' direcciones · ' + (estado.lugares || 0).toLocaleString('es-CO')
                + ' lugares · catastro: ' + estado.importacion + (estado.ultimaImportacion ? ' (' + estado.ultimaImportacion.slice(0, 10) + ')' : '')
                + ' · OpenStreetMap: ' + (estado.osm || 'INACTIVO') + ' · ' + pend.length + ' pendientes';
            $('dirEstado').className = 'status info';
            $('pendingList').replaceChildren();
            pend.forEach((item) => {
                const row = document.createElement('div'); row.className = 'catalog-row';
                const info = document.createElement('button'); info.type = 'button'; info.className = 'catalog-place';
                const title = document.createElement('strong'); title.textContent = item.texto;
                const small = document.createElement('small'); small.textContent = 'Buscada ' + item.veces + (item.veces === 1 ? ' vez' : ' veces') + ' · pulsa para ubicarla';
                info.append(title, small);
                info.onclick = () => {
                    $('placeName').value = item.texto; $('placeAddress').value = item.texto; $('placeSearch').value = item.texto;
                    pendingText = item.texto; selected = null; setPicking(true); status('Marca en el mapa dónde está «' + item.texto + '» y pulsa Guardar.', 'info');
                };
                const del = document.createElement('button'); del.type = 'button'; del.className = 'text-button danger'; del.textContent = 'Descartar';
                del.onclick = async () => {
                    try { await Spevb.api('/api/editor/direcciones/sin-resultado/descartar', { method: 'POST', headers: adminHeaders(), body: JSON.stringify({ consulta: item.consulta }) }); await loadPending(); }
                    catch (error) { status(error.message, 'error'); }
                };
                row.append(info, del); $('pendingList').append(row);
            });
        } catch (error) { $('dirEstado').textContent = error.message; $('dirEstado').className = 'status error'; }
    }
    $('loadPending').onclick = loadPending;
    const startImport = (path, label) => async () => {
        try {
            const r = await Spevb.api(path, { method: 'POST', headers: adminHeaders(), body: '{}' });
            $('dirEstado').textContent = r.mensaje || label; $('dirEstado').className = 'status success';
        } catch (error) { $('dirEstado').textContent = error.message; $('dirEstado').className = 'status error'; }
    };
    $('reimportCatastro').onclick = startImport('/api/editor/direcciones/importar-catastro', 'Importación del catastro iniciada.');
    $('reimportOsm').onclick = startImport('/api/editor/direcciones/importar-osm', 'Importación de OpenStreetMap iniciada.');
})();
