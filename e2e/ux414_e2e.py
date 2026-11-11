"""Pruebas de comportamiento de la experiencia MÓVIL 4.14 con un navegador real (Playwright) sobre el stub: coordinación de superficies, menú durante el viaje,
tarjeta del viaje en pantallas pequeñas, «Volver al inicio», búsqueda SOLO Google y confirmación fiable de A/B.

Qué es y qué NO es: el frontend es el real; el backend es un stub (e2e/stub_server.py) y Google se simula con rutas de Playwright (sin Internet ni claves); el GPS
se simula con e2e/simulador_gps.js; los viewports son emulaciones de Chromium/Edge. NO es una prueba en un iPhone ni en un Android físicos.
Uso: SEVB_E2E_URL=http://127.0.0.1:18080/ python e2e/ux414_e2e.py [filtro]     (lo normal es e2e/run_e2e.py)
"""
import json
import os
import sys
import traceback
from urllib.parse import parse_qs, urlparse

if hasattr(sys.stdout, 'reconfigure'):
    sys.stdout.reconfigure(encoding='utf-8', errors='replace')

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import sevb_e2e as B  # noqa: E402
import viaje_e2e as V  # noqa: E402
from playwright.sync_api import sync_playwright  # noqa: E402

PRUEBAS = []
MOVIL = V.MOVIL
TAMANOS = [('320x568', 320, 568), ('360x640', 360, 640), ('375x667', 375, 667), ('390x844', 390, 844), ('430x932', 430, 932),
           ('tableta 600x960', 600, 960), ('tableta 768x1024', 768, 1024), ('apaisado 844x390', 844, 390)]


def prueba(f):
    PRUEBAS.append(f)
    return f


def movil(nav, ancho=390, alto=844, **extra):
    s = V.sesion(nav, viewport={'width': ancho, 'height': alto}, is_mobile=True, has_touch=True, device_scale_factor=2, **extra)
    return s


class GoogleSim:
    """Simula la API propia (que a su vez habla con Google). Permite retener respuestas y liberarlas después, fallar y responder vacío."""

    def __init__(self, ctx):
        self.ctx = ctx
        self.sug_pedidos, self.det_pedidos, self.retenidas, self.todos = [], [], [], []
        self.modo_sug = 'ok'          # ok | vacio | error | config | sin_config | retener
        self.modo_det = 'ok'          # ok | vacio | error | retener
        ctx.on('request', lambda r: self.todos.append(r.url))
        ctx.route('**/api/lugares/sugerencias*', self._sug)
        ctx.route(lambda u: urlparse(u).path == '/api/lugares', self._det)

    @staticmethod
    def _json(route, cuerpo):
        route.fulfill(status=200, content_type='application/json', body=json.dumps(cuerpo))

    def _sug(self, route):
        q = parse_qs(urlparse(route.request.url).query)
        self.sug_pedidos.append({'q': q.get('q', [''])[0], 'sesion': q.get('sesion', [''])[0]})
        if self.modo_sug == 'retener':
            self.retenidas.append(('sug', route, q.get('q', [''])[0])); return
        self._responder_sug(route, q.get('q', [''])[0])

    def _responder_sug(self, route, texto):
        estado = {'ok': 'OK', 'vacio': 'SIN_COINCIDENCIAS', 'error': 'RED', 'config': 'CLAVE', 'sin_config': 'CLAVE'}.get(self.modo_sug, 'OK')
        items = []
        if estado == 'OK':
            for i, (n, d) in enumerate([(texto + ' · Centro Comercial', 'Villavicencio, Meta'), (texto + ' · Otra opción', 'Villavicencio')]):
                items.append({'texto': n, 'detalle': d, 'magicKey': 'g:ChIJ%s%02d' % (abs(hash(texto)) % 10**8, i), 'lat': None, 'lng': None, 'id': None, 'fuente': 'Google Maps', 'precision': None})
        self._json(route, {'estado': estado, 'proveedor': 'google', 'sugerencias': items})

    def _det(self, route):
        q = parse_qs(urlparse(route.request.url).query)
        self.det_pedidos.append({'q': q.get('q', [''])[0], 'magicKey': q.get('magicKey', [''])[0], 'sesion': q.get('sesion', [''])[0]})
        if self.modo_det == 'retener':
            self.retenidas.append(('det', route, q.get('q', [''])[0])); return
        self._responder_det(route, q.get('q', [''])[0], q.get('magicKey', [''])[0])

    def _responder_det(self, route, texto, magic):
        if self.modo_det == 'error':
            return self._json(route, {'lugares': [], 'avisos': ['Google no respondió.'], 'estado': 'RED', 'proveedor': 'google', 'eleccionFallida': True})
        if self.modo_det == 'vacio':
            return self._json(route, {'lugares': [], 'avisos': [], 'estado': 'SIN_COINCIDENCIAS', 'proveedor': 'google', 'eleccionFallida': True})
        lat, lng = (4.1450, -73.6540) if 'Origen' in texto or 'origen' in texto else (4.1450, -73.6240)
        if 'Segundo' in texto: lat, lng = 4.1500, -73.6300
        self._json(route, {'lugares': [{'id': None, 'nombre': texto, 'direccion': texto + ', Villavicencio', 'lat': lat, 'lng': lng, 'fuente': 'Google Maps', 'confirmado': False, 'precision': 'LUGAR'}],
                           'avisos': [], 'estado': 'OK', 'proveedor': 'google'})

    def liberar(self, indice=0, tipo=None):
        cand = [r for r in self.retenidas if tipo in (None, r[0])]
        t, route, q = cand[indice]
        self.retenidas.remove((t, route, q))
        self.modo_sug_prev, self.modo_det_prev = self.modo_sug, self.modo_det
        if t == 'sug':
            m, self.modo_sug = self.modo_sug, 'ok'; self._responder_sug(route, q); self.modo_sug = m
        else:
            m, self.modo_det = self.modo_det, 'ok'
            magic = parse_qs(urlparse(route.request.url).query).get('magicKey', [''])[0]
            self._responder_det(route, q, magic); self.modo_det = m

    def externos_prohibidos(self):
        malos = ('nominatim', 'geocode', 'geocoding', 'arcgis.com/arcgis/rest/services/world', 'findaddresscandidates', 'photon', 'komoot', 'mapbox.com/geocoding')
        return [u for u in self.todos if any(m in u.lower() for m in malos)]


def escribir(p, campo, texto):
    p.click('#' + campo)
    p.fill('#' + campo, texto)


def esperar_opciones(p, lista, minimo=1, ms=6000):
    B.esperar(p, f"document.querySelectorAll('#{lista} .autosuggest-option').length >= {minimo}", ms)


def puntos(p):
    return p.evaluate("(() => { const q = Spevb.Planner.puntos(); return { A: q.origen && [q.origen.lat, q.origen.lng, q.origen.nombre], B: q.destino && [q.destino.lat, q.destino.lng, q.destino.nombre] }; })()")


def estados(p):
    return p.evaluate('Spevb.Planner.estados()')


def sup(p):
    return p.evaluate('Spevb.Superficies.estado()')


# ============================================================================================================================ A · PRIORIDAD DE MENÚS
@prueba
def menu_desde_planificacion_cede_el_planificador_y_devuelve_el_foco(nav):
    s = movil(nav); p = s.abrir()
    p.click('#menuButton'); p.wait_for_timeout(400)
    e = sup(p)
    assert e['overlay'] == 'menu' and e['principal'] == 'planificador', e
    assert p.evaluate("document.querySelector('.app').inert === true"), 'el planificador y el mapa no reciben foco ni toques con el menú abierto (inert)'
    assert p.evaluate("document.getElementById('sideMenu').inert === false")
    assert p.evaluate("document.body.classList.contains('menu-open')")
    p.click('#closeMenu'); p.wait_for_timeout(400)
    e = sup(p)
    assert e['overlay'] is None and not p.evaluate("document.body.classList.contains('menu-open')"), e
    assert p.evaluate("document.getElementById('sheet').inert === false")
    assert p.evaluate("document.activeElement.id") == 'menuButton', 'el foco vuelve al botón que abrió el menú (sin abrir teclado)'
    s.limpio('menu desde planificacion'); s.cerrar()


