"""Pruebas de navegador de 4.10.2: «Mejores opciones», estabilidad del desplazamiento del panel en celular y aviso de «elegir en el mapa».

Uso: SEVB_E2E_URL=http://127.0.0.1:18080/ python e2e/resultados_e2e.py [filtro]      (lo normal es e2e/run_e2e.py)
Los escenarios los fabrica el stub según la latitud del origen (4.1470 = 4 normales + 3 contrarias; 4.1475 = solo normales; 4.1485 = solo
contrarias; 4.1433 = solo transbordos; 4.1490 = directas + transbordos). La prueba principal de desplazamiento es 384×848 con emulación táctil.
"""
import json
import os
import sys
import traceback

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import sevb_e2e as B  # noqa: E402
from playwright.sync_api import sync_playwright  # noqa: E402

PRUEBAS = []
DEST = '4.1450, -73.6240'
MOVIL = {'viewport': {'width': 384, 'height': 848}, 'is_mobile': True, 'has_touch': True, 'device_scale_factor': 2}
ESC = {'multi': '4.1470, -73.6530', 'normales': '4.1475, -73.6530', 'contrarias': '4.1485, -73.6530', 'transbordos': '4.1433, -73.6020', 'mixto': '4.1490, -73.6530'}


def prueba(f):
    PRUEBAS.append(f)
    return f


class Retenidas:
    def __init__(self, ctx, patron):
        self.pend = []
        ctx.route(patron, lambda route: self.pend.append(route))

    def liberar(self):
        for r in self.pend:
            try:
                r.continue_()
            except Exception:  # noqa: BLE001
                pass
        self.pend = []


def sesion(nav, **opts):
    s = B.Sesion(nav, **opts)
    s.datos = {}
    s.page.on('response', lambda r: s.datos.__setitem__('planificar', r.json()) if r.url.endswith('/api/viajes/planificar') and r.request.method == 'POST' else None)
    return s


def planear(p, escenario='multi', espera=3500):
    p.fill('#tripOrigin', ESC[escenario]); p.keyboard.press('Enter'); p.wait_for_timeout(700)
    p.fill('#tripDestination', DEST); p.keyboard.press('Enter'); p.wait_for_timeout(700)
    p.evaluate("document.querySelector('#planTrip').click()")
    B.esperar(p, "document.querySelectorAll('#tripResults .trip-option').length > 0", 12000)
    p.wait_for_timeout(espera)


def codigos(p, selector):
    return p.evaluate("(s) => [...document.querySelectorAll(s)].map(c => c.querySelector('.route-chip') ? c.querySelector('.route-chip').textContent : '')", selector)


def scroll_body(p):
    return p.evaluate("document.querySelector('.sheet-body').scrollTop")


def deslizar(p, distancia=-320):
    """Gesto táctil real de desplazamiento sobre el cuerpo del panel (negativo = hacia abajo)."""
    caja = p.locator('.sheet-body').bounding_box()
    alto = p.evaluate('window.innerHeight')
    y = caja['y'] + (min(caja['y'] + caja['height'], alto) - caja['y']) * 0.6          # zona realmente visible del cuerpo
    cdp = p.context.new_cdp_session(p)
    cdp.send('Input.synthesizeScrollGesture', {'x': caja['x'] + caja['width'] / 2, 'y': y, 'yDistance': distancia, 'gestureSourceType': 'touch', 'speed': 900})
    p.wait_for_timeout(500)


def abrir_lista(p):
    """«Ver otras opciones»: abre el panel a media altura con la lista de rutas (el flujo normal es ir directo a «Iniciar viaje»)."""
    p.click('#verOpciones')
    B.esperar(p, "document.getElementById('sheet').dataset.state === 'half'", 5000)
    p.wait_for_timeout(450)


def abrir_grupos(p):
    for g in p.locator('details.results-group').all():
        if not g.evaluate('e => e.open'):
            g.locator(':scope > summary').click()
            p.wait_for_timeout(150)


# ------------------------------------------------------------------------------------------------ 1. Mejores opciones
@prueba
def dos_recomendaciones_primero_compactas_y_sin_duplicados(nav):
    s = sesion(nav)
    p = s.abrir()
    planear(p, 'multi')
    data = s.datos['planificar']
    mejores = p.locator('.best-options .trip-option')
    assert mejores.count() == 2, 'hay exactamente dos recomendaciones'
    assert p.locator('.best-options h3').inner_text() == 'Mejores opciones'
    c = codigos(p, '.best-options .trip-option')
    assert c == [data['opciones'][0]['codigo'], data['alternativas'][0]['codigo']], f'orden del servidor: {c}'
    assert mejores.nth(0).get_attribute('data-recomendacion') == 'normal' and 'Mejor en el sentido de la ruta' in mejores.nth(0).inner_text()
    assert mejores.nth(1).get_attribute('data-recomendacion') == 'contraria' and 'Mejor en sentido contrario' in mejores.nth(1).inner_text()
    for i in (0, 1):
        assert mejores.nth(i).locator('.best-badge svg').count() == 1, 'ícono de dirección además del texto y el color'
    antes = p.evaluate("(() => { const b = document.querySelector('.best-options'), g = document.querySelector('details.results-group'); return !!(b.compareDocumentPosition(g) & Node.DOCUMENT_POSITION_FOLLOWING); })()")
    assert antes, 'las recomendaciones van antes de los grupos secundarios'
    assert not mejores.nth(0).evaluate('e => e.open') and not mejores.nth(1).evaluate('e => e.open'), 'comienzan compactas y cerradas'
    assert p.locator('#tripResults .trip-option[open]').count() == 0, 'ninguna tarjeta se abre sola'
    assert 'selected' in mejores.nth(0).get_attribute('class'), 'la recomendación inicial se dibuja y queda marcada'
    total = p.locator('#tripResults .trip-option').count()
    assert total == len(data['opciones']) + len(data['alternativas']) == 7, f'cada resultado existe una sola vez: {total}'
    todos = codigos(p, '#tripResults .trip-option')
    assert len(todos) == len(set(todos)), f'sin duplicados: {todos}'
    s.limpio('mejores opciones')
    s.cerrar()


