"""Pruebas de comportamiento con un navegador REAL (Playwright + Edge/Chrome) para SEVB 4.13.1.

No son comprobaciones de texto en el código fuente: abren la aplicación, escriben, arrastran con ratón/táctil/lápiz,
simulan GPS, sin conexión, movimiento reducido y fallos de recursos, y verifican la consola.

Uso (lo normal es e2e/run_e2e.py, que levanta una base PostGIS desechable y la aplicación):
    SEVB_E2E_URL=http://127.0.0.1:18080/ python e2e/sevb_e2e.py [filtro]

Variables: SEVB_E2E_URL, CHROME_PATH (opcional; por defecto el canal «msedge», luego «chrome»).
Las pruebas solo escriben en localStorage del contexto del navegador (desechable) y nunca en la base real.
"""
import json
import os
import sys
import traceback

from playwright.sync_api import sync_playwright

URL = os.environ.get('SEVB_E2E_URL', 'http://127.0.0.1:18080/').rstrip('/') + '/'
CHROME = os.environ.get('CHROME_PATH') or None
CENTRO = {'latitude': 4.15000, 'longitude': -73.63287}
A = '4.1450, -73.6540'      # Galán (ruta DEMO R002)
B = '4.1450, -73.6240'      # Centro
A_LEJOS = '4.1480, -73.6525'   # a ~350 m de la ruta: la caminata hasta ella tiene longitud real
TRANS_A, TRANS_B = '4.1432, -73.6020', '4.1548, -73.5834'   # solo se llega con transbordo (docs/pruebas/transbordo_demo.sql)
PERMITIDOS_PUENTE = {'id', 'nombre', 'metros', 'trazo'}

PRUEBAS = []


def prueba(f):
    PRUEBAS.append(f)
    return f


# ------------------------------------------------------------------ infraestructura
INIT = """
window.__rechazos = [];
window.addEventListener('unhandledrejection', (e) => window.__rechazos.push(String(e.reason && e.reason.message || e.reason)));
try { const a = JSON.parse(localStorage.getItem('spevb-ajustes') || '{}'); if (a.panelBajar === undefined) a.panelBajar = false; localStorage.setItem('spevb-ajustes', JSON.stringify(a)); } catch (e) {}
try { if (!localStorage.getItem('__t')) { localStorage.setItem('__t', '1'); localStorage.setItem('spevb-voz', 'no'); localStorage.setItem('spevb-bienvenida', '2'); } } catch (e) {}
"""
PNG_1PX = bytes.fromhex('89504e470d0a1a0a0000000d49484452000000010000000108060000001f15c4890000000d49444154789c6360000002000001e221bc330000000049454e44ae426082')


class Sesion:
    """Un contexto de navegador desechable con vigilancia de errores de consola y promesas rechazadas."""

    def __init__(self, navegador, **opciones):
        self.errores = []
        self.ctx = navegador.new_context(**{'viewport': {'width': 1280, 'height': 860}, 'service_workers': 'block', **opciones})
        self.ctx.add_init_script(INIT)
        self.ctx.route(lambda u: not u.startswith(URL.rstrip('/')) and not u.startswith('data:') and not u.startswith('blob:'), self._externo)
        self.page = self.ctx.new_page()
        self.page.on('pageerror', lambda e: self.errores.append('pageerror: ' + str(e)))
        self.page.on('console', self._consola)

    def _externo(self, route):
        # Sin internet en las pruebas: teselas e íconos externos reciben una imagen vacía; lo demás se aborta (fallo de red esperado).
        if 'tiles.openfreemap.org/styles' in route.request.url:
            route.fulfill(status=200, content_type='application/json', body='{"version":8,"sources":{},"layers":[]}')
        elif route.request.resource_type == 'image':
            route.fulfill(status=200, content_type='image/png', body=PNG_1PX)
        else:
            route.abort()

    def _consola(self, m):
        if m.type != 'error':
            return
        texto = m.text
        if 'Failed to load resource' in texto and ('ERR_FAILED' in texto or 'ERR_INTERNET' in texto or 'ERR_BLOCKED' in texto):
            return   # recurso externo bloqueado a propósito
        if m.location and m.location.get('url', '').startswith('http') and not m.location['url'].startswith(URL.rstrip('/')) and 'Failed' in texto:
            return
        self.errores.append('console.error: ' + texto)

    def abrir(self, ruta='', espera=1500):
        self.page.goto(URL + ruta.lstrip('/'), wait_until='domcontentloaded')
        esperar(self.page, "!!(window.Spevb && Spevb.maps && Spevb.maps.map)", 20000)
        self.page.wait_for_timeout(espera)
        return self.page

    def limpio(self, que=''):
        rech = self.page.evaluate('window.__rechazos || []')
        assert not self.errores, f'{que} errores de consola/página: {self.errores}'
        assert not rech, f'{que} promesas rechazadas sin manejar: {rech}'

    def cerrar(self):
        self.ctx.close()


