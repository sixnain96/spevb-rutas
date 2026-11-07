"""Pruebas de navegador de la BÚSQUEDA de direcciones y de la CONFIRMACIÓN de A/B (contrato B.2–B.4 y C de docs/CONTRATO-RECOMENDACION-Y-BUSQUEDA.md).

Verifican comportamientos del producto con un navegador real contra el servidor simulado (e2e/stub_server.py): sugerencias que no reaparecen tras
confirmar, respuestas tardías o retenidas, errores por estado, elección fallida sin sustitución silenciosa, una sesión de Places por búsqueda,
composición de texto, intercambio A↔B y ocultar el panel con sugerencias abiertas. Este stub NO es el backend Java: el orden de proveedores
(Google → ArcGIS → local) lo prueban las pruebas Java; aquí se prueba lo que el NAVEGADOR hace con lo que recibe.

Uso: SEVB_E2E_URL=http://127.0.0.1:18110/ python e2e/busqueda_e2e.py [filtro]
Las pruebas que controlan «respuesta tardía» retienen la petición con Playwright (route) y la sueltan cuando quieren.
"""
import json
import os
import sys
import traceback
import urllib.request

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import sevb_e2e as B  # noqa: E402
from playwright.sync_api import sync_playwright  # noqa: E402

PRUEBAS = []
ID = {'origin': 'tripOrigin', 'destination': 'tripDestination'}


def prueba(f):
    PRUEBAS.append(f)
    return f


class Retenidas:
    """Retiene las peticiones que coincidan con el patrón hasta llamar a liberar() (o las deja pasar con soltar())."""

    def __init__(self, ctx, patron):
        self.pend = []
        self.activo = True
        ctx.route(patron, self._manejar)

    def _manejar(self, route):
        if self.activo:
            self.pend.append(route)
        else:
            route.continue_()

    def liberar(self, indice=None):
        lista = self.pend if indice is None else [self.pend[indice]]
        for r in lista:
            try:
                r.continue_()
            except Exception:  # noqa: BLE001
                pass
        if indice is None:
            self.pend = []

    def soltar(self):
        self.activo = False
        self.liberar()


def sesion(nav, **opts):
    s = B.Sesion(nav, **opts)
    p = s.abrir()
    limpiar_log()
    return s, p


def registro():
    return json.loads(urllib.request.urlopen(B.URL + '__log/busqueda').read())


def limpiar_log():
    urllib.request.urlopen(B.URL + '__log/limpiar').read()


def consultas(ruta=None):
    return [r for r in registro() if ruta is None or r['ruta'] == ruta]


def lista(p, modo):
    return p.locator(f'#{modo}Results .autosuggest-option')


def esperar_lista(p, modo, timeout=8000):
    B.esperar(p, f"document.querySelectorAll('#{modo}Results .autosuggest-option').length > 0", timeout)


def punto(p, modo):
    return p.evaluate("(m) => { const q = Spevb.Planner.puntos(); return q[m] && q[m].nombre; }", 'origen' if modo == 'origin' else 'destino')


def aria(p, modo):
    return p.evaluate(f"document.getElementById('{ID[modo]}').getAttribute('aria-expanded')")


def confirmar_por_sugerencia(p, modo='origin', texto='Carrera 22'):
    p.fill('#' + ID[modo], texto)
    esperar_lista(p, modo)
    lista(p, modo).first.click()
    B.esperar(p, f"!!Spevb.Planner.puntos()['{'origen' if modo == 'origin' else 'destino'}']", 6000)
    p.wait_for_timeout(300)


# ------------------------------------------------------------------------------------------------ confirmar cierra todo y se queda cerrado
@prueba
def sugerencias_no_reaparecen_tras_confirmar_ni_por_foco_ni_por_pasar_el_tiempo(nav):
    s, p = sesion(nav)
    confirmar_por_sugerencia(p)
    hechas = len(consultas('sugerencias'))
    assert lista(p, 'origin').count() == 0 and aria(p, 'origin') == 'false', 'al confirmar la lista se cierra y se vacía'
    assert p.evaluate("document.getElementById('tripOrigin').hasAttribute('aria-activedescendant')") is False
    # foco, desenfoque, foco otra vez, cambio de layout: nada de eso consulta ni reabre
    p.click('#tripOrigin'); p.wait_for_timeout(700)
    p.click('#tripDestination'); p.wait_for_timeout(300)
    p.click('#tripOrigin'); p.wait_for_timeout(900)
    p.set_viewport_size({'width': 900, 'height': 700}); p.wait_for_timeout(500)
    assert len(consultas('sugerencias')) == hechas, f'consultas nuevas tras confirmar: {consultas()}'
    assert lista(p, 'origin').count() == 0 and aria(p, 'origin') == 'false'
    assert punto(p, 'origin'), 'el punto sigue confirmado'
    s.limpio('confirmar'); s.cerrar()


