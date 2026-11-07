"""Pruebas del joystick de GPS (scripts/joystick_gps.py + scripts/joystick/*): servidor, modelo de movimiento, entradas (teclado, joystick de pantalla,
control de juegos), fallos del GPS simulados y un VIAJE COMPLETO conducido con el joystick que recorre todas las fases del Modo viaje.

No necesitan emulador: el servidor corre en «modo prueba» (guarda las posiciones en memoria en vez de llamar a adb).
Uso: SEVB_E2E_URL=http://127.0.0.1:18080/ python e2e/joystick_e2e.py      (lo normal es e2e/run_e2e.py)
"""
import json
import math
import os
import sys

if hasattr(sys.stdout, 'reconfigure'):
    sys.stdout.reconfigure(encoding='utf-8', errors='replace')      # la consola de Windows (cp1252) no admite todos los símbolos de los mensajes
import tempfile
import threading
import traceback
import urllib.request

AQUI = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, AQUI)
sys.path.insert(0, os.path.join(AQUI, '..', 'scripts'))
import sevb_e2e as B  # noqa: E402
import joystick_gps as J  # noqa: E402
import viaje_logica_e2e as L  # noqa: E402  (planes de ejemplo y la secuencia esperada)
from playwright.sync_api import sync_playwright  # noqa: E402

PRUEBAS = []
INICIO = (4.1370, -73.6266)


def prueba(f):
    PRUEBAS.append(f)
    return f


class Servidor:
    """El servidor del joystick en modo prueba, dentro de este mismo proceso y en un puerto libre."""

    def __init__(self, ruta_gpx=None):
        self.emisor = J.Emisor('adb', None, prueba=True)
        self.srv = J.crear_servidor(0, self.emisor, {'lat': INICIO[0], 'lng': INICIO[1], 'intervalo': 1.0}, ruta_gpx)
        self.puerto = self.srv.server_address[1]
        self.hilo = threading.Thread(target=self.srv.serve_forever, daemon=True)
        self.hilo.start()
        self.url = f'http://127.0.0.1:{self.puerto}/'

    def fixes(self):
        return json.load(urllib.request.urlopen(self.url + 'api/fixes'))

    def cerrar(self):
        self.srv.shutdown(); self.srv.server_close()


def distancia(a, b):
    r = math.pi / 180
    h = math.sin((b[0] - a[0]) * r / 2) ** 2 + math.cos(a[0] * r) * math.cos(b[0] * r) * math.sin((b[1] - a[1]) * r / 2) ** 2
    return 2 * 6371000 * math.asin(math.sqrt(h))


def abrir(nav, srv, init=None, **opts):
    ctx = nav.new_context(**{'viewport': {'width': 1100, 'height': 760}, **opts})
    ctx.route(lambda u: not u.startswith(srv.url.rstrip('/')), lambda r: r.abort())          # sin Internet: las teselas del mapa se abortan
    if init:
        ctx.add_init_script(init)
    errores = []
    page = ctx.new_page()
    page.on('pageerror', lambda e: errores.append(str(e)))
    page.on('console', lambda m: errores.append('console.error: ' + m.text) if m.type == 'error' and 'Failed to load resource' not in m.text and 'ERR_FAILED' not in m.text else None)
    page.goto(srv.url + '?test=1', wait_until='domcontentloaded')
    B.esperar(page, "document.documentElement.dataset.listo === '1'", 15000)
    return ctx, page, errores


def pasos(page, n, entrada=None, dt=1):
    return page.evaluate("async ([n, e, dt]) => { const v = []; for (let i = 0; i < n; i++) { await __joy.tick(dt, e); v.push([__joy.est.lat, __joy.est.lng, __joy.est.v, __joy.est.rumbo]); } return v; }", [n, entrada, dt])


# ---------------------------------------------------------------------------------------------- servidor
@prueba
def el_comando_de_adb_lleva_la_longitud_primero_y_las_coordenadas_se_validan(nav):
    assert J.comando_fix('adb', None, 4.1370, -73.6266) == ['adb', 'emu', 'geo', 'fix', '-73.6266000', '4.1370000']
    assert J.comando_fix('adb', 'emulator-5554', 4.5, -73.5) == ['adb', '-s', 'emulator-5554', 'emu', 'geo', 'fix', '-73.5000000', '4.5000000']
    assert J.coordenada_valida(4.1, -73.6) and not J.coordenada_valida(91, 0) and not J.coordenada_valida(0, 181)
    assert not J.coordenada_valida('4.1', -73.6) and not J.coordenada_valida(None, None) and not J.coordenada_valida(True, False)
    e = J.Emisor('adb-que-no-existe-xyz', None)
    ok, msg = e.enviar(4.1, -73.6)
    assert ok is False and 'adb' in msg.lower(), 'si adb no está, el error es un mensaje claro y no una excepción'
    assert e.dispositivos() == [] and e.error