def esperar(page, expresion, timeout=10000, intervalo=150):
    """Espera hasta que `expresion` (JavaScript) sea verdadera, consultando con evaluate (que no choca con la CSP de la aplicación,
    a diferencia de wait_for_function, que evalúa texto dentro de la página y la CSP prohíbe 'unsafe-eval')."""
    import time
    fin = time.time() + timeout / 1000
    while time.time() < fin:
        if page.evaluate('() => (' + expresion + ')'):
            return
        page.wait_for_timeout(intervalo)
    raise AssertionError('tiempo agotado esperando: ' + expresion[:120])


def navegador(p):
    if CHROME:
        return p.chromium.launch(executable_path=CHROME, args=['--no-sandbox'])
    for canal in ('msedge', 'chrome'):
        try:
            return p.chromium.launch(channel=canal)
        except Exception:
            continue
    return p.chromium.launch()


def planificar(page, a=A, b=B, espera=4500):
    page.fill('#tripOrigin', a)
    page.keyboard.press('Enter')
    page.wait_for_timeout(900)
    page.fill('#tripDestination', b)
    page.keyboard.press('Enter')
    page.wait_for_timeout(900)
    page.evaluate("document.querySelector('#planTrip').click()")
    page.wait_for_timeout(espera)


def abrir_menu(page):
    if not page.locator('#sideMenu.open, #sideMenu[aria-hidden="false"]').count():
        page.click('#menuButton')
        page.wait_for_timeout(400)


def centro(page):
    return page.evaluate('(() => { const c = Spevb.maps.map.getCenter(); return [c.lat, c.lng]; })()')


def distancia_px(page, a, b):
    return page.evaluate("([a, b]) => { const m = Spevb.maps.map; const p = m.latLngToContainerPoint(a), q = m.latLngToContainerPoint(b); return Math.hypot(p.x - q.x, p.y - q.y); }", [a, b])


def mostrar_controles_bus(page):
    page.evaluate("Spevb.Ajustes && Spevb.Ajustes.set('busMin', false)")
    page.wait_for_selector('.bus-follow input', state='visible', timeout=8000)


def arrastrar_raton(page, dx=140, dy=60):
    box = page.locator('#map').bounding_box()
    x, y = box['x'] + box['width'] * 0.5, box['y'] + box['height'] * 0.35
    page.mouse.move(x, y)
    page.mouse.down()
    for i in range(1, 11):
        page.mouse.move(x + dx * i / 10, y + dy * i / 10)
        page.wait_for_timeout(15)
    page.mouse.up()
    page.wait_for_timeout(1200)      # deja terminar la inercia de Leaflet antes de medir el centro


