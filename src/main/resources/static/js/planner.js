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

    const status = (message, type = 'info') => {
        $('tripStatus').textContent = message;
        $('tripStatus').className = 'status ' + type;
    };
    const coordinates = (point) => [point.lat, point.lng];
    const distance = (meters) => meters < 1000
        ? Math.round(meters) + ' m'
        : (meters / 1000).toLocaleString('es-CO', { maximumFractionDigits: 1 }) + ' km';
    const walkStyle = { color: '#2f3640', weight: 6, opacity: 0.95, dashArray: '1 11', lineCap: 'round' };
    const fitPadding = { paddingTopLeft: [55, 55], paddingBottomRight: [55, 130] };
    const endpointInput = (mode) => $(mode === 'origin' ? 'tripOrigin' : 'tripDestination');
    const endpointSearch = (mode) => $(mode === 'origin' ? 'searchOrigin' : 'searchDestination');

    function drawPins() {
        pins.clearLayers();
        [[origin, 'A', 'Origen'], [destination, 'B', 'Destino']].forEach(([point, letter, title]) => {
            if (!point) return;
            L.marker(coordinates(point), {
                icon: L.divIcon({
                    className: 'endpoint-wrapper',
                    iconSize: [38, 38],
                    iconAnchor: [19, 19],
                    html: '<div class="trip-pin">' + letter + '</div>',
                }),
                zIndexOffset: 1200,
            }).addTo(pins).bindTooltip(Spevb.text(title + ': ' + point.nombre));
        });
        $('nearOriginRoutes').disabled = !origin;
    }

    function clearPlan() {
        sequence++;
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
        if (mode) status(mode === 'origin' ? 'Toca en el mapa el punto exacto desde donde sales.' : 'Toca en el mapa el punto exacto a donde vas.', 'info');
    }

    function choose(mode, point) {
        if (mode === 'origin') {
            origin = point;
            clearNearby();
        } else {
            destination = point;
        }
        endpointInput(mode).value = point.nombre;
        $(mode + 'Results').replaceChildren();
        clearPlan();
        drawPins();
        pickMode(null);
        status(origin && destination
            ? 'Origen y destino listos. Pulsa «Encontrar rutas cercanas».'
            : 'Punto seleccionado. Ahora selecciona el otro extremo.', 'success');
    }

    function parseCoordinates(query) {
        const match = query.match(/^\s*(-?\d+(?:\.\d+)?)\s*,\s*(-?\d+(?:\.\d+)?)\s*$/);
        if (!match) return null;
        const lat = Number(match[1]);
        const lng = Number(match[2]);
        if (Math.abs(lat) > 90 || Math.abs(lng) > 180) throw new Error('Coordenadas fuera de rango. Usa latitud, longitud.');
        return { id: null, lat, lng, nombre: lat.toFixed(6) + ', ' + lng.toFixed(6), direccion: null, fuente: 'Coordenadas', confirmado: false };
    }

    function renderPlaceResults(mode, places) {
        const box = $(mode + 'Results');
        box.replaceChildren();
        places.forEach((place) => {
            const result = document.createElement('button');
            result.type = 'button';
            result.className = 'place-option rich-place';
            const title = document.createElement('strong');
            title.textContent = place.nombre;
            const meta = document.createElement('small');
            meta.textContent = (place.confirmado ? 'Catálogo SPEVB · ' : '') + (place.fuente || 'Ubicación');
            result.append(title, meta);
            result.onclick = () => {
                choose(mode, place);
                map.setView(coordinates(place), 17);
            };
            box.append(result);
        });
    }

    async function lookup(mode) {
        const input = endpointInput(mode);
        const button = endpointSearch(mode);
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
            const data = await Spevb.api('/api/lugares?q=' + encodeURIComponent(query));
            if (current !== lookups[mode] || input.value.trim() !== query) return;
            renderPlaceResults(mode, data.lugares || []);
            if (data.lugares?.length) {
                status('Selecciona la coincidencia correcta.', 'success');
            } else {
                status('No encontré esa nomenclatura en la cartografía disponible. Puedes marcar el punto exacto con «Elegir en mapa» y el planificador funcionará igual.', 'info');
            }
        } catch (error) {
            if (current === lookups[mode]) status(error.message, 'error');
        } finally {
            button.disabled = false;
        }
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
            const place = await Spevb.api('/api/lugares/reverso?lat=' + encodeURIComponent(lat) + '&lng=' + encodeURIComponent(lng));
            choose(mode, place || fallback);
        } catch (error) {
            // La geocodificación inversa es sólo una ayuda de nombre. El punto exacto del mapa
            // sigue siendo completamente válido para calcular rutas aunque Nominatim falle.
            choose(mode, fallback);
            status('Punto guardado por coordenadas. El buscador de nombres no respondió, pero esto no impide calcular rutas.', 'success');
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
            if (e.key === 'Enter') {
                e.preventDefault();
                lookup(mode);
            }
        });
        input.addEventListener('input', () => {
            lookups[mode]++;
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
        });
    });

    map.on('click', (e) => {
        if (picking) reversePoint(picking, e.latlng.lat, e.latlng.lng);
    });

    $('useMyLocation').onclick = () => {
        if (!navigator.geolocation) {
            status('Tu navegador no ofrece geolocalización.', 'error');
            return;
        }
        $('useMyLocation').disabled = true;
        status('Solicitando tu ubicación…');
        navigator.geolocation.getCurrentPosition(
            (position) => reversePoint('origin', position.coords.latitude, position.coords.longitude)
                .finally(() => { $('useMyLocation').disabled = false; }),
            () => {
                $('useMyLocation').disabled = false;
                status('No fue posible obtener tu ubicación. Usa «Elegir en mapa».', 'error');
            },
            { enableHighAccuracy: true, timeout: 10000, maximumAge: 30000 },
        );
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
        return explorer.showRoute(item.rutaId).then((loaded) => {
            if (!loaded) return;
            nearbyLayers.clearLayers();
            document.querySelectorAll('.nearby-card.selected').forEach((c) => c.classList.remove('selected'));
            card.classList.add('selected');
            const p = item.puntoCercano.coordinates;
            L.polyline([coordinates(origin), [p[1], p[0]]], walkStyle)
                .bindTooltip(Spevb.text('Aprox. ' + distance(item.distanciaMetros) + ' en línea recta hasta la ruta'))
                .addTo(nearbyLayers);
            L.circleMarker([p[1], p[0]], { radius: 8, color: '#8c5612', weight: 3, fillColor: '#ffd17c', fillOpacity: 1 })
                .bindTooltip(Spevb.text('Punto más cercano de la ruta')).addTo(nearbyLayers);
            const bounds = L.latLngBounds([coordinates(origin), [p[1], p[0]]]);
            map.fitBounds(bounds, { ...fitPadding, maxZoom: 16 });
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
                detail.textContent = distance(item.distanciaMetros) + ' desde el origen · ' + item.origen + ' → ' + item.destino;
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

    function showOption(option, start, finish, current, card, scroll) {
        return explorer.showRoute(option.rutaId).then((loaded) => {
            if (!loaded || current !== sequence) return;
            tripLayers.clearLayers();
            document.querySelectorAll('.trip-option.selected').forEach((c) => c.classList.remove('selected'));
            card.classList.add('selected');
            const a = option.puntoSubida.coordinates;
            const b = option.puntoBajada.coordinates;
            const segment = L.geoJSON(option.tramo, { style: { color: '#f39b20', weight: 10, opacity: 0.95 } }).addTo(tripLayers);
            L.polyline([coordinates(start), [a[1], a[0]]], walkStyle)
                .bindTooltip(Spevb.text('Caminar aprox. ' + distance(option.acercamientoInicioMetros) + ' en línea recta hasta la ruta'))
                .addTo(tripLayers);
            L.polyline([[b[1], b[0]], coordinates(finish)], walkStyle)
                .bindTooltip(Spevb.text('Caminar aprox. ' + distance(option.acercamientoFinMetros) + ' en línea recta hasta el destino'))
                .addTo(tripLayers);
            [[a, 'Aquí tomas el bus (punto aproximado)'], [b, 'Aquí te bajas (punto aproximado)']].forEach(([pt, label]) => {
                L.circleMarker([pt[1], pt[0]], { radius: 8, color: '#8c5612', weight: 3, fillColor: '#ffd17c', fillOpacity: 1 })
                    .bindTooltip(Spevb.text(label)).addTo(tripLayers);
            });
            const bounds = segment.getBounds();
            bounds.extend(coordinates(start));
            bounds.extend(coordinates(finish));
            map.fitBounds(bounds, { ...fitPadding, maxZoom: 17 });
            status('Ruta ' + option.codigo + ': camina por la línea punteada hasta el punto amarillo, toma el bus por el tramo naranja y bájate en el otro punto amarillo.', 'success');
            if (scroll) map.getContainer().scrollIntoView({ behavior: 'smooth', block: 'center' });
        });
    }

    function renderOption(option, index, start, finish, current, alternative = false) {
        const card = document.createElement('article');
        card.className = 'trip-option sevb-result-card' + (alternative ? ' alternative' : '');
        card.style.setProperty('--route-color', /^#[0-9a-f]{6}$/i.test(option.color || '') ? option.color : '#176650');

        const heading = document.createElement('div');
        heading.className = 'trip-option-heading';
        const left = document.createElement('div');
        left.className = 'result-route-title';
        const number = document.createElement('span');
        number.className = 'result-number';
        number.textContent = String(index + 1);
        const titleWrap = document.createElement('div');
        const title = document.createElement('strong');
        title.textContent = option.codigo + ' · ' + option.nombre;
        const sub = document.createElement('small');
        const reversed = option.sentidoPermitido === false;
        sub.textContent = reversed ? 'Va en sentido contrario al registrado para esta ruta'
            : index === 0 ? 'La ruta registrada más cercana'
            : 'Otra ruta que puedes usar';
        titleWrap.append(title, sub);
        left.append(number, titleWrap);
        const badge = document.createElement('span');
        badge.className = 'pill ' + (option.validada ? 'ok' : 'pending');
        badge.textContent = option.validada ? 'Revisada' : 'Por validar';
        heading.append(left, badge);

        const metrics = document.createElement('div');
        metrics.className = 'result-metrics';
        const items = [
            ['Caminar hasta la ruta', distance(option.acercamientoInicioMetros)],
            ['En bus', option.recorridoKm.toLocaleString('es-CO', { maximumFractionDigits: 2 }) + ' km'],
            ['Caminar al destino', distance(option.acercamientoFinMetros)],
        ];
        items.forEach(([label, value]) => {
            const box = document.createElement('div');
            const sm = document.createElement('small'); sm.textContent = label;
            const st = document.createElement('strong'); st.textContent = value;
            box.append(sm, st); metrics.append(box);
        });

        const direction = document.createElement('p');
        direction.className = 'result-direction';
        direction.textContent = reversed
            ? option.advertencia
            : option.sentidoRuta === 'CIRCULAR'
                ? 'Ruta circular: el viaje sigue el sentido en que fue dibujada.'
                : 'El viaje sigue el sentido en que fue dibujada la ruta.';

        const button = document.createElement('button');
        button.type = 'button';
        button.className = alternative ? 'secondary' : 'result-show-button';
        button.textContent = 'Ver exactamente por dónde ir';
        button.onclick = () => { if (current === sequence) showOption(option, start, finish, current, card, true); };
        card.showOnMap = () => showOption(option, start, finish, current, card, false);

        card.append(heading, metrics, direction, button);
        return card;
    }

    $('planTrip').onclick = async () => {
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
            const data = await Spevb.api('/api/viajes/planificar', {
                method: 'POST',
                signal: controller.signal,
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({
                    origen: { lat: start.lat, lng: start.lng },
                    destino: { lat: finish.lat, lng: finish.lng },
                    soloValidadas: $('onlyValidatedTrips').checked,
                }),
            });
            if (current !== sequence) return;

            const options = data.opciones || [];
            const alternatives = data.alternativas || [];
            const cards = [];
            if (options.length) {
                const head = document.createElement('div');
                head.className = 'results-heading';
                const strong = document.createElement('strong'); strong.textContent = 'Rutas que puedes tomar';
                const span = document.createElement('span'); span.textContent = 'De la más cercana a la más lejana';
                head.append(strong, span);
                $('tripResults').append(head);
                options.forEach((option, index) => {
                    const card = renderOption(option, index, start, finish, current, false);
                    cards.push(card);
                    $('tripResults').append(card);
                });
            }

            if (alternatives.length) {
                const divider = document.createElement('div');
                divider.className = 'alternative-heading';
                const strong = document.createElement('strong'); strong.textContent = 'Rutas que van en sentido contrario';
                const small = document.createElement('small'); small.textContent = 'Solo sirven si el bus también pasa de regreso por esas calles.';
                divider.append(strong, small);
                $('tripResults').append(divider);
                alternatives.forEach((option, index) => {
                    const card = renderOption(option, index, start, finish, current, true);
                    cards.push(card);
                    $('tripResults').append(card);
                });
            }

            if (!options.length && !alternatives.length) {
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
                await cards[0].showOnMap();
                if (current !== sequence) return;
                const best = options[0] || alternatives[0];
                const total = options.length + alternatives.length;
                status('La ruta más cercana es ' + best.codigo + ': caminas ' + distance(best.acercamientoInicioMetros)
                    + ' hasta ella y ' + distance(best.acercamientoFinMetros) + ' desde ella hasta tu destino. '
                    + (total > 1 ? 'Toca otra tarjeta para ver las demás opciones.' : ''), 'success');
            }

            const note = document.createElement('p');
            note.className = 'hint';
            note.textContent = data.alcance || '';
            $('tripResults').append(note);
        } catch (error) {
            if (error.name !== 'AbortError' && current === sequence) {
                status(error.message + ' Si el problema continúa, revisa /api/estado y que PostGIS esté healthy.', 'error');
            }
        } finally {
            if (current === sequence) {
                planning = false;
                $('planTrip').disabled = false;
            }
        }
    };
})();
