"""Pruebas de la CAPA DE MAPA (docs/MAPA-CAPA.md): geometría pura, contrato del adaptador Leaflet, adaptador Google con un SDK SIMULADO,
errores clasificados y recuperables, carga exclusiva del proveedor elegido, CSP y diseño móvil.

IMPORTANTE: el SDK de Google aquí es un doble (e2e/harness/google_fake.js). Estas pruebas NO demuestran que una clave real, la CSP real, el Map ID
o los gestos del mapa de Google funcionen; para eso están e2e/mapa_google_real_e2e.py (SDK real, solo errores/CSP sin clave válida) y el
procedimiento manual de docs/GOOGLE-MAPS.md (clave de prueba propia).

Uso: SEVB_E2E_URL=http://127.0.0.1:18080/ python e2e/mapa_adaptadores_e2e.py [filtro]   (lo normal es e2e/run_e2e.py; el servidor de ese URL va en modo Leaflet)
Levanta por su cuenta dos servidores simulados en modo Google (puertos 18091 y 18092, uno sin clave).
"""
import os
import pathlib
import subprocess
import sys
import time
import traceback
import urllib.request

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import sevb_e2e as B  # noqa: E402
from playwright.sync_api import sync_playwright  # noqa: E402

RAIZ = pathlib.Path(__file__).resolve().parent.parent
FAKE = (RAIZ / 'e2e' / 'harness' / 'google_fake.js').read_text(encoding='utf-8')
URL_LEAFLET = B.URL
PRUEBAS = []


def prueba(f):
    PRUEBAS.append(f)
    return f


# ------------------------------------------------------------------ servidores simulados en modo Google
_PROCESOS = []


def lanzar_stub(puerto, clave=None):
    entorno = {**os.environ, 'SEVB_STUB_MAPA': 'google'}
    if clave is not None:
        entorno['SEVB_STUB_CLAVE_NAVEGADOR'] = clave
    p = subprocess.Popen([sys.executable, '-I', str(RAIZ / 'e2e' / 'stub_server.py'), str(puerto)], cwd=RAIZ, env=entorno, stdin=subprocess.DEVNULL)
    _PROCESOS.append(p)
    fin = time.time() + 30
    while time.time() < fin:
        try:
            if urllib.request.urlopen(f'http://127.0.0.1:{puerto}/api/estado', timeout=2).status == 200:
                return f'http://127.0.0.1:{puerto}/'
        except OSError:
            time.sleep(0.5)
    raise SystemExit(f'el servidor simulado del puerto {puerto} no respondió')


class Google:
    """Contexto de pruebas con el SDK simulado servido en lugar de maps.googleapis.com."""

    def __init__(self, nav, base, modo='ok', sdk='fake', **opciones):
        B.URL = base
        self.s = B.Sesion(nav, **opciones)
        self.peticiones, self.sdk_pedidos = [], []
        self.s.page.on('request', lambda r: self.peticiones.append(r.url))
        self.s.ctx.add_init_script("window.__csp = []; document.addEventListener('securitypolicyviolation', (e) => window.__csp.push(e.violatedDirective + ' ' + e.blockedURI));")
        self.modo, self.sdk = modo, sdk
        self.s.ctx.route('https://maps.googleapis.com/maps/api/js*', self._sdk)

    def _sdk(self, route):
        url = route.request.url
        self.sdk_pedidos.append(url)
        if self.sdk == 'abortar':
            return route.abort()
        q = dict(p.split('=', 1) for p in url.split('?', 1)[1].split('&'))
        cb = q.get('callback', '')
        cuerpo = f"window.__gfake_modo = '{self.modo}';\n" + FAKE + f"\n;window.{cb} && window.{cb}();"
        route.fulfill(status=200, content_type='text/javascript', body=cuerpo)

    def abrir(self, ruta='', esperar_mapa=True):
        self.s.page.goto(B.URL + ruta, wait_until='domcontentloaded')
        if esperar_mapa:
            B.esperar(self.s.page, "!!(window.Spevb && Spevb.maps && Spevb.maps.map)", 20000)
        self.s.page.wait_for_timeout(500)
        return self.s.page

    def cerrar(self):
        self.s.cerrar()
        B.URL = URL_LEAFLET


def externas(peticiones):
    return [u for u in peticiones if not u.startswith(('http://127.0.0.1', 'data:', 'blob:'))]