@prueba
def si_caminar_directo_tarda_igual_o_menos_que_la_ruta_se_avisa_sin_ocultar_la_opcion(nav):
    s = sesion(nav)
    p = s.abrir()
    p.fill('#tripOrigin', '4.1475, -73.6530'); p.keyboard.press('Enter'); p.wait_for_timeout(700)
    p.fill('#tripDestination', '4.1475, -73.6523'); p.keyboard.press('Enter'); p.wait_for_timeout(700)
    p.evaluate("document.querySelector('#planTrip').click()")
    B.esperar(p, "document.querySelectorAll('#tripResults .trip-option').length > 0", 12000)
    p.wait_for_timeout(2500)
    assert p.locator('#tripResults .trip-option.walk-better').count() >= 1, 'la tarjeta avisa que caminar directo no tarda mas'
    t = p.locator('#tripResults .trip-option.walk-better .result-aviso-caminar').first.text_content()
    assert 'Caminar directamente' in t and 'min' in t, t
    assert p.locator('#tripResults .trip-option').count() >= 1, 'la opcion no se oculta'
    assert 'caminar directo' in p.inner_text('#startTripInfo'), 'el resumen de Iniciar viaje tambien lo dice'
    s.limpio('aviso caminar directo')
    s.cerrar()


@prueba
def grupos_secundarios_cerrados_con_su_cantidad_y_todas_las_rutas(nav):
    s = sesion(nav)
    p = s.abrir()
    planear(p, 'multi')
    data = s.datos['planificar']
    titulos = p.evaluate("[...document.querySelectorAll('details.results-group > summary strong')].map(e => e.textContent)")
    assert 'Otras opciones en este sentido · 3' in titulos and 'Otras opciones en sentido contrario · 2' in titulos, titulos
    assert all(not g.evaluate('e => e.open') for g in p.locator('details.results-group').all()), 'los grupos secundarios empiezan cerrados'
    normales = p.locator('details.results-group', has=p.locator('summary strong', has_text='este sentido'))
    contrarias = p.locator('details.results-group', has=p.locator('summary strong', has_text='sentido contrario'))
    normales.locator(':scope > summary').click(); contrarias.locator(':scope > summary').click(); p.wait_for_timeout(300)
    assert codigos(p, 'details.results-group:has(summary strong) .group-body > .trip-option')[:3] == [o['codigo'] for o in data['opciones'][1:]]
    assert normales.locator('.trip-option').count() == 3 and contrarias.locator('.trip-option').count() == 2
    assert [x for x in codigos(p, '#tripResults .trip-option')] and len(codigos(p, '#tripResults .trip-option')) == 7
    s.limpio('grupos')
    s.cerrar()


@prueba
def cada_tarjeta_dibuja_su_ruta_y_la_seleccion_inicial_es_determinista(nav):
    s = sesion(nav)
    pedidos = []
    p = s.page
    p.on('request', lambda r: pedidos.append(r.url) if r.url.endswith('/api/viajes/planificar') else None)
    p = s.abrir()
    # cuenta cuántas veces se DIBUJA una ruta (explorer.showRoute): la inicial debe dibujarse una sola vez
    p.evaluate("window.__draws = []; const o = SpevbExplorer.showRoute; SpevbExplorer.showRoute = (...a) => { window.__draws.push(a[0]); return o(...a); }; 0")     # «; 0»: evaluate llamaría a la función devuelta
    planear(p, 'multi')
    sel = p.evaluate("Spevb.Planner.seleccion.codigos")
    assert sel == ['M01'], f'la inicial es la mejor del sentido normal: {sel}'
    assert len([u for u in pedidos if u.endswith('/api/viajes/planificar')]) == 1, 'un solo cálculo'
    dibujos = p.evaluate('window.__draws')
    assert dibujos == [21], f'la ruta inicial se dibuja una sola vez: {dibujos}'
    segunda = p.locator('.best-options .trip-option').nth(1)
    segunda.locator(':scope > summary').click(); p.wait_for_timeout(1200)
    assert segunda.evaluate('e => e.open') and p.evaluate("Spevb.Planner.seleccion.codigos") == ['M05'], 'tocar la tarjeta abre su detalle y dibuja esa ruta'
    assert p.locator('#tripResults .trip-option[open]').count() == 1
    otra = p.locator('details.results-group').first
    otra.locator(':scope > summary').click(); p.wait_for_timeout(200)
    otra.locator('.trip-option').first.locator(':scope > summary').click(); p.wait_for_timeout(1200)
    assert p.evaluate("Spevb.Planner.seleccion.codigos")[0] in ('M02', 'M06') and p.locator('#tripResults .trip-option[open]').count() == 1
    s.limpio('selección')
    s.cerrar()


