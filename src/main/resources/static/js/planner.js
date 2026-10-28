'use strict';
(() => {
    const $ = (id) => document.getElementById(id);
    const explorer = window.SpevbExplorer;
    if (!explorer) return;

    const map = explorer.map;
    const pins = L.layerGroup().addTo(map);
    const tripLayers = L.layerGroup().addTo(map);
    const nearbyLayers = L.layerGroup().addTo(map);
    let origin = null;
    let destination = null;
    let picking = null;
    let sequence = 0;
    let planning = false;
    let controller;
    const lookups = { origin: 0, destination: 0 };
    const suggestTimers = { origin: null, destination: null };
    const suggestRequests = { origin: null, destination: null };
    const suggestedOptions = { origin: [], destination: [] };
    const activeSuggestion = { origin: -1, destination: -1 };
    const lastTyped = { origin: '', destination: '' };

    // Opción elegida en pantalla (para «Avisarme al bajar», compartir y reportar). Otros módulos la leen con Spevb.Planner.
    const Planner = (Spevb.Planner = { seleccion: null });
    const setSeleccion = (sel) => { Planner.seleccion = sel; window.dispatchEvent(new CustomEvent('spevb-seleccion')); };

    const status = (message, type = 'info') => {
        // Los errores también salen como aviso sobre el mapa: la línea de estado queda al fondo del panel y puede estar tapada.
        if (type === 'error') Spevb.toast(message, { tipo: 'error', ms: 12000, clave: 'estado' });
        else Spevb.toast.cerrar('estado');
        $('tripStatus').textContent = message;
        $('tripStatus').className = 'status ' + type;
    };
    const coordinates = (point) => [point.lat, point.lng];
    const distance = (meters) => meters < 1000
        ? Math.round(meters) + ' m'
        : (meters / 1000).toLocaleString('es-CO', { maximumFractionDigits: 1 }) + ' km';
    // Las líneas punteadas de caminata se dibujan en SVG (el mapa usa canvas, que no admite animación CSS) y se mueven muy
    // despacio en el sentido del trayecto: se ve el camino «avanzando» hacia el bus o el destino. Ver .walk-line en CSS.
    map.createPane('walkPane').style.zIndex = 450;
    const walkRenderer = L.svg({ pane: 'walkPane', padding: 0.3 });
    const walkStyle = { color: '#2f3640', weight: 5, opacity: 0.95, dashArray: '1 10', lineCap: 'round', smoothFactor: 0.3, renderer: walkRenderer, className: 'walk-line' };
    // Sustituye la línea recta por el trayecto peatonal por calles cuando el servidor lo calcula.
    // Si no hay respuesta, la línea recta aproximada permanece como respaldo.
    const walkCache = new Map();
    const walkKey = (a, b) => [a[0], a[1], b[0], b[1]].map((n) => n.toFixed(5)).join(',');
    // Un solo pedido por par de puntos: se reutiliza al cambiar de opción y se precarga al pasar el cursor.
    function getWalk(from, to) {
        const key = walkKey(from, to);
        if (!walkCache.has(key)) {
            const q = new URLSearchParams({ olat: from[0], olng: from[1], dlat: to[0], dlng: to[1] });
            const request = Spevb.api('/api/caminata?' + q).then((data) => { Spevb.Caminatas?.guardar(key, data); return data; })
                .catch((error) => {
                    walkCache.delete(key);
                    // Sin señal: se usa el trazado por calles que ya se había calculado para este mismo par de puntos.
                    const guardado = (error.offline || error.timeout) && Spevb.Caminatas?.leer(key);
                    if (guardado) return guardado;
                    throw error;
                });
            walkCache.set(key, request);
            if (walkCache.size > 60) walkCache.delete(walkCache.keys().next().value);
        }
        return walkCache.get(key);
    }
    // Sustituye la línea recta por el trayecto peatonal por calles cuando el servidor lo calcula.
    // Si no hay respuesta, la línea recta aproximada permanece como respaldo.
    const fallbackStyle = { color: '#c2410c', dashArray: '2 8', opacity: 0.8 };
    function streetWalk(line, from, to, layer, onResult, retried) {
        if (L.latLng(from).distanceTo(L.latLng(to)) < 30) return;
        getWalk(from, to).then((data) => {
            if (!layer.hasLayer(line) || !Array.isArray(data?.coordinates)) return;
            const path = data.coordinates.map((c) => [c[1], c[0]]);
            line.setStyle(walkStyle);
            line.getElement()?.classList.remove('walk-fallback');
            line.setLatLngs([from, ...path, to]);
            // Se suman los tramos rectos cortos entre cada punto y la calle más cercana.
            const metros = data.metros + L.latLng(from).distanceTo(L.latLng(path[0]))
                + L.latLng(path[path.length - 1]).distanceTo(L.latLng(to));
            line.setTooltipContent(Spevb.text('Caminar aprox. ' + distance(metros) + ' (≈ ' + data.minutos + ' min) por calles'));
            onResult?.({ ...data, metros });
        }).catch(() => {
            if (!layer.hasLayer(line)) return;
            // La red de calles puede estar cargándose: se reintenta una vez antes de dejar la línea de respaldo.
            if (!retried) { setTimeout(() => streetWalk(line, from, to, layer, onResult, true), 3500); return; }
            // Sin trazado por calles: la línea recta se marca distinta para que no parezca un camino real.
            line.setStyle(fallbackStyle);
            line.getElement()?.classList.add('walk-fallback');   // la línea recta de respaldo no se anima
            line.setTooltipContent(Spevb.text('Sin trazado por calles disponible: línea recta aproximada, NO es un camino real'));
        });
    }
    // En móvil el panel inferior tapa parte del mapa: se deja ese espacio libre al ajustar la vista.
    const sheetCover = () => parseFloat(getComputedStyle(document.documentElement).getPropertyValue('--sheet-visible')) || 0;
    const fitPaddingNow = () => Spevb.fitPadding(map);
    const endpointInput = (mode) => $(mode === 'origin' ? 'tripOrigin' : 'tripDestination');
    const endpointSearch = (mode) => $(mode === 'origin' ? 'searchOrigin' : 'searchDestination');

    function drawPins() {
        pins.clearLayers();
        [[origin, 'A', 'Origen'], [destination, 'B', 'Destino']].forEach(([point, letter, title]) => {
            if (!point) return;
            L.marker(coordinates(point), {
                icon: L.divIcon({
                    className: 'endpoint-wrapper',
                    iconSize: [38, 48],
                    iconAnchor: [19, 46],
                    tooltipAnchor: [0, -44],
                    html: '<div class="trip-pin ' + letter.toLowerCase() + '">' + letter + '</div>',
                }),
                zIndexOffset: 1200,
            }).addTo(pins).bindTooltip(Spevb.text(title + ': ' + point.nombre));
        });
        $('nearOriginRoutes').disabled = !origin;
    }

    function clearPlan() {
        sequence++;
        setSeleccion(null);
        controller?.abort();
        tripLayers.clearLayers();
        $('tripResults').replaceChildren();
        planning = false;
        $('planTrip').disabled = false;
    }

    function clearNearby() {
        nearbyLayers.clearLayers();
        $('nearbyResults').replaceChildren();
    }

    function pickMode(mode) {
        picking = mode;
        $('pickOrigin').setAttribute('aria-pressed', String(mode === 'origin'));
        $('pickDestination').setAttribute('aria-pressed', String(mode === 'destination'));
        map.getContainer().classList.toggle('picking-location', !!mode);
        if (mode) window.dispatchEvent(new CustomEvent('spevb-picking', { detail: { mode } }));
        if (mode) status(mode === 'origin' ? 'Toca en el mapa el punto exacto desde donde sales.' : 'Toca en el mapa el punto exacto a donde vas.', 'info');
        if (mode) {
            const touch = isTouchLayout();
            const where = mode === 'origin' ? 'sales (A)' : 'llegas (B)';
            Spevb.toast(touch ? 'Mueve el mapa hasta dejar el pin donde ' + where.replace(/ \(.\)/, '') + ' y pulsa «Confirmar»' : 'Toca el mapa: ahí ' + where,
                { tipo: 'info', ms: 0, clave: 'pick', accion: [
                    ...(touch ? [{ texto: 'Confirmar', alHacer: confirmCenter, cerrar: false }] : []),
                    { texto: 'Cancelar', alHacer: () => pickMode(null) }] });
            showCenterPin(touch);
        } else {
            Spevb.toast.cerrar('pick');
            showCenterPin(false);
        }
    }

    // En celulares y tabletas se elige con un pin fijo en el centro: se mueve el mapa y se confirma. Es más preciso y no
    // depende de acertar un toque con el dedo sobre el mapa.
    const isTouchLayout = () => window.matchMedia('(pointer: coarse)').matches || Spevb.compactLayout();
    let centerPin = null;
    function centerPoint() {
        const size = map.getSize();
        return L.point(size.x / 2, Math.max(40, (size.y - sheetCover()) / 2));
    }
    function placeCenterPin() {
        if (!centerPin) return;
        const p = centerPoint();
        centerPin.style.left = p.x + 'px';
        centerPin.style.top = p.y + 'px';
    }
    function showCenterPin(show) {
        if (show && !centerPin) {
            centerPin = document.createElement('div');
            centerPin.className = 'center-pin';
            centerPin.setAttribute('aria-hidden', 'true');
            centerPin.innerHTML = '<svg viewBox="0 0 24 32" width="40" height="53"><path d="M12 31S2 19.5 2 11.5a10 10 0 0 1 20 0C22 19.5 12 31 12 31z" fill="#176650" stroke="#fff" stroke-width="2"/><circle cx="12" cy="11.5" r="4" fill="#fff"/></svg>';
            map.getContainer().append(centerPin);
            window.addEventListener('resize', placeCenterPin);
        }
        if (centerPin) {
            centerPin.hidden = !show;
            if (show) { placeCenterPin(); setTimeout(placeCenterPin, 400); }   // el panel baja con una animación
        }
    }
    function confirmCenter() {
        if (!picking) return;
        const ll = map.containerPointToLatLng(centerPoint());
        lastPick = Date.now();
        reversePoint(picking, ll.lat, ll.lng);
    }

    /** Si A y B ya están elegidos y alguno quedó fuera de la parte visible del mapa, se ajusta la vista para ver los dos. */
    function showBothPoints() {
        if (!origin || !destination) return;
        const size = map.getSize(), cover = sheetCover(), mobile = Spevb.compactLayout();
        const visible = (p) => {
            const c = map.latLngToContainerPoint(coordinates(p));
            return c.x > (mobile ? 24 : 440) && c.x < size.x - (mobile ? 76 : 70) && c.y > 70 && c.y < size.y - (mobile ? cover + 30 : 130);
        };
        if (visible(origin) && visible(destination)) return;
        Spevb.fitMap(map, L.latLngBounds([coordinates(origin), coordinates(destination)]), { maxZoom: 17 });
    }

    function choose(mode, point) {
        clearTimeout(suggestTimers[mode]);
        suggestRequests[mode]?.abort();
        ++lookups[mode];
        if (mode === 'origin') {
            origin = point;
            clearNearby();
        } else {
            destination = point;
        }
        endpointInput(mode).value = point.nombre;
        $(mode + 'Results').replaceChildren();
        resetSuggestions(mode);
        clearPlan();
        drawPins();
        pickMode(null);
        if (Spevb.compactLayout() && document.activeElement?.tagName === 'INPUT') document.activeElement.blur();   // cierra el teclado
        window.dispatchEvent(new CustomEvent('spevb-point-selected'));
        setTimeout(showBothPoints, 350);      // espera a que el panel termine de bajar para medir el espacio libre
        const approximate = ['PREDIO_APROXIMADO', 'CALLE_APROXIMADA', 'VIA', 'SECTOR', 'INTERPOLADA', 'VECINA_APROXIMADA',
            'VIA_APROXIMADA', 'ESQUINA_APROXIMADA', 'BARRIO'].includes(point.precision);
        status(approximate
            ? 'Punto ubicado aproximadamente. Comprueba el marcador: puede ser el centro del predio o un lugar sobre la vía, no la entrada exacta.'
            : origin && destination
                ? 'Origen y destino listos. Pulsa «Encontrar rutas cercanas».'
                : 'Punto seleccionado. Ahora selecciona el otro extremo.', approximate ? 'info' : 'success');
    }


    function resetSuggestions(mode) {
        suggestedOptions[mode] = [];
        activeSuggestion[mode] = -1;
        const box = $(mode + 'Results');
        box.classList.remove('suggestion-open');
        endpointInput(mode).setAttribute('aria-expanded', 'false');
        endpointInput(mode).removeAttribute('aria-activedescendant');
    }

    function setActiveSuggestion(mode, index) {
        const options = suggestedOptions[mode];
        if (!options.length) return;
        activeSuggestion[mode] = (index + options.length) % options.length;
        const box = $(mode + 'Results');
        Array.from(box.querySelectorAll('.autosuggest-option')).forEach((item, i) => {
            item.classList.toggle('keyboard-selected', i === activeSuggestion[mode]);
            item.setAttribute('aria-selected', String(i === activeSuggestion[mode]));
        });
        endpointInput(mode).setAttribute('aria-activedescendant', mode + '-suggestion-' + activeSuggestion[mode]);
    }

    function parseCoordinates(query) {
        const match = query.match(/^\s*(-?\d+(?:\.\d+)?)\s*,\s*(-?\d+(?:\.\d+)?)\s*$/);
        if (!match) return null;
        const lat = Number(match[1]);
        const lng = Number(match[2]);
        if (Math.abs(lat) > 90 || Math.abs(lng) > 180) throw new Error('Coordenadas fuera de rango. Usa latitud, longitud.');
        return { id: null, lat, lng, nombre: lat.toFixed(6) + ', ' + lng.toFixed(6), direccion: null, fuente: 'Coordenadas', confirmado: false };
    }

    const precisionLabel = (precision) => ({
        PREDIO_APROXIMADO: ' · Centro aproximado del predio (no puerta)',
        CONFIRMADA_POR_USUARIOS: ' · Ubicación confirmada por varias personas',
        VERIFICADO_LOCAL: ' · Punto confirmado por el equipo',
        LUGAR: ' · Lugar con nombre (© colaboradores de OpenStreetMap)',
        INTERPOLADA: ' · Estimada entre predios vecinos: confirma el pin',
        VECINA_APROXIMADA: ' · Cerca de un predio vecino: confirma el pin',
        VIA_APROXIMADA: ' · Zona aproximada de la vía: confirma el pin',
        ESQUINA_APROXIMADA: ' · Esquina aproximada',
        BARRIO: ' · Centro del barrio',
        CALLE_APROXIMADA: ' · Ubicación aproximada sobre la vía (NO es la casa)',
        DIRECCION_PUNTUAL: ' · Número de puerta informado por proveedor; verifica el pin',
    })[precision] || ' · Coincidencia sin precisión comprobada';

    function renderPlaceResults(mode, places) {
        const box = $(mode + 'Results');
        box.replaceChildren();
        resetSuggestions(mode);
        if (!places.length) {
            const note = document.createElement('p');
            note.className = 'address-warning';
            note.textContent = 'No se encontró una coincidencia a nivel de predio o número de puerta. Una avenida no equivale a la dirección exacta. Puedes usar «Elegir en mapa».';
            box.append(note);
        }
        places.forEach((place) => {
            const result = document.createElement('button');
            result.type = 'button';
            result.className = 'place-option rich-place';
            const title = document.createElement('strong');
            title.textContent = place.nombre;
            const meta = document.createElement('small');
            meta.textContent = (place.confirmado ? 'Confirmado por el equipo · ' : '') + (place.fuente || 'Ubicación') + precisionLabel(place.precision);
            result.append(title, meta);
            result.onclick = () => {
                choose(mode, place);
                map.setView(coordinates(place), 17);
            };
            box.append(result);
        });
    }

    // Sugerencia de la base propia: ya trae coordenadas, se ubica sin otra consulta.
    function pickLocalSuggestion(mode, item) {
        const point = {
            id: item.id || null, nombre: item.texto, direccion: item.texto + (item.detalle ? ', ' + item.detalle : ''),
            lat: item.lat, lng: item.lng, fuente: item.fuente, confirmado: false, precision: item.precision,
        };
        choose(mode, point);
        map.setView(coordinates(point), 17);
        if (item.id) Spevb.api('/api/lugares/elecciones', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ id: item.id }) }).catch(() => {});
    }

    async function lookup(mode, suggestion = null) {
        const input = endpointInput(mode);
        const button = endpointSearch(mode);
        if (suggestion && suggestion.lat != null) { pickLocalSuggestion(mode, suggestion); return; }
        if (suggestion) input.value = suggestion.texto;
        clearTimeout(suggestTimers[mode]);
        suggestRequests[mode]?.abort();
        resetSuggestions(mode);
        const query = input.value.trim();
        const current = ++lookups[mode];
        button.disabled = true;
        try {
            const point = parseCoordinates(query);
            if (point) {
                choose(mode, point);
                map.setView(coordinates(point), 17);
                return;
            }
            if (query.length < 3) throw new Error('Escribe al menos 3 caracteres para buscar.');
            status('Buscando únicamente en Villavicencio…');
            const url = '/api/lugares?q=' + encodeURIComponent(query)
                + (suggestion?.magicKey ? '&magicKey=' + encodeURIComponent(suggestion.magicKey) : '');
            const data = await Spevb.api(url);
            if (current !== lookups[mode] || input.value.trim() !== query) return;
            const lugares = data.lugares || [];
            // Con una sugerencia seleccionada y un único lugar identificable, ubicarlo
            // inmediatamente como en un buscador de mapas. Si hay ambigüedad, pedir elección.
            if (suggestion?.magicKey && lugares.length === 1
                    && ['LUGAR', 'DIRECCION_PUNTUAL'].includes(lugares[0].precision)) {
                choose(mode, lugares[0]);
                map.setView(coordinates(lugares[0]), 17);
                return;
            }
            if (lugares.length === 1 && ['PREDIO_APROXIMADO', 'CONFIRMADA_POR_USUARIOS', 'VERIFICADO_LOCAL'].includes(lugares[0].precision)) {
                choose(mode, lugares[0]);
                map.setView(coordinates(lugares[0]), 17);
                return;
            }
            renderPlaceResults(mode, lugares);
            const avisos = data.avisos || [];
            if (avisos.length) {
                const note = document.createElement('p');
                note.className = 'address-warning';
                note.textContent = avisos.join(' ');
                $(mode + 'Results').prepend(note);
            }
            if (data.lugares?.length) {
                status('Selecciona una coincidencia y comprueba su precisión. La vía o el barrio no equivalen al número de casa.', 'info');
            } else {
                status('No encontré la dirección con su número en las fuentes disponibles. Revisa los avisos y usa «Elegir en mapa» si es necesario.', 'info');
            }
        } catch (error) {
            if (current === lookups[mode]) status(error.message, 'error');
        } finally {
            button.disabled = false;
        }
    }

    // Si la persona escribió una dirección y luego fijó el punto a mano, esa pareja (texto → punto) ayuda a
    // mejorar la base: con varias confirmaciones coherentes el punto se usa en futuras búsquedas.
    function confirmTyped(mode, lat, lng) {
        const text = lastTyped[mode];
        lastTyped[mode] = '';
        if (text.length < 6) return;
        Spevb.api('/api/lugares/confirmaciones', {
            method: 'POST', headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ consulta: text, lat, lng }),
        }).catch(() => {});
    }

    async function reversePoint(mode, lat, lng) {
        const fallback = {
            id: null,
            lat,
            lng,
            nombre: 'Punto en mapa · ' + lat.toFixed(6) + ', ' + lng.toFixed(6),
            direccion: null,
            fuente: 'Selección manual en mapa',
            confirmado: false,
        };
        status('Confirmando el punto seleccionado…');
        try {
            // Si el buscador de nombres tarda, no se deja esperando a la persona: pasados 8 s se usan las coordenadas.
            const limit = new AbortController();
            const timer = setTimeout(() => limit.abort(), 8000);
            let place;
            try {
                place = await Spevb.api('/api/lugares/reverso?lat=' + encodeURIComponent(lat) + '&lng=' + encodeURIComponent(lng), { signal: limit.signal });
            } finally {
                clearTimeout(timer);
            }
            choose(mode, place ? { ...place, lat, lng } : fallback);
            confirmTyped(mode, lat, lng);
        } catch (error) {
            // La geocodificación inversa es sólo una ayuda de nombre. El punto exacto del mapa
            // sigue siendo completamente válido para calcular rutas aunque Nominatim falle.
            choose(mode, fallback);
            confirmTyped(mode, lat, lng);
            status('Punto guardado por coordenadas. El buscador de nombres no respondió, pero esto no impide calcular rutas.', 'success');
        }
    }

    async function suggest(mode, query, current) {
        if (query.length < 3) return;
        suggestRequests[mode]?.abort();
        const request = new AbortController();
        suggestRequests[mode] = request;
        try {
            const data = await Spevb.api('/api/lugares/sugerencias?q=' + encodeURIComponent(query), { signal: request.signal });
            if (current !== lookups[mode] || endpointInput(mode).value.trim() !== query) return;
            const box = $(mode + 'Results');
            box.replaceChildren();
            resetSuggestions(mode);
            if (!data.arcgisConfigurado && !data.direccionesLocales) return; // El botón de búsqueda manual siempre sigue disponible.
            const suggestions = (data.sugerencias || []).slice(0, 6);
            if (!suggestions.length) {
                const hint = document.createElement('p');
                hint.className = 'search-hint';
                hint.textContent = 'Sin sugerencias por ahora. Pulsa «Buscar dirección» para consultar también el catastro.';
                box.append(hint);
                return;
            }
            suggestedOptions[mode] = suggestions;
            box.classList.add('suggestion-open');
            endpointInput(mode).setAttribute('aria-expanded', 'true');
            suggestions.forEach((item, index) => {
                const button = document.createElement('button');
                button.type = 'button';
                button.id = mode + '-suggestion-' + index;
                button.className = 'place-option rich-place autosuggest-option';
                button.setAttribute('role', 'option');
                button.setAttribute('aria-selected', 'false');
                const label = document.createElement('strong');
                label.textContent = item.texto;
                const meta = document.createElement('small');
                meta.textContent = item.lat != null ? (item.detalle || '') : 'ArcGIS · seleccionar y ubicar en el mapa';
                button.append(label, meta);
                button.onclick = () => lookup(mode, item);
                box.append(button);
            });
        } catch (error) {
            if (error?.name !== 'AbortError') {
                // Sin conexión con ArcGIS, se mantiene la búsqueda manual con catastro.
            }
        } finally {
            if (suggestRequests[mode] === request) suggestRequests[mode] = null;
        }
    }

    ['origin', 'destination'].forEach((mode) => {
        const pick = $(mode === 'origin' ? 'pickOrigin' : 'pickDestination');
        endpointSearch(mode).onclick = () => lookup(mode);
        pick.onclick = () => {
            const next = picking === mode ? null : mode;
            pickMode(next);
            if (next) map.getContainer().scrollIntoView({ behavior: 'smooth', block: 'center' });
        };
        const input = endpointInput(mode);
        input.addEventListener('keydown', (e) => {
            if (e.key === 'ArrowDown' && suggestedOptions[mode].length) {
                e.preventDefault();
                setActiveSuggestion(mode, activeSuggestion[mode] + 1);
            } else if (e.key === 'ArrowUp' && suggestedOptions[mode].length) {
                e.preventDefault();
                setActiveSuggestion(mode, activeSuggestion[mode] < 0 ? suggestedOptions[mode].length - 1 : activeSuggestion[mode] - 1);
            } else if (e.key === 'Escape') {
                clearTimeout(suggestTimers[mode]);
                suggestRequests[mode]?.abort();
                $(mode + 'Results').replaceChildren();
                resetSuggestions(mode);
            } else if (e.key === 'Enter') {
                e.preventDefault();
                const selected = suggestedOptions[mode][activeSuggestion[mode]];
                lookup(mode, selected || null);
            }
        });
        input.addEventListener('input', () => {
            clearTimeout(suggestTimers[mode]);
            suggestRequests[mode]?.abort();
            resetSuggestions(mode);
            lastTyped[mode] = input.value.trim();
            const current = ++lookups[mode];
            if (mode === 'origin') {
                origin = null;
                clearNearby();
            } else {
                destination = null;
            }
            $(mode + 'Results').replaceChildren();
            clearPlan();
            drawPins();
            status('Busca la dirección o usa «Elegir en mapa» para confirmar el punto exacto.');
            const query = input.value.trim();
            if (query.length >= 3) suggestTimers[mode] = setTimeout(() => suggest(mode, query, current), 450);
        });
    });

    // Elegir punto en el mapa. Además del «click» de Leaflet (que descarta toques con un leve movimiento del dedo)
    // se detecta el toque directamente: en celulares reales el dedo tiembla y Leaflet lo toma por un arrastre.
    let lastPick = 0;
    const pickAt = (latlng) => {
        if (!picking || Date.now() - lastPick < 700) return;
        lastPick = Date.now();
        reversePoint(picking, latlng.lat, latlng.lng);
    };
    map.on('click', (e) => pickAt(e.latlng));
    let tapStart = null;
    map.getContainer().addEventListener('pointerdown', (e) => {
        tapStart = e.isPrimary ? { x: e.clientX, y: e.clientY, t: performance.now() } : null;
    });
    map.getContainer().addEventListener('pointerup', (e) => {
        const start = tapStart;
        tapStart = null;
        if (!start || !picking) return;
        if (Math.hypot(e.clientX - start.x, e.clientY - start.y) > 22 || performance.now() - start.t > 900) return;   // fue un arrastre o una pulsación larga
        if (e.target.closest('.leaflet-control, .leaflet-marker-icon, .map-fabs, .stops-toggle, button, a')) return;
        pickAt(map.mouseEventToLatLng(e));
    });

    // ---- Mi ubicación (GPS): lectura de buena calidad, marcador con círculo de precisión y seguimiento opcional.
    const gpsLayer = L.layerGroup().addTo(map);
    let gpsMarker = null, gpsCircle = null, gpsFollow = null, gpsBusy = false;
    const gpsQuality = (f) => 'precisión ' + Spevb.Gps.calidad(f.precision).texto + ' (±' + Math.round(f.precision) + ' m)';
    /** Mensaje del GPS: en la línea de estado y como aviso sobre el mapa (los errores salen por status). */
    const gpsSay = (message, type = 'info', ms = 7000) => {
        status(message, type);
        if (type !== 'error') Spevb.toast(message, { tipo: type, ms, clave: 'gps' });
        else Spevb.toast.cerrar('gps');
    };
    const gpsProblem = (error) => error.message + (error.ayuda ? ' ' + error.ayuda : '');

    function drawGps(f, center) {
        const ll = [f.lat, f.lng];
        if (!gpsCircle) {
            gpsCircle = L.circle(ll, { radius: f.precision, color: '#1a73e8', weight: 1, fillColor: '#1a73e8', fillOpacity: 0.12, interactive: false }).addTo(gpsLayer);
            gpsMarker = L.marker(ll, {
                icon: L.divIcon({ className: 'gps-dot-wrapper', html: '<div class="gps-arrow hidden"></div><div class="gps-dot"></div>', iconSize: [22, 22], iconAnchor: [11, 11] }),
                zIndexOffset: 900, keyboard: false,
            }).addTo(gpsLayer).bindTooltip('', { direction: 'top', offset: [0, -10] });
        } else {
            gpsCircle.setLatLng(ll).setRadius(f.precision);
            gpsMarker.setLatLng(ll);
        }
        gpsMarker.setTooltipContent(Spevb.text('Estás aquí · ' + gpsQuality(f)));
        const arrow = gpsMarker.getElement()?.querySelector('.gps-arrow');
        if (arrow) {
            const moving = f.rumbo != null && (f.velocidad ?? 0) > 1;
            arrow.classList.toggle('hidden', !moving);
            if (moving) arrow.style.transform = 'rotate(' + f.rumbo + 'deg)';
        }
        if (center) Spevb.fitMap(map, gpsCircle.getBounds(), { maxZoom: 17 });
    }

    /** Comprueba soporte, HTTPS y permiso antes de pedir la ubicación; lanza un error con ayuda si algo lo impide. */
    async function gpsReady() {
        const problem = Spevb.Gps.problema();
        if (problem) throw problem;
        const state = await Spevb.Gps.permiso();
        if (state === 'denied') throw Spevb.Gps.errorGps({ code: 1 });
        gpsSay(state === 'prompt' ? 'Tu navegador te pedirá permiso para usar la ubicación: elige «Permitir».' : 'Buscando tu ubicación…', 'info', 0);
    }

    $('useMyLocation').onclick = async () => {
        if (gpsBusy) return;
        gpsBusy = true;
        $('useMyLocation').disabled = true;
        try {
            await gpsReady();
            const fix = await Spevb.Gps.obtener({ objetivo: 40, onProgreso: (l) => { gpsSay('Mejorando la precisión… ' + gpsQuality(l), 'info', 0); drawGps(l, false); } });
            drawGps(fix, true);
            if (!Spevb.Gps.dentroDeCiudad(fix.lat, fix.lng)) {
                gpsSay('Tu ubicación (' + fix.lat.toFixed(4) + ', ' + fix.lng.toFixed(4) + ') está fuera de Villavicencio. Escribe una dirección o usa «Elegir en el mapa».', 'error');
                return;
            }
            if (fix.precision > 1000) {
                gpsSay('Solo se obtuvo una ubicación muy aproximada (±' + Math.round(fix.precision) + ' m), típica de un computador sin GPS. No se usó como origen: elige el punto en el mapa o escribe la dirección.', 'error');
                return;
            }
            gpsSay('Ubicación encontrada (' + gpsQuality(fix) + '). Confirmando el punto…', 'info', 0);
            await reversePoint('origin', fix.lat, fix.lng);
            gpsSay('Tu ubicación quedó como origen · ' + gpsQuality(fix)
                + (fix.precision > 150 ? '. Es aproximada (común en computadores sin GPS): si no coincide, usa «Elegir en el mapa».' : '.'),
                fix.precision > 150 ? 'info' : 'success');
        } catch (error) {
            gpsSay(gpsProblem(error.codigo ? error : Spevb.Gps.errorGps(error)), 'error');
        } finally {
            gpsBusy = false;
            $('useMyLocation').disabled = false;
        }
    };

    // Botón flotante: seguir mi ubicación en el mapa mientras me muevo (un segundo toque lo detiene).
    const locateButton = $('locateMe');
    function stopFollowing() {
        gpsFollow?.detener();
        gpsFollow = null;
        locateButton?.setAttribute('aria-pressed', 'false');
    }
    if (locateButton) locateButton.onclick = async () => {
        if (gpsFollow) { stopFollowing(); gpsSay('Seguimiento de tu ubicación detenido.'); return; }
        try {
            await gpsReady();
        } catch (error) {
            gpsSay(gpsProblem(error), 'error');
            return;
        }
        locateButton.setAttribute('aria-pressed', 'true');
        let first = true, lost = false;
        gpsFollow = Spevb.Gps.seguir({
            onLectura: (l) => {
                drawGps(l, first);
                if (lost) { lost = false; gpsSay('Señal recuperada · ' + gpsQuality(l) + '.', 'success'); }
                if (first) {
                    first = false;
                    gpsSay(Spevb.Gps.dentroDeCiudad(l.lat, l.lng)
                        ? 'Siguiendo tu ubicación · ' + gpsQuality(l) + '. Toca el botón de nuevo para detener.'
                        : 'Estás fuera de Villavicencio. Tu posición se muestra en el mapa.', 'info');
                }
            },
            onAviso: () => { lost = true; gpsSay('Señal de GPS débil o perdida: sigo intentando. Si estás bajo techo, acércate a una ventana.', 'info', 0); },
            onError: (error) => { stopFollowing(); gpsSay(gpsProblem(error), 'error'); },
        });
    };

    $('swapTrip').onclick = () => {
        [origin, destination] = [destination, origin];
        const a = $('tripOrigin').value;
        $('tripOrigin').value = $('tripDestination').value;
        $('tripDestination').value = a;
        lookups.origin++;
        lookups.destination++;
        $('originResults').replaceChildren();
        $('destinationResults').replaceChildren();
        clearPlan();
        clearNearby();
        drawPins();
        status('Origen y destino intercambiados. Vuelve a calcular las rutas.');
    };

    $('onlyValidatedTrips').addEventListener('change', () => { clearPlan(); clearNearby(); });
    window.addEventListener('spevb-route-change', () => tripLayers.clearLayers());

    function showNearby(item, card) {
        return explorer.showRoute(item.rutaId, { fit: false }).then((loaded) => {
            if (!loaded) return;
            nearbyLayers.clearLayers();
            document.querySelectorAll('.nearby-card.selected').forEach((c) => c.classList.remove('selected'));
            card.classList.add('selected');
            const p = item.puntoCercano.coordinates;
            const nearLine = L.polyline([coordinates(origin), [p[1], p[0]]], walkStyle)
                .bindTooltip(Spevb.text('Aprox. ' + distance(item.distanciaMetros) + (item.caminataPorCalles ? ' caminando' : ' en línea recta') + ' hasta la ruta'))
                .addTo(nearbyLayers);
            streetWalk(nearLine, coordinates(origin), [p[1], p[0]], nearbyLayers);
            L.circleMarker([p[1], p[0]], { radius: 8, color: '#8c5612', weight: 3, fillColor: '#ffd17c', fillOpacity: 1 })
                .bindTooltip(Spevb.text(item.caminataPorCalles ? 'Mejor punto para subir (el más cercano caminando)' : 'Punto más cercano de la ruta')).addTo(nearbyLayers);
            const bounds = L.latLngBounds([coordinates(origin), [p[1], p[0]]]);
            Spevb.fitMap(map, bounds, { maxZoom: 16 });
        });
    }

    $('nearOriginRoutes').onclick = async () => {
        if (!origin) return;
        clearNearby();
        $('nearOriginRoutes').disabled = true;
        status('Buscando las rutas más cercanas al origen…');
        try {
            // Sin radio: el servidor devuelve las rutas más cercanas, estén a la distancia que estén.
            const data = await Spevb.api('/api/rutas/cercanas?lat=' + encodeURIComponent(origin.lat)
                + '&lng=' + encodeURIComponent(origin.lng)
                + '&soloValidadas=' + $('onlyValidatedTrips').checked);
            if (!data.length) {
                status('Todavía no hay recorridos registrados.', 'info');
                return;
            }
            const cards = data.map((item, index) => {
                const card = document.createElement('div');
                card.className = 'nearby-card';
                const title = document.createElement('strong');
                title.textContent = (index === 0 ? 'Más cercana · ' : '') + item.codigo + ' · ' + item.nombre;
                const detail = document.createElement('small');
                detail.textContent = distance(item.distanciaMetros) + (item.caminataPorCalles ? ' caminando desde el origen · ' : ' desde el origen · ') + item.origen + ' → ' + item.destino;
                const button = document.createElement('button');
                button.type = 'button';
                button.className = 'secondary';
                button.textContent = 'Ver en el mapa';
                button.onclick = () => showNearby(item, card);
                card.append(title, detail, button);
                $('nearbyResults').append(card);
                return card;
            });
            await showNearby(data[0], cards[0]);
            status('La ruta más cercana al origen es ' + data[0].codigo + ', a ' + distance(data[0].distanciaMetros) + '. Se muestra en el mapa.', 'success');
        } catch (error) {
            status(error.message, 'error');
        } finally {
            $('nearOriginRoutes').disabled = !origin;
        }
    };

    // ---- rótulos sobre el mapa: «Sube aquí», «Baja aquí», insignias por tramo y el aviso del cambio de bus
    const daltonico = () => document.documentElement.classList.contains('daltonico');
    const html = (tag, cls, text) => { const e = document.createElement(tag); if (cls) e.className = cls; if (text != null) e.textContent = text; return e; };
    function etiqueta(latlng, texto, cls, anchor) {
        const m = L.marker(latlng, { interactive: false, keyboard: false, zIndexOffset: 900, icon: L.divIcon({
            className: 'map-tag-wrap', html: html('div', 'map-tag ' + cls, texto), iconSize: [0, 0], iconAnchor: anchor || [0, 0] }) });
        m._tag = true;
        return m;
    }
    /** Insignia en medio de un tramo de bus: número del bus + código de la ruta, en el color del tramo. */
    function insigniaTramo(latlngs, numero, codigo, color) {
        const mid = latlngs[Math.floor(latlngs.length / 2)];
        const box = html('div', 'leg-badge');
        box.style.setProperty('--c', color);
        box.append(html('b', null, String(numero)), html('span', null, codigo));
        return L.marker(mid, { interactive: false, keyboard: false, zIndexOffset: 800, icon: L.divIcon({ className: 'map-tag-wrap', html: box, iconSize: [0, 0], iconAnchor: [0, 0] }) });
    }
    /** El «cuadro» del transbordo: de qué bus a qué bus se cambia, justo en el punto donde se baja. */
    function avisoCambio(latlng, de, a, colorDe, colorA) {
        const box = html('div', 'swap-callout');
        const chip = (t, c) => { const e = html('span', 'swap-chip', t); e.style.background = c; return e; };
        const fila = html('div', 'swap-row');
        fila.append(chip(de, colorDe), html('span', 'swap-arrow', '➜'), chip(a, colorA));
        box.append(html('small', 'swap-title', 'CAMBIO DE BUS'), fila, html('small', 'swap-sub', 'Bájate de ' + de + ' y sube a ' + a));
        const m = L.marker(latlng, { interactive: false, keyboard: false, zIndexOffset: 1100, icon: L.divIcon({ className: 'map-tag-wrap', html: box, iconSize: [0, 0], iconAnchor: [0, 0] }) });
        m._tag = true;
        return m;
    }
    /** Los rótulos nunca se salen de la pantalla: si el punto queda cerca de un borde, el rótulo se corre hacia adentro (la flecha sigue señalando el punto). */
    function acomodarRotulos() {
        const size = map.getSize();
        tripLayers.eachLayer((l) => {
            if (!l._tag) return;
            const el = l.getElement() && l.getElement().firstElementChild;
            if (!el) return;
            const w = el.offsetWidth, x = map.latLngToContainerPoint(l.getLatLng()).x, derecha = size.x - 72;   // 72 px: columna de botones flotantes
            if (el.classList.contains('map-tag')) {            // «Sube / Bájate aquí»: a la derecha del punto, o a la izquierda si no cabe
                el.classList.toggle('flip', x + 16 + w > derecha);
                return;
            }
            const dx = Math.max(0, 8 - (x - w / 2)) - Math.max(0, (x + w / 2) - derecha);
            el.style.setProperty('--dx', dx + 'px');
        });
    }
    map.on('moveend zoomend resize', acomodarRotulos);

    function showOption(option, start, finish, current, card, scroll) {
        return explorer.showRoute(option.rutaId, { fit: false }).then((loaded) => {
            if (!loaded || current !== sequence) return;
            tripLayers.clearLayers();
            document.querySelectorAll('.trip-option.selected').forEach((c) => c.classList.remove('selected'));
            card.classList.add('selected');
            const a = option.puntoSubida.coordinates;
            const b = option.puntoBajada.coordinates;
            setSeleccion({ codigos: [option.codigo], paradas: [{ lat: b[1], lng: b[0], texto: 'Bájate de la ruta ' + option.codigo }] });
            // Tramo del viaje en rojo con borde blanco: no se confunde con las calles naranja del mapa base.
            const tramoLatLngs = L.GeoJSON.coordsToLatLngs(option.tramo.coordinates, 0);
            L.polyline(tramoLatLngs, { color: '#fff', weight: 13, opacity: 0.95, interactive: false, smoothFactor: 0.4 }).addTo(tripLayers);
            const segment = L.polyline(tramoLatLngs,
                { color: '#e53935', weight: 8, opacity: 1, interactive: false, smoothFactor: 0.4 }).addTo(tripLayers);
            explorer.bus.play(option.tramo.coordinates, { color: option.color });
            const walkIn = L.polyline([coordinates(start), [a[1], a[0]]], walkStyle)
                .bindTooltip(Spevb.text('Caminar aprox. ' + distance(option.acercamientoInicioMetros) + (option.caminataPorCalles ? ' caminando' : ' en línea recta') + ' hasta la ruta'))
                .addTo(tripLayers);
            const walkOut = L.polyline([[b[1], b[0]], coordinates(finish)], walkStyle)
                .bindTooltip(Spevb.text('Caminar aprox. ' + distance(option.acercamientoFinMetros) + (option.caminataPorCalles ? ' caminando' : ' en línea recta') + ' hasta el destino'))
                .addTo(tripLayers);
            // El servidor ya calculó por calles los dos tramos a pie de la mejor opción: se dibujan sin más consultas.
            [[option.caminataInicioRuta, coordinates(start), [a[1], a[0]]], [option.caminataFinRuta, [b[1], b[0]], coordinates(finish)]].forEach(([leg, from, to]) => {
                if (leg?.coordinates?.length) walkCache.set(walkKey(from, to), Promise.resolve({ coordinates: leg.coordinates, metros: leg.metros, minutos: leg.minutos }));
            });
            const metricValues = card.querySelectorAll('.result-metrics strong');
            const setWalk = (index) => (data) => {
                if (metricValues[index]) metricValues[index].textContent = distance(data.metros) + ' · ' + data.minutos + ' min';
                if (metricValues[index]) metricValues[index].title = 'Caminata por calles peatonales';
            };
            streetWalk(walkIn, coordinates(start), [a[1], a[0]], tripLayers, setWalk(0));
            streetWalk(walkOut, [b[1], b[0]], coordinates(finish), tripLayers, setWalk(2));
            [[a, option.paraderoSubida ? 'Aquí tomas el bus: paradero ' + option.paraderoSubida : option.caminataPorCalles ? 'Aquí tomas el bus (el punto con menos caminata)' : 'Aquí tomas el bus (punto aproximado)'], [b, option.paraderoBajada ? 'Aquí te bajas: paradero ' + option.paraderoBajada : option.caminataPorCalles ? 'Aquí te bajas (el punto con menos caminata)' : 'Aquí te bajas (punto aproximado)']].forEach(([pt, label]) => {
                L.circleMarker([pt[1], pt[0]], { radius: 8, color: '#8c5612', weight: 3, fillColor: '#ffd17c', fillOpacity: 1 })
                    .bindTooltip(Spevb.text(label)).addTo(tripLayers);
            });
            etiqueta([a[1], a[0]], 'Sube aquí · ' + option.codigo, 'tag-up', [-4, 34]).addTo(tripLayers);
            etiqueta([b[1], b[0]], 'Bájate aquí', 'tag-down', [-4, 34]).addTo(tripLayers);
            const bounds = segment.getBounds();
            bounds.extend(coordinates(start));
            bounds.extend(coordinates(finish));
            Spevb.fitMap(map, bounds, { maxZoom: 17 });
            setTimeout(acomodarRotulos, 60);
            status('Ruta ' + option.codigo + ': camina por la línea punteada hasta el punto de subida, toma el bus por el tramo rojo y bájate en el otro punto.', 'success');
            if (scroll) window.dispatchEvent(new CustomEvent('spevb-focus-map'));
        });
    }

    function renderOption(option, index, start, finish, current, alternative = false) {
        const card = document.createElement('details');
        card.className = 'trip-option sevb-result-card' + (alternative ? ' alternative' : '');
        card.style.setProperty('--route-color', /^#[0-9a-f]{6}$/i.test(option.color || '') ? option.color : '#176650');
        const reversed = option.sentidoPermitido === false;
        if (option.variante === 'MAS_RAPIDA') card.classList.add('fast-variant');

        const summary = document.createElement('summary');
        const chip = document.createElement('span');
        chip.className = 'route-chip';
        chip.textContent = option.codigo;
        const main = document.createElement('span');
        main.className = 'opt-main';
        const title = document.createElement('strong');
        title.textContent = option.nombre;
        const sub = document.createElement('small');
        const total = option.acercamientoInicioMetros + option.acercamientoFinMetros;
        const fast = option.variante === 'MAS_RAPIDA';
        sub.textContent = (reversed ? 'Sentido contrario · ' : fast ? 'Más rápida · ' : index === 0 && !alternative ? 'Más cercana · ' : '')
            + 'caminas ' + distance(total) + ' · bus ' + option.recorridoKm.toLocaleString('es-CO', { maximumFractionDigits: 1 }) + ' km';
        // Tiempo estimado: caminata a 4,8 km/h y bus urbano a 16 km/h (sin espera: no hay horarios registrados).
        const walkMin = (m) => m / 80, busMin = option.recorridoKm * 1000 / 267;
        const minutes = Math.max(1, Math.round(walkMin(option.acercamientoInicioMetros) + busMin + walkMin(option.acercamientoFinMetros)));
        const legs = document.createElement('span');
        legs.className = 'leg-bar';
        legs.setAttribute('aria-hidden', 'true');
        [['walk', walkMin(option.acercamientoInicioMetros)], ['bus', busMin], ['walk', walkMin(option.acercamientoFinMetros)]].forEach(([kind, value]) => {
            const seg = document.createElement('i');
            seg.className = kind;
            seg.style.flexGrow = String(Math.max(0.6, value));
            legs.append(seg);
        });
        main.append(title, sub, legs);
        const eta = document.createElement('span');
        eta.className = 'opt-eta';
        eta.title = 'Tiempo estimado sin contar la espera del bus';
        const etaValue = document.createElement('b');
        etaValue.textContent = minutes >= 90 ? Math.floor(minutes / 60) + ' h ' + String(minutes % 60).padStart(2, '0') : String(minutes);
        const etaUnit = document.createElement('small');
        etaUnit.textContent = minutes >= 90 ? 'min' : 'min';
        eta.append(etaValue, etaUnit);
        summary.append(chip, main, eta);

        const body = document.createElement('div');
        body.className = 'opt-body';
        // Pasos al estilo de un itinerario: caminar → bus → caminar. Los <strong> 0 y 2 se actualizan con la caminata por calles.
        const steps = document.createElement('ol');
        steps.className = 'result-metrics steps';
        [
            ['walk', 'Camina ', distance(option.acercamientoInicioMetros), option.paraderoSubida ? ' hasta el paradero «' + option.paraderoSubida + '»' : ' hasta el punto de subida'],
            ['bus', 'Ruta ' + option.codigo + ' · ', option.recorridoKm.toLocaleString('es-CO', { maximumFractionDigits: 2 }) + ' km', ' en bus'],
            ['walk', 'Camina ', distance(option.acercamientoFinMetros), option.paraderoBajada ? ' desde el paradero «' + option.paraderoBajada + '» hasta tu destino' : ' hasta tu destino'],
        ].forEach(([kind, before, value, after]) => {
            const li = document.createElement('li');
            li.className = kind;
            const st = document.createElement('strong'); st.textContent = value;
            li.append(document.createTextNode(before), st, document.createTextNode(after));
            steps.append(li);
        });
        const metrics = steps;

        const direction = document.createElement('p');
        direction.className = 'result-direction';
        direction.textContent = reversed
            ? option.advertencia
            : option.sentidoRuta === 'CIRCULAR'
                ? 'Ruta circular: el viaje sigue el sentido en que fue dibujada.'
                : 'El viaje sigue el sentido en que fue dibujada la ruta.';
        if (option.variante === 'MAS_RAPIDA') direction.textContent = 'Misma ruta con otros puntos de subida y bajada: caminas un poco más, pero pasas menos tiempo en total. '
            + direction.textContent;
        if (!option.validada) direction.textContent += ' Recorrido por validar.';

        const button = document.createElement('button');
        button.type = 'button';
        button.className = 'secondary result-show-button';
        button.textContent = 'Centrar en el mapa';
        button.onclick = () => { if (current === sequence) showOption(option, start, finish, current, card, true); };

        let inflight = false;
        card.showOnMap = () => {
            if (inflight || card.classList.contains('selected')) return Promise.resolve();
            inflight = true;
            return showOption(option, start, finish, current, card, false).finally(() => { inflight = false; });
        };
        const prefetch = () => {
            const a = option.puntoSubida.coordinates, b = option.puntoBajada.coordinates;
            [[coordinates(start), [a[1], a[0]]], [[b[1], b[0]], coordinates(finish)]].forEach(([from, to]) => {
                if (L.latLng(from).distanceTo(L.latLng(to)) >= 30) getWalk(from, to).catch(() => {});
            });
        };
        card.addEventListener('pointerenter', prefetch, { once: true });
        card.addEventListener('focusin', prefetch, { once: true });
        // Acordeón: al abrir una opción se cierran las demás y se dibuja en el mapa.
        card.addEventListener('toggle', () => {
            if (!card.open) return;
            document.querySelectorAll('#tripResults .trip-option[open]').forEach((other) => { if (other !== card) other.open = false; });
            if (current === sequence) card.showOnMap();
        });

        body.append(metrics, direction, button);
        card.append(summary, body);
        return card;
    }

    // ---- Viajes con un transbordo (dos buses) ----
    const BUS2_COLOR = '#1e5fbf';      // el segundo bus se dibuja en azul; el primero, en rojo como siempre

    function showTransfer(t, start, finish, current, card, scroll) {
        return explorer.showRoute(t.primerBus.rutaId, { fit: false }).then((loaded) => {
            if (!loaded || current !== sequence) return;
            tripLayers.clearLayers();
            document.querySelectorAll('.trip-option.selected').forEach((c) => c.classList.remove('selected'));
            card.classList.add('selected');
            // Modo para daltónicos: azul y naranja (distinguibles para todos) y el segundo tramo con trazo discontinuo; además cada tramo lleva su número.
            const dalt = daltonico();
            const color1 = dalt ? '#D55E00' : '#e53935', color2 = dalt ? '#0072B2' : BUS2_COLOR;
            const line = (leg, color, numero) => {
                const latlngs = L.GeoJSON.coordsToLatLngs(leg.tramo.coordinates, 0);
                L.polyline(latlngs, { color: '#fff', weight: 13, opacity: 0.95, interactive: false, smoothFactor: 0.4 }).addTo(tripLayers);
                const l = L.polyline(latlngs, { color, weight: 8, opacity: 1, interactive: false, smoothFactor: 0.4, dashArray: dalt && numero === 2 ? '16 9' : null })
                    .bindTooltip(Spevb.text('Ruta ' + leg.codigo + ' · ' + leg.nombre), { sticky: true }).addTo(tripLayers);
                insigniaTramo(latlngs, numero, leg.codigo, color).addTo(tripLayers);
                return l;
            };
            const seg1 = line(t.primerBus, color1, 1), seg2 = line(t.segundoBus, color2, 2);
            // El bus recorre primero el tramo 1 y luego el 2.
            explorer.bus.play(t.primerBus.tramo.coordinates.concat(t.segundoBus.tramo.coordinates), { color: t.primerBus.color });
            const p = (leg, key) => [leg[key].coordinates[1], leg[key].coordinates[0]];
            const board1 = p(t.primerBus, 'puntoSubida'), out1 = p(t.primerBus, 'puntoBajada');
            const board2 = p(t.segundoBus, 'puntoSubida'), out2 = p(t.segundoBus, 'puntoBajada');
            setSeleccion({ codigos: [t.primerBus.codigo, t.segundoBus.codigo], paradas: [
                { lat: out1[0], lng: out1[1], texto: 'Bájate de la ruta ' + t.primerBus.codigo + ' y camina al transbordo' },
                { lat: out2[0], lng: out2[1], texto: 'Bájate de la ruta ' + t.segundoBus.codigo }] });
            const legs = [
                [t.caminataInicio, coordinates(start), board1, 'Camina hasta la ruta ' + t.primerBus.codigo],
                [t.caminataCambio, out1, board2, 'Camina de la ruta ' + t.primerBus.codigo + ' a la ruta ' + t.segundoBus.codigo + ' (transbordo)'],
                [t.caminataFin, out2, coordinates(finish), 'Camina hasta tu destino'],
            ];
            legs.forEach(([leg, from, to, label]) => {
                const walk = L.polyline([from, to], walkStyle)
                    .bindTooltip(Spevb.text(label + ' · ' + distance(leg.metros))).addTo(tripLayers);
                if (leg?.coordinates?.length) walkCache.set(walkKey(from, to), Promise.resolve({ coordinates: leg.coordinates, metros: leg.metros, minutos: leg.minutos }));
                streetWalk(walk, from, to, tripLayers);
            });
            const mark = (pt, label, fill) => L.circleMarker(pt, { radius: 8, color: '#8c5612', weight: 3, fillColor: fill, fillOpacity: 1 })
                .bindTooltip(Spevb.text(label)).addTo(tripLayers);
            mark(board1, 'Aquí tomas la ruta ' + t.primerBus.codigo + (t.primerBus.paraderoSubida ? ' (paradero ' + t.primerBus.paraderoSubida + ')' : ''), '#ffd17c');
            mark(out1, 'Aquí te bajas de la ruta ' + t.primerBus.codigo, '#ffffff');
            mark(board2, 'Aquí tomas la ruta ' + t.segundoBus.codigo + ' (transbordo)', '#9ec5ff');
            mark(out2, 'Aquí te bajas de la ruta ' + t.segundoBus.codigo, '#ffd17c');
            etiqueta(board1, 'Sube aquí · ' + t.primerBus.codigo, 'tag-up', [-4, 34]).addTo(tripLayers);
            etiqueta(out2, 'Bájate aquí', 'tag-down', [-4, 34]).addTo(tripLayers);
            // Aviso grande en el punto del cambio: de qué bus a cuál (se ubica en el punto medio entre donde bajas y donde subes).
            avisoCambio([(out1[0] + board2[0]) / 2, (out1[1] + board2[1]) / 2], t.primerBus.codigo, t.segundoBus.codigo, color1, color2).addTo(tripLayers);
            const bounds = seg1.getBounds().extend(seg2.getBounds());
            bounds.extend(coordinates(start));
            bounds.extend(coordinates(finish));
            Spevb.fitMap(map, bounds, { maxZoom: 17 });
            setTimeout(acomodarRotulos, 60);
            status('Transbordo: toma la ruta ' + t.primerBus.codigo + ' (tramo rojo), baja, camina hasta la ruta ' + t.segundoBus.codigo + ' (tramo azul) y bájate cerca de tu destino.', 'success');
            if (scroll) window.dispatchEvent(new CustomEvent('spevb-focus-map'));
        });
    }

    function renderTransfer(t, index, start, finish, current) {
        const card = document.createElement('details');
        card.className = 'trip-option sevb-result-card transfer-option';
        card.style.setProperty('--route-color', /^#[0-9a-f]{6}$/i.test(t.primerBus.color || '') ? t.primerBus.color : '#176650');
        const chip = (leg, cls) => {
            const c = document.createElement('span');
            c.className = 'route-chip ' + cls;
            c.textContent = leg.codigo;
            c.style.background = /^#[0-9a-f]{6}$/i.test(leg.color || '') ? leg.color : '#176650';
            return c;
        };
        const summary = document.createElement('summary');
        const chips = document.createElement('span');
        chips.className = 'transfer-chips';
        const plus = document.createElement('span');
        plus.className = 'transfer-plus';
        plus.textContent = '+';
        chips.append(chip(t.primerBus, 'one'), plus, chip(t.segundoBus, 'two'));
        const main = document.createElement('span');
        main.className = 'opt-main';
        const title = document.createElement('strong');
        title.textContent = t.primerBus.nombre + ' → ' + t.segundoBus.nombre;
        const sub = document.createElement('small');
        sub.textContent = (index === 0 ? 'Mejor transbordo · ' : 'Transbordo · ') + 'caminas ' + distance(t.caminataMetros) + ' · 2 pasajes';
        const walkMin = (m) => m / 80;
        const legs = document.createElement('span');
        legs.className = 'leg-bar';
        legs.setAttribute('aria-hidden', 'true');
        [['walk', walkMin(t.caminataInicio.metros)], ['bus', t.primerBus.recorridoKm * 1000 / 267], ['walk', walkMin(t.caminataCambio.metros) + t.esperaCambioMinutos],
            ['bus', t.segundoBus.recorridoKm * 1000 / 267], ['walk', walkMin(t.caminataFin.metros)]].forEach(([kind, value]) => {
            const seg = document.createElement('i');
            seg.className = kind;
            seg.style.flexGrow = String(Math.max(0.6, value));
            legs.append(seg);
        });
        main.append(title, sub, legs);
        const eta = document.createElement('span');
        eta.className = 'opt-eta';
        eta.title = 'Tiempo estimado: incluye unos ' + t.esperaCambioMinutos + ' min de espera en el cambio';
        const minutes = Math.max(1, Math.round(t.minutos));
        const etaValue = document.createElement('b');
        etaValue.textContent = minutes >= 90 ? Math.floor(minutes / 60) + ' h ' + String(minutes % 60).padStart(2, '0') : String(minutes);
        const etaUnit = document.createElement('small');
        etaUnit.textContent = 'min';
        eta.append(etaValue, etaUnit);
        summary.append(chips, main, eta);

        const body = document.createElement('div');
        body.className = 'opt-body';
        const steps = document.createElement('ol');
        steps.className = 'result-metrics steps';
        [
            ['walk', 'Camina ', distance(t.caminataInicio.metros), t.primerBus.paraderoSubida ? ' hasta el paradero «' + t.primerBus.paraderoSubida + '»' : ' hasta la ruta ' + t.primerBus.codigo],
            ['bus one', 'Ruta ' + t.primerBus.codigo + ' · ', t.primerBus.recorridoKm.toLocaleString('es-CO', { maximumFractionDigits: 2 }) + ' km', ' en bus'],
            ['walk swap', 'Transbordo: camina ', distance(t.caminataCambio.metros), ' hasta la ruta ' + t.segundoBus.codigo + (t.segundoBus.paraderoSubida ? ' (paradero «' + t.segundoBus.paraderoSubida + '»)' : '')],
            ['bus two', 'Ruta ' + t.segundoBus.codigo + ' · ', t.segundoBus.recorridoKm.toLocaleString('es-CO', { maximumFractionDigits: 2 }) + ' km', ' en bus'],
            ['walk', 'Camina ', distance(t.caminataFin.metros), ' hasta tu destino'],
        ].forEach(([kind, before, value, after]) => {
            const li = document.createElement('li');
            li.className = kind;
            if (kind === 'bus one') li.style.setProperty('--route-color', t.primerBus.color);
            if (kind === 'bus two') li.style.setProperty('--route-color', t.segundoBus.color);
            const st = document.createElement('strong'); st.textContent = value;
            li.append(document.createTextNode(before), st, document.createTextNode(after));
            steps.append(li);
        });
        const note = document.createElement('p');
        note.className = 'result-direction';
        note.textContent = t.advertencia + ((t.primerBus.validada && t.segundoBus.validada) ? '' : ' Alguna de las rutas está por validar.');
        const button = document.createElement('button');
        button.type = 'button';
        button.className = 'secondary result-show-button';
        button.textContent = 'Centrar en el mapa';
        button.onclick = () => { if (current === sequence) showTransfer(t, start, finish, current, card, true); };
        let inflight = false;
        card.showOnMap = () => {
            if (inflight || card.classList.contains('selected')) return Promise.resolve();
            inflight = true;
            return showTransfer(t, start, finish, current, card, false).finally(() => { inflight = false; });
        };
        card.addEventListener('toggle', () => {
            if (!card.open) return;
            document.querySelectorAll('#tripResults .trip-option[open]').forEach((other) => { if (other !== card) other.open = false; });
            if (current === sequence) card.showOnMap();
        });
        body.append(steps, note, button);
        card.append(summary, body);
        return card;
    }

    function resultGroup(title, hint, cards, open) {
        const group = document.createElement('details');
        group.className = 'results-group';
        group.open = open;
        const head = document.createElement('summary');
        const strong = document.createElement('strong'); strong.textContent = title;
        const count = document.createElement('span'); count.className = 'count'; count.textContent = String(cards.length);
        head.append(strong, count);
        const wrap = document.createElement('div');
        wrap.className = 'group-body';
        if (hint) {
            const small = document.createElement('p'); small.className = 'group-hint'; small.textContent = hint;
            wrap.append(small);
        }
        wrap.append(...cards);
        group.append(head, wrap);
        return group;
    }

    // Deja guardados los recorridos de un resultado para poder dibujarlos después sin conexión.
    function guardarDetalles(data) {
        const ids = [];
        (data.opciones || []).concat(data.alternativas || []).forEach((o) => ids.push(o.rutaId));
        (data.transbordos || []).forEach((t) => { ids.push(t.primerBus?.rutaId, t.segundoBus?.rutaId); });
        Spevb.Offline?.guardarDetalleRutas(ids.filter(Boolean));
    }

    // Viajes recientes: un toque vuelve a poner A y B y repite la búsqueda (con lo guardado si no hay señal).
    function renderRecientes() {
        const box = $('recentTrips');
        const lista = Spevb.Recientes?.listar() || [];
        box.replaceChildren();
        box.hidden = !lista.length;
        if (!lista.length) return;
        const title = document.createElement('span');
        title.className = 'recent-title';
        title.textContent = 'Recientes';
        const clear = document.createElement('button');
        clear.type = 'button';
        clear.className = 'text-button recent-clear';
        clear.textContent = 'Borrar';
        clear.onclick = () => Spevb.Recientes.borrar();
        const head = document.createElement('div');
        head.className = 'recent-head';
        head.append(title, clear);
        const row = document.createElement('div');
        row.className = 'recent-list';
        lista.forEach((r) => {
            const b = document.createElement('button');
            b.type = 'button';
            b.className = 'recent-item';
            const label = (r.a.nombre || 'A') + ' → ' + (r.b.nombre || 'B');
            b.textContent = label;
            b.title = label;
            b.onclick = () => {
                $('onlyValidatedTrips').checked = !!r.soloValidadas;
                choose('origin', { ...r.a });
                choose('destination', { ...r.b });
                setTimeout(() => $('planTrip').click(), 60);
            };
            row.append(b);
        });
        box.append(head, row);
    }
    window.addEventListener('spevb-recientes', renderRecientes);
    renderRecientes();

    $('planTrip').onclick = async () => {
        // En el celular se cierra el teclado: así el mapa y el panel miden el espacio real antes de encuadrar los resultados.
        if (document.activeElement && document.activeElement !== document.body) document.activeElement.blur?.();
        if (!origin || !destination) {
            status('Primero selecciona el punto A y el punto B. Puedes hacerlo directamente sobre el mapa.', 'error');
            return;
        }
        if (planning) return;
        if (L.latLng(coordinates(origin)).distanceTo(coordinates(destination)) < 20) {
            status('El origen y el destino están prácticamente en el mismo punto.', 'info');
            return;
        }

        const start = { ...origin };
        const finish = { ...destination };
        const current = ++sequence;
        planning = true;
        $('planTrip').disabled = true;
        controller = new AbortController();
        pickMode(null);
        tripLayers.clearLayers();
        $('tripResults').replaceChildren();
        status('Comparando A y B con todos los recorridos guardados…');

        try {
            const soloValidadas = $('onlyValidatedTrips').checked;
            let data, guardadoEn = null;
            try {
                data = await Spevb.api('/api/viajes/planificar', {
                    method: 'POST',
                    signal: controller.signal,
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({
                        origen: { lat: start.lat, lng: start.lng },
                        destino: { lat: finish.lat, lng: finish.lng },
                        soloValidadas,
                    }),
                });
                Spevb.Recientes?.guardar(start, finish, data, soloValidadas);
                guardarDetalles(data);
                if ((data.opciones || []).length || (data.alternativas || []).length || (data.transbordos || []).length) {
                    const mejor = (data.transbordos || [])[0];
                    const codigos = mejor ? [mejor.primerBus.codigo, mejor.segundoBus.codigo] : [((data.opciones || [])[0] || (data.alternativas || [])[0]).codigo];
                    window.dispatchEvent(new CustomEvent('spevb-viaje', { detail: { a: start, b: finish, soloValidadas, codigos } }));
                }
            } catch (error) {
                // Sin conexión o con señal muy mala: si este mismo viaje se consultó antes, se muestra lo guardado.
                const reciente = (error.offline || error.timeout) && Spevb.Recientes?.buscar(start, finish, soloValidadas);
                if (!reciente) {
                    if (error.offline || error.timeout) error.message += ' Los viajes que ya consultaste aparecen en «Recientes».';
                    throw error;
                }
                data = reciente.data;
                guardadoEn = reciente.t;
            }
            if (current !== sequence) return;
            if (guardadoEn) {
                const nota = document.createElement('div');
                nota.className = 'saved-trip-note';
                nota.textContent = 'Resultado guardado ' + Spevb.Offline.hace(guardadoEn) + '. Sin conexión no se recalcula: puede no reflejar cambios recientes.';
                $('tripResults').append(nota);
            }

            const options = data.opciones || [];
            const alternatives = data.alternativas || [];
            const cards = [];
            const transfers = data.transbordos || [];
            if (transfers.length) {
                // El servidor solo ofrece transbordos que conviene mostrar (ahorran tiempo o caminata, o no hay ruta directa):
                // van primero y abiertos, y se selecciona el mejor.
                const list = transfers.map((t, index) => renderTransfer(t, index, start, finish, current));
                cards.push(...list);
                $('tripResults').append(resultGroup('Con transbordo (2 buses)', options.length
                    ? 'Ahorran tiempo o caminata frente a las rutas directas de abajo. Cuestan dos pasajes e incluyen una espera estimada en el cambio.'
                    : 'No hay una ruta directa que sirva: combina dos rutas. Cuestan dos pasajes e incluyen una espera estimada en el cambio.', list, true));
            }
            if (options.length) {
                const list = options.map((option, index) => renderOption(option, index, start, finish, current, false));
                cards.push(...list);
                $('tripResults').append(resultGroup('Rutas que puedes tomar', 'Ordenadas por lo que menos caminas. «Más rápida» es la misma ruta con otros puntos, si ahorra tiempo.', list, true));
            }
            if (alternatives.length) {
                const list = alternatives.map((option, index) => renderOption(option, index, start, finish, current, true));
                cards.push(...list);
                $('tripResults').append(resultGroup('Sentido contrario', 'Solo sirven si el bus también pasa de regreso por esas calles.', list, !options.length && !transfers.length));
            }

            if (!options.length && !alternatives.length && !transfers.length) {
                const empty = document.createElement('div');
                empty.className = 'planner-empty';
                const title = document.createElement('strong');
                title.textContent = 'Todavía no hay una ruta registrada que conecte estos dos puntos.';
                const p = document.createElement('p');
                p.textContent = 'Registra más recorridos en el editor y aparecerán aquí automáticamente. También puedes usar «Ver rutas que pasan cerca del origen».';
                empty.append(title, p);
                $('tripResults').append(empty);
                status('No hay rutas registradas que conecten A y B.', 'info');
            } else {
                // Se dibuja de inmediato la mejor opción con las líneas punteadas de caminata.
                window.dispatchEvent(new CustomEvent('spevb-results'));
                cards[0].open = true;
                await cards[0].showOnMap();
                if (current !== sequence) return;
                const best = options[0] || alternatives[0];
                const total = options.length + alternatives.length + transfers.length;
                // Sin ruta directa, el mensaje lo dejó ya la tarjeta de transbordo que se acaba de dibujar.
                if (best && !transfers.length) status('La ruta más cercana es ' + best.codigo + ': caminas ' + distance(best.acercamientoInicioMetros)
                    + ' hasta ella y ' + distance(best.acercamientoFinMetros) + ' desde ella hasta tu destino. '
                    + (transfers.length ? 'También hay ' + (transfers.length === 1 ? 'una opción' : transfers.length + ' opciones') + ' con transbordo. ' : '')
                    + (total > 1 ? 'Toca otra tarjeta para ver las demás opciones.' : ''), 'success');
            }

            if (data.alcance) {
                const how = document.createElement('details');
                how.className = 'how-calc';
                const sum = document.createElement('summary');
                sum.textContent = 'Cómo se calcula';
                const note = document.createElement('p');
                note.textContent = data.alcance;
                how.append(sum, note);
                $('tripResults').append(how);
            }
        } catch (error) {
            if (error.name !== 'AbortError' && current === sequence) {
                status(error.message + (error.offline || error.timeout ? '' : ' Si el problema continúa, revisa /api/estado y que PostGIS esté healthy.'), 'error');
            }
        } finally {
            if (current === sequence) {
                planning = false;
                $('planTrip').disabled = false;
            }
        }
    };

    // Al cambiar el modo para daltónicos se vuelve a dibujar la opción elegida con los colores nuevos.
    window.addEventListener('spevb-ajustes', (e) => {
        if (e.detail.clave !== 'daltonico') return;
        const c = document.querySelector('.trip-option.selected');
        if (c && c.showOnMap) { c.classList.remove('selected'); c.showOnMap(); }
    });

    // Interfaz para los módulos añadidos (compartir, favoritos, reportes): no cambia el comportamiento del planificador.
    Object.assign(Planner, {
        puntos: () => ({ origen: origin && { ...origin }, destino: destination && { ...destination } }),
        poner(modo, punto) { choose(modo, { ...punto }); },
        buscar() { $('planTrip').click(); },
        soloValidadas: () => $('onlyValidatedTrips').checked,
    });
    window.dispatchEvent(new CustomEvent('spevb-planner-listo'));
})();
