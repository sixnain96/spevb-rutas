'use strict';
window.Spevb = (() => {
    const center = [4.142, -73.6266];
    const cityBounds = [[3.95, -73.85], [4.35, -73.45]];
    async function api(url, options = {}) {
        const response = await fetch(url, {
            ...options,
            headers: { Accept: 'application/json', ...options.headers },
        });
        let data;
        try {
            data = await response.json();
        } catch {
            data = null;
        }
        if (!response.ok) {
            const error = new Error(
                data?.message ||
                    'No se pudo completar la operación (HTTP ' + response.status + ').',
            );
            error.status = response.status;
            throw error;
        }
        return data;
    }
    function text(value) {
        const node = document.createElement('span');
        node.textContent = String(value ?? '');
        return node;
    }
    function download(data, name) {
        const url = URL.createObjectURL(
            new Blob([JSON.stringify(data, null, 2)], { type: 'application/geo+json' }),
        );
        const anchor = document.createElement('a');
        anchor.href = url;
        anchor.download = name;
        anchor.click();
        setTimeout(() => URL.revokeObjectURL(url), 1000);
    }
    function geometry(value) {
        let g = value;
        if (g?.type === 'FeatureCollection') {
            if (g.features?.length !== 1) throw new Error('Importa una sola ruta por archivo.');
            g = g.features[0];
        }
        if (g?.type === 'Feature') g = g.geometry;
        if (
            g?.type !== 'LineString' ||
            !Array.isArray(g.coordinates) ||
            g.coordinates.length < 2 ||
            g.coordinates.length > 10000
        )
            throw new Error('Se requiere un LineString de 2 a 10.000 puntos.');
        let previous;
        for (const p of g.coordinates) {
            if (
                !Array.isArray(p) ||
                p.length !== 2 ||
                !p.every(Number.isFinite) ||
                Math.abs(p[0]) > 180 ||
                Math.abs(p[1]) > 90
            )
                throw new Error('Coordenadas inválidas: usa [longitud, latitud] en WGS84.');
            if (previous && p[0] === previous[0] && p[1] === previous[1])
                throw new Error('Hay puntos consecutivos duplicados.');
            previous = p;
        }
        if (g.crs) throw new Error('No se admite un CRS alternativo. Usa WGS84.');
        return { type: 'LineString', coordinates: g.coordinates.map((p) => [...p]) };
    }
    function feature(route) {
        return {
            type: 'Feature',
            properties: {
                codigo: route.codigo,
                nombre: route.nombre,
                origen: route.origen,
                destino: route.destino,
                fuente: route.fuente,
                validada: route.validada,
                version: route.version,
            },
            geometry: route.recorrido,
        };
    }
    function createMap(id) {
        if (!window.L)
            throw new Error(
                'No se pudo cargar el mapa. Comprueba tu conexión y recarga la página.',
            );
        const map = L.map(id, { preferCanvas: true, maxBounds: cityBounds, maxBoundsViscosity: 0.45, minZoom: 11 }).setView(center, 13);
        const tiles = L.tileLayer('https://tile.openstreetmap.org/{z}/{x}/{y}.png', {
            maxZoom: 19,
            attribution:
                '&copy; <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a>',
        }).addTo(map);
        let reported = false;
        tiles.on('tileerror', () => {
            if (reported) return;
            reported = true;
            const note = document.createElement('div');
            note.className = 'map-notice';
            note.textContent =
                'La cartografía no está disponible. El recorrido puede seguir consultándose.';
            document.getElementById(id).appendChild(note);
        });
        new ResizeObserver(() => map.invalidateSize()).observe(document.getElementById(id));
        return map;
    }
    const endpoint = (kind) =>
        L.divIcon({
            className: 'endpoint-wrapper',
            iconSize: [34, 34],
            iconAnchor: [17, 17],
            html:
                '<div class="endpoint-marker ' +
                kind +
                '">' +
                (kind === 'start' ? 'A' : 'B') +
                '</div>',
        });
    return { api, text, download, geometry, feature, createMap, endpoint, center, cityBounds };
})();
