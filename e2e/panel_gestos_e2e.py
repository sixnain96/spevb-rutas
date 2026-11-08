"""Interacción MAPA ↔ PANEL: cada gesto debe responder solo a lo que le corresponde.

Defectos que reproducen (antes de la corrección): (1) al enfocar un campo el panel pasaba a «full» (94 %) y NO volvía al elegir A, dejando solo
una franja de mapa; (2) el arrastre del panel escuchaba pointermove/pointerup en `window` sin comprobar el puntero ni el botón: un puntero ajeno
o un arrastre sin pointerup (cancelado) seguían moviendo el panel al pasar el ratón por el mapa; (3) un gesto cancelado se trataba como un
lanzamiento con velocidad; (4) «Ahora escribe B» subía el panel sin que la persona lo pidiera de forma deliberada.

Navegador real (Chromium/Edge) contra el servidor simulado; mapa Leaflet real para los arrastres del mapa y Google simulado para el layout.
Uso: SEVB_E2E_URL=http://127.0.0.1:18080/ python e2e/panel_gestos_e2e.py [filtro]
"""
import os
import sys
import traceback

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import sevb_e2e as B  # noqa: E402
import busqueda_e2e as BU  # noqa: E402
import mapa_adaptadores_e2e as M  # noqa: E402
from playwright.sync_api import sync_playwright  # noqa: E402

PUERTO_GOOGLE = int(os.environ.get('SEVB_E2E_PUERTO_GESTOS', '18126'))
PRUEBAS = []
ESCRITORIO_ESTRECHO = {'viewport': {'width': 500, 'height': 800}}               # PC con la ventana estrecha: diseño compacto, pero ratón y teclado
MOVIL = {'viewport': {'width': 390, 'height': 844}, 'is_mobile': True, 'has_touch': True}
CONTAR = """(() => { const n = { pointermove: 0, pointerup: 0, pointercancel: 0, pointerdown: 0 }; const o = window.addEventListener;
  window.addEventListener = function (t, ...r) { if (t in n) n[t]++; return o.call(this, t, ...r); }; window.__oyentes = n; })();"""


def prueba(f):
    PRUEBAS.append(f)
    return f


def abrir(nav, **opciones):
    B.URL = M.URL_LEAFLET
    s = B.Sesion(nav, **opciones)
    s.ctx.add_init_script(CONTAR)
    p = s.abrir()
    BU.limpiar_log()
    return s, p


def panel(p):
    p.wait_for_timeout(450)
    return p.evaluate("""() => { const s = document.getElementById('sheet'), r = s.getBoundingClientRect();
        return { estado: s.dataset.state, arriba: Math.round(r.top), alto: innerHeight, visible: innerHeight - Math.round(r.top), transform: s.style.transform,
                 oculto: document.body.classList.contains('panel-oculto'), foco: document.activeElement && document.activeElement.tagName }; }""")


def centro(p):
    return p.evaluate('Spevb.maps.map.getCenter()')


def arrastrar_mapa(p, x0, y0, x1, y1, pasos=14):
    p.mouse.move(x0, y0); p.mouse.down()
    p.mouse.move(x1, y1, steps=pasos)
    p.mouse.up()
    p.wait_for_timeout(200)


def caja(p, sel):
    return p.evaluate("(s) => { const r = document.querySelector(s).getBoundingClientRect(); return { x: r.x, y: r.y, w: r.width, h: r.height }; }", sel)


def toque_arrastrar(p, x, y0, y1, pasos=10, soltar=True):
    """Arrastre con un dedo REAL (eventos táctiles de CDP, no de ratón): es lo que hace una persona en Android/iPhone."""
    cdp = p.context.new_cdp_session(p)
    cdp.send('Input.dispatchTouchEvent', {'type': 'touchStart', 'touchPoints': [{'x': x, 'y': y0, 'id': 1}]})
    for i in range(1, pasos + 1):
        cdp.send('Input.dispatchTouchEvent', {'type': 'touchMove', 'touchPoints': [{'x': x, 'y': y0 + (y1 - y0) * i / pasos, 'id': 1}]})
        p.wait_for_timeout(16)
    if soltar:
        cdp.send('Input.dispatchTouchEvent', {'type': 'touchEnd', 'touchPoints': []})
    p.wait_for_timeout(120)


