"""Regresión del LAYOUT del mapa al abrir, cerrar y ocultar el panel (celular y escritorio).

Defecto reproducido (390 × 844, SDK simulado): con el panel oculto el mapa de Google seguía midiendo lo mismo que con el panel visible
(576 px en «peek», 51 px en «full») porque `--sheet-real` no se actualizaba al ocultarlo. Estas pruebas MIDEN el tamaño efectivo del contenedor
(no solo que exista un canvas) y comprueban que el mapa conserva centro, zoom y elementos.

SDK de Google simulado (e2e/harness/google_fake.js): válido para el layout; no demuestra rendimiento ni gestos del SDK real.
Uso: SEVB_E2E_URL=http://127.0.0.1:18080/ python e2e/layout_panel_e2e.py [filtro]    (levanta su propio servidor Google en el puerto 18125)
"""
import os
import sys
import traceback

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import sevb_e2e as B  # noqa: E402
import mapa_adaptadores_e2e as M  # noqa: E402
from playwright.sync_api import sync_playwright  # noqa: E402

PUERTO_GOOGLE = int(os.environ.get('SEVB_E2E_PUERTO_LAYOUT', '18125'))
PRUEBAS = []
MOVIL = {'viewport': {'width': 390, 'height': 844}, 'is_mobile': True, 'has_touch': True}


def prueba(f):
    PRUEBAS.append(f)
    return f


MEDIR = """() => {
  const m = document.getElementById('map').getBoundingClientRect(), s = document.getElementById('sheet').getBoundingClientRect();
  const cs = getComputedStyle(document.documentElement), map = Spevb.maps.map;
  return { mapaAlto: Math.round(m.height), mapaAbajo: Math.round(m.bottom), panelArriba: Math.round(s.top), alto: innerHeight,
           real: parseFloat(cs.getPropertyValue('--sheet-real')) || 0, visible: parseFloat(cs.getPropertyValue('--sheet-visible')) || 0,
           oculto: document.body.classList.contains('panel-oculto'), estado: document.getElementById('sheet').dataset.state,
           centro: map.getCenter(), zoom: map.getZoom(), tam: map.getSize() };
}"""


def medir(p):
    p.wait_for_timeout(450)           # deja terminar la transición del panel (var(--motion-normal))
    return p.evaluate(MEDIR)


def a_estado(p, destino):
    for _ in range(4):
        if medir(p)['estado'] == destino:
            return
        p.locator('#sheetHandle').click()
    assert medir(p)['estado'] == destino, f'no se pudo llegar a {destino}'


def abrir(nav, base, **opciones):
    g = M.Google(nav, base, **{**MOVIL, **opciones})
    p = g.abrir()
    p.wait_for_timeout(600)
    return g, p


def ocultar(p):
    p.locator('#panelHide').click()


def mostrar(p):
    p.locator('#panelShow').click()


@prueba
def google_el_mapa_se_libera_al_ocultar_el_panel_desde_cada_estado(nav):
    g, p = abrir(nav, BASES['google'])
    for estado in ('peek', 'half', 'full'):
        a_estado(p, estado)
        antes = medir(p)
        assert antes['mapaAbajo'] <= antes['panelArriba'] + 1.5, f'{estado}: con el panel visible el mapa termina sobre el panel (créditos de Google visibles): {antes}'
        assert abs(antes['real'] - (antes['alto'] - antes['panelArriba'])) <= 1.5, f'{estado}: --sheet-real debe ser el área realmente ocupada: {antes}'
        ocultar(p)
        oculto = medir(p)
        assert oculto['oculto']
        assert oculto['mapaAlto'] >= oculto['alto'] - 1, f'{estado}: oculto el panel el mapa debe ocupar toda la pantalla, mide {oculto["mapaAlto"]} de {oculto["alto"]} (defecto confirmado: seguía en {antes["mapaAlto"]})'
        assert oculto['real'] == 0, f'{estado}: --sheet-real debe liberarse al ocultar: {oculto}'
        assert abs(oculto['centro'][0] - antes['centro'][0]) < 1e-6 and abs(oculto['zoom'] - antes['zoom']) < 1e-9, f'{estado}: ocultar el panel no debe mover la cámara'
        mostrar(p)
        despues = medir(p)
        assert not despues['oculto'] and despues['estado'] == estado, f'{estado}: al mostrarlo recupera su estado anterior: {despues}'
        assert despues['mapaAbajo'] <= despues['panelArriba'] + 1.5 and abs(despues['real'] - antes['real']) <= 1.5, f'{estado}: reserva restaurada: {antes} → {despues}'
    assert p.evaluate('__gfake.mapas') == 1, 'el mapa nunca se recrea'
    g.s.limpio('layout panel'); g.cerrar()


