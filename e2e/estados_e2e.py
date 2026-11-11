"""Pruebas de regresión de 4.10.3: estados obsoletos entre el planificador y el explorador (selección, bus, capas, ficha, `?ruta=`),
respuestas tardías, historial con los códigos reales y enlaces compartidos.

Cada prueba reproduce un error que existía en 4.10.2. Uso: SEVB_E2E_URL=http://127.0.0.1:18080/ python e2e/estados_e2e.py [filtro]
(lo normal es e2e/run_e2e.py). Los escenarios del stub se eligen por la latitud del origen (ver resultados_e2e.py).
"""
import json
import os
import sys

if hasattr(sys.stdout, 'reconfigure'):
    sys.stdout.reconfigure(encoding='utf-8', errors='replace')      # la consola de Windows (cp1252) no admite todos los símbolos de los mensajes
import traceback

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import sevb_e2e as B  # noqa: E402
from playwright.sync_api import sync_playwright  # noqa: E402

PRUEBAS = []
DEST = '4.1450, -73.6240'
ESC = {'multi': '4.1470, -73.6530', 'normales': '4.1475, -73.6530', 'contrarias': '4.1485, -73.6530', 'transbordos': '4.1433, -73.6020', 'mixto': '4.1490, -73.6530'}
MOVIL = {'viewport': {'width': 384, 'height': 848}, 'is_mobile': True, 'has_touch': True, 'device_scale_factor': 2}
GPS = {'latitude': 4.15, 'longitude': -73.63287, 'accuracy': 15}
VIAJES = "window.__viajes = []; window.addEventListener('spevb-viaje', (e) => window.__viajes.push(e.detail.codigos));"


def prueba(f):
    PRUEBAS.append(f)
    return f


class Retenidas:
    """Retiene las peticiones que coinciden con el patrón hasta que la prueba las libera (simula una conexión lenta)."""

    def __init__(self, ctx, patron, metodo=None):
        self.pend = []
        ctx.route(patron, lambda route: self.pend.append(route) if (metodo is None or route.request.method == metodo) else route.continue_())

    def liberar(self):
        for r in self.pend:
            try:
                r.continue_()
            except Exception:  # noqa: BLE001  (la página abortó la petición: es lo esperado)
                pass
        self.pend = []


def sesion(nav, **opts):
    s = B.Sesion(nav, **opts)
    s.ctx.add_init_script(VIAJES)
    return s


def esperar_resultados(p, espera=1800):
    B.esperar(p, "document.querySelectorAll('#tripResults .trip-option').length > 0", 12000)
    p.wait_for_timeout(espera)


def poner_puntos(p, escenario):
    p.fill('#tripOrigin', ESC[escenario]); p.keyboard.press('Enter'); p.wait_for_timeout(700)
    p.fill('#tripDestination', DEST); p.keyboard.press('Enter'); p.wait_for_timeout(700)


def planear(p, escenario='multi', espera=1800):
    poner_puntos(p, escenario)
    p.evaluate("document.querySelector('#planTrip').click()")
    esperar_resultados(p, espera)


def primera_contraria(p):
    g = p.locator('details.results-group', has=p.locator('summary strong', has_text='sentido contrario'))
    g.locator(':scope > summary').click()
    p.wait_for_timeout(200)
    return g.locator('.trip-option').first


def seleccion(p):
    return p.evaluate("Spevb.Planner.seleccion && Spevb.Planner.seleccion.codigos")


def poligonos(p):
    """Cantidad de polilíneas dibujadas en el mapa (rutas, tramos, caminatas)."""
    return p.evaluate("SpevbExplorer.map.elementos().filter((e) => e.tipo === 'linea').length")


def vista(p):
    return p.evaluate("(() => { const m = SpevbExplorer.map, c = m.getCenter(); return [c[0], c[1], m.getZoom()]; })()")


def pines(p):
    return p.evaluate("({a: document.querySelectorAll('.trip-pin.a').length, b: document.querySelectorAll('.trip-pin.b').length})")


def aviso_activo(p):
    return p.evaluate("document.getElementById('alightAlert').getAttribute('aria-pressed') === 'true'")


def activar_aviso(p):
    p.click('#alightAlert')
    B.esperar(p, "document.getElementById('alightAlert').getAttribute('aria-pressed') === 'true'", 5000)