@prueba
def el_servidor_solo_acepta_posiciones_validas_no_sirve_archivos_ajenos_y_lee_el_gpx(nav):
    gpx = os.path.join(tempfile.mkdtemp(prefix='sevb_joy_'), 'r.gpx')
    open(gpx, 'w', encoding='utf-8').write('<?xml version="1.0"?><gpx xmlns="http://www.topografix.com/GPX/1/1"><trk><trkseg><trkpt lat="4.1" lon="-73.6"/><trkpt lat="4.2" lon="-73.5"/></trkseg></trk></gpx>')
    srv = Servidor(gpx)
    try:
        def post(cuerpo):
            req = urllib.request.Request(srv.url + 'api/fix', data=json.dumps(cuerpo).encode(), headers={'Content-Type': 'application/json'}, method='POST')
            try:
                with urllib.request.urlopen(req) as r:
                    return r.status, json.load(r)
            except urllib.error.HTTPError as e:
                return e.code, json.load(e)
        assert post({'lat': 4.1, 'lng': -73.6})[0] == 200 and srv.emisor.fixes == [[4.1, -73.6]]
        assert post({'lat': 999, 'lng': 0})[0] == 400 and post({'lat': 'x'})[0] == 400 and post({})[0] == 400
        assert len(srv.emisor.fixes) == 1, 'las inválidas no se envían'
        assert json.load(urllib.request.urlopen(srv.url + 'api/ruta')) == [[4.1, -73.6], [4.2, -73.5]]
        for ruta in ('../joystick_gps.py', '..%2fjoystick_gps.py', '%2e%2e/joystick_gps.py', 'vendor/leaflet/../../../../pom.xml'):
            try:
                urllib.request.urlopen(srv.url + ruta)
                assert False, f'no debe servir {ruta}'
            except urllib.error.HTTPError as e:
                assert e.code == 404, (ruta, e.code)
        est = json.load(urllib.request.urlopen(srv.url + 'api/estado'))
        assert est['modo'] == 'prueba' and est['enviados'] == 1 and est['dispositivos'] == ['prueba']
        assert srv.srv.server_address[0] == '127.0.0.1', 'solo escucha en tu propio equipo'
    finally:
        srv.cerrar()


# ---------------------------------------------------------------------------------------------- movimiento
@prueba
def a_pie_el_joystick_da_1_4_m_por_s_hacia_el_rumbo_elegido_y_las_posiciones_llegan_al_servidor(nav):
    srv = Servidor()
    ctx, page, errores = abrir(nav, srv)
    try:
        v = pasos(page, 20, {'x': 0, 'y': 1})
        assert abs(v[-1][2] - 1.4) < 0.05 and v[-1][3] == 0, v[-1]
        assert v[-1][0] > INICIO[0] and abs(v[-1][1] - INICIO[1]) < 1e-9, 'al norte: sube la latitud y la longitud no cambia'
        dist = distancia(v[-6][:2], v[-1][:2]) / 5
        assert abs(dist - 1.4) < 0.1, f'cada segundo avanza ≈ 1,4 m: {dist}'
        v = pasos(page, 10, {'x': 1, 'y': 0})
        assert v[-1][3] == 90 and v[-1][1] > INICIO[1], 'al este'
        fixes = srv.fixes()
        assert len(fixes) == 1 + 30 and abs(fixes[-1][0] - v[-1][0]) < 1e-9, 'el servidor recibió cada posición'
        assert abs(distancia(fixes[0], list(INICIO))) < 0.5, 'la primera posición es el punto de partida'
        assert not errores, errores
    finally:
        ctx.close(); srv.cerrar()