@prueba
def google_resize_y_orientacion_con_el_panel_oculto_no_restauran_una_reserva_vieja(nav):
    g, p = abrir(nav, BASES['google'])
    a_estado(p, 'half')
    ocultar(p)
    assert medir(p)['mapaAlto'] >= 843
    p.set_viewport_size({'width': 844, 'height': 390})
    m = medir(p)
    assert m['oculto'] and m['real'] == 0, f'girar la pantalla con el panel oculto no restaura la reserva: {m}'
    assert m['mapaAlto'] >= m['alto'] - 1, f'apaisado y oculto: el mapa ocupa todo el alto: {m}'
    p.set_viewport_size({'width': 390, 'height': 844})
    m = medir(p)
    assert m['oculto'] and m['real'] == 0 and m['mapaAlto'] >= 843, f'de vuelta a vertical, sigue sin reserva: {m}'
    p.set_viewport_size({'width': 390, 'height': 700})          # barra del navegador / teclado que cambia el alto
    m = medir(p)
    assert m['real'] == 0 and m['mapaAlto'] >= 699, f'cambio de alto con el panel oculto: {m}'
    mostrar(p)
    m = medir(p)
    assert m['real'] > 0 and m['mapaAbajo'] <= m['panelArriba'] + 1.5, f'al mostrarlo se vuelve a reservar lo que ocupa: {m}'
    g.s.limpio('resize'); g.cerrar()


@prueba
def google_teclado_y_foco_con_el_panel_ocultado_despues(nav):
    g, p = abrir(nav, BASES['google'])
    p.locator('#tripOrigin').click()            # el foco abre el panel completo (sheet.js)
    p.keyboard.type('Cra 22')
    # Teclado virtual simulado (iPhone): el visual viewport se encoge 300 px y la página no.
    p.evaluate("""() => { const vv = window.visualViewport; Object.defineProperty(vv, 'height', { configurable: true, get: () => innerHeight - 300 });
        Object.defineProperty(vv, 'offsetTop', { configurable: true, get: () => 0 }); vv.dispatchEvent(new Event('resize')); }""")
    m = medir(p)
    assert m['mapaAbajo'] <= m['panelArriba'] + 1.5, f'con teclado el mapa sigue terminando sobre el panel: {m}'
    p.locator('#panelHide').click(force=True)
    m = medir(p)
    assert m['oculto'] and m['real'] == 0 and m['mapaAlto'] >= m['alto'] - 1, f'ocultar con un campo enfocado y teclado: {m}'
    p.evaluate("""() => { const vv = window.visualViewport; delete vv.height; delete vv.offsetTop; vv.dispatchEvent(new Event('resize')); }""")
    m = medir(p)
    assert m['real'] == 0 and m['mapaAlto'] >= m['alto'] - 1, f'cerrar el teclado con el panel oculto no restaura la reserva: {m}'
    mostrar(p)
    assert medir(p)['mapaAbajo'] <= medir(p)['panelArriba'] + 1.5
    g.s.limpio('teclado'); g.cerrar()


@prueba
def google_resultados_menu_y_viaje_con_el_panel_oculto(nav):
    g, p = abrir(nav, BASES['google'])
    B.planificar(p)
    a_estado(p, 'peek')
    antes = medir(p)
    p.locator('#menuButton').click()                         # el botón del menú vive en la cabecera del panel: con el panel visible
    p.wait_for_timeout(500)
    assert medir(p)['mapaAlto'] == antes['mapaAlto'], 'abrir el menú lateral no cambia el mapa'
    p.locator('#closeMenu').click()
    p.wait_for_timeout(400)
    ocultar(p)
    m = medir(p)
    assert m['mapaAlto'] >= m['alto'] - 1, f'tras recibir resultados y ocultar: {m}'
    mostrar(p)
    assert medir(p)['mapaAbajo'] <= medir(p)['panelArriba'] + 1.5
    if p.locator('#startTrip').is_visible():
        p.locator('#startTrip').click(); p.wait_for_timeout(1500)
        ocultar(p)
        m = medir(p)
        assert m['mapaAlto'] >= m['alto'] - 1, f'ocultar el panel en pleno modo viaje: {m}'
        card = p.evaluate("""(() => { const c = document.getElementById('viajeCard'); if (!c || c.hidden) return null; const r = c.getBoundingClientRect(), b = document.getElementById('panelShow').getBoundingClientRect();
            return { abajo: Math.round(innerHeight - r.bottom), solapa: !(r.bottom <= b.top || r.top >= b.bottom || r.right <= b.left || r.left >= b.right) }; })()""")
        if card:
            assert card['abajo'] <= 110, f'con el panel oculto la tarjeta del viaje baja, no queda flotando donde estaba el panel: {card}'
            assert not card['solapa'], f'la tarjeta del viaje no tapa el botón «Buscar ruta»: {card}'
        mostrar(p)
    assert p.evaluate('__gfake.mapas') == 1
    g.s.limpio('resultados/menú/viaje'); g.cerrar()