@prueba
def enter_sobre_un_punto_confirmado_no_vuelve_a_buscar_ni_reabre_la_lista(nav):
    s, p = sesion(nav)
    confirmar_por_sugerencia(p)
    antes = len(registro())
    p.focus('#tripOrigin'); p.keyboard.press('Enter'); p.wait_for_timeout(900)
    p.click('#searchOrigin'); p.wait_for_timeout(900)
    assert len(registro()) == antes, f'Enter o «Buscar» sobre un punto confirmado no deben consultar: {registro()[antes:]}'
    assert lista(p, 'origin').count() == 0 and punto(p, 'origin')
    s.limpio('enter confirmado'); s.cerrar()


@prueba
def un_evento_input_sin_cambio_no_invalida_el_punto_confirmado(nav):
    """Autocompletado del navegador, dictado o composición terminada pueden emitir «input» sin cambiar el texto."""
    s, p = sesion(nav)
    confirmar_por_sugerencia(p)
    antes = len(registro())
    p.evaluate("document.getElementById('tripOrigin').dispatchEvent(new Event('input', { bubbles: true }))")
    p.wait_for_timeout(900)
    assert punto(p, 'origin'), 'el punto confirmado debe seguir ahí'
    assert len(registro()) == antes and lista(p, 'origin').count() == 0
    s.limpio('input sin cambio'); s.cerrar()


@prueba
def editar_de_verdad_un_punto_confirmado_lo_invalida_y_reactiva_la_busqueda(nav):
    s, p = sesion(nav)
    confirmar_por_sugerencia(p)
    antes = len(consultas('sugerencias'))
    p.click('#tripOrigin'); p.keyboard.press('End'); p.keyboard.type(' bis')
    esperar_lista(p, 'origin')
    assert punto(p, 'origin') is None, 'editar el texto invalida el punto confirmado'
    assert len(consultas('sugerencias')) == antes + 1, 'una sola consulta por la edición (con su espera)'
    s.limpio('editar confirmado'); s.cerrar()


@prueba
def pegar_texto_consulta_una_vez_y_no_por_cada_letra(nav):
    s, p = sesion(nav)
    p.fill('#tripOrigin', 'Avenida Catama con carrera 40')
    esperar_lista(p, 'origin')
    p.wait_for_timeout(700)
    assert len(consultas('sugerencias')) == 1, consultas()
    s.limpio('pegar'); s.cerrar()


# ------------------------------------------------------------------------------------------------ respuestas tardías
@prueba
def una_respuesta_retenida_no_reabre_la_lista_despues_de_confirmar_otro_punto(nav):
    s, p = sesion(nav)
    ret = Retenidas(s.ctx, '**/api/lugares/sugerencias*')
    p.fill('#tripOrigin', 'Calle 38 con carrera 22')
    p.wait_for_timeout(900)
    assert ret.pend, 'la consulta quedó retenida'
    p.evaluate("Spevb.Planner.poner('origin', { id: null, lat: 4.1450, lng: -73.6540, nombre: 'Favorito Casa', direccion: null, fuente: 'Favorito', confirmado: true })")
    ret.liberar(); p.wait_for_timeout(900)
    assert lista(p, 'origin').count() == 0 and aria(p, 'origin') == 'false', 'la respuesta tardía no debe reabrir la lista'
    assert punto(p, 'origin') == 'Favorito Casa', 'ni reemplazar lo confirmado'
    s.limpio('respuesta retenida'); s.cerrar()


@prueba
def borrar_el_texto_antes_de_la_respuesta_no_deja_sugerencias(nav):
    s, p = sesion(nav)
    ret = Retenidas(s.ctx, '**/api/lugares/sugerencias*')
    p.fill('#tripOrigin', 'Calle 38 con carrera 22'); p.wait_for_timeout(900)
    p.fill('#tripOrigin', ''); p.wait_for_timeout(200)
    ret.liberar(); p.wait_for_timeout(800)
    assert lista(p, 'origin').count() == 0 and aria(p, 'origin') == 'false'
    s.limpio('borrar antes'); s.cerrar()


