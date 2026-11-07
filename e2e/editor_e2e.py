"""Pruebas de navegador del EDITOR de recorridos (selección y edición de vértices, rutas de 1.500 y 10.000 puntos).

Uso: SEVB_E2E_URL=http://127.0.0.1:18080/ python e2e/editor_e2e.py [filtro]      (lo normal es e2e/run_e2e.py)
Reutiliza la infraestructura de sevb_e2e.py (sesión con vigilancia de consola, esperas sin eval por la CSP).
"""
import os
import sys
import time
import traceback

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import sevb_e2e as B  # noqa: E402
from playwright.sync_api import sync_playwright  # noqa: E402

PRUEBAS = []


def prueba(f):
    PRUEBAS.append(f)
    return f


def abrir_editor(nav, ruta_codigo, **opciones):
    s = B.Sesion(nav, **opciones)
    s.escrituras = []      # POST/PUT/DELETE/PATCH hechos por la página: abrir y editar NO debe guardar nada
    s.page.on('request', lambda r: s.escrituras.append(r.method + ' ' + r.url) if r.method in ('POST', 'PUT', 'DELETE', 'PATCH') and '/api/editor/vial/' not in r.url else None)
    s.page.goto(B.URL + 'editor.html', wait_until='domcontentloaded')
    B.esperar(s.page, "!!(window.SpevbEditor && SpevbEditor.map)", 20000)
    B.esperar(s.page, "document.querySelectorAll('#editorRoute option').length > 1", 15000)
    s.page.select_option('#editorRoute', label=None, value=s.page.evaluate(
        "(c) => { const o = [...document.querySelectorAll('#editorRoute option')].find(x => x.textContent.startsWith(c + ' ')); return o ? o.value : null; }", ruta_codigo))
    B.esperar(s.page, "SpevbEditor.points().length > 0", 20000)
    s.page.wait_for_timeout(500)
    return s


def info(page):
    return page.locator('#vertexInfo').inner_text()


def sel(page):
    """Índice (base 1) mostrado en el campo «Ir al vértice número» ('' si no hay selección)."""
    return page.input_value('#vertexIndex')


def px_de_vertice(page, i):
    return page.evaluate("(i) => { const m = SpevbEditor.map, p = SpevbEditor.points()[i]; const q = m.toPoint(p); const b = m.el.getBoundingClientRect(); return [b.x + q.x, b.y + q.y]; }", i)


@prueba
def selecciona_el_vertice_52_de_una_ruta_de_1500_puntos(nav):
    s = abrir_editor(nav, 'X1500')
    p = s.page
    assert p.evaluate('SpevbEditor.points().length') == 1500
    p.fill('#vertexIndex', '52')
    p.wait_for_timeout(400)
    t = info(p)
    assert 'Vértice 52 de 1.500' in t.replace('\n', ' '), t
    assert 'Anterior:' in t and 'Siguiente:' in t and ',' in t
    # marcador grande del vértice seleccionado y pocos marcadores (no 1.500)
    assert p.locator('.vertex.selected').count() == 1
    marcadores = p.locator('.sevb-marcador').count()
    assert marcadores <= 30, f'demasiados marcadores Leaflet: {marcadores}'
    # el vértice queda centrado
    c = p.evaluate("(() => { const m = SpevbEditor.map, p = SpevbEditor.points()[51], q = m.toPoint(p), s = m.getSize(); return [Math.abs(q.x - s.x / 2), Math.abs(q.y - s.y / 2)]; })()")
    assert c[0] < 40 and c[1] < 40, f'vértice no centrado: {c}'
    t = p.evaluate("""() => { const G = Spevb.EditorGeo, m = SpevbEditor.map, pts = SpevbEditor.points();
        const proj = (q) => { const c = m.toPoint(q); return [c.x, c.y]; };
        const c = m.getCenter(), t0 = performance.now();
        for (let i = 0; i < 20; i++) G.seleccionar(pts, c, proj, 22);
        return { sel: (performance.now() - t0) / 20 }; }""")
    print('      [1.500 pts] selección por clic %.2f ms · marcadores %d' % (t['sel'], marcadores))
    assert not s.escrituras, f'abrir y navegar no debe guardar: {s.escrituras}'
    s.limpio('vértice 52')
    s.cerrar()