@prueba
def si_falta_un_sentido_no_se_fabrica_una_recomendacion(nav):
    for escenario, esperado, contrario in (('normales', 'normal', False), ('contrarias', 'contraria', True)):
        s = sesion(nav)
        p = s.abrir()
        planear(p, escenario)
        mejores = p.locator('.best-options .trip-option')
        assert mejores.count() == 1 and mejores.first.get_attribute('data-recomendacion') == esperado, f'{escenario}: una sola recomendación'
        titulos = p.evaluate("[...document.querySelectorAll('details.results-group > summary strong')].map(e => e.textContent)")
        assert titulos == (['Otras opciones en sentido contrario · 1'] if contrario else ['Otras opciones en este sentido · 1']), titulos
        assert 'selected' in mejores.first.get_attribute('class'), 'la inicial es la normal si existe y, si no, la contraria'
        s.limpio(escenario)
        s.cerrar()


@prueba
def transbordos_como_recomendacion_cuando_no_hay_directas_y_los_demas_siguen_disponibles(nav):
    s = sesion(nav)
    p = s.abrir()
    planear(p, 'transbordos', 5000)
    data = s.datos['planificar']
    assert len(data['transbordos']) == 3 and not data['opciones'] and not data['alternativas']
    assert p.locator('.best-options .transfer-option').count() == 1 and 'Mejor opción con transbordo' in p.locator('.best-options').inner_text()
    grupo = p.locator('details.results-group', has=p.locator('summary strong', has_text='transbordo'))
    assert grupo.locator('.transfer-option').count() == 2, 'los demás transbordos siguen disponibles'
    assert p.locator('#tripResults .transfer-option').count() == 3
    assert not p.locator('.best-options .trip-option').first.evaluate('e => e.open') and 'selected' in p.locator('.best-options .trip-option').first.get_attribute('class')
    # directas + transbordos: las directas son las recomendaciones y los transbordos conservan su grupo
    s2 = sesion(nav)
    q = s2.abrir()
    planear(q, 'mixto', 5000)
    assert q.locator('.best-options .trip-option').count() == 1 and q.locator('.best-options .transfer-option').count() == 0
    assert q.locator('details.results-group', has=q.locator('summary strong', has_text='transbordo')).locator('.transfer-option').count() == 2
    s.limpio('transbordos'); s2.limpio('mixto')
    s.cerrar(); s2.cerrar()


# ------------------------------------------------------------------------------------------------ 2. Desplazamiento en celular
@prueba
def en_384x848_la_lista_conserva_su_posicion_y_nada_la_devuelve_al_inicio(nav):
    s = sesion(nav, **MOVIL)
    p = s.abrir()
    planear(p, 'multi')
    assert p.evaluate("document.getElementById('sheet').dataset.state") == 'peek', 'tras buscar, el panel queda compacto (el protagonista es «Iniciar viaje»)'
    abrir_lista(p)                                               # la persona pide ver las demás opciones
    abrir_grupos(p)
    body = p.locator('.sheet-body')
    maximo = p.evaluate("(() => { const b = document.querySelector('.sheet-body'); return b.scrollHeight - b.clientHeight; })()")
    assert maximo > 150, f'hay contenido para desplazar: {maximo}'
    deslizar(p, -420)
    pos = scroll_body(p)
    assert pos > 100, f'el usuario puede bajar: {pos}'
    eventos = p.evaluate("(() => { window.__sc = 0; document.querySelector('.sheet-body').addEventListener('scroll', () => window.__sc++); return 0; })()")
    estado = p.evaluate("[document.getElementById('sheet').dataset.state, document.getElementById('sheet').style.transform]")
    p.wait_for_timeout(5200)
    assert abs(scroll_body(p) - pos) <= 1, f'la posición se mantiene 5 s: {pos} → {scroll_body(p)}'
    assert p.evaluate('window.__sc') == 0, 'no hay un segundo desplazamiento programado ni rebote'
    assert p.evaluate("[document.getElementById('sheet').dataset.state, document.getElementById('sheet').style.transform]") == estado, 'el panel no se movió'
    # resize y visualViewport.resize
    p.evaluate("window.dispatchEvent(new Event('resize'))"); p.wait_for_timeout(300)
    assert abs(scroll_body(p) - pos) <= 2, 'resize conserva la posición'
    p.evaluate("window.visualViewport && window.visualViewport.dispatchEvent(new Event('resize'))"); p.wait_for_timeout(500)
    assert abs(scroll_body(p) - pos) <= 2, 'visualViewport.resize conserva la posición'
    # abrir y cerrar tarjetas y grupos no devuelve la lista al inicio
    visible = p.evaluate("(() => { const b = document.querySelector('.sheet-body').getBoundingClientRect(); const c = [...document.querySelectorAll('#tripResults .trip-option > summary')].find(e => { const r = e.getBoundingClientRect(); return r.top > b.top + 20 && r.bottom < b.bottom - 20; }); return c ? (c.scrollIntoView === undefined ? null : [c.getBoundingClientRect().x + 30, c.getBoundingClientRect().y + 20]) : null; })()")
    assert visible, 'hay una tarjeta visible para tocar'
    p.touchscreen.tap(visible[0], visible[1]); p.wait_for_timeout(1500)
    assert scroll_body(p) > pos * 0.4 and scroll_body(p) > 50, f'abrir una tarjeta no devuelve la lista al inicio: {pos} → {scroll_body(p)}'
    antes_cerrar = scroll_body(p)
    p.touchscreen.tap(visible[0], visible[1]); p.wait_for_timeout(1000)
    assert scroll_body(p) > 50, f'cerrar una tarjeta no devuelve la lista al inicio ({antes_cerrar} → {scroll_body(p)})'
    deslizar(p, -300)
    despues = scroll_body(p)
    assert despues >= 0 and p.evaluate("document.querySelector('.sheet-body').scrollTop") == despues
    s.limpio('scroll 384x848')
    s.cerrar()