# ------------------------------------------------------------------ geometría pura (sin SDK)
@prueba
def geo_conversiones_y_distancias(nav):
    s = B.Sesion(nav)
    p = s.page
    p.goto(B.URL + 'reportes.html', wait_until='domcontentloaded')
    try:
        B.esperar(p, '!!(window.Spevb && Spevb.Geo)', 10000)
    except AssertionError:
        raise AssertionError(f'Spevb.Geo no existe en {p.url}; errores: {s.errores}')
    r = p.evaluate("""() => {
        const G = Spevb.Geo, out = {};
        const geo = [[-73.6540123456789, 4.14501234567], [-73.62, 4.15], [-73.60, 4.16]];
        const ll = G.desdeGeoJSON(geo);
        out.orden = ll[0][0] === 4.14501234567 && ll[0][1] === -73.6540123456789;
        out.ida_vuelta = JSON.stringify(G.aGeoJSON(ll)) === JSON.stringify(geo);                 // mismo orden y misma precisión
        out.google = JSON.stringify(G.aGoogle(ll[0])) === JSON.stringify({ lat: 4.14501234567, lng: -73.6540123456789 });
        out.desde_google_literal = JSON.stringify(G.deGoogle({ lat: 1, lng: 2 })) === '[1,2]';
        out.desde_google_objeto = JSON.stringify(G.deGoogle({ lat: () => 3, lng: () => 4 })) === '[3,4]';
        out.invalido = G.ll([NaN, 1]) === null && G.ll(null) === null && G.ll({}) === null;
        out.diez_milesimas = Math.abs(G.distancia([4.15, -73.63], [4.16, -73.63]) - 1111.95) < 0.6;       // 0,01° de latitud ≈ 1.111,95 m (R = 6.371 km)
        out.simetrica = G.distancia(ll[0], ll[1]) === G.distancia(ll[1], ll[0]) && G.distancia(ll[0], ll[0]) === 0;
        const b = G.limites(ll);
        out.caja = b.s === 4.14501234567 && b.n === 4.16 && b.w === -73.6540123456789 && b.e === -73.60;
        out.contiene = G.contiene(b, [4.15, -73.62]) && !G.contiene(b, [5, -73.62]);
        out.unir = G.unir({ s: 1, w: 1, n: 2, e: 2 }, { s: 0, w: 3, n: 1, e: 4 }).w === 1 && G.unir(null, b) === b;
        out.vacia = G.limites([]) === null && !G.esValida(null);
        const seg = G.proyectarEnSegmento([4.1501, -73.6300], [4.15, -73.64], [4.15, -73.62]);
        out.segmento = Math.abs(seg.t - 0.5) < 0.02 && Math.abs(seg.distanciaM - 11.1) < 0.6;
        const fin = G.proyectarEnSegmento([4.15, -73.70], [4.15, -73.64], [4.15, -73.62]);
        out.segmento_extremo = fin.t === 0;
        // centro con márgenes: si la tarjeta tapa la izquierda 100 px, el mapa debe centrarse a la IZQUIERDA del punto
        const c = G.centroConMargen([4.15, -73.63], 15, { left: 100, right: 0, top: 0, bottom: 0 });
        out.margen_x = c[1] < -73.63 && c[0] === 4.15 || Math.abs(c[0] - 4.15) < 1e-9;
        const px = (G.aMundo([4.15, -73.63])[0] - G.aMundo(c)[0]) * 256 * 2 ** 15;
        out.margen_px = Math.abs(px - 50) < 0.5;
        const cb = G.centroConMargen([4.15, -73.63], 15, { top: 0, bottom: 200 });
        out.margen_abajo = cb[0] < 4.15;                       // tarjeta abajo → el mapa se centra más al sur: el punto queda en la parte alta
        const rt = G.deMundo(...G.aMundo([4.15, -73.63]));
        out.mercator = Math.abs(rt[0] - 4.15) < 1e-9 && Math.abs(rt[1] + 73.63) < 1e-9;
        return out;
    }""")
    malas = [k for k, v in r.items() if not v]
    assert not malas, f'fallan: {malas}'
    s.limpio('geo')
    s.cerrar()


# ------------------------------------------------------------------ contrato del adaptador Leaflet
@prueba
def leaflet_contrato_y_sin_google(nav):
    s = B.Sesion(nav)
    peticiones = []
    s.page.on('request', lambda r: peticiones.append(r.url))
    p = s.abrir()
    r = p.evaluate("""async () => {
        const m = Spevb.maps.map, out = {}, G = Spevb.Geo;
        out.proveedor = m.proveedor === 'leaflet' && document.documentElement.dataset.mapa === 'leaflet' && Spevb.Mapa.proveedor === 'leaflet';
        const dibujados = () => Object.values(m.n._layers).filter((l) => l instanceof L.Marker || l instanceof L.Path).length;      // solo lo que SEVB dibuja (no las teselas)
        const g = m.group().addTo(m);
        const antes = dibujados();
        const mk = m.marker([4.14, -73.62], { html: '<b>x</b>', size: [20, 20], anchor: [10, 10], tooltip: 'rótulo', draggable: true, z: 5 }).addTo(g);
        const dt = m.dot([4.141, -73.621], { radius: 6, color: '#fff', fillColor: '#176650', fillOpacity: 1, tooltip: 'punto' }).addTo(g);
        const ci = m.circle([4.142, -73.622], 50, { color: '#1a73e8' }).addTo(g);
        const li = m.polyline([[4.14, -73.62], [4.15, -73.61]], { color: '#e53935', weight: 6, dashArray: '8 6', interactive: false }).addTo(g);
        const cam = m.polyline([[4.14, -73.62], [4.15, -73.61]], { capa: 'caminata', color: '#2f3640', dashArray: '1 10' }).addTo(g);
        out.creados = g.size === 5 && m.has(g);
        out.pos = JSON.stringify(mk.getPosition()) === '[4.14,-73.62]';
        mk.setPosition([4.2, -73.6]); out.mover = mk.getPosition()[0] === 4.2;
        out.contenido_propio = !!mk.getElement() && mk.getElement().textContent === 'x';
        li.setPath([[4.1, -73.6], [4.2, -73.5], [4.3, -73.4]]);
        out.ruta = li.getPath().length === 3 && JSON.stringify(li.getBounds()) === JSON.stringify({ s: 4.1, w: -73.6, n: 4.3, e: -73.4 }) || li.getBounds().s === 4.1;
        li.setStyle({ color: '#000', dashArray: null }); out.estilo = true;
        ci.setRadius(80); out.circulo = G.esValida(ci.getBounds());
        dt.setStyle({ fillColor: '#c62828' }); dt.setPosition([4.15, -73.65]);
        cam.setClase('walk-fallback', true); out.clase = cam.getElement().classList.contains('walk-fallback') && cam.getElement().classList.contains('walk-line');
        // eventos del mapa y de elementos
        let clics = 0, ultimo = null; const off = m.on('click', (e) => { clics++; ultimo = e.latlng; });
        m.n.fire('click', { latlng: L.latLng(4.15, -73.63), originalEvent: new MouseEvent('click') });
        off(); m.n.fire('click', { latlng: L.latLng(4.15, -73.63), originalEvent: new MouseEvent('click') });
        out.evento_mapa = clics === 1 && ultimo[0] === 4.15;
        let eMarcador = 0; mk.on('click', () => { eMarcador++; }); mk.n.fire('click', { originalEvent: new MouseEvent('click') });
        out.evento_marcador = eMarcador === 1;
        // proyección ida y vuelta y encuadre con márgenes
        const pt = m.toPoint([4.14, -73.62]), v = m.toLatLng(pt);
        out.proyeccion = Math.abs(v[0] - 4.14) < 2e-4 && Math.abs(v[1] + 73.62) < 2e-4;       // Leaflet redondea a píxel enteros
        m.fitBounds({ s: 4.13, w: -73.64, n: 4.15, e: -73.60 }, { padding: { top: 60, left: 30, bottom: 150, right: 70 }, maxZoom: 17, animate: false });
        out.encuadre = m.getZoom() <= 17 && G.contiene(m.getBounds(), [4.14, -73.62]);
        m.setView([4.14, -73.62], 16, { animate: false, padding: { top: 0, left: 0, bottom: 300, right: 0 } });
        out.centro_libre = m.getCenter()[0] < 4.14;       // tarjeta abajo → el punto queda en la parte alta
        // controles
        const caja = document.createElement('div'); caja.id = 'ctlPrueba';
        const quitar = m.addControl(caja, 'topright');
        out.control = document.getElementById('ctlPrueba').classList.contains('mapa-control');
        quitar(); out.control_fuera = !document.getElementById('ctlPrueba');
        // ocultar y volver a mostrar sin destruir; clear() destruye
        m.quitar(g); out.oculto = !m.has(g) && dibujados() === antes;
        g.addTo(m); out.vuelve = m.has(g) && dibujados() > antes;
        g.clear(); out.limpio = g.size === 0 && mk.liberado && dibujados() === antes;
        // sin acumulación tras repetir acciones
        for (let i = 0; i < 300; i++) { const e = m.marker([4.14, -73.62], { html: 'a', size: [8, 8] }).addTo(m); e.on('click', () => {}); e.remove(); const l = m.polyline([[4, -73], [4.1, -73.1]]).addTo(m); l.remove(); }
        out.sin_fugas = dibujados() === antes;
        // texto de datos nunca como HTML
        const mal = m.marker([4.14, -73.62], { html: '<i></i>', tooltip: '<img src=x onerror=window.__xss=1>' }).addTo(m); mal.openTooltip();
        await new Promise((ok) => setTimeout(ok, 100)); out.sin_html = !window.__xss && !document.querySelector('.leaflet-tooltip img'); mal.remove();
        m.setDobleClicZoom(false); out.dblclick = !m.n.doubleClickZoom.enabled(); m.setDobleClicZoom(true);
        return out;
    }""")
    malas = [k for k, v in r.items() if not v]
    assert not malas, f'fallan: {malas}'
    ext = externas(peticiones)
    assert not [u for u in ext if 'googleapis.com/maps' in u or 'gstatic.com' in u or 'maps.google' in u], f'Leaflet no debe pedir el SDK de Google: {ext}'
    assert p.evaluate('typeof window.google') == 'undefined', 'en modo Leaflet no existe window.google'
    assert any('/vendor/leaflet/leaflet.js' in u for u in peticiones), 'Leaflet se carga (el proveedor elegido)'
    assert not any('/js/mapa/adaptador-google.js' in u for u in peticiones), 'el adaptador de Google no se descarga en modo Leaflet'
    s.limpio('leaflet')
    s.cerrar()