@prueba
def seleccion_por_clic_cerca_de_la_linea_y_navegacion(nav):
    s = abrir_editor(nav, 'X1500')
    p = s.page
    p.fill('#vertexIndex', '300')
    p.wait_for_timeout(400)
    # se deselecciona y se hace clic a 3 px del vértice 300 (índice 299)
    p.evaluate("SpevbEditor.map.setView(SpevbEditor.points()[299], 18, {animate: false})")
    p.wait_for_timeout(300)
    x, y = px_de_vertice(p, 299)
    p.mouse.click(x + 3, y + 4)
    p.wait_for_timeout(300)
    assert sel(p) in ('299', '300', '301'), f'el clic debe elegir un vértice cercano: {sel(p)}'
    base = int(sel(p))
    p.click('#vxNext'); assert int(sel(p)) == base + 1
    p.click('#vxPrev'); assert int(sel(p)) == base
    p.click('#vxFwd10'); assert int(sel(p)) == base + 10
    p.click('#vxBack10'); assert int(sel(p)) == base
    p.click('#vxFirst'); assert int(sel(p)) == 1
    p.click('#vxLast'); assert int(sel(p)) == 1500
    p.click('#vxFwd10'); assert int(sel(p)) == 1500, 'no pasa del último'
    s.limpio('selección por clic')
    s.cerrar()


@prueba
def mover_deshacer_rehacer_insertar_y_eliminar(nav):
    s = abrir_editor(nav, 'X1500')
    p = s.page
    p.fill('#vertexIndex', '700')
    p.wait_for_timeout(400)
    antes = p.evaluate('SpevbEditor.points()[699]')
    x, y = px_de_vertice(p, 699)
    p.mouse.move(x, y); p.mouse.down()
    for k in range(1, 9):
        p.mouse.move(x + 6 * k, y + 4 * k); p.wait_for_timeout(15)
    p.mouse.up(); p.wait_for_timeout(300)
    movido = p.evaluate('SpevbEditor.points()[699]')
    assert movido != antes, 'el vértice se movió'
    p.click('#undoPoint'); p.wait_for_timeout(200)
    assert p.evaluate('SpevbEditor.points()[699]') == antes, 'deshacer devuelve el vértice'
    p.click('#redoPoint'); p.wait_for_timeout(200)
    assert p.evaluate('SpevbEditor.points()[699]') == movido, 'rehacer lo vuelve a mover'
    # insertar con doble clic sobre un segmento (zoom alto) y eliminar el intermedio
    p.evaluate("SpevbEditor.map.setView(SpevbEditor.points()[899], 20, {animate: false})")
    p.wait_for_timeout(300)
    ax, ay = px_de_vertice(p, 899); bx, by = px_de_vertice(p, 900)
    p.mouse.dblclick((ax + bx) / 2, (ay + by) / 2)
    p.wait_for_timeout(400)
    assert p.evaluate('SpevbEditor.points().length') == 1501, 'el doble clic inserta un vértice'
    assert sel(p) == '901'
    p.click('#deletePoint'); p.wait_for_timeout(200)
    assert p.evaluate('SpevbEditor.points().length') == 1500, 'eliminar el vértice intermedio'
    # insertar con el botón sobre el segmento elegido por clic
    ax, ay = px_de_vertice(p, 899); bx, by = px_de_vertice(p, 900)
    p.mouse.click((ax + bx) / 2, (ay + by) / 2 + 2); p.wait_for_timeout(300)
    if p.locator('#insertAtSegment').is_enabled():
        p.click('#insertAtSegment'); p.wait_for_timeout(200)
        assert p.evaluate('SpevbEditor.points().length') == 1501
    assert not s.escrituras, f'editar no guarda automáticamente: {s.escrituras}'
    s.limpio('mover/insertar/eliminar')
    s.cerrar()


@prueba
def atajos_de_teclado_no_actuan_al_escribir_en_formularios(nav):
    s = abrir_editor(nav, 'X1500')
    p = s.page
    p.fill('#vertexIndex', '10'); p.wait_for_timeout(300)
    p.evaluate("document.activeElement && document.activeElement.blur()")
    p.keyboard.press(']'); p.wait_for_timeout(200)
    assert sel(p) == '11', 'fuera de los formularios el atajo ] avanza'
    p.keyboard.press('{'); p.wait_for_timeout(200)
    p.click('#editorName')
    p.keyboard.type('[]]')
    p.wait_for_timeout(200)
    assert sel(p) == '1', f'{sel(p)}'  # '{' retrocedió 10 desde 11 → 1; escribir en el campo no mueve la selección
    assert '[]]' in p.input_value('#editorName')
    p.click('#editorName'); p.keyboard.press('Delete')
    assert p.evaluate('SpevbEditor.points().length') == 1500, 'Supr dentro de un campo no elimina vértices'
    s.limpio('atajos')
    s.cerrar()