@prueba
def una_respuesta_asincrona_del_mapa_o_un_toque_cancelan_el_posicionamiento_automatico(nav):
    # a) el usuario toca MIENTRAS el mapa dibuja la ruta inicial: el posicionamiento automático NO se ejecuta después
    s = sesion(nav, **MOVIL)
    ret = Retenidas(s.ctx, '**/api/rutas/21')
    p = s.abrir()
    p.fill('#tripOrigin', ESC['multi']); p.keyboard.press('Enter'); p.wait_for_timeout(600)
    p.fill('#tripDestination', DEST); p.keyboard.press('Enter'); p.wait_for_timeout(600)
    p.evaluate("document.querySelector('#planTrip').click()")
    B.esperar(p, "document.querySelectorAll('#tripResults .trip-option').length > 0", 12000)
    p.wait_for_timeout(500)
    assert ret.pend, 'el dibujo de la ruta inicial quedó pendiente'
    caja = p.locator('.sheet-body').bounding_box()
    p.touchscreen.tap(caja['x'] + 20, caja['y'] + 30)            # toque manual sobre el cuerpo del panel
    p.wait_for_timeout(300)
    antes = scroll_body(p)
    ret.liberar()
    p.wait_for_timeout(2500)
    assert abs(scroll_body(p) - antes) <= 1, f'la respuesta asíncrona del mapa no cambia la posición: {antes} → {scroll_body(p)}'
    s.limpio('async')
    s.cerrar()
    # b) sin interacción, el posicionamiento ocurre UNA sola vez y rápido
    s = sesion(nav, **MOVIL)
    p = s.abrir()
    planear(p, 'multi', 1500)
    pos = scroll_body(p)
    assert pos > 20, f'sin interacción se muestra el comienzo de los resultados: {pos}'
    p.evaluate("window.__sc = 0; document.querySelector('.sheet-body').addEventListener('scroll', () => window.__sc++)")
    p.wait_for_timeout(3000)
    assert p.evaluate('window.__sc') == 0 and abs(scroll_body(p) - pos) <= 1, 'no se repite'
    s.limpio('auto una vez')
    s.cerrar()


@prueba
def arrastrar_el_cuerpo_no_mueve_el_panel_y_el_ocultamiento_automatico_se_cancela(nav):
    s = sesion(nav, **MOVIL)
    s.ctx.add_init_script("try { const a = JSON.parse(localStorage.getItem('spevb-ajustes') || '{}'); a.panelBajar = true; localStorage.setItem('spevb-ajustes', JSON.stringify(a)); } catch (e) {}")
    p = s.abrir()
    p.fill('#tripOrigin', ESC['multi']); p.keyboard.press('Enter'); p.wait_for_timeout(600)
    p.fill('#tripDestination', DEST); p.keyboard.press('Enter'); p.wait_for_timeout(600)
    p.evaluate("document.querySelector('#planTrip').click()")
    B.esperar(p, "document.querySelectorAll('#tripResults .trip-option').length > 0", 12000)
    p.wait_for_timeout(300)
    estado = p.evaluate("[document.getElementById('sheet').dataset.state, document.getElementById('sheet').style.transform]")
    abrir_lista(p)                                              # la persona empieza a revisar los resultados
    deslizar(p, -200)
    p.wait_for_timeout(2600)
    assert not p.evaluate("document.body.classList.contains('panel-oculto')"), 'tocar los resultados cancela el ocultamiento automático'
    assert p.evaluate("[document.getElementById('sheet').dataset.state, document.getElementById('sheet').style.transform]")[0] == 'half', 'arrastrar el cuerpo no cambió el estado del panel'
    s.limpio('panel automático')
    s.cerrar()
    # control: sin tocar nada, el panel SÍ baja solo (el temporizador existe)
    s = sesion(nav, **MOVIL)
    s.ctx.add_init_script("try { const a = JSON.parse(localStorage.getItem('spevb-ajustes') || '{}'); a.panelBajar = true; localStorage.setItem('spevb-ajustes', JSON.stringify(a)); } catch (e) {}")
    p = s.abrir()
    p.fill('#tripOrigin', ESC['multi']); p.keyboard.press('Enter'); p.wait_for_timeout(600)
    p.fill('#tripDestination', DEST); p.keyboard.press('Enter'); p.wait_for_timeout(600)
    p.evaluate("document.querySelector('#planTrip').click()")
    B.esperar(p, "document.body.classList.contains('panel-oculto')", 9000)
    s.limpio('control del temporizador')
    s.cerrar()


JS_CAMINATAS = """() => [...document.querySelectorAll('path.walk-line')].map((e) => {
    const cs = getComputedStyle(e);
    return { fallback: e.classList.contains('walk-fallback'), nombre: cs.animationName, dash: e.getAttribute('stroke-dasharray'), off: parseFloat(cs.strokeDashoffset) || 0,
             mover: cs.animationPlayState, d: (e.getAttribute('d') || '').length };
})"""


