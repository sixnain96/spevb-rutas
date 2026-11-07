"""Pruebas de navegador de los controles para BORRAR el origen (A), el destino (B) o ambos (4.10.1), con respuestas asíncronas viejas.

Uso: SEVB_E2E_URL=http://127.0.0.1:18080/ python e2e/puntos_e2e.py [filtro]      (lo normal es e2e/run_e2e.py)
Para simular lo «pendiente» (búsqueda, sugerencias, geocodificación inversa) las rutas del stub se RETIENEN con Playwright y se liberan
a mano después de borrar: así se comprueba que una respuesta vieja no vuelve a colocar el punto.
"""
import json
import os
import sys
import traceback

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import sevb_e2e as B  # noqa: E402
from playwright.sync_api import sync_playwright  # noqa: E402

PRUEBAS = []
A_TXT, B_TXT = '4.1450, -73.6540', '4.1450, -73.6240'


def prueba(f):
    PRUEBAS.append(f)
    return f


GPS_MANUAL = """
window.__gps = [];
Object.defineProperty(navigator, 'geolocation', { configurable: true, value: {
  getCurrentPosition: (f) => { window.__gps.push(f); },
  watchPosition: (f) => { window.__gps.push(f); return window.__gps.length; },
  clearWatch: () => {} } });
window.__gpsResolver = () => window.__gps.forEach((f) => f({ coords: { latitude: 4.15, longitude: -73.63287, accuracy: 15, speed: null, heading: null }, timestamp: Date.now() }));
"""


def abrir(nav, **opciones):
    s = B.Sesion(nav, **opciones)
    p = s.abrir()
    return s, p


def poner(p, modo, texto):
    campo = '#tripOrigin' if modo == 'origin' else '#tripDestination'
    p.fill(campo, texto)
    p.keyboard.press('Enter')
    p.wait_for_timeout(700)


def pines(p):
    return p.evaluate("({a: document.querySelectorAll('.trip-pin.a').length, b: document.querySelectorAll('.trip-pin.b').length})")


def puntos(p):
    return p.evaluate("(() => { const q = Spevb.Planner.puntos(); return {a: !!q.origen, b: !!q.destino}; })()")


def estado(p):
    return p.locator('#tripStatus').inner_text()


def con_viaje_y_cercanas(p):
    poner(p, 'origin', A_TXT)
    poner(p, 'destination', B_TXT)
    p.evaluate("document.querySelector('#planTrip').click()")
    p.wait_for_timeout(2500)
    p.click('#menuButton'); p.wait_for_timeout(500)         # 4.14: «Rutas cerca del origen» vive en el menú lateral
    p.click('#nearOriginRoutes')
    B.esperar(p, "document.querySelectorAll('#nearbyResults .nearby-card').length >= 1", 8000)
    p.click('#closeMenu'); p.wait_for_timeout(500)
    assert p.locator('#tripResults').inner_text().strip(), 'hay resultados del viaje'


class Retenidas:
    """Retiene las respuestas de una ruta del stub hasta que la prueba las libera (simula una petición lenta)."""

    def __init__(self, ctx, patron):
        self.pend = []
        ctx.route(patron, lambda route: self.pend.append(route))

    def liberar(self, cuerpo, tipo='application/json'):
        for r in self.pend:
            try:
                r.fulfill(status=200, content_type=tipo, body=json.dumps(cuerpo))
            except Exception:  # noqa: BLE001  (la petición fue abortada por la página: es lo esperado)
                pass
        self.pend = []