@prueba
def ruta_de_10000_puntos_sin_bloquear_la_interfaz(nav):
    s = abrir_editor(nav, 'X10000')
    p = s.page
    assert p.evaluate('SpevbEditor.points().length') == 10000
    p.fill('#vertexIndex', '5000'); p.wait_for_timeout(500)
    marcadores = p.locator('.sevb-marcador').count()
    assert marcadores <= 30, f'con 10.000 puntos solo debe haber unos pocos marcadores: {marcadores}'
    capas = p.evaluate("(() => { let n = 0; n = SpevbEditor.map.elementos().length; return n; })()")
    assert capas < 450, f'capas Leaflet: {capas}'
    # tiempos medidos dentro del navegador
    t = p.evaluate("""() => {
        const G = Spevb.EditorGeo, m = SpevbEditor.map, pts = SpevbEditor.points();
        const proj = (q) => { const c = m.toPoint(q); return [c.x, c.y]; };
        const c = m.getCenter(), t0 = performance.now();
        for (let i = 0; i < 20; i++) G.seleccionar(pts, c, proj, 22);
        const sel = (performance.now() - t0) / 20;
        const t1 = performance.now(); const idx = G.simplificar(pts, proj, 7); const simp = performance.now() - t1;
        return { seleccionMs: sel, simplificarMs: simp, simplificados: idx.length };
    }""")
    assert t['seleccionMs'] < 250, t
    assert t['simplificarMs'] < 1500, t
    print('      [10.000 pts] selección por clic %.1f ms · simplificación %.1f ms (%d índices) · marcadores %d · capas %d' % (t['seleccionMs'], t['simplificarMs'], t['simplificados'], marcadores, capas))
    # la interfaz responde: navegar y cambiar el zoom
    t0 = time.time()
    p.click('#vxLast'); p.evaluate("SpevbEditor.map.setView(SpevbEditor.map.getCenter(), SpevbEditor.map.getZoom() + 1, {animate: false})"); p.wait_for_timeout(200)
    assert sel(p) == '10000' and time.time() - t0 < 5
    assert not s.escrituras
    s.limpio('10.000 puntos')
    s.cerrar()


@prueba
def tactil_y_lapiz_seleccionan_vertices(nav):
    s = abrir_editor(nav, 'X1500', viewport={'width': 1024, 'height': 800}, has_touch=True)
    p = s.page
    p.fill('#vertexIndex', '400'); p.wait_for_timeout(400)
    p.evaluate("SpevbEditor.map.setView(SpevbEditor.points()[399], 18, {animate: false})"); p.wait_for_timeout(300)
    x, y = px_de_vertice(p, 399)
    p.touchscreen.tap(x + 4, y + 3); p.wait_for_timeout(400)
    assert sel(p) in ('399', '400', '401'), f'toque: {sel(p)}'
    cdp = p.context.new_cdp_session(p)
    p.evaluate("SpevbEditor.map.setView(SpevbEditor.points()[899], 18, {animate: false})"); p.wait_for_timeout(300)
    x, y = px_de_vertice(p, 899)
    base = {'x': x + 3, 'y': y + 3, 'pointerType': 'pen', 'button': 'left', 'clickCount': 1}
    cdp.send('Input.dispatchMouseEvent', {**base, 'type': 'mouseMoved', 'buttons': 0})
    cdp.send('Input.dispatchMouseEvent', {**base, 'type': 'mousePressed', 'buttons': 1})
    cdp.send('Input.dispatchMouseEvent', {**base, 'type': 'mouseReleased', 'buttons': 0}); p.wait_for_timeout(400)
    assert sel(p) in ('899', '900', '901'), f'lápiz: {sel(p)}'
    s.limpio('táctil y lápiz')
    s.cerrar()


TOKEN = 'x' * 40      # el stub no valida la clave; el editor exige 32+ caracteres


def guardar(page):
    page.fill('#editorToken', TOKEN)
    page.click('#saveDraw')
    page.wait_for_timeout(800)