@prueba
def las_lineas_punteadas_de_caminata_avanzan_en_el_sentido_del_trayecto(nav):
    s = sesion(nav, **MOVIL)
    p = s.abrir()
    planear(p, 'multi', 2500)
    ls = [l for l in p.evaluate(JS_CAMINATAS) if not l['fallback']]
    assert ls, 'hay al menos una caminata por calles dibujada'
    for l in ls:
        assert 'walk-flow' in l['nombre'] and l['dash'] == '1 10', f'la caminata lleva la animación de avance: {l}'
    # el desplazamiento cambia con el tiempo y siempre hacia valores negativos (los puntos avanzan de origen a destino)
    muestras = []
    for _ in range(6):
        muestras.append([l['off'] for l in p.evaluate(JS_CAMINATAS) if not l['fallback']][0])
        p.wait_for_timeout(230)
    assert len(set(round(m, 2) for m in muestras)) >= 4, f'el desplazamiento de los puntos cambia con el tiempo: {muestras}'
    assert all(-11.01 <= m <= 0.01 for m in muestras), f'el patrón de 11 px avanza de 0 a -11: {muestras}'
    # al mover el mapa la animación se pausa (menos trabajo mientras se arrastra) y luego sigue
    p.evaluate("document.getElementById('map').classList.add('map-moving')")
    assert set(p.evaluate(JS_CAMINATAS)[0]['mover'].replace(' ', '').split(',')) == {'paused'}, 'mientras el mapa se mueve la caminata no se anima'
    p.evaluate("document.getElementById('map').classList.remove('map-moving')")
    p.wait_for_timeout(100)
    assert set(p.evaluate(JS_CAMINATAS)[0]['mover'].replace(' ', '').split(',')) == {'running'}
    s.limpio('caminatas animadas'); s.cerrar()
    # con movimiento reducido las líneas quedan quietas (siguen visibles)
    s = sesion(nav, reduced_motion='reduce', **MOVIL)
    p = s.abrir()
    planear(p, 'multi', 2500)
    ls = [l for l in p.evaluate(JS_CAMINATAS) if not l['fallback']]
    assert ls and all(l['nombre'] == 'none' for l in ls), f'con movimiento reducido no hay animación: {ls}'
    s.limpio('caminatas sin animación'); s.cerrar()


JS_TRAZOS = """() => { const r = { pesos: [], tips: document.querySelectorAll('.route-end-tip').length, paraderos: document.querySelectorAll('.paradero-marca').length, puentes: document.querySelectorAll('.puente-icono').length };
    Spevb.maps.map.elementos().forEach((e) => { if (e.tipo === 'linea' && e.peso) r.pesos.push(e.peso); }); return r; }"""


@prueba
def al_buscar_de_A_a_B_solo_se_dibuja_el_trayecto_y_el_explorador_muestra_la_ruta_entera(nav):
    s = sesion(nav, **MOVIL)
    p = s.abrir()
    planear(p, 'multi', 2500)
    t = p.evaluate(JS_TRAZOS)
    assert 8 in t['pesos'], f'se dibuja el tramo que se recorre (rojo, grueso): {t}'
    assert 6 not in t['pesos'], f'no queda dibujada la ruta entera (la línea larga que confundía): {t}'
    assert t['tips'] == 0, f'tampoco los rótulos «Inicio»/«Fin» de la ruta completa: {t}'
    s.limpio('solo el trayecto'); s.cerrar()
    # Explorador de rutas (sin A→B): la ruta se ve entera, con su inicio y su fin.
    s = sesion(nav)
    p = s.abrir()
    B.esperar(p, "document.querySelectorAll('#routeList > *').length > 0", 10000)
    p.evaluate("document.querySelector('#routeList .route-option').click()")
    B.esperar(p, "document.querySelectorAll('.route-end-tip').length >= 1", 8000)
    t = p.evaluate(JS_TRAZOS)
    assert 6 in t['pesos'] and t['tips'] >= 1, f'el explorador dibuja la ruta completa: {t}'
    s.limpio('ruta completa'); s.cerrar()


@prueba
def limpiar_A_y_B_es_un_boton_visible_junto_a_buscar_rutas_y_la_guia_de_colores_va_al_final(nav):
    for nombre, op in (('384x848', MOVIL), ('320x568', {'viewport': {'width': 320, 'height': 568}, 'is_mobile': True, 'has_touch': True}), ('escritorio', {})):
        s = sesion(nav, **op)
        p = s.abrir()
        r = p.evaluate("""() => { const b = document.getElementById('clearTripPoints'), r = b.getBoundingClientRect(), pl = document.getElementById('planTrip').getBoundingClientRect(),
            g = document.getElementById('colorGuide'), res = document.getElementById('tripResults');
            return { txt: b.textContent.trim(), h: r.height, w: r.width, mismaFila: Math.abs(r.top - pl.top) < 12, dentro: r.right <= innerWidth + 1 && r.left >= -1,
                     guiaAlFinal: !!g.closest('#sideMenu'), deshab: b.disabled, cerrada: !g.open }; }""")
        assert r['txt'] == 'Limpiar A y B' and r['h'] >= 44 and r['mismaFila'] and r['dentro'], f'{nombre}: botón visible, de 44 px y junto a «Buscar rutas»: {r}'
        assert r['guiaAlFinal'] and r['cerrada'], f'{nombre}: la guía de colores está en el menú lateral (4.14) y plegada: {r}'
        assert r['deshab'], f'{nombre}: sin nada que limpiar queda inactivo'
        p.fill('#tripOrigin', 'Calle 15'); p.wait_for_timeout(200)
        assert p.is_enabled('#clearTripPoints'), f'{nombre}: con texto en A se activa'
        p.click('#clearTripPoints'); p.wait_for_timeout(300)
        assert p.input_value('#tripOrigin') == '' and p.is_disabled('#clearTripPoints'), f'{nombre}: limpia A y B'
        s.limpio(nombre); s.cerrar()