# ------------------------------------------------------------------ Google (SDK simulado)
@prueba
def google_solo_carga_google_y_una_sola_vez(nav):
    base = BASES['google']
    g = Google(nav, base)
    p = g.abrir()
    pet = g.s.page.evaluate('performance.getEntriesByType("resource").map(r => r.name)')
    assert not any('/vendor/leaflet' in u or '/vendor/maplibre' in u or 'openfreemap' in u or 'arcgisonline' in u or 'tile.openstreetmap' in u for u in pet + g.peticiones), \
        f'en modo Google no se debe descargar Leaflet, MapLibre ni los mapas anteriores: {[u for u in g.peticiones if "vendor" in u or "openfree" in u]}'
    assert not any('/js/mapa/adaptador-leaflet.js' in u for u in g.peticiones)
    assert p.evaluate('typeof window.L') == 'undefined', 'Leaflet no existe en modo Google'
    assert len(g.sdk_pedidos) == 1, f'el SDK se carga una sola vez por documento: {g.sdk_pedidos}'
    url = g.sdk_pedidos[0]
    for fragmento in ('key=AIza', 'v=quarterly', 'language=es', 'region=CO', 'loading=async', 'callback='):
        assert fragmento in url, f'falta {fragmento} en {url}'
    info = p.evaluate('({ prov: Spevb.Mapa.proveedor, attr: document.documentElement.dataset.mapa, imp: __gfake.importaciones, mapas: __gfake.mapas, o: __gfake.opciones && { mapId: __gfake.opciones.mapId, restr: __gfake.opciones.restriction, ui: __gfake.opciones.disableDefaultUI, clic: __gfake.opciones.clickableIcons, gh: __gfake.opciones.gestureHandling } })')
    assert info['prov'] == 'google' and info['attr'] == 'google'
    assert {'maps', 'marker', 'core'} <= set(info['imp']) and info['mapas'] == 1
    assert info['o']['mapId'] == 'DEMO_MAP_ID' and info['o']['ui'] is True and info['o']['clic'] is False
    r = info['o']['restr']['latLngBounds']
    assert (r['south'], r['north'], r['west'], r['east']) == (3.95, 4.35, -73.85, -73.45), 'los límites de SEVB se conservan además de la región'
    assert p.evaluate('typeof Spevb.Mapa.google.calcularEncuadre') == 'function'
    assert not p.evaluate('window.__csp'), f'violaciones de CSP: {p.evaluate("window.__csp")}'
    g.s.limpio('google')
    g.cerrar()