@prueba
def cambia_el_codigo_de_una_ruta_con_confirmacion_y_alias_y_actualiza_favoritos(nav):
    s = abrir_editor(nav, 'R006')
    p = s.page
    dialogos = []
    p.on('dialog', lambda d: (dialogos.append(d.message), d.accept()))
    assert p.input_value('#editorCode') == 'R006'
    assert not p.locator('#editorCode').is_disabled(), 'el código de una ruta existente ya es editable'
    p.fill('#editorCode', 'rx009')
    guardar(p)
    assert dialogos and 'R006' in dialogos[0] and 'RX009' in dialogos[0], f'confirmación con código anterior y nuevo: {dialogos}'
    assert p.input_value('#editorCode') == 'RX009'
    assert 'versión 1' in p.locator('#editorStatus').inner_text().lower() or 'Versión 1' in p.locator('#versionLabel').inner_text()
    r = p.evaluate("fetch('/api/rutas/codigo/R006').then(r => r.json())")
    assert r['codigo'] == 'RX009' and r['aliasUsado'] is True and r['id'] == 6, r
    s.limpio('cambio de código')
    s.cerrar()
    # favoritos antiguos: el código anterior se actualiza al vigente al cargar la página principal
    s2 = B.Sesion(nav)
    s2.ctx.add_init_script("try { if (!localStorage.getItem('__f')) { localStorage.setItem('spevb-rutas-fav', JSON.stringify([{id: 'R006', nombre: 'Ruta para cambiar de código'}])); localStorage.setItem('__f', '1'); } } catch (e) {}")
    pg = s2.abrir(espera=1000)
    B.esperar(pg, "JSON.parse(localStorage.getItem('spevb-rutas-fav') || '[]').some(f => f.id === 'RX009')", 8000)
    assert pg.evaluate("JSON.parse(localStorage.getItem('spevb-rutas-fav')).every(f => f.id !== 'R006')"), 'el favorito antiguo se reemplaza, no se duplica'
    s2.limpio('favoritos con alias')
    s2.cerrar()


@prueba
def rechaza_codigo_duplicado_y_conflicto_de_version(nav):
    s = abrir_editor(nav, 'X1500')
    p = s.page
    p.on('dialog', lambda d: d.accept())
    p.fill('#editorCode', 'R002')
    guardar(p)
    assert 'Ya existe' in p.locator('#editorStatus').inner_text(), p.locator('#editorStatus').inner_text()
    # conflicto: otra persona guardó antes (el stub sube la versión) → 409 y nada se pierde
    p.fill('#editorCode', 'X1500')
    p.evaluate("""fetch('/api/editor/rutas/3/recorrido', {method: 'PUT', headers: {'Content-Type': 'application/json'},
        body: JSON.stringify({recorrido: {type: 'LineString', coordinates: SpevbEditor.points().map(q => [q[1], q[0]])}, nombre: 'Otra persona', origen: 'A', destino: 'B',
        sentido: 'IDA', color: '#00838F', version: SpevbEditor.route().version})}).then(r => r.status)""")
    p.wait_for_timeout(300)
    guardar(p)
    assert 'modificada' in p.locator('#editorStatus').inner_text().lower(), p.locator('#editorStatus').inner_text()
    assert p.evaluate('SpevbEditor.points().length') == 1500, 'tras el conflicto el trazado local no se pierde'
    # los 400 y 409 de este escenario son intencionales (el navegador los registra como «Failed to load resource»)
    s.errores = [e for e in s.errores if 'status of 400' not in e and 'status of 409' not in e]
    s.limpio('duplicado y conflicto')
    s.cerrar()


def abrir_puentes(nav):
    s = B.Sesion(nav)
    s.escrituras = []
    s.page.on('request', lambda r: s.escrituras.append(r.method + ' ' + r.url) if r.method in ('POST', 'PUT', 'DELETE', 'PATCH') and '/api/editor/vial/' not in r.url else None)
    s.page.on('dialog', lambda d: d.accept())
    s.page.goto(B.URL + 'puentes.html', wait_until='domcontentloaded')
    B.esperar(s.page, "!!(window.SpevbPuentes && SpevbPuentes.map)", 20000)
    s.page.fill('#puenteToken', TOKEN)
    s.page.dispatch_event('#puenteToken', 'change')
    B.esperar(s.page, "document.querySelectorAll('#puenteLista .paso-row').length >= 2", 10000)
    s.page.wait_for_timeout(300)
    return s


def px_puente(page, i):
    return page.evaluate("(i) => { const m = SpevbPuentes.map, p = SpevbPuentes.puntos()[i]; const q = m.toPoint(p); const b = m.el.getBoundingClientRect(); return [b.x + q.x, b.y + q.y]; }", i)