@prueba
def borra_solo_A_conserva_B_y_limpia_viaje_y_cercanas(nav):
    s, p = abrir(nav)
    con_viaje_y_cercanas(p)
    assert pines(p) == {'a': 1, 'b': 1} and p.locator('#clearOrigin').is_enabled() and p.locator('#clearDestination').is_enabled()
    b_texto = p.input_value('#tripDestination')
    p.click('#clearOrigin')
    p.wait_for_timeout(300)
    assert p.input_value('#tripOrigin') == '' and puntos(p) == {'a': False, 'b': True}
    assert p.input_value('#tripDestination') == b_texto, 'B conserva su texto'
    assert pines(p) == {'a': 0, 'b': 1}, 'desaparece solo el marcador A'
    assert p.locator('#tripResults').inner_text().strip() == '', 'se eliminan los resultados del viaje'
    assert p.locator('#nearbyResults .nearby-card').count() == 0, 'se limpian las rutas cerca del origen'
    assert p.locator('#nearOriginRoutes').is_disabled(), 'el botón de rutas cercanas se desactiva'
    assert estado(p) == 'Origen eliminado. Selecciona nuevamente el punto A.'
    assert p.evaluate('document.activeElement.id') == 'tripOrigin', 'el foco vuelve al campo del origen'
    assert p.locator('#clearOrigin').is_disabled() and p.locator('#clearDestination').is_enabled()
    assert p.locator('#tripTools').is_hidden(), 'se ocultan compartir, guardar y reportar'
    # seleccionar de nuevo A
    poner(p, 'origin', A_TXT)
    assert pines(p) == {'a': 1, 'b': 1} and p.locator('#clearOrigin').is_enabled()
    s.limpio('borrar A')
    s.cerrar()


@prueba
def borra_solo_B_conserva_A_y_las_rutas_cercanas(nav):
    s, p = abrir(nav)
    con_viaje_y_cercanas(p)
    a_texto = p.input_value('#tripOrigin')
    p.click('#clearDestination')
    p.wait_for_timeout(300)
    assert p.input_value('#tripDestination') == '' and puntos(p) == {'a': True, 'b': False}
    assert p.input_value('#tripOrigin') == a_texto, 'A conserva su texto'
    assert pines(p) == {'a': 1, 'b': 0}
    assert p.locator('#tripResults').inner_text().strip() == ''
    assert p.locator('#nearbyResults .nearby-card').count() >= 1, 'las rutas cerca del origen se conservan: A todavía existe'
    assert p.locator('#nearOriginRoutes').is_enabled()
    assert estado(p) == 'Destino eliminado. Selecciona nuevamente el punto B.'
    assert p.evaluate('document.activeElement.id') == 'tripDestination'
    s.limpio('borrar B')
    s.cerrar()