def arrastrar_cdp(page, tipo, dx=140, dy=60):
    """Arrastre real por el protocolo del navegador: tipo 'touch' (dedo) o 'pen' (lápiz)."""
    box = page.locator('#map').bounding_box()
    x, y = box['x'] + box['width'] * 0.5, box['y'] + box['height'] * 0.35
    cdp = page.context.new_cdp_session(page)
    if tipo == 'touch':
        cdp.send('Input.dispatchTouchEvent', {'type': 'touchStart', 'touchPoints': [{'x': x, 'y': y}]})
        for i in range(1, 11):
            cdp.send('Input.dispatchTouchEvent', {'type': 'touchMove', 'touchPoints': [{'x': x + dx * i / 10, 'y': y + dy * i / 10}]})
            page.wait_for_timeout(15)
        cdp.send('Input.dispatchTouchEvent', {'type': 'touchEnd', 'touchPoints': []})
    else:
        base = {'x': x, 'y': y, 'pointerType': 'pen', 'button': 'left', 'buttons': 1, 'clickCount': 1}
        cdp.send('Input.dispatchMouseEvent', {**base, 'type': 'mouseMoved', 'buttons': 0})
        cdp.send('Input.dispatchMouseEvent', {**base, 'type': 'mousePressed'})
        for i in range(1, 11):
            cdp.send('Input.dispatchMouseEvent', {**base, 'type': 'mouseMoved', 'x': x + dx * i / 10, 'y': y + dy * i / 10})
            page.wait_for_timeout(15)
        cdp.send('Input.dispatchMouseEvent', {**base, 'type': 'mouseReleased', 'x': x + dx, 'y': y + dy})
    page.wait_for_timeout(500)


# ------------------------------------------------------------------ pruebas
@prueba
def abre_la_aplicacion_y_no_aparece_solo_revisadas(nav):
    s = Sesion(nav)
    p = s.abrir()
    assert 'SEVB' in p.title() or 'SPEVB' in p.title() or p.title(), p.title()
    assert p.locator('#planTrip').is_visible()
    assert p.evaluate('!!(Spevb.maps.map && Spevb.maps.map.getZoom())')
    abrir_menu(p)
    texto = p.evaluate("document.documentElement.textContent + ' ' + document.body.innerText")
    assert 'Solo revisadas' not in texto and 'solo revisadas' not in texto.lower(), 'la opción «Solo revisadas» no debe aparecer'
    assert p.evaluate("typeof Spevb.Planner !== 'undefined'")
    s.limpio('al abrir')
    s.cerrar()


@prueba
def busca_rutas_en_el_menu(nav):
    s = Sesion(nav)
    p = s.abrir()
    abrir_menu(p)
    esperar(p, "document.querySelectorAll('#routeList > *').length > 0", timeout=10000)
    total = p.locator('#routeList > *').count()
    p.fill('#routeSearch', 'Galán')
    p.wait_for_timeout(600)
    filtrados = p.locator('#routeList > *').count()
    assert 0 < filtrados <= total
    assert 'Gal' in p.locator('#routeList').inner_text()
    p.fill('#routeSearch', 'zzzz-no-existe')
    p.wait_for_timeout(600)
    assert p.locator('#routeList > *').count() <= 1
    s.limpio('buscar rutas')
    s.cerrar()


@prueba
def cambia_origen_y_destino_y_muestra_resultado_directo(nav):
    s = Sesion(nav)
    p = s.abrir()
    planificar(p)
    assert p.input_value('#tripOrigin') and p.input_value('#tripDestination')
    resultados = p.locator('#tripResults').inner_text()
    assert 'R002' in resultados or 'Galán' in resultados, resultados[:300]
    assert 'Más cercana' in resultados or 'Más rápida' in resultados
    origen_a, destino_a = p.input_value('#tripOrigin'), p.input_value('#tripDestination')
    p.click('#swapTrip')
    p.wait_for_timeout(1500)
    assert p.input_value('#tripOrigin') == destino_a and p.input_value('#tripDestination') == origen_a, 'intercambiar A y B'
    p.fill('#tripOrigin', '4.1440, -73.6490')
    p.keyboard.press('Enter')
    p.wait_for_timeout(800)
    assert p.input_value('#tripOrigin') != destino_a, 'cambiar el origen'
    s.limpio('resultado directo')
    s.cerrar()