def editar(page, nombre):
    page.locator('#puenteLista .paso-row', has_text=nombre).get_by_text('Editar', exact=True).click()
    B.esperar(page, "!!SpevbPuentes.editando()", 5000)
    page.wait_for_timeout(600)


@prueba
def edita_un_puente_existente_sin_guardar_hasta_confirmar_y_cancela(nav):
    s = abrir_puentes(nav)
    p = s.page
    editar(p, 'Puente Esperanza')
    assert p.evaluate('SpevbPuentes.editando().id') == 9001
    n0 = p.evaluate('SpevbPuentes.puntos().length')
    assert n0 >= 3, n0
    assert p.input_value('#puenteNombre') == 'Puente Esperanza' and 'Nota interna' in p.input_value('#puenteNota'), 'el editor ve la nota'
    # mover un vértice (arrastre real) y deshacer / rehacer
    antes = p.evaluate('SpevbPuentes.puntos()[1]')
    x, y = px_puente(p, 1)
    p.mouse.move(x, y); p.mouse.down()
    for k in range(1, 7):
        p.mouse.move(x + 5 * k, y + 5 * k); p.wait_for_timeout(15)
    p.mouse.up(); p.wait_for_timeout(300)
    movido = p.evaluate('SpevbPuentes.puntos()[1]')
    assert movido != antes
    p.click('#puenteDeshacer'); assert p.evaluate('SpevbPuentes.puntos()[1]') == antes
    p.click('#puenteRehacer'); assert p.evaluate('SpevbPuentes.puntos()[1]') == movido
    # insertar con doble clic sobre un segmento y eliminar el vértice elegido
    ax, ay = px_puente(p, 2); bx, by = px_puente(p, 3)
    p.mouse.dblclick((ax + bx) / 2, (ay + by) / 2); p.wait_for_timeout(300)
    assert p.evaluate('SpevbPuentes.puntos().length') == n0 + 1, 'doble clic inserta un vértice'
    # cancelar: no se guardó nada
    p.click('#puenteCancelar'); p.wait_for_timeout(200)
    assert p.evaluate('SpevbPuentes.editando()') is None and p.evaluate('SpevbPuentes.puntos().length') == 0
    assert not s.escrituras, f'editar y cancelar no guarda: {s.escrituras}'
    s.limpio('editar puente / cancelar')
    s.cerrar()


@prueba
def guarda_la_edicion_de_un_puente_conservando_el_id_y_sin_exponer_la_nota(nav):
    s = abrir_puentes(nav)
    p = s.page
    editar(p, 'Puente Esperanza')
    p.fill('#puenteNombre', 'Puente Esperanza (editado)')
    p.fill('#puenteNota', 'Nota privada nueva')
    p.click('#puenteAjustarExt'); p.wait_for_timeout(200)
    p.click('#puenteGuardar'); p.wait_for_timeout(900)
    puts = [w for w in s.escrituras if w.startswith('PUT')]
    assert len(puts) == 1 and puts[0].endswith('/api/editor/pasos/9001'), s.escrituras
    assert p.evaluate('SpevbPuentes.editando()') is None
    assert 'actualizado' in p.locator('#puenteStatus').inner_text().lower() or 'conectado' in p.locator('#puenteStatus').inner_text().lower()
    publico = p.evaluate("fetch('/api/puentes').then(r => r.json())")
    ed = [b for b in publico if b['id'] == 9001]
    assert ed and ed[0]['nombre'] == 'Puente Esperanza (editado)', 'mismo id, nuevo nombre'
    assert set(ed[0].keys()) == B.PERMITIDOS_PUENTE and 'nota' not in ed[0], 'la API pública sigue sin nota'
    admin = p.evaluate("fetch('/api/editor/pasos').then(r => r.json())")
    assert [b for b in admin if b['id'] == 9001][0]['nota'] == 'Nota privada nueva' and [b for b in admin if b['id'] == 9001][0]['version'] == 1
    s.limpio('guardar edición de puente')
    s.cerrar()


