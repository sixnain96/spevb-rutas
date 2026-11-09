'use strict';
(() => {
    const $ = (id) => document.getElementById(id);
    const Geo = Spevb.Geo;
    // Otros módulos leen Spevb.Planner.seleccion desde que cargan: se crea ya y se completa cuando el mapa está listo.
    const Planner = (Spevb.Planner = { seleccion: null });
    // El mapa se crea de forma asíncrona (con Google): se espera al hito «explorador» que publica app.js. Todo lo demás de este
    // archivo va dentro de este callback (la sangría no cambia para que el diff sea revisable).
    Spevb.esperar('explorador').then((explorer) => {
    const map = explorer.map;
    const pins = map.group().addTo(map);
    const tripLayers = map.group().addTo(map);
    const nearbyLayers = map.group().addTo(map);
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
    // Control de respuestas asíncronas antiguas: la generación por extremo es `lookups[mode]` (se incrementa al elegir, escribir, intercambiar o borrar);
    // toda respuesta que llegue con una generación vieja se ignora. `reverseRequests` permite además ABORTAR la geocodificación inversa en vuelo.
    const reverseRequests = { origin: null, destination: null };
    const gpsUse = { gen: 0, activo: false };       // intento vigente de «Usar mi ubicación» como origen; borrar, escribir o intercambiar lo invalida
    const lookupRequests = { origin: null, destination: null };      // búsqueda explícita / elección de una sugerencia en vuelo (cancelable)

    // ---- Estado de cada campo A/B (contrato C): vacio → editando → consultando → mostrando → resolviendo → confirmado | error.
    // `texto` es el texto del punto confirmado (solo una edición REAL que lo cambie lo invalida); `sesion` es el sessionToken de Places de la búsqueda en curso
    // (un UUID por búsqueda: se descarta al confirmar, al borrar o al abandonar el campo); `componiendo` indica una composición de texto (IME) sin terminar.
    const nuevoCampo = () => ({ estado: 'vacio', texto: '', sesion: null, componiendo: false });
    const campo = { origin: nuevoCampo(), destination: nuevoCampo() };
    const nuevoUuid = () => {
        try { if (window.crypto && crypto.randomUUID) return crypto.randomUUID(); } catch { /* contexto no seguro */ }
        const h = (n) => Array.from({ length: n }, () => Math.floor(Math.random() * 16).toString(16)).join('');
        return h(8) + '-' + h(4) + '-4' + h(3) + '-a' + h(3) + '-' + h(12);
    };
    const sesionDe = (mode) => (campo[mode].sesion ||= nuevoUuid());
    const cerrarSesion = (mode) => { campo[mode].sesion = null; };
    const panelOculto = () => document.body.classList.contains('panel-oculto');

    // Opción elegida en pantalla (para «Avisarme al bajar», compartir y reportar). Otros módulos la leen con Spevb.Planner.
    /** Datos del recorrido para el «Modo viaje» (viaje.js): origen, destino y, por cada bus, dónde subir, dónde bajar y el tramo que recorre. */
    const viajeDe = (start, finish, tramos) => ({
        origen: { lat: start.lat, lng: start.lng },
        destino: { lat: finish.lat, lng: finish.lng },
        buses: tramos.map((l) => ({
            codigo: l.codigo, etiqueta: Spevb.rutaNombre(l.codigo), nombre: l.nombre, color: l.color,
            subida: { lat: l.puntoSubida.coordinates[1], lng: l.puntoSubida.coordinates[0] },
            bajada: { lat: l.puntoBajada.coordinates[1], lng: l.puntoBajada.coordinates[0] },
            paraderoSubida: l.paraderoSubida || null, paraderoBajada: l.paraderoBajada || null,
            tramo: l.tramo.coordinates,
        })),
    });
    // Cifras de la opción elegida para el botón «Iniciar viaje» (mismas fórmulas que muestran las tarjetas: caminata a 4,8 km/h y bus a 16 km/h).
    // Caminar de A a B sin bus: línea recta × 1,3 (las calles rodean las manzanas) a 80 m/min. Es solo una referencia aproximada.
    const minDirecto = (a, b) => Math.max(1, Math.round(Geo.distancia(a, b) * 1.3 / 80));
    const minOpcion = (o) => o.acercamientoInicioMetros / 80 + o.recorridoKm * 1000 / 267 + o.acercamientoFinMetros / 80;
    const resumenOpcion = (o, directo = null) => ({
        minutos: Math.max(1, Math.round(minOpcion(o))),
        caminaMetros: Math.round(o.acercamientoInicioMetros + o.acercamientoFinMetros), pasajes: 1,
        // Si el bus no ahorra tiempo frente a ir caminando, la interfaz lo dice (la opción no se oculta ni se cambia en silencio).
        directoMin: directo, caminarMejor: directo != null && minOpcion(o) >= directo,
    });
    const resumenTransbordo = (t) => ({ minutos: Math.max(1, Math.round(t.minutos)), caminaMetros: Math.round(t.caminataMetros), pasajes: 2 });
    const setSeleccion = (sel, transitorio = false) => { Planner.seleccion = sel; window.dispatchEvent(new CustomEvent('spevb-seleccion', { detail: { transitorio } })); };

    const status = (message, type = 'info') => {
        // Los errores también salen como aviso sobre el mapa: la línea de estado queda al fondo del panel y puede estar tapada.
        if (type === 'error') Spevb.toast(message, { tipo: 'error', ms: 12000, clave: 'estado' });
        else Spevb.toast.cerrar('estado');
        $('tripStatus').textContent = message;
        $('tripStatus').className = 'status ' + type;
    };
    const coordinates = (point) => [point.lat, point.lng];
    // «Unas 3 cuadras»: una cuadra mide ≈ 100 m. Más fácil de imaginar que los metros para quien no está acostumbrado a las cifras.
    const cuadras = (m) => { const c = Math.max(1, Math.round(m / 100)); return c === 1 ? 'una cuadra' : c + ' cuadras'; };
    /** Línea de tiempo del viaje: «caminar 4 min → bus 12 min → caminar 2 min», con iconos propios. partes: [{ tipo: 'walk' | 'bus', min, metros?, etiqueta? }] (las caminatas de menos de 30 m no se muestran). */
    function lineaTiempo(partes) {
        const lista = partes.filter((p) => p.tipo !== 'walk' || p.metros >= 30);
        const box = document.createElement('div');
        box.className = 'trip-timeline';
        box.setAttribute('role', 'img');
        box.setAttribute('aria-label', 'Tu viaje: ' + lista.map((p) => (p.tipo === 'walk' ? 'caminas ' : 'bus ') + p.min + ' minutos').join(', '));
        lista.forEach((p, i) => {
            if (i) box.append(html('span', 'tl-sep', '→'));
            const it = html('span', 'tl-item ' + p.tipo, null);
            const ico = html('span', 'tl-ico', null); ico.append(Spevb.Iconos.el(p.tipo === 'walk' ? 'caminar' : 'bus', { tam: 24 }));
            it.append(ico, html('b', null, p.min + ' min'),
                html('small', null, p.tipo === 'walk' ? cuadras(p.metros) : (p.etiqueta || 'en bus')));
            box.append(it);
        });
        return box;
    }
    const minCaminata = (m) => Math.max(1, Math.round(m / 80)), minBus = (km) => Math.max(1, Math.round(km * 1000 / 267));
    const distance = (meters) => meters < 1000
        ? Math.round(meters) + ' m'
        : (meters / 1000).toLocaleString('es-CO', { maximumFractionDigits: 1 }) + ' km';
    // Las líneas punteadas de caminata se dibujan en SVG (el mapa usa canvas, que no admite animación CSS) y se mueven muy
    // despacio en el sentido del trayecto: se ve el camino «avanzando» hacia el bus o el destino. Ver .walk-line en CSS.
    // (capa:'caminata' le pide al proveedor ese dibujo por encima de las rutas; con Google el punteado es estático.)
    const walkStyle = { color: '#1b2a33', weight: 5, opacity: 0.95, dashArray: '1 10', lineCap: 'round', smoothFactor: 0.3, capa: 'caminata' };
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
        if (Geo.distancia(from, to) < 30 || !layer.has(line)) return;
        getWalk(from, to).then((data) => {
            if (!layer.has(line) || !Array.isArray(data?.coordinates)) return;
            const path = Geo.desdeGeoJSON(data.coordinates);
            line.setStyle(walkStyle);
            line.setClase('walk-fallback', false);
            line.setPath([from, ...path, to]);
            // Se suman los tramos rectos cortos entre cada punto y la calle más cercana.
            const metros = data.metros + Geo.distancia(from, path[0])
                + Geo.distancia(path[path.length - 1], to);
            line.setTooltip(Spevb.text('Caminar aprox. ' + distance(metros) + ' (≈ ' + data.minutos + ' min) por calles'));
            onResult?.({ ...data, metros });
            // Puentes peatonales: solo se destacan (y se avisan) los que esta caminata realmente cruza.
            if (Spevb.Puentes) Spevb.Puentes.alRecorrer([from, ...path, to], layer).then((lista) => {
                if (!lista.length || !layer.has(line)) return;
                const nombres = lista.map((b) => b.nombre).join(' y ');
                line.setTooltip(Spevb.text('Caminar aprox. ' + distance(metros) + ' (≈ ' + data.minutos + ' min) por calles · Cruza el puente peatonal ' + nombres));
                line._onPuentes?.(lista);
            });
        }).catch(() => {
            if (!layer.has(line)) return;
            // La red de calles puede estar cargándose: se reintenta una vez antes de dejar la línea de respaldo.
            if (!retried) { setTimeout(() => streetWalk(line, from, to, layer, onResult, true), 3500); return; }
            // Sin trazado por calles: la línea recta se marca distinta para que no parezca un camino real.
            line.setStyle(fallbackStyle);
            line.setClase('walk-fallback', true);   // la línea recta de respaldo no se anima
            line.setTooltip(Spevb.text('Sin trazado por calles disponible: línea recta aproximada, NO es un camino real'));
        });
    }
    // En móvil el panel inferior tapa parte del mapa: se deja ese espacio libre al ajustar la vista.
    // Alto del mapa que tapa el panel del celular (con Google el mapa ya termina encima del panel: 0).
    const sheetCover = () => Spevb.coberturaPanel(map);
    const fitPaddingNow = () => Spevb.fitPadding(map);
    const endpointInput = (mode) => $(mode === 'origin' ? 'tripOrigin' : 'tripDestination');
    const endpointSearch = (mode) => $(mode === 'origin' ? 'searchOrigin' : 'searchDestination');

    // Cada pin se recrea solo si su punto cambió: uno sin cambios se queda donde está y no repite su entrada. Un pin nuevo entra
    // con una transición corta (pin-in); al intercambiar origen y destino cada pin viaja desde donde estaba (pin-swap). Ver motion.css.
    const pinState = { A: null, B: null };      // { key, ll, marker } del último pin dibujado de cada letra
    const sameLL = (p, q) => !!p && !!q && p[0] === q[0] && p[1] === q[1];
    function drawPins() {
        const now = { A: origin, B: destination };
        const prevLL = { A: pinState.A && pinState.A.ll, B: pinState.B && pinState.B.ll };
        [['A', 'Origen'], ['B', 'Destino']].forEach(([letter, title]) => {
            const point = now[letter], state = pinState[letter];
            if (!point) {
                if (state) { state.marker.remove(); pinState[letter] = null; }
                return;
            }
            const ll = coordinates(point), key = ll[0] + ',' + ll[1] + '|' + point.nombre;
            if (state && state.key === key) return;
            if (state) state.marker.remove();
            const other = letter === 'A' ? 'B' : 'A';
            let motion = ' pin-in', style = '';
            if (prevLL[letter] && sameLL(ll, prevLL[other])) {
                const from = map.toPoint(prevLL[letter]), to = map.toPoint(ll);     // null si el proveedor aún no tiene proyección: entra con pin-in
                const dx = from && to ? Math.round(from.x - to.x) : 9999, dy = from && to ? Math.round(from.y - to.y) : 9999;
                if (Math.abs(dx) < 900 && Math.abs(dy) < 900) { motion = ' pin-swap'; style = ' style="--pin-dx:' + dx + 'px;--pin-dy:' + dy + 'px"'; }
            }
            const marker = map.marker(ll, {
                className: 'endpoint-wrapper', size: [38, 48], anchor: [19, 46],
                tooltipOpciones: { offset: [0, -44] },       // equivale al tooltipAnchor [0, -44] de Leaflet
                html: '<div class="trip-pin ' + letter.toLowerCase() + motion + '"' + style + '>' + letter + '</div>',
                z: 1200, tooltip: Spevb.text(title + ': ' + point.nombre),
            }).addTo(pins);
            pinState[letter] = { key, ll, marker };
        });
        $('nearOriginRoutes').disabled = !origin;
        syncClearButtons();
    }

    /** Muestra las X solo si el campo tiene texto o existe un punto; «Limpiar A y B» si hay algo en cualquiera de los dos. */
    function syncClearButtons() {
        const pendiente = { origin: !!reverseRequests.origin || gpsUse.activo, destination: !!reverseRequests.destination };
        const has = { origin: !!origin || endpointInput('origin').value.trim() !== '' || pendiente.origin,
                      destination: !!destination || endpointInput('destination').value.trim() !== '' || pendiente.destination };
        [['origin', 'clearOrigin'], ['destination', 'clearDestination']].forEach(([mode, id]) => {
            $(id).disabled = !has[mode];
            endpointInput(mode).closest('.endpoint-block').classList.toggle('has-clear', has[mode]);
        });
        $('clearTripPoints').disabled = !(has.origin || has.destination);
    }

    // El explorador dibuja el recorrido completo de la opción elegida. Si lo cargó este planificador (source 'planner') le toca
    // limpiarlo; una ruta que la persona abrió por su cuenta desde la lista no se toca.
    let plannerOwnsRoute = false;
    function releasePlannerRoute() {
        if (!plannerOwnsRoute) return;
        plannerOwnsRoute = false;
        explorer.clearRoute();
    }

    function clearPlan() {
        sequence++;
        setSeleccion(null);
        controller?.abort();
        revealToken++;
        releasePlannerRoute();
        tripLayers.clear();
        $('tripResults').replaceChildren();
        planning = false;
        $('planTrip').disabled = false;
    }

    let nearbySequence = 0;
    function clearNearby() {
        nearbySequence++;
        releasePlannerRoute();
        nearbyLayers.clear();
        $('nearbyResults').replaceChildren();
    }

    /** Cancela TODO lo pendiente de un extremo: temporizador y petición de sugerencias, búsqueda manual, geocodificación inversa,
     *  elegir en el mapa y (en A) el intento de usar el GPS como origen. Las respuestas que lleguen después se ignoran por su generación. */
    function invalidateEndpoint(mode) {
        clearTimeout(suggestTimers[mode]);
        suggestTimers[mode] = null;
        suggestRequests[mode]?.abort();
        suggestRequests[mode] = null;
        reverseRequests[mode]?.abort();
        reverseRequests[mode] = null;
        lookupRequests[mode]?.abort();
        lookupRequests[mode] = null;
        ++lookups[mode];
        lastTyped[mode] = '';
        campo[mode] = nuevoCampo();
        endpointInput(mode).dataset.campoEstado = 'vacio';
        if (mode === 'origin') {
            if (gpsAcquiring && gpsBusy) Spevb.toast.cerrar('gps');      // el seguimiento general del GPS continúa; solo se descarta su uso como origen
            gpsUse.gen++;
            gpsUse.activo = false;
        }
        endpointSearch(mode).disabled = false;
        $(mode + 'Results').replaceChildren();
        resetSuggestions(mode);
    }

    /** Borra un extremo (A u B) y todo lo que depende de él. No toca favoritos, historial, viajes guardados, rutas ni el GPS. */
    function clearEndpoint(mode, { announce = true, focus = true } = {}) {
        invalidateEndpoint(mode);
        if (picking === mode) pickMode(null);
        if (mode === 'origin') { origin = null; clearNearby(); } else destination = null;
        endpointInput(mode).value = '';
        clearPlan();
        drawPins();
        if (announce) status(mode === 'origin' ? 'Origen eliminado. Selecciona nuevamente el punto A.' : 'Destino eliminado. Selecciona nuevamente el punto B.', 'info');
        if (focus) endpointInput(mode).focus();
        window.dispatchEvent(new CustomEvent('spevb-point-cleared', { detail: { mode } }));
    }

    function clearBothEndpoints({ focus = true } = {}) {
        invalidateEndpoint('origin');
        invalidateEndpoint('destination');
        if (picking) pickMode(null);          // también oculta el pin central de selección manual
        origin = null;
        destination = null;
        endpointInput('origin').value = '';
        endpointInput('destination').value = '';
        clearPlan();
        clearNearby();
        drawPins();
        status('Origen y destino eliminados. Marca nuevamente A y B.', 'info');
        if (focus) endpointInput('origin').focus();
        window.dispatchEvent(new CustomEvent('spevb-point-cleared', { detail: { mode: 'both' } }));
    }

    function pickMode(mode) {
        picking = mode;
        $('pickOrigin').setAttribute('aria-pressed', String(mode === 'origin'));
        $('pickDestination').setAttribute('aria-pressed', String(mode === 'destination'));
        map.el.classList.toggle('picking-location', !!mode);
        if (mode) window.dispatchEvent(new CustomEvent('spevb-picking', { detail: { mode } }));
        if (mode) status(mode === 'origin' ? 'Toca en el mapa el punto exacto desde donde sales.' : 'Toca en el mapa el punto exacto a donde vas.', 'info');
        if (mode) {
            const touch = isTouchLayout();
            const where = mode === 'origin' ? 'sales (A)' : 'llegas (B)';
            Spevb.toast(touch ? 'Mueve el mapa hasta dejar el pin donde ' + where.replace(/ \(.\)/, '') + ' y pulsa «Confirmar»' : 'Toca el mapa: ahí ' + where,
                { tipo: 'info', ms: 0, clave: 'pick', ubicacion: 'map-controls', accion: [
                    ...(touch ? [{ texto: 'Confirmar', alHacer: confirmCenter, cerrar: false, clase: 'toast-primary', etiqueta: 'Confirmar el punto en el centro del mapa' }] : []),
                    { texto: 'Cancelar', alHacer: () => pickMode(null), clase: 'toast-secondary', etiqueta: 'Cancelar la selección en el mapa' }] });
            showCenterPin(touch);
        } else {
            Spevb.toast.cerrar('pick');
            showCenterPin(false);
        }
    }

    // Escape cancela la selección en el mapa (si no hay una lista de sugerencias abierta que Escape deba cerrar primero).
    document.addEventListener('keydown', (e) => {
        if (e.key !== 'Escape' || !picking) return;
        if (e.target.matches?.('input') && (suggestedOptions.origin.length || suggestedOptions.destination.length)) return;
        pickMode(null);
    });
    // En celulares y tabletas se elige con un pin fijo en el centro: se mueve el mapa y se confirma. Es más preciso y no
    // depende de acertar un toque con el dedo sobre el mapa.
    const isTouchLayout = () => window.matchMedia('(pointer: coarse)').matches || Spevb.compactLayout();
    let centerPin = null;
    function centerPoint() {
        const size = map.getSize();
        return { x: size.x / 2, y: Math.max(40, (size.y - sheetCover()) / 2) };
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
            map.el.append(centerPin);
            window.addEventListener('resize', placeCenterPin);
        }
        if (centerPin) {
            centerPin.hidden = !show;
            if (show) { placeCenterPin(); setTimeout(placeCenterPin, 400); }   // el panel baja con una animación
        }
    }
    function confirmCenter() {
        if (!picking) return;
        const ll = map.toLatLng(centerPoint()) || map.getCenter();      // sin proyección todavía: el centro del mapa
        lastPick = Date.now();
        reversePoint(picking, ll[0], ll[1]);
    }

    /** Si A y B ya están elegidos y alguno quedó fuera de la parte visible del mapa, se ajusta la vista para ver los dos. */
    function showBothPoints() {
        if (!origin || !destination) return;
        const size = map.getSize(), cover = sheetCover(), mobile = Spevb.compactLayout();
        const visible = (p) => {
            const c = map.toPoint(coordinates(p));
            if (!c) return true;                       // sin proyección todavía no se puede medir: no se reencuadra
            return c.x > (mobile ? 24 : 440) && c.x < size.x - (mobile ? 76 : 70) && c.y > 70 && c.y < size.y - (mobile ? cover + 30 : 130);
        };
        if (visible(origin) && visible(destination)) return;
        Spevb.fitMap(map, Geo.limites([coordinates(origin), coordinates(destination)]), { maxZoom: 17 });
    }

    /** Marca un campo como confirmado: texto y sesión de Places quedan fijados y nada pendiente puede volver a abrir la lista. */
    function setCampo(mode, estado) {
        campo[mode].estado = estado;
        endpointInput(mode).dataset.campoEstado = estado;
    }

    function choose(mode, point) {
        clearTimeout(suggestTimers[mode]);
        suggestRequests[mode]?.abort();
        lookupRequests[mode]?.abort();
        lookupRequests[mode] = null;
        ++lookups[mode];
        endpointSearch(mode).disabled = false;          // la operación que estaba en curso (si la había) ya no re-habilita el botón: lo hace la confirmación
        if (mode === 'origin') {
            origin = point;
            clearNearby();
        } else {
            destination = point;
        }
        endpointInput(mode).value = point.nombre;
        $(mode + 'Results').replaceChildren();
        resetSuggestions(mode);
        campo[mode].texto = String(point.nombre || '').trim();
        cerrarSesion(mode);
        setCampo(mode, 'confirmado');
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
            result.className = 'place-option rich-place autosuggest-option';
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
        // Mismo menú desplegable que las sugerencias: una sola lista a la vez, que se cierra al salir del campo o al enfocar el otro.
        if (box.querySelector('.autosuggest-option')) {
            box.classList.add('suggestion-open');
            endpointInput(mode).setAttribute('aria-expanded', 'true');
        }
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

    // Mensajes por estado de la búsqueda (contrato B.2): «sin coincidencias» no es lo mismo que un tiempo agotado, una cuota o una clave inválida.
    const MENSAJES_BUSQUEDA = {
        TIMEOUT: 'La búsqueda tardó demasiado. Inténtalo de nuevo o usa «Elegir en el mapa».',
        RED: 'No hay conexión con el servidor de direcciones. Revisa tu señal e inténtalo de nuevo.',
        CUOTA: 'El servicio de direcciones llegó a su límite de uso por ahora. Usa «Elegir en el mapa» o inténtalo más tarde.',
        CLAVE: 'La búsqueda de direcciones no está bien configurada. Avisa a quien administra la aplicación y, mientras tanto, usa «Elegir en el mapa».',
        SIN_COINCIDENCIAS: 'No encontré sugerencias. Pulsa «Buscar dirección» para consultar también el catastro o usa «Elegir en el mapa».',
        FUERA_DE_CIUDAD: 'Ese lugar queda fuera de Villavicencio, que es el área de SEVB. Revisa la dirección.',
        RESPUESTA_INVALIDA: 'El servidor respondió algo que no pude leer. Inténtalo de nuevo.',
        PAUSADO: 'El servicio de direcciones está en pausa unos segundos por fallos seguidos. Vuelve a intentar enseguida o usa «Elegir en el mapa».',
    };
    const estadoDeError = (error) => (error?.offline ? 'RED' : error?.timeout ? 'TIMEOUT' : error?.status === 429 ? 'CUOTA'
        : (error?.status === 401 || error?.status === 403) ? 'CLAVE' : 'RESPUESTA_INVALIDA');
    /** Aviso en la caja de resultados del campo (anunciado a lectores de pantalla), con botones opcionales «Reintentar» y «Elegir otra». */
    function avisoBusqueda(mode, estado, { texto = null, reintentar = null, otra = null, rol = 'status' } = {}) {
        const box = $(mode + 'Results');
        box.replaceChildren();
        resetSuggestions(mode);
        const p = document.createElement('p');
        p.className = 'search-hint search-error';
        p.dataset.estado = estado;
        p.setAttribute('role', rol);
        p.textContent = texto || MENSAJES_BUSQUEDA[estado] || MENSAJES_BUSQUEDA.RESPUESTA_INVALIDA;
        box.append(p);
        [['reintentar', 'Reintentar', reintentar], ['otra', 'Elegir otra opción', otra]].forEach(([accion, etiqueta, fn]) => {
            if (!fn) return;
            const b = document.createElement('button');
            b.type = 'button';
            b.className = 'text-button search-retry';
            b.dataset.accion = accion;
            b.textContent = etiqueta;
            b.onclick = fn;
            box.append(b);
        });
    }
    /** La elección de una sugerencia no se pudo resolver: se informa, NO se sustituye por el primer resultado de otro proveedor. */
    function falloEleccion(mode, suggestion, estado, distinto = false) {
        setCampo(mode, 'error');
        const donde = suggestion.magicKey.startsWith('g:') ? 'Google' : 'el proveedor de direcciones';
        const texto = distinto
            ? 'El servidor no devolvió el lugar que elegiste. No lo usé para no ponerte en otro sitio: reintenta o elige otra opción.'
            : 'No pude ubicar «' + suggestion.texto + '» (' + (estado === 'TIMEOUT' ? donde + ' tardó demasiado' : (MENSAJES_BUSQUEDA[estado] || donde + ' no respondió').replace(/\.$/, '')) + '). Reintenta o elige otra opción.';
        avisoBusqueda(mode, estado, {
            texto, rol: 'alert',
            reintentar: () => lookup(mode, suggestion),
            otra: () => { const q = endpointInput(mode).value.trim(); endpointInput(mode).focus(); suggest(mode, q, ++lookups[mode]); },
        });
        status(texto, 'error');
    }

    // ---- Recuperación para el pasajero (sin mensajes de administración): reintentar, revisar A/B, rutas cercanas y reportar.
    const accionRevisar = () => { const falta = !origin ? 'tripOrigin' : 'tripDestination'; $(falta).focus(); status('Revisa que A y B estén bien ubicados en el mapa.', 'info'); };
    const accionCercanas = () => {
        if (!origin) { $('tripOrigin').focus(); status('Primero elige el origen (A) para buscar las rutas más cercanas.', 'info'); return; }
        if (!$('sideMenu').classList.contains('open')) $('menuButton').click();
        $('nearOriginRoutes').click();
    };
    const accionReportar = () => $('reportProblem').click();
    const mensajePasajero = (error, contexto, generico) => {
        const detalle = Spevb.Carga.detalleTecnico(error, contexto);
        if (error && (error.offline || error.timeout)) return error.message + detalle;
        const util = error && error.status >= 400 && error.status < 500 && error.message && !/HTTP \d+|api\/|SQL|PostGIS|exception/i.test(error.message);
        return (util ? error.message : generico) + detalle;
    };

    async function lookup(mode, suggestion = null) {
        const input = endpointInput(mode);
        const button = endpointSearch(mode);
        if (suggestion && suggestion.lat != null) { pickLocalSuggestion(mode, suggestion); return; }
        // Un punto ya confirmado con el texto sin cambios no se vuelve a buscar (Enter o «Buscar» tras confirmar no abren otra lista).
        if (!suggestion && campo[mode].estado === 'confirmado' && input.value.trim() === campo[mode].texto) {
            status('Ese punto ya está confirmado. Edita el texto si quieres buscar otra dirección.', 'info');
            return;
        }
        if (suggestion) input.value = suggestion.texto;
        // Doble toque (o Enter repetido): la misma búsqueda ya está en curso, no se lanza otra solicitud.
        if (!suggestion && lookupRequests[mode] && lookupRequests[mode]._consulta === input.value.trim()) return;
        clearTimeout(suggestTimers[mode]);
        suggestRequests[mode]?.abort();
        lookupRequests[mode]?.abort();
        resetSuggestions(mode);
        const query = input.value.trim();
        const current = ++lookups[mode];
        const vigente = () => current === lookups[mode];
        const pedido = new AbortController();
        pedido._consulta = query;
        lookupRequests[mode] = pedido;
        const carga = Spevb.Carga.iniciar({
            contenedor: button.closest('.endpoint-block'), boton: button, texto: 'Buscando…', ancla: $('tripStatus'),
            etapa: 'Seguimos buscando la dirección en Villavicencio. Con señal lenta puede tardar un poco.',
            alCancelar: () => { pedido.abort(); status('Búsqueda cancelada. Puedes escribir otra dirección o elegir el punto en el mapa.', 'info'); },
            alReintentar: () => { pedido.abort(); lookup(mode, suggestion); },
        });
        button.disabled = true;
        setCampo(mode, 'resolviendo');
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
                + (suggestion?.magicKey ? '&magicKey=' + encodeURIComponent(suggestion.magicKey) : '')
                + '&sesion=' + encodeURIComponent(sesionDe(mode));
            let data;
            try {
                data = await Spevb.api(url, { signal: pedido.signal });
            } catch (error) {
                if (error?.name === 'AbortError' || !vigente()) return;
                if (suggestion?.magicKey) { falloEleccion(mode, suggestion, estadoDeError(error)); return; }
                throw error;
            }
            if (!vigente() || input.value.trim() !== query) return;
            if (!data || typeof data !== 'object' || !Array.isArray(data.lugares)) {
                if (suggestion?.magicKey) { falloEleccion(mode, suggestion, 'RESPUESTA_INVALIDA'); return; }
                setCampo(mode, 'error');
                avisoBusqueda(mode, 'RESPUESTA_INVALIDA');
                status(MENSAJES_BUSQUEDA.RESPUESTA_INVALIDA, 'error');
                return;
            }
            const lugares = data.lugares;
            if (suggestion?.magicKey) {
                // La persona ya eligió: se ubica ESE lugar, sin un segundo menú de coincidencias. Si el servidor avisa que falló, si no devolvió nada o si el lugar viene
                // de un proveedor distinto del que se eligió, NO se acepta lugares[0]: se informa y se permite reintentar o elegir otra.
                const esperado = suggestion.magicKey.startsWith('g:') ? 'google' : 'arcgis';
                const distinto = !!data.proveedor && data.proveedor !== esperado && data.proveedor !== 'ninguno';
                const fallo = data.eleccionFallida || (data.estado && data.estado !== 'OK') || !lugares.length || distinto;
                if (fallo) { falloEleccion(mode, suggestion, distinto ? 'RESPUESTA_INVALIDA' : (data.estado && data.estado !== 'OK' ? data.estado : 'TIMEOUT'), distinto); return; }
                choose(mode, lugares[0]);
                map.setView(coordinates(lugares[0]), 17);
                return;
            }
            if (lugares.length === 1 && ['PREDIO_APROXIMADO', 'CONFIRMADA_POR_USUARIOS', 'VERIFICADO_LOCAL'].includes(lugares[0].precision)) {
                choose(mode, lugares[0]);
                map.setView(coordinates(lugares[0]), 17);
                return;
            }
            if (!lugares.length && data.estado && data.estado !== 'OK' && data.estado !== 'SIN_COINCIDENCIAS') {
                setCampo(mode, 'error');
                avisoBusqueda(mode, data.estado);
                status(MENSAJES_BUSQUEDA[data.estado] || MENSAJES_BUSQUEDA.RESPUESTA_INVALIDA, 'error');
                return;
            }
            renderPlaceResults(mode, lugares);
            setCampo(mode, lugares.length ? 'mostrando' : 'editando');
            const avisos = data.avisos || [];
            if (avisos.length) {
                const note = document.createElement('p');
                note.className = 'address-warning';
                note.textContent = avisos.join(' ');
                $(mode + 'Results').prepend(note);
            }
            if (lugares.length) {
                status('Selecciona una coincidencia y comprueba su precisión. La vía o el barrio no equivalen al número de casa.', 'info');
            } else {
                status('No encontré la dirección con su número en las fuentes disponibles. Revisa los avisos y usa «Elegir en mapa» si es necesario.', 'info');
            }
        } catch (error) {
            if (vigente()) { setCampo(mode, 'error'); status(error.message, 'error'); }
        } finally {
            if (lookupRequests[mode] === pedido) lookupRequests[mode] = null;
            // Solo la operación vigente toca el botón y el estado: el final de una búsqueda antigua no cambia lo de una posterior.
            if (vigente()) {
                button.disabled = false;
                if (campo[mode].estado === 'resolviendo') setCampo(mode, input.value.trim() ? 'editando' : 'vacio');
            }
            carga.fin();
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
        // Cada llamada toma una generación nueva del extremo y aborta la anterior: si la persona borra, escribe, intercambia o elige
        // otro punto mientras llega la respuesta, esta queda obsoleta y NO vuelve a colocar el punto.
        reverseRequests[mode]?.abort();
        const generation = ++lookups[mode];
        const limit = new AbortController();
        reverseRequests[mode] = limit;
        syncClearButtons();
        const vigente = () => generation === lookups[mode];
        setCampo(mode, 'resolviendo');
        status('Confirmando el punto seleccionado…');
        try {
            // Si el buscador de nombres tarda, no se deja esperando a la persona: pasados 8 s se usan las coordenadas.
            const timer = setTimeout(() => { if (vigente()) limit.abort(); }, 8000);
            let place;
            try {
                place = await Spevb.api('/api/lugares/reverso?lat=' + encodeURIComponent(lat) + '&lng=' + encodeURIComponent(lng), { signal: limit.signal });
            } finally {
                clearTimeout(timer);
            }
            if (!vigente()) return;
            choose(mode, place ? { ...place, lat, lng } : fallback);
            confirmTyped(mode, lat, lng);
        } catch (error) {
            // La geocodificación inversa es sólo una ayuda de nombre. El punto exacto del mapa
            // sigue siendo completamente válido para calcular rutas aunque Nominatim falle.
            if (!vigente()) return;
            choose(mode, fallback);
            confirmTyped(mode, lat, lng);
            status('Punto guardado por coordenadas. El buscador de nombres no respondió, pero esto no impide calcular rutas.', 'success');
        } finally {
            if (reverseRequests[mode] === limit) { reverseRequests[mode] = null; syncClearButtons(); }
        }
    }

    async function suggest(mode, query, current) {
        if (query.length < 3 || campo[mode].componiendo || panelOculto()) return;
        suggestRequests[mode]?.abort();
        const request = new AbortController();
        suggestRequests[mode] = request;
        setCampo(mode, 'consultando');
        // Una respuesta solo vale si sigue siendo la de ESTE campo, ESTE texto y ninguna confirmación, borrado, intercambio ni panel oculto la superó.
        const vigente = () => current === lookups[mode] && endpointInput(mode).value.trim() === query && campo[mode].estado === 'consultando' && !panelOculto();
        try {
            let data;
            try {
                data = await Spevb.api('/api/lugares/sugerencias?q=' + encodeURIComponent(query) + '&sesion=' + encodeURIComponent(sesionDe(mode)), { signal: request.signal });
            } catch (error) {
                if (error?.name === 'AbortError' || !vigente()) return;
                setCampo(mode, 'error');
                avisoBusqueda(mode, estadoDeError(error));
                return;
            }
            if (!vigente()) return;
            const box = $(mode + 'Results');
            box.replaceChildren();
            resetSuggestions(mode);
            if (!data || typeof data !== 'object' || !Array.isArray(data.sugerencias)) {
                setCampo(mode, 'error');
                avisoBusqueda(mode, 'RESPUESTA_INVALIDA');
                return;
            }
            const suggestions = data.sugerencias.slice(0, 8);
            if (!suggestions.length) {
                const estado = data.estado || 'SIN_COINCIDENCIAS';
                // Servidores anteriores sin proveedores externos ni base local: el botón de búsqueda manual sigue disponible, sin mensajes.
                if (!data.estado && !data.arcgisConfigurado && !data.direccionesLocales) { setCampo(mode, 'editando'); return; }
                setCampo(mode, estado === 'SIN_COINCIDENCIAS' ? 'editando' : 'error');
                avisoBusqueda(mode, estado);
                return;
            }
            suggestedOptions[mode] = suggestions;
            setCampo(mode, 'mostrando');
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
                meta.textContent = item.lat != null ? (item.detalle || '') : (item.detalle || 'ArcGIS') + ' · seleccionar y ubicar en el mapa';
                button.append(label, meta);
                button.onclick = () => lookup(mode, item);
                box.append(button);
            });
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
            if (next) map.el.scrollIntoView({ behavior: 'smooth', block: 'center' });
        };
        const input = endpointInput(mode);
        input.dataset.campoEstado = 'vacio';
        // Una sola lista de sugerencias abierta a la vez: al enfocar un campo se cierra la del otro, y al salir del campo se cierra la suya
        // (tocar una opción no quita el foco: mousedown/pointerdown se cancelan en la lista para que el toque llegue al botón).
        // Cerrar una lista también cancela lo pendiente de ese campo (una respuesta tardía no la reabre) y descarta su sesión de Places (abandono).
        const other = mode === 'origin' ? 'destination' : 'origin';
        const closeList = (m) => {
            if (campo[m].estado === 'resolviendo') return;      // elegir una sugerencia no se interrumpe al cambiar el foco
            if (['editando', 'consultando', 'mostrando', 'error'].includes(campo[m].estado)) {
                clearTimeout(suggestTimers[m]);
                suggestRequests[m]?.abort();
                ++lookups[m];
                cerrarSesion(m);
                setCampo(m, endpointInput(m).value.trim() ? 'editando' : 'vacio');
            }
            if ($(m + 'Results').querySelector('.autosuggest-option, .search-error')) $(m + 'Results').replaceChildren();
            resetSuggestions(m);
        };
        const listBox = $(mode + 'Results');
        ['mousedown', 'pointerdown'].forEach((ev) => listBox.addEventListener(ev, (e) => { if (e.target.closest('.autosuggest-option, .search-retry')) e.preventDefault(); }));
        input.addEventListener('focus', () => closeList(other));
        input.addEventListener('blur', () => setTimeout(() => {
            if (document.activeElement === input || listBox.contains(document.activeElement)) return;
            closeList(mode);
        }, 160));
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
                if (campo[mode].estado !== 'confirmado' && campo[mode].estado !== 'resolviendo') { ++lookups[mode]; setCampo(mode, input.value.trim() ? 'editando' : 'vacio'); }
                $(mode + 'Results').replaceChildren();
                resetSuggestions(mode);
            } else if (e.key === 'Enter') {
                e.preventDefault();
                const selected = suggestedOptions[mode][activeSuggestion[mode]];
                lookup(mode, selected || null);
            }
        });
        // Solo una EDICIÓN real cambia el punto: foco, desenfoque, autocompletado o un «input» que deja el mismo texto no lo invalidan ni consultan.
        const alEditar = () => {
            const texto = input.value.trim();
            if (campo[mode].estado === 'confirmado' && texto === campo[mode].texto) return;
            if (texto === '') {      // borrar todo el texto a mano = pulsar la X (sin mensaje ni foco: ya está escribiendo)
                clearEndpoint(mode, { announce: false, focus: false });
                status('Busca la dirección o usa «Elegir en mapa» para confirmar el punto exacto.');
                return;
            }
            clearTimeout(suggestTimers[mode]);
            suggestRequests[mode]?.abort();
            lookupRequests[mode]?.abort();
            lookupRequests[mode] = null;
            reverseRequests[mode]?.abort();
            if (mode === 'origin') { gpsUse.gen++; gpsUse.activo = false; }
            resetSuggestions(mode);
            lastTyped[mode] = texto;
            const current = ++lookups[mode];
            if (mode === 'origin') {
                origin = null;
                clearNearby();
            } else {
                destination = null;
            }
            $(mode + 'Results').replaceChildren();
            endpointSearch(mode).disabled = false;
            clearPlan();
            drawPins();
            setCampo(mode, 'editando');
            sesionDe(mode);
            status('Busca la dirección o usa «Elegir en mapa» para confirmar el punto exacto.');
            if (texto.length >= 3 && !campo[mode].componiendo) suggestTimers[mode] = setTimeout(() => suggest(mode, texto, current), 450);
        };
        input.addEventListener('compositionstart', () => { campo[mode].componiendo = true; });
        input.addEventListener('compositionend', () => { campo[mode].componiendo = false; alEditar(); });
        input.addEventListener('input', (e) => {
            if (e.isComposing || campo[mode].componiendo) return;      // composición de texto (teclados con predicción, chino/japonés…) sin terminar: no se consulta
            alEditar();
        });
    });

    // Ocultar el panel con sugerencias abiertas: se cierran y se cancela lo que estaba por consultarse. (Una elección en curso sigue: no se interrumpe.)
    window.addEventListener('spevb-panel', (e) => {
        if (!e.detail || !e.detail.oculto) return;
        ['origin', 'destination'].forEach((m) => {
            if (campo[m].estado === 'resolviendo') return;
            clearTimeout(suggestTimers[m]);
            suggestRequests[m]?.abort();
            if (['editando', 'consultando', 'mostrando', 'error'].includes(campo[m].estado)) {
                ++lookups[m];
                setCampo(m, endpointInput(m).value.trim() ? 'editando' : 'vacio');
            }
            $(m + 'Results').replaceChildren();
            resetSuggestions(m);
        });
    });

    // Elegir punto en el mapa. Además del «click» del mapa (que descarta toques con un leve movimiento del dedo)
    // se detecta el toque directamente: en celulares reales el dedo tiembla y el mapa lo toma por un arrastre.
    let lastPick = 0;
    const pickAt = (latlng) => {
        if (!latlng || !picking || Date.now() - lastPick < 700) return;
        lastPick = Date.now();
        reversePoint(picking, latlng[0], latlng[1]);
    };
    map.on('click', (e) => pickAt(e.latlng));
    let tapStart = null;
    map.el.addEventListener('pointerdown', (e) => {
        tapStart = e.isPrimary ? { x: e.clientX, y: e.clientY, t: performance.now() } : null;
    });
    map.el.addEventListener('pointerup', (e) => {
        const start = tapStart;
        tapStart = null;
        if (!start || !picking) return;
        if (Math.hypot(e.clientX - start.x, e.clientY - start.y) > 22 || performance.now() - start.t > 900) return;   // fue un arrastre o una pulsación larga
        if (e.target.closest('.mapa-control, .sevb-marcador, .map-fabs, .stops-toggle, button, a')) return;
        pickAt(map.eventLatLng(e));
    });

    // ---- Mi ubicación (GPS): lectura de buena calidad, marcador con círculo de precisión y seguimiento opcional.
    const gpsLayer = map.group().addTo(map);
    let gpsMarker = null, gpsCircle = null, gpsFollow = null, gpsBusy = false, gpsAcquiring = false;
    const gpsQuality = (f) => 'precisión ' + Spevb.Gps.calidad(f.precision).texto + ' (±' + Math.round(f.precision) + ' m)';
    /** Mensaje del GPS: en la línea de estado y como aviso sobre el mapa (los errores salen por status). */
    const gpsSay = (message, type = 'info', ms = 7000) => {
        status(message, type);
        if (type !== 'error') Spevb.toast(message, { tipo: type, ms, clave: 'gps' });
        else Spevb.toast.cerrar('gps');
    };
    const gpsProblem = (error) => error.message + (error.ayuda ? ' ' + error.ayuda : '');
    /** Estado visible del botón de ubicación (buscando → encontrado, o perdido / error) y su texto accesible. Sin estado vuelve al original. */
    function setEstado(boton, estado, texto) {
        if (!boton) return;
        if (boton._base === undefined) boton._base = boton.getAttribute('aria-label') || boton.title || '';
        clearTimeout(boton._estadoT);
        if (estado) { boton.dataset.estado = estado; boton.setAttribute('aria-label', texto); boton.title = texto; }
        else { delete boton.dataset.estado; boton.setAttribute('aria-label', boton._base); boton.title = boton._base; }
    }

    function drawGps(f, center) {
        const ll = [f.lat, f.lng];
        if (!gpsCircle) {
            gpsCircle = map.circle(ll, f.precision, { color: '#1a73e8', weight: 1, fillColor: '#1a73e8', fillOpacity: 0.12, interactive: false }).addTo(gpsLayer);
            gpsMarker = map.marker(ll, {
                className: 'gps-dot-wrapper', html: '<div class="gps-arrow hidden"></div><div class="gps-dot"></div>', size: [22, 22], anchor: [11, 11],
                z: 900, keyboard: false,
            }).addTo(gpsLayer);
        } else {
            gpsCircle.setPosition(ll).setRadius(f.precision);
            // El punto se desliza hacia la nueva lectura (solo si el cambio es pequeño); una lectura descartada nunca llega hasta aquí.
            const el = gpsMarker.getElement();
            if (el && !(Spevb.Motion && Spevb.Motion.reduced) && Geo.distancia(gpsMarker.getPosition(), ll) < 200) {
                el.style.transition = 'transform var(--motion-normal) linear';
                clearTimeout(el._gpsT);
                el._gpsT = setTimeout(() => { el.style.transition = ''; }, 300);
            }
            gpsMarker.setPosition(ll);
        }
        // El pulso solo existe mientras se intenta adquirir una lectura buena.
        gpsMarker.getElement()?.querySelector('.gps-dot')?.classList.toggle('adquiriendo', gpsAcquiring);
        gpsMarker.setTooltip(Spevb.text(f.desdeCache ? 'Última ubicación conocida · puede estar desactualizada' : 'Estás aquí · ' + gpsQuality(f)), { direccion: 'top', offset: [0, -10] });
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
        let ok = false;
        const intento = ++gpsUse.gen, obsoleto = () => intento !== gpsUse.gen;      // borrar A, escribir o intercambiar invalida este intento
        gpsUse.activo = true;
        syncClearButtons();
        try {
            await gpsReady();
            gpsAcquiring = true;
            setEstado($('useMyLocation'), 'buscando', 'Buscando tu ubicación…');
            const fix = await Spevb.Gps.obtener({ objetivo: 40, onProgreso: (l) => {
                if (obsoleto()) { drawGps(l, false); return; }
                gpsSay(l.desdeCache ? 'Mostrando tu última ubicación conocida; busco la actual…' : 'Mejorando la precisión… ' + gpsQuality(l), 'info', 0);
                drawGps(l, false);
            } });
            gpsAcquiring = false;
            drawGps(fix, obsoleto() ? false : true);      // el punto azul del GPS sigue; solo se descarta usarlo como origen
            if (obsoleto()) return;
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
            if (obsoleto()) return;
            gpsSay('Tu ubicación quedó como origen · ' + gpsQuality(fix)
                + (fix.precision > 150 ? '. Es aproximada (común en computadores sin GPS): si no coincide, usa «Elegir en el mapa».' : '.'),
                fix.precision > 150 ? 'info' : 'success');
            ok = true;
        } catch (error) {
            if (!obsoleto()) gpsSay(gpsProblem(error.codigo ? error : Spevb.Gps.errorGps(error)), 'error');
        } finally {
            if (!obsoleto()) { gpsUse.activo = false; syncClearButtons(); }
            gpsBusy = false;
            gpsAcquiring = false;
            gpsMarker?.getElement()?.querySelector('.gps-dot')?.classList.remove('adquiriendo');
            $('useMyLocation').disabled = false;
            const boton = $('useMyLocation');
            setEstado(boton, ok ? 'encontrado' : 'error', ok ? 'Ubicación encontrada' : 'No se pudo obtener tu ubicación');
            boton._estadoT = setTimeout(() => setEstado(boton, null), 6000);
        }
    };

    // Botón flotante: seguir mi ubicación en el mapa mientras me muevo (un segundo toque lo detiene).
    const locateButton = $('locateMe');
    function stopFollowing() {
        gpsFollow?.detener();
        gpsFollow = null;
        gpsAcquiring = false;
        gpsMarker?.getElement()?.querySelector('.gps-dot')?.classList.remove('adquiriendo');
        setEstado(locateButton, null);
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
        gpsAcquiring = true;
        setEstado(locateButton, 'buscando', 'Buscando tu ubicación…');
        let first = true, lost = false;
        gpsFollow = Spevb.Gps.seguir({
            onLectura: (l) => {
                if (l.precision <= 60) gpsAcquiring = false;
                drawGps(l, first);
                setEstado(locateButton, 'encontrado', 'Siguiendo tu ubicación · ' + gpsQuality(l) + '. Toca para detener.');
                if (lost) { lost = false; gpsSay('Señal recuperada · ' + gpsQuality(l) + '.', 'success'); }
                if (first) {
                    first = false;
                    gpsSay(Spevb.Gps.dentroDeCiudad(l.lat, l.lng)
                        ? 'Siguiendo tu ubicación · ' + gpsQuality(l) + '. Toca el botón de nuevo para detener.'
                        : 'Estás fuera de Villavicencio. Tu posición se muestra en el mapa.', 'info');
                }
            },
            onAviso: () => { lost = true; setEstado(locateButton, 'perdido', 'Sin señal de GPS: sigo intentando.'); gpsSay('Señal de GPS débil o perdida: sigo intentando. Si estás bajo techo, acércate a una ventana.', 'info', 0); },
            onError: (error) => { stopFollowing(); gpsSay(gpsProblem(error), 'error'); },
        });
    };

    $('clearOrigin').onclick = () => clearEndpoint('origin');
    $('clearDestination').onclick = () => clearEndpoint('destination');
    $('clearTripPoints').onclick = () => clearBothEndpoints();

    $('swapTrip').onclick = () => {
        const giro = $('swapTrip'); giro.classList.remove('girando'); void giro.offsetWidth; giro.classList.add('girando'); setTimeout(() => giro.classList.remove('girando'), 420);      // media vuelta del botón
        reverseRequests.origin?.abort();
        reverseRequests.destination?.abort();
        ['origin', 'destination'].forEach((m) => {      // consultas pendientes de sugerencias o de búsqueda: no deben terminar en el campo equivocado
            clearTimeout(suggestTimers[m]);
            suggestRequests[m]?.abort();
            lookupRequests[m]?.abort();
            lookupRequests[m] = null;
            endpointSearch(m).disabled = false;
        });
        [campo.origin, campo.destination] = [campo.destination, campo.origin];      // cada campo conserva su estado, su texto y su sesión al cambiar de lugar
        setCampo('origin', campo.origin.estado === 'confirmado' ? 'confirmado' : (campo.origin.estado === 'vacio' ? 'vacio' : 'editando'));
        setCampo('destination', campo.destination.estado === 'confirmado' ? 'confirmado' : (campo.destination.estado === 'vacio' ? 'vacio' : 'editando'));
        gpsUse.gen++;      // un intento de GPS pendiente ya no debe fijar el origen después de intercambiar
        gpsUse.activo = false;
        [origin, destination] = [destination, origin];
        const a = $('tripOrigin').value;
        $('tripOrigin').value = $('tripDestination').value;
        $('tripDestination').value = a;
        lookups.origin++;
        lookups.destination++;
        resetSuggestions('origin');
        resetSuggestions('destination');
        $('originResults').replaceChildren();
        $('destinationResults').replaceChildren();
        clearPlan();
        clearNearby();
        drawPins();
        status('Origen y destino intercambiados. Vuelve a calcular las rutas.');
    };

    // Cuando el explorador empieza a cargar CUALQUIER recorrido (el de otra opción o una ruta independiente) la selección anterior deja de
    // ser verdad: se invalida ya, se detiene «Avisarme al bajar» (spevb-seleccion) y, si la nueva carga falla, no reaparece.
    window.addEventListener('spevb-route-change', (e) => {
        plannerOwnsRoute = !!e.detail && e.detail.source === 'planner';
        setSeleccion(null, plannerOwnsRoute);      // transitorio: si es otra opción del planificador, «Avisarme al bajar» se reanuda con la nueva
        tripLayers.clear();
        document.querySelectorAll('.trip-option.selected').forEach((c) => c.classList.remove('selected'));
    });
    const DIBUJO_INTERNO = { fit: false, updateHistory: false, source: 'planner', completa: false };      // A→B: solo se dibuja el tramo que se recorre
    const DIBUJO_CERCANA = { ...DIBUJO_INTERNO, completa: true };                                          // «ruta más cercana»: aún no hay tramo, se ve la ruta entera

    function showNearby(item, card) {
        const vigente = nearbySequence;
        return explorer.showRoute(item.rutaId, DIBUJO_CERCANA).then((loaded) => {
            if (!loaded || vigente !== nearbySequence) return;
            nearbyLayers.clear();
            document.querySelectorAll('.nearby-card.selected').forEach((c) => c.classList.remove('selected'));
            card.classList.add('selected');
            const p = item.puntoCercano.coordinates;
            const nearLine = map.polyline([coordinates(origin), [p[1], p[0]]], { ...walkStyle,
                tooltip: Spevb.text('Aprox. ' + distance(item.distanciaMetros) + (item.caminataPorCalles ? ' caminando' : ' en línea recta') + ' hasta la ruta') })
                .addTo(nearbyLayers);
            streetWalk(nearLine, coordinates(origin), [p[1], p[0]], nearbyLayers);
            punto('sube', [p[1], p[0]], item.caminataPorCalles ? 'Mejor punto para subir (el más cercano caminando)' : 'Punto más cercano de la ruta').addTo(nearbyLayers);
            const bounds = Geo.limites([coordinates(origin), [p[1], p[0]]]);
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
                + '&soloValidadas=false');   // todas las rutas activas participan (el backend conserva el parámetro)
            if (!data.length) {
                status('Todavía no hay recorridos registrados.', 'info');
                return;
            }
            const cards = data.map((item, index) => {
                const card = document.createElement('div');
                card.className = 'nearby-card';
                const title = document.createElement('strong');
                title.textContent = (index === 0 ? 'Más cercana · ' : '') + Spevb.rutaNombre(item.codigo) + ' · ' + item.nombre;
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
            status('La ruta más cercana al origen es ' + Spevb.rutaNombre(data[0].codigo) + ', a ' + distance(data[0].distanciaMetros) + '. Se muestra en el mapa.', 'success');
        } catch (error) {
            status(error.message, 'error');
        } finally {
            $('nearOriginRoutes').disabled = !origin;
        }
    };

    // ---- rótulos sobre el mapa: «Sube aquí», «Baja aquí», insignias por tramo y el aviso del cambio de bus
    const daltonico = () => document.documentElement.classList.contains('daltonico');
    const html = (tag, cls, text) => { const e = document.createElement(tag); if (cls) e.className = cls; if (text != null) e.textContent = text; return e; };
    const rotulos = new Set();        // marcadores de texto («Sube aquí», insignias…) que acomodarRotulos corre hacia adentro
    const comoRotulo = (m) => { rotulos.add(m); return m; };
    function etiqueta(latlng, texto, cls, anchor) {
        return comoRotulo(map.marker(latlng, { interactive: false, keyboard: false, z: 900,
            className: 'map-tag-wrap', html: html('div', 'map-tag ' + cls, texto), size: [0, 0], anchor: anchor || [0, 0] }));
    }
    /** Insignia en medio de un tramo de bus: número del bus + código de la ruta, en el color del tramo. */
    function insigniaTramo(latlngs, numero, codigo, color) {
        const mid = latlngs[Math.floor(latlngs.length / 2)];
        const box = html('div', 'leg-badge');
        box.style.setProperty('--c', color);
        box.append(html('b', null, String(numero)), html('span', null, Spevb.rutaNombre(codigo)));
        return comoRotulo(map.marker(mid, { interactive: false, keyboard: false, z: 800, className: 'map-tag-wrap', html: box, size: [0, 0], anchor: [0, 0] }));
    }
    /** El «cuadro» del transbordo: de qué bus a qué bus se cambia, justo en el punto donde se baja. */
    function avisoCambio(latlng, de, a, colorDe, colorA) {
        const box = html('div', 'swap-callout');
        const chip = (t, c) => { const e = html('span', 'swap-chip', t); e.style.background = c; return e; };
        const fila = html('div', 'swap-row');
        fila.append(chip(de, colorDe), html('span', 'swap-arrow', '→'), chip(a, colorA));
        box.append(html('small', 'swap-title', 'CAMBIO DE BUS'), fila, html('small', 'swap-sub', 'Bájate de ' + de + ' y sube a ' + a));
        return comoRotulo(map.marker(latlng, { interactive: false, keyboard: false, z: 1100, className: 'map-tag-wrap', html: box, size: [0, 0], anchor: [0, 0] }));
    }
    // ---- puntos del itinerario: cada uno con su símbolo (subir, bajar, cambio de bus); no se usan para nada más
    const SIMBOLO = {
        sube: '<svg viewBox="0 0 24 24" width="14" height="14" aria-hidden="true"><path d="M12 19V6M6 11l6-6 6 6" fill="none" stroke="currentColor" stroke-width="3" stroke-linecap="round" stroke-linejoin="round"/></svg>',
        baja: '<svg viewBox="0 0 24 24" width="14" height="14" aria-hidden="true"><path d="M12 5v13M6 13l6 6 6-6" fill="none" stroke="currentColor" stroke-width="3" stroke-linecap="round" stroke-linejoin="round"/></svg>',
        cambio: '<svg viewBox="0 0 24 24" width="15" height="15" aria-hidden="true"><path d="M5 9h13M14 5l4 4-4 4M19 15H6M10 11l-4 4 4 4" fill="none" stroke="currentColor" stroke-width="2.6" stroke-linecap="round" stroke-linejoin="round"/></svg>',
    };
    function punto(tipo, latlng, label, colores) {
        const box = html('span', 'pt-marca pt-' + tipo);
        box.innerHTML = SIMBOLO[tipo];                       // SVG constante de este archivo: no entra ningún dato externo
        if (colores) { box.style.setProperty('--c1', colores[0]); box.style.setProperty('--c2', colores[1]); }
        return map.marker(latlng, { keyboard: false, z: 1000, className: 'map-tag-wrap', html: box, size: [26, 26], anchor: [13, 13], tooltip: Spevb.text(label) });
    }
    /** Línea «Cruza el puente peatonal …» en el detalle de la tarjeta (sin repetir nombres si varios tramos cruzan el mismo). */
    function notaPuentes(card, lista) {
        const nombres = card._puentes || (card._puentes = new Set());
        lista.forEach((b) => nombres.add(b.nombre));
        let nota = card.querySelector('.bridge-note');
        if (!nota) { nota = html('p', 'bridge-note'); card.append(nota); }
        nota.textContent = 'Cruza el puente peatonal ' + [...nombres].join(' y ') + '.';
    }
    // ---- el tramo del viaje se dibuja creciendo de punta a punta, una sola vez por selección (≈ 480 ms). El mapa sigue usando canvas:
    //      se reasigna la lista de puntos por cuadros; con movimiento reducido el tramo aparece completo. Se cancela si se elige otra opción.
    // ---- Paraderos y puentes a la vista, sin saturar el mapa ---------------------------------------------------------------------------
    // Solo se dibujan los que sirven al trayecto elegido: los paraderos de la ruta que caen sobre el tramo que se recorre, los que hay cerca
    // de A y de B (≤ 150 m: «llega al paradero …») y los puentes peatonales cerca de las caminatas. Lejos de eso no se muestra nada.
    const PARADERO_CERCA_M = 150, PARADERO_EN_TRAMO_M = 30, PUENTE_CERCA_M = 120;
    const ICONO_PARADERO = {
        className: 'paradero-marca-wrap', size: [22, 22], anchor: [11, 11],
        html: '<span class="paradero-marca" aria-hidden="true"><svg viewBox="0 0 24 24" width="14" height="14"><path d="M5 5h14a1 1 0 0 1 1 1v9a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2V6a1 1 0 0 1 1-1zm0 6h14M7 20v-3m10 3v-3" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/></svg></span>',
    };
    /** Distancia (m) de un punto a una polilínea [[lng, lat], …] (plano local, suficiente para decidir «está sobre el tramo»). */
    function distanciaATrazo(lat, lng, coords) {
        const kx = Math.cos(lat * Math.PI / 180) * 111320, ky = 110574;
        let mejor = Infinity;
        for (let i = 1; i < coords.length; i++) {
            const ax = (coords[i - 1][0] - lng) * kx, ay = (coords[i - 1][1] - lat) * ky, dx = (coords[i][0] - coords[i - 1][0]) * kx, dy = (coords[i][1] - coords[i - 1][1]) * ky;
            const l2 = dx * dx + dy * dy, t = l2 ? Math.max(0, Math.min(1, -(ax * dx + ay * dy) / l2)) : 0;
            mejor = Math.min(mejor, Math.hypot(ax + t * dx, ay + t * dy));
        }
        return mejor;
    }
    const paraderosDe = new WeakMap();       // capa → marcadores de paradero dibujados en ella (para no repetir uno en el mismo sitio)
    function marcaParadero(lat, lng, texto, layer) {
        const previos = (paraderosDe.get(layer) || []).filter((m) => layer.has(m));       // los de una capa ya limpiada no cuentan
        if (previos.some((m) => Math.abs(m._paradero[0] - lat) < 1e-5 && Math.abs(m._paradero[1] - lng) < 1e-5)) return null;
        const m = map.marker([lat, lng], { ...ICONO_PARADERO, keyboard: false, z: 600, tooltip: Spevb.text(texto) });
        m._paradero = [lat, lng];
        previos.push(m); paraderosDe.set(layer, previos);
        return m.addTo(layer);
    }
    /** Paraderos de la ruta que están sobre el tramo recorrido (entre «Sube aquí» y «Bájate aquí»). */
    function paraderosDelTramo(ruta, tramo, extremos, layer, token) {
        if (!ruta || token !== revealToken || !Array.isArray(ruta.paraderos)) return;
        ruta.paraderos.forEach((p) => {
            const c = p.ubicacion && p.ubicacion.coordinates;
            if (!c || distanciaATrazo(c[1], c[0], tramo) > PARADERO_EN_TRAMO_M) return;
            if (extremos.some((e) => Geo.distancia(e, [c[1], c[0]]) < 12)) return;       // ya tiene su propio símbolo («Sube»/«Baja»)
            marcaParadero(c[1], c[0], 'Paradero · ' + p.nombre + ' (la ruta ' + (Spevb.rutaNombre(ruta.codigo) || '') + ' pasa por aquí)', layer);
        });
    }
    /** Paraderos registrados a ≤ 150 m de un punto (A o B): sirven para decir «llega al paradero …». Devuelve el más cercano. */
    async function paraderosCercanos(latlng, layer, token) {
        const d = 0.0016;
        try {
            const lista = await Spevb.api('/api/paraderos?' + new URLSearchParams({ sur: latlng[0] - d, oeste: latlng[1] - d, norte: latlng[0] + d, este: latlng[1] + d, limite: 30 }));
            if (token !== revealToken || !Array.isArray(lista)) return null;
            const cerca = lista.map((p) => ({ p, m: Geo.distancia(latlng, [p.lat, p.lng]) })).filter((x) => x.m <= PARADERO_CERCA_M).sort((x, y) => x.m - y.m).slice(0, 3);
            cerca.forEach((x) => marcaParadero(x.p.lat, x.p.lng, 'Paradero · ' + x.p.nombre + ' (a ' + Math.round(x.m) + ' m)', layer));
            return cerca[0] ? { nombre: cerca[0].p.nombre, metros: Math.round(cerca[0].m) } : null;
        } catch { return null; }                  // sin conexión: el trayecto se ve igual, solo faltan estos paraderos extra
    }
    /** Puentes peatonales a ≤ 120 m de los puntos de la caminata: se ven (con su nombre) aunque la caminata no los cruce. */
    async function puentesCerca(puntos, layer, token) {
        if (!Spevb.Puentes || !Spevb.Puentes.cercanos) return;
        const lista = await Spevb.Puentes.cercanos(puntos, PUENTE_CERCA_M);
        if (token === revealToken) lista.forEach((b) => Spevb.Puentes.marcar(b, layer));
    }
    /** Aviso de paradero cercano a A («Hay un paradero a N m …») cuando el punto de subida no es ya un paradero. */
    function avisoParaderoA(start, layer, token, yaEsParadero) {
        return paraderosCercanos(coordinates(start), layer, token).then((c) => {
            if (!c || yaEsParadero || token !== revealToken) return;
            const previo = $('tripStatus').textContent.trim();
            if (/^(Ruta |La ruta |Transbordo|Recomendada)/.test(previo)) status(previo + ' Hay un paradero a ' + c.metros + ' m de tu punto A: ' + c.nombre + ', puedes esperar el bus allí.', 'success');
        });
    }
    let revealToken = 0;
    function revelar(lineas, puntos, ms, retrasoMs = 0) {
        // Los llamadores pasan pares [lat, lng] (Spevb.Geo.desdeGeoJSON); Geo.ll tolera también {lat, lng}.
        const latlngs = puntos.map((p) => Geo.ll(p));
        if ((Spevb.Motion && Spevb.Motion.reduced) || latlngs.length < 3) return;
        const mi = revealToken, acum = [0];
        for (let i = 1; i < latlngs.length; i++) acum.push(acum[i - 1] + Geo.distancia(latlngs[i - 1], latlngs[i]));
        const total = acum[acum.length - 1];
        if (!(total > 0)) return;
        lineas.forEach((l) => l.setPath([latlngs[0], latlngs[0]]));
        const t0 = performance.now() + retrasoMs;
        let k = 1;
        const paso = (ahora) => {
            if (mi !== revealToken) { lineas.forEach((l) => !l.liberado && l.setPath(latlngs)); return; }
            const t = Math.min(1, Math.max(0, (ahora - t0) / ms)), d = (1 - Math.pow(1 - t, 3)) * total;
            while (k < acum.length - 1 && acum[k] < d) k++;
            const f = Math.min(1, Math.max(0, (d - acum[k - 1]) / ((acum[k] - acum[k - 1]) || 1)));
            const a = latlngs[k - 1], b = latlngs[k];
            const parcial = latlngs.slice(0, k).concat([[a[0] + (b[0] - a[0]) * f, a[1] + (b[1] - a[1]) * f]]);
            lineas.forEach((l) => !l.liberado && l.setPath(t < 1 ? parcial : latlngs));
            if (t < 1) requestAnimationFrame(paso);
        };
        requestAnimationFrame(paso);
    }
    /** Los rótulos nunca se salen de la pantalla: si el punto queda cerca de un borde, el rótulo se corre hacia adentro (la flecha sigue señalando el punto). */
    function acomodarRotulos() {
        if (!rotulos.size) return;
        const size = map.getSize();
        rotulos.forEach((l) => {
            if (l.liberado) { rotulos.delete(l); return; }            // ya se retiró del mapa (Grupo.clear)
            if (!tripLayers.has(l)) return;
            const el = l.getElement() && l.getElement().firstElementChild;
            if (!el) return;
            const pt = map.toPoint(l.getPosition());
            if (!pt) return;                                           // sin proyección todavía: se acomoda en el próximo moveend
            const w = el.offsetWidth, x = pt.x, derecha = size.x - 72;   // 72 px: columna de botones flotantes
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
        if (current !== sequence) return Promise.resolve();
        return explorer.showRoute(option.rutaId, DIBUJO_INTERNO).then((loaded) => {
            if (!loaded || current !== sequence) return;
            revealToken++;
            tripLayers.clear();
            document.querySelectorAll('.trip-option.selected').forEach((c) => c.classList.remove('selected'));
            card.classList.add('selected');
            const a = option.puntoSubida.coordinates;
            const b = option.puntoBajada.coordinates;
            setSeleccion({ codigos: [option.codigo], paradas: [{ lat: b[1], lng: b[0], texto: 'Bájate de la ruta ' + Spevb.rutaNombre(option.codigo) }], viaje: viajeDe(start, finish, [option]), resumen: resumenOpcion(option, minDirecto(coordinates(start), coordinates(finish))) });
            // Tramo del viaje en rojo con borde blanco: no se confunde con las calles naranja del mapa base.
            const tramoLatLngs = Geo.desdeGeoJSON(option.tramo.coordinates);
            const casing = map.polyline(tramoLatLngs, { color: '#fff', weight: 13, opacity: 0.95, interactive: false, smoothFactor: 0.4 }).addTo(tripLayers);
            const segment = map.polyline(tramoLatLngs,
                { color: '#e53935', weight: 8, opacity: 1, interactive: false, smoothFactor: 0.4 }).addTo(tripLayers);
            revelar([casing, segment], tramoLatLngs, 480);
            explorer.bus.play(option.tramo.coordinates, { color: option.color });
            const walkIn = map.polyline([coordinates(start), [a[1], a[0]]], { ...walkStyle,
                tooltip: Spevb.text('Caminar aprox. ' + distance(option.acercamientoInicioMetros) + (option.caminataPorCalles ? ' caminando' : ' en línea recta') + ' hasta la ruta') })
                .addTo(tripLayers);
            const walkOut = map.polyline([[b[1], b[0]], coordinates(finish)], { ...walkStyle,
                tooltip: Spevb.text('Caminar aprox. ' + distance(option.acercamientoFinMetros) + (option.caminataPorCalles ? ' caminando' : ' en línea recta') + ' hasta el destino') })
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
            walkIn._onPuentes = walkOut._onPuentes = (lista) => notaPuentes(card, lista);
            streetWalk(walkIn, coordinates(start), [a[1], a[0]], tripLayers, setWalk(0));
            streetWalk(walkOut, [b[1], b[0]], coordinates(finish), tripLayers, setWalk(2));
            [[a, 'sube', option.paraderoSubida ? 'Aquí tomas el bus: paradero ' + option.paraderoSubida : option.caminataPorCalles ? 'Aquí tomas el bus (el punto con menos caminata)' : 'Aquí tomas el bus (punto aproximado)'], [b, 'baja', option.paraderoBajada ? 'Aquí te bajas: paradero ' + option.paraderoBajada : option.caminataPorCalles ? 'Aquí te bajas (el punto con menos caminata)' : 'Aquí te bajas (punto aproximado)']].forEach(([pt, tipo, label]) => {
                punto(tipo, [pt[1], pt[0]], label).addTo(tripLayers);
            });
            etiqueta([a[1], a[0]], 'Sube aquí · ' + Spevb.rutaNombre(option.codigo), 'tag-up', [-4, 34]).addTo(tripLayers);
            etiqueta([b[1], b[0]], 'Bájate aquí', 'tag-down', [-4, 34]).addTo(tripLayers);
            const bounds = Geo.limites([...segment.getPath(), coordinates(start), coordinates(finish)]);
            Spevb.fitMap(map, bounds, { maxZoom: 17 });
            const tokenParadas = revealToken, extremos = [[a[1], a[0]], [b[1], b[0]]];
            paraderosDelTramo(loaded, option.tramo.coordinates, extremos, tripLayers, tokenParadas);
            paraderosCercanos(coordinates(finish), tripLayers, tokenParadas);
            puentesCerca([coordinates(start), extremos[0], extremos[1], coordinates(finish)], tripLayers, tokenParadas);
            setTimeout(acomodarRotulos, 60);
            status('Ruta ' + Spevb.rutaNombre(option.codigo) + ': camina por la línea punteada hasta el punto de subida, toma el bus por el tramo rojo y bájate en el otro punto.', 'success');
            setTimeout(() => { if (tokenParadas === revealToken) avisoParaderoA(start, tripLayers, tokenParadas, !!option.paraderoSubida); }, 0);
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
        chip.textContent = Spevb.rutaNombre(option.codigo);
        const main = document.createElement('span');
        main.className = 'opt-main';
        const title = document.createElement('strong');
        title.textContent = option.nombre;
        const sub = document.createElement('small');
        const total = option.acercamientoInicioMetros + option.acercamientoFinMetros;
        const fast = option.variante === 'MAS_RAPIDA';
        // Variantes que propone el cálculo de transbordos cuando un segundo bus no compensa (ver TransbordoService.decidirSegundoBus).
        const caminaEnVez = option.variante === 'BAJAR_Y_CAMINAR', soloUna = option.variante === 'SOLO_SEGUNDA_RUTA';
        sub.textContent = (reversed ? 'Sentido contrario · ' : fast ? 'Más rápida · ' : caminaEnVez ? 'Baja y camina · ' : soloUna ? 'Una sola ruta · ' : index === 0 && !alternative ? 'Más cercana · ' : '')
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
            ['bus', 'Ruta ' + Spevb.rutaNombre(option.codigo) + ' · ', option.recorridoKm.toLocaleString('es-CO', { maximumFractionDigits: 2 }) + ' km', ' en bus'],
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
        // «Baja aquí y camina aproximadamente 700 m…»: el texto lo redacta el servidor con las cifras reales de la caminata por calles.
        if (caminaEnVez || soloUna) direction.textContent = option.advertencia;
        // Ruta lejana: si ir caminando directo tardaría igual o menos, se avisa (ocurre cuando ninguna ruta registrada pasa cerca de A y B).
        let avisoCaminar = null;
        const directoMin = minDirecto(coordinates(start), coordinates(finish));
        if (!reversed && !caminaEnVez && !soloUna && minOpcion(option) >= directoMin) {
            card.classList.add('walk-better');
            sub.textContent += ' · caminar directo ≈ ' + directoMin + ' min';
            const aviso = document.createElement('p');
            aviso.className = 'result-aviso-caminar';
            aviso.setAttribute('role', 'note');
            aviso.textContent = 'Ojo: ninguna parada de esta ruta queda cerca de tu origen y de tu destino. Caminar directamente tardaría unos '
                + directoMin + ' min (aprox.), igual o menos que esta opción. Mira las otras opciones antes de salir.';
            avisoCaminar = aviso;
        }

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
                if (Geo.distancia(from, to) >= 30) getWalk(from, to).catch(() => {});
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

        body.append(lineaTiempo([
            { tipo: 'walk', min: minCaminata(option.acercamientoInicioMetros), metros: option.acercamientoInicioMetros },
            { tipo: 'bus', min: minBus(option.recorridoKm), etiqueta: Spevb.rutaNombre(option.codigo) },
            { tipo: 'walk', min: minCaminata(option.acercamientoFinMetros), metros: option.acercamientoFinMetros }]), metrics, direction, ...(avisoCaminar ? [avisoCaminar] : []), button);
        card.append(summary, body);
        return card;
    }

    // ---- Viajes con un transbordo (dos buses) ----
    const BUS2_COLOR = '#1e5fbf';      // el segundo bus se dibuja en azul; el primero, en rojo como siempre

    function showTransfer(t, start, finish, current, card, scroll) {
        if (current !== sequence) return Promise.resolve();
        return explorer.showRoute(t.primerBus.rutaId, DIBUJO_INTERNO).then((loaded) => {
            if (!loaded || current !== sequence) return;
            revealToken++;
            tripLayers.clear();
            document.querySelectorAll('.trip-option.selected').forEach((c) => c.classList.remove('selected'));
            card.classList.add('selected');
            // Modo para daltónicos: azul y naranja (distinguibles para todos) y el segundo tramo con trazo discontinuo; además cada tramo lleva su número.
            const dalt = daltonico();
            const color1 = dalt ? '#D55E00' : '#e53935', color2 = dalt ? '#0072B2' : BUS2_COLOR;
            const line = (leg, color, numero) => {
                const latlngs = Geo.desdeGeoJSON(leg.tramo.coordinates);
                const borde = map.polyline(latlngs, { color: '#fff', weight: 13, opacity: 0.95, interactive: false, smoothFactor: 0.4 }).addTo(tripLayers);
                const l = map.polyline(latlngs, { color, weight: 8, opacity: 1, interactive: false, smoothFactor: 0.4, dashArray: dalt && numero === 2 ? '16 9' : null,
                    tooltip: Spevb.text('Ruta ' + Spevb.rutaNombre(leg.codigo) + ' · ' + leg.nombre), tooltipPegajoso: true }).addTo(tripLayers);
                revelar([borde, l], latlngs, 420, (numero - 1) * 420);       // primero el bus 1 y después el bus 2
                insigniaTramo(latlngs, numero, Spevb.rutaNombre(leg.codigo), color).addTo(tripLayers);
                return l;
            };
            const seg1 = line(t.primerBus, color1, 1), seg2 = line(t.segundoBus, color2, 2);
            // El bus recorre primero el tramo 1 y luego el 2.
            explorer.bus.play(t.primerBus.tramo.coordinates.concat(t.segundoBus.tramo.coordinates), { color: t.primerBus.color });
            const p = (leg, key) => [leg[key].coordinates[1], leg[key].coordinates[0]];
            const board1 = p(t.primerBus, 'puntoSubida'), out1 = p(t.primerBus, 'puntoBajada');
            const board2 = p(t.segundoBus, 'puntoSubida'), out2 = p(t.segundoBus, 'puntoBajada');
            setSeleccion({ codigos: [t.primerBus.codigo, t.segundoBus.codigo], paradas: [
                { lat: out1[0], lng: out1[1], texto: 'Bájate de la ruta ' + Spevb.rutaNombre(t.primerBus.codigo) + ' y camina al transbordo' },
                { lat: out2[0], lng: out2[1], texto: 'Bájate de la ruta ' + Spevb.rutaNombre(t.segundoBus.codigo) }], viaje: viajeDe(start, finish, [t.primerBus, t.segundoBus]), resumen: resumenTransbordo(t) });
            const legs = [
                [t.caminataInicio, coordinates(start), board1, 'Camina hasta la ruta ' + Spevb.rutaNombre(t.primerBus.codigo)],
                [t.caminataCambio, out1, board2, 'Camina de la ruta ' + Spevb.rutaNombre(t.primerBus.codigo) + ' a la ruta ' + Spevb.rutaNombre(t.segundoBus.codigo) + ' (transbordo)'],
                [t.caminataFin, out2, coordinates(finish), 'Camina hasta tu destino'],
            ];
            legs.forEach(([leg, from, to, label]) => {
                const walk = map.polyline([from, to], { ...walkStyle, tooltip: Spevb.text(label + ' · ' + distance(leg.metros)) }).addTo(tripLayers);
                if (leg?.coordinates?.length) walkCache.set(walkKey(from, to), Promise.resolve({ coordinates: leg.coordinates, metros: leg.metros, minutos: leg.minutos }));
                walk._onPuentes = (lista) => notaPuentes(card, lista);
                streetWalk(walk, from, to, tripLayers);
            });
            punto('sube', board1, 'Aquí tomas la ruta ' + Spevb.rutaNombre(t.primerBus.codigo) + (t.primerBus.paraderoSubida ? ' (paradero ' + t.primerBus.paraderoSubida + ')' : '')).addTo(tripLayers);
            punto('baja', out1, 'Aquí te bajas de la ruta ' + Spevb.rutaNombre(t.primerBus.codigo)).addTo(tripLayers);
            punto('cambio', board2, 'Cambio de bus: aquí tomas la ruta ' + Spevb.rutaNombre(t.segundoBus.codigo) + ' (transbordo)', [color1, color2]).addTo(tripLayers);
            punto('baja', out2, 'Aquí te bajas de la ruta ' + Spevb.rutaNombre(t.segundoBus.codigo)).addTo(tripLayers);
            etiqueta(board1, 'Sube aquí · ' + Spevb.rutaNombre(t.primerBus.codigo), 'tag-up', [-4, 34]).addTo(tripLayers);
            etiqueta(out2, 'Bájate aquí', 'tag-down', [-4, 34]).addTo(tripLayers);
            // Aviso grande en el punto del cambio: de qué bus a cuál (se ubica en el punto medio entre donde bajas y donde subes).
            avisoCambio([(out1[0] + board2[0]) / 2, (out1[1] + board2[1]) / 2], Spevb.rutaNombre(t.primerBus.codigo), Spevb.rutaNombre(t.segundoBus.codigo), color1, color2).addTo(tripLayers);
            const bounds = Geo.limites([...seg1.getPath(), ...seg2.getPath(), coordinates(start), coordinates(finish)]);
            Spevb.fitMap(map, bounds, { maxZoom: 17 });
            const tokenT = revealToken;
            paraderosDelTramo(loaded, t.primerBus.tramo.coordinates, [[board1[0], board1[1]], [out1[0], out1[1]]], tripLayers, tokenT);
            Spevb.api('/api/rutas/' + t.segundoBus.rutaId).then((r2) => paraderosDelTramo(r2, t.segundoBus.tramo.coordinates, [board2, out2], tripLayers, tokenT)).catch(() => {});
            paraderosCercanos(coordinates(finish), tripLayers, tokenT);
            puentesCerca([coordinates(start), board1, out1, board2, out2, coordinates(finish)], tripLayers, tokenT);
            setTimeout(acomodarRotulos, 60);
            status('Transbordo: toma la ruta ' + Spevb.rutaNombre(t.primerBus.codigo) + ' (tramo rojo), baja, camina hasta la ruta ' + Spevb.rutaNombre(t.segundoBus.codigo) + ' (tramo azul) y bájate cerca de tu destino.', 'success');
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
            c.textContent = Spevb.rutaNombre(leg.codigo);
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
        sub.textContent = (index === 0 ? 'Mejor transbordo · ' : 'Transbordo · ') + 'caminas ' + distance(t.caminataMetros) + ' · dos buses';
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
            ['walk', 'Camina ', distance(t.caminataInicio.metros), t.primerBus.paraderoSubida ? ' hasta el paradero «' + t.primerBus.paraderoSubida + '»' : ' hasta la ruta ' + Spevb.rutaNombre(t.primerBus.codigo)],
            ['bus one', 'Ruta ' + Spevb.rutaNombre(t.primerBus.codigo) + ' · ', t.primerBus.recorridoKm.toLocaleString('es-CO', { maximumFractionDigits: 2 }) + ' km', ' en bus'],
            ['walk swap', 'Transbordo: camina ', distance(t.caminataCambio.metros), ' hasta la ruta ' + Spevb.rutaNombre(t.segundoBus.codigo) + (t.segundoBus.paraderoSubida ? ' (paradero «' + t.segundoBus.paraderoSubida + '»)' : '')],
            ['bus two', 'Ruta ' + Spevb.rutaNombre(t.segundoBus.codigo) + ' · ', t.segundoBus.recorridoKm.toLocaleString('es-CO', { maximumFractionDigits: 2 }) + ' km', ' en bus'],
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
        note.textContent = t.advertencia;
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
        body.append(lineaTiempo([
            { tipo: 'walk', min: minCaminata(t.caminataInicio.metros), metros: t.caminataInicio.metros },
            { tipo: 'bus', min: minBus(t.primerBus.recorridoKm), etiqueta: Spevb.rutaNombre(t.primerBus.codigo) },
            { tipo: 'walk', min: minCaminata(t.caminataCambio.metros), metros: t.caminataCambio.metros },
            { tipo: 'bus', min: minBus(t.segundoBus.recorridoKm), etiqueta: Spevb.rutaNombre(t.segundoBus.codigo) },
            { tipo: 'walk', min: minCaminata(t.caminataFin.metros), metros: t.caminataFin.metros }]), steps, note, button);
        card.append(summary, body);
        return card;
    }

    const FLECHA = {
        normal: '<svg viewBox="0 0 24 24" width="14" height="14" aria-hidden="true"><path d="M4 12h15m0 0l-5-5m5 5l-5 5" fill="none" stroke="currentColor" stroke-width="2.4" stroke-linecap="round" stroke-linejoin="round"/></svg>',
        contraria: '<svg viewBox="0 0 24 24" width="14" height="14" aria-hidden="true"><path d="M20 12H5m0 0l5-5m-5 5l5 5" fill="none" stroke="currentColor" stroke-width="2.4" stroke-linecap="round" stroke-linejoin="round"/></svg>',
        transbordo: '<svg viewBox="0 0 24 24" width="14" height="14" aria-hidden="true"><path d="M7 4v8a3 3 0 0 0 3 3h7m0 0l-3-3m3 3l-3 3" fill="none" stroke="currentColor" stroke-width="2.4" stroke-linecap="round" stroke-linejoin="round"/></svg>',
    };
    const ETIQUETA_RECOMENDADA = { normal: 'Mejor en el sentido de la ruta', contraria: 'Mejor en sentido contrario', transbordo: 'Mejor opción con transbordo' };
    /** Etiqueta visible (texto + ícono de dirección + detalle de color; no depende solo del color) y marca de la tarjeta recomendada. */
    function marcarRecomendada(card, tipo) {
        card.classList.add('best-card', 'best-' + tipo);
        card.dataset.recomendacion = tipo;
        const host = card.querySelector(':scope > summary .opt-main') || card.querySelector(':scope > summary');
        const badge = document.createElement('span');
        badge.className = 'best-badge ' + tipo;
        badge.innerHTML = FLECHA[tipo];
        const texto = document.createElement('span');
        texto.textContent = ETIQUETA_RECOMENDADA[tipo];
        badge.append(texto);
        host.prepend(badge);
    }
    // ---- Recomendación del servidor (contrato A): el navegador NO aplica criterio propio; dibuja, marca e inicia la opción `idRecomendada`.
    const POLITICA_ACTUAL = 2;      // versión de la política que entiende esta pantalla (ViajeResponse.recomendacion.politicaVersion)
    const recomendacionVigente = (data) => {
        const r = data && data.recomendacion;
        return r && Array.isArray(r.ranking) && (r.politicaVersion || 0) >= POLITICA_ACTUAL ? r : null;
    };
    const idOpcion = (o, contraria) => o.id || ((contraria ? 'A:' : 'D:') + o.rutaId + ':' + (o.variante || 'MENOS_CAMINATA'));
    const idTransbordo = (t) => t.id || ('T:' + t.primerBus.rutaId + '>' + t.segundoBus.rutaId);
    const ETIQUETAS = { SIN_TRANSBORDO: 'Sin transbordo', PRIMER_BUS_MAS_CERCANO: 'Primer bus más cercano', MENOR_CAMINATA_TOTAL: 'Menor caminata total', MENOR_TIEMPO: 'Menor tiempo estimado' };

    /** Caminata inicial / de cambio / final / total y tiempo, con lo aproximado y lo estimado dicho como tal. Todo viene del resultado (nada se inventa aquí). */
    function desglose(item, espera) {
        const box = document.createElement('div');
        box.className = 'reco-desglose';
        const caminata = document.createElement('p');
        const aprox = item.calidadDistancia === 'RECTA' ? ' (aproximada: línea recta, no por calles)' : '';
        caminata.textContent = ['Caminata inicial ' + distance(item.caminataInicialMetros), item.buses === 2 ? 'de cambio ' + distance(item.caminataCambioMetros) : null,
            'final ' + distance(item.caminataFinalMetros), 'total ' + distance(item.caminataTotalMetros)].filter(Boolean).join(' · ') + aprox;
        const tiempo = document.createElement('p');
        tiempo.textContent = (item.buses === 2 ? 'Dos buses' : 'Un solo bus') + ' · tiempo estimado ≈ ' + Math.max(1, Math.round(item.minutos)) + ' min'
            + (item.buses === 2 ? ' (incluye ≈ ' + Math.round(espera || 8) + ' min de espera estimada en el cambio: no hay horarios)' : '');
        box.append(caminata, tiempo);
        return box;
    }
    /** Etiquetas de comparación: solo las que el servidor mandó (solo vienen cuando de verdad se comparó y esa opción es la mejor sin empate). */
    function chipsEtiquetas(item) {
        const tags = (item.etiquetas || []).filter((e) => ETIQUETAS[e]);
        if (!tags.length) return null;
        const row = document.createElement('span');
        row.className = 'reco-etiquetas';
        tags.forEach((e) => { const c = document.createElement('span'); c.className = 'reco-etiqueta'; c.dataset.etiqueta = e; c.textContent = ETIQUETAS[e]; row.append(c); });
        return row;
    }
    function decorar(card, entrada, item, espera) {
        card.dataset.opcionId = entrada.id;
        if (!item) return;
        card.dataset.orden = String(item.orden);
        const main = card.querySelector(':scope > summary .opt-main');
        const chips = chipsEtiquetas(item);
        if (main && chips) main.append(chips);
        const cuerpo = card.querySelector(':scope > .opt-body');
        if (cuerpo) cuerpo.insertBefore(desglose(item, espera), cuerpo.querySelector('.result-show-button'));
    }
    /** Marca una tarjeta de «Mejores opciones». `recomendada`: la que eligió el servidor; si no, es solo la mejor de comparación o un respaldo no elegible. */
    function marcarMejor(card, tipo, { texto, motivo = null, recomendada = false }) {
        card.classList.add('best-card', 'best-' + tipo);
        card.dataset.recomendacion = tipo;
        if (recomendada) { card.dataset.recomendada = 'true'; card.classList.add('best-recomendada'); }
        const host = card.querySelector(':scope > summary .opt-main') || card.querySelector(':scope > summary');
        if (motivo) {
            const m = document.createElement('small');
            m.className = 'reco-motivo';
            m.textContent = motivo;
            host.prepend(m);
        }
        const badge = document.createElement('span');
        badge.className = 'best-badge ' + tipo;
        badge.innerHTML = FLECHA[tipo];
        const t = document.createElement('span');
        t.textContent = texto;
        badge.append(t);
        host.prepend(badge);
    }

    /** Dibuja «Mejores opciones» y los grupos según la recomendación del servidor. Devuelve la tarjeta inicial (la recomendada). */
    function renderConRecomendacion(data, rec, start, finish, current, box) {
        const options = data.opciones || [], alternatives = data.alternativas || [], transfers = data.transbordos || [];
        const entradas = [
            ...options.map((o) => ({ id: idOpcion(o, false), tipo: 'normal', o })),
            ...alternatives.map((o) => ({ id: idOpcion(o, true), tipo: 'contraria', o })),
            ...transfers.map((t) => ({ id: idTransbordo(t), tipo: 'transbordo', t })),
        ];
        const porId = new Map(entradas.map((e) => [e.id, e]));
        const items = new Map(rec.ranking.map((i) => [i.id, i]));
        const orden = (e) => (items.get(e.id) ? items.get(e.id).orden : 1e6);
        const hacer = (e) => (e.tipo === 'transbordo' ? renderTransfer(e.t, 1, start, finish, current) : renderOption(e.o, 1, start, finish, current, e.tipo === 'contraria'));
        // La tarjeta inicial es EXACTAMENTE la recomendada. Sin recomendada elegible (p. ej. solo hay sentido contrario) se muestra la primera del ranking, SIN llamarla recomendada.
        const inicial = porId.get(rec.idRecomendada) || porId.get(rec.ranking[0] && rec.ranking[0].id) || entradas[0] || null;
        if (!inicial) return null;
        const esReco = !!rec.idRecomendada && inicial.id === rec.idRecomendada;
        const cardInicial = hacer(inicial);
        decorar(cardInicial, inicial, items.get(inicial.id), inicial.t && inicial.t.esperaCambioMinutos);
        marcarMejor(cardInicial, inicial.tipo, {
            recomendada: esReco,
            texto: esReco ? 'Recomendada' : inicial.tipo === 'contraria' ? 'Mejor en sentido contrario (verifica que el bus regrese por ahí)' : 'Mejor opción disponible',
            motivo: esReco ? (rec.motivo || null) : null,
        });
        const mejores = [cardInicial];
        const usados = new Set([inicial.id]);
        // Si lo recomendado lleva transbordo, la mejor directa se muestra al lado para poder compararlas de un vistazo.
        if (inicial.tipo === 'transbordo') {
            const dir = rec.ranking.filter((r) => r.elegible && r.tipo !== 'TRANSBORDO').map((r) => porId.get(r.id)).find(Boolean);
            if (dir) {
                const c = hacer(dir);
                decorar(c, dir, items.get(dir.id));
                marcarMejor(c, 'normal', { texto: 'Sin transbordo (para comparar)' });
                mejores.push(c);
                usados.add(dir.id);
            }
        }
        box.append(bestSection(mejores));
        const resto = entradas.filter((e) => !usados.has(e.id)).sort((a, b) => orden(a) - orden(b));
        const armar = (lista) => lista.map((e) => { const c = hacer(e); decorar(c, e, items.get(e.id), e.t && e.t.esperaCambioMinutos); return c; });
        const trans = resto.filter((e) => e.tipo === 'transbordo'), normales = resto.filter((e) => e.tipo === 'normal'), contrarias = resto.filter((e) => e.tipo === 'contraria');
        if (trans.length) {
            box.append(resultGroup('Con transbordo (2 buses)', 'Cuestan dos buses e incluyen una espera estimada en el cambio. Se muestran con su caminata inicial, de cambio y final.', armar(trans), true));
        }
        if (normales.length) {
            box.append(resultGroup('Otras opciones en este sentido · ' + normales.length, 'En el orden que calculó el servidor. «Más rápida» es la misma ruta con otros puntos, si ahorra tiempo.', armar(normales), false, true));
        }
        if (contrarias.length) {
            box.append(resultGroup('Otras opciones en sentido contrario · ' + contrarias.length, 'Solo sirven si el bus también pasa de regreso por esas calles: no se recomiendan.', armar(contrarias), false, true));
        }
        return cardInicial;
    }

    /** Sección «Mejores opciones» (hasta dos tarjetas compactas y cerradas). */
    function bestSection(cards) {
        const section = document.createElement('section');
        section.className = 'best-options';
        section.setAttribute('aria-labelledby', 'bestOptionsTitle');
        const title = document.createElement('h3');
        title.id = 'bestOptionsTitle';
        title.className = 'best-title';
        title.textContent = 'Mejores opciones';
        section.append(title, ...cards);
        return section;
    }

    function resultGroup(title, hint, cards, open, countInTitle = false) {
        const group = document.createElement('details');
        group.className = 'results-group';
        group.open = open;
        const head = document.createElement('summary');
        const strong = document.createElement('strong'); strong.textContent = title;
        const count = document.createElement('span'); count.className = 'count'; count.textContent = String(cards.length);
        if (countInTitle) head.append(strong); else head.append(strong, count);        // «Otras opciones … · N»: la cantidad va en el título
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
        const teniaFoco = document.activeElement === $('planTrip');
        // En el celular se cierra el teclado: así el mapa y el panel miden el espacio real antes de encuadrar los resultados.
        if (document.activeElement && document.activeElement !== document.body) document.activeElement.blur?.();
        if (!origin || !destination) {
            status('Primero selecciona el punto A y el punto B. Puedes hacerlo directamente sobre el mapa.', 'error');
            return;
        }
        if (planning) return;
        if (Geo.distancia(coordinates(origin), coordinates(destination)) < 20) {
            status('El origen y el destino están prácticamente en el mismo punto.', 'info');
            return;
        }

        const start = { ...origin };
        const finish = { ...destination };
        const current = ++sequence;
        planning = true;
        $('planTrip').disabled = true;
        controller = new AbortController();
        const miControl = controller;
        const carga = Spevb.Carga.iniciar({
            contenedor: $('tripResults'), boton: $('planTrip'), texto: 'Buscando…', ancla: $('tripStatus'), foco: teniaFoco,
            etapa: 'Estamos comparando tu origen y tu destino con todos los recorridos. Con señal lenta puede tardar un poco.',
            alCancelar: () => { miControl.abort(); status('Búsqueda cancelada. Cuando quieras, vuelve a tocar «Buscar rutas».', 'info'); },
            alReintentar: () => { miControl.abort(); setTimeout(() => $('planTrip').click(), 0); },
        });
        pickMode(null);
        // Un cálculo nuevo (aunque sea con los mismos A y B) invalida YA lo anterior: selección, aviso de bajada, barra del viaje y recorrido dibujado.
        setSeleccion(null);
        releasePlannerRoute();
        tripLayers.clear();
        $('tripResults').replaceChildren();
        status('Comparando A y B con todos los recorridos guardados…');

        try {
            // Todas las rutas activas registradas por el equipo participan: el filtro público «solo revisadas» ya no existe.
            // El campo se sigue enviando (en false) y se sigue guardando en recientes, historial y eventos por compatibilidad.
            const soloValidadas = false;
            let data, guardadoEn = null, calculoNuevo = false;
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
                calculoNuevo = true;
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
            const transfers = data.transbordos || [];
            const box = $('tripResults');
            const rec = recomendacionVigente(data);
            let initialRecommendationCard = null;
            if (rec) {
                initialRecommendationCard = renderConRecomendacion(data, rec, start, finish, current, box);
            } else {
                // Sin recomendación vigente (resultado guardado por una versión anterior o servidor sin la política nueva): se conserva el orden antiguo,
                // pero NO se presenta como un cálculo nuevo.
                if (options.length || alternatives.length || transfers.length) {
                    const aviso = document.createElement('div');
                    aviso.className = 'saved-trip-note legacy-note';
                    aviso.setAttribute('role', 'note');
                    aviso.textContent = 'Este resultado se calculó con reglas anteriores y no incluye la recomendación actual: vuelve a buscar para actualizarlo.';
                    box.append(aviso);
                }
                // «Mejores opciones»: como máximo dos tarjetas, en el orden que calculó el servidor (sin reordenar ni recalcular aquí):
                // la mejor del sentido normal (opciones[0]) y la mejor del sentido contrario (alternativas[0]). Sin rutas directas, el mejor transbordo.
                // Cada resultado existe UNA sola vez en el DOM: las demás rutas van a sus grupos (slice(1)).
                const bestNormal = options.length ? renderOption(options[0], 0, start, finish, current, false) : null;
                const bestContrary = alternatives.length ? renderOption(alternatives[0], 0, start, finish, current, true) : null;
                const bestTransfer = !bestNormal && !bestContrary && transfers.length ? renderTransfer(transfers[0], 0, start, finish, current) : null;
                if (bestNormal) marcarRecomendada(bestNormal, 'normal');
                if (bestContrary) marcarRecomendada(bestContrary, 'contraria');
                if (bestTransfer) marcarRecomendada(bestTransfer, 'transbordo');
                // Recomendación inicial EXPLÍCITA (no una posición accidental de un arreglo): normal → contraria → mejor transbordo.
                initialRecommendationCard = bestNormal || bestContrary || bestTransfer || null;
                const recommended = [bestNormal, bestContrary, bestTransfer].filter(Boolean);
                if (recommended.length) box.append(bestSection(recommended));
                const otherTransfers = bestTransfer ? transfers.slice(1) : transfers;
                if (otherTransfers.length) {
                    // El servidor solo ofrece transbordos que conviene mostrar (ahorran tiempo o caminata, o no hay ruta directa).
                    const list = otherTransfers.map((t, index) => renderTransfer(t, index + (bestTransfer ? 1 : 0), start, finish, current));
                    box.append(resultGroup('Con transbordo (2 buses)', options.length || alternatives.length
                        ? 'Ahorran tiempo o caminata frente a las rutas directas. Cuestan dos pasajes e incluyen una espera estimada en el cambio.'
                        : 'No hay una ruta directa que sirva: combina dos rutas. Cuestan dos pasajes e incluyen una espera estimada en el cambio.', list, true));
                }
                if (options.length > 1) {
                    const list = options.slice(1).map((option, index) => renderOption(option, index + 1, start, finish, current, false));
                    box.append(resultGroup('Otras opciones en este sentido · ' + list.length, 'Ordenadas por lo que menos caminas. «Más rápida» es la misma ruta con otros puntos, si ahorra tiempo.', list, false, true));
                }
                if (alternatives.length > 1) {
                    const list = alternatives.slice(1).map((option, index) => renderOption(option, index + 1, start, finish, current, true));
                    box.append(resultGroup('Otras opciones en sentido contrario · ' + list.length, 'Solo sirven si el bus también pasa de regreso por esas calles.', list, false, true));
                }
            }
            if (!options.length && !alternatives.length && !transfers.length) {
                const empty = document.createElement('div');
                empty.className = 'planner-empty';
                const title = document.createElement('strong');
                title.textContent = 'Todavía no hay una ruta registrada que conecte estos dos puntos.';
                const p = document.createElement('p');
                p.textContent = 'Revisa que A y B estén bien ubicados, mira las rutas que pasan cerca del origen o cuéntanos qué pasó para que lo revisemos.';
                empty.append(title, p, Spevb.Carga.accionesRecuperacion({ revisar: accionRevisar, cercanas: accionCercanas, reportar: accionReportar }));
                box.append(empty);
                status('No hay rutas registradas que conecten A y B.', 'info');
                window.dispatchEvent(new CustomEvent('spevb-results', { detail: { sinRutas: true } }));      // el panel muestra el mensaje en vez de dejarlo escondido
            }
            if (data.alcance) {
                const how = document.createElement('details');
                how.className = 'how-calc';
                const sum = document.createElement('summary');
                sum.textContent = 'Cómo se calcula';
                const note = document.createElement('p');
                note.textContent = data.alcance;
                how.append(sum, note);
                box.append(how);
            }
            if (initialRecommendationCard) {
                // El DOM ya está completo y estable. Se dibuja la recomendación inicial SIN abrir su <details> (las tarjetas empiezan compactas)
                // y recién después se avisa con spevb-results: el panel se posiciona una sola vez (ver sheet.js) y sin pisar al usuario.
                await initialRecommendationCard.showOnMap();
                if (current !== sequence) return;
                // Historial: una sola vez por cálculo nuevo y con los códigos REALES de la recomendación ya dibujada (no con otra lógica).
                const codigos = Planner.seleccion ? Planner.seleccion.codigos.slice() : [];
                if (calculoNuevo && codigos.length) {
                    window.dispatchEvent(new CustomEvent('spevb-viaje', { detail: { a: start, b: finish, soloValidadas, codigos } }));
                }
                const best = options[0] || alternatives[0];
                const total = options.length + alternatives.length + transfers.length;
                // Con recomendación: se dice POR QUÉ (frase del servidor con las cifras reales) junto con las instrucciones de la opción dibujada.
                if (rec && rec.motivo && rec.idRecomendada) status(rec.motivo + ' ' + $('tripStatus').textContent + (total > 1 ? ' Toca otra tarjeta para ver las demás opciones.' : ''), 'success');
                // Sin ruta directa, el mensaje lo dejó ya la tarjeta de transbordo que se acaba de dibujar.
                else if (best && !transfers.length) status('La ruta más cercana es ' + Spevb.rutaNombre(best.codigo) + ': caminas ' + distance(best.acercamientoInicioMetros)
                    + ' hasta ella y ' + distance(best.acercamientoFinMetros) + ' desde ella hasta tu destino. '
                    + (total > 1 ? 'Toca otra tarjeta para ver las demás opciones.' : ''), 'success');
                window.dispatchEvent(new CustomEvent('spevb-results'));
            }
        } catch (error) {
            if (error.name !== 'AbortError' && current === sequence) {
                status(mensajePasajero(error, 'planificar', 'No pudimos calcular tu viaje en este momento. Inténtalo de nuevo en unos segundos.'), 'error');
                $('tripResults').replaceChildren(Spevb.Carga.accionesRecuperacion({
                    reintentar: () => $('planTrip').click(), revisar: accionRevisar, cercanas: accionCercanas, reportar: accionReportar }));
            }
        } finally {
            if (current === sequence) {
                planning = false;
                $('planTrip').disabled = false;
            }
            carga.fin();
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
        limpiar() { clearBothEndpoints({ focus: false }); },      // «volver al inicio»: borra A y B sin abrir el teclado
        soloValidadas: () => false,   // compatibilidad: otros módulos lo consultan; ya no hay filtro público de revisión
    });
    window.dispatchEvent(new CustomEvent('spevb-planner-listo'));
    });      // fin de Spevb.esperar('explorador')
})();
