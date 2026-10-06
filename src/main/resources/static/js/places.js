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
    let picking = false, selected = null, lookupSeq = 0;

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
            meta.textContent = (place.confirmado ? 'Catálogo local · ' : '') + (place.fuente || 'Ubicación');
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
    map.on('click', (e) => { if (picking) reverse(e.latlng.lat, e.latlng.lng); });
    $('resetPlaceMap').onclick = () => map.setView(Spevb.center, 13);

    $('useCurrentPlace').onclick = () => {
        if (!navigator.geolocation) { status('Este navegador no ofrece geolocalización.', 'error'); return; }
        status('Solicitando tu ubicación…');
        navigator.geolocation.getCurrentPosition(
            (position) => reverse(position.coords.latitude, position.coords.longitude),
            () => status('No fue posible obtener tu ubicación. Puedes elegir el punto en el mapa.', 'error'),
            { enableHighAccuracy: true, timeout: 10000, maximumAge: 30000 },
        );
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
            fill(saved); status('Lugar guardado en PostgreSQL. Ya aparecerá en las búsquedas de SPEVB.', 'success');
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
})();