@prueba
def limpiar_A_y_B_cierra_sugerencias_resultados_y_no_toca_favoritos_ni_historial(nav):
    s = B.Sesion(nav)
    datos = {'spevb-favoritos': [{'nombre': 'Casa', 'lat': 4.145, 'lng': -73.654}],
             'spevb-viajes-guardados': [{'id': 'v1', 'nombre': 'Casa a Centro', 'a': {'lat': 4.145, 'lng': -73.654, 'nombre': 'Casa'}, 'b': {'lat': 4.145, 'lng': -73.624, 'nombre': 'Centro'}, 't': 1700000000000}]}
    s.ctx.add_init_script("try { if (!localStorage.getItem('__s')) { const d = " + json.dumps(datos) + "; for (const k in d) localStorage.setItem(k, JSON.stringify(d[k])); localStorage.setItem('__s', '1'); } } catch (e) {}")
    p = s.abrir()
    con_viaje_y_cercanas(p)
    antes = p.evaluate("[localStorage.getItem('spevb-favoritos'), localStorage.getItem('spevb-viajes-guardados')]")
    n_hist = p.evaluate("JSON.parse(localStorage.getItem('spevb-historial') || '[]').length")
    gps_antes = p.evaluate("document.querySelectorAll('.gps-dot').length")
    # una lista de sugerencias abierta en el destino
    p.fill('#tripDestination', 'Carrera 7'); p.wait_for_timeout(900)
    assert p.locator('#destinationResults.suggestion-open').count() == 1, 'hay sugerencias abiertas'
    centro = p.evaluate("(() => { const c = Spevb.maps.map.getCenter(), z = Spevb.maps.map.getZoom(); return [c.lat, c.lng, z]; })()")
    # «Limpiar A y B» ahora está junto a «Buscar rutas»: con la lista abierta en pantalla ancha la lista lo cubre (tocar ahí elige una sugerencia, como en cualquier
    # autocompletado); aquí se activa el botón por código para comprobar lo que importa: que limpiar cierra sugerencias y no toca favoritos ni historial.
    p.evaluate("document.getElementById('clearTripPoints').click()"); p.wait_for_timeout(300)
    assert p.input_value('#tripOrigin') == '' and p.input_value('#tripDestination') == ''
    assert puntos(p) == {'a': False, 'b': False} and pines(p) == {'a': 0, 'b': 0}
    assert p.locator('.suggestion-open').count() == 0 and p.locator('#originResults > *, #destinationResults > *').count() == 0, 'sugerencias cerradas'
    assert p.locator('#tripResults').inner_text().strip() == '' and p.locator('#nearbyResults .nearby-card').count() == 0
    assert p.locator('#tripTools').is_hidden()
    assert estado(p) == 'Origen y destino eliminados. Marca nuevamente A y B.'
    assert p.evaluate('document.activeElement.id') == 'tripOrigin'
    assert p.locator('#clearTripPoints').is_disabled() and p.locator('#clearOrigin').is_disabled() and p.locator('#clearDestination').is_disabled()
    assert p.evaluate("[localStorage.getItem('spevb-favoritos'), localStorage.getItem('spevb-viajes-guardados')]") == antes, 'favoritos y viajes guardados intactos'
    assert p.evaluate("JSON.parse(localStorage.getItem('spevb-historial') || '[]').length") >= n_hist, 'el historial no se borra'
    assert p.evaluate("document.querySelectorAll('.gps-dot').length") == gps_antes
    assert p.evaluate("(() => { const c = Spevb.maps.map.getCenter(), z = Spevb.maps.map.getZoom(); return [c.lat, c.lng, z]; })()") == centro, 'la vista del mapa no cambia bruscamente'
    # escribir de nuevo después de limpiar
    poner(p, 'origin', A_TXT)
    assert pines(p)['a'] == 1 and p.locator('#clearOrigin').is_enabled()
    s.limpio('limpiar A y B')
    s.cerrar()


@prueba
def borrar_con_una_busqueda_manual_o_sugerencias_pendientes_no_restaura_el_punto(nav):
    s, p = abrir(nav)
    # búsqueda manual pendiente
    manual = Retenidas(s.ctx, '**/api/lugares?q=*')
    p.fill('#tripOrigin', 'Carrera 7 con Calle 30'); p.click('#searchOrigin'); p.wait_for_timeout(500)
    assert manual.pend, 'la búsqueda quedó pendiente'
    p.click('#clearOrigin'); p.wait_for_timeout(200)
    manual.liberar({'lugares': [{'id': None, 'nombre': 'Lugar viejo', 'direccion': 'x', 'lat': 4.146, 'lng': -73.652, 'fuente': 'Stub', 'confirmado': False, 'precision': 'PREDIO_APROXIMADO'}], 'avisos': []})
    p.wait_for_timeout(700)
    assert p.input_value('#tripOrigin') == '' and puntos(p)['a'] is False and pines(p)['a'] == 0, 'la respuesta vieja no restaura A'
    # sugerencias pendientes en B
    sug = Retenidas(s.ctx, '**/api/lugares/sugerencias*')
    p.fill('#tripDestination', 'Avenida 40'); p.wait_for_timeout(900)
    assert sug.pend, 'la petición de sugerencias quedó pendiente'
    p.click('#clearDestination'); p.wait_for_timeout(200)
    sug.liberar({'arcgisConfigurado': True, 'direccionesLocales': True, 'sugerencias': [{'texto': 'Avenida 40 vieja', 'detalle': 'x', 'lat': 4.14, 'lng': -73.63, 'fuente': 'Stub', 'precision': 'DIRECCION_PUNTUAL', 'id': None}]})
    p.wait_for_timeout(700)
    assert p.locator('#destinationResults.suggestion-open').count() == 0 and p.locator('#destinationResults .autosuggest-option').count() == 0, 'las sugerencias viejas no reaparecen'
    s.limpio('pendientes')
    s.cerrar()