@prueba
def con_el_dedo_se_puede_agrandar_y_achicar_el_panel_tambien_con_el_buscador_activo(nav):
    s, p = abrir(nav, **MOVIL)
    h = caja(p, '#sheetHandle'); x, y = h['x'] + h['w'] / 2, h['y'] + h['h'] / 2
    antes = panel(p)
    toque_arrastrar(p, x, y, y - 320)                      # arrastrar el asa hacia arriba con el dedo
    a = panel(p)
    assert a['visible'] > antes['visible'] + 150, f'un dedo arrastrando el asa hacia arriba agranda el panel: {antes} -> {a}'
    h = caja(p, '#sheetHandle'); x, y = h['x'] + h['w'] / 2, h['y'] + h['h'] / 2
    toque_arrastrar(p, x, y, y + 300)
    b = panel(p)
    assert b['visible'] < a['visible'] - 150, f'y hacia abajo lo achica: {a} -> {b}'
    # con el buscador activo (campo enfocado) el panel sigue obedeciendo al dedo
    p.tap('#tripOrigin'); p.wait_for_timeout(500)
    c = panel(p)
    h = caja(p, '#sheetHandle'); x, y = h['x'] + h['w'] / 2, h['y'] + h['h'] / 2
    toque_arrastrar(p, x, y, y - 400)
    d = panel(p)
    assert d['estado'] == 'full' or d['visible'] > c['visible'], f'con el buscador activo se puede agrandar con el dedo: {c} -> {d}'
    # también desde la cabecera (donde está el logotipo)
    cab = caja(p, '#sheet .sheet-head')
    libre = p.evaluate("""() => { const c = document.querySelector('#sheet .sheet-head').getBoundingClientRect(), y = c.top + c.height / 2;
        for (let x = c.left + 10; x < c.right - 10; x += 6) { const e = document.elementFromPoint(x, y); if (e && !e.closest('a, button')) return x; } return null; }""")
    assert libre, 'hay una zona libre de la cabecera para arrastrar'
    toque_arrastrar(p, libre, cab['y'] + cab['h'] / 2, cab['y'] + cab['h'] / 2 + 300)
    assert panel(p)['visible'] < d['visible'] - 100, 'arrastrar la cabecera hacia abajo con el dedo achica el panel'
    s.limpio('toque real'); s.cerrar()


@prueba
def el_zoom_de_pagina_con_los_dedos_no_se_confunde_con_el_teclado(nav):
    """En el video de Android el usuario hizo zoom de página (pellizco): el visual viewport se encoge pero NO hay teclado. Eso se leía como «teclado
    de 400 px», levantaba el panel (--kb) y recalculaba sus alturas con un alto falso: el panel quedaba pequeño y no se podía agrandar."""
    s, p = abrir(nav, **MOVIL)
    antes = panel(p); kb0 = p.evaluate("getComputedStyle(document.documentElement).getPropertyValue('--kb')")
    p.evaluate("""() => { const vv = window.visualViewport;
        Object.defineProperty(vv, 'scale', { configurable: true, get: () => 2.5 });
        Object.defineProperty(vv, 'height', { configurable: true, get: () => innerHeight / 2.5 });
        Object.defineProperty(vv, 'offsetTop', { configurable: true, get: () => 120 });
        vv.dispatchEvent(new Event('resize')); vv.dispatchEvent(new Event('scroll')); }""")
    p.wait_for_timeout(500)
    zoom = panel(p)
    kb = p.evaluate("getComputedStyle(document.documentElement).getPropertyValue('--kb')")
    assert kb.strip() in ('0px', ''), f'el zoom de página no es un teclado: --kb={kb}'
    assert zoom['estado'] == antes['estado'] and abs(zoom['visible'] - antes['visible']) <= 2, f'el panel no cambia de tamaño por un zoom de página: {antes} -> {zoom}'
    # y con ese zoom el panel sigue obedeciendo: se puede agrandar con el asa
    p.locator('#sheetHandle').click(force=True); p.wait_for_timeout(500)
    assert panel(p)['estado'] != antes['estado'], 'con zoom de página el asa sigue funcionando'
    s.limpio('zoom de página'); s.cerrar()