@prueba
def se_ven_los_paraderos_del_tramo_y_el_cercano_a_A_con_su_aviso(nav):
    s = sesion(nav, **MOVIL)
    p = s.abrir()
    planear(p, 'multi', 3500)
    t = p.evaluate(JS_TRAZOS)
    assert t['paraderos'] >= 1, f'se dibuja al menos el paradero cercano al punto A o a B (≤ 150 m): {t}'
    hubo = p.evaluate("[...document.querySelectorAll('.paradero-marca-wrap')].every((e) => e.getBoundingClientRect().width > 0)")
    assert hubo, 'los paraderos tienen tamaño visible'
    estado = p.inner_text('#tripStatus')
    assert 'uta' in estado and 'paradero' in estado.lower(), f'el estado explica el trayecto y avisa del paradero cercano a A: {estado}'
    s.limpio('paraderos'); s.cerrar()


JS_FLUJO = """() => { const n = (sel, pseudo) => { const e = document.querySelector(sel); return e ? getComputedStyle(e, pseudo || null).animationName : null; };
    return { ir: n('#startTrip'), late: n('#startTrip', '::after'), tarjeta: n('#tripResults .trip-option'), conector: n('.endpoints', '::after'), letra: n('.endpoint-block.has-clear .endpoint-letter'),
             hoja: getComputedStyle(document.getElementById('sheet')).transitionTimingFunction, spring: getComputedStyle(document.documentElement).getPropertyValue('--motion-spring').trim() }; }"""


@prueba
def el_flujo_de_movimiento_anima_y_se_apaga_con_movimiento_reducido(nav):
    s = sesion(nav, **MOVIL)
    p = s.abrir()
    planear(p, 'multi', 1200)
    f = p.evaluate(JS_FLUJO)
    assert 'ir-entra' in f['ir'] and 'ir-late' in f['late'], f'«Iniciar viaje» entra y late: {f}'
    assert 'tarjeta-sube' in (f['tarjeta'] or ''), f'las tarjetas de resultados suben con rebote: {f}'
    assert 'letra-pop' in (f['letra'] or ''), f'las letras A/B hacen pop al marcar el punto: {f}'
    assert f['spring'] == '420ms', f
    s.limpio('flujo'); s.cerrar()
    s = sesion(nav, reduced_motion='reduce', **MOVIL)
    p = s.abrir()
    planear(p, 'multi', 600)
    f = p.evaluate(JS_FLUJO)
    for clave in ('ir', 'late', 'tarjeta', 'conector', 'letra'):
        assert f[clave] in (None, 'none'), f'con movimiento reducido no hay animación en {clave}: {f}'
    assert f['spring'] == '1ms', f
    assert p.evaluate('document.documentElement.scrollWidth - innerWidth') <= 1
    s.limpio('flujo reducido'); s.cerrar()


@prueba
def si_la_lista_de_rutas_llega_mal_buscar_de_A_a_B_sigue_funcionando(nav):
    """Un túnel (ngrok) o un service worker sin red pueden devolver 200 con una página en vez de la lista de rutas: antes `routes` quedaba en null y
    «Buscar rutas» fallaba con «null is not an object (evaluating 'routes.filter')» (visto en un iPhone)."""
    s = sesion(nav, **MOVIL)
    s.ctx.route('**/api/rutas', lambda r: r.fulfill(status=200, content_type='text/html', body='<html><body>aviso del tunel</body></html>'))
    p = s.abrir()
    ct = p.evaluate("fetch('/api/rutas').then((r) => r.headers.get('content-type'))")
    assert 'html' in ct, f'la prueba sí interceptó la lista de rutas: {ct}'
    assert p.evaluate('window.SpevbExplorer.map && true')
    planear(p, 'multi', 1500)
    assert p.locator('#tripResults .trip-option').count() > 0, 'las opciones de A a B aparecen aunque la lista general de rutas haya fallado'
    estado = p.inner_text('#tripStatus')
    assert 'filter' not in estado and 'null' not in estado and 'PostGIS' not in estado, f'sin error técnico en el estado: {estado}'      # Chrome dice «reading 'filter'», Safari «routes.filter»
    s.limpio('lista de rutas inválida'); s.cerrar()


