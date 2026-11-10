"""Pruebas de experiencia en CELULARES con una matriz de modelos (iPhone, Android, plegable, tableta y apaisados).

Qué comprueban, en cada modelo:
  · Teclado estilo iPhone (la página NO se encoge; solo se reduce el «visual viewport»): el panel queda apoyado sobre el teclado, el campo que se
    escribe se ve completo, la letra es de 16 px (si no, iOS hace zoom) y la primera sugerencia queda a la vista. Al cerrarlo, todo vuelve.
  · Teclado estilo Android (la página sí se encoge): el campo sigue a la vista.
  · Campos cómodos: las herramientas van DEBAJO del campo (no tapan su centro) y queda ancho suficiente para escribir.
  · Sin choques: ningún elemento flotante se monta sobre otro, no hay desborde horizontal, y «Iniciar viaje» se ve de inmediato tras buscar.
El teclado de iPhone se simula con un `visualViewport` falso (no hay iOS real en esta máquina): reproduce lo que cambia en iOS —alto visible menor y
desplazamiento del visual viewport— y deja la altura de la página intacta.
Uso: SEVB_E2E_URL=http://127.0.0.1:18080/ python e2e/movil_e2e.py [filtro]     (lo normal es e2e/run_e2e.py)
"""
import os
import sys
import traceback

if hasattr(sys.stdout, 'reconfigure'):
    sys.stdout.reconfigure(encoding='utf-8', errors='replace')      # la consola de Windows (cp1252) no admite todos los símbolos de los mensajes

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import sevb_e2e as B  # noqa: E402
import viaje_e2e as V  # noqa: E402
from playwright.sync_api import sync_playwright  # noqa: E402

PRUEBAS = []
# (nombre, ancho, alto, alto del teclado en ese modelo)
MODELOS = [('iPhone SE 320x568', 320, 568, 260), ('Galaxy Fold plegado 280x653', 280, 653, 300), ('Android pequeño 360x640', 360, 640, 280),
           ('iPhone 13 390x844', 390, 844, 336), ('Pixel 5 393x851', 393, 851, 330), ('Galaxy S20 Ultra 412x915', 412, 915, 340),
           ('iPhone 14 Pro Max 430x932', 430, 932, 346), ('iPad mini 768x1024', 768, 1024, 320)]
APAISADOS = [('iPhone 13 apaisado 844x390', 844, 390), ('Android apaisado 740x360', 740, 360), ('iPhone SE apaisado 568x320', 568, 320)]
BUSQUEDA = 'Avenida 40 con carrera 33'

TECLADO_IOS = """
(() => {
  const vv = new EventTarget(); const est = { h: window.innerHeight, top: 0 };
  Object.defineProperties(vv, { height: { get: () => est.h }, width: { get: () => window.innerWidth }, offsetTop: { get: () => est.top }, offsetLeft: { get: () => 0 },
                                scale: { get: () => 1 }, pageTop: { get: () => est.top }, pageLeft: { get: () => 0 } });
  Object.defineProperty(window, 'visualViewport', { configurable: true, get: () => vv });
  window.__teclado = (kb, top = 0) => { est.h = window.innerHeight - kb - top; est.top = top; vv.dispatchEvent(new Event('resize')); vv.dispatchEvent(new Event('scroll')); };
})();
"""