@prueba
def el_menu_se_usa_durante_el_viaje_se_desplaza_y_el_viaje_sigue(nav):
    s = movil(nav, 360, 640); p = s.abrir()
    V.planear(p); V.destino_lejos(p)
    ls = V.lecturas(p, ruido={'sigma': 6, 'semilla': 3})
    V.iniciar(p)
    V.alimentar(p, ls, 0, 'fase:esperando')
    fase0 = V.card(p)['fase']
    assert sup(p)['principal'] == 'viaje' and p.evaluate("document.body.classList.contains('panel-oculto')"), 'con el viaje, la tarjeta es la principal y el planificador cede'
    assert p.is_visible('#menuFlotante'), 'sin cabecera visible, el menú sigue accesible con su botón flotante'
    p.click('#menuFlotante'); p.wait_for_timeout(450)
    e = sup(p)
    assert e['overlay'] == 'menu' and e['viaje'] is True, e
    assert p.evaluate("getComputedStyle(document.getElementById('viajeCard')).visibility") == 'hidden', 'la tarjeta cede el espacio al menú'
    # el menú recibe el toque: lo que hay en su centro es el menú, no la tarjeta ni una capa transparente
    arriba = p.evaluate("(() => { const m = document.getElementById('sideMenu').getBoundingClientRect(); const el = document.elementFromPoint(m.left + m.width / 2, m.top + m.height * 0.8); return !!el && !!el.closest('#sideMenu'); })()")
    assert arriba, 'el centro del menú recibe los toques'
    antes = p.evaluate("document.getElementById('sideMenu').scrollTop")
    p.mouse.move(180, 400); p.mouse.wheel(0, 500); p.wait_for_timeout(300)
    despues = p.evaluate("document.getElementById('sideMenu').scrollTop")
    assert despues > antes, f'el contenido del menú se desplaza ({antes} → {despues})'
    c = p.evaluate("(() => { const m = document.getElementById('sideMenu'); return [m.scrollHeight > m.clientHeight, getComputedStyle(m).overflowY, getComputedStyle(m).touchAction]; })()")
    assert c[0] and c[1] in ('auto', 'scroll') and 'pan-y' in c[2], c
    # botones del menú responden
    assert p.evaluate('window.__geo.escuchas') == 1 and p.evaluate('Spevb.Viaje.activo()'), 'abrir el menú no detiene ni duplica el GPS'
    p.click('#closeMenu'); p.wait_for_timeout(450)
    assert V.card(p)['fase'] == fase0, 'la tarjeta vuelve con el estado actual, sin reiniciar la fase'
    assert p.evaluate("getComputedStyle(document.getElementById('viajeCard')).visibility") == 'visible'
    assert p.evaluate("document.getElementById('sideMenu').scrollTop") >= 0 and p.evaluate('window.__geo.escuchas') == 1
    s.limpio('menu en viaje'); s.cerrar()


@prueba
def ajustes_de_cuenta_cede_el_menu_y_al_cerrar_devuelve_la_pantalla_a_su_estado(nav):
    s = movil(nav); p = s.abrir()
    p.click('#menuButton'); p.wait_for_timeout(400)
    p.click('#accountLogin'); p.wait_for_timeout(500)
    e = sup(p)
    assert e['overlay'] == 'modal' and e['modal'] == 'authModal' and not e['menu'], e
    assert p.evaluate("document.body.classList.contains('modal-open')") and not p.evaluate("document.body.classList.contains('menu-open')")
    p.click('#authCancel'); p.wait_for_timeout(500)
    e = sup(p)
    assert e['overlay'] is None and e['modal'] is None and not p.evaluate("document.querySelector('.app').inert"), f'al cerrar el diálogo el fondo vuelve a la vida (contrato 4.13.5; el menú no se reabre solo): {e}'
    assert not p.evaluate("document.body.classList.contains('modal-open')")
    s.limpio('ajustes y regresar'); s.cerrar()


@prueba
def ocultar_la_tarjeta_se_respeta_y_no_se_reabre_al_cerrar_el_menu(nav):
    s = movil(nav); p = s.abrir()
    V.planear(p); V.destino_lejos(p); V.iniciar(p)
    p.click('#viajeOcultar'); p.wait_for_timeout(300)
    assert p.is_visible('#viajePastilla') and p.is_hidden('#viajeCard')
    p.click('#menuFlotante'); p.wait_for_timeout(450)
    assert p.evaluate("getComputedStyle(document.getElementById('viajePastilla')).visibility") == 'hidden'
    p.click('#closeMenu'); p.wait_for_timeout(450)
    assert p.is_visible('#viajePastilla') and p.is_hidden('#viajeCard'), 'la tarjeta que la persona ocultó NO se reabre sola'
    p.click('#viajePastilla'); p.wait_for_timeout(300)
    assert p.is_visible('#viajeCard')
    s.limpio('ocultar y recuperar'); s.cerrar()


@prueba
def los_resultados_que_llegan_con_el_menu_abierto_no_reorganizan_la_pantalla(nav):
    s = movil(nav); p = s.abrir()
    p.fill('#tripOrigin', B.A); p.keyboard.press('Enter'); p.wait_for_timeout(700)
    p.fill('#tripDestination', B.B); p.keyboard.press('Enter'); p.wait_for_timeout(700)
    estado0 = p.evaluate("document.getElementById('sheet').dataset.state")
    p.click('#menuButton'); p.wait_for_timeout(400)
    p.evaluate("Spevb.Planner.buscar()")
    B.esperar(p, "document.querySelectorAll('#tripResults .trip-option').length > 0", 12000)
    p.wait_for_timeout(500)
    assert p.evaluate("document.getElementById('sheet').dataset.state") == estado0, 'el panel no cambió de altura por una respuesta con el menú abierto'
    assert sup(p)['overlay'] == 'menu'
    reg = p.evaluate('Spevb.Superficies.registro().map(r => r.causa)')
    assert any(c == 'rechazada:resultados' for c in reg), reg
    s.limpio('resultados con menu'); s.cerrar()


@prueba
def un_aviso_importante_se_ve_aunque_el_menu_este_abierto_y_es_una_sola_pregunta(nav):
    s = movil(nav, 360, 640); p = s.abrir()
    V.planear(p); V.destino_lejos(p)
    ls = V.lecturas(p, ruido={'sigma': 6, 'semilla': 3})
    V.iniciar(p)
    V.alimentar(p, ls, 0, 'fase:esperando')
    p.click('#menuFlotante'); p.wait_for_timeout(450)
    V.alimentar(p, ls, 0, 'pregunta')
    p.wait_for_timeout(300)
    d = p.evaluate("""() => { const c = document.getElementById('viajeCard'); const r = c.getBoundingClientRect(); const cs = getComputedStyle(c);
        const q = document.getElementById('viajePregunta'); const visibles = [...c.querySelectorAll('.viaje-pregunta, .viaje-aviso')].filter(e => e.offsetParent !== null).length;
        const el = document.elementFromPoint(r.left + r.width / 2, r.top + 20);
        return { urgente: c.dataset.urgente, visibilidad: cs.visibility, top: r.top, bottom: r.bottom, ancho: r.width, preguntaVisible: q.offsetParent !== null, visibles, recibe: !!el && !!el.closest('#viajeCard') }; }""")
    assert d['urgente'] == '1' and d['visibilidad'] == 'visible' and d['preguntaVisible'] and d['recibe'], d
    assert d['top'] >= 0 and d['bottom'] <= 640 and d['visibles'] == 1, f'una sola pregunta vigente, dentro de la pantalla: {d}'
    p.click('#viajeSi'); p.wait_for_timeout(300)
    assert V.card(p)['fase'] == 'en_bus' and sup(p)['overlay'] == 'menu', 'responder desde el aviso no cierra el menú ni reinicia el viaje'
    assert p.evaluate("document.getElementById('viajeCard').dataset.urgente") is None, 'la pregunta contestada se retira'
    s.limpio('aviso con menu'); s.cerrar()