@prueba
def borrar_con_una_geocodificacion_inversa_pendiente_ignora_la_respuesta_vieja(nav):
    s, p = abrir(nav)
    rev = Retenidas(s.ctx, '**/api/lugares/reverso*')
    for modo, boton, letra in (('origin', 'pickOrigin', 'a'), ('destination', 'pickDestination', 'b')):
        p.click('#' + boton); p.wait_for_timeout(200)
        caja = p.locator('#map').bounding_box()
        p.mouse.click(caja['x'] + caja['width'] * 0.55, caja['y'] + caja['height'] * 0.4)
        p.wait_for_timeout(500)
        assert rev.pend, f'{modo}: la geocodificación inversa quedó pendiente'
        limpiar = '#clearOrigin' if modo == 'origin' else '#clearDestination'
        assert p.locator(limpiar).is_enabled(), 'con algo pendiente la X permite cancelarlo'
        p.click(limpiar); p.wait_for_timeout(200)
        rev.liberar({'id': None, 'nombre': 'Respuesta vieja', 'direccion': 'x', 'lat': 4.15, 'lng': -73.64, 'fuente': 'Stub', 'confirmado': False})
        p.wait_for_timeout(700)
        assert pines(p)[letra] == 0 and p.input_value('#tripOrigin' if modo == 'origin' else '#tripDestination') == '', f'{modo}: la respuesta vieja no restaura el punto'
    # sin borrar, la geocodificación inversa sigue funcionando
    rev2 = Retenidas(s.ctx, '**/api/lugares/reverso*')
    p.click('#pickOrigin'); caja = p.locator('#map').bounding_box(); p.mouse.click(caja['x'] + caja['width'] * 0.5, caja['y'] + caja['height'] * 0.4); p.wait_for_timeout(400)
    rev2.liberar({'id': None, 'nombre': 'Respuesta vigente', 'direccion': 'x', 'lat': 4.15, 'lng': -73.64, 'fuente': 'Stub', 'confirmado': False})
    p.wait_for_timeout(700)
    assert pines(p)['a'] == 1 and 'vigente' in p.input_value('#tripOrigin')
    s.limpio('reverso pendiente')
    s.cerrar()


@prueba
def borrar_A_mientras_se_obtiene_el_gps_invalida_el_intento_pero_no_el_gps(nav):
    s = B.Sesion(nav, geolocation={'latitude': 4.15, 'longitude': -73.63287, 'accuracy': 15}, permissions=['geolocation'])
    s.ctx.add_init_script(GPS_MANUAL)
    p = s.abrir()
    p.click('#useMyLocation'); p.wait_for_timeout(800)
    assert p.evaluate('window.__gps.length') >= 1, 'el GPS quedó esperando lectura'
    assert p.locator('#clearOrigin').is_enabled(), 'con una lectura GPS pendiente se puede cancelar'
    p.click('#clearOrigin'); p.wait_for_timeout(300)
    p.evaluate('window.__gpsResolver()')
    p.wait_for_timeout(2500)
    assert p.input_value('#tripOrigin') == '' and puntos(p)['a'] is False and pines(p)['a'] == 0, 'la lectura GPS antigua no vuelve a establecer A'
    assert 'quedó como origen' not in estado(p)
    assert p.locator('.gps-dot').count() >= 1, 'el punto azul del GPS sigue en el mapa (el seguimiento no se apaga)'
    s.limpio('GPS pendiente')
    s.cerrar()