JS_FLOTANTES = """() => {
  const vis = (e) => { const r = e.getBoundingClientRect(), s = getComputedStyle(e); return r.width > 2 && r.height > 2 && s.visibility !== 'hidden' && s.display !== 'none' && +s.opacity > 0.05; };
  const nombre = (e) => (e.id ? '#' + e.id : '') + (e.className && typeof e.className === 'string' ? '.' + e.className.trim().split(/\\s+/).slice(0, 2).join('.') : '') || e.tagName;
  // «Visible» de verdad: no recortado por el panel ni tapado por otro elemento (si ningún punto del elemento responde a él, no se ve).
  const golpea = (e) => {
    if (getComputedStyle(e).pointerEvents === 'none') return true;
    const r = e.getBoundingClientRect();
    return [[.5, .5], [.2, .2], [.8, .2], [.2, .8], [.8, .8]].some(([fx, fy]) => { const x = r.left + r.width * fx, y = r.top + r.height * fy;
      if (x < 0 || y < 0 || x >= innerWidth || y >= innerHeight) return false; const h = document.elementFromPoint(x, y); return !!h && (h === e || e.contains(h)); });
  };
  const cand = [...document.querySelectorAll('body *')].filter((e) => {
    if (!vis(e) || !golpea(e)) return false;
    if (e.closest('.leaflet-pane, .leaflet-tile-container, .leaflet-marker-pane, .leaflet-overlay-pane, svg, canvas')) return false;
    const s = getComputedStyle(e);
    return s.position === 'fixed' || e.matches('.leaflet-control, .bus-panel, #sheet') || (s.position === 'absolute' && e.closest('.app') && e.matches('button, .center-pin, .bus-panel, .map-actions *'));
  });
  const uniq = cand.filter((e) => !cand.some((o) => o !== e && o.contains(e) && !e.matches('button, a, .leaflet-control')));
  const out = [];
  for (let i = 0; i < uniq.length; i++) for (let j = i + 1; j < uniq.length; j++) {
    const a = uniq[i], b = uniq[j];
    if (a.contains(b) || b.contains(a)) continue;
    const ra = a.getBoundingClientRect(), rb = b.getBoundingClientRect();
    const w = Math.min(ra.right, rb.right) - Math.max(ra.left, rb.left), h = Math.min(ra.bottom, rb.bottom) - Math.max(ra.top, rb.top);
    if (w > 4 && h > 4) out.push(nombre(a) + ' × ' + nombre(b) + ' (' + Math.round(w) + '×' + Math.round(h) + ')');
  }
  return out;
}"""

# Solapes que son parte del diseño y no un defecto: la atribución de Leaflet queda bajo los botones del mapa y el panel desliza sobre los controles.
# 4.14: la tarjeta del viaje vive en <body> (no dentro de .app) para poder verse sobre el menú; el contenedor .app abarca toda la pantalla por diseño.
PERMITIDOS = ('leaflet-control-attribution', '#sheet × ', ' × #sheet', '.app × ', ' × .app')


def prueba(f):
    PRUEBAS.append(f)
    return f


def abrir(nav, w, h, ios=False, dsf=1):
    s = V.sesion(nav, viewport={'width': w, 'height': h}, is_mobile=True, has_touch=True, device_scale_factor=dsf)
    if ios:
        s.ctx.add_init_script(TECLADO_IOS)
    return s, s.abrir()


def medir_campo(p, campo):
    return p.evaluate("""(id) => { const i = document.getElementById(id), r = i.getBoundingClientRect(), sh = document.getElementById('sheet').getBoundingClientRect();
        const kb = parseInt(getComputedStyle(document.documentElement).getPropertyValue('--kb') || '0'), vv = window.visualViewport;
        return { top: r.top, bottom: r.bottom, sheetTop: sh.top, sheetBottom: sh.bottom, kb, h: innerHeight, vvBottom: vv ? vv.offsetTop + vv.height : innerHeight,
                 fuente: parseFloat(getComputedStyle(i).fontSize), foco: document.activeElement === i, ancho: r.width }; }""", campo)


def esperar_sugerencias(p, lista, que=''):
    """Espera (hasta 8 s) a que aparezca la lista y deja que termine de acomodarse al espacio visible."""
    try:
        B.esperar(p, f"document.getElementById('{lista}').classList.contains('suggestion-open') && !!document.getElementById('{lista}').querySelector('.autosuggest-option, .place-option')", 8000)
    except AssertionError:
        estado = p.evaluate(f"({{ clase: document.getElementById('{lista}').className, hijos: document.getElementById('{lista}').childElementCount, texto: (document.activeElement || {{}}).value, foco: (document.activeElement || {{}}).id }})")
        raise AssertionError(f'{que}: no aparecieron sugerencias en 8 s ({estado})')
    p.wait_for_timeout(500)