@prueba
def repetir_aperturas_y_cierres_no_deja_bloqueos_ni_estados_pegados(nav):
    s = movil(nav); p = s.abrir()
    V.planear(p); V.destino_lejos(p); V.iniciar(p)
    for _ in range(8):
        p.click('#menuFlotante'); p.wait_for_timeout(120)
        p.keyboard.press('Escape'); p.wait_for_timeout(120)
    e = sup(p)
    assert e['overlay'] is None and e['viaje'] is True, e
    assert p.evaluate("[document.body.classList.contains('menu-open'), document.body.classList.contains('sup-overlay'), document.getElementById('sheet').inert, document.getElementById('sideMenu').inert]") == [False, False, False, True] or \
        p.evaluate("document.body.classList.contains('panel-oculto')"), 'sin overlay no queda ni scroll bloqueado ni inert de más'
    assert not p.evaluate("document.body.classList.contains('menu-open') || document.body.classList.contains('modal-open')"), 'el bloqueo de scroll se libera siempre'
    assert p.evaluate('window.__geo.escuchas') == 1
    assert len(p.evaluate('Spevb.Superficies.registro()')) <= 80
    s.limpio('repetir'); s.cerrar()


# ============================================================================================================================ B · GESTOS
@prueba
def cancelar_el_arrastre_del_asa_no_mueve_el_panel_con_inercia(nav):
    s = movil(nav); p = s.abrir()
    antes = p.evaluate("document.getElementById('sheet').dataset.state")
    transf = p.evaluate("document.getElementById('sheet').style.transform")
    r = p.evaluate("(() => { const b = document.getElementById('sheetHandle').getBoundingClientRect(); return [b.left + b.width / 2, b.top + b.height / 2]; })()")
    p.evaluate("""([x, y]) => { const h = document.getElementById('sheetHandle'); const o = { bubbles: true, cancelable: true, pointerId: 7, pointerType: 'touch', isPrimary: true, clientX: x };
        h.dispatchEvent(new PointerEvent('pointerdown', { ...o, clientY: y }));
        window.dispatchEvent(new PointerEvent('pointermove', { ...o, clientY: y - 300 }));
        window.dispatchEvent(new PointerEvent('pointermove', { ...o, clientY: y - 420 }));
        window.dispatchEvent(new PointerEvent('pointercancel', { ...o, clientY: y - 420 })); }""", r)
    p.wait_for_timeout(500)
    assert p.evaluate("document.getElementById('sheet').dataset.state") == antes, 'cancelar el gesto devuelve la altura de antes'
    assert p.evaluate("document.getElementById('sheet').style.transform") == transf
    s.limpio('cancelar gesto'); s.cerrar()


@prueba
def otro_dedo_no_mueve_el_panel_mientras_se_arrastra_el_asa(nav):
    s = movil(nav); p = s.abrir()
    r = p.evaluate("(() => { const b = document.getElementById('sheetHandle').getBoundingClientRect(); return [b.left + b.width / 2, b.top + b.height / 2]; })()")
    t0 = p.evaluate("document.getElementById('sheet').style.transform")
    p.evaluate("""([x, y]) => { const h = document.getElementById('sheetHandle');
        h.dispatchEvent(new PointerEvent('pointerdown', { bubbles: true, pointerId: 1, pointerType: 'touch', isPrimary: true, clientX: x, clientY: y }));
        window.dispatchEvent(new PointerEvent('pointermove', { bubbles: true, pointerId: 2, pointerType: 'touch', isPrimary: false, clientX: x, clientY: y - 250 })); }""", r)
    assert p.evaluate("document.getElementById('sheet').style.transform") == t0, 'un puntero ajeno no arrastra el panel'
    p.evaluate("window.dispatchEvent(new PointerEvent('pointercancel', { bubbles: true, pointerId: 1, pointerType: 'touch' }))")
    s.limpio('otro dedo'); s.cerrar()


# ============================================================================================================================ C · A/B CON GOOGLE
@prueba
def seleccionar_A_y_B_tocando_las_sugerencias_los_confirma_con_su_marcador(nav):
    s = movil(nav); g = GoogleSim(s.ctx); p = s.abrir()
    escribir(p, 'tripOrigin', 'Origen Unicentro'); esperar_opciones(p, 'originResults', 2)
    p.locator('#originResults .autosuggest-option').first.tap()
    B.esperar(p, "!!Spevb.Planner.puntos().origen", 5000)
    escribir(p, 'tripDestination', 'Destino Plaza'); esperar_opciones(p, 'destinationResults', 2)
    p.locator('#destinationResults .autosuggest-option').first.tap()
    B.esperar(p, "!!Spevb.Planner.puntos().destino", 5000)
    pt = puntos(p)
    assert pt['A'][2].startswith('Origen Unicentro') and pt['B'][2].startswith('Destino Plaza'), pt
    assert estados(p)['origen'] == 'confirmado' and estados(p)['destino'] == 'confirmado'
    assert p.locator('.trip-pin.a').count() == 1 and p.locator('.trip-pin.b').count() == 1, 'los marcadores coinciden con A y B'
    assert p.locator('#originResults > *').count() == 0 and p.locator('#destinationResults > *').count() == 0, 'las listas se cierran y vacían'
    assert p.is_enabled('#searchOrigin') and p.is_enabled('#searchDestination') and p.evaluate("Spevb.Planner.estados().indicador") == {'origin': 0, 'destination': 0}
    assert p.evaluate("document.activeElement.tagName") != 'INPUT', 'el teclado se cierra al confirmar'
    assert p.evaluate("document.getElementById('sheet').dataset.state") != 'full', 'seleccionar A/B no maximiza el planificador'
    ses = {d['sesion'] for d in g.det_pedidos}
    assert all(d['magicKey'].startswith('g:') and d['sesion'] for d in g.det_pedidos), g.det_pedidos
    assert len(ses) == 2, 'cada campo usa su propia sesión de autocompletado y detalle'
    for d in g.det_pedidos:
        assert any(x['sesion'] == d['sesion'] for x in g.sug_pedidos), 'el detalle reutiliza la sesión del autocompletado'
    assert not g.externos_prohibidos()
    s.limpio('A y B por toque'); s.cerrar()


@prueba
def seleccionar_con_el_teclado_confirma_igual(nav):
    s = movil(nav); GoogleSim(s.ctx); p = s.abrir()
    escribir(p, 'tripOrigin', 'Origen Teclado'); esperar_opciones(p, 'originResults', 2)
    p.keyboard.press('ArrowDown'); p.keyboard.press('ArrowDown'); p.keyboard.press('Enter')
    B.esperar(p, "!!Spevb.Planner.puntos().origen", 5000)
    assert 'Otra opción' in puntos(p)['A'][2], 'Enter confirma la sugerencia resaltada con las flechas'
    s.limpio('teclado'); s.cerrar()