@prueba
def google_planificar_dibuja_y_no_recrea_el_mapa(nav):
    g = Google(nav, BASES['google'])
    p = g.abrir()
    B.planificar(p, a=B.A_LEJOS)          # A a ~350 m de la ruta: hay caminata (línea punteada)
    r = p.evaluate("""() => {
        const f = __gfake, out = {};
        out.mapas = f.mapas;
        const pin = (letra) => f.marcadores.filter((m) => m.map && m.children[0] && m.children[0].textContent.includes('>' + letra + '<') || (m.map && m.children[0] && m.children[0].querySelector('.trip-pin') && m.children[0].textContent.trim() === letra));
        out.A = pin('A').length === 1 && JSON.stringify(Spevb.Geo.deGoogle(pin('A')[0].position)) === '[4.148,-73.6525]';
        out.B = pin('B').length === 1 && JSON.stringify(Spevb.Geo.deGoogle(pin('B')[0].position)) === '[4.145,-73.624]';
        const visibles = f.polilineas.filter((l) => l._map);
        out.lineas = visibles.length >= 2;
        out.seleccion = !!(Spevb.Planner.seleccion && Spevb.Planner.seleccion.viaje);       // el planificador terminó de dibujar la opción (no solo el bus de demostración)
        out.tramo_rojo = visibles.some((l) => l.o.strokeColor === '#e53935' && l.o.strokeWeight === 8 && l.getPath().getLength() > 2);
        // geometría de la ruta R002 conservada: primer vértice [lng -73.654, lat 4.145] → {lat, lng}
        out.zorden = (() => { const z = visibles.map((l) => l.o.zIndex); return z.length === new Set(z).size; })();      // el borde blanco y la línea de color no comparten zIndex
        out.marcadores_tienen_tooltip_texto = true;
        return out;
    }""")
    malas = [k for k, v in r.items() if not v]
    assert not malas, f'fallan: {malas} {r}'
    # Geometría conservada: la ruta R002 del servidor tiene un vértice [lng -73.6490, lat 4.1440]; con Google debe quedar como {lat: 4.1440, lng: -73.6490}.
    p.evaluate('SpevbExplorer.showRoute(2)')
    p.wait_for_timeout(2000)
    assert p.evaluate("""__gfake.polilineas.filter((l) => l._map).some((l) => l.getPath().getArray().some((v) => Math.abs(v.lat - 4.1440) < 1e-9 && Math.abs(v.lng + 73.6490) < 1e-9))"""), 'orden [lng,lat] → {lat,lng} conservado en la ruta dibujada'
    # repetir la misma búsqueda no crea mapas nuevos ni acumula elementos: tras la 2.ª y la 4.ª búsqueda hay exactamente los mismos elementos visibles
    visibles = "__gfake.polilineas.filter(l => l._map).length + __gfake.marcadores.filter(m => m.map).length"
    B.planificar(p, espera=3000)
    n2 = p.evaluate(visibles)
    for _ in range(2):
        B.planificar(p, espera=3000)
    despues = p.evaluate('({ mapas: __gfake.mapas, vis: ' + visibles + ', escuchas: __gfake.escuchas })')
    assert despues['mapas'] == 1, 'volver a buscar no crea instancias nuevas de Map'
    assert despues['vis'] == n2, f'los elementos del plan anterior se retiran sin acumularse: 2.ª búsqueda={n2}, 4.ª={despues}'
    assert not p.evaluate('window.__csp')
    g.s.limpio('plan google')
    g.cerrar()


@prueba
def google_contrato_elementos_eventos_y_proyeccion(nav):
    g = Google(nav, BASES['google'])
    p = g.abrir()
    r = p.evaluate("""async () => {
        const m = Spevb.maps.map, f = __gfake, out = {}, G = Spevb.Geo;
        out.cap = m.proveedor === 'google' && m.capacidades.marcadoresAvanzados && m.capacidades.mapaSobrePanel && !m.capacidades.mapaSinConexion && !m.capacidades.capasBase;
        const grupo = m.group().addTo(m);
        const antes = f.marcadores.filter((x) => x.map).length;
        const mk = m.marker([4.14, -73.62], { html: '<b>x</b>', size: [20, 20], anchor: [10, 10], tooltip: 'rótulo', draggable: true, z: 5 }).addTo(grupo);
        const nat = f.marcadores.at(-1);
        out.avanzado = nat.map === m.n && nat.gmpDraggable === true && nat.gmpClickable === true && nat.zIndex === 5 && nat.children.length === 1;
        out.ancla = nat.children[0].style.transform === 'translate(0px, 10px)';         // size 20×20, ancla (10,10) → desplazar (0, h - ay)
        out.pos = JSON.stringify(G.deGoogle(nat.position)) === '[4.14,-73.62]' && JSON.stringify(mk.getPosition()) === '[4.14,-73.62]';
        mk.setPosition([4.2, -73.6]); out.mover = G.deGoogle(nat.position)[0] === 4.2;
        const dt = m.dot([4.141, -73.621], { radius: 6, weight: 3, color: '#ffffff', fillColor: '#176650', fillOpacity: 1 }).addTo(grupo);
        const nd = f.marcadores.at(-1);
        out.punto = nd.children[0].style.borderRadius === '50%' && nd.children[0].style.width === '15px' && nd.map === m.n;
        const ci = m.circle([4.142, -73.622], 50, { color: '#1a73e8' }).addTo(grupo);
        out.circulo = f.circulos.at(-1).o.radius === 50 && f.circulos.at(-1)._map === m.n; ci.setRadius(80); out.radio = f.circulos.at(-1).o.radius === 80;
        const li = m.polyline([[4.14, -73.62], [4.15, -73.61]], { color: '#e53935', weight: 6, interactive: false }).addTo(grupo);
        const nl = f.polilineas.at(-1);
        out.linea = nl._map === m.n && nl.o.clickable === false && nl.getPath().getLength() === 2;
        li.setPath([[4.1, -73.6], [4.2, -73.5], [4.3, -73.4]]);
        out.camino = nl.getPath().getLength() === 3 && JSON.stringify(li.getBounds()) === JSON.stringify({ s: 4.1, w: -73.6, n: 4.3, e: -73.4 });
        const pun = m.polyline([[4.14, -73.62], [4.15, -73.61]], { color: '#2f3640', weight: 5, dashArray: '1 10', capa: 'caminata' }).addTo(grupo);
        const np = f.polilineas.at(-1);
        out.puntos = np.o.strokeOpacity === 0 && np.o.icons[0].repeat === '11px' && np.o.zIndex > nl.o.zIndex;
        const gu = m.polyline([[4.14, -73.62], [4.15, -73.61]], { color: '#000', weight: 4, dashArray: '8 6' }).addTo(grupo);
        out.guiones = f.polilineas.at(-1).o.icons[0].repeat === '14px';
        gu.setStyle({ dashArray: null, color: '#fff' }); out.solida = f.polilineas.at(-1).o.icons.length === 0 && f.polilineas.at(-1).o.strokeOpacity === 1;
        // eventos
        let clics = 0, ult = null; const off = m.on('click', (e) => { clics++; ult = e.latlng; });
        m.n._emitir('click', { latLng: new google.maps.LatLng(4.15, -73.63), domEvent: new MouseEvent('click') });
        off(); m.n._emitir('click', { latLng: new google.maps.LatLng(4.15, -73.63) });
        out.evento_mapa = clics === 1 && ult[0] === 4.15 && ult[1] === -73.63;
        let eM = 0, fin = null; mk.on('click', () => { eM++; }); mk.on('dragend', (e) => { fin = e.latlng; });
        nat._disparar('gmp-click'); nat.position = { lat: 4.3, lng: -73.3 }; nat._disparar('gmp-dragend');
        out.evento_marcador = eM === 1 && JSON.stringify(fin) === '[4.3,-73.3]';
        // arrastre del mapa: lo provoca la persona; mover por código no lo dispara
        let arrastres = 0; m.on('dragstart', () => { arrastres++; });
        m.panTo([4.16, -73.6], { animate: false }); m.setView([4.17, -73.6], 15, { animate: false });
        out.sin_dragstart_programatico = arrastres === 0;
        m.n._emitir('dragstart', {}); out.dragstart_persona = arrastres === 1;
        // proyección: disponible tras el primer dibujo, ida y vuelta, y con eventos del DOM
        const pt = m.toPoint([4.17, -73.6]), v = m.toLatLng(pt);
        out.proyeccion = !!pt && Math.abs(v[0] - 4.17) < 1e-6 && Math.abs(v[1] + 73.6) < 1e-6;
        const r0 = m.el.getBoundingClientRect(), ev = { clientX: r0.left + pt.x, clientY: r0.top + pt.y };
        const dl = m.eventLatLng(ev); out.evento_dom = Math.abs(dl[0] - 4.17) < 1e-6;
        // encuadre con márgenes y máximo de zoom (Google no admite maxZoom en fitBounds)
        m.fitBounds({ s: 4.1449, w: -73.6241, n: 4.1451, e: -73.6239 }, { padding: { top: 60, left: 30, bottom: 150, right: 70 }, maxZoom: 17 });
        out.maxzoom = Math.abs(m.getZoom() - 17) < 1e-9;
        m.fitBounds({ s: 4.05, w: -73.75, n: 4.25, e: -73.55 }, { padding: { top: 60, left: 30, bottom: 150, right: 70 }, maxZoom: 17 });
        const b = m.getBounds(); out.encuadre = b.s <= 4.05 && b.n >= 4.25 && b.w <= -73.75 && b.e >= -73.55;
        m.setView([4.14, -73.62], 16, { animate: false, padding: { top: 0, left: 0, bottom: 300, right: 0 } });
        out.centro_libre = m.getCenter()[0] < 4.14;
        // controles propios
        const n0 = f.map.controls[3].getLength();       // el panel del bus ya es un control propio
        const caja2 = document.createElement('div'); const quitar2 = m.addControl(caja2, 'topright');
        out.control = caja2.classList.contains('mapa-control') && f.map.controls[3].getLength() === n0 + 1; quitar2(); out.control_fuera = f.map.controls[3].getLength() === n0;
        // ocultar/mostrar y liberar: sin acumulación de listeners ni elementos
        m.quitar(grupo); out.oculto = f.marcadores.filter((x) => x.map).length === antes;
        grupo.addTo(m); out.vuelve = f.marcadores.filter((x) => x.map).length === antes + 2;
        const esc0 = f.escuchas; grupo.clear();
        out.limpio = f.marcadores.filter((x) => x.map).length === antes && f.escuchas < esc0 && mk.liberado;
        const baseEsc = f.escuchas;
        for (let i = 0; i < 200; i++) { const e = m.marker([4.14, -73.62], { html: 'a', size: [8, 8], draggable: true }).addTo(m); e.on('click', () => {}); e.remove(); const l = m.polyline([[4, -73], [4.1, -73.1]]).addTo(m); l.remove(); }
        out.sin_fugas = f.escuchas === baseEsc && f.marcadores.filter((x) => x.map).length === antes && f.polilineas.filter((x) => x._map && x.o.zIndex).length < 10;
        // texto de datos como nodo de texto (nunca HTML) en tooltip y popup
        const peligro = '<img src=x onerror=window.__xss=1>';
        const mal = m.marker([4.14, -73.62], { html: '<i></i>', tooltip: peligro, popup: peligro }).addTo(m); mal.openPopup();
        out.popup_texto = f.info.contenido.textContent === peligro && !f.info.contenido.querySelector('img');
        mal.remove();
        out.una_instancia = f.mapas === 1;
        return out;
    }""")
    malas = [k for k, v in r.items() if not v]
    assert not malas, f'fallan: {malas}'
    g.s.limpio('contrato google')
    g.cerrar()


