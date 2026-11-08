"""Pruebas del service worker en un navegador REAL: navegación normal, con query string, sin conexión,
actualización/limpieza de cachés y varias URL compartidas sin copias duplicadas.

Uso: SEVB_E2E_URL=http://127.0.0.1:18080/ python e2e/sw_e2e.py
"""
import json
import os
import sys
import traceback
import urllib.request

from playwright.sync_api import sync_playwright

URL = os.environ.get('SEVB_E2E_URL', 'http://127.0.0.1:18080/').rstrip('/') + '/'
CHROME = os.environ.get('CHROME_PATH') or None
VERSION = json.load(urllib.request.urlopen(URL + 'api/estado'))['version']
CACHE = f'spevb-v{VERSION}-mapa2'
COMPARTIDAS = [
    '?origen=4.1450,-73.6540&destino=4.1450,-73.6240',
    '?origen=Casa%20de%20Ana&destino=Trabajo&nombre=Ana',
    '?a=4.14321,-73.60201&b=4.15481,-73.58341&an=Mi%20casa&bn=Mi%20trabajo',
    '?origen=1&destino=2&x=3',
    '?ruta=1&lat=4.1&lng=-73.6',
]
PRUEBAS = []


def prueba(f):
    PRUEBAS.append(f)
    return f


def navegador(p):
    if CHROME:
        return p.chromium.launch(executable_path=CHROME, args=['--no-sandbox'])
    for canal in ('msedge', 'chrome'):
        try:
            return p.chromium.launch(channel=canal)
        except Exception:
            continue
    return p.chromium.launch()


def contexto(nav):
    ctx = nav.new_context(service_workers='allow')
    page = ctx.new_page()
    errores = []
    page.on('pageerror', lambda e: errores.append(str(e)))
    return ctx, page, errores


def instalar(page):
    page.goto(URL, wait_until='domcontentloaded')
    esperar(page, "navigator.serviceWorker.ready.then(() => true)", timeout=20000)
    # espera a que el worker termine de guardar el shell y controle la página
    esperar(page, f"caches.open('{CACHE}').then(c => c.keys()).then(k => k.length > 30)", timeout=30000)
    page.reload(wait_until='domcontentloaded')
    esperar(page, "navigator.serviceWorker.controller !== null", timeout=15000)


def esperar(page, expresion, timeout=15000, intervalo=200):
    """Consulta con evaluate hasta que `expresion` sea verdadera (wait_for_function evalúa texto y la CSP de la aplicación lo prohíbe)."""
    import time
    fin = time.time() + timeout / 1000
    while time.time() < fin:
        if page.evaluate('() => (' + expresion + ')'):
            return
        page.wait_for_timeout(intervalo)
    raise AssertionError('tiempo agotado esperando: ' + expresion[:120])


def claves(page, nombre=None):
    return page.evaluate("""async (n) => { const out = {}; for (const k of await caches.keys()) { if (n && k !== n) continue;
        out[k] = (await (await caches.open(k)).keys()).map(r => r.url); } return out; }""", nombre)


@prueba
def navegacion_normal(nav):
    ctx, page, errores = contexto(nav)
    instalar(page)
    todas = claves(page)
    assert CACHE in todas, f'falta la caché {CACHE}: {list(todas)}'
    shell = todas[CACHE]
    assert URL in shell, 'el shell se guarda con la clave canónica «/»'
    assert not any('?' in u for u in shell), 'ninguna clave lleva parámetros'
    assert page.evaluate("document.querySelector('#map') !== null")
    assert not errores, errores
    ctx.close()


@prueba
def navegacion_con_query_no_deja_datos_en_cache_y_conserva_la_url(nav):
    ctx, page, errores = contexto(nav)
    instalar(page)
    q = COMPARTIDAS[1]
    page.goto(URL + q, wait_until='domcontentloaded')
    page.wait_for_timeout(2500)
    assert page.evaluate('location.search') == q, 'la URL visible conserva sus parámetros'
    todas = claves(page)
    texto = json.dumps(todas)
    for secreto in ('Casa', 'Ana', 'Trabajo', 'origen', 'destino', '4.1450'):
        assert secreto not in texto, f'«{secreto}» no debe aparecer en las claves de Cache Storage'
    assert sum(1 for u in todas[CACHE] if u == URL) == 1
    assert page.evaluate("document.querySelector('#map') !== null")
    assert not errores, errores
    ctx.close()