def medir_sugerencia(p, lista):
    return p.evaluate("""(id) => { const l = document.getElementById(id), o = l.querySelector('.autosuggest-option, .place-option'), vv = window.visualViewport;
        return { abierta: l.classList.contains('suggestion-open'), lista: l.getBoundingClientRect().bottom, opcion: o ? o.getBoundingClientRect().bottom : null,
                 borde: vv ? vv.offsetTop + vv.height : innerHeight }; }""", lista)


# ---------------------------------------------------------------------------------------------- teclado estilo iPhone
@prueba
def teclado_estilo_iphone_en_todos_los_modelos_el_panel_se_apoya_en_el_teclado_y_el_campo_se_ve(nav):
    for nombre, w, h, kb in MODELOS:
        for campo, lista in (('tripOrigin', 'originResults'), ('tripDestination', 'destinationResults')):
            s, p = abrir(nav, w, h, ios=True)
            p.tap('#' + campo)
            p.evaluate(f'__teclado({kb})')
            p.wait_for_timeout(1000)
            m = medir_campo(p, campo)
            borde = m['h'] - kb
            assert abs(m['kb'] - kb) <= 2, f'{nombre} {campo}: --kb {m["kb"]} ≠ {kb}'
            assert abs(m['sheetBottom'] - borde) <= 2, f'{nombre} {campo}: el panel debe apoyarse sobre el teclado: borde del panel {m["sheetBottom"]:.0f}, teclado desde {borde}'
            assert m['sheetTop'] >= -1, f'{nombre} {campo}: el panel no sale por arriba: {m["sheetTop"]}'
            assert m['top'] >= 0 and m['bottom'] <= borde - 4, f'{nombre} {campo}: el campo se ve entero sobre el teclado: {m}'
            assert m['fuente'] >= 16 and m['foco'], f'{nombre} {campo}: letra de 16 px (sin zoom de iOS) y con el foco: {m}'
            p.fill('#' + campo, BUSQUEDA)
            esperar_sugerencias(p, lista, f'{nombre} {campo}')
            m = medir_campo(p, campo)
            assert m['top'] >= 0 and m['bottom'] <= borde - 4, f'{nombre} {campo}: escribiendo, el campo sigue a la vista: {m}'
            sg = medir_sugerencia(p, lista)
            assert sg['abierta'] and sg['opcion'] is not None, f'{nombre} {campo}: aparecen sugerencias'
            assert sg['opcion'] <= borde + 1 and sg['lista'] <= borde + 1, f'{nombre} {campo}: la primera sugerencia queda sobre el teclado: {sg}, teclado desde {borde}'
            p.evaluate('__teclado(0)'); p.evaluate('document.activeElement.blur()'); p.wait_for_timeout(600)
            m = medir_campo(p, campo)
            assert m['kb'] == 0 and abs(m['sheetBottom'] - m['h']) <= 2, f'{nombre} {campo}: al cerrar el teclado el panel vuelve a su sitio: {m}'
            s.limpio(f'{nombre} {campo}'); s.cerrar()


@prueba
def una_sola_lista_de_sugerencias_abierta_y_ninguna_al_salir_del_campo(nav):
    s, p = abrir(nav, 390, 844)
    p.tap('#tripOrigin'); p.fill('#tripOrigin', BUSQUEDA)
    esperar_sugerencias(p, 'originResults', 'origen')
    p.tap('#tripDestination'); p.wait_for_timeout(300)
    abiertas = p.evaluate("document.querySelectorAll('.place-results.suggestion-open').length")
    assert abiertas == 0, f'al pasar al destino se cierra la lista del origen ({abiertas} abiertas)'
    p.fill('#tripDestination', BUSQUEDA)
    esperar_sugerencias(p, 'destinationResults', 'destino')
    p.evaluate('document.activeElement.blur()'); p.wait_for_timeout(500)
    abiertas = p.evaluate("document.querySelectorAll('.place-results.suggestion-open').length")
    assert abiertas == 0, f'al salir del campo no queda ninguna lista abierta ({abiertas})'
    s.limpio('listas'); s.cerrar()