def sin_rastro_del_recorrido(p, base, que):
    assert p.evaluate("document.querySelectorAll('.bus-wrapper').length") == 0, f'{que}: el marcador del bus sigue en el mapa'
    assert p.locator('.bus-panel').is_hidden(), f'{que}: los controles del bus siguen visibles'
    assert p.evaluate("document.getElementById('routeCard').classList.contains('hidden')"), f'{que}: la ficha de la ruta sigue visible'
    assert p.evaluate("document.getElementById('fitRoute').disabled"), f'{que}: «Encuadrar» sigue habilitado'
    assert poligonos(p) == base, f'{que}: quedan capas de la ruta anterior ({poligonos(p)} frente a {base})'
    assert 'ruta=' not in p.evaluate('location.search'), f'{que}: la URL conserva ?ruta='
    assert seleccion(p) is None, f'{que}: la selección sigue viva'
    assert p.locator('#tripTools').is_hidden(), f'{que}: la barra del viaje sigue visible'


# ---------------------------------------------------------------------------------------------- 1. selección obsoleta
@prueba
def abrir_una_ruta_independiente_invalida_la_seleccion_y_detiene_el_aviso(nav):
    s = sesion(nav, geolocation=GPS, permissions=['geolocation'])
    p = s.abrir()
    planear(p, 'multi')
    assert seleccion(p) == ['M01'] and p.locator('#tripTools').is_visible()
    activar_aviso(p)
    p.evaluate("SpevbExplorer.showRoute(1)")
    B.esperar(p, "!document.getElementById('routeCard').classList.contains('hidden')", 8000)
    assert seleccion(p) is None, 'la selección anterior ya no es verdad'
    assert p.locator('#tripTools').is_hidden(), 'la barra del viaje se oculta'
    assert not aviso_activo(p), '«Avisarme al bajar» no puede seguir vigilando el viaje anterior'
    assert p.evaluate("document.querySelectorAll('.trip-option.selected').length") == 0
    s.limpio('ruta independiente')
    s.cerrar()


@prueba
def si_la_nueva_ruta_falla_no_reaparece_la_seleccion_anterior(nav):
    s = sesion(nav)
    p = s.abrir()
    planear(p, 'multi')
    assert seleccion(p) == ['M01']
    s.ctx.route('**/api/rutas/25', lambda r: r.abort())           # M05, la mejor del sentido contrario
    primera_contraria(p).locator(':scope > summary').click()
    p.wait_for_timeout(1500)
    # 4.14: elegir una opción ya no espera al detalle de su ruta (si el detalle falla, la opción queda elegida sin línea dibujada).
    # Lo que no debe pasar es que reaparezca la selección ANTERIOR (M01) como si nada hubiera cambiado.
    assert seleccion(p) != ['M01'], 'la selección vieja no debe reaparecer cuando la nueva ruta falla'
    s.limpio('carga fallida')
    s.cerrar()


@prueba
def cambiar_de_tarjeta_en_el_planificador_conserva_el_aviso_con_la_nueva_opcion(nav):
    s = sesion(nav, geolocation=GPS, permissions=['geolocation'])
    p = s.abrir()
    planear(p, 'multi')
    activar_aviso(p)
    primera_contraria(p).locator(':scope > summary').click()
    B.esperar(p, "Spevb.Planner.seleccion && Spevb.Planner.seleccion.codigos[0] === 'M05'", 8000)
    p.wait_for_timeout(400)
    assert aviso_activo(p), 'el aviso se reanuda con la opción nueva (comportamiento que ya existía)'
    assert p.locator('#tripTools').is_visible()
    s.limpio('cambio de tarjeta')
    s.cerrar()