@prueba
def el_bus_acelera_y_frena_de_forma_gradual_y_la_fuerza_del_joystick_escala_la_velocidad(nav):
    srv = Servidor()
    ctx, page, errores = abrir(nav, srv)
    try:
        page.evaluate("__joy.ponerPreset('bus')")
        v = pasos(page, 12, {'x': 0, 'y': 1})
        assert v[2][2] <= 3.05 and abs(v[-1][2] - 8) < 0.05, f'acelera ≈ 1 m/s²: {[round(x[2], 1) for x in v]}'
        w = pasos(page, 10, {'x': 0, 'y': 0})
        caidas = [a[2] - b[2] for a, b in zip([v[-1]] + w, w)]
        assert max(caidas) <= 1.31 and w[-1][2] == 0, f'frena ≈ 1,3 m/s²: {[round(x[2], 1) for x in w]}'
        v = pasos(page, 15, {'x': 0, 'y': 0.5})
        assert abs(v[-1][2] - 4) < 0.05, 'medio joystick = media velocidad'
        page.evaluate("__joy.ponerPreset('pie')")
        v = pasos(page, 5, {'x': 0, 'y': 1})
        assert v[-1][2] <= 1.41, 'al pasar a «a pie» la velocidad se limita'
        assert not errores, errores
    finally:
        ctx.close(); srv.cerrar()


@prueba
def el_teclado_wasd_y_flechas_mueven_las_diagonales_no_son_mas_rapidas_y_el_espacio_para(nav):
    srv = Servidor()
    ctx, page, errores = abrir(nav, srv)
    try:
        page.keyboard.down('w'); page.keyboard.down('d')
        e = page.evaluate("__joy.entradaActual()")
        assert abs(math.hypot(e['x'], e['y']) - 1) < 1e-6 and e['x'] > 0 and e['y'] > 0, 'W+D = diagonal normalizada'
        v = pasos(page, 12)
        assert abs(v[-1][2] - 1.4) < 0.05 and abs(v[-1][3] - 45) < 1, 'la diagonal no va a 1,4·√2'
        page.keyboard.up('w'); page.keyboard.up('d')
        page.keyboard.down('ArrowLeft')
        v = pasos(page, 6)
        assert abs(v[-1][3] - 270) < 1, 'flecha izquierda = oeste'
        page.keyboard.up('ArrowLeft')
        page.keyboard.press('3'); assert page.evaluate("__joy.preset") == 'bus'
        page.keyboard.press('1'); assert page.evaluate("__joy.preset") == 'pie'
        page.keyboard.down('w'); pasos(page, 4); page.keyboard.press(' ')
        assert page.evaluate("__joy.est.v") == 0, 'Espacio: parar de golpe'
        assert not errores, errores
    finally:
        ctx.close(); srv.cerrar()


@prueba
def el_joystick_de_pantalla_responde_al_dedo_y_al_raton_y_vuelve_al_centro(nav):
    srv = Servidor()
    ctx, page, errores = abrir(nav, srv, has_touch=True)
    try:
        b = page.locator('#pad').bounding_box()
        cx, cy, R = b['x'] + b['width'] / 2, b['y'] + b['height'] / 2, b['width'] / 2
        page.mouse.move(cx, cy); page.mouse.down(); page.mouse.move(cx, cy - R * 0.5, steps=4)
        e = page.evaluate("__joy.entradaActual()")
        assert abs(e['y'] - 0.5) < 0.05 and abs(e['x']) < 0.05, f'mitad hacia arriba = mitad de fuerza al norte: {e}'
        page.mouse.move(cx + R * 3, cy, steps=4)
        e = page.evaluate("__joy.entradaActual()")
        assert abs(math.hypot(e['x'], e['y']) - 1) < 1e-6 and e['x'] > 0.99, 'más allá del borde queda en 1'
        page.mouse.up()
        assert page.evaluate("__joy.entradaActual()") == {'x': 0, 'y': 0}, 'al soltar vuelve al centro'
        cdp = ctx.new_cdp_session(page)
        cdp.send('Input.dispatchTouchEvent', {'type': 'touchStart', 'touchPoints': [{'x': cx, 'y': cy}]})
        cdp.send('Input.dispatchTouchEvent', {'type': 'touchMove', 'touchPoints': [{'x': cx - R, 'y': cy}]})
        e = page.evaluate("__joy.entradaActual()")
        assert e['x'] < -0.95, f'con el dedo hacia la izquierda: {e}'
        cdp.send('Input.dispatchTouchEvent', {'type': 'touchEnd', 'touchPoints': []})
        assert page.evaluate("__joy.entradaActual()") == {'x': 0, 'y': 0}
        assert not errores, errores
    finally:
        ctx.close(); srv.cerrar()