@prueba
def intercambiar_A_y_B_con_consultas_pendientes_no_reabre_listas(nav):
    s, p = sesion(nav)
    confirmar_por_sugerencia(p, 'origin', 'Carrera 22')
    ret = Retenidas(s.ctx, '**/api/lugares/sugerencias*')
    p.fill('#tripDestination', 'Calle 10 sur'); p.wait_for_timeout(900)
    p.click('#swapTrip'); p.wait_for_timeout(300)
    ret.liberar(); p.wait_for_timeout(900)
    assert lista(p, 'destination').count() == 0 and lista(p, 'origin').count() == 0, 'ninguna lista debe reabrirse tras intercambiar'
    assert p.input_value('#tripDestination').startswith('Carrera 22') and p.input_value('#tripOrigin') == 'Calle 10 sur'
    s.limpio('intercambiar'); s.cerrar()


@prueba
def ocultar_el_panel_con_sugerencias_abiertas_las_cierra_y_cancela_lo_pendiente(nav):
    s, p = sesion(nav)
    p.fill('#tripOrigin', 'Carrera 30'); esperar_lista(p, 'origin')
    p.click('#panelHide'); p.wait_for_timeout(400)
    assert lista(p, 'origin').count() == 0 and aria(p, 'origin') == 'false', 'con el panel oculto no deben quedar sugerencias abiertas'
    ret = Retenidas(s.ctx, '**/api/lugares/sugerencias*')
    p.click('#panelShow'); p.wait_for_timeout(300)
    p.fill('#tripOrigin', 'Carrera 31'); p.wait_for_timeout(900)
    p.click('#panelHide'); p.wait_for_timeout(300)
    ret.liberar(); p.wait_for_timeout(700)
    assert lista(p, 'origin').count() == 0, 'una respuesta que llega con el panel oculto no reabre la lista'
    s.limpio('ocultar panel'); s.cerrar()


# ------------------------------------------------------------------------------------------------ formas de confirmar
@prueba
def seleccionar_con_flechas_y_enter_o_con_toque_confirma_y_cierra(nav):
    s, p = sesion(nav)
    p.fill('#tripOrigin', 'Carrera 22'); esperar_lista(p, 'origin')
    p.keyboard.press('ArrowDown'); p.keyboard.press('Enter')
    B.esperar(p, "!!Spevb.Planner.puntos().origen", 6000)
    assert lista(p, 'origin').count() == 0 and aria(p, 'origin') == 'false'
    p.fill('#tripDestination', 'Calle 10'); esperar_lista(p, 'destination')
    lista(p, 'destination').first.click()
    B.esperar(p, "!!Spevb.Planner.puntos().destino", 6000)
    assert lista(p, 'destination').count() == 0 and punto(p, 'origin') and punto(p, 'destination')
    s.limpio('flechas'); s.cerrar()


@prueba
def coordenadas_favorito_y_punto_en_el_mapa_se_resuelven_sin_consultar_proveedores(nav):
    s, p = sesion(nav)
    p.fill('#tripOrigin', '4.1450, -73.6540'); p.keyboard.press('Enter'); p.wait_for_timeout(700)
    assert punto(p, 'origin') and lista(p, 'origin').count() == 0
    assert not consultas('lugares') and not consultas('sugerencias'), f'las coordenadas no pasan por proveedores: {registro()}'
    p.evaluate("Spevb.Planner.poner('destination', { id: null, lat: 4.1450, lng: -73.6240, nombre: 'Favorito Trabajo', direccion: null, fuente: 'Favorito', confirmado: true })")
    p.wait_for_timeout(700)
    assert not registro() and lista(p, 'destination').count() == 0
    # punto elegido en el mapa: usa geocodificación inversa (otra operación), nunca sugerencias
    p.click('#pickDestination'); p.wait_for_timeout(200)
    p.evaluate("(() => { const m = Spevb.maps.map; const q = m.toLatLng({ x: m.getSize().x * 0.6, y: m.getSize().y * 0.5 }); Spevb.Planner.poner('destination', { id: null, lat: q[0], lng: q[1], nombre: 'Punto en mapa', direccion: null, fuente: 'Selección manual en mapa', confirmado: false }); })()")
    p.wait_for_timeout(600)
    assert not consultas('sugerencias')
    s.limpio('coordenadas'); s.cerrar()


