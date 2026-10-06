'use strict';
(() => {
    const $ = (id) => document.getElementById(id);
    const status = (message, type = 'info') => {
        $('statusMessage').textContent = message;
        $('statusMessage').className = 'status ' + type;
    };
    let map;
    try {
        map = Spevb.createMap('map');
    } catch (error) {
        status(error.message, 'error');
        return;
    }
    const layers = L.layerGroup().addTo(map),
        stops = L.layerGroup().addTo(map);
    let routes = [],
        selected = null,
        routeLayer,
        controller,
        sequence = 0;
    const normalize = (s) =>
        String(s ?? '')
            .normalize('NFD')
            .replace(/[\u0300-\u036f]/g, '')
            .toLowerCase();
    function renderList() {
        const query = normalize($('routeSearch').value),
            filter = $('validationFilter').value;
        const filtered = routes.filter(
            (r) =>
                normalize([r.codigo, r.nombre, r.origen, r.destino].join(' ')).includes(query) &&
                (filter === 'all' || (filter === 'validated' ? r.validada : !r.validada)),
        );
        $('routeList').replaceChildren();
        $('routeCount').textContent = filtered.length;
        filtered.forEach((r) => {
            const button = document.createElement('button');
            button.className = 'route-option';
            button.type = 'button';
            button.setAttribute('aria-pressed', String(selected?.id === r.id));
            button.dataset.id = r.id;
            const dot = document.createElement('span');
            dot.className = 'route-option-dot';
            dot.style.backgroundColor = /^#[0-9a-f]{6}$/i.test(r.color) ? r.color : '#0b6e4f';
            const info = document.createElement('span');
            const code = document.createElement('small');
            code.textContent = r.codigo + ' · ' + (r.sentido || 'IDA');
            const name = document.createElement('strong');
            name.textContent = r.nombre;
            const summary = document.createElement('small');
            summary.textContent = r.origen + ' → ' + r.destino;
            info.append(code, name, summary);
            button.append(dot, info);
            button.addEventListener('click', () => loadRoute(r.id));
            $('routeList').append(button);
        });
        if (!filtered.length) {
            const empty = document.createElement('p');
            empty.className = 'empty';
            empty.textContent = 'No hay rutas para esta búsqueda.';
            $('routeList').append(empty);
        }
    }
    async function loadRoutes() {
        $('retryRoutes').classList.add('hidden');
        status('Cargando rutas…');
        try {
            routes = await Spevb.api('/api/rutas');
            renderList();
            status(routes.length + ' rutas disponibles.', 'success');
        } catch (error) {
            status(error.message, 'error');
            $('retryRoutes').classList.remove('hidden');
        }
    }
    function fit() {
        if (routeLayer?.getBounds().isValid())
            map.fitBounds(routeLayer.getBounds(), { padding: [50, 50], maxZoom: 17 });
    }
    async function loadRoute(id) {
        window.dispatchEvent(new CustomEvent('spevb-route-change'));
        controller?.abort();
        controller = new AbortController();
        const current = ++sequence;
        selected = null;
        layers.clearLayers();
        stops.clearLayers();
        routeLayer = null;
        $('routeCard').classList.add('hidden');
        $('fitRoute').disabled = true;
        renderList();
        status('Cargando recorrido…');
        try {
            const route = await Spevb.api('/api/rutas/' + id, { signal: controller.signal });
            if (current !== sequence) return;
            Spevb.geometry(route.recorrido);
            selected = route;
            const color = /^#[0-9a-f]{6}$/i.test(route.color) ? route.color : '#0b6e4f';
            L.geoJSON(route.recorrido, {
                style: { color: '#fff', weight: 11, opacity: 0.95 },
            }).addTo(layers);
            routeLayer = L.geoJSON(route.recorrido, { style: { color, weight: 6 } }).addTo(layers);
            [
                ['inicio', 'origen', 'start', 'Inicio'],
                ['fin', 'destino', 'end', 'Fin'],
            ].forEach(([key, name, kind, label]) => {
                const p = route[key]?.coordinates;
                if (!p) return;
                L.marker([p[1], p[0]], { icon: Spevb.endpoint(kind), zIndexOffset: 1000 })
                    .addTo(layers)
                    .bindPopup(Spevb.text(label + ': ' + route[name]))
                    .bindTooltip(Spevb.text(label + ' · ' + route[name]), {
                        permanent: true,
                        direction: 'top',
                        offset: [0, -18],
                    });
            });
            $('stopsList').replaceChildren();
            (route.paraderos || []).forEach((stop) => {
                const p = stop.ubicacion.coordinates;
                const marker = L.circleMarker([p[1], p[0]], {
                    radius: 6,
                    color: '#fff',
                    weight: 2,
                    fillColor: '#184b42',
                    fillOpacity: 1,
                })
                    .bindTooltip(Spevb.text(stop.secuencia + '. ' + stop.nombre))
                    .addTo(stops);
                const li = document.createElement('li'),
                    button = document.createElement('button');
                button.type = 'button';
                button.className = 'stop-button';
                button.textContent = stop.nombre;
                button.addEventListener('click', () => {
                    if (!map.hasLayer(stops)) {
                        stops.addTo(map);
                        $('showStops').checked = true;
                    }
                    map.setView(marker.getLatLng(), 17);
                    marker.openTooltip();
                });
                li.append(button);
                $('stopsList').append(li);
            });
            const texts = {
                routeCode: route.codigo,
                routeName: route.nombre,
                routeOrigin: route.origen,
                routeDestination: route.destino,
                directionBadge: route.sentido,
                stopCount: route.paraderos.length,
                routeDistance:
                    new Intl.NumberFormat('es-CO', { maximumFractionDigits: 2 }).format(
                        route.distanciaKm,
                    ) + ' km',
                routeDescription: route.descripcion,
                routeSource: 'Fuente: ' + (route.fuente || 'Sin registrar'),
                updatedAt:
                    'Actualizado: ' +
                    new Date(route.actualizadoEn).toLocaleString('es-CO') +
                    ' · Versión ' +
                    route.version,
                mapSubtitle: route.codigo + ' · ' + route.origen + ' → ' + route.destino,
            };
            Object.entries(texts).forEach(([key, value]) => ($(key).textContent = value ?? ''));
            $('routeColor').style.backgroundColor = color;
            $('validationBadge').textContent = route.validada
                ? 'Revisión registrada'
                : 'Pendiente de validar';
            $('validationBadge').className = 'pill ' + (route.validada ? 'ok' : 'pending');
            $('demoWarning').classList.toggle('hidden', route.validada);
            $('stopsBlock').classList.toggle('hidden', !route.paraderos.length);
            $('routeWarnings').replaceChildren();
            route.advertencias.forEach((w) => {
                const li = document.createElement('li');
                li.textContent = w;
                $('routeWarnings').append(li);
            });
            $('routeWarnings').classList.toggle('hidden', !route.advertencias.length);
            $('routeCard').classList.remove('hidden');
            $('fitRoute').disabled = false;
            renderList();
            fit();
            status('Mostrando ' + route.codigo + '.', 'success');
            history.replaceState(null, '', '?ruta=' + route.id);
            return route;
        } catch (error) {
            if (error.name !== 'AbortError' && current === sequence) status(error.message, 'error');
        }
    }
    window.SpevbExplorer = { map, showRoute: loadRoute };
    $('routeSearch').addEventListener('input', renderList);
    $('validationFilter').addEventListener('change', renderList);
    $('retryRoutes').addEventListener('click', loadRoutes);
    $('fitRoute').addEventListener('click', fit);
    $('resetMap').addEventListener('click', () => map.setView(Spevb.center, 13));
    $('showStops').addEventListener('change', (e) =>
        e.target.checked ? stops.addTo(map) : map.removeLayer(stops),
    );
    $('exportRoute').addEventListener('click', () => {
        if (selected) Spevb.download(Spevb.feature(selected), selected.codigo + '.geojson');
    });
    loadRoutes().then(() => {
        const id = new URLSearchParams(location.search).get('ruta');
        if (/^\d+$/.test(id || '') && routes.some((r) => String(r.id) === id)) loadRoute(id);
    });
})();