@prueba
def teclado_con_el_visual_viewport_desplazado_como_hace_ios_tambien_funciona(nav):
    for nombre, w, h, kb in (('iPhone SE 320x568', 320, 568, 260), ('iPhone 13 390x844', 390, 844, 336)):
        s, p = abrir(nav, w, h, ios=True)
        p.tap('#tripDestination')
        p.evaluate(f'__teclado({kb}, 60)')                                  # iOS desplaza el visual viewport 60 px al abrir el teclado
        p.wait_for_timeout(900)
        m = medir_campo(p, 'tripDestination')
        assert abs(m['sheetBottom'] - m['vvBottom']) <= 2, f'{nombre}: el panel se apoya en el borde inferior de lo visible: {m}'
        assert m['top'] >= 60 and m['bottom'] <= m['vvBottom'] - 4, f'{nombre}: el campo queda dentro de la zona visible (desde y=60): {m}'
        s.limpio(nombre); s.cerrar()


# ---------------------------------------------------------------------------------------------- teclado estilo Android (la página encoge)
@prueba
def teclado_estilo_android_la_pagina_encoge_y_el_campo_sigue_a_la_vista(nav):
    for nombre, w, h, kb in MODELOS:
        for campo, lista in (('tripOrigin', 'originResults'), ('tripDestination', 'destinationResults')):
            s, p = abrir(nav, w, h)
            p.tap('#' + campo)
            p.set_viewport_size({'width': w, 'height': h - kb})              # Android (interactive-widget=resizes-content): la ventana misma encoge
            p.wait_for_timeout(900)
            p.fill('#' + campo, BUSQUEDA)
            esperar_sugerencias(p, lista, f'{nombre} {campo} android')
            m = medir_campo(p, campo)
            assert m['kb'] == 0 and m['top'] >= 0 and m['bottom'] <= m['h'] - 4, f'{nombre} {campo}: el campo se ve en la ventana encogida: {m}'
            assert abs(m['sheetBottom'] - m['h']) <= 2, f'{nombre} {campo}: el panel llega al borde de la ventana encogida: {m}'
            sg = medir_sugerencia(p, lista)
            assert sg['abierta'] and sg['opcion'] is not None and sg['opcion'] <= m['h'] + 1, f'{nombre} {campo}: la primera sugerencia se ve: {sg}'
            s.limpio(f'{nombre} {campo} android'); s.cerrar()


# ---------------------------------------------------------------------------------------------- campos cómodos
@prueba
def campos_comodos_las_herramientas_van_debajo_no_tapan_el_campo_y_queda_ancho_para_escribir(nav):
    for nombre, w, h, _ in MODELOS:
        s, p = abrir(nav, w, h)
        p.tap('#tripOrigin'); p.evaluate('document.activeElement.blur()'); p.wait_for_timeout(700)      # panel a su altura completa: ambos campos a la vista
        r = p.evaluate("""() => { const out = {};
            for (const [campo, tools] of [['tripOrigin', 'searchOrigin,pickOrigin,useMyLocation'], ['tripDestination', 'searchDestination,pickDestination']]) {
                const i = document.getElementById(campo), ri = i.getBoundingClientRect(), cx = ri.left + ri.width / 2, cy = ri.top + ri.height / 2;
                const hit = document.elementFromPoint(cx, cy);
                const botones = tools.split(',').map((id) => document.getElementById(id).getBoundingClientRect());
                out[campo] = { centroLibre: hit === i, debajo: botones.every((b) => b.top >= ri.bottom - 1), alto: Math.min(...botones.map((b) => b.height)), ancho: Math.min(...botones.map((b) => b.width)),
                               dentro: botones.every((b) => b.left >= ri.left - 1 && b.right <= ri.right + 1), campoAncho: ri.width, fuente: parseFloat(getComputedStyle(i).fontSize) }; }
            return out; }""")
        for campo, d in r.items():
            assert d['centroLibre'], f'{nombre} {campo}: nada tapa el centro del campo'
            assert d['debajo'] and d['dentro'], f'{nombre} {campo}: las herramientas están debajo del campo y dentro de su ancho: {d}'
            assert d['alto'] >= 43.5 and d['ancho'] >= 43.5, f'{nombre} {campo}: herramientas de al menos 44 px: {d}'
            assert d['fuente'] >= 16, f'{nombre} {campo}: letra de 16 px: {d}'
        # con texto escrito aparece la X de borrar y el área de escritura sigue siendo cómoda
        p.fill('#tripOrigin', 'Calle 15 con carrera 40 barrio Galán')
        p.wait_for_timeout(500)
        area = p.evaluate("""() => { const i = document.getElementById('tripOrigin'), cs = getComputedStyle(i), x = document.getElementById('clearOrigin').getBoundingClientRect(), ri = i.getBoundingClientRect();
            return { ancho: ri.width, area: ri.width - parseFloat(cs.paddingLeft) - parseFloat(cs.paddingRight), xAncho: x.width, xAlto: x.height, xDentro: x.right <= ri.right + 1 && x.left >= ri.left, fuente: parseFloat(cs.fontSize) }; }""")
        assert area['area'] >= min(140, 0.6 * area['ancho']), f'{nombre}: con la X visible quedan {area["area"]:.0f} px para escribir de {area["ancho"]:.0f} (antes 62 a 112 en total)'
        assert area['xAncho'] >= 43.5 and area['xAlto'] >= 43.5 and area['xDentro'] and area['fuente'] >= 16, f'{nombre}: la X de borrar: {area}'
        s.limpio(nombre); s.cerrar()