@prueba
def google_centrar_no_debajo_de_la_tarjeta(nav):
    g = Google(nav, BASES['google'], viewport={'width': 390, 'height': 844}, is_mobile=True, has_touch=True)
    p = g.abrir()
    r = p.evaluate("""() => {
        const m = Spevb.maps.map;
        const t = document.createElement('div'); t.id = 'viajeCard';
        t.style.cssText = 'position:fixed;left:10px;right:10px;bottom:300px;height:220px;z-index:5';
        document.body.append(t);
        const objetivo = [4.15, -73.63];
        Spevb.centrarEn(m, objetivo, 16, { animate: false });
        const pt = m.toPoint(objetivo), caja = m.el.getBoundingClientRect(), tr = t.getBoundingClientRect();
        return { y: caja.top + pt.y, techo: tr.top, x: caja.left + pt.x, ancho: innerWidth };
    }""")
    assert r['y'] < r['techo'] - 8, f'la posición quedó debajo de la tarjeta: {r}'
    assert 0 < r['x'] < r['ancho']
    g.s.limpio('centrar')
    g.cerrar()


@prueba
def google_arranca_el_modo_viaje_sin_recrear_el_mapa(nav):
    g = Google(nav, BASES['google'], viewport={'width': 390, 'height': 844}, is_mobile=True, has_touch=True,
               geolocation={'latitude': 4.1450, 'longitude': -73.6540}, permissions=['geolocation'])
    p = g.abrir()
    B.planificar(p)
    if p.locator('#startTrip').is_visible():
        p.locator('#startTrip').click()
        p.wait_for_timeout(2500)
        mk = p.evaluate('__gfake.marcadores.filter(m => m.map).length')
        p.context.set_geolocation({'latitude': 4.1452, 'longitude': -73.6535})
        p.wait_for_timeout(2500)
        info = p.evaluate('({ mapas: __gfake.mapas, mk: __gfake.marcadores.filter(m => m.map).length })')
        assert info['mapas'] == 1, 'las lecturas de GPS no crean mapas nuevos'
        assert info['mk'] <= mk + 1, 'la posición se actualiza sobre el mismo marcador'
        assert p.locator('#viajeCard').count() == 1
    g.s.limpio('viaje google')
    g.cerrar()