@prueba
def usar_mi_ubicacion_confirma_el_origen_y_no_abre_sugerencias(nav):
    s, p = sesion(nav, geolocation=B.CENTRO, permissions=['geolocation'])
    p.fill('#tripOrigin', 'Cal'); p.wait_for_timeout(100)
    p.click('#useMyLocation')
    B.esperar(p, "!!Spevb.Planner.puntos().origen", 30000)
    p.wait_for_timeout(800)
    n = len(consultas('sugerencias'))
    p.wait_for_timeout(900)
    assert lista(p, 'origin').count() == 0 and aria(p, 'origin') == 'false'
    assert len(consultas('sugerencias')) == n, 'tras confirmar con el GPS no hay más consultas'
    s.limpio('gps'); s.cerrar()


@prueba
def seleccionar_y_cambiar_enseguida_al_otro_campo_no_reabre_el_primero(nav):
    s, p = sesion(nav)
    p.fill('#tripOrigin', 'Carrera 22'); esperar_lista(p, 'origin')
    lista(p, 'origin').first.click()
    p.fill('#tripDestination', 'Calle 10'); esperar_lista(p, 'destination')
    p.wait_for_timeout(700)
    assert lista(p, 'origin').count() == 0 and punto(p, 'origin'), 'el origen sigue confirmado y su lista cerrada'
    assert lista(p, 'destination').count() >= 1
    s.limpio('cambio de campo'); s.cerrar()


# ------------------------------------------------------------------------------------------------ errores tipados
@prueba
def cada_estado_de_error_dice_algo_distinto_y_no_es_no_hay_direcciones(nav):
    s, p = sesion(nav)
    esperado = {'tiempo extra': 'tard', 'redca': 'conexi', 'cuota': 'límite', 'clave': 'configur', 'pausa': 'pausa', 'fuera de la ciudad': 'fuera de Villavicencio',
                'nada que ver': 'No encontr', 'basura json': 'no pude leer'}
    textos = {}
    for consulta, fragmento in esperado.items():
        p.fill('#tripOrigin', consulta)
        B.esperar(p, "document.querySelector('#originResults .search-hint, #originResults .address-warning, #originResults .search-error') !== null", 6000)
        t = p.locator('#originResults').inner_text().lower()
        textos[consulta] = t
        assert fragmento.lower() in t, f'«{consulta}»: se esperaba «{fragmento}» y salió «{t}»'
        assert p.locator('#originResults [role="status"], #originResults [role="alert"]').count() >= 1, f'«{consulta}»: el aviso debe anunciarse'
    assert len(set(textos.values())) == len(textos), f'los estados deben diferenciarse: {textos}'
    s.limpio('estados'); s.cerrar()


@prueba
def la_eleccion_fallida_no_se_sustituye_en_silencio_y_ofrece_reintentar(nav):
    s, p = sesion(nav)
    p.fill('#tripOrigin', 'goofallo carrera 22'); esperar_lista(p, 'origin')
    lista(p, 'origin').first.click()
    B.esperar(p, "document.querySelector('#originResults [data-accion=\"reintentar\"], #originResults .search-error') !== null", 6000)
    assert punto(p, 'origin') is None, 'no se ubica nada si el detalle del lugar elegido falló'
    t = p.locator('#originResults').inner_text().lower()
    assert 'reintent' in t, f'debe poder reintentar o elegir otra: {t}'
    assert p.input_value('#tripOrigin').lower().startswith('goofallo'), 'el texto elegido se conserva para reintentar'
    assert p.locator('#originResults .autosuggest-option').count() >= 0
    s.limpio('elección fallida'); s.cerrar()


@prueba
def un_lugar_de_otro_proveedor_no_reemplaza_la_sugerencia_elegida(nav):
    """Un backend viejo o defectuoso devuelve lugares[0] de ArcGIS para una sugerencia de Google: el navegador no debe aceptarlo."""
    s, p = sesion(nav)
    p.fill('#tripOrigin', 'goosust carrera 22'); esperar_lista(p, 'origin')
    lista(p, 'origin').first.click()
    p.wait_for_timeout(1200)
    n = punto(p, 'origin')
    assert n is None or 'OTRO LUGAR' not in n, f'se aceptó un lugar de otro proveedor: {n}'
    s.limpio('sustitución'); s.cerrar()


@prueba
def elegir_una_sugerencia_de_google_resuelve_ese_identificador_y_ubica_el_punto(nav):
    s, p = sesion(nav)
    p.fill('#tripOrigin', 'goo carrera 22'); esperar_lista(p, 'origin')
    lista(p, 'origin').first.click()
    B.esperar(p, "!!Spevb.Planner.puntos().origen", 6000)
    reg = [r for r in consultas('lugares') if r['magicKey']]
    assert reg and reg[-1]['magicKey'].startswith('g:ok'), f'se resuelve ESE identificador: {reg}'
    assert lista(p, 'origin').count() == 0 and aria(p, 'origin') == 'false'
    s.limpio('elegir google'); s.cerrar()