@prueba
def conflicto_de_version_al_editar_un_puente_conserva_la_edicion_local(nav):
    s = abrir_puentes(nav)
    p = s.page
    editar(p, 'Puente Lejano')
    coords = p.evaluate("SpevbPuentes.puntos().map(q => [q[1], q[0]])")
    p.evaluate("""(c) => fetch('/api/editor/pasos/9002', {method: 'PUT', headers: {'Content-Type': 'application/json'},
        body: JSON.stringify({nombre: 'Cambio de otra persona', nota: 'x', coordenadas: c, version: 0})}).then(r => r.status)""", coords)
    p.wait_for_timeout(300)
    p.fill('#puenteNombre', 'Mi cambio')
    p.click('#puenteGuardar'); p.wait_for_timeout(800)
    assert 'otra persona' in p.locator('#puenteStatus').inner_text().lower(), p.locator('#puenteStatus').inner_text()
    assert p.evaluate('SpevbPuentes.editando()') is not None, 'tras un 409 la edición local sigue abierta'
    s.errores = [e for e in s.errores if 'status of 409' not in e]
    s.limpio('conflicto en puente')
    s.cerrar()


@prueba
def paraderos_se_revisan_tras_cambiar_la_geometria_sin_moverse_ni_borrarse_solos(nav):
    s = abrir_editor(nav, 'R005')
    p = s.page
    assert p.locator('#stopList .stop-row').count() == 3
    nombres = p.evaluate("[...document.querySelectorAll('#stopList .stop-row input')].map(i => i.value)")
    pos_antes = p.evaluate("[...document.querySelectorAll('#stopList .stop-row input')].length")
    # sin cambios en la geometría no hay avisos
    assert p.locator('#stopRevision').is_hidden()
    # el vértice 3 (índice 2) se arrastra lejos: los paraderos cercanos quedan a más de 30 m
    # se arrastra, lejos de la línea, el vértice más cercano a «Paradero Centro» (deja a ese paradero a cientos de metros del nuevo trazado)
    i = p.evaluate("""(() => { const q = SpevbEditor.route().paraderos.find(s => s.nombre === 'Paradero Centro').ubicacion.coordinates;
        let best = 0, bd = 1e9; SpevbEditor.points().forEach((v, k) => { const d = (v[0] - q[1]) ** 2 + (v[1] - q[0]) ** 2; if (d < bd) { bd = d; best = k; } }); return best; })()""")
    p.fill('#vertexIndex', str(i + 1)); p.wait_for_timeout(400)
    x, y = px_de_vertice(p, i)
    p.mouse.move(x, y); p.mouse.down()
    for k in range(1, 11):
        p.mouse.move(x, y - 40 * k); p.wait_for_timeout(15)
    p.mouse.up(); p.wait_for_timeout(900)
    caja = p.locator('#stopRevision')
    assert caja.is_visible() and 'lejos' in caja.inner_text().lower(), caja.inner_text()
    assert p.locator('#stopRevisionList .stop-row').count() >= 1
    assert p.locator('#stopList .stop-row').count() == 3, 'no se borró ningún paradero'
    assert p.evaluate("[...document.querySelectorAll('#stopList .stop-row input')].map(i => i.value)") == nombres, 'nombres intactos'
    assert p.locator('#stopRevisionList .stop-row').first.inner_text().count('→') == 1, 'muestra distancia anterior → nueva'
    lat_antes = p.evaluate("SpevbEditor.route().paraderos.map(q => q.ubicacion.coordinates.join())")
    # ajustar uno por uno hasta que ninguno quede lejos
    for _ in range(5):
        if p.locator('#stopRevisionList button').count() == 0:
            break
        p.locator('#stopRevisionList button').first.click(); p.wait_for_timeout(500)
    assert p.locator('#stopRevisionList button').count() == 0 and p.locator('#stopAjustarTodos').is_disabled()
    despues = p.evaluate("[...document.querySelectorAll('#stopList .stop-row input')].map(i => i.value)")
    assert len(despues) == 3 and sorted(despues) == sorted(nombres), f'paraderos tras ajustar: {despues} (antes {nombres})'
    assert 'Cambios pendientes' in p.locator('#stopDirty').inner_text(), 'quedan como cambios por confirmar'
    assert not s.escrituras, f'nada se guarda solo: {s.escrituras}'
    s.limpio('paraderos tras geometría')
    s.cerrar()


def con_clave(s):
    s.page.fill('#editorToken', TOKEN)
    s.page.dispatch_event('#editorToken', 'input')