@prueba
def varias_urls_compartidas_no_duplican_el_html(nav):
    ctx, page, _ = contexto(nav)
    instalar(page)
    antes = claves(page, CACHE)[CACHE]
    for q in COMPARTIDAS:
        page.goto(URL + q, wait_until='domcontentloaded')
        page.wait_for_timeout(1200)
    despues = claves(page, CACHE)[CACHE]
    raices = [u for u in despues if u.rstrip('/') == URL.rstrip('/')]
    assert len(raices) == 1, f'una sola copia del HTML principal, hay {len(raices)}'
    assert len(despues) <= len(antes) + 2, f'la caché no crece con cada URL: {len(antes)} → {len(despues)}'
    assert not any('?' in u for u in despues)
    ctx.close()


@prueba
def sin_conexion_entrega_el_shell_desde_la_clave_canonica(nav):
    ctx, page, errores = contexto(nav)
    instalar(page)
    ctx.set_offline(True)
    for ruta in ('', '?origen=nunca-visto&destino=otro', '?a=4.1,-73.6&b=4.2,-73.5'):
        page.goto(URL + ruta, wait_until='domcontentloaded')
        esperar(page, "window.Spevb && Spevb.maps && Spevb.maps.map", timeout=15000)
        assert page.evaluate("document.querySelector('#planTrip') !== null"), f'sin conexión se ve la aplicación en «/{ruta}»'
    # /api/* no se intercepta: sin conexión, falla como siempre (la página tiene su propio manejo de «recientes»)
    fallo = page.evaluate("fetch('/api/estado').then(() => 'respondio').catch(() => 'fallo')")
    assert fallo == 'fallo', '/api/estado no debe servirse desde la caché del worker'
    # los recursos estáticos siguen disponibles
    assert page.evaluate("fetch('/js/app.js').then(r => r.ok)")
    assert page.evaluate("fetch('/vendor/leaflet/leaflet.js').then(r => r.ok)")
    ctx.set_offline(False)
    ctx.close()


@prueba
def conexion_normal_pide_la_url_original(nav):
    ctx, page, _ = contexto(nav)
    instalar(page)
    pedidos = []
    page.on('request', lambda r: pedidos.append(r.url))
    q = COMPARTIDAS[3]
    page.goto(URL + q, wait_until='domcontentloaded')
    assert any(u == URL + q for u in pedidos), 'con conexión la solicitud original (con sus parámetros) llega al servidor'
    ctx.close()


@prueba
def no_se_guardan_respuestas_fallidas(nav):
    ctx, page, _ = contexto(nav)
    instalar(page)
    page.evaluate("fetch('/js/no-existe-xyz.js').then(r => r.status)")
    page.evaluate("fetch('/pagina-que-no-existe').then(r => r.status)")
    page.wait_for_timeout(800)
    texto = json.dumps(claves(page))
    assert 'no-existe-xyz' not in texto and 'pagina-que-no-existe' not in texto, 'un 404 jamás se guarda'
    ctx.close()


@prueba
def actualizacion_elimina_caches_antiguas_y_claves_con_parametros(nav):
    ctx, page, _ = contexto(nav)
    instalar(page)
    # Simula lo que dejaba una versión anterior: una caché vieja y una clave con parámetros en la caché vigente.
    page.evaluate(f"""(async () => {{
        await (await caches.open('spevb-v0.0.1')).put(new Request('/viejo'), new Response('x'));
        const c = await caches.open('{CACHE}');
        await c.put(new Request('/?origen=4.1,-73.6&destino=4.2,-73.5'), new Response('<html></html>', {{headers: {{'Content-Type': 'text/html'}}}}));
    }})()""")
    assert 'spevb-v0.0.1' in claves(page)
    assert any('origen=' in u for u in claves(page, CACHE)[CACHE])
    # Se reinstala el worker: se vuelve a ejecutar «activate».
    page.evaluate("navigator.serviceWorker.getRegistrations().then(rs => Promise.all(rs.map(r => r.unregister())))")
    page.reload(wait_until='domcontentloaded')
    # El arnés compite con el propio navegador (unregister + reload): si en 8 s la caché vieja sigue ahí se repite UNA vez el ciclo antes de dar el fallo.
    try:
        esperar(page, "caches.keys().then(k => !k.includes('spevb-v0.0.1'))", timeout=8000)
    except AssertionError:
        page.evaluate("navigator.serviceWorker.getRegistrations().then(rs => Promise.all(rs.map(r => r.unregister())))")
        page.reload(wait_until='domcontentloaded')
    # No se espera a `serviceWorker.ready`: justo tras unregister() puede resolverse con el registro anterior (carrera del propio arnés, no del worker);
    # lo que se afirma es el efecto de «activate»: la caché vieja desaparece.
    esperar(page, "caches.keys().then(k => !k.includes('spevb-v0.0.1'))", timeout=20000)
    todas = claves(page)
    assert 'spevb-v0.0.1' not in todas, 'las cachés antiguas se eliminan al activar'
    esperar(page, f"caches.open('{CACHE}').then(c => c.keys()).then(k => k.every(r => !r.url.includes('?')))", timeout=15000)
    ctx.close()