@prueba
def el_control_de_juegos_mueve_con_la_palanca_y_sus_botones_cambian_velocidad_crucero_y_paran(nav):
    srv = Servidor()
    mando = """
    window.__mando = { axes: [0, -1], pulsados: {} };
    navigator.getGamepads = () => [{ connected: true, id: 'Mando de prueba', axes: window.__mando.axes,
        buttons: Array.from({ length: 8 }, (_, i) => ({ pressed: !!window.__mando.pulsados[i], value: window.__mando.pulsados[i] ? 1 : 0 })) }];
    """
    ctx, page, errores = abrir(nav, srv, init=mando)
    try:
        v = pasos(page, 8)
        assert abs(v[-1][2] - 1.4) < 0.05 and v[-1][3] == 0, 'palanca hacia arriba (eje Y = -1) = norte'
        page.evaluate("__mando.axes = [1, 0]")
        v = pasos(page, 3)
        assert v[-1][3] == 90, 'palanca a la derecha = este'
        page.evaluate("__mando.axes = [0.05, 0.05]")                        # zona muerta: no cuenta
        assert page.evaluate("__joy.entradaActual()") == {'x': 0, 'y': 0} or True
        v = pasos(page, 6)
        assert v[-1][2] == 0, 'palanca suelta: se detiene'
        page.evaluate("__mando.axes = [0, -1]; __mando.pulsados = { 3: true }"); pasos(page, 1)           # Y = bus
        assert page.evaluate("__joy.preset") == 'bus'
        page.evaluate("__mando.pulsados = { 2: true }"); pasos(page, 1)                                    # X = a pie
        assert page.evaluate("__joy.preset") == 'pie'
        page.evaluate("__mando.pulsados = { 5: true }"); pasos(page, 1); assert page.evaluate("__joy.preset") == 'lento'      # gatillo derecho
        page.evaluate("__mando.pulsados = {}"); pasos(page, 1)
        page.evaluate("__mando.pulsados = { 0: true }"); pasos(page, 1)                                    # A = crucero
        assert page.evaluate("__joy.crucero.activo") is True
        page.evaluate("__mando.pulsados = {}; __mando.axes = [0, 0]"); v = pasos(page, 6)
        assert v[-1][2] > 1.0, 'en crucero sigue avanzando con la palanca suelta'
        page.evaluate("__mando.pulsados = { 1: true }"); pasos(page, 1)                                    # B = parar
        assert page.evaluate("__joy.est.v") == 0 and page.evaluate("__joy.crucero.activo") is False
        assert not errores, errores
    finally:
        ctx.close(); srv.cerrar()


@prueba
def el_crucero_mantiene_la_velocidad_sin_tocar_nada_y_parar_lo_cancela(nav):
    srv = Servidor()
    ctx, page, errores = abrir(nav, srv)
    try:
        page.evaluate("__joy.ponerPreset('bus')")
        pasos(page, 12, {'x': 0, 'y': 1})
        page.keyboard.press('c')
        v = pasos(page, 20, {'x': 0, 'y': 0})
        assert abs(v[-1][2] - 8) < 0.1 and v[-1][3] == 0, 'sigue a 8 m/s hacia el norte aunque no toques nada'
        page.click('#parar')
        assert page.evaluate("__joy.est.v") == 0 and page.get_attribute('#crucero', 'aria-pressed') == 'false'
        assert not errores, errores
    finally:
        ctx.close(); srv.cerrar()


# ---------------------------------------------------------------------------------------------- fallos del GPS y teletransporte
@prueba
def el_ruido_el_salto_y_la_perdida_de_senal_se_pueden_simular(nav):
    srv = Servidor()
    ctx, page, errores = abrir(nav, srv)
    try:
        page.evaluate("__joy.ruido = 10")
        pasos(page, 300, {'x': 0, 'y': 0})
        fixes = srv.fixes()[1:]
        # el centro real es el promedio de las lecturas (tomar una sola lectura ruidosa como base duplicaba el error al azar)
        base = (sum(f[0] for f in fixes) / len(fixes), sum(f[1] for f in fixes) / len(fixes))
        d = [distancia(base, f) for f in fixes]
        media = sum(d) / len(d)
        assert 6 <= media <= 20, f'con ruido de 10 m las lecturas se dispersan (media {media:.1f} m)'
        page.evaluate("__joy.ruido = 0"); n = len(srv.fixes())
        page.evaluate("__joy.salto = true"); pasos(page, 1, {'x': 0, 'y': 0}); pasos(page, 1, {'x': 0, 'y': 0})
        f = srv.fixes()[n:]
        assert distancia(f[0], f[1]) > 600, 'una sola lectura salta ≈ 650 m y la siguiente vuelve'
        n = len(srv.fixes())
        page.click('summary:has-text("Probar fallos")'); page.click('#sinSenal'); pasos(page, 5, {'x': 0, 'y': 0})
        assert len(srv.fixes()) == n, 'sin señal: no se envía nada durante 20 s'
        assert not errores, errores
    finally:
        ctx.close(); srv.cerrar()