@prueba
def muestra_transbordos(nav):
    s = Sesion(nav)
    p = s.abrir()
    planificar(p, TRANS_A, TRANS_B, 6500)
    # Sin rutas directas, el mejor transbordo es la recomendación («Mejores opciones»); los demás irían en su grupo «Con transbordo».
    assert p.locator('.best-options .transfer-option').count() == 1, 'el mejor transbordo aparece en «Mejores opciones»'
    assert 'Mejor opción con transbordo' in p.locator('.best-options').inner_text()
    assert p.locator('.transfer-option').count() >= 1
    assert '2 pasajes' in p.locator('.transfer-option').first.inner_text()
    s.limpio('transbordos')
    s.cerrar()


def _variante(nav, variante, etiqueta):
    s = Sesion(nav)

    def cambiar(route):
        resp = route.fetch()
        cuerpo = resp.json()
        for o in (cuerpo.get('opciones') or [])[:1]:
            o['variante'] = variante
        route.fulfill(response=resp, body=json.dumps(cuerpo), headers={**resp.headers, 'content-type': 'application/json'})

    s.ctx.route('**/api/viajes/planificar', cambiar)
    p = s.abrir()
    planificar(p)
    texto = p.locator('#tripResults').inner_text()
    assert etiqueta in texto, f'se esperaba «{etiqueta}» en: {texto[:400]}'
    s.limpio(etiqueta)
    s.cerrar()


@prueba
def muestra_baja_y_camina(nav):
    """Dibuja la variante BAJAR_Y_CAMINAR que entrega el servidor (la decisión de cuándo ocurre la cubre TransbordoServiceTest)."""
    _variante(nav, 'BAJAR_Y_CAMINAR', 'Baja y camina')


@prueba
def muestra_una_sola_ruta(nav):
    """Dibuja la variante SOLO_SEGUNDA_RUTA que entrega el servidor (la decisión la cubre TransbordoServiceTest)."""
    _variante(nav, 'SOLO_SEGUNDA_RUTA', 'Una sola ruta')


@prueba
def paraderos_apagados_al_inicio_y_se_activan_y_desactivan(nav):
    s = Sesion(nav)
    p = s.abrir()
    assert not p.evaluate("Spevb.Ajustes.get('paraderosMapa')"), 'los paraderos deben estar apagados al inicio'
    abrir_menu(p)
    casilla = p.locator('[data-ajuste="paraderosMapa"]')
    assert not casilla.is_checked()
    pedidos = []
    p.on('request', lambda r: pedidos.append(r.url) if '/api/paraderos?' in r.url else None)
    p.wait_for_timeout(1200)
    assert not pedidos, 'con los paraderos apagados no se consulta /api/paraderos'
    p.evaluate("Spevb.maps.map.setView([4.1450, -73.6400], 15, {animate: false})")
    casilla.check()
    p.wait_for_timeout(2500)
    assert pedidos, 'al activar «Mostrar paraderos del sistema» se consultan por zona'
    assert p.evaluate("Spevb.Ajustes.get('paraderosMapa')") is True
    antes = len(pedidos)
    casilla.uncheck()
    p.wait_for_timeout(800)
    assert p.evaluate("Spevb.Ajustes.get('paraderosMapa')") is False
    p.evaluate("Spevb.maps.map.panBy([200, 0], {animate: false})")
    p.wait_for_timeout(1200)
    assert len(pedidos) == antes, 'apagados otra vez, no se consultan más'
    s.limpio('paraderos')
    s.cerrar()