def forzar(estado):
    urllib.request.urlopen(URL + f'__fallo/{estado}').read()


def pedir(page, ruta):
    return page.evaluate("""async (r) => { const x = await fetch(r); const t = await x.text();
        return { status: x.status, cache: x.headers.get('X-Spevb-Cache'), cuerpo: t.slice(0, 200) }; }""", ruta)


@prueba
def un_error_5xx_del_servidor_usa_la_copia_guardada_de_la_ruta(nav):
    ctx, page, _ = contexto(nav)
    try:
        instalar(page)
        ok = pedir(page, '/api/rutas/1')
        assert ok['status'] == 200 and ok['cache'] is None, 'con la red bien se conserva el comportamiento actual'
        esperar(page, "caches.keys().then(async ks => { for (const k of ks) { if ((await (await caches.open(k)).keys()).some(r => r.url.endsWith('/api/rutas/1'))) return true; } return false; })", timeout=10000)
        for estado in (500, 502, 503, 504):
            forzar(estado)
            r = pedir(page, '/api/rutas/1')
            assert r['status'] == 200 and r['cache'] == 'guardado' and '"id"' in r['cuerpo'], f'{estado}: copia guardada con su encabezado: {r}'
        forzar(0)
    finally:
        forzar(0)
        ctx.close()


@prueba
def un_404_nunca_se_convierte_en_datos_viejos_y_sin_copia_se_conserva_el_error(nav):
    ctx, page, _ = contexto(nav)
    try:
        instalar(page)
        pedir(page, '/api/rutas/1')
        page.wait_for_timeout(600)
        forzar(404)
        r = pedir(page, '/api/rutas/1')
        assert r['status'] == 404 and r['cache'] is None, f'404 es legítimo: no se sustituye por una copia: {r}'
        for estado in (400, 401, 403):
            forzar(estado)
            r = pedir(page, '/api/rutas/1')
            assert r['status'] == estado and r['cache'] is None, f'{estado} no se sustituye: {r}'
        forzar(503)
        sin_copia = pedir(page, '/api/rutas/2')                   # la ruta 2 nunca se guardó
        assert sin_copia['status'] == 503 and sin_copia['cache'] is None and 'Fallo simulado' in sin_copia['cuerpo'], f'sin copia: error original: {sin_copia}'
        forzar(0)
        # las respuestas fallidas no se guardaron: con la red bien y luego un 503 la copia sigue siendo la buena
        forzar(503)
        r = pedir(page, '/api/rutas/1')
        assert r['status'] == 200 and r['cache'] == 'guardado' and 'Fallo simulado' not in r['cuerpo']
    finally:
        forzar(0)
        ctx.close()


@prueba
def calcular_un_viaje_no_deja_claves_con_parametros_en_ninguna_cache(nav):
    ctx, page, _ = contexto(nav)
    try:
        instalar(page)
        page.fill('#tripOrigin', '4.1470, -73.6530'); page.keyboard.press('Enter'); page.wait_for_timeout(700)
        page.fill('#tripDestination', '4.1450, -73.6240'); page.keyboard.press('Enter'); page.wait_for_timeout(700)
        page.evaluate("document.querySelector('#planTrip').click()")
        esperar(page, "document.querySelectorAll('#tripResults .trip-option').length > 0", timeout=15000)
        page.wait_for_timeout(2500)
        todas = claves(page)
        urls = [u for lista in todas.values() for u in lista]
        assert urls and not [u for u in urls if '?' in u], f'claves canónicas, sin parámetros: {[u for u in urls if "?" in u]}'
        assert not [u for u in urls if '4.147' in u or '4.145' in u or 'planificar' in u], 'ni coordenadas ni el cálculo del viaje se guardan'
        assert 'ruta=' not in page.evaluate('location.search'), 'el dibujo interno del viaje no escribe ?ruta='
    finally:
        ctx.close()


def correr():
    fallos = []
    with sync_playwright() as p:
        nav = navegador(p)
        print('Navegador:', nav.browser_type.name, nav.version, '| URL:', URL, '| caché:', CACHE)
        for f in PRUEBAS:
            try:
                f(nav)
                print('OK    ', f.__name__)
            except Exception as e:  # noqa: BLE001
                fallos.append(f.__name__)
                print('FALLA ', f.__name__, '|', str(e).splitlines()[0][:300] if str(e) else repr(e))
                traceback.print_exc(limit=2)
        nav.close()
    print(f'\n{len(PRUEBAS)} pruebas del service worker; fallos: {len(fallos)} {fallos}')
    return 1 if fallos else 0


if __name__ == '__main__':
    sys.exit(correr())