@prueba
def intercambiar_con_solo_A_solo_B_o_ninguno_actualiza_las_x(nav):
    s, p = abrir(nav)
    p.click('#swapTrip'); p.wait_for_timeout(200)              # ninguno
    assert puntos(p) == {'a': False, 'b': False} and p.locator('#clearTripPoints').is_disabled()
    poner(p, 'origin', A_TXT)                                   # solo A
    p.click('#swapTrip'); p.wait_for_timeout(300)
    assert puntos(p) == {'a': False, 'b': True} and pines(p) == {'a': 0, 'b': 1}
    assert p.input_value('#tripOrigin') == '' and p.input_value('#tripDestination') != ''
    assert p.locator('#clearOrigin').is_disabled() and p.locator('#clearDestination').is_enabled()
    p.click('#swapTrip'); p.wait_for_timeout(300)               # solo B -> vuelve a ser solo A
    assert puntos(p) == {'a': True, 'b': False} and pines(p) == {'a': 1, 'b': 0}
    assert p.locator('#clearOrigin').is_enabled() and p.locator('#clearDestination').is_disabled()
    poner(p, 'destination', B_TXT)                              # ambos
    p.click('#swapTrip'); p.wait_for_timeout(300)
    assert puntos(p) == {'a': True, 'b': True} and p.locator('#clearOrigin').is_enabled() and p.locator('#clearDestination').is_enabled()
    s.limpio('intercambiar')
    s.cerrar()


@prueba
def borrar_todo_el_texto_con_el_teclado_equivale_a_pulsar_la_x(nav):
    s, p = abrir(nav)
    poner(p, 'origin', A_TXT)
    assert pines(p)['a'] == 1
    planes = []
    p.on('request', lambda r: planes.append(r.url) if '/api/viajes/planificar' in r.url else None)
    p.click('#tripOrigin'); p.keyboard.press('Control+A'); p.keyboard.press('Backspace'); p.wait_for_timeout(300)
    assert p.input_value('#tripOrigin') == '' and puntos(p)['a'] is False, 'no queda una coordenada oculta con el campo vacío'
    assert pines(p)['a'] == 0, 'no se conserva un marcador que ya no corresponde al texto'
    assert p.locator('#clearOrigin').is_disabled() and p.locator('.endpoint-block.has-clear').count() == 0
    assert not planes
    # escribir de nuevo después de borrar a mano
    poner(p, 'origin', A_TXT)
    assert pines(p)['a'] == 1
    s.limpio('borrado manual')
    s.cerrar()


@prueba
def accesibilidad_etiquetas_orden_de_tabulacion_y_teclado(nav):
    s, p = abrir(nav)
    esperado = {'clearOrigin': ('Borrar punto de origen', 'Borrar origen'), 'clearDestination': ('Borrar punto de destino', 'Borrar destino'), 'clearTripPoints': ('Borrar origen y destino', None)}
    for id_, (aria, title) in esperado.items():
        el = p.locator('#' + id_)
        assert el.get_attribute('aria-label') == aria and el.get_attribute('type') == 'button', id_
        if title:
            assert el.get_attribute('title') == title
    assert 'Limpiar A y B' in p.locator('#clearTripPoints').inner_text()
    assert p.locator('#clearOrigin svg').get_attribute('aria-hidden') == 'true', 'la X es un SVG decorativo con etiqueta en el botón'
    poner(p, 'origin', A_TXT)
    # orden de tabulación: campo -> X -> buscar -> elegir en mapa
    p.focus('#tripOrigin'); p.keyboard.press('Tab')
    assert p.evaluate('document.activeElement.id') == 'clearOrigin'
    p.keyboard.press('Tab'); assert p.evaluate('document.activeElement.id') == 'searchOrigin'
    # activar con el teclado, el foco vuelve al campo y el lector recibe el mensaje por tripStatus
    p.focus('#clearOrigin'); p.keyboard.press('Enter'); p.wait_for_timeout(300)
    assert p.evaluate('document.activeElement.id') == 'tripOrigin' and puntos(p)['a'] is False
    assert p.locator('#tripStatus').get_attribute('role') == 'status' and 'Origen eliminado' in estado(p)
    # Escape cierra sugerencias y Enter con coordenadas sigue buscando
    p.fill('#tripDestination', 'Carrera 7'); p.wait_for_timeout(900)
    assert p.locator('#destinationResults.suggestion-open').count() == 1
    p.keyboard.press('ArrowDown'); p.keyboard.press('Escape'); p.wait_for_timeout(200)
    assert p.locator('#destinationResults.suggestion-open').count() == 0
    poner(p, 'destination', B_TXT)
    assert pines(p)['b'] == 1
    s.limpio('accesibilidad')
    s.cerrar()