@prueba
def ajusta_un_vertice_a_la_via_con_previsualizacion_limite_y_deshacer(nav):
    s = abrir_editor(nav, 'X1500')
    p = s.page
    con_clave(s)
    p.fill('#vertexIndex', '700'); p.wait_for_timeout(500)
    p.check('#editorValidated')
    antes = p.evaluate('SpevbEditor.points()[699]')
    # 1) previsualización: nada cambia hasta confirmar
    p.fill('#snapMax', '80')
    p.click('#snapVertex'); p.wait_for_timeout(700)
    assert p.locator('#snapPanel').is_visible(), p.locator('#snapStatus').inner_text()
    n = p.locator('#snapList .snap-row').count()
    assert 1 <= n <= 3, f'hasta tres candidatos: {n}'
    assert 'Requiere revisión del equipo' in p.locator('#snapStatus').inner_text()
    assert p.evaluate('SpevbEditor.points()[699]') == antes, 'la previsualización no cambia el punto'
    texto = p.locator('#snapList').inner_text()
    assert ' m ·' in texto, 'cada candidato muestra su distancia'
    # 2) aplicar: cambio pendiente, desmarca «validada», se puede deshacer, no se guarda
    p.click('#snapApply'); p.wait_for_timeout(300)
    despues = p.evaluate('SpevbEditor.points()[699]')
    assert despues != antes
    assert (abs(despues[0] / 0.001 - round(despues[0] / 0.001)) < 1e-3) or (abs(despues[1] / 0.001 - round(despues[1] / 0.001)) < 1e-3), 'el punto quedó sobre una calle de la cuadrícula de prueba'
    assert not p.is_checked('#editorValidated'), '«validada» se desmarca al ajustar'
    p.click('#undoPoint'); p.wait_for_timeout(200)
    assert p.evaluate('SpevbEditor.points()[699]') == antes, 'deshacer'
    # 3) más lejos del límite: advertencia y el punto se conserva (deshacer deja sin selección: se vuelve a elegir el vértice)
    p.fill('#vertexIndex', '700'); p.wait_for_timeout(400)
    p.fill('#snapMax', '5')
    p.click('#snapVertex'); p.wait_for_timeout(700)
    estado = p.locator('#snapStatus').inner_text()
    if p.locator('#snapPanel').is_hidden():
        assert 'No hay una vía' in estado and 'se conserva' in estado, estado
    assert p.evaluate('SpevbEditor.points()[699]') == antes
    assert not s.escrituras, f'ajustar no guarda: {s.escrituras}'
    s.limpio('ajuste de vértice')
    s.cerrar()


@prueba
def analiza_y_ajusta_la_ruta_completa_sin_aplicar_ni_guardar_hasta_confirmar(nav):
    s = abrir_editor(nav, 'R005')
    p = s.page
    con_clave(s)
    original = p.evaluate('SpevbEditor.points()')
    p.fill('#snapMax', '10')
    p.click('#matchRoute'); p.wait_for_timeout(1200)
    assert p.locator('#matchPanel').is_visible(), p.locator('#snapStatus').inner_text()
    assert p.evaluate('SpevbEditor.points()') == original, 'analizar NO modifica los puntos'
    resumen = p.locator('#matchSummary').inner_text()
    for etiqueta in ('Puntos', 'Longitud', 'Desviación promedio', 'Recorrido ajustado', 'Segmentos dudosos', 'Puntos sin coincidencia', 'Cambios de vía ambiguos', 'giros ilegales'):
        assert etiqueta in resumen, f'falta «{etiqueta}» en el resumen: {resumen}'
    prop = p.evaluate('SpevbAjuste.propuesta()')
    assert prop['requiereRevisionManual'] and prop['problemas'], 'con un radio de 10 m hay tramos problemáticos'
    # sin marcar la revisión no se puede aceptar todo
    p.click('#matchAcceptAll'); p.wait_for_timeout(300)
    assert p.evaluate('SpevbEditor.points()') == original, 'sin revisar la comparación no se aplica'
    assert 'Revisé la comparación' in p.locator('#snapStatus').inner_text()
    # revisar el siguiente problema
    p.click('#matchNext'); p.wait_for_timeout(300)
    assert 'Problema 1 de' in p.locator('#snapStatus').inner_text()
    # rechazar todo: nada cambia
    p.click('#matchReject'); p.wait_for_timeout(200)
    assert p.locator('#matchPanel').is_hidden() and p.evaluate('SpevbEditor.points()') == original
    # volver a analizar y aceptar solo lo aceptado por tramo
    p.click('#matchRoute'); p.wait_for_timeout(1200)
    p.click('#matchNext'); p.wait_for_timeout(200)
    p.click('#matchKeepSeg'); p.click('#matchApplySel'); p.wait_for_timeout(300)
    assert p.evaluate('SpevbEditor.points()') == original, 'aplicar tramos con problemas exige marcar la revisión'
    p.check('#matchReview'); p.click('#matchAcceptSeg'); p.click('#matchApplySel'); p.wait_for_timeout(400)
    parcial = p.evaluate('SpevbEditor.points()')
    assert len(parcial) >= 2 and not s.escrituras
    # aceptar todo tras revisar: cambio pendiente, desmarca «validada», Deshacer lo revierte
    p.click('#undoPoint'); p.wait_for_timeout(200)
    p.check('#editorValidated')
    p.click('#matchRoute'); p.wait_for_timeout(1200)
    p.check('#matchReview'); p.click('#matchAcceptAll'); p.wait_for_timeout(500)
    nuevo = p.evaluate('SpevbEditor.points()')
    assert nuevo != original, 'aceptar todo aplica la propuesta'
    assert not p.is_checked('#editorValidated'), 'la ruta ajustada queda pendiente de revisión: «validada» desmarcada'
    assert 'Cambios pendientes' in p.locator('#dirtyBadge').inner_text()
    p.click('#undoPoint'); p.wait_for_timeout(200)
    assert p.evaluate('SpevbEditor.points()') == original
    assert not s.escrituras, f'nunca se guarda sin «Guardar recorrido»: {s.escrituras}'
    s.limpio('ajuste completo')
    s.cerrar()