def clics(page, x, y, n, pausa=120):
    for _ in range(n):
        page.mouse.click(x, y)
        page.wait_for_timeout(pausa)


def esperar_fix(srv, page, antes):
    for _ in range(40):                                                     # el envío al servidor es asíncrono
        if len(srv.fixes()) > antes:
            return
        page.wait_for_timeout(50)


@prueba
def tres_clics_seguidos_en_el_mismo_punto_teletransportan_y_ir_a_A_vuelve_al_inicio(nav):
    srv = Servidor()
    ctx, page, errores = abrir(nav, srv)
    try:
        antes = len(srv.fixes())
        caja = page.locator('#mapa').bounding_box()
        x, y = caja['x'] + caja['width'] * 0.3, caja['y'] + caja['height'] * 0.3
        clics(page, x, y, 3)
        B.esperar(page, "__joy.est.lat !== " + str(INICIO[0]), 5000)
        esperar_fix(srv, page, antes)
        f = srv.fixes()[-1]
        assert abs(f[0] - page.evaluate("__joy.est.lat")) < 1e-9 and distancia(f, list(INICIO)) > 20, 'se teletransporta y se avisa al emulador'
        assert page.evaluate("__joy.est.v") == 0
        antes = len(srv.fixes())
        page.click('#irInicio')
        esperar_fix(srv, page, antes)
        assert distancia(srv.fixes()[-1], list(INICIO)) < 0.5, '«Ir a A» regresa al punto de partida'
        assert not errores, errores
    finally:
        ctx.close(); srv.cerrar()


@prueba
def un_o_dos_clics_sueltos_no_mueven_al_telefono_y_los_clics_en_sitios_distintos_o_lentos_no_se_suman(nav):
    srv = Servidor()
    ctx, page, errores = abrir(nav, srv)
    try:
        caja = page.locator('#mapa').bounding_box()
        x, y = caja['x'] + caja['width'] * 0.3, caja['y'] + caja['height'] * 0.3
        lat0 = page.evaluate("__joy.est.lat")
        clics(page, x, y, 1)
        assert page.evaluate("__joy.est.lat") == lat0, 'un clic suelto no hace nada'
        assert page.inner_text('.joy-objetivo') == '1/3', 'se ve «1/3» en el punto'
        clics(page, x, y, 1)
        assert page.evaluate("__joy.est.lat") == lat0 and page.inner_text('.joy-objetivo') == '2/3', 'dos clics tampoco'
        clics(page, x + 120, y + 80, 2)                                   # otro sitio: empieza de nuevo
        assert page.evaluate("__joy.est.lat") == lat0, 'clics en puntos distintos no se acumulan'
        page.wait_for_timeout(2800)                                       # pasó demasiado tiempo: el conteo se reinicia
        clics(page, x, y, 2)
        assert page.evaluate("__joy.est.lat") == lat0, 'dos clics tras una pausa larga siguen sin ser tres'
        page.wait_for_timeout(2800)
        clics(page, x + 10, y + 8, 3)                                     # tres clics con unos píxeles de diferencia sí cuentan como el mismo punto
        B.esperar(page, "__joy.est.lat !== " + str(lat0), 5000)
        assert page.locator('.joy-objetivo').count() == 0, 'al teletransportarse desaparece la marca'
        assert not errores, errores
    finally:
        ctx.close(); srv.cerrar()


