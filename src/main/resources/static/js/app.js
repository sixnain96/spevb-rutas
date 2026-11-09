'use strict';
(() => {
    const $ = (id) => document.getElementById(id);
    const status = (message, type = 'info') => {
        $('statusMessage').textContent = message;
        $('statusMessage').className = 'status ' + type;
    };
    // El mapa se crea de forma asíncrona (Google carga su SDK): todo lo demás arranca cuando está listo y UNA sola vez.
    Spevb.conMapa('map', (map) => {
    const bus = Spevb.createBusPlayer(map);
    // Los paraderos de la ruta ya no se dibujan solos: aparecen al pedirlos en sus detalles («Ver en el mapa») o al tocar uno de la lista.
    const layers = map.group().addTo(map),
        stops = map.group();
    const showRouteStops = (on) => {
        if (on && !map.has(stops)) stops.addTo(map);
        if (!on && map.has(stops)) map.quitar(stops);
        $('toggleRouteStops').setAttribute('aria-pressed', String(on));
        $('toggleRouteStops').textContent = on ? 'Ocultar del mapa' : 'Ver en el mapa';
    };
    const routeCache = new Map();
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
        const favs = new Set((Spevb.Datos ? Spevb.Datos.leer('rutas') : []).map((f) => f.id));
        const filtered = (routes || []).filter(
            (r) =>
                normalize([r.codigo, Spevb.rutaPopular(r.codigo), r.nombre, r.origen, r.destino].join(' ')).includes(query) &&
                (filter !== 'favorites' || favs.has(r.codigo)),
        );
        // Las rutas favoritas (★) van primero.
        filtered.sort((a, b) => Number(favs.has(b.codigo)) - Number(favs.has(a.codigo)));
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
            code.textContent = (Spevb.rutaPopular(r.codigo) ? Spevb.rutaPopular(r.codigo) + ' (' + r.codigo + ')' : r.codigo) + ' · ' + (r.sentido || 'IDA');
            const name = document.createElement('strong');
            name.textContent = r.nombre;
            const summary = document.createElement('small');
            summary.textContent = r.origen + ' → ' + r.destino;
            info.append(code, name, summary);
            button.append(dot, info);
            button.addEventListener('click', () => loadRoute(r.id));
            const row = document.createElement('div');
            row.className = 'route-row';
            row.append(button);
            if (Spevb.Datos) {
                const star = document.createElement('button');
                star.type = 'button';
                const on = favs.has(r.codigo);
                star.className = 'route-star' + (on ? ' on' : '');
                star.replaceChildren(window.Spevb.Iconos.el('estrella', { tam: 20 }));
                star.setAttribute('aria-pressed', String(on));
                star.setAttribute('aria-label', (on ? 'Quitar de favoritas la ruta ' : 'Marcar como favorita la ruta ') + r.codigo);
                star.title = on ? 'Quitar de favoritas' : 'Marcar como favorita';
                star.addEventListener('click', () => {
                    const lista = Spevb.Datos.leer('rutas').filter((f) => f.id !== r.codigo);
                    if (!on) {
                        if (lista.length >= 100) return Spevb.toast('Máximo 100 rutas favoritas.', { tipo: 'info', ms: 4000 });
                        lista.unshift({ id: r.codigo, nombre: String(r.nombre || '').slice(0, 80) });
                    }
                    Spevb.Datos.escribir('rutas', lista);
                });
                row.append(star);
            }
            $('routeList').append(row);
        });
        if (!filtered.length) {
            const empty = document.createElement('p');
            empty.className = 'empty';
            empty.textContent = 'No hay rutas para esta búsqueda.';
            $('routeList').append(empty);
        }
    }
    /** Si una ruta favorita cambió de código, el código anterior se resuelve (alias) y el favorito se actualiza al vigente. */
    async function migrarFavoritos() {
        try {
            if (!Spevb.Datos) return;
            const vigentes = new Set(routes.map((r) => r.codigo)), favs = Spevb.Datos.leer('rutas');
            const viejos = favs.filter((f) => f && f.id && !vigentes.has(f.id));
            if (!viejos.length) return;
            const nueva = favs.slice();
            let cambio = false;
            for (const f of viejos) {
                try {
                    const r = await Spevb.api('/api/rutas/codigo/' + encodeURIComponent(f.id));
                    if (r && r.codigo && r.codigo !== f.id) { nueva[nueva.indexOf(f)] = { ...f, id: r.codigo, nombre: String(r.nombre || f.nombre || '').slice(0, 80) }; cambio = true; }
                } catch { /* 404 u otro fallo: el favorito se conserva tal cual */ }
            }
            if (cambio) Spevb.Datos.escribir('rutas', nueva.filter((f, i) => nueva.findIndex((g) => g.id === f.id) === i));
        } catch { /* sin almacenamiento */ }
    }
    let reintentoRutas = null;
    async function loadRoutes() {
        $('retryRoutes').classList.add('hidden');
        status('Cargando rutas…');
        try {
            const lista = await Spevb.api('/api/rutas');
            // Una respuesta que no es la lista (página de aviso de un túnel, respuesta vacía del service worker sin red…) no debe dejar `routes` en null:
            // antes eso rompía «Buscar rutas» con «null is not an object (evaluating 'routes.filter')».
            if (!Array.isArray(lista)) throw new Error('El servidor no devolvió la lista de rutas (revisa la conexión o el túnel).');
            routes = lista;
            renderList();
            migrarFavoritos();
            status(routes.length + ' rutas disponibles.', 'success');
        } catch (error) {
            routes = Array.isArray(routes) ? routes : [];
            status(error.message, 'error');
            $('retryRoutes').classList.remove('hidden');
            clearTimeout(reintentoRutas);
            reintentoRutas = setTimeout(() => { if (!routes.length) loadRoutes(); }, 5000);      // un reintento automático por si fue un tropiezo de la conexión
        }
    }
    function fit() {
        const caja = routeLayer?.getBounds();
        if (caja) Spevb.fitMap(map, caja, { maxZoom: 17 });
    }
    /** Quita de la dirección SOLO el parámetro {@code ruta} (conserva cualquier otro, p. ej. un viaje compartido). */
    function quitarRutaDeLaUrl() {
        try {
            const params = new URLSearchParams(location.search);
            if (!params.has('ruta')) return;
            params.delete('ruta');
            const resto = params.toString();
            history.replaceState(null, '', location.pathname + (resto ? '?' + resto : '') + location.hash);
        } catch { /* sin history */ }
    }
    /**
     * Deja el explorador sin recorrido: aborta la petición pendiente, invalida su secuencia (una respuesta tardía ya no pinta nada),
     * detiene el bus y quita marcador, estela y controles, capas, paraderos, ficha y {@code ?ruta=}. No mueve el mapa ni toca pines,
     * GPS, favoritos, historial ni viajes guardados.
     */
    function clearRoute() {
        controller?.abort();
        controller = null;
        sequence++;
        selected = null;
        bus.stop();
        layers.clear();
        stops.clear();
        showRouteStops(false);
        routeLayer = null;
        $('stopsList').replaceChildren();
        $('routeCard').classList.add('hidden');
        $('fitRoute').disabled = true;
        quitarRutaDeLaUrl();
        renderList();
        if (routes.length) status(routes.length + ' rutas disponibles.', 'success');
    }
    /**
     * {@code fit: false}: quien llama encuadra el mapa por su cuenta (evita dos animaciones seguidas que se pisan).
     * {@code updateHistory: false}: la ruta se carga como parte de un viaje A→B y no debe escribir {@code ?ruta=} en la dirección.
     * {@code source}: quién la pidió (el planificador pasa 'planner'); viaja en el evento spevb-route-change.
     * {@code completa: false}: no se dibuja el recorrido entero ni sus extremos «Inicio»/«Fin»; quien llama dibuja solo el tramo que la persona
     * va a recorrer (así no queda una línea larga sin explicar). El explorador de rutas sí la dibuja completa.
     */
    async function loadRoute(id, { fit: encuadrar = true, updateHistory = true, source = 'explorer', completa = true } = {}) {
        window.dispatchEvent(new CustomEvent('spevb-route-change', { detail: { source } }));
        controller?.abort();
        controller = new AbortController();
        const current = ++sequence;
        selected = null;
        bus.stop();
        layers.clear();
        stops.clear();
        showRouteStops(false);
        routeLayer = null;
        $('routeCard').classList.add('hidden');
        $('fitRoute').disabled = true;
        renderList();
        status('Cargando recorrido…');
        const cached = routeCache.get(String(id));
        const miControl = controller;
        const carga = cached && Date.now() - cached.at < 30000 ? null : Spevb.Carga.iniciar({
            contenedor: document.querySelector('.menu-route-browser'), ancla: $('statusMessage'), texto: 'Cargando…',
            etapa: 'Seguimos descargando el recorrido. Con señal lenta puede tardar un poco.',
            alCancelar: () => { miControl.abort(); status('Carga cancelada.', 'info'); },
            alReintentar: () => { miControl.abort(); loadRoute(id, { fit: encuadrar, updateHistory, source, completa }); },
        });
        try {
            const route = cached && Date.now() - cached.at < 30000
                ? cached.route
                : await Spevb.api('/api/rutas/' + id, { signal: controller.signal });
            routeCache.set(String(id), { route, at: Date.now() });
            if (routeCache.size > 12) routeCache.delete(routeCache.keys().next().value);
            if (current !== sequence) return;
            Spevb.geometry(route.recorrido);
            selected = route;
            const color = /^#[0-9a-f]{6}$/i.test(route.color) ? route.color : '#0b6e4f';
            // Mismas coordenadas y sin hit-testing; smoothFactor bajo para conservar la forma exacta de la línea.
            const latlngs = Spevb.Geo.desdeGeoJSON(route.recorrido.coordinates);
            const lineOptions = { interactive: false, smoothFactor: 0.4 };
            if (completa) {
                map.polyline(latlngs, { ...lineOptions, color: '#fff', weight: 11, opacity: 0.95 }).addTo(layers);
                routeLayer = map.polyline(latlngs, { ...lineOptions, color, weight: 6 }).addTo(layers);
            }
            bus.play(route.recorrido.coordinates, { color });
            if (completa) [
                ['inicio', 'origen', 'start', 'Inicio'],
                ['fin', 'destino', 'end', 'Fin'],
            ].forEach(([key, name, kind, label]) => {
                const p = route[key]?.coordinates;
                if (!p) return;
                map.marker([p[1], p[0]], {
                    ...Spevb.endpoint(kind),
                    z: 1000,
                    popup: Spevb.text(label + ': ' + route[name]),
                    tooltip: Spevb.text(label + ' · ' + route[name]),
                    tooltipOpciones: { permanente: true, direccion: 'top', offset: [0, -12], clase: 'route-end-tip' },
                }).addTo(layers);
            });
            $('stopsList').replaceChildren();
            (route.paraderos || []).forEach((stop) => {
                const p = stop.ubicacion.coordinates;
                const marker = map.dot([p[1], p[0]], {
                    radius: 6,
                    color: '#fff',
                    weight: 2,
                    fillColor: '#184b42',
                    fillOpacity: 1,
                    tooltip: Spevb.text(stop.secuencia + '. ' + stop.nombre),
                }).addTo(stops);
                const li = document.createElement('li'),
                    button = document.createElement('button');
                button.type = 'button';
                button.className = 'stop-button';
                button.textContent = stop.nombre;
                button.addEventListener('click', () => {
                    showRouteStops(true);
                    map.setView(marker.getPosition(), 17);
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
                mapSubtitle: Spevb.rutaNombre(route.codigo) + ' · ' + route.origen + ' → ' + route.destino,
            };
            Object.entries(texts).forEach(([key, value]) => ($(key).textContent = value ?? ''));
            $('routeColor').style.backgroundColor = color;
            $('stopsBlock').classList.toggle('hidden', !route.paraderos.length);
            $('routeWarnings').replaceChildren();
            route.advertencias.forEach((w) => {
                const li = document.createElement('li');
                li.textContent = w;
                $('routeWarnings').append(li);
            });
            $('routeWarnings').classList.toggle('hidden', !route.advertencias.length);
            $('routeCard').classList.remove('hidden');
            $('fitRoute').disabled = !routeLayer;
            renderList();
            if (encuadrar) fit();
            status('Mostrando ' + route.codigo + '.', 'success');
            if (updateHistory) history.replaceState(null, '', '?ruta=' + route.id);
            else quitarRutaDeLaUrl();
            return route;
        } catch (error) {
            if (error.name !== 'AbortError' && current === sequence) status(error.message, 'error');
        } finally {
            if (carga) carga.fin();
        }
    }
    window.SpevbExplorer = { map, showRoute: loadRoute, clearRoute, bus };
    Spevb.publicar('explorador', window.SpevbExplorer);
    $('routeSearch').addEventListener('input', renderList);
    $('validationFilter').addEventListener('change', renderList);
    window.addEventListener('spevb-datos', (e) => { if (e.detail.clave === 'rutas') renderList(); });
    $('retryRoutes').addEventListener('click', loadRoutes);
    $('fitRoute').addEventListener('click', fit);
    $('resetMap').addEventListener('click', () => map.setView(Spevb.center, 13));
    $('toggleRouteStops').addEventListener('click', () => showRouteStops(!map.has(stops)));
    $('exportRoute').addEventListener('click', () => {
        if (selected) Spevb.download(Spevb.feature(selected), selected.codigo + '.geojson');
    });
    loadRoutes().then(() => {
        const id = new URLSearchParams(location.search).get('ruta');
        if (/^\d+$/.test(id || '') && routes.some((r) => String(r.id) === id)) loadRoute(id);
    });
    }, { alFallar: (error) => status(error.message, 'error') });
})();
