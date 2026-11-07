/* Humo de la interfaz sobre la app real. Pegar en la consola (o ejecutar con javascript_exec) con la app abierta en http://127.0.0.1:8081/ y la
   ventana en tamaño de escritorio (≥ 1100 px). Deja el navegador como estaba (borra las claves spevb-* de prueba). Devuelve una lista de comprobaciones. */
(async () => {
    const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
    const $ = (id) => document.getElementById(id);
    const res = [], errs = [];
    const ok = (n, c, x = '') => res.push({ n, ok: !!c, x: String(x) });
    window.addEventListener('error', (e) => errs.push(e.message));
    window.addEventListener('unhandledrejection', (e) => errs.push(String(e.reason)));
    const waitFor = async (fn, ms = 6000) => { const t = performance.now(); while (performance.now() - t < ms) { if (fn()) return true; await sleep(50); } return !!fn(); };
    const guardado = {}; for (const k of Object.keys(localStorage)) if (k.startsWith('spevb-')) guardado[k] = localStorage.getItem(k);
    const P = Spevb.Planner, map = window.SpevbExplorer.map;
    const A = { lat: 4.1405863, lng: -73.5952789, nombre: 'Prueba A', direccion: null, fuente: 'Coordenadas', confirmado: false, id: null };
    const B = { lat: 4.1274090, lng: -73.6357916, nombre: 'Prueba B', direccion: null, fuente: 'Coordenadas', confirmado: false, id: null };

    // 1) contrato del DOM
    for (const id of ['planTrip', 'swapTrip', 'useMyLocation', 'locateMe', 'shareTrip', 'alightAlert', 'saveTrip', 'reportProblem', 'colorGuide', 'routeSearch',
        'validationFilter', 'toggleRouteStops', 'fitRoute', 'resetMap', 'exportRoute', 'pickOrigin', 'pickDestination', 'nearOriginRoutes', 'installApp', 'offlineInfo', 'menuButton'])
        ok('#' + id + ' existe', !!$(id));
    ok('«Buscar rutas» sin «Solo revisadas»', $('planTrip').textContent.trim() === 'Buscar rutas' && !$('onlyValidatedTrips'));
    for (const a of ['busVisible', 'panelAuto', 'daltonico', 'paraderosMapa']) ok('ajuste ' + a + ' en el menú', !!document.querySelector('[data-ajuste="' + a + '"]'));
    // 2) API pública entre módulos
    const api = { Gps: ['obtener', 'seguir', 'permiso', 'calidad', 'dentroDeCiudad', 'distancia', 'errorGps', 'problema', 'plataforma'], Planner: ['puntos', 'poner', 'buscar', 'soloValidadas'],
        Datos: ['leer', 'escribir'], Ajustes: ['get', 'set'], Recientes: ['listar', 'guardar', 'buscar', 'borrar'], Puentes: ['alRecorrer', 'usados', 'cargar'], Motion: [], Offline: [], Cuenta: [], Caminatas: [] };
    for (const [k, fs] of Object.entries(api)) ok('Spevb.' + k, !!Spevb[k] && fs.every((f) => typeof Spevb[k][f] === 'function'));
    ok('Spevb.createBusPlayer y busModel.build3DBus', typeof Spevb.createBusPlayer === 'function' && typeof Spevb.busModel.build3DBus === 'function');
    // 3) explorador de rutas: lista, favoritos, paradas de la ruta
    await waitFor(() => document.querySelectorAll('#routeList .route-option').length >= 10);
    ok('lista de 10 rutas', document.querySelectorAll('#routeList .route-option').length === 10);
    ok('filtro: Todas / Favoritas', [...$('validationFilter').options].map((o) => o.value).join() === 'all,favorites');
    document.querySelectorAll('#routeList .route-option')[1].click(); await waitFor(() => !$('routeCard').classList.contains('hidden'));
    ok('abrir una ruta muestra su tarjeta sin insignia de validación', !$('routeCard').classList.contains('hidden') && !$('validationBadge') && !$('demoWarning'));
    ok('bus listo al abrir una ruta (marcador 72×72)', (() => { const i = document.querySelector('.leaflet-marker-icon.bus-wrapper'); return i && i.offsetWidth === 72 && i.offsetHeight === 72; })());
    const star = document.querySelector('#routeList .route-star'); const favAntes = Spevb.Datos.leer('rutas').length; star.click(); await sleep(150);
    ok('favorita: se guarda', Spevb.Datos.leer('rutas').length === favAntes + 1);
    $('validationFilter').value = 'favorites'; $('validationFilter').dispatchEvent(new Event('change')); ok('filtro «Mis rutas favoritas» muestra 1', $('routeCount').textContent === '1', $('routeCount').textContent);
    $('validationFilter').value = 'all'; $('validationFilter').dispatchEvent(new Event('change')); document.querySelector('#routeList .route-star.on').click(); await sleep(150);
    ok('favorita: se quita', Spevb.Datos.leer('rutas').length === favAntes);
    // 4) selección directa sobre el mapa
    $('pickOrigin').click(); await sleep(150);
    map.fire('click', { latlng: L.latLng(B.lat + 0.001, B.lng + 0.001) });
    ok('elegir en el mapa fija el origen', await waitFor(() => P.puntos().origen && Math.abs(P.puntos().origen.lat - (B.lat + 0.001)) < 1e-6, 8000));
    // 5) planificar con transbordos, rutas directas y alternativas
    P.poner('origin', A); P.poner('destination', B); await sleep(300); P.buscar();
    await waitFor(() => document.querySelector('#tripResults .trip-option'), 15000); await sleep(1500);
    const cards = [...document.querySelectorAll('#tripResults .trip-option')];
    ok('resultados: directas, transbordo(s) y alternativas', cards.length >= 8 && cards.some((c) => c.classList.contains('transfer-option')) && cards.some((c) => /Una sola ruta|Baja y camina/.test(c.textContent)), 'tarjetas=' + cards.length);
    ok('la primera tarjeta queda seleccionada y es usable', !!document.querySelector('#tripResults .trip-option.selected'));
    ok('sin textos de validación en los resultados', !/por validar|validación oficial|Pendiente de validar/.test($('tripResults').textContent));
    ok('pines A y B', document.querySelectorAll('.trip-pin').length === 2);
    const tr = cards.find((c) => c.classList.contains('transfer-option')); tr.querySelector('summary').click(); await sleep(300); const btn = tr.querySelector('.result-show-button'); if (btn) btn.click(); await sleep(2500);
    ok('transbordo: símbolos de subir, cambio y bajar + aviso «Cambio de bus»', !!document.querySelector('.pt-sube') && !!document.querySelector('.pt-cambio') && !!document.querySelector('.pt-baja') && !!document.querySelector('.swap-callout'));
    ok('transbordo: caminata de cambio dibujada', document.querySelectorAll('.walk-line').length >= 2, document.querySelectorAll('.walk-line').length);
    ok('historial y recientes se guardan', Spevb.Datos.leer('historial').length >= 1 && Spevb.Recientes.listar().length >= 1);
    ok('la planificación envió soloValidadas=false', Spevb.Recientes.listar()[0].soloValidadas === false);
    // 6) intercambiar origen y destino
    const antes = P.puntos(); document.getElementById('swapTrip').click(); await sleep(400); const des = P.puntos();
    ok('intercambiar A y B', des.origen.lat === antes.destino.lat && des.destino.lat === antes.origen.lat);
    ok('el intercambio anima los pines (pin-swap)', [...document.querySelectorAll('.trip-pin')].some((e) => e.classList.contains('pin-swap')));
    // 7) controles del bus
    P.poner('origin', A); P.poner('destination', B); await sleep(300); P.buscar(); await waitFor(() => document.querySelector('#tripResults .trip-option'), 15000); await sleep(1200);
    const play = document.querySelector('.bus-play'), sp = document.querySelector('.bus-speed'), range = document.querySelector('.bus-range');
    const p0 = play.getAttribute('aria-pressed'); play.click(); const p1 = play.getAttribute('aria-pressed'); play.click();
    ok('bus: pausa y reanudación', p0 !== p1 && play.getAttribute('aria-pressed') === p0);
    const seq = [sp.textContent]; for (let i = 0; i < 3; i++) { sp.click(); seq.push(sp.textContent); } ok('bus: velocidades 1×/2×/4×', seq.join() === '1×,2×,4×,1×', seq.join());
    range.value = 500; range.dispatchEvent(new Event('input')); ok('bus: barra de avance', /\/ \d/.test(document.querySelector('.bus-readout').textContent));
    [...document.querySelectorAll('.bus-btn')].find((b) => b.textContent === '↺').click(); ok('bus: reinicio', range.value === '0');
    let seg = null; window.addEventListener('spevb-follow', (e) => { seg = e.detail; }, { once: true }); const fb = document.querySelector('.bus-follow input'); fb.click(); ok('bus: «Seguir bus»', seg === true && fb.checked); fb.click();
    const b3 = document.querySelector('.bus-3d'); const modo = () => { const w = document.querySelector('.bus3d'); return w.classList.contains('sprite') ? 'sprite' : w.classList.contains('flat') ? '2d' : 'css'; };
    const m0 = modo(); b3.click(); await waitFor(() => modo() !== m0, 5000); const m1 = modo(); b3.click(); await sleep(300);
    ok('bus: cambio 2D ↔ 3D y preferencia guardada', m0 === '2d' && m1 !== '2d' && modo() === '2d' && localStorage.getItem('spevb-bus-modelo') === '2d', m0 + '→' + m1);
    ok('bus: pointer-events none y no intercepta clics', getComputedStyle(document.querySelector('.bus3d')).pointerEvents === 'none');
    // 8) preferencias, daltónico y guía de colores
    const dal = document.querySelector('[data-ajuste="daltonico"]'); dal.click(); await sleep(150); const conDal = document.documentElement.classList.contains('daltonico'); dal.click(); await sleep(150);
    ok('modo para daltónicos', conDal && !document.documentElement.classList.contains('daltonico'));
    Spevb.Ajustes.set('busMin', true); await sleep(100); const colapsado = document.querySelector('.bus-panel').classList.contains('collapsed'); Spevb.Ajustes.set('busMin', false); await sleep(100);
    ok('preferencia: ocultar y mostrar los controles del bus', colapsado && !document.querySelector('.bus-panel').classList.contains('collapsed'));
    // 9) guardar, compartir, reportar
    $('saveTrip').click(); await sleep(300); ok('guardar viaje abre el nombre', !$('nameModal').hidden && getComputedStyle($('nameModal')).display !== 'none'); $('nameCancel').click(); await sleep(200);
    let copiado = null; Object.defineProperty(navigator, 'clipboard', { value: { writeText: async (t) => { copiado = t; } }, configurable: true }); try { Object.defineProperty(navigator, 'share', { value: undefined, configurable: true }); } catch { /* nada */ }
    $('shareTrip').click(); await waitFor(() => copiado, 3000); ok('compartir arma un enlace con el viaje', !!copiado && /^https?:\/\//.test(copiado), copiado && copiado.slice(0, 60));
    $('reportProblem').click(); await sleep(300); ok('reportar abre el formulario', !$('reportModal').hidden && getComputedStyle($('reportModal')).display !== 'none'); $('reportCancel').click(); await sleep(200);
    // 10) GPS: ubicación como origen y seguimiento (geolocalización simulada)
    Object.defineProperty(navigator, 'permissions', { value: { query: async () => ({ state: 'granted' }) }, configurable: true });
    const g = { w: new Map(), n: 1, watchPosition(ok2) { const id = g.n++; g.w.set(id, ok2); return id; }, clearWatch(id) { g.w.delete(id); }, getCurrentPosition() {} };
    Object.defineProperty(navigator, 'geolocation', { value: g, configurable: true });
    const loc = $('locateMe'); loc.click(); await sleep(400); const id = [...g.w.keys()][0]; g.w.get(id)({ coords: { latitude: 4.1432, longitude: -73.6266, accuracy: 25 }, timestamp: Date.now() }); await sleep(300);
    ok('GPS: seguimiento activo y encontrado', loc.getAttribute('aria-pressed') === 'true' && loc.dataset.estado === 'encontrado', loc.dataset.estado); loc.click(); await sleep(200);
    ok('GPS: seguimiento se detiene y libera la escucha', loc.getAttribute('aria-pressed') === 'false' && g.w.size === 0);
    // 11) offline y caminatas guardadas
    ok('uso sin conexión: información y cachés', $('offlineInfo').textContent.length > 3 && typeof Spevb.Caminatas.leer === 'function');
    ok('sin errores de JavaScript durante todo el recorrido', errs.length === 0, errs.join(' | '));
    // limpieza
    for (const k of Object.keys(localStorage)) if (k.startsWith('spevb-')) localStorage.removeItem(k);
    for (const [k, v] of Object.entries(guardado)) localStorage.setItem(k, v);
    return { total: res.length, correctas: res.filter((r) => r.ok).length, fallos: res.filter((r) => !r.ok) };
})()