@prueba
def ningun_campo_de_texto_baja_de_16_px_en_el_celular(nav):
    s, p = abrir(nav, 390, 844)
    bajos = p.evaluate("""() => [...document.querySelectorAll("input:not([type=checkbox]):not([type=radio]):not([type=range]):not([type=hidden]):not([type=button]):not([type=submit]), select, textarea")]
        .map((e) => [e.id || e.name || e.className || e.tagName, parseFloat(getComputedStyle(e).fontSize)]).filter(([, f]) => f < 16)""")
    assert not bajos, f'campos con letra menor de 16 px (iOS hace zoom al tocarlos): {bajos}'
    p.fill('#tripOrigin', 'Calle 15')
    p.wait_for_timeout(400)
    assert p.evaluate("parseFloat(getComputedStyle(document.getElementById('tripOrigin')).fontSize)") >= 16, 'con la X visible la letra tampoco baja de 16 px'
    s.limpio('letra 16'); s.cerrar()


# ---------------------------------------------------------------------------------------------- sin choques y sin desborde
@prueba
def ventanas_y_menu_con_teclado_estilo_iphone_sus_campos_quedan_sobre_el_teclado(nav):
    probados = []
    for nombre, w, h, kb in (('iPhone SE 320x568', 320, 568, 260), ('iPhone 13 390x844', 390, 844, 336), ('iPhone 14 Pro Max 430x932', 430, 932, 346)):
        for abrir_js, campo in (("document.getElementById('authModal').hidden = false", 'authPass'), ("document.getElementById('authModal').hidden = false", 'authName'),
                                ("document.getElementById('reportModal').hidden = false", 'reportContact'), ("document.getElementById('settingsModal').hidden = false", 'setDel')):
            s, p = abrir(nav, w, h, ios=True)
            p.evaluate(abrir_js)
            p.wait_for_timeout(200)
            if not p.is_visible('#' + campo):                               # el campo puede estar en una parte de la ventana que no se muestra (p. ej. sin sesión)
                s.cerrar()
                continue
            p.evaluate(f"document.getElementById('{campo}').focus()")
            p.evaluate(f'__teclado({kb})')
            p.wait_for_timeout(1100)
            d = p.evaluate("""(id) => { const c = document.getElementById(id), r = c.getBoundingClientRect(), card = c.closest('.modal-card').getBoundingClientRect(), fondo = c.closest('.modal-backdrop').getBoundingClientRect(), vv = window.visualViewport;
                return { campoTop: r.top, campoBottom: r.bottom, cardBottom: card.bottom, cardTop: card.top, fondoBottom: fondo.bottom, borde: vv.offsetTop + vv.height, fuente: parseFloat(getComputedStyle(c).fontSize) }; }""", campo)
            assert d['fondoBottom'] <= d['borde'] + 2 and d['cardBottom'] <= d['borde'] + 2, f'{nombre} {campo}: la ventana queda sobre el teclado: {d}'
            assert d['cardTop'] >= -1, f'{nombre} {campo}: la ventana no sale por arriba: {d}'
            assert d['campoTop'] >= d['cardTop'] - 1 and d['campoBottom'] <= d['borde'] - 2, f'{nombre} {campo}: el campo está a la vista: {d}'
            assert d['fuente'] >= 16, f'{nombre} {campo}: letra de 16 px: {d}'
            probados.append((nombre, campo))
            s.limpio(f'{nombre} {campo}'); s.cerrar()
    assert len(probados) >= 9, f'se probaron pocos campos de ventanas: {probados}'