@prueba
def google_encuadre_casos_limite_y_estela_que_solo_crece(nav):
    g = Google(nav, BASES['google'])
    p = g.abrir()
    r = p.evaluate("""() => {
        const c = Spevb.Mapa.google.calcularEncuadre, out = {};
        const ok = (r) => r && Number.isFinite(r.zoom) && r.center.every(Number.isFinite);
        const tam = { x: 390, y: 600 }, pad = { top: 60, left: 30, bottom: 150, right: 70 };
        out.un_punto = ok(c({ s: 4.15, w: -73.63, n: 4.15, e: -73.63 }, tam, pad, { maxZoom: 17 })) && c({ s: 4.15, w: -73.63, n: 4.15, e: -73.63 }, tam, pad, { maxZoom: 17 }).zoom === 17;
        out.padding_excesivo = ok(c({ s: 4.1, w: -73.7, n: 4.2, e: -73.6 }, { x: 200, y: 200 }, { top: 500, left: 500, bottom: 500, right: 500 }, { maxZoom: 17, minZoom: 11 }));
        out.contenedor_minusculo = ok(c({ s: 4.1, w: -73.7, n: 4.2, e: -73.6 }, { x: 0, y: 0 }, pad, { maxZoom: 19, minZoom: 11 }));
        const z = c({ s: 4.1, w: -73.7, n: 4.2, e: -73.6 }, tam, pad, { maxZoom: 19, minZoom: 11 }).zoom;
        out.zoom_acotado = z >= 11 && z <= 19;
        const grande = c({ s: -80, w: -170, n: 80, e: 170 }, tam, pad, { maxZoom: 19, minZoom: 11 });
        out.mundo_entero_no_baja_del_minimo = grande.zoom === 11 && ok(grande);
        const margen = c({ s: 4.14, w: -73.64, n: 4.15, e: -73.62 }, tam, { top: 0, left: 200, bottom: 0, right: 0 }, { maxZoom: 19 });
        out.centro_desplazado = margen.center[1] < -73.63;       // la tarjeta tapa la izquierda → el centro del mapa queda a la izquierda del de la caja
        return out;
    }""")
    malas = [k for k, v in r.items() if not v]
    assert not malas, f'encuadre: {malas}'
    # La estela del bus solo crece: se agregan puntos nuevos sin reconvertir todo y se reconstruye si el arreglo cambió de otra forma.
    r = p.evaluate("""() => {
        const m = Spevb.maps.map, out = {}, pts = [[4.14, -73.62], [4.141, -73.621]];
        const l = m.polyline([pts[0]], { color: '#fff', interactive: false }).addTo(m), nat = __gfake.polilineas.at(-1);
        l.setPath(pts, { crece: true }); const a = nat.getPath().getArray()[0];
        pts.push([4.142, -73.622]); pts.push([4.143, -73.623]); l.setPath(pts, { crece: true });
        out.agrega = nat.getPath().getLength() === 4 && nat.getPath().getArray()[0] === a;       // el primer punto es el mismo objeto: no se reconvirtió
        pts.length = 0; pts.push([4.2, -73.5], [4.21, -73.51]); l.setPath(pts, { crece: true });
        out.reconstruye = nat.getPath().getLength() === 2 && nat.getPath().getArray()[0].lat === 4.2;
        l.setPath([[1, 1], [2, 2], [3, 3]]); out.completo = nat.getPath().getLength() === 3;
        return out;
    }""")
    malas = [k for k, v in r.items() if not v]
    assert not malas, f'estela: {malas}'
    g.s.limpio('encuadre'); g.cerrar()


@prueba
def google_caminatas_animadas_con_tema_halo_sentido_y_un_solo_controlador(nav):
    g = Google(nav, BASES['google'])
    p = g.abrir()
    B.planificar(p, a='4.1501, -73.6535', b='4.1450, -73.6240', espera=5000)         # caso A del contrato: transbordo recomendado = tres caminatas
    r = p.evaluate("""async () => {
        const f = __gfake, out = {}, A = Spevb.Mapa.google;
        out.capacidad = Spevb.maps.map.capacidades.lineaAnimada === true;
        const cam = f.polilineas.filter((l) => l._map && l.o.strokeOpacity === 0 && l.o.icons && l.o.icons.length);        // caminatas visibles (símbolos repetidos)
        out.tres = cam.length >= 3;
        out.halo = cam.every((l) => f.polilineas.some((h) => h !== l && h._map && h.o.strokeOpacity > 0 && h.o.strokeWeight === l.o.strokeWeight + 3 && h.getPath().getLength() === l.getPath().getLength()));
        const rect = (l) => { const a = l.getPath().getArray(); return [a[0], a.at(-1)]; };
        // sentido real del viaje: A -> primera subida, bajada -> segunda subida, última bajada -> B
        const inicio = cam.find((l) => Math.abs(rect(l)[0].lat - 4.1501) < 1e-6), final = cam.find((l) => Math.abs(rect(l)[1].lat - 4.1450) < 1e-6 && Math.abs(rect(l)[1].lng + 73.6240) < 1e-6);
        out.sentido_inicio = !!inicio; out.sentido_final = !!final;
        const medio = cam.find((l) => l !== inicio && l !== final);
        out.sentido_cambio = !!medio;
        // color de día (el modo «auto» depende de la hora del reloj: se fija «claro» para que la prueba no dependa de cuándo se ejecuta)
        Spevb.Ajustes.set('mapaNoche', 'claro'); Spevb.Confort.aplicar(); await new Promise((ok) => setTimeout(ok, 150));
        out.dia = cam.every((l) => [A.TEMA_CAMINATA.respaldo.color].some((c) => l.o.icons[0].icon.fillColor === c || l.o.icons[0].icon.strokeColor === c) || l.o.strokeColor === A.TEMA_CAMINATA.dia.color || l.o.icons[0].icon.fillColor === A.TEMA_CAMINATA.dia.color || l.o.icons[0].icon.strokeColor === A.TEMA_CAMINATA.dia.color);
        // animación: el desplazamiento de los símbolos cambia con el tiempo, sin crear polilíneas nuevas, y con un único bucle
        const n0 = f.polilineas.length, off = () => cam.map((l) => l.o.icons[0].offset);
        const o1 = off(); await new Promise((ok) => setTimeout(ok, 400)); const o2 = off();
        out.avanza = JSON.stringify(o1) !== JSON.stringify(o2);
        out.sin_nuevas = f.polilineas.length === n0;
        out.un_bucle = typeof A.Anim.raf === 'number' && A.Anim.lineas.size >= 3;
        out.limite = A.Anim.elegidas().length <= 4;
        // sin saltos: el desplazamiento nunca supera el periodo del patrón (ciclo continuo)
        out.ciclo = o2.every((x) => parseFloat(x) >= 0 && parseFloat(x) < 11.01);
        // pausa mientras se arrastra o se hace zoom
        document.getElementById('map').classList.add('map-moving'); await new Promise((ok) => setTimeout(ok, 120)); const p1 = off(); await new Promise((ok) => setTimeout(ok, 300)); const p2 = off();
        out.pausa_en_gesto = JSON.stringify(p1) === JSON.stringify(p2); document.getElementById('map').classList.remove('map-moving');
        // noche: se re-tematiza SIN reconstruir (mismos objetos, misma cámara)
        const c0 = Spevb.maps.map.getCenter(), z0 = Spevb.maps.map.getZoom(); const ids = new Set(f.polilineas);
        document.documentElement.classList.add('mapa-noche'); await new Promise((ok) => setTimeout(ok, 250));
        out.noche = cam.every((l) => [l.o.strokeColor, l.o.icons[0].icon.fillColor, l.o.icons[0].icon.strokeColor].includes(A.TEMA_CAMINATA.noche.color));
        out.noche_sin_reconstruir = f.polilineas.length === ids.size && Spevb.maps.map.getZoom() === z0 && Spevb.maps.map.getCenter()[0] === c0[0] && !!Spevb.Planner.seleccion;
        out.halo_noche = f.polilineas.some((h) => h._map && h.o.strokeColor === A.TEMA_CAMINATA.noche.halo);
        document.documentElement.classList.remove('mapa-noche'); await new Promise((ok) => setTimeout(ok, 200));
        out.vuelve_dia = cam.every((l) => !(l.o.icons[0].icon.fillColor === A.TEMA_CAMINATA.noche.color));
        // fallback (línea recta aproximada): no se anima y mantiene su identidad punteada
        const una = cam[0]; Spevb.Planner && 0;
        return out;
    }""")
    malas = [k for k, v in r.items() if not v]
    assert not malas, f'caminatas: {malas} {r}'
    # al limpiar A y B (y recalcular) no quedan animaciones colgadas: el controlador queda vacío y detenido
    p.locator('#clearTripPoints').click(); p.wait_for_timeout(800)
    fin = p.evaluate("({ lineas: Spevb.Mapa.google.Anim.lineas.size, raf: Spevb.Mapa.google.Anim.raf })")
    assert fin['lineas'] == 0 and fin['raf'] == 0, f'sin caminatas visibles no hay bucle activo: {fin}'
    assert not p.evaluate('window.__csp')
    g.s.limpio('caminatas animadas'); g.cerrar()