@prueba
def el_toque_se_procesa_aunque_el_campo_pierda_el_foco_y_cambie_el_layout(nav):
    """pointerdown → blur → (el teclado cambia el layout) → pointerup: antes la lista se cerraba y el click se perdía."""
    s = movil(nav); GoogleSim(s.ctx); p = s.abrir()
    escribir(p, 'tripOrigin', 'Origen Blur'); esperar_opciones(p, 'originResults', 2)
    caja = p.locator('#originResults .autosuggest-option').first.bounding_box()
    x, y = caja['x'] + caja['width'] / 2, caja['y'] + caja['height'] / 2
    p.evaluate("""([x, y]) => { const o = document.querySelector('#originResults .autosuggest-option'); window.__op = o;
        o.dispatchEvent(new PointerEvent('pointerdown', { bubbles: true, cancelable: true, pointerId: 5, pointerType: 'touch', isPrimary: true, clientX: x, clientY: y })); }""", [x, y])
    p.evaluate("document.getElementById('tripOrigin').blur()")
    p.wait_for_timeout(400)      # más que el retardo con que el blur cierra la lista (160 ms)
    assert p.evaluate("document.contains(window.__op)"), 'la lista NO desaparece mientras un dedo está sobre ella'
    p.evaluate("""([x, y]) => window.__op.dispatchEvent(new PointerEvent('pointerup', { bubbles: true, cancelable: true, pointerId: 5, pointerType: 'touch', isPrimary: true, clientX: x, clientY: y }))""", [x, y])
    B.esperar(p, "!!Spevb.Planner.puntos().origen", 5000)
    assert estados(p)['origen'] == 'confirmado'
    s.limpio('blur y layout'); s.cerrar()


@prueba
def un_toque_que_desplaza_la_lista_no_elige_nada_y_un_toque_doble_no_procesa_dos_veces(nav):
    s = movil(nav); g = GoogleSim(s.ctx); p = s.abrir()
    escribir(p, 'tripOrigin', 'Origen Scroll'); esperar_opciones(p, 'originResults', 2)
    caja = p.locator('#originResults .autosuggest-option').first.bounding_box()
    x, y = caja['x'] + caja['width'] / 2, caja['y'] + caja['height'] / 2
    p.evaluate("""([x, y]) => { const o = document.querySelector('#originResults .autosuggest-option'); const f = { bubbles: true, cancelable: true, pointerId: 3, pointerType: 'touch', isPrimary: true };
        o.dispatchEvent(new PointerEvent('pointerdown', { ...f, clientX: x, clientY: y }));
        o.dispatchEvent(new PointerEvent('pointerup', { ...f, clientX: x, clientY: y + 60 })); }""", [x, y])
    p.wait_for_timeout(300)
    assert not puntos(p)['A'] and not g.det_pedidos, 'un arrastre no es una elección'
    p.locator('#originResults .autosuggest-option').first.dblclick()
    B.esperar(p, "!!Spevb.Planner.puntos().origen", 5000)
    p.wait_for_timeout(300)
    assert len(g.det_pedidos) == 1, f'el mismo toque no se procesa dos veces: {g.det_pedidos}'
    s.limpio('scroll y doble'); s.cerrar()


@prueba
def cambiar_de_campo_mientras_se_resuelve_no_quita_el_foco_ni_pierde_lo_escrito(nav):
    s = movil(nav); g = GoogleSim(s.ctx); p = s.abrir()
    escribir(p, 'tripOrigin', 'Origen Lento'); esperar_opciones(p, 'originResults', 2)
    g.modo_det = 'retener'
    p.locator('#originResults .autosuggest-option').first.tap()
    B.esperar(p, "Spevb.Planner.estados().origen === 'resolviendo'", 3000)
    assert p.is_disabled('#searchOrigin'), 'indicador de carga del campo A'
    p.click('#tripDestination'); p.fill('#tripDestination', 'Destino Plaza'); esperar_opciones(p, 'destinationResults', 2)
    g.liberar(tipo='det')
    B.esperar(p, "!!Spevb.Planner.puntos().origen", 5000)
    p.wait_for_timeout(300)
    assert p.evaluate("document.activeElement.id") == 'tripDestination', 'confirmar A no le quita el foco a B'
    assert p.input_value('#tripDestination') == 'Destino Plaza' and p.locator('#destinationResults .autosuggest-option').count() == 2, 'B conserva su texto y sus sugerencias'
    assert estados(p)['indicador']['origin'] == 0 and p.is_enabled('#searchOrigin')
    s.limpio('cambiar de campo'); s.cerrar()


@prueba
def una_respuesta_retenida_de_sugerencias_no_reabre_la_lista_tras_confirmar(nav):
    s = movil(nav); g = GoogleSim(s.ctx); p = s.abrir()
    escribir(p, 'tripOrigin', 'Origen Primero'); esperar_opciones(p, 'originResults', 2)
    g.modo_sug = 'retener'
    p.fill('#tripOrigin', 'Origen Primero y más'); p.wait_for_timeout(700)
    assert any(r[0] == 'sug' for r in g.retenidas), 'hay una consulta retenida'
    g.modo_sug = 'ok'
    p.fill('#tripOrigin', 'Origen Final'); esperar_opciones(p, 'originResults', 2)
    p.locator('#originResults .autosuggest-option').first.tap()
    B.esperar(p, "!!Spevb.Planner.puntos().origen", 5000)
    g.liberar(tipo='sug'); p.wait_for_timeout(400)
    assert p.locator('#originResults > *').count() == 0 and not p.evaluate("document.getElementById('originResults').classList.contains('suggestion-open')"), 'la respuesta tardía no reabre sugerencias'
    assert puntos(p)['A'][2].startswith('Origen Final'), 'ni sobrescribe la elección actual'
    s.limpio('respuesta tardia'); s.cerrar()


@prueba
def borrar_mientras_carga_deja_el_campo_vacio_y_sin_indicador(nav):
    s = movil(nav); g = GoogleSim(s.ctx); p = s.abrir()
    escribir(p, 'tripOrigin', 'Origen Borrar'); esperar_opciones(p, 'originResults', 2)
    g.modo_det = 'retener'
    p.locator('#originResults .autosuggest-option').first.tap()
    B.esperar(p, "Spevb.Planner.estados().origen === 'resolviendo'", 3000)
    p.click('#clearOrigin'); p.wait_for_timeout(200)
    g.liberar(tipo='det'); p.wait_for_timeout(500)
    assert not puntos(p)['A'] and p.input_value('#tripOrigin') == '', 'la respuesta tardía no restaura A'
    assert estados(p)['indicador']['origin'] == 0 and p.is_enabled('#searchOrigin') and estados(p)['origen'] == 'vacio'
    s.limpio('borrar cargando'); s.cerrar()


@prueba
def intercambiar_con_una_resolucion_pendiente_no_pone_el_punto_en_el_campo_equivocado(nav):
    s = movil(nav); g = GoogleSim(s.ctx); p = s.abrir()
    escribir(p, 'tripOrigin', 'Origen Intercambio'); esperar_opciones(p, 'originResults', 2)
    g.modo_det = 'retener'
    p.locator('#originResults .autosuggest-option').first.tap()
    B.esperar(p, "Spevb.Planner.estados().origen === 'resolviendo'", 3000)
    p.click('#swapTrip'); p.wait_for_timeout(200)
    g.liberar(tipo='det'); p.wait_for_timeout(500)
    pt = puntos(p)
    assert not pt['A'] and not pt['B'], f'lo pendiente quedó invalidado por el intercambio: {pt}'
    assert p.evaluate("Spevb.Planner.estados().indicador") == {'origin': 0, 'destination': 0}
    s.limpio('intercambiar'); s.cerrar()