@prueba
def pellizcar_sobre_el_mapa_no_hace_zoom_de_pagina(nav):
    s, p = abrir(nav, **MOVIL)
    ta = p.evaluate("[getComputedStyle(document.getElementById('map')).touchAction, getComputedStyle(document.querySelector('.map-area')).touchAction]")
    assert 'none' in ta[1] or 'pan-x' in ta[1], f'el área del mapa no deja que el navegador haga zoom de página con el pellizco (el mapa lo maneja por su cuenta): {ta}'
    s.limpio('touch-action'); s.cerrar()


@prueba
def escritorio_estrecho_al_confirmar_A_el_panel_no_queda_casi_completo(nav):
    s, p = abrir(nav, **ESCRITORIO_ESTRECHO)
    antes = panel(p)
    BU.confirmar_por_sugerencia(p, 'origin')
    despues = panel(p)
    assert despues['estado'] != 'full' and despues['visible'] <= 0.6 * despues['alto'], f'con solo A el panel no debe ocupar casi toda la pantalla: {antes} -> {despues}'
    assert despues['estado'] == antes['estado'], f'al terminar de editar vuelve al estado que la persona tenía: {antes} -> {despues}'
    s.limpio('A en escritorio estrecho'); s.cerrar()


@prueba
def movil_al_confirmar_A_el_panel_recupera_su_altura_y_B_sigue_visible(nav):
    s, p = abrir(nav, **MOVIL)
    antes = panel(p)
    BU.confirmar_por_sugerencia(p, 'origin')
    despues = panel(p)
    assert despues['estado'] != 'full', f'seleccionar A no maximiza el panel: {despues}'
    b = caja(p, '#tripDestination')
    assert b['y'] + b['h'] <= despues['alto'] and b['y'] >= despues['arriba'] - 1, f'con A puesto el campo B queda visible dentro del panel: {b} {despues}'
    assert despues['arriba'] >= 0.3 * despues['alto'], f'queda una superficie útil de mapa: {despues}'
    s.limpio('A en móvil'); s.cerrar()


@prueba
def la_ampliacion_manual_deliberada_se_respeta_al_confirmar(nav):
    s, p = abrir(nav, **MOVIL)
    p.locator('#sheetHandle').click(); p.locator('#sheetHandle').click()          # peek -> half -> full a propósito
    assert panel(p)['estado'] == 'full'
    BU.confirmar_por_sugerencia(p, 'origin')
    assert panel(p)['estado'] in ('full', 'peek'), 'una ampliación manual no se revierte a escondidas ni salta a otro estado'
    s.limpio('ampliación manual'); s.cerrar()


@prueba
def ahora_escribe_B_orienta_sin_enfocar_ni_maximizar(nav):
    s, p = abrir(nav, **MOVIL)
    BU.confirmar_por_sugerencia(p, 'origin')
    B.esperar(p, "!!document.querySelector('.pestana-siguiente.visible')", 6000)
    antes = panel(p)
    p.evaluate("document.activeElement && document.activeElement.blur()")
    p.locator('.pestana-siguiente').click()
    despues = panel(p)
    assert despues['foco'] != 'INPUT', f'la pestañita no abre el teclado ni enfoca un campo: {despues}'
    assert despues['estado'] != 'full', f'ni maximiza el panel: {antes} -> {despues}'
    s.limpio('Ahora escribe B'); s.cerrar()


@prueba
def arrastrar_el_mapa_no_cambia_ni_el_estado_ni_la_altura_del_panel(nav):
    for nombre, op in (('escritorio estrecho', ESCRITORIO_ESTRECHO), ('móvil táctil', MOVIL)):
        s, p = abrir(nav, **op)
        BU.confirmar_por_sugerencia(p, 'origin')
        p.mouse.click(250, 150)                                      # clic en el mapa: suelta el foco del campo
        antes = panel(p); c0 = centro(p)
        mapa = caja(p, '#map')
        cx, cy = mapa['x'] + mapa['w'] / 2, mapa['y'] + 120
        for dx, dy in ((120, 0), (-120, 0), (0, 90), (0, -60), (90, 90)):
            arrastrar_mapa(p, cx, cy, cx + dx, cy + dy)
        # un arrastre que empieza en el mapa y termina con el cursor sobre el panel sigue siendo del mapa
        a = caja(p, '#sheet')
        arrastrar_mapa(p, cx, cy, cx, a['y'] + 40)
        despues = panel(p)
        assert (despues['estado'], despues['arriba'], despues['transform']) == (antes['estado'], antes['arriba'], antes['transform']), f'{nombre}: arrastrar el mapa movió el panel: {antes} -> {despues}'
        assert centro(p) != c0, f'{nombre}: el mapa sí se mueve'
        s.limpio('arrastre del mapa ' + nombre); s.cerrar()