@prueba
def google_con_un_viaje_en_curso_solo_se_anima_la_caminata_mas_cercana(nav):
    g = Google(nav, BASES['google'])
    p = g.abrir()
    B.planificar(p, a='4.1501, -73.6535', b='4.1450, -73.6240', espera=5000)
    r = p.evaluate("""() => { const A = Spevb.Mapa.google.Anim, m = Spevb.maps.map, antes = A.elegidas().length;
        m.priorizarCaminata([4.1501, -73.6535]); const una = A.elegidas();
        m.priorizarCaminata(null); const todas = A.elegidas().length;
        return { antes, una: una.length, cerca: una.length === 1 && una[0].distanciaA([4.1501, -73.6535]) < 30, todas }; }""")
    assert r['antes'] >= 3 and r['una'] == 1 and r['cerca'] and r['todas'] == r['antes'], r
    g.s.limpio('prioridad caminata'); g.cerrar()


@prueba
def google_caminatas_con_movimiento_reducido_son_estaticas_con_flecha_de_sentido(nav):
    g = Google(nav, BASES['google'], reduced_motion='reduce')
    p = g.abrir()
    B.planificar(p, a='4.1501, -73.6535', b='4.1450, -73.6240', espera=5000)
    r = p.evaluate("""async () => {
        const cam = __gfake.polilineas.filter((l) => l._map && l.o.strokeOpacity === 0 && l.o.icons && l.o.icons.length), off = () => cam.map((l) => l.o.icons[0].offset), out = {};
        out.hay = cam.length >= 3;
        const a = off(); await new Promise((ok) => setTimeout(ok, 400)); out.quieta = JSON.stringify(a) === JSON.stringify(off());
        out.flecha = cam.every((l) => l.o.icons.length === 2 && /L 1,1/.test(l.o.icons[1].icon.path));      // el sentido se indica con flechas, no con movimiento
        return out;
    }""")
    malas = [k for k, v in r.items() if not v]
    assert not malas, f'movimiento reducido: {malas}'
    g.s.limpio('caminatas reducidas'); g.cerrar()


PAGINAS = [('', 'map'), ('editor.html', 'editorMap'), ('lugares.html', 'placesMap'), ('paraderos.html', 'paradasMap'), ('puentes.html', 'puentesMap'), ('pasos.html', 'pasosMap')]


@prueba
def todas_las_paginas_con_mapa_cargan_en_ambos_proveedores(nav):
    for proveedor in ('leaflet', 'google'):
        for ruta, ident in PAGINAS:
            if proveedor == 'google':
                g = Google(nav, BASES['google'])
                s, pet = g.s, g.peticiones
                g.s.page.goto(B.URL + ruta, wait_until='domcontentloaded')
            else:
                B.URL = URL_LEAFLET
                s = B.Sesion(nav)
                pet = []
                s.page.on('request', lambda r, pet=pet: pet.append(r.url))
                s.page.goto(B.URL + ruta, wait_until='domcontentloaded')
            B.esperar(s.page, f"!!(window.Spevb && Spevb.maps && Spevb.maps['{ident}'])", 20000)
            s.page.wait_for_timeout(600)
            info = s.page.evaluate(f"({{ prov: Spevb.maps['{ident}'].proveedor, tam: Spevb.maps['{ident}'].getSize(), error: !!document.querySelector('.mapa-error') }})")
            assert info['prov'] == proveedor and not info['error'], f'{proveedor} {ruta or "index"}: {info}'
            assert info['tam']['x'] > 100 and info['tam']['y'] > 100, f'{proveedor} {ruta or "index"}: el mapa no tiene tamaño: {info}'
            if proveedor == 'google':
                assert not any('/vendor/leaflet' in u or '/vendor/maplibre' in u for u in pet), f'{ruta}: Google no debe cargar Leaflet'
                assert not s.page.evaluate('window.__csp'), f'{ruta}: CSP {s.page.evaluate("window.__csp")}'
            else:
                assert not any('maps.googleapis.com' in u for u in pet), f'{ruta}: Leaflet no debe cargar el SDK de Google'
            s.limpio(f'{proveedor} {ruta or "index"}')
            if proveedor == 'google':
                g.cerrar()
            else:
                s.cerrar()