@prueba
def google_veinte_repeticiones_sin_acumular_nada(nav):
    g, p = abrir(nav, BASES['google'])
    base = p.evaluate('({ esc: __gfake.escuchas, vis: __gfake.marcadores.filter(m => m.map).length + __gfake.polilineas.filter(l => l._map).length })')
    a_estado(p, 'half')
    c0 = medir(p)
    for i in range(20):
        ocultar(p); mostrar(p)
        if i % 5 == 4:
            a_estado(p, ('peek', 'half', 'full')[i // 5 % 3]); a_estado(p, 'half')
    p.wait_for_timeout(500)
    fin = p.evaluate('({ esc: __gfake.escuchas, mapas: __gfake.mapas, vis: __gfake.marcadores.filter(m => m.map).length + __gfake.polilineas.filter(l => l._map).length })')
    assert fin['mapas'] == 1 and fin['esc'] <= base['esc'] + 2 and fin['vis'] == base['vis'], f'repetir 20 veces no acumula: {base} → {fin}'
    c1 = medir(p)
    assert abs(c1['centro'][0] - c0['centro'][0]) < 1e-6 and c1['zoom'] == c0['zoom'], 'la cámara no se movió tras 20 repeticiones'
    assert c1['mapaAbajo'] <= c1['panelArriba'] + 1.5
    g.s.limpio('20 repeticiones'); g.cerrar()


@prueba
def google_pantallas_pequenas_y_escritorio(nav):
    for ancho, alto, movil in ((320, 568, True), (360, 640, True), (430, 932, True), (1366, 800, False)):
        g, p = abrir(nav, BASES['google'], viewport={'width': ancho, 'height': alto}, is_mobile=movil, has_touch=movil)
        ocultar(p)
        m = medir(p)
        assert m['mapaAlto'] >= m['alto'] - 1 and m['real'] == 0, f'{ancho}×{alto} oculto: {m}'
        mostrar(p)
        m = medir(p)
        if p.evaluate('Spevb.compactLayout()'):
            assert m['mapaAbajo'] <= m['panelArriba'] + 1.5, f'{ancho}×{alto} visible: {m}'
        assert m['mapaAlto'] > 100
        g.s.limpio(f'{ancho}'); g.cerrar()


@prueba
def leaflet_el_mapa_no_cambia_de_tamano_al_ocultar_el_panel(nav):
    B.URL = M.URL_LEAFLET
    s = B.Sesion(nav, **MOVIL)
    p = s.abrir()
    for estado in ('peek', 'half', 'full'):
        a_estado(p, estado)
        antes = medir(p)
        ocultar(p); oculto = medir(p); mostrar(p)
        assert oculto['mapaAlto'] == antes['mapaAlto'] == antes['alto'], f'Leaflet: el mapa va a pantalla completa en ambos estados: {antes} {oculto}'
        assert oculto['real'] == 0
    s.limpio('leaflet panel'); s.cerrar()


def correr(filtro=None):
    fallos = []
    global BASES
    BASES = {}
    M.BASES = BASES
    try:
        BASES['google'] = M.lanzar_stub(PUERTO_GOOGLE)
        with sync_playwright() as p:
            nav = B.navegador(p)
            print('Navegador:', nav.browser_type.name, nav.version)
            for f in PRUEBAS:
                if filtro and filtro not in f.__name__:
                    continue
                B.URL = M.URL_LEAFLET
                try:
                    f(nav)
                    print('OK    ', f.__name__)
                except Exception as e:  # noqa: BLE001
                    fallos.append(f.__name__)
                    print('FALLA ', f.__name__, '|', str(e).splitlines()[0][:420] if str(e) else repr(e))
                    traceback.print_exc(limit=2)
            nav.close()
    finally:
        for pr in M._PROCESOS:
            pr.terminate()
    print(f'\n{len(PRUEBAS) if not filtro else "?"} pruebas de layout del panel; fallos: {len(fallos)} {fallos}')
    return 1 if fallos else 0


if __name__ == '__main__':
    sys.exit(correr(sys.argv[1] if len(sys.argv) > 1 else None))