# ---------------------------------------------------------------------------------------------- 2. borrar A/B limpia el recorrido
def borrar(nav, boton, pines_esperados, que):
    s = sesion(nav, geolocation=GPS, permissions=['geolocation'])
    p = s.abrir()
    base = poligonos(p)
    p.evaluate("Spevb.Datos.escribir('lugares', [{id: 'f1', nombre: 'Casa', lat: 4.14, lng: -73.62}])")
    planear(p, 'multi')
    assert seleccion(p) == ['M01'] and p.evaluate("document.querySelectorAll('.bus-wrapper').length") == 1 and p.locator('.bus-panel').is_visible()
    assert 'ruta=' not in p.evaluate('location.search'), 'el dibujo interno del viaje no escribe ?ruta='
    centro = vista(p)
    historial = p.evaluate("Spevb.Datos.leer('historial').length")
    activar_aviso(p)
    p.click(boton)
    p.wait_for_timeout(500)
    assert p.locator('#tripResults').inner_text().strip() == '', f'{que}: los resultados quedan vacíos'
    sin_rastro_del_recorrido(p, base, que)
    assert not aviso_activo(p), f'{que}: el aviso de bajada queda detenido'
    assert pines(p) == pines_esperados, f'{que}: pines {pines(p)}'
    assert vista(p) == centro, f'{que}: el centro y el zoom no cambian'
    assert p.evaluate("Spevb.Datos.leer('lugares').length") == 1 and p.evaluate("Spevb.Datos.leer('historial').length") == historial, f'{que}: favoritos e historial intactos'
    s.limpio(que)
    s.cerrar()


@prueba
def borrar_A_limpia_el_bus_la_ruta_la_ficha_y_la_url(nav):
    borrar(nav, '#clearOrigin', {'a': 0, 'b': 1}, 'borrar A')


@prueba
def borrar_B_limpia_el_bus_la_ruta_la_ficha_y_la_url(nav):
    borrar(nav, '#clearDestination', {'a': 1, 'b': 0}, 'borrar B')


@prueba
def limpiar_A_y_B_limpia_el_bus_la_ruta_la_ficha_y_la_url(nav):
    borrar(nav, '#clearTripPoints', {'a': 0, 'b': 0}, 'limpiar A y B')


@prueba
def borrar_conserva_el_gps_general_y_una_ruta_abierta_por_la_persona(nav):
    s = sesion(nav, geolocation=GPS, permissions=['geolocation'])
    p = s.abrir()
    p.click('#useMyLocation'); p.wait_for_timeout(2600)
    assert p.locator('.gps-dot').count() == 1
    p.fill('#tripDestination', DEST); p.keyboard.press('Enter'); p.wait_for_timeout(700)
    # una ruta que la persona abrió por su cuenta no es del planificador: borrar B no la toca
    p.evaluate("SpevbExplorer.showRoute(1)")
    B.esperar(p, "!document.getElementById('routeCard').classList.contains('hidden')", 8000)
    p.click('#clearDestination'); p.wait_for_timeout(400)
    assert p.locator('.gps-dot').count() == 1, 'el punto azul del GPS general sigue'
    assert p.evaluate("!document.getElementById('routeCard').classList.contains('hidden')"), 'la ruta abierta por la persona sigue'
    assert 'ruta=1' in p.evaluate('location.search')
    s.limpio('GPS y ruta propia')
    s.cerrar()


@prueba
def elegir_en_el_mapa_conserva_el_viaje_hasta_confirmar_y_confirmar_lo_invalida(nav):
    s = sesion(nav, **MOVIL)
    p = s.abrir()
    base = poligonos(p)
    planear(p, 'multi')
    p.click('#pickDestination'); p.wait_for_timeout(500)
    assert seleccion(p) == ['M01'] and p.evaluate("document.querySelectorAll('.bus-wrapper').length") == 1, 'solo entrar en «Elegir en el mapa» no borra el viaje'
    p.keyboard.press('Escape'); p.wait_for_timeout(300)
    assert seleccion(p) == ['M01'], 'cancelar tampoco'
    p.click('#pickDestination'); p.wait_for_timeout(400)
    p.evaluate("(() => { const m = SpevbExplorer.map, c = m.toPoint(m.getCenter()); m.panTo(m.toLatLng({ x: c.x + 60, y: c.y }), { animate: false }); })()"); p.wait_for_timeout(300)
    p.locator('#spevbMapToasts .toast-primary').click(); p.wait_for_timeout(900)
    sin_rastro_del_recorrido(p, base, 'confirmar un punto nuevo')
    s.limpio('elegir en el mapa')
    s.cerrar()