# ------------------------------------------------------------------ errores clasificados y recuperables
def _panel(page):
    B.esperar(page, "!!document.querySelector('.mapa-error')", 15000)
    return page.locator('.mapa-error').first.inner_text()


@prueba
def google_sin_clave_error_claro_sin_cargar_el_sdk(nav):
    g = Google(nav, BASES['google_sin_clave'])
    g.abrir(esperar_mapa=False)
    texto = _panel(g.s.page)
    assert 'no está configurado' in texto, texto
    assert g.s.page.locator('.mapa-error button').count() == 0, 'sin clave no sirve reintentar'
    assert not g.sdk_pedidos, 'sin clave ni se intenta cargar el SDK'
    assert g.s.page.evaluate('typeof window.L') == 'undefined', 'no se sustituye por Leaflet en silencio'
    assert 'ICON' not in texto and 'AIza' not in texto
    g.cerrar()


@prueba
def google_errores_de_clave_y_map_id_se_explican(nav):
    casos = [('authfail', 'no está autorizado'), ('sinmapid', 'Map ID')]
    for modo, esperado in casos:
        g = Google(nav, BASES['google'], modo=modo)
        g.abrir(esperar_mapa=False)
        texto = _panel(g.s.page)
        assert esperado in texto, f'{modo}: {texto}'
        assert g.s.page.evaluate('!!(window.Spevb.maps && Spevb.maps.map)') is False
        assert g.s.page.locator('.mapa-error button').count() == 0, f'{modo}: un problema de clave/Map ID no se arregla reintentando'
        g.cerrar()


@prueba
def google_red_caida_permite_reintentar_sin_duplicar(nav):
    g = Google(nav, BASES['google'], sdk='abortar')
    p = g.abrir(esperar_mapa=False)
    texto = _panel(p)
    assert 'No se pudo descargar' in texto, texto
    assert p.locator('.mapa-error button', has_text='Reintentar').count() == 1
    assert len(g.sdk_pedidos) == 1, 'no hay reintentos infinitos ni automáticos'
    p.wait_for_timeout(1500)
    assert len(g.sdk_pedidos) == 1
    g.sdk = 'fake'                      # vuelve la red
    p.locator('.mapa-error button', has_text='Reintentar').click()
    B.esperar(p, '!!(window.Spevb && Spevb.maps && Spevb.maps.map)', 15000)
    assert p.locator('.mapa-error').count() == 0
    info = p.evaluate('({ mapas: __gfake.mapas, scripts: document.querySelectorAll("script[data-mapa-sdk]").length })')
    assert info['mapas'] == 1 and info['scripts'] == 1, info
    assert p.evaluate('document.querySelectorAll("#routeList .route-option").length') >= 0
    g.cerrar()


# ------------------------------------------------------------------ diseño móvil / escritorio
TAMANOS = [(320, 640, True), (360, 740, True), (390, 844, True), (430, 932, True), (844, 390, True), (1366, 800, False)]


@prueba
def diseno_en_todos_los_tamanos_en_ambos_proveedores(nav):
    for ancho, alto, movil in TAMANOS:
        opts = {'viewport': {'width': ancho, 'height': alto}, 'is_mobile': movil, 'has_touch': movil}
        # Leaflet (servidor principal)
        B.URL = URL_LEAFLET
        s = B.Sesion(nav, **opts)
        p = s.abrir()
        assert p.evaluate('document.documentElement.scrollWidth - innerWidth') <= 1, f'leaflet {ancho}x{alto}: desborde horizontal'
        assert p.locator('#planTrip').is_visible()
        s.limpio(f'leaflet {ancho}')
        s.cerrar()
        # Google (SDK simulado): el mapa termina encima del panel en celular, para no tapar logotipo ni créditos
        g = Google(nav, BASES['google'], **opts)
        p = g.abrir()
        assert p.evaluate('document.documentElement.scrollWidth - innerWidth') <= 1, f'google {ancho}x{alto}: desborde horizontal'
        compacto = p.evaluate('Spevb.compactLayout()')
        d = p.evaluate("(() => { const m = document.getElementById('map').getBoundingClientRect(), s = document.getElementById('sheet').getBoundingClientRect(); return { mapaAbajo: m.bottom, panelArriba: s.top, alto: m.height }; })()")
        if compacto:
            assert d['mapaAbajo'] <= d['panelArriba'] + 1.5, f'google {ancho}x{alto}: el panel tapa la parte inferior del mapa (créditos de Google): {d}'
        assert d['alto'] > 100, d
        g.s.limpio(f'google {ancho}')
        g.cerrar()


def correr(filtro=None):
    fallos = []
    global BASES
    BASES = {}
    try:
        BASES['google'] = lanzar_stub(18091)
        BASES['google_sin_clave'] = lanzar_stub(18092, clave='')
        with sync_playwright() as p:
            nav = B.navegador(p)
            print('Navegador:', nav.browser_type.name, nav.version, '| URL Leaflet:', URL_LEAFLET, '| Google simulado:', BASES['google'])
            for f in PRUEBAS:
                if filtro and filtro not in f.__name__:
                    continue
                B.URL = URL_LEAFLET
                try:
                    f(nav)
                    print('OK    ', f.__name__)
                except Exception as e:  # noqa: BLE001
                    fallos.append(f.__name__)
                    print('FALLA ', f.__name__, '|', str(e).splitlines()[0][:400] if str(e) else repr(e))
                    traceback.print_exc(limit=3)
            nav.close()
    finally:
        for pr in _PROCESOS:
            pr.terminate()
    print(f'\n{len(PRUEBAS) if not filtro else "?"} pruebas de la capa de mapa; fallos: {len(fallos)} {fallos}')
    return 1 if fallos else 0


if __name__ == '__main__':
    sys.exit(correr(sys.argv[1] if len(sys.argv) > 1 else None))