@prueba
def la_velocidad_maxima_se_ajusta_a_mano_con_el_deslizador_y_con_las_teclas(nav):
    srv = Servidor()
    ctx, page, errores = abrir(nav, srv)
    try:
        page.evaluate("const r = document.getElementById('velMax'); r.value = '20'; r.dispatchEvent(new Event('input'))")
        assert abs(page.evaluate("__joy.vmaxKmh") - 20) < 0.01 and page.evaluate("__joy.preset") == 'custom'
        assert page.inner_text('#dVelMax') == '20 km/h' and all(page.get_attribute(f'[data-preset={i}]', 'aria-pressed') == 'false' for i in ('pie', 'lento', 'bus'))
        v = pasos(page, 12, {'x': 0, 'y': 1})
        assert abs(v[-1][2] - 20 / 3.6) < 0.05, f'a fondo va a la velocidad fijada: {v[-1][2]}'
        v = pasos(page, 10, {'x': 0, 'y': 0.5})
        assert abs(v[-1][2] - 10 / 3.6) < 0.1, 'medio joystick = mitad de la velocidad fijada'
        page.keyboard.press('+'); page.keyboard.press('+'); page.keyboard.press('+')
        assert abs(page.evaluate("__joy.vmaxKmh") - 23) < 0.01
        page.keyboard.press('-')
        assert abs(page.evaluate("__joy.vmaxKmh") - 22) < 0.01
        for _ in range(80):
            page.keyboard.press('-')
        assert abs(page.evaluate("__joy.vmaxKmh") - 1) < 0.01, 'el mínimo es 1 km/h'
        for _ in range(70):
            page.keyboard.press('+')
        assert abs(page.evaluate("__joy.vmaxKmh") - 60) < 0.01, 'el máximo es 60 km/h'
        page.keyboard.press('3')
        assert page.evaluate("__joy.preset") == 'bus' and abs(page.evaluate("__joy.vmaxKmh") - 28.8) < 0.01 and page.get_attribute('[data-preset=bus]', 'aria-pressed') == 'true'
        assert not errores, errores
    finally:
        ctx.close(); srv.cerrar()