@prueba
def si_falla_el_detalle_no_se_elige_otro_lugar_y_se_puede_reintentar(nav):
    s = movil(nav); g = GoogleSim(s.ctx); p = s.abrir()
    escribir(p, 'tripOrigin', 'Origen Falla'); esperar_opciones(p, 'originResults', 2)
    g.modo_det = 'error'
    p.locator('#originResults .autosuggest-option').first.tap()
    B.esperar(p, "!!document.querySelector('#originResults .search-retry')", 4000)
    assert not puntos(p)['A'] and p.input_value('#tripOrigin').startswith('Origen Falla'), 'el texto se conserva y A no se confirma con otra cosa'
    assert estados(p)['origen'] == 'error' and p.evaluate("Spevb.Planner.estados().indicador.origin") == 0
    g.modo_det = 'ok'
    p.click('#originResults .search-retry')
    B.esperar(p, "!!Spevb.Planner.puntos().origen", 5000)
    assert estados(p)['origen'] == 'confirmado'
    s.limpio('detalle falla'); s.cerrar()


@prueba
def volver_a_enfocar_un_punto_confirmado_no_busca_y_editar_si_invalida(nav):
    s = movil(nav); g = GoogleSim(s.ctx); p = s.abrir()
    escribir(p, 'tripOrigin', 'Origen Foco'); esperar_opciones(p, 'originResults', 2)
    p.locator('#originResults .autosuggest-option').first.tap()
    B.esperar(p, "!!Spevb.Planner.puntos().origen", 5000)
    n = len(g.sug_pedidos)
    p.click('#tripOrigin'); p.wait_for_timeout(900); p.evaluate("document.getElementById('tripOrigin').blur()"); p.click('#tripOrigin'); p.wait_for_timeout(900)
    assert len(g.sug_pedidos) == n and p.locator('#originResults > *').count() == 0, 'enfocar de nuevo no inicia otra búsqueda'
    assert puntos(p)['A'], 'sigue confirmado'
    p.keyboard.type('x'); p.wait_for_timeout(900)
    assert not puntos(p)['A'] and estados(p)['origen'] in ('editando', 'consultando', 'mostrando'), 'una edición real invalida el punto'
    assert len(g.sug_pedidos) > n
    s.limpio('reenfocar'); s.cerrar()


@prueba
def coordenadas_y_punto_en_el_mapa_siguen_funcionando_sin_google(nav):
    s = movil(nav); g = GoogleSim(s.ctx); g.modo_sug = 'sin_config'; g.modo_det = 'error'; p = s.abrir()
    p.fill('#tripOrigin', B.A); p.keyboard.press('Enter'); p.wait_for_timeout(600)
    assert puntos(p)['A'] and abs(puntos(p)['A'][0] - 4.145) < 1e-6, 'coordenadas escritas a mano'
    p.click('#pickDestination'); p.wait_for_timeout(400)
    p.evaluate("Spevb.maps.map.emitir('click', { latlng: [4.15, -73.63] })"); p.wait_for_timeout(800)
    assert puntos(p)['B'] and abs(puntos(p)['B'][0] - 4.15) < 1e-3, 'punto elegido en el mapa'
    assert not g.externos_prohibidos()
    s.limpio('coords y mapa'); s.cerrar()


# ============================================================================================================================ D · SOLO GOOGLE
@prueba
def google_sin_resultados_con_error_lento_o_sin_configurar_informa_y_conserva_el_texto(nav):
    s = movil(nav); g = GoogleSim(s.ctx); p = s.abrir()
    texto = lambda: p.inner_text('#originResults')
    g.modo_sug = 'vacio'; escribir(p, 'tripOrigin', 'zzzz sin nada'); B.esperar(p, "!!document.querySelector('#originResults .search-hint')", 4000)
    assert p.get_attribute('#originResults .search-hint', 'data-estado') == 'SIN_COINCIDENCIAS' and p.input_value('#tripOrigin') == 'zzzz sin nada'
    g.modo_sug = 'error'; p.fill('#tripOrigin', 'Origen error'); B.esperar(p, "!!document.querySelector('#originResults .search-retry')", 4000)
    assert p.get_attribute('#originResults .search-hint', 'data-estado') == 'RED' and p.input_value('#tripOrigin') == 'Origen error'
    n = len(g.sug_pedidos); p.wait_for_timeout(1500)
    assert len(g.sug_pedidos) == n, 'no hay reintentos automáticos ni bucles'
    g.modo_sug = 'ok'; p.click('#originResults .search-retry'); esperar_opciones(p, 'originResults', 2)
    g.modo_sug = 'sin_config'; p.fill('#tripOrigin', 'Origen config'); B.esperar(p, "!!document.querySelector('#originResults .search-hint')", 4000)
    assert p.get_attribute('#originResults .search-hint', 'data-estado') == 'CLAVE', 'clave/permisos: estado propio'
    g.modo_sug = 'config'; p.fill('#tripOrigin', 'Origen cfg2'); B.esperar(p, "!!document.querySelector('#originResults .search-hint')", 4000)
    g.modo_sug = 'retener'; p.fill('#tripOrigin', 'Origen lento'); p.wait_for_timeout(700)
    assert p.evaluate("Spevb.Planner.estados().origen") == 'consultando', 'Google lento: el campo muestra que consulta'
    assert not g.externos_prohibidos(), g.externos_prohibidos()
    s.limpio('estados google'); s.cerrar()


@prueba
def solo_se_llama_a_la_api_propia_nunca_a_arcgis_ni_nominatim(nav):
    s = movil(nav); g = GoogleSim(s.ctx); p = s.abrir()
    escribir(p, 'tripOrigin', 'Origen Proveedores'); esperar_opciones(p, 'originResults', 2)
    p.locator('#originResults .autosuggest-option').first.tap(); B.esperar(p, "!!Spevb.Planner.puntos().origen", 5000)
    p.click('#pickDestination'); p.evaluate("Spevb.maps.map.emitir('click', { latlng: [4.15, -73.63] })"); p.wait_for_timeout(900)
    p.fill('#tripDestination', 'zzz'); p.keyboard.press('Enter'); p.wait_for_timeout(900)
    api = [u for u in g.todos if '/api/lugares' in u]
    assert api and all(u.startswith(B.URL) for u in api)
    assert not g.externos_prohibidos(), g.externos_prohibidos()
    s.limpio('solo google'); s.cerrar()


# ============================================================================================================================ E · VOLVER AL INICIO
@prueba
def volver_al_inicio_desde_resultados_limpia_el_trabajo_actual_y_conserva_lo_personal(nav):
    s = movil(nav); p = s.abrir()
    p.evaluate("""() => { const a = { lat: 4.1, lng: -73.6, nombre: 'Uno' }, b = { lat: 4.2, lng: -73.62, nombre: 'Dos' };
        localStorage.setItem('spevb-favoritos', JSON.stringify([{ nombre: 'Casa', lat: 4.1, lng: -73.6 }]));
        localStorage.setItem('spevb-historial', JSON.stringify([{ id: 'h1', a, b, t: 1, codigos: [] }]));
        localStorage.setItem('spevb-viajes-guardados', JSON.stringify([{ id: 'v1', nombre: 'Mi viaje', a, b }])); }""")
    V.planear(p)
    assert p.locator('#tripResults .trip-option').count() > 0
    p.click('.sheet-head .brand'); p.wait_for_timeout(700)
    assert not puntos(p)['A'] and not puntos(p)['B'] and p.input_value('#tripOrigin') == '' and p.input_value('#tripDestination') == ''
    assert p.locator('#tripResults > *').count() == 0 and p.locator('.trip-pin').count() == 0 and p.evaluate('Spevb.Planner.seleccion') is None
    assert p.evaluate("document.activeElement.tagName") != 'INPUT', 'sin teclado'
    assert p.evaluate("['spevb-favoritos','spevb-historial','spevb-viajes-guardados'].every(k => localStorage.getItem(k))"), 'cuenta/favoritos/historial/viajes guardados NO se borran'
    assert not p.evaluate("document.body.classList.contains('panel-oculto')") and sup(p)['overlay'] is None
    p.click('.sheet-head .brand'); p.click('.sheet-head .brand'); p.wait_for_timeout(300)      # idempotente
    s.limpio('inicio desde resultados'); s.cerrar()


