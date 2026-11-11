"""Pruebas del MAPA BASE DE GOOGLE (4.14) en un navegador real con Google SIMULADO: no hay clave ni Internet, así que la «Maps JavaScript API» se sustituye por un
doble que registra lo que recibe. Prueba la integración (carga del script, creación del mapa, sincronización con Leaflet, respaldo ante fallos y clave inválida),
NO el aspecto real del mapa de Google ni su facturación: eso requiere una clave y un navegador con Internet.
Uso: SEVB_E2E_URL=http://127.0.0.1:18080/ python e2e/mapa_google_e2e.py [filtro]"""
import json
import os
import sys
from urllib.parse import urlparse

if hasattr(sys.stdout, 'reconfigure'):
    sys.stdout.reconfigure(encoding='utf-8', errors='replace')
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import sevb_e2e as B  # noqa: E402
from playwright.sync_api import sync_playwright  # noqa: E402

PRUEBAS = []
CLAVE = 'AIzaSyFALSA_FALSA_FALSA_FALSA_12345'
GOOGLE_FALSO = """
window.__gm = { creadas: 0, opciones: null, ultimo: null, llamadas: 0 };
window.google = { maps: { Map: function (div, o) { window.__gm.creadas++; window.__gm.opciones = o; window.__gm.div = div;
    this.moveCamera = function (c) { window.__gm.llamadas++; window.__gm.ultimo = c; }; } } };
setTimeout(function () { window.__spevbGoogleListo(); %s }, 20);
"""


def prueba(f):
    PRUEBAS.append(f)
    return f


def sesion(nav, modo='ok', clave=CLAVE, **op):
    s = B.Sesion(nav, **{'viewport': {'width': 390, 'height': 844}, **op})
    s.pedidos = []
    s.ctx.on('request', lambda r: s.pedidos.append(r.url))
    s.ctx.route(lambda u: urlparse(u).path == '/api/mapa/config', lambda r: r.fulfill(
        status=200, content_type='application/json', body=json.dumps({'googleMapsKey': clave, 'proveedor': 'google' if clave else 'respaldo'})))
    if modo == 'ok':
        s.ctx.route('https://maps.googleapis.com/maps/api/js*', lambda r: r.fulfill(status=200, content_type='application/javascript', body=GOOGLE_FALSO % ''))
    elif modo == 'clave':
        s.ctx.route('https://maps.googleapis.com/maps/api/js*', lambda r: r.fulfill(status=200, content_type='application/javascript', body=GOOGLE_FALSO % 'window.gm_authFailure();'))
    else:
        s.ctx.route('https://maps.googleapis.com/maps/api/js*', lambda r: r.abort())
    return s


@prueba
def con_clave_el_mapa_base_es_google_y_no_se_descarga_el_de_respaldo(nav):
    s = sesion(nav); p = s.abrir(espera=800)
    B.esperar(p, "Spevb.maps.map.activeBase() === 'Google' && window.__gm && window.__gm.creadas === 1", 8000)
    o = p.evaluate('window.__gm.opciones')
    assert o['disableDefaultUI'] and o['gestureHandling'] == 'none' and o['draggable'] is False and o['keyboardShortcuts'] is False, o
    url = next(u for u in s.pedidos if 'maps.googleapis.com/maps/api/js' in u)
    assert 'key=' + CLAVE in url and 'callback=__spevbGoogleListo' in url and 'loading=async' in url and 'language=es' in url, url
    assert not any('tiles.openfreemap.org' in u or '/vendor/maplibre/' in u for u in s.pedidos), 'con Google no se descarga el mapa de respaldo'
    assert p.locator('.google-base').count() == 1
    s.limpio('google base'); s.cerrar()


@prueba
def el_mapa_de_google_sigue_el_centro_y_el_zoom_de_leaflet(nav):
    s = sesion(nav); p = s.abrir(espera=800)
    B.esperar(p, "!!window.__gm && window.__gm.creadas === 1", 8000)
    p.evaluate("Spevb.maps.map.setView([4.1500, -73.6300], 16, { animate: false })")
    p.wait_for_timeout(200)
    u = p.evaluate('window.__gm.ultimo')
    assert abs(u['center']['lat'] - 4.15) < 1e-4 and abs(u['center']['lng'] + 73.63) < 1e-4 and u['zoom'] == 16, u
    p.evaluate("Spevb.maps.map.panBy([200, 0], { animate: false })"); p.wait_for_timeout(200)
    c = p.evaluate("Spevb.maps.map.getCenter()")
    u2 = p.evaluate('window.__gm.ultimo')
    assert abs(u2['center']['lng'] - c['lng']) < 1e-6, (u2, c)
    assert p.evaluate("Spevb.maps.map.options.zoomAnimation") is False, 'sin animación de zoom CSS mientras Google es el fondo (no desfasa marcadores)'
    s.limpio('sincronizacion'); s.cerrar()