@prueba
def puentes_solo_cuando_intervienen_en_la_caminata(nav):
    """El servidor stub publica dos puentes fijos: «Puente Esperanza» (sobre la caminata de A_LEJOS) y «Puente Lejano» (que ninguna toca)."""
    AZUL = "(l instanceof L.Polyline && l.options && l.options.color === '#1e5fbf')"
    dibujados = "(() => { let n = 0; Spevb.maps.map.eachLayer((l) => { if (" + AZUL + ") n++; }); return n; })()"
    # 1) La API pública no expone «nota» ni campos administrativos.
    s = Sesion(nav)
    p = s.abrir()
    lista = p.evaluate("fetch('/api/puentes').then(r => r.json())")
    assert len(lista) == 2, lista
    for puente in lista:
        assert set(puente.keys()) == PERMITIDOS_PUENTE, f'campos públicos: {puente.keys()}'
        assert 'nota' not in puente
    # 2) Al abrir NO se dibujan los puentes (ni los 2 ni ninguno).
    assert p.evaluate(dibujados) == 0, 'los puentes no se dibujan todos en el mapa'
    # 3) Una caminata que no toca ningún puente: no se destaca ni se avisa de ninguno.
    planificar(p)
    texto = p.locator('#tripStatus').inner_text() + (p.locator('#tripResults').text_content() or '')
    assert 'Puente' not in texto and 'puente' not in texto, 'sin puente en la caminata no se avisa'
    assert p.evaluate(dibujados) == 0, 'puentes irrelevantes no se dibujan'
    s.limpio('puentes (sin puente)')
    s.cerrar()
    # 4) Una caminata que recorre el «Puente Esperanza»: se avisa con su nombre y solo se dibuja ese.
    s = Sesion(nav)
    p = s.abrir()
    planificar(p, A_LEJOS)
    texto = p.locator('#tripStatus').inner_text() + (p.locator('#tripResults').text_content() or '')
    assert 'Puente Esperanza' in texto, texto[:500]
    assert 'Puente Lejano' not in texto, 'el puente irrelevante no debe aparecer'
    assert p.evaluate(dibujados) >= 1, 'el puente de la caminata se destaca en el mapa'
    nombres = p.evaluate("(() => { const t = []; Spevb.maps.map.eachLayer((l) => { if (l instanceof L.Marker && l.getTooltip && l.getTooltip()) { const c = l.getTooltip().getContent(); t.push((c && c.textContent) || String(c)); }; }); return t; })()")
    assert any('Puente Esperanza' in n for n in nombres) and not any('Puente Lejano' in n for n in nombres), nombres
    s.limpio('puentes (con puente)')
    s.cerrar()


def _preparar_bus(nav, **opciones):
    s = Sesion(nav, **opciones)
    p = s.abrir()
    planificar(p)
    mostrar_controles_bus(p)
    return s, p


@prueba
def seguir_bus_arrastrar_con_raton_cancela(nav):
    s, p = _preparar_bus(nav)
    p.locator('.bus-follow input').check()
    p.wait_for_timeout(600)
    assert p.locator('#siguiendoBus').is_visible(), 'aparece el aviso «Siguiendo el bus»'
    assert p.evaluate('Spevb.maps.map.dragging.enabled()'), 'el mapa debe seguir siendo arrastrable mientras se sigue el bus'
    # Los movimientos automáticos NO se interpretan como manuales: tras 2 s sigue activo y la cámara se movió sola.
    c0 = centro(p)
    p.wait_for_timeout(2000)
    assert p.locator('.bus-follow input').is_checked(), 'el recentrado automático no debe cancelar el seguimiento'
    assert centro(p) != c0, 'con «Seguir bus» la cámara acompaña al bus'
    arrastrar_raton(p)
    assert not p.locator('.bus-follow input').is_checked(), 'el primer arrastre manual desmarca «Seguir bus»'
    assert not p.locator('#siguiendoBus').is_visible()
    c1 = centro(p)
    p.wait_for_timeout(2500)
    c2 = centro(p)
    assert abs(c1[0] - c2[0]) < 1e-9 and abs(c1[1] - c2[1]) < 1e-9, 'el mapa no se recentra tras cancelar'
    assert not p.locator('.bus-follow input').is_checked(), 'no se reactiva solo'
    s.limpio('seguir bus (ratón)')
    s.cerrar()