def sin_choques(p, que):
    solapes = [x for x in p.evaluate(JS_FLOTANTES) if not any(t in x for t in PERMITIDOS)]
    assert not solapes, f'{que}: elementos montados unos sobre otros: {solapes}'
    assert p.evaluate('document.documentElement.scrollWidth - innerWidth') <= 1, f'{que}: desborde horizontal'


@prueba
def sin_choques_ni_desborde_en_todos_los_modelos_en_el_flujo_completo(nav):
    for nombre, w, h, _ in MODELOS:
        s, p = abrir(nav, w, h)
        sin_choques(p, f'{nombre} · inicio')
        V.planear(p)
        p.wait_for_timeout(500)
        sin_choques(p, f'{nombre} · tras buscar')
        b = p.locator('#startTrip').bounding_box()
        assert b and b['y'] >= 0 and b['y'] + b['height'] <= h, f'{nombre}: «Iniciar viaje» se ve de inmediato tras buscar: {b}'
        ls = V.lecturas(p)
        V.iniciar(p)
        p.wait_for_timeout(400)
        sin_choques(p, f'{nombre} · viaje en curso')
        V.alimentar(p, ls, 0, 'pregunta')
        p.wait_for_timeout(400)
        sin_choques(p, f'{nombre} · pregunta del viaje')
        card = p.evaluate("(() => { const r = document.getElementById('viajeCard').getBoundingClientRect(); return [r.top, r.bottom, r.left, r.right]; })()")
        assert card[0] >= 0 and card[1] <= h and card[2] >= 0 and card[3] <= w, f'{nombre}: la tarjeta del viaje cabe en la pantalla: {card}'
        s.limpio(nombre); s.cerrar()


@prueba
def celulares_apaisados_sin_desborde_y_con_el_boton_de_inicio_alcanzable(nav):
    for nombre, w, h in APAISADOS:
        s, p = abrir(nav, w, h)
        sin_choques(p, f'{nombre} · inicio')
        V.planear(p)
        p.wait_for_timeout(500)
        sin_choques(p, f'{nombre} · tras buscar')
        p.evaluate("document.getElementById('startTrip').scrollIntoView({ block: 'nearest' })")
        p.wait_for_timeout(300)
        b = p.locator('#startTrip').bounding_box()
        assert b and b['y'] >= -1 and b['y'] + b['height'] <= h + 1 and b['x'] >= -1 and b['x'] + b['width'] <= w + 1, f'{nombre}: el botón «Iniciar viaje» se alcanza en pantalla apaisada: {b}'
        s.limpio(nombre); s.cerrar()


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
                print('FALLA ', f.__name__, '|', str(e).splitlines()[0][:700] if str(e) else repr(e))
                traceback.print_exc(limit=3)
        nav.close()
    print(f'\n{len(PRUEBAS) if not filtro else "?"} pruebas de celulares; fallos: {len(fallos)} {fallos}')
    return 1 if fallos else 0


if __name__ == '__main__':
    sys.exit(correr(sys.argv[1] if len(sys.argv) > 1 else None))