@prueba
def movimiento_reducido_y_resoluciones_moviles(nav):
    s = sesion(nav, reduced_motion='reduce', **MOVIL)
    p = s.abrir()
    planear(p, 'multi')
    abrir_lista(p)
    abrir_grupos(p)
    deslizar(p, -400)
    pos = scroll_body(p)
    p.wait_for_timeout(2500)
    assert pos > 100 and abs(scroll_body(p) - pos) <= 1, 'con movimiento reducido la lista tampoco rebota'
    s.limpio('movimiento reducido')
    s.cerrar()
    tamanos = [('320x568', 320, 568, 1), ('360x640', 360, 640, 1), ('384x848', 384, 848, 1), ('390x844', 390, 844, 1), ('430x932', 430, 932, 1), ('tableta', 768, 1024, 0), ('escritorio', 1280, 800, 0)]
    for nombre, w, h, movil in tamanos:
        opts = {'viewport': {'width': w, 'height': h}, 'is_mobile': bool(movil or w == 768), 'has_touch': bool(movil or w == 768)}
        s = sesion(nav, **opts)
        p = s.abrir()
        planear(p, 'multi', 1500)
        assert p.evaluate('document.documentElement.scrollWidth - window.innerWidth') <= 1, f'{nombre}: desbordamiento horizontal'
        assert p.locator('.best-options .trip-option').count() == 2, nombre
        if movil:
            b = p.locator('#startTrip').bounding_box()
            assert b and b['y'] >= 0 and b['y'] + b['height'] <= h, f'{nombre}: «Iniciar viaje» se ve de inmediato tras buscar: {b}'
            abrir_lista(p)
        if movil and w >= 384:
            caja = p.locator('.sheet-body').bounding_box()
            c0, c1 = p.locator('.best-options .trip-option').nth(0).bounding_box(), p.locator('.best-options .trip-option').nth(1).bounding_box()
            assert c0['y'] >= caja['y'] - 2 and c1['y'] + c1['height'] <= caja['y'] + caja['height'] + 2, f'{nombre}: las dos recomendaciones se ven juntas en la primera pantalla: {c0} {c1} {caja}'
        s.limpio(nombre)
        s.cerrar()


# ------------------------------------------------------------------------------------------------ 3. Aviso «elegir en el mapa»
def solapan(a, b):
    return not (a['x'] + a['width'] <= b['x'] or b['x'] + b['width'] <= a['x'] or a['y'] + a['height'] <= b['y'] or b['y'] + b['height'] <= a['y'])


def caja(p, selector):
    loc = p.locator(selector)
    if loc.count() == 0 or not loc.first.is_visible():
        return None
    return loc.first.bounding_box()


@prueba
def el_aviso_de_elegir_punto_va_abajo_a_la_izquierda_sin_tapar_nada(nav):
    for nombre, w, h in (('320x568', 320, 568), ('360x640', 360, 640), ('384x848', 384, 848), ('390x844', 390, 844), ('430x932', 430, 932)):
        s = sesion(nav, viewport={'width': w, 'height': h}, is_mobile=True, has_touch=True, device_scale_factor=2)
        p = s.abrir()
        p.evaluate("Spevb.toast('Un error cualquiera', {tipo: 'error', ms: 0, clave: 'otro'})")
        p.click('#pickOrigin'); p.wait_for_timeout(700)
        t = caja(p, '#spevbMapToasts .map-action-toast')
        assert t, f'{nombre}: el aviso aparece'
        assert t['x'] <= 16 and t['y'] > h * 0.25, f'{nombre}: abajo a la izquierda: {t}'
        hoja = p.locator('#sheet').bounding_box()
        assert t['y'] + t['height'] <= hoja['y'] + 1, f'{nombre}: por encima del panel inferior (margen): {t} {hoja}'
        for sel, que in (('.map-fabs', 'los botones flotantes'), ('.leaflet-control-attribution', 'la atribución de Leaflet'), ('.center-pin', 'el pin central')):
            c = caja(p, sel)
            if c:
                assert not solapan(t, c), f'{nombre}: el aviso tapa {que}: {t} {c}'
        assert p.evaluate('document.documentElement.scrollWidth - window.innerWidth') <= 1
        otro = caja(p, '#spevbToasts .spevb-toast')
        assert otro and otro['y'] < 120, f'{nombre}: los demás avisos conservan su posición superior: {otro}'
        assert p.locator('#spevbMapToasts .spevb-toast.error, #spevbToasts .map-action-toast').count() == 0
        for b in p.locator('#spevbMapToasts button').all():
            bb = b.bounding_box()
            assert bb['width'] >= 43.5 and bb['height'] >= 43.5, f'{nombre}: área táctil {bb}'
        s.limpio(nombre)
        s.cerrar()


@prueba
def el_aviso_queda_encima_de_los_controles_del_bus(nav):
    s = sesion(nav, **MOVIL)
    p = s.abrir()
    planear(p, 'multi', 2000)
    p.evaluate("Spevb.Ajustes && Spevb.Ajustes.set('busMin', false)"); p.wait_for_timeout(400)
    assert caja(p, '.bus-panel'), 'los controles del bus están visibles'
    p.click('#pickDestination'); p.wait_for_timeout(900)
    bus = caja(p, '.bus-panel')                                 # el panel baja al elegir un punto: se mide después
    t = caja(p, '#spevbMapToasts .map-action-toast')
    assert t and not solapan(t, bus), f'el aviso no tapa los controles del bus: {t} {bus}'
    assert t['y'] + t['height'] <= bus['y'] + 1, f'justo encima de «Controles»: {t} {bus}'
    c = caja(p, '.center-pin')
    if c:
        assert not solapan(t, c), f'ni el pin central: {t} {c}'
    # con «Controles» colapsado el aviso también queda encima
    p.evaluate("Spevb.Ajustes.set('busMin', true)"); p.wait_for_timeout(600)
    bus2 = caja(p, '.bus-panel'); t2 = caja(p, '#spevbMapToasts .map-action-toast')
    assert t2 and (not bus2 or not solapan(t2, bus2)), f'colapsado: {t2} {bus2}'
    s.limpio('encima de controles')
    s.cerrar()