@prueba
def arrastrar_el_asa_mueve_solo_el_panel(nav):
    s, p = abrir(nav, **ESCRITORIO_ESTRECHO)
    c0 = centro(p); antes = panel(p)
    h = caja(p, '#sheetHandle')
    p.mouse.move(h['x'] + h['w'] / 2, h['y'] + h['h'] / 2); p.mouse.down(); p.mouse.move(h['x'] + h['w'] / 2, h['y'] - 260, steps=12); p.mouse.up()
    despues = panel(p)
    assert despues['estado'] != antes['estado'] and despues['visible'] > antes['visible'], f'el asa sube el panel: {antes} -> {despues}'
    assert centro(p) == c0, 'arrastrar el panel no desplaza la cámara del mapa'
    s.limpio('asa'); s.cerrar()


@prueba
def un_puntero_ajeno_o_un_gesto_cancelado_no_mueven_el_panel(nav):
    s, p = abrir(nav, **ESCRITORIO_ESTRECHO)
    h = caja(p, '#sheetHandle')
    x, y = h['x'] + h['w'] / 2, h['y'] + h['h'] / 2
    # 1) otro puntero (otro dedo / lápiz) se mueve mientras el ratón arrastra: no debe actualizar el arrastre
    p.evaluate("window.addEventListener('pointerdown', (e) => { window.__pid = e.pointerId; }, true)")
    p.mouse.move(x, y); p.mouse.down(); p.mouse.move(x, y - 12, steps=3)
    t0 = p.evaluate("document.getElementById('sheet').style.transform")
    p.evaluate("window.dispatchEvent(new PointerEvent('pointermove', { pointerId: 777, pointerType: 'touch', clientX: 10, clientY: 5, bubbles: true }))")
    assert p.evaluate("document.getElementById('sheet').style.transform") == t0, 'un puntero ajeno no actualiza el arrastre del panel'
    # 2) el gesto se cancela (el sistema se queda con el puntero): se limpia y se vuelve a una posición coherente, sin «lanzamiento»
    p.evaluate("document.getElementById('sheetHandle').dispatchEvent(new PointerEvent('pointercancel', { pointerId: window.__pid, pointerType: 'mouse', bubbles: true }))")
    quieto = panel(p)
    assert quieto['estado'] == 'peek' and quieto['transform'] == panel(p)['transform'], f'tras cancelar vuelve al estado anterior: {quieto}'
    p.mouse.up()
    quieto['motivo_tras_up'] = p.evaluate("document.getElementById('sheet').dataset.motivo")
    # 3) y ya no queda un arrastre activo: mover el ratón por el mapa y el panel no cambia nada
    for yy in (300, 500, 700, 760):
        p.mouse.move(250, yy)
    despues = panel(p)
    assert (despues['estado'], despues['transform']) == (quieto['estado'], quieto['transform']), f'un arrastre cancelado no sigue activo: {quieto} -> {despues} motivo={p.evaluate("document.getElementById('sheet').dataset.motivo")}'
    s.limpio('gesto cancelado'); s.cerrar()


@prueba
def el_clic_posterior_a_un_arrastre_no_vuelve_a_cambiar_la_altura(nav):
    s, p = abrir(nav, **ESCRITORIO_ESTRECHO)
    h = caja(p, '#sheetHandle'); x, y = h['x'] + h['w'] / 2, h['y'] + h['h'] / 2
    p.mouse.move(x, y); p.mouse.down(); p.mouse.move(x, y - 150, steps=10); p.mouse.up()
    e1 = panel(p)
    p.wait_for_timeout(600)
    assert panel(p)['estado'] == e1['estado'], f'el clic que sigue al arrastre no cicla el estado: {e1}'
    s.limpio('clic tras arrastre'); s.cerrar()