@prueba
def las_sugerencias_de_arcgis_siguen_funcionando(nav):
    """Con ArcGIS «configurado» (stub) escribir muestra sugerencias y elegir una fija el punto: el flujo existente no cambió."""
    s, p = abrir(nav)
    p.fill('#tripOrigin', 'Carrera 7'); p.wait_for_timeout(900)
    assert p.locator('#originResults .autosuggest-option').count() == 1
    p.locator('#originResults .autosuggest-option').first.click(); p.wait_for_timeout(500)
    assert pines(p)['a'] == 1 and 'prueba' in p.input_value('#tripOrigin') and p.locator('#clearOrigin').is_enabled()
    s.limpio('sugerencias')
    s.cerrar()


@prueba
def movil_y_escritorio_sin_desborde_y_con_area_tactil_de_44px(nav):
    casos = [('escritorio 1280', {'viewport': {'width': 1280, 'height': 800}}, False),
             ('móvil 390', {'viewport': {'width': 390, 'height': 844}, 'is_mobile': True, 'has_touch': True}, True),
             ('móvil pequeño 320', {'viewport': {'width': 320, 'height': 640}, 'is_mobile': True, 'has_touch': True}, True)]
    for nombre, opts, tactil in casos:
        s, p = abrir(nav, **opts)
        poner(p, 'origin', A_TXT)
        poner(p, 'destination', B_TXT)
        assert p.evaluate('document.documentElement.scrollWidth - window.innerWidth') <= 1, f'{nombre}: desbordamiento horizontal'
        for id_ in ('clearOrigin', 'clearDestination'):
            caja = p.locator('#' + id_).bounding_box()
            assert caja and caja['width'] > 0, f'{nombre}: {id_} visible'
            if tactil:
                assert caja['width'] >= 43.5 and caja['height'] >= 43.5, f'{nombre}: {id_} mide {caja["width"]:.0f}×{caja["height"]:.0f} px (mínimo 44)'
        # el texto no queda bajo los botones y queda un ancho útil razonable
        util = p.evaluate("""() => { const i = document.getElementById('tripOrigin'), cs = getComputedStyle(i);
            const tools = document.querySelector('#clearOrigin').closest('.endpoint-tools').getBoundingClientRect(), campo = i.getBoundingClientRect();
            return { util: i.clientWidth - parseFloat(cs.paddingLeft) - parseFloat(cs.paddingRight), padding: parseFloat(cs.paddingRight), herramientas: tools.width, solape: (tools.top >= campo.bottom - 1 ? 0 : campo.right - tools.left) }; }""")      # herramientas bajo el campo (teléfonos de 360 px o menos): no hay solape
        assert util['padding'] + 1 >= util['solape'], f'{nombre}: el texto queda bajo los botones: {util}'
        print(f'      [{nombre}] ancho útil del texto en A con la X visible: {util["util"]:.0f} px')
        assert util['util'] >= 60, f'{nombre}: campo demasiado estrecho: {util}'
        s.limpio(nombre)
        s.cerrar()


def correr(filtro=None):
    fallos = []
    with sync_playwright() as p:
        nav = B.navegador(p)
        print('Navegador:', nav.browser_type.name, nav.version, '| URL:', B.URL)
        for f in PRUEBAS:
            if filtro and filtro not in f.__name__:
                continue
            try:
                f(nav)
                print('OK    ', f.__name__)
            except Exception as e:  # noqa: BLE001
                fallos.append(f.__name__)
                print('FALLA ', f.__name__, '|', str(e).splitlines()[0][:300] if str(e) else repr(e))
                traceback.print_exc(limit=3)
        nav.close()
    print(f'\n{len(PRUEBAS) if not filtro else "?"} pruebas de borrado de A/B; fallos: {len(fallos)} {fallos}')
    return 1 if fallos else 0


if __name__ == '__main__':
    sys.exit(correr(sys.argv[1] if len(sys.argv) > 1 else None))