@prueba
def confirmar_y_cancelar_se_comportan_y_el_aviso_no_deja_rastro(nav):
    s = sesion(nav, **MOVIL)
    p = s.abrir()
    # confirmar el origen
    sheet_antes = p.evaluate("document.getElementById('sheet').dataset.state")
    p.click('#pickOrigin'); p.wait_for_timeout(600)
    assert p.get_attribute('#pickOrigin', 'aria-pressed') == 'true' and p.locator('.center-pin').is_visible()
    botones = p.locator('#spevbMapToasts button')
    assert botones.count() == 2 and botones.nth(0).get_attribute('aria-label') and botones.nth(1).get_attribute('aria-label'), 'botones con nombre accesible'
    assert 'Confirmar' in botones.nth(0).inner_text() and 'Cancelar' in botones.nth(1).inner_text()
    assert p.locator('#spevbMapToasts .map-action-toast').get_attribute('role') == 'status'
    # tocar el mapa para moverlo NO cierra el aviso
    caja_mapa = p.locator('#map').bounding_box()
    p.mouse.move(caja_mapa['x'] + 120, caja_mapa['y'] + 200); p.mouse.down(); p.mouse.move(caja_mapa['x'] + 160, caja_mapa['y'] + 230, steps=6); p.mouse.up(); p.wait_for_timeout(300)
    assert p.locator('#spevbMapToasts .map-action-toast').count() == 1, 'mover el mapa no cierra el aviso'
    botones.nth(0).click(); p.wait_for_timeout(1200)
    assert B.esperar(p, "Spevb.Planner.puntos().origen !== null", 6000) is None
    assert p.locator('#spevbMapToasts .spevb-toast').count() == 0 and p.locator('.center-pin').is_hidden(), 'se cierra el aviso y desaparece el pin central'
    assert p.get_attribute('#pickOrigin', 'aria-pressed') == 'false'
    origen = p.evaluate("Spevb.Planner.puntos().origen")
    # cancelar la elección del destino: no cambia nada
    p.click('#pickDestination'); p.wait_for_timeout(500)
    p.locator('#spevbMapToasts button', has_text='Cancelar').click(); p.wait_for_timeout(500)
    assert p.evaluate("Spevb.Planner.puntos().destino") is None and p.evaluate("Spevb.Planner.puntos().origen") == origen
    assert p.locator('#spevbMapToasts .spevb-toast').count() == 0 and p.locator('.center-pin').is_hidden()
    assert p.get_attribute('#pickDestination', 'aria-pressed') == 'false'
    # cancelar con un punto previo: se conserva
    p.click('#pickOrigin'); p.wait_for_timeout(400)
    p.locator('#spevbMapToasts button', has_text='Cancelar').click(); p.wait_for_timeout(400)
    assert p.evaluate("Spevb.Planner.puntos().origen") == origen, 'cancelar no modifica el punto anterior'
    # no quedan clases de ubicación antiguas al reutilizar la clave
    p.evaluate("Spevb.toast('Mensaje común', {tipo: 'success', ms: 0, clave: 'pick'})")
    el = p.locator('#spevbToasts .spevb-toast')
    assert el.count() == 1 and 'map-action-toast' not in el.first.get_attribute('class') and p.locator('#spevbMapToasts .spevb-toast').count() == 0, 'la ubicación se actualiza al reutilizar la clave'
    p.evaluate("Spevb.toast.cerrar('pick')")
    assert p.locator('.spevb-toast').count() == 0, 'no queda ningún aviso vacío'
    s.limpio('confirmar/cancelar')
    s.cerrar()


@prueba
def teclado_escape_y_escritorio(nav):
    s = sesion(nav, **MOVIL)
    p = s.abrir()
    p.click('#pickDestination'); p.wait_for_timeout(500)
    p.locator('#spevbMapToasts button').first.focus()
    p.keyboard.press('Tab')
    assert p.evaluate("document.activeElement.textContent.trim()") == 'Cancelar', 'orden de tabulación lógico: Confirmar → Cancelar'
    p.keyboard.press('Shift+Tab'); p.keyboard.press('Enter'); p.wait_for_timeout(1200)       # Enter sobre «Confirmar»
    B.esperar(p, "Spevb.Planner.puntos().destino !== null", 6000)
    p.click('#pickOrigin'); p.wait_for_timeout(400)
    p.keyboard.press('Escape'); p.wait_for_timeout(400)
    assert p.get_attribute('#pickOrigin', 'aria-pressed') == 'false' and p.locator('#spevbMapToasts .spevb-toast').count() == 0, 'Escape cancela la selección'
    p.click('#pickOrigin'); p.wait_for_timeout(400)
    p.locator('#spevbMapToasts button').first.focus(); p.keyboard.press('Space'); p.wait_for_timeout(1200)
    B.esperar(p, "Spevb.Planner.puntos().origen !== null", 6000)
    s.limpio('teclado')
    s.cerrar()
    # escritorio: el aviso conserva su posición superior y no tapa el planificador
    s = sesion(nav, viewport={'width': 1280, 'height': 800})
    p = s.abrir()
    p.click('#pickOrigin'); p.wait_for_timeout(500)
    t = caja(p, '#spevbMapToasts .map-action-toast'); plan = p.locator('#sheet').bounding_box()
    assert t and t['y'] < 80, f'escritorio: arriba: {t}'
    s.limpio('escritorio')
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
                print('FALLA ', f.__name__, '|', str(e).splitlines()[0][:400] if str(e) else repr(e))
                traceback.print_exc(limit=3)
        nav.close()
    print(f'\n{len(PRUEBAS) if not filtro else "?"} pruebas de resultados y aviso; fallos: {len(fallos)} {fallos}')
    return 1 if fallos else 0


if __name__ == '__main__':
    sys.exit(correr(sys.argv[1] if len(sys.argv) > 1 else None))