# ---------------------------------------------------------------------------------------------- 3. respuestas tardías
@prueba
def una_ruta_retenida_no_reaparece_despues_de_borrar_B_o_limpiar_A_y_B(nav):
    for boton in ('#clearDestination', '#clearTripPoints', '#clearOrigin'):
        s = sesion(nav)
        p = s.abrir()
        base = poligonos(p)
        pend = Retenidas(s.ctx, '**/api/rutas/21')
        poner_puntos(p, 'multi')
        p.evaluate("document.querySelector('#planTrip').click()")
        esperar_resultados(p, 600)
        assert len(pend.pend) >= 1, 'la petición de la ruta quedó retenida'
        assert seleccion(p) is None and p.evaluate("document.querySelectorAll('.bus-wrapper').length") == 0
        p.click(boton); p.wait_for_timeout(300)
        pend.liberar()
        p.wait_for_timeout(1500)
        sin_rastro_del_recorrido(p, base, f'respuesta tardía tras {boton}')
        assert p.locator('#tripResults').inner_text().strip() == ''
        s.limpio('ruta retenida ' + boton)
        s.cerrar()


@prueba
def recalcular_los_mismos_puntos_invalida_ya_la_seleccion_la_barra_y_el_aviso(nav):
    s = sesion(nav, geolocation=GPS, permissions=['geolocation'])
    p = s.abrir()
    planear(p, 'multi')
    assert seleccion(p) == ['M01']
    activar_aviso(p)
    pend = Retenidas(s.ctx, '**/api/viajes/planificar', 'POST')
    p.evaluate("document.querySelector('#planTrip').click()")
    p.wait_for_timeout(600)
    assert len(pend.pend) == 1, 'el segundo cálculo está pendiente'
    assert seleccion(p) is None, 'mientras calcula no hay selección'
    assert p.locator('#tripTools').is_hidden(), 'ni barra del resultado anterior'
    assert not aviso_activo(p), 'ni aviso del viaje anterior'
    assert p.evaluate("document.querySelectorAll('.bus-wrapper').length") == 0 and p.locator('#tripResults').inner_text().strip() == ''
    pend.liberar()
    esperar_resultados(p, 1500)
    assert seleccion(p) == ['M01'] and p.locator('#tripTools').is_visible()
    s.limpio('recalcular')
    s.cerrar()


@prueba
def si_el_nuevo_calculo_falla_o_no_encuentra_rutas_no_reaparece_lo_anterior(nav):
    s = sesion(nav)
    p = s.abrir()
    planear(p, 'multi')
    assert seleccion(p) == ['M01']
    s.ctx.route('**/api/viajes/planificar', lambda r: r.fulfill(status=500, content_type='application/json', body=json.dumps({'status': 500, 'message': 'Fallo simulado del servidor.'})))
    p.evaluate("document.querySelector('#planTrip').click()")
    p.wait_for_timeout(1200)
    assert seleccion(p) is None and p.locator('#tripTools').is_hidden() and p.evaluate("document.querySelectorAll('.bus-wrapper').length") == 0
    s.cerrar()
    # sin rutas: un origen lejano de todo escenario
    s = sesion(nav)
    p = s.abrir()
    planear(p, 'multi')
    s.ctx.route('**/api/viajes/planificar', lambda r: r.fulfill(status=200, content_type='application/json',
                                                              body=json.dumps({'opciones': [], 'alternativas': [], 'transbordos': [], 'alcance': 'sin rutas'})))
    p.evaluate("document.querySelector('#planTrip').click()")
    p.wait_for_timeout(1800)
    assert p.locator('.planner-empty').count() == 1
    assert seleccion(p) is None and p.locator('#tripTools').is_hidden(), 'sin rutas no queda la selección anterior'
    s.cerrar()


# ---------------------------------------------------------------------------------------------- 4. historial
def historial(p):
    return p.evaluate("Spevb.Datos.leer('historial').map((h) => h.codigos)")


@prueba
def el_historial_usa_los_codigos_de_la_recomendacion_directa_y_no_los_del_transbordo(nav):
    s = sesion(nav)
    p = s.abrir()
    planear(p, 'mixto')
    assert seleccion(p) == ['M01'], seleccion(p)
    assert p.evaluate('window.__viajes') == [['M01']], 'un solo evento con la ruta realmente seleccionada'
    assert historial(p) == [['M01']], f'nunca R001 + R002: {historial(p)}'
    # abrir otra tarjeta (un transbordo) no duplica el historial
    abrir = p.locator('details.results-group').first
    if not abrir.evaluate('e => e.open'):
        abrir.locator(':scope > summary').click(); p.wait_for_timeout(200)
    abrir.locator('.trip-option').first.locator(':scope > summary').click(); p.wait_for_timeout(1500)
    assert p.evaluate('window.__viajes') == [['M01']] and historial(p) == [['M01']], 'abrir otra tarjeta no escribe historial'
    s.limpio('historial mixto')
    s.cerrar()