@prueba
def volver_al_inicio_en_pleno_viaje_pide_confirmar_y_seguir_viajando_no_cambia_nada(nav):
    s = movil(nav); p = s.abrir()
    V.planear(p); V.destino_lejos(p); ls = V.lecturas(p); V.iniciar(p); V.alimentar(p, ls, 0, 'fase:esperando')
    fase = V.card(p)['fase']
    p.click('#viajeInicio'); p.wait_for_timeout(300)
    assert p.is_visible('#inicioModal') and p.inner_text('#inicioTitulo') == '¿Terminar el viaje y volver al inicio?'
    assert p.evaluate("document.activeElement.id") == 'inicioSeguir', 'el foco cae en la opción segura'
    p.click('#inicioSeguir'); p.wait_for_timeout(300)
    assert p.is_hidden('#inicioModal') and p.evaluate('Spevb.Viaje.activo()') and V.card(p)['fase'] == fase and p.evaluate('window.__geo.escuchas') == 1
    assert puntos(p)['A'] and puntos(p)['B'], 'A y B siguen donde estaban'
    p.click('#viajeInicio'); p.keyboard.press('Escape'); p.wait_for_timeout(300)
    assert p.is_hidden('#inicioModal') and p.evaluate('Spevb.Viaje.activo()'), 'Escape cancela la confirmación'
    s.limpio('inicio cancelar'); s.cerrar()


@prueba
def volver_al_inicio_confirmado_termina_el_viaje_libera_recursos_y_no_se_restaura_al_recargar(nav):
    s = movil(nav); g = GoogleSim(s.ctx); p = s.abrir()
    V.planear(p); V.destino_lejos(p); ls = V.lecturas(p); V.iniciar(p); V.alimentar(p, ls, 0, 'fase:esperando')
    assert p.evaluate("localStorage.getItem('spevb-viaje-curso')") is not None
    p.click('#viajeInicio'); p.click('#inicioTerminar'); p.wait_for_timeout(700)
    assert not p.evaluate('Spevb.Viaje.activo()') and p.evaluate('window.__geo.escuchas') == 0, 'el GPS del viaje se libera'
    assert p.evaluate("localStorage.getItem('spevb-viaje-curso')") is None, 'se borra el estado reanudable'
    assert not puntos(p)['A'] and not puntos(p)['B'] and p.is_hidden('#viajeCard') and p.is_hidden('#viajePastilla')
    assert p.evaluate("[document.body.classList.contains('viaje-activo'), document.body.classList.contains('panel-oculto'), document.getElementById('startTrip').disabled]") == [False, False, False]
    assert p.locator('.viaje-yo').count() == 0, 'sin marcador ni capas del viaje'
    c = p.evaluate("Spevb.maps.map.getCenter()")
    assert abs(c[0] - 4.142) < 0.02 and abs(c[1] + 73.6266) < 0.03, c
    assert sup(p)['viaje'] is False and sup(p)['principal'] == 'planificador'
    for _ in range(3):
        p.evaluate('Spevb.Inicio.volver("repetido")')      # idempotente
    p.reload(wait_until='domcontentloaded'); B.esperar(p, "!!(window.Spevb && Spevb.maps && Spevb.maps.map)", 20000); p.wait_for_timeout(900)
    assert p.is_hidden('#viajeCard') and not p.evaluate('Spevb.Viaje.activo()'), 'tras recargar no se ofrece el viaje terminado'
    s.limpio('inicio confirmado'); s.cerrar()


@prueba
def volver_al_inicio_invalida_respuestas_pendientes(nav):
    s = movil(nav); g = GoogleSim(s.ctx); p = s.abrir()
    escribir(p, 'tripDestination', 'Destino Pendiente'); esperar_opciones(p, 'destinationResults', 2)
    g.modo_det = 'retener'
    p.locator('#destinationResults .autosuggest-option').first.tap()
    B.esperar(p, "Spevb.Planner.estados().destino === 'resolviendo'", 3000)
    p.click('.sheet-head .brand'); p.wait_for_timeout(300)
    g.liberar(tipo='det'); p.wait_for_timeout(600)
    assert not puntos(p)['B'] and p.input_value('#tripDestination') == '' and estados(p)['indicador']['destination'] == 0
    s.limpio('inicio pendientes'); s.cerrar()


@prueba
def volver_al_inicio_desde_el_menu_cierra_el_menu(nav):
    s = movil(nav); p = s.abrir()
    p.fill('#tripOrigin', B.A); p.keyboard.press('Enter'); p.wait_for_timeout(500)
    p.click('#menuButton'); p.wait_for_timeout(400)
    p.click('#menuInicio'); p.wait_for_timeout(600)
    assert sup(p)['overlay'] is None and not puntos(p)['A'] and p.input_value('#tripOrigin') == ''
    s.limpio('inicio desde menu'); s.cerrar()


# ============================================================================================================================ F · DISEÑO
LARGO = 'R-' + 'TERMINAL-CIRCUNVALAR-CENTRO-' * 3 + 'Universidad_Santo_Tomás_Villavicencio_Sede_Principal'

JS_DISENO = """() => {
  const W = innerWidth, H = innerHeight, r = (e) => e.getBoundingClientRect();
  const out = { desborde: document.documentElement.scrollWidth - W, problemas: [] };
  const card = document.getElementById('viajeCard');
  if (card && !card.hidden) {
    const c = r(card); out.card = { top: c.top, bottom: c.bottom, left: c.left, right: c.right, w: c.width };
    if (c.top < 0 || c.bottom > H + 1 || c.left < -1 || c.right > W + 1) out.problemas.push('la tarjeta se sale de la pantalla');
    const ins = r(document.getElementById('viajeInstruccion'));
    out.instruccionAncho = ins.width / c.width;
    if (ins.width / c.width < 0.6) out.problemas.push('la instrucción quedó en una columna estrecha: ' + Math.round(ins.width));
    const t = document.getElementById('viajeTerminar'); const tr = r(t);
    out.terminar = { top: tr.top, bottom: tr.bottom };
    if (tr.bottom > H + 1 || tr.top < 0) out.problemas.push('«Terminar viaje» queda fuera de la pantalla');
    if (card.scrollWidth > card.clientWidth + 1) out.problemas.push('desborde horizontal dentro de la tarjeta');
    card.querySelectorAll('button').forEach((b) => { const br = r(b); if (!b.hidden && br.width && (br.height < 43.5 || br.width < 43.5) && !b.closest('[hidden]')) out.problemas.push('objetivo táctil pequeño: ' + b.id + ' ' + Math.round(br.width) + 'x' + Math.round(br.height)); });
  }
  document.querySelectorAll('#sheet .endpoint-tools .tool:not(.tool-clear)').forEach((b) => {
    if (r(b).width && b.closest('#sheet') && getComputedStyle(b.closest('#sheet')).visibility !== 'hidden') {
      const lbl = b.getAttribute('data-etiqueta') || ''; const cs = getComputedStyle(b, '::after');
      if (b.scrollWidth > b.clientWidth + 1) out.problemas.push('texto recortado en ' + b.id + ' (' + lbl + ')');
    }
  });
  return out;
}"""