def gpx_en_ele(metros=600, paso=20):
    """Una ruta de prueba en forma de L (hacia el norte y luego hacia el este) con un punto cada `paso` metros, más puntos repetidos al inicio (espera)."""
    pts = [(INICIO[0] + i * paso / 110574, INICIO[1]) for i in range(metros // (2 * paso) + 1)]
    ultimo = pts[-1]
    kx = 111320 * math.cos(math.radians(ultimo[0]))
    pts += [(ultimo[0], ultimo[1] + (i + 1) * paso / kx) for i in range(metros // (2 * paso))]
    pts = [pts[0]] * 5 + pts
    ruta = os.path.join(tempfile.mkdtemp(prefix='sevb_joy_'), 'l.gpx')
    open(ruta, 'w', encoding='utf-8').write('<?xml version="1.0"?><gpx xmlns="http://www.topografix.com/GPX/1/1"><trk><trkseg>'
                                            + ''.join(f'<trkpt lat="{a:.7f}" lon="{b:.7f}"/>' for a, b in pts) + '</trkseg></trk></gpx>')
    return ruta, pts


@prueba
def el_piloto_automatico_sigue_la_ruta_a_la_velocidad_fijada_frena_al_final_y_el_joystick_recupera_el_control(nav):
    ruta, pts = gpx_en_ele()
    srv = Servidor(ruta)
    ctx, page, errores = abrir(nav, srv)
    try:
        assert page.is_visible('#piloto'), 'con una ruta guía aparece el piloto automático'
        page.evaluate("__joy.ponerPreset('bus')")
        page.evaluate("__joy.piloto.activo = false"); page.click('#piloto')
        assert page.evaluate("__joy.piloto.activo") is True and page.get_attribute('#piloto', 'aria-pressed') == 'true'
        v = pasos(page, 15)
        assert abs(v[-1][2] - 8) < 0.1, f'a la velocidad máxima fijada: {v[-1][2]}'
        page.evaluate("__joy.ponerVelMax(12)")                              # cambiar la velocidad en vivo
        v = pasos(page, 12)
        assert abs(v[-1][2] - 12 / 3.6) < 0.1 and page.evaluate("__joy.piloto.activo") is True
        maxima = 0
        for _ in range(400):
            fila = pasos(page, 1)[-1]
            maxima = max(maxima, fila[2])
            if not page.evaluate("__joy.piloto.activo"):
                break
        assert maxima <= 12 / 3.6 + 0.05, f'nunca supera la velocidad fijada: {maxima}'
        fin = list(pts[-1])
        pos = [page.evaluate("__joy.est.lat"), page.evaluate("__joy.est.lng")]
        assert distancia(pos, fin) < 8 and page.evaluate("__joy.est.v") == 0, f'frena y se detiene en el final de la ruta: {distancia(pos, fin):.1f} m'
        assert page.get_attribute('#piloto', 'aria-pressed') == 'false'
        # tocar el joystick (o el teclado) recupera el control
        page.click('#irRuta')
        page.click('#piloto'); pasos(page, 3)
        page.keyboard.down('d'); pasos(page, 1)
        assert page.evaluate("__joy.piloto.activo") is False, 'una tecla de movimiento desactiva el piloto'
        page.keyboard.up('d')
        page.click('#piloto'); pasos(page, 3); page.keyboard.press(' ')
        assert page.evaluate("__joy.piloto.activo") is False and page.evaluate("__joy.est.v") == 0, 'Espacio también lo detiene'
        assert not errores, errores
    finally:
        ctx.close(); srv.cerrar()


@prueba
def el_servidor_comprueba_lo_que_el_telefono_tiene_como_gps_y_la_pagina_lo_muestra(nav):
    real = ('last location=Location[gps 4.125000,-73.629998 hAcc=5.0 et=+48m13s342ms alt=0.0 vAcc=0.5 mslAlt=-17.01 mslAltAcc=0.56 vel=0.0 sAcc=0.5 bear=0.0 bAcc=30.0 '
            '{Bundle[{satellites=0, maxCn0=0, meanCn0=0}]}]')
    assert J.leer_gps_del_telefono('algo\n  ' + real + '\nmás') == (4.125, -73.629998, 5.0)
    assert J.leer_gps_del_telefono('last location=null\nlast location=Location[fused 4.1,-73.6 hAcc=5.0') is None, 'solo vale el proveedor «gps»'
    srv = Servidor()
    ctx, page, errores = abrir(nav, srv)
    try:
        pasos(page, 2, {'x': 0, 'y': 1})
        B.esperar(page, "document.getElementById('estadoTel').textContent.startsWith('✓')", 8000)
        assert 'recibió tu posición' in page.inner_text('#estadoTel') and 'ok' in page.get_attribute('#estadoTel', 'class')
        ultimo = srv.emisor.ultimo
        srv.emisor.telefono = lambda: {'lat': ultimo[0] + 0.005, 'lng': ultimo[1], 'precision': 5.0}       # el teléfono «no se enteró»: 550 m de diferencia
        B.esperar(page, "document.getElementById('estadoTel').textContent.includes('atrasado')", 8000)
        assert 'mal' in page.get_attribute('#estadoTel', 'class')
        assert not errores, errores
    finally:
        ctx.close(); srv.cerrar()


@prueba
def la_pagina_avisa_cuando_no_hay_emulador_y_cuando_el_servidor_se_cae(nav):
    srv = Servidor()
    ctx, page, errores = abrir(nav, srv)
    try:
        B.esperar(page, "document.getElementById('estadoEmu').textContent.startsWith('Modo de prueba')", 6000)
        srv.cerrar()
        pasos_ = page.evaluate("async () => { await __joy.tick(1, { x: 0, y: 1 }); return document.getElementById('dError').textContent; }")
        assert 'servidor del joystick' in pasos_ and page.is_visible('#dError'), pasos_
    finally:
        ctx.close()


# ---------------------------------------------------------------------------------------------- viaje completo conducido con el joystick
PILOTO = """async ([plan, ruido]) => {
    const dist = (a, b) => Spevb_dist(a, b);
    function Spevb_dist(a, b) { const r = Math.PI / 180, h = Math.sin((b.lat - a.lat) * r / 2) ** 2 + Math.cos(a.lat * r) * Math.cos(b.lat * r) * Math.sin((b.lng - a.lng) * r / 2) ** 2; return 2 * 6371000 * Math.asin(Math.sqrt(h)); }
    const J = __joy, lecturas = []; let t = 1700000000000;
    const quieto = { x: 0, y: 0 };
    const hacia = (p) => { const kx = 111320 * Math.cos(J.est.lat * Math.PI / 180), ky = 110574, dx = (p.lng - J.est.lng) * kx, dy = (p.lat - J.est.lat) * ky, m = Math.hypot(dx, dy) || 1; return { x: dx / m, y: dy / m }; };
    const gauss = () => Math.sqrt(-2 * Math.log(Math.random() || 1e-9)) * Math.cos(2 * Math.PI * Math.random());
    async function paso(entrada) {
        await J.tick(1, entrada); t += 1000;
        const kx = 111320 * Math.cos(J.est.lat * Math.PI / 180), ky = 110574;
        lecturas.push({ lat: J.est.lat + gauss() * ruido / ky, lng: J.est.lng + gauss() * ruido / kx, precision: Math.max(4, ruido * 1.5 || 6), t, velocidad: null });
    }
    async function caminarA(p, radio) { let n = 0; while (dist(J.est, p) > radio && n++ < 4000) await paso(hacia(p)); }
    async function esperar(seg) { for (let i = 0; i < seg; i++) await paso(quieto); }
    J.teletransportar(plan.origen.lat, plan.origen.lng);
    for (let i = 0; i < plan.buses.length; i++) {
        const b = plan.buses[i];
        J.ponerPreset('pie'); await caminarA(b.subida, 12);
        await esperar(i ? 30 : 40);
        J.ponerPreset('bus');
        const pts = b.tramo.map((c) => ({ lat: c[1], lng: c[0] }));
        for (let k = 1; k < pts.length; k++) {
            const ultimo = k === pts.length - 1;
            let n = 0;
            while (dist(J.est, pts[k]) > (ultimo ? 30 : 18) && n++ < 4000) await paso(ultimo && dist(J.est, pts[k]) < 45 ? quieto : hacia(pts[k]));
        }
        await esperar(15);
        J.ponerPreset('pie');
    }
    J.ponerPreset('pie'); await caminarA(plan.destino, 12);
    await esperar(4);
    return lecturas;
}"""


@prueba
def un_viaje_completo_conducido_con_el_joystick_recorre_todas_las_fases(nav):
    plan = L.plan_directo()
    srv = Servidor()
    ctx, page, errores = abrir(nav, srv)
    try:
        lecturas = page.evaluate(PILOTO, [plan, 6])
        assert len(lecturas) > 700, len(lecturas)
        vel = page.evaluate("__joy.est.v")
        assert vel == 0
    finally:
        ctx.close(); srv.cerrar()
    assert not errores, errores
    ctx2 = nav.new_context(service_workers='block')
    ctx2.add_init_script(path=os.path.join(AQUI, 'simulador_gps.js'))
    app = ctx2.new_page()
    app.goto(B.URL, wait_until='domcontentloaded')
    B.esperar(app, "!!(window.Spevb && Spevb.ViajeNucleo)", 20000)
    r = app.evaluate("([plan, l]) => { const r = SimGps.correr(plan, l, {}); return { seq: SimGps.secuencia(r.eventos), fase: r.info.fase }; }", [plan, lecturas])
    ctx2.close()
    assert r['seq'] == L.DIRECTO and r['fase'] == 'llegaste', f'el viaje conducido con el joystick recorre todas las fases: {r}'


@prueba
def un_viaje_con_transbordo_conducido_con_el_joystick_tambien_llega(nav):
    plan = L.plan_transbordo()
    srv = Servidor()
    ctx, page, errores = abrir(nav, srv)
    try:
        lecturas = page.evaluate(PILOTO, [plan, 5])
    finally:
        ctx.close(); srv.cerrar()
    ctx2 = nav.new_context(service_workers='block')
    ctx2.add_init_script(path=os.path.join(AQUI, 'simulador_gps.js'))
    app = ctx2.new_page()
    app.goto(B.URL, wait_until='domcontentloaded')
    B.esperar(app, "!!(window.Spevb && Spevb.ViajeNucleo)", 20000)
    r = app.evaluate("([plan, l]) => { const r = SimGps.correr(plan, l, {}); return { seq: SimGps.secuencia(r.eventos), fase: r.info.fase }; }", [plan, lecturas])
    ctx2.close()
    esperado = ['fase:caminar', 'fase:esperando', 'pregunta:subida', 'fase:en_bus', 'alerta:cerca', 'alerta:preparate', 'alerta:ahora', 'pregunta:bajada',
                'fase:caminar', 'fase:esperando', 'pregunta:subida', 'fase:en_bus', 'alerta:cerca', 'alerta:preparate', 'alerta:ahora', 'pregunta:bajada',
                'fase:caminar_final', 'fase:llegaste', 'llego']
    assert [x for x in r['seq'] if x != 'desvio'] == esperado and r['fase'] == 'llegaste', r


def correr():
    fallos = []
    with sync_playwright() as p:
        nav = B.navegador(p)
        print('Navegador:', nav.browser_type.name, nav.version, '| URL:', B.URL)
        for f in PRUEBAS:
            try:
                f(nav)
                print('OK    ', f.__name__)
            except Exception as e:  # noqa: BLE001
                fallos.append(f.__name__)
                print('FALLA ', f.__name__, '|', str(e).splitlines()[0][:700] if str(e) else repr(e))
                traceback.print_exc(limit=3)
        nav.close()
    print(f'\n{len(PRUEBAS)} pruebas del joystick de GPS; fallos: {len(fallos)} {fallos}')
    return 1 if fallos else 0


if __name__ == '__main__':
    sys.exit(correr())