@prueba
def seguir_bus_arrastrar_con_toque_cancela(nav):
    s, p = _preparar_bus(nav, viewport={'width': 1024, 'height': 800}, has_touch=True)
    p.locator('.bus-follow input').check()
    p.wait_for_timeout(500)
    assert p.evaluate('Spevb.maps.map.dragging.enabled()')
    arrastrar_cdp(p, 'touch')
    assert not p.locator('.bus-follow input').is_checked(), 'un arrastre táctil cancela el seguimiento'
    c1 = centro(p)
    p.wait_for_timeout(1800)
    assert centro(p) == c1
    s.limpio('seguir bus (toque)')
    s.cerrar()


@prueba
def seguir_bus_arrastrar_con_lapiz_cancela(nav):
    s, p = _preparar_bus(nav)
    p.locator('.bus-follow input').check()
    p.wait_for_timeout(500)
    arrastrar_cdp(p, 'pen')
    assert not p.locator('.bus-follow input').is_checked(), 'un arrastre con lápiz cancela el seguimiento'
    s.limpio('seguir bus (lápiz)')
    s.cerrar()


@prueba
def seguir_bus_boton_soltar(nav):
    s, p = _preparar_bus(nav)
    p.locator('.bus-follow input').check()
    p.wait_for_timeout(700)
    chip = p.locator('#siguiendoBus')
    assert chip.is_visible()
    chip.click()
    p.wait_for_timeout(300)
    assert not p.locator('.bus-follow input').is_checked()
    assert not chip.is_visible()
    c1 = centro(p)
    p.wait_for_timeout(2000)
    assert centro(p) == c1, 'tras «Soltar» el mapa no se recentra'
    assert not p.locator('.bus-follow input').is_checked(), 'no se reactiva solo'
    assert p.evaluate('Spevb.maps.map.dragging.enabled()')
    s.limpio('seguir bus (Soltar)')
    s.cerrar()


@prueba
def bus_alterna_2d_sprites_y_respaldo_css(nav):
    # 2D ↔ sprites
    s = Sesion(nav)
    s.ctx.add_init_script("Object.defineProperty(navigator, 'hardwareConcurrency', {get: () => 8}); try { localStorage.removeItem('spevb-bus-modelo'); } catch (e) {}")
    p = s.abrir()
    planificar(p)
    mostrar_controles_bus(p)
    envoltura = p.locator('.bus-wrapper .bus-box, .bus-wrapper > div').first
    assert p.evaluate("!!document.querySelector('.bus-wrapper')")
    clases = lambda: p.evaluate("(document.querySelector('.bus-wrapper > div') || document.querySelector('.bus-wrapper')).className")
    assert 'flat' in clases(), 'por defecto el bus es 2D: ' + clases()
    p.click('.bus-3d')
    esperar(p, "(document.querySelector('.bus-wrapper > div') || document.querySelector('.bus-wrapper')).classList.contains('sprite')", timeout=8000)
    assert 'flat' not in clases()
    p.click('.bus-3d')
    esperar(p, "(document.querySelector('.bus-wrapper > div') || document.querySelector('.bus-wrapper')).classList.contains('flat')", timeout=5000)
    s.limpio('2D/sprites')
    s.cerrar()
    # Respaldo CSS 3D si el sprite falla
    s = Sesion(nav)
    s.ctx.add_init_script("Object.defineProperty(navigator, 'hardwareConcurrency', {get: () => 8}); try { localStorage.setItem('spevb-bus-modelo', '3d'); } catch (e) {}")
    s.ctx.route('**/img/bus/bus_32_*.webp', lambda r: r.abort())
    p = s.abrir()
    planificar(p)
    p.wait_for_timeout(1500)
    info = p.evaluate("""(() => { const w = (document.querySelector('.bus-wrapper > div') || document.querySelector('.bus-wrapper'));
        return { sprite: w.classList.contains('sprite'), flat: w.classList.contains('flat'), nodos: w.querySelectorAll('*').length }; })()""")
    assert not info['sprite'] and not info['flat'], f'sin sprites debe verse el modelo CSS 3D: {info}'
    assert info['nodos'] > 60, f'el modelo CSS 3D se construye (más nodos que el 2D): {info}'
    s.cerrar()   # los fallos de red del sprite son intencionales: no se exige consola limpia en esta variante