@prueba
def la_tarjeta_del_viaje_y_los_controles_caben_en_todos_los_tamanos_con_nombres_largos(nav):
    for nombre, w, h in TAMANOS:
        s = movil(nav, w, h); p = s.abrir()
        V.planear(p); V.destino_lejos(p)
        p.evaluate("(l) => { const v = Spevb.Planner.seleccion.viaje; v.buses.forEach((b) => { b.etiqueta = l; }); }", LARGO)
        antes = p.evaluate(JS_DISENO)
        assert antes['desborde'] <= 0 and not antes['problemas'], f'{nombre} (planificación): {antes}'
        ls = V.lecturas(p, ruido={'sigma': 6, 'semilla': 3})
        V.iniciar(p); V.alimentar(p, ls, 0, 'fase:esperando'); V.alimentar(p, ls, 0, 'pregunta'); p.wait_for_timeout(300)
        r = p.evaluate(JS_DISENO)
        assert r['desborde'] <= 0 and not r['problemas'], f'{nombre}: {r}'
        # máximo una superficie principal expandida: con el viaje activo el planificador no está expandido
        e = sup(p)
        compacto = w < 560 or (w <= 860 and h >= 481)      # diseño de celular; el apaisado y el escritorio conviven con la tarjeta (sin ceder), como antes
        assert e['principal'] == 'viaje' and p.evaluate("document.body.classList.contains('panel-oculto')") == compacto, (nombre, e)
        s.limpio('diseno ' + nombre); s.cerrar()


@prueba
def texto_ampliado_y_modo_oscuro_no_rompen_la_tarjeta_ni_los_botones(nav):
    for esquema in ('light', 'dark'):
        s = movil(nav, 360, 640, color_scheme=esquema); p = s.abrir()
        p.evaluate("document.documentElement.dataset.letra = 'enorme'; try { localStorage.setItem('spevb-confort', JSON.stringify({ letra: 'enorme' })); } catch (e) {}")
        p.click('#menuButton'); p.wait_for_timeout(300)
        if p.locator('[data-letra="enorme"]').count(): p.click('[data-letra="enorme"]')
        p.click('#closeMenu'); p.wait_for_timeout(300)
        V.planear(p); V.destino_lejos(p)
        p.evaluate("(l) => { Spevb.Planner.seleccion.viaje.buses.forEach((b) => { b.etiqueta = l; }); }", LARGO)
        V.iniciar(p); p.wait_for_timeout(300)
        r = p.evaluate(JS_DISENO)
        assert r['desborde'] <= 0 and not [x for x in r['problemas'] if 'objetivo' not in x], f'{esquema}: {r}'
        fondo, color = p.evaluate("(() => { const c = document.getElementById('viajeCard'); const t = document.getElementById('viajeTitulo'); return [getComputedStyle(c).backgroundColor, getComputedStyle(t).color]; })()")
        def lum(rgb):
            v = [int(x) for x in rgb[rgb.index('(') + 1:rgb.index(')')].split(',')[:3]]
            f = [(c / 255) / 12.92 if c / 255 <= 0.03928 else (((c / 255) + 0.055) / 1.055) ** 2.4 for c in v]
            return 0.2126 * f[0] + 0.7152 * f[1] + 0.0722 * f[2]
        a, b = sorted([lum(fondo), lum(color)], reverse=True)
        assert (a + 0.05) / (b + 0.05) >= 4.5, f'{esquema}: contraste del título {fondo} / {color}'
        s.limpio('ampliado ' + esquema); s.cerrar()


@prueba
def la_tarjeta_no_crece_sin_limite_con_mensajes_largos_y_se_desplaza_por_dentro(nav):
    s = movil(nav, 320, 568); p = s.abrir()
    V.planear(p); V.destino_lejos(p); ls = V.lecturas(p); V.iniciar(p)
    p.evaluate("""(t) => { const a = document.getElementById('viajeAviso'); a.textContent = t; a.dataset.nivel = 'alerta'; a.hidden = false; document.getElementById('viajeInstruccion').textContent = t; }""", 'Mensaje larguísimo. ' * 40)
    p.wait_for_timeout(200)
    d = p.evaluate("(() => { const c = document.getElementById('viajeCard'); const r = c.getBoundingClientRect(); return { alto: r.height, H: innerHeight, scroll: c.scrollHeight > c.clientHeight, overflowY: getComputedStyle(c).overflowY }; })()")
    assert d['alto'] <= d['H'] * 0.66 and d['scroll'] and d['overflowY'] in ('auto', 'scroll'), d
    t = p.evaluate("(() => { const t = document.getElementById('viajeTerminar').getBoundingClientRect(); return [t.top, t.bottom, innerHeight]; })()")
    assert 0 <= t[0] and t[1] <= t[2] + 1, f'la acción «Terminar viaje» sigue alcanzable (pie fijo): {t}'
    s.limpio('crecimiento'); s.cerrar()


@prueba
def con_el_teclado_abierto_el_cambio_de_altura_no_abre_ni_maximiza_otros_paneles(nav):
    s = movil(nav, 390, 844); GoogleSim(s.ctx)
    s.ctx.add_init_script(path=os.path.join(os.path.dirname(os.path.abspath(__file__)), 'movil_teclado_ios.js')) if os.path.exists(os.path.join(os.path.dirname(os.path.abspath(__file__)), 'movil_teclado_ios.js')) else None
    p = s.abrir()
    estado0 = p.evaluate("document.getElementById('sheet').dataset.state")
    p.evaluate("window.dispatchEvent(new Event('resize')); if (window.visualViewport) window.visualViewport.dispatchEvent(new Event('resize'))")
    p.wait_for_timeout(500)
    assert p.evaluate("document.getElementById('sheet').dataset.state") == estado0, 'resize/visualViewport solos no cambian la altura del panel'
    # un foco SIN gesto (como al volver de otra app) no maximiza el planificador
    p.evaluate("document.getElementById('tripOrigin').focus()"); p.wait_for_timeout(700)
    assert p.evaluate("document.getElementById('sheet').dataset.state") == estado0, 'el foco programático no abre el panel completo'
    p.evaluate("document.getElementById('tripOrigin').blur()")
    p.tap('#tripOrigin'); p.wait_for_timeout(700)
    assert p.evaluate("document.getElementById('sheet').dataset.state") in ('half', 'full'), 'tocar el campo para escribir sí sube el panel'
    s.limpio('teclado'); s.cerrar()