@prueba
def los_gestos_y_las_rutas_siguen_siendo_de_leaflet_con_google_de_fondo(nav):
    s = sesion(nav); p = s.abrir(espera=800)
    B.esperar(p, "!!window.__gm && window.__gm.creadas === 1", 8000)
    p.fill('#tripOrigin', B.A); p.keyboard.press('Enter'); p.wait_for_timeout(600)
    p.fill('#tripDestination', B.B); p.keyboard.press('Enter'); p.wait_for_timeout(600)
    p.evaluate("document.querySelector('#planTrip').click()")
    B.esperar(p, "document.querySelectorAll('#tripResults .trip-option').length > 0", 12000)
    assert p.locator('.trip-pin.a').count() == 1 and p.locator('.trip-pin.b').count() == 1, 'marcadores A y B de Leaflet sobre el mapa de Google'
    assert p.evaluate("getComputedStyle(document.querySelector('.google-base')).pointerEvents") == 'none', 'el fondo de Google no intercepta gestos'
    antes = p.evaluate('window.__gm.llamadas')
    p.evaluate("Spevb.maps.map.panBy([120, 60], { animate: false })"); p.wait_for_timeout(200)
    assert p.evaluate('window.__gm.llamadas') > antes
    s.limpio('rutas con google'); s.cerrar()


@prueba
def si_google_no_carga_se_usa_el_mapa_de_respaldo(nav):
    s = sesion(nav, modo='falla'); p = s.abrir(espera=800)
    B.esperar(p, "Spevb.maps.map.activeBase() === 'Moderno'", 10000)
    assert p.locator('.google-base').count() == 0, 'el fondo de Google se retira'
    assert p.evaluate("Spevb.maps.map.options.zoomAnimation") is not False, 'la animación de zoom de Leaflet se restaura'
    s.limpio('google falla'); s.cerrar()


@prueba
def una_clave_invalida_o_sin_permiso_cae_al_respaldo(nav):
    s = sesion(nav, modo='clave'); p = s.abrir(espera=800)
    B.esperar(p, "Spevb.maps.map.activeBase() === 'Moderno'", 10000)
    s.limpio('clave invalida'); s.cerrar()


@prueba
def sin_clave_se_usa_el_mapa_de_respaldo_y_no_se_pide_nada_a_google(nav):
    s = sesion(nav, clave=''); p = s.abrir(espera=800)
    B.esperar(p, "Spevb.maps.map.activeBase() === 'Moderno'", 8000)
    assert not any('maps.googleapis.com' in u for u in s.pedidos)
    s.limpio('sin clave'); s.cerrar()


@prueba
def sin_conexion_no_se_intenta_google(nav):
    s = sesion(nav); s.ctx.add_init_script("Object.defineProperty(navigator, 'onLine', { get: () => false });"); p = s.abrir(espera=800)
    B.esperar(p, "Spevb.maps.map.activeBase() === 'Moderno'", 8000)
    assert not any('maps.googleapis.com' in u for u in s.pedidos)
    s.cerrar()


def correr(filtro=None):
    fallos = []
    with sync_playwright() as p:
        nav = B.navegador(p)
        for f in PRUEBAS:
            if filtro and filtro not in f.__name__:
                continue
            try:
                f(nav)
                print('OK    ', f.__name__)
            except Exception as e:  # noqa: BLE001
                fallos.append(f.__name__)
                print('FALLA ', f.__name__, '|', str(e)[:500].replace('\n', ' '))
        nav.close()
    print(f'\n{len([f for f in PRUEBAS if not filtro or filtro in f.__name__])} pruebas del mapa base de Google; fallos: {len(fallos)}', fallos)
    return 1 if fallos else 0


if __name__ == '__main__':
    sys.exit(correr(sys.argv[1] if len(sys.argv) > 1 else None))
