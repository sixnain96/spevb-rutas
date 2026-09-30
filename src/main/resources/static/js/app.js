const VILLAVICENCIO_CENTER = [4.1420, -73.6266];
const DEFAULT_ZOOM = 13;

const routeSelect = document.getElementById('routeSelect');
const statusMessage = document.getElementById('statusMessage');
const routeCard = document.getElementById('routeCard');
const demoWarning = document.getElementById('demoWarning');
const resetMapButton = document.getElementById('resetMap');
const stopsBlock = document.getElementById('stopsBlock');
const stopsList = document.getElementById('stopsList');

if (typeof L === 'undefined') throw new Error('Leaflet no se cargó.');

const map = L.map('map', { zoomControl: true, preferCanvas: true }).setView(VILLAVICENCIO_CENTER, DEFAULT_ZOOM);
L.tileLayer('https://tile.openstreetmap.org/{z}/{x}/{y}.png', { maxZoom: 19, attribution: '&copy; OpenStreetMap contributors' }).addTo(map);

let routeOutline = null;
let routeLayer = null;
let startMarker = null;
let endMarker = null;
let stopLayer = L.layerGroup().addTo(map);

function setStatus(message, type = 'info') {
    statusMessage.textContent = message;
    statusMessage.className = `status ${type}`;
}

function markerIcon(kind) {
    return L.divIcon({
        className: 'endpoint-wrapper',
        html: `<div class="endpoint-marker ${kind}">${kind === 'start' ? 'A' : 'B'}</div>`,
        iconSize: [34, 34], iconAnchor: [17, 17]
    });
}

function pointToLatLng(point) {
    if (!point?.coordinates || point.coordinates.length < 2) return null;
    return [point.coordinates[1], point.coordinates[0]];
}

function clearRoute() {
    [routeOutline, routeLayer, startMarker, endMarker].forEach(layer => { if (layer) map.removeLayer(layer); });
    routeOutline = routeLayer = startMarker = endMarker = null;
    stopLayer.clearLayers();
}

function showRouteInfo(route) {
    document.getElementById('routeCode').textContent = route.codigo ?? '';
    document.getElementById('routeName').textContent = route.nombre ?? '';
    document.getElementById('routeOrigin').textContent = route.origen ?? 'Sin información';
    document.getElementById('routeDestination').textContent = route.destino ?? 'Sin información';
    document.getElementById('routeDistance').textContent = route.distanciaKm != null ? `${route.distanciaKm} km` : '—';
    document.getElementById('routeDescription').textContent = route.descripcion ?? '';
    document.getElementById('routeColor').style.backgroundColor = route.color || '#0b6e4f';
    document.getElementById('directionBadge').textContent = route.sentido || 'SIN SENTIDO';
    const validation = document.getElementById('validationBadge');
    validation.textContent = route.validada ? 'Trazado validado' : 'Pendiente de validar';
    validation.className = `pill ${route.validada ? 'ok' : 'pending'}`;
    document.getElementById('routeSource').textContent = route.fuente ? `Fuente: ${route.fuente}` : 'Fuente: no registrada';
    demoWarning.classList.toggle('hidden', route.validada);

    stopsList.innerHTML = '';
    (route.paraderos || []).forEach(stop => {
        const li = document.createElement('li');
        li.textContent = `${stop.secuencia}. ${stop.nombre}`;
        stopsList.appendChild(li);
    });
    stopsBlock.classList.toggle('hidden', !(route.paraderos || []).length);
    routeCard.classList.remove('hidden');
}

function drawEndpoints(route) {
    const start = pointToLatLng(route.inicio);
    const end = pointToLatLng(route.fin);

    if (start) {
        startMarker = L.marker(start, { icon: markerIcon('start'), zIndexOffset: 1000 }).addTo(map)
            .bindPopup(`<strong>INICIO DE RUTA</strong><br>${route.origen ?? ''}`)
            .bindTooltip(`Inicio · ${route.origen ?? ''}`, { permanent: true, direction: 'top', offset: [0, -18], className: 'endpoint-label start-label' });
    }
    if (end) {
        endMarker = L.marker(end, { icon: markerIcon('end'), zIndexOffset: 1000 }).addTo(map)
            .bindPopup(`<strong>FIN DE RUTA</strong><br>${route.destino ?? ''}`)
            .bindTooltip(`Fin · ${route.destino ?? ''}`, { permanent: true, direction: 'top', offset: [0, -18], className: 'endpoint-label end-label' });
    }
}

function drawStops(route) {
    (route.paraderos || []).forEach(stop => {
        const latlng = pointToLatLng(stop.ubicacion);
        if (!latlng) return;
        L.circleMarker(latlng, { radius: 6, weight: 2, fillOpacity: 1 })
            .bindTooltip(`${stop.secuencia}. ${stop.nombre}`)
            .addTo(stopLayer);
    });
}

async function loadRoutes() {
    try {
        const response = await fetch('/api/rutas');
        if (!response.ok) throw new Error(`HTTP ${response.status}`);
        const routes = await response.json();
        routeSelect.innerHTML = '<option value="">Selecciona una ruta</option>';
        routes.forEach(route => {
            const option = document.createElement('option');
            option.value = route.id;
            option.textContent = `${route.codigo} · ${route.nombre}${route.sentido ? ` · ${route.sentido}` : ''}`;
            routeSelect.appendChild(option);
        });
        routeSelect.disabled = false;
        setStatus(`${routes.length} rutas disponibles. Selecciona una para verla en el mapa.`, 'success');
    } catch (error) {
        console.error(error);
        setStatus('No se pudo conectar con la API.', 'error');
    }
}

async function loadRoute(id) {
    if (!id) {
        clearRoute();
        routeCard.classList.add('hidden');
        map.setView(VILLAVICENCIO_CENTER, DEFAULT_ZOOM);
        return;
    }
    routeSelect.disabled = true;
    setStatus('Cargando recorrido…', 'info');
    try {
        const response = await fetch(`/api/rutas/${id}`);
        if (!response.ok) throw new Error(`HTTP ${response.status}`);
        const route = await response.json();
        clearRoute();

        routeOutline = L.geoJSON(route.recorrido, { style: { color: '#ffffff', weight: 11, opacity: 0.95, lineCap: 'round', lineJoin: 'round' } }).addTo(map);
        routeLayer = L.geoJSON(route.recorrido, { style: { color: route.color || '#0b6e4f', weight: 7, opacity: 1, lineCap: 'round', lineJoin: 'round' } }).addTo(map);
        drawEndpoints(route);
        drawStops(route);
        showRouteInfo(route);

        requestAnimationFrame(() => {
            map.invalidateSize(true);
            const bounds = routeLayer.getBounds();
            if (bounds.isValid()) map.fitBounds(bounds, { padding: [70, 70], maxZoom: 17 });
        });
        setStatus(`Mostrando ${route.codigo} · ${route.nombre}`, 'success');
    } catch (error) {
        console.error(error);
        setStatus('No se pudo cargar el recorrido seleccionado.', 'error');
    } finally {
        routeSelect.disabled = false;
    }
}

routeSelect.addEventListener('change', e => loadRoute(e.target.value));
resetMapButton.addEventListener('click', () => { map.invalidateSize(true); map.setView(VILLAVICENCIO_CENTER, DEFAULT_ZOOM); });
window.addEventListener('load', () => setTimeout(() => map.invalidateSize(true), 250));
window.addEventListener('resize', () => map.invalidateSize(false));
loadRoutes();