@prueba
def repetir_los_gestos_no_acumula_oyentes(nav):
    s, p = abrir(nav, **ESCRITORIO_ESTRECHO)
    base = p.evaluate('({ ...window.__oyentes })')
    h = caja(p, '#sheetHandle'); x, y = h['x'] + h['w'] / 2, h['y'] + h['h'] / 2
    for i in range(20):
        p.mouse.move(x, y); p.mouse.down(); p.mouse.move(x, y - 40, steps=3); p.mouse.up()
        p.locator('#panelHide').click(); p.locator('#panelShow').click()
        h = caja(p, '#sheetHandle'); x, y = h['x'] + h['w'] / 2, h['y'] + h['h'] / 2
    fin = p.evaluate('({ ...window.__oyentes })')
    assert fin == base, f'repetir 20 veces no registra oyentes nuevos en window: {base} -> {fin}'
    s.limpio('oyentes'); s.cerrar()


@prueba
def teclado_visible_expande_para_escribir_y_al_terminar_se_recupera(nav):
    s, p = abrir(nav, **MOVIL)
    antes = panel(p)
    p.locator('#tripOrigin').click()
    p.evaluate("""() => { const vv = window.visualViewport; Object.defineProperty(vv, 'height', { configurable: true, get: () => innerHeight - 320 });
        Object.defineProperty(vv, 'offsetTop', { configurable: true, get: () => 0 }); vv.dispatchEvent(new Event('resize')); }""")
    p.wait_for_timeout(500)
    escribiendo = panel(p)
    p.fill('#tripOrigin', 'Carrera 22'); BU.esperar_lista(p, 'origin'); BU.lista(p, 'origin').first.click()
    p.evaluate("""() => { const vv = window.visualViewport; delete vv.height; delete vv.offsetTop; vv.dispatchEvent(new Event('resize')); }""")
    B.esperar(p, "!!Spevb.Planner.puntos().origen", 6000)
    despues = panel(p)
    assert despues['estado'] == antes['estado'], f'cerrado el teclado y confirmado el punto, el panel vuelve a {antes["estado"]} (estaba {escribiendo["estado"]} mientras se escribía): {despues}'
    s.limpio('teclado'); s.cerrar()


@prueba
def la_rueda_sobre_el_mapa_no_toca_el_panel_y_sobre_los_resultados_solo_los_desplaza(nav):
    s, p = abrir(nav, **ESCRITORIO_ESTRECHO)
    B.planificar(p)
    antes = panel(p)
    mapa = caja(p, '#map')
    p.mouse.move(mapa['x'] + mapa['w'] / 2, mapa['y'] + 100); p.mouse.wheel(0, -300); p.wait_for_timeout(300)
    assert panel(p)['estado'] == antes['estado'], 'la rueda sobre el mapa (zoom) no cambia el panel'
    r = caja(p, '#sheet .sheet-body')
    p.mouse.move(r['x'] + r['w'] / 2, r['y'] + r['h'] / 2); p.mouse.wheel(0, 200); p.wait_for_timeout(300)
    assert panel(p)['estado'] == antes['estado'] and panel(p)['arriba'] == antes['arriba'], 'la rueda sobre los resultados desplaza la lista, no el panel'
    s.limpio('rueda'); s.cerrar()


@prueba
def redimensionar_la_ventana_conserva_un_panel_coherente(nav):
    s, p = abrir(nav, **ESCRITORIO_ESTRECHO)
    BU.confirmar_por_sugerencia(p, 'origin')
    for ancho, alto in ((1280, 800), (700, 800), (500, 800), (420, 700), (500, 800)):
        p.set_viewport_size({'width': ancho, 'height': alto}); p.wait_for_timeout(300)
        m = panel(p)
        assert m['estado'] != 'full' or ancho <= 559, f'{ancho}×{alto}: {m}'
        assert p.evaluate('document.documentElement.scrollWidth - innerWidth') <= 1
    s.limpio('resize'); s.cerrar()


def correr(filtro=None):
    fallos = []
    try:
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
    print(f'\n{len(PRUEBAS) if not filtro else "?"} pruebas de gestos mapa/panel; fallos: {len(fallos)} {fallos}')
    return 1 if fallos else 0


if __name__ == '__main__':
    sys.exit(correr(sys.argv[1] if len(sys.argv) > 1 else None))