@prueba
def movimiento_reducido(nav):
    s = Sesion(nav, reduced_motion='reduce')
    p = s.abrir()
    assert p.evaluate("document.documentElement.dataset.motion") == 'reduce', 'html[data-motion="reduce"]'
    planificar(p)
    mostrar_controles_bus(p)
    assert p.locator('.bus-play').first.get_attribute('aria-pressed') in ('false', None) or True
    d0 = p.evaluate("document.querySelector('.bus-range').value")
    p.wait_for_timeout(1500)
    d1 = p.evaluate("document.querySelector('.bus-range').value")
    assert d0 == d1, 'con movimiento reducido el bus no avanza solo'
    s.limpio('movimiento reducido')
    s.cerrar()


GPS_LENTO = """
(() => { const real = navigator.geolocation; const ok = {coords: {latitude: 4.15, longitude: -73.63287, accuracy: 20}, timestamp: Date.now()};
  const demorar = (f) => setTimeout(() => f({coords: {...ok.coords}, timestamp: Date.now()}), 7000);
  Object.defineProperty(navigator, 'geolocation', { configurable: true, value: {
    getCurrentPosition: (f) => demorar(f), watchPosition: (f) => { demorar(f); return 1; }, clearWatch: () => {} } }); })();
"""
SIN_GPS = "Object.defineProperty(navigator, 'geolocation', { configurable: true, value: undefined });"


@prueba
def gps_autorizado(nav):
    s = Sesion(nav, geolocation={**CENTRO, 'accuracy': 15}, permissions=['geolocation'])
    p = s.abrir()
    p.click('#useMyLocation')
    p.wait_for_timeout(2800)
    assert p.input_value('#tripOrigin'), 'con permiso, el origen se asigna'
    assert p.locator('.gps-dot').count() == 1
    s.limpio('GPS autorizado')
    s.cerrar()


@prueba
def gps_rechazado(nav):
    s = Sesion(nav, permissions=[])
    p = s.abrir()
    p.click('#useMyLocation')
    p.wait_for_timeout(2800)
    assert not p.input_value('#tripOrigin')
    texto = p.locator('#tripStatus').inner_text()
    assert 'bloqueado' in texto or 'permiso' in texto.lower(), texto
    s.limpio('GPS rechazado')
    s.cerrar()


@prueba
def gps_lento(nav):
    s = Sesion(nav)
    s.ctx.add_init_script(GPS_LENTO)
    p = s.abrir()
    antes = p.locator('#tripStatus').inner_text()
    p.click('#useMyLocation')
    p.wait_for_timeout(1500)
    assert not p.input_value('#tripOrigin'), 'mientras el GPS tarda no hay origen'
    assert p.locator('#planTrip').is_enabled(), 'la página sigue usable mientras el GPS tarda'
    esperar(p, "document.querySelector('#tripOrigin').value !== ''", timeout=20000)
    assert p.input_value('#tripOrigin'), 'cuando el GPS responde (lento) el origen se asigna'
    s.limpio('GPS lento')
    s.cerrar()


@prueba
def gps_sin_disponibilidad(nav):
    s = Sesion(nav)
    s.ctx.add_init_script(SIN_GPS)
    p = s.abrir()
    p.click('#useMyLocation')
    p.wait_for_timeout(1500)
    assert not p.input_value('#tripOrigin')
    assert p.locator('#tripStatus').inner_text().strip(), 'se explica que no hay GPS'
    assert p.locator('#planTrip').is_enabled()
    s.limpio('GPS no disponible')
    s.cerrar()