@prueba
def traer_el_planificador_durante_el_viaje_compacta_la_tarjeta_y_una_pregunta_sale_arriba(nav):
    s = movil(nav, 360, 640); p = s.abrir()
    V.planear(p); V.destino_lejos(p)
    ls = V.lecturas(p, ruido={'sigma': 6, 'semilla': 3})
    V.iniciar(p); V.alimentar(p, ls, 0, 'fase:esperando')
    assert p.is_visible('#panelShow'), 'durante el viaje se puede traer el planificador'
    box = p.evaluate("(() => { const r = document.getElementById('panelShow').getBoundingClientRect(); const c = document.getElementById('viajeCard').getBoundingClientRect(); return [r.top, r.bottom, c.top]; })()")
    assert box[1] <= box[2] + 1, f'«Buscar ruta» no queda tapado por la tarjeta: {box}'
    p.click('#panelShow'); p.wait_for_timeout(600)
    e = sup(p)
    assert e['principal'] == 'planificador' and e['viaje'] and e['cedio'] == 'viaje', e
    d = p.evaluate("""() => { const c = document.getElementById('viajeCard'); const r = c.getBoundingClientRect(); const sh = document.getElementById('sheet').getBoundingClientRect();
        return { cede: c.dataset.cede, alto: r.height, bottom: r.bottom, sheetTop: sh.top, instruccion: document.getElementById('viajeInstruccion').offsetParent !== null }; }""")
    assert d['cede'] == 'planificador' and not d['instruccion'] and d['alto'] < 140 and d['bottom'] <= d['sheetTop'] + 1, f'la tarjeta se compacta y no se apila con el planificador: {d}'
    V.alimentar(p, ls, 0, 'pregunta'); p.wait_for_timeout(300)
    q = p.evaluate("""() => { const c = document.getElementById('viajeCard'); const r = c.getBoundingClientRect();
        return { urgente: c.dataset.urgente, top: r.top, visibles: [...c.querySelectorAll('.viaje-pregunta, .viaje-aviso')].filter(e => e.offsetParent !== null).length, fase: c.dataset.fase }; }""")
    assert q['urgente'] == '1' and q['top'] <= 64 and q['visibles'] == 1, q
    p.click('#viajeSi'); p.wait_for_timeout(300)
    assert V.card(p)['fase'] == 'en_bus'
    p.click('#panelHide'); p.wait_for_timeout(500)
    e = sup(p)
    assert e['principal'] == 'viaje' and p.evaluate("document.getElementById('viajeCard').dataset.cede") == '', e
    assert p.evaluate("document.getElementById('viajeInstruccion').offsetParent !== null"), 'la tarjeta vuelve completa al ocultar el planificador'
    s.limpio('planificador en viaje'); s.cerrar()


@prueba
def una_direccion_larga_confirmada_se_ve_completa_y_el_campo_sigue_editable(nav):
    s = movil(nav, 320, 568); p = s.abrir()
    larga = 'Universidad_Santo_Tomás_Villavicencio_Sede_Principal_Carrera_22_No_7_Sur_Barrio_Barzal_Alto'
    p.evaluate("([n, d]) => Spevb.Planner.poner('origin', { id: null, lat: 4.145, lng: -73.654, nombre: n, direccion: d, fuente: 'Google Maps', confirmado: false, precision: 'LUGAR' })",
               [larga, larga + ', Villavicencio, Meta, Colombia'])
    p.wait_for_timeout(400)
    d = p.evaluate("""() => { const el = document.getElementById('originCompleto'), i = document.getElementById('tripOrigin');
        return { visible: !!el && !el.hidden && el.offsetParent !== null, texto: el && el.textContent, titulo: i.title, desborde: document.documentElement.scrollWidth - innerWidth, valor: i.value }; }""")
    assert d['visible'] and 'Villavicencio, Meta, Colombia' in d['texto'] and d['titulo'] == d['texto'] and d['desborde'] <= 0, d
    assert d['valor'] == larga, 'el input conserva el texto completo y editable (no se fuerza multilínea)'
    p.click('#tripOrigin'); p.keyboard.press('End'); p.keyboard.type('X'); p.wait_for_timeout(300)
    assert not puntos(p)['A'] and p.evaluate("document.getElementById('originCompleto').hidden"), 'editar invalida el punto y retira la presentación completa'
    s.limpio('direccion larga'); s.cerrar()


@prueba
def usar_mi_ubicacion_confirma_A_sin_llamar_a_google(nav):
    # GPS real del navegador (geolocalización de Playwright), sin el simulador del viaje
    s = B.Sesion(nav, viewport={'width': 390, 'height': 844}, is_mobile=True, has_touch=True, permissions=['geolocation'], geolocation={'latitude': 4.15, 'longitude': -73.63287, 'accuracy': 15})
    g = GoogleSim(s.ctx); p = s.abrir()
    p.tap('#useMyLocation')
    B.esperar(p, "!!Spevb.Planner.puntos().origen", 15000)
    assert abs(puntos(p)['A'][0] - 4.15) < 0.01 and estados(p)['origen'] == 'confirmado'
    assert not g.sug_pedidos and not g.det_pedidos and not g.externos_prohibidos(), 'el GPS del teléfono no es una búsqueda de texto: no consulta a Google'
    s.limpio('mi ubicacion'); s.cerrar()


@prueba
def girar_el_telefono_o_cambiar_la_ventana_en_pleno_viaje_no_abre_paneles_ni_pierde_el_viaje(nav):
    s = movil(nav, 390, 844); p = s.abrir()
    V.planear(p); V.destino_lejos(p)
    ls = V.lecturas(p, ruido={'sigma': 6, 'semilla': 3})
    V.iniciar(p); V.alimentar(p, ls, 0, 'fase:esperando')
    fase = V.card(p)['fase']
    for w, h in ((844, 390), (600, 960), (390, 844), (320, 568)):
        p.set_viewport_size({'width': w, 'height': h}); p.wait_for_timeout(500)
        assert p.evaluate('Spevb.Viaje.activo()') and p.evaluate('window.__geo.escuchas') == 1 and V.card(p)['fase'] == fase, f'{w}x{h}: el viaje sigue igual'
        e = sup(p)
        assert e['overlay'] is None and e['principal'] == 'viaje', (w, h, e)
        r = p.evaluate(JS_DISENO)
        assert r['desborde'] <= 0 and not [x for x in r['problemas'] if 'objetivo' not in x], f'{w}x{h}: {r}'
        # ningún panel grande: como máximo el planificador a su altura normal en el diseño ancho; nunca el panel a pantalla completa
        assert p.evaluate("document.getElementById('sheet').dataset.state") != 'full', f'{w}x{h}: ningún panel se maximizó solo'
    s.limpio('rotar y redimensionar'); s.cerrar()


@prueba
def una_ventana_estrecha_con_raton_sin_tactil_tambien_funciona(nav):
    s = V.sesion(nav, viewport={'width': 700, 'height': 900})      # ventana angosta de escritorio o tableta con ratón: sin touch
    p = s.abrir()
    V.planear(p); V.destino_lejos(p); V.iniciar(p)
    assert p.is_visible('#menuFlotante')
    p.click('#menuFlotante'); p.wait_for_timeout(400)
    assert sup(p)['overlay'] == 'menu'
    p.mouse.move(300, 500); p.mouse.wheel(0, 400); p.wait_for_timeout(250)
    assert p.evaluate("document.getElementById('sideMenu').scrollTop") > 0
    p.keyboard.press('Escape'); p.wait_for_timeout(300)
    assert sup(p)['overlay'] is None and p.evaluate('Spevb.Viaje.activo()') and p.evaluate('window.__geo.escuchas') == 1
    p.click('#viajeInicio'); p.wait_for_timeout(250)
    assert p.is_visible('#inicioModal'); p.click('#inicioSeguir'); p.wait_for_timeout(500)
    assert p.evaluate('Spevb.Viaje.activo()')
    r = p.evaluate(JS_DISENO)
    assert r['desborde'] <= 0 and not [x for x in r['problemas'] if 'objetivo' not in x], r
    s.limpio('ventana estrecha con raton'); s.cerrar()


# ============================================================================================================================
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
                print('FALLA ', f.__name__, '|', str(e)[:600].replace('\n', ' '))
                if os.environ.get('SEVB_E2E_TRAZA'):
                    traceback.print_exc()
        nav.close()
    print(f'\n{len([f for f in PRUEBAS if not filtro or filtro in f.__name__])} pruebas de experiencia móvil 4.14; fallos: {len(fallos)}', fallos)
    return 1 if fallos else 0


if __name__ == '__main__':
    sys.exit(correr(sys.argv[1] if len(sys.argv) > 1 else None))