# ------------------------------------------------------------------------------------------------ sesión de Places y composición
@prueba
def cada_campo_tiene_su_propia_sesion_por_busqueda_y_se_descarta_al_confirmar(nav):
    s, p = sesion(nav)
    p.fill('#tripOrigin', 'goo carrera 22'); esperar_lista(p, 'origin')
    p.fill('#tripOrigin', 'goo carrera 22 con'); p.wait_for_timeout(900)
    p.fill('#tripDestination', 'goo calle 10'); esperar_lista(p, 'destination')
    sug = consultas('sugerencias')
    por_texto = {r['q']: r['sesion'] for r in sug}
    assert all(por_texto.values()), f'toda consulta lleva sesion=: {sug}'
    assert por_texto['goo carrera 22'] == por_texto['goo carrera 22 con'], 'una misma búsqueda comparte su sesión'
    assert por_texto['goo calle 10'] != por_texto['goo carrera 22'], 'A y B no comparten sesión'
    lista(p, 'origin').first.click() if lista(p, 'origin').count() else None
    p.fill('#tripOrigin', 'goo carrera 22'); esperar_lista(p, 'origin')
    lista(p, 'origin').first.click()
    B.esperar(p, "!!Spevb.Planner.puntos().origen", 6000)
    elegidas = [r for r in consultas('lugares') if r['magicKey']]
    assert elegidas and elegidas[-1]['sesion'], 'el detalle del lugar elegido usa la sesión de esa búsqueda'
    ultima = elegidas[-1]['sesion']
    p.click('#tripOrigin'); p.keyboard.press('End'); p.keyboard.type(' x'); esperar_lista(p, 'origin')
    nueva = [r for r in consultas('sugerencias') if r['q'].endswith(' x')][-1]['sesion']
    assert nueva and nueva != ultima, 'tras confirmar, una búsqueda nueva usa otra sesión'
    s.limpio('sesiones'); s.cerrar()


@prueba
def la_composicion_de_texto_no_consulta_hasta_terminar(nav):
    s, p = sesion(nav)
    p.focus('#tripOrigin')
    p.evaluate("""() => { const i = document.getElementById('tripOrigin');
        i.dispatchEvent(new CompositionEvent('compositionstart', { bubbles: true }));
        for (const t of ['Ca', 'Car', 'Carr']) { i.value = t; i.dispatchEvent(new InputEvent('input', { bubbles: true, isComposing: true })); } }""")
    p.wait_for_timeout(1100)
    assert not consultas('sugerencias'), f'no se consulta durante una composición incompleta: {consultas()}'
    p.evaluate("""() => { const i = document.getElementById('tripOrigin'); i.value = 'Carrera';
        i.dispatchEvent(new CompositionEvent('compositionend', { bubbles: true, data: 'Carrera' }));
        i.dispatchEvent(new InputEvent('input', { bubbles: true, isComposing: false })); }""")
    esperar_lista(p, 'origin')
    assert len(consultas('sugerencias')) == 1
    s.limpio('composición'); s.cerrar()


@prueba
def el_final_de_una_busqueda_antigua_no_cambia_el_boton_de_la_nueva(nav):
    s, p = sesion(nav)
    ret = Retenidas(s.ctx, '**/api/lugares?*')
    p.fill('#tripOrigin', 'Calle 38 con carrera 22'); p.click('#searchOrigin'); p.wait_for_timeout(500)
    assert p.is_disabled('#searchOrigin') and len(ret.pend) == 1
    p.fill('#tripOrigin', 'Calle 40 con carrera 23'); p.focus('#tripOrigin'); p.keyboard.press('Enter'); p.wait_for_timeout(500)
    ret.liberar(0); p.wait_for_timeout(700)
    assert p.is_disabled('#searchOrigin'), 'el finally de la búsqueda anterior no debe habilitar el botón de la que sigue en curso'
    ret.soltar(); p.wait_for_timeout(900)
    assert not p.is_disabled('#searchOrigin')
    s.limpio('finally'); s.cerrar()


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
                traceback.print_exc(limit=2)
        nav.close()
    print(f'\n{len(PRUEBAS) if not filtro else "?"} pruebas de búsqueda y confirmación; fallos: {len(fallos)} {fallos}')
    return 1 if fallos else 0


if __name__ == '__main__':
    sys.exit(correr(sys.argv[1] if len(sys.argv) > 1 else None))