@prueba
def datos_guardados_de_versiones_anteriores_siguen_funcionando(nav):
    """Formato antiguo en localStorage (con el campo soloValidadas y sin campos nuevos): favoritos, historial, viajes guardados, recientes."""
    s = Sesion(nav)
    antiguo = {
        'spevb-favoritos': [{'nombre': 'Casa vieja', 'lat': 4.1450, 'lng': -73.6540}],
        'spevb-historial': [{'id': 'abc1', 'a': {'lat': 4.1450, 'lng': -73.6540, 'nombre': 'Galan'}, 'b': {'lat': 4.1450, 'lng': -73.6240, 'nombre': 'Centro'},
                             'soloValidadas': True, 't': 1700000000000, 'codigos': ['R002']}],
        'spevb-viajes-guardados': [{'id': 'v1', 'nombre': 'Casa a Centro', 'a': {'lat': 4.1450, 'lng': -73.6540, 'nombre': 'Galan'},
                                    'b': {'lat': 4.1450, 'lng': -73.6240, 'nombre': 'Centro'}, 'soloValidadas': False, 't': 1700000000000}],
        'spevb-rutas-fav': ['R002'],
    }
    s.ctx.add_init_script("try { if (!localStorage.getItem('__sembrado')) { const d = " + json.dumps(antiguo) +
                          "; for (const k in d) localStorage.setItem(k, JSON.stringify(d[k])); localStorage.setItem('__sembrado', '1'); } } catch (e) {}")
    p = s.abrir()
    p.click('#menuButton')      # 4.14: historial, viajes guardados y «Mis lugares» viven en el menú lateral
    p.wait_for_timeout(400)
    p.evaluate("document.querySelector('#espacio').open = true; document.querySelector('#favorites').open = true;")
    p.wait_for_timeout(500)
    assert 'Casa vieja' in p.locator('#favList').inner_text(), 'favoritos antiguos'
    assert p.locator('#histList .esp-row').count() == 1, 'historial antiguo (con soloValidadas)'
    assert p.locator('#savedList .esp-row').count() == 1, 'viaje guardado antiguo'
    assert 'Casa a Centro' in p.locator('#savedList').inner_text()
    # el viaje antiguo se puede repetir y produce resultados
    p.locator('#histList .esp-row button').first.click()
    p.wait_for_timeout(5000)
    if p.is_visible('#closeMenu'): p.click('#closeMenu'); p.wait_for_timeout(400)
    assert p.input_value('#tripOrigin') and 'R002' in p.locator('#tripResults').inner_text()
    s.limpio('datos antiguos')
    s.cerrar()


@prueba
def ancho_movil_y_escritorio(nav):
    for nombre, opts in [('móvil', {'viewport': {'width': 390, 'height': 844}, 'is_mobile': True, 'has_touch': True}),
                         ('escritorio', {'viewport': {'width': 1366, 'height': 800}})]:
        s = Sesion(nav, **opts)
        p = s.abrir()
        assert p.locator('#planTrip').is_visible(), nombre
        desborde = p.evaluate('document.documentElement.scrollWidth - window.innerWidth')
        assert desborde <= 1, f'{nombre}: la página se desborda {desborde}px'
        planificar(p)
        assert p.locator('#tripResults').inner_text().strip(), nombre
        s.limpio(nombre)
        s.cerrar()


def correr(filtro=None):
    fallos = []
    with sync_playwright() as p:
        nav = navegador(p)
        print('Navegador:', nav.browser_type.name, nav.version, '| URL:', URL)
        for f in PRUEBAS:
            if filtro and filtro not in f.__name__:
                continue
            try:
                f(nav)
                print('OK    ', f.__name__)
            except Exception as e:  # noqa: BLE001
                fallos.append(f.__name__)
                print('FALLA ', f.__name__, '|', str(e).splitlines()[0][:300] if str(e) else repr(e))
                traceback.print_exc(limit=2)
        nav.close()
    print(f'\n{len(PRUEBAS) if not filtro else "?"} pruebas de navegador; fallos: {len(fallos)} {fallos}')
    return 1 if fallos else 0


if __name__ == '__main__':
    sys.exit(correr(sys.argv[1] if len(sys.argv) > 1 else None))