@prueba
def los_vertices_bloqueados_son_anclas_y_el_ajuste_no_los_mueve(nav):
    s = abrir_editor(nav, 'X1500')
    p = s.page
    con_clave(s)
    p.fill('#vertexIndex', '500'); p.wait_for_timeout(400)
    original = p.evaluate('SpevbEditor.points()[499]')
    p.click('#anchorToggle'); p.wait_for_timeout(200)
    assert p.evaluate('SpevbEditor.anclas()') == [499]
    p.fill('#snapMax', '60')
    p.click('#matchRoute'); p.wait_for_timeout(2500)
    prop = p.evaluate('SpevbAjuste.propuesta()')
    assert prop is not None and prop['propuesta'][499] == list(original), 'el ancla se conserva en la propuesta'
    # las anclas se desplazan al insertar antes y se pueden deshacer
    p.fill('#vertexIndex', '10'); p.wait_for_timeout(300)
    p.click('#insertPoint'); p.wait_for_timeout(200)
    assert p.evaluate('SpevbEditor.anclas()') == [500]
    p.click('#undoPoint'); p.wait_for_timeout(200)
    assert p.evaluate('SpevbEditor.anclas()') == [499]
    s.limpio('anclas')
    s.cerrar()


@prueba
def la_auditoria_de_calidad_lista_las_rutas_abre_el_primer_problema_y_no_modifica_nada(nav):
    s = abrir_editor(nav, 'R005')
    p = s.page
    con_clave(s)
    p.click('#auditRoutes'); p.wait_for_timeout(1500)
    assert p.locator('#auditPanel').is_visible()
    filas = p.locator('#auditList .audit-row')
    assert filas.count() >= 4, f'se listan las rutas: {filas.count()}'
    resumen = p.locator('#auditSummary').inner_text()
    assert 'correctas' in resumen and 'críticas' in resumen and 'Requiere revisión del equipo' in resumen, resumen
    texto = filas.first.inner_text()
    for dato in ('pts', 'sobre vías', 'desv.', 'sospechosos'):
        assert dato in texto, f'falta «{dato}»: {texto}'
    assert p.locator('#auditList button', has_text='Ir al primer problema').count() >= 1
    assert p.locator('#auditPanel', has_text='Ajustar todas').count() == 0, 'no hay ajustes masivos'
    antes = p.evaluate('SpevbEditor.points()')
    # abrir la primera ruta con problemas, directamente en su primer segmento problemático
    p.locator('#auditList button', has_text='Ir al primer problema').first.click(); p.wait_for_timeout(1500)
    assert p.evaluate('SpevbEditor.selected()') >= 0, 'salta al primer segmento problemático'
    assert not s.escrituras, f'la auditoría no guarda ni modifica: {s.escrituras}'
    assert p.locator('#dirtyBadge').inner_text() == 'Sin cambios'
    s.limpio('auditoría')
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
    print(f'\n{len(PRUEBAS) if not filtro else "?"} pruebas del editor; fallos: {len(fallos)} {fallos}')
    return 1 if fallos else 0


if __name__ == '__main__':
    sys.exit(correr(sys.argv[1] if len(sys.argv) > 1 else None))