@prueba
def el_historial_con_solo_transbordo_o_solo_sentido_contrario(nav):
    s = sesion(nav)
    p = s.abrir()
    planear(p, 'transbordos')
    esperada = seleccion(p)
    assert esperada and len(esperada) == 2
    assert p.evaluate('window.__viajes') == [esperada] and historial(p) == [esperada], f'los dos buses elegidos: {historial(p)} {esperada}'
    s.limpio('solo transbordo')
    s.cerrar()
    s = sesion(nav)
    p = s.abrir()
    planear(p, 'contrarias')
    esperada = seleccion(p)
    assert esperada and len(esperada) == 1
    assert p.evaluate('window.__viajes') == [esperada] and historial(p) == [esperada], 'la ruta contraria seleccionada'
    s.limpio('solo contraria')
    s.cerrar()


@prueba
def sin_recomendacion_valida_no_se_guarda_historial(nav):
    s = sesion(nav)
    p = s.abrir()
    s.ctx.route('**/api/rutas/21', lambda r: r.abort())
    planear(p, 'multi', 1500)
    # 4.14: la recomendación se elige aunque el detalle de la ruta no llegue; lo registrado debe ser exactamente lo elegido (nunca otra ruta).
    esperada = seleccion(p)
    assert esperada, 'la recomendación queda elegida sin esperar al detalle'
    assert p.evaluate('window.__viajes') == [esperada] and historial(p) == [esperada], f'lo registrado coincide con lo elegido: {historial(p)} {esperada}'
    s.cerrar()


# ---------------------------------------------------------------------------------------------- 5. ?ruta= en la URL
@prueba
def la_url_solo_lleva_ruta_cuando_se_abre_directamente(nav):
    s = sesion(nav)
    p = s.abrir()
    # abierta desde la lista pública: sí escribe ?ruta=
    p.evaluate("document.querySelector('#routeList .route-option').click()")
    B.esperar(p, "!document.getElementById('routeCard').classList.contains('hidden')", 8000)
    rid = p.evaluate("document.querySelector('#routeList .route-option[aria-pressed=true]').dataset.id")
    assert p.evaluate('location.search') == '?ruta=' + rid
    # calcular un viaje: el dibujo interno no agrega ?ruta= y retira el obsoleto, sin tocar otros parámetros
    p.evaluate("history.replaceState(null, '', '?otro=1&ruta=' + " + json.dumps(rid) + ")")
    planear(p, 'multi')
    assert p.evaluate('location.search') == '?otro=1', p.evaluate('location.search')
    p.click('#clearTripPoints'); p.wait_for_timeout(400)
    assert p.evaluate('location.search') == '?otro=1', 'limpiar el viaje no deja ?ruta= obsoleto ni borra otros parámetros'
    s.limpio('url')
    s.cerrar()


@prueba
def los_enlaces_directos_de_ruta_y_de_viaje_compartido_siguen_funcionando(nav):
    s = sesion(nav)
    p = s.abrir('?ruta=1')
    B.esperar(p, "!document.getElementById('routeCard').classList.contains('hidden')", 10000)
    assert p.evaluate('location.search') == '?ruta=1', 'un enlace directo legítimo se conserva'
    s.limpio('enlace de ruta')
    s.cerrar()
    s = sesion(nav)
    p = s.abrir('?a=4.147000,-73.653000&b=4.145000,-73.624000')
    esperar_resultados(p, 2000)
    assert seleccion(p) == ['M01'], 'el viaje compartido calcula y selecciona'
    assert 'ruta=' not in p.evaluate('location.search')
    assert p.evaluate("Spevb.Planner.puntos().origen !== null && Spevb.Planner.puntos().destino !== null")
    s.limpio('viaje compartido')
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
                print('FALLA ', f.__name__, '|', str(e).splitlines()[0][:500] if str(e) else repr(e))
                traceback.print_exc(limit=3)
        nav.close()
    print(f'\n{len(PRUEBAS) if not filtro else "?"} pruebas de estados obsoletos y carreras; fallos: {len(fallos)} {fallos}')
    return 1 if fallos else 0


if __name__ == '__main__':
    sys.exit(correr(sys.argv[1] if len(sys.argv) > 1 else None))
