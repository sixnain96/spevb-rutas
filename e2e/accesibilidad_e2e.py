"""Pruebas de navegador de 4.13.5: menu lateral y modales accesibles, enlaces de administracion sin destello, mensajes para pasajeros,
estado de carga y recuperacion, marca visible y areas de toque de 44 px.

Uso: SEVB_E2E_URL=http://127.0.0.1:18080/ python e2e/accesibilidad_e2e.py [filtro]      (lo normal es e2e/run_e2e.py)
"""
import json
import os
import sys
import traceback

if hasattr(sys.stdout, 'reconfigure'):
    sys.stdout.reconfigure(encoding='utf-8', errors='replace')
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import sevb_e2e as B  # noqa: E402
from playwright.sync_api import sync_playwright  # noqa: E402

PRUEBAS = []
MOVIL = {'viewport': {'width': 390, 'height': 844}, 'is_mobile': True, 'has_touch': True, 'device_scale_factor': 2}
DEST = '4.1450, -73.6240'
ORIGEN = '4.1475, -73.6530'
ADMIN = ['/lugares.html', '/paraderos.html', '/puentes.html', '/pasos.html', '/reportes.html', '/editor.html']


def prueba(f):
    PRUEBAS.append(f)
    return f


def activo_id(p):
    return p.evaluate("document.activeElement ? (document.activeElement.id || document.activeElement.tagName) : ''")


def dentro(p, id_):
    return p.evaluate("(id) => { const a = document.activeElement, m = document.getElementById(id); return !!a && !!m && m.contains(a); }", id_)


def enlaces_admin_ocultos(p):
    return p.evaluate("[...document.querySelectorAll('.admin-only')].map((e) => e.hidden)")


# ------------------------------------------------------------------ 1. menu lateral
@prueba
def menu_lateral_teclado_foco_trampa_escape_y_restauracion(nav):
    s = B.Sesion(nav)
    p = s.abrir()
    # Cerrado: ningun descendiente entra al orden de tabulacion (inert) y no hay nada del menu enfocable.
    assert p.evaluate("document.getElementById('sideMenu').inert === true"), 'cerrado, el menu debe estar inert'
    p.evaluate("document.activeElement && document.activeElement.blur()")
    for _ in range(40):
        p.keyboard.press('Tab')
        assert not dentro(p, 'sideMenu'), 'con el menu cerrado el foco entró al menú: ' + activo_id(p)
    # Abrir con teclado.
    p.focus('#menuButton')
    p.keyboard.press('Enter')
    p.wait_for_timeout(500)
    assert p.evaluate("document.getElementById('sideMenu').classList.contains('open')"), 'Enter abre el menú'
    assert activo_id(p) == 'closeMenu', 'al abrir, el foco va a «Cerrar menú»: ' + activo_id(p)
    # Semantica de dialogo modal.
    sem = p.evaluate("(() => { const m = document.getElementById('sideMenu'); const n = m.getAttribute('aria-labelledby'); return { rol: m.getAttribute('role'), modal: m.getAttribute('aria-modal'), nombre: m.getAttribute('aria-label') || (n && document.getElementById(n) && document.getElementById(n).textContent.trim()), inert: m.inert }; })()")
    assert sem['rol'] == 'dialog' and sem['modal'] == 'true' and sem['nombre'] and not sem['inert'], sem
    # El fondo no recibe foco ni clic.
    assert p.evaluate("document.querySelector('.app').inert === true"), 'el contenido de fondo debe estar inert'
    # Tab y Shift+Tab no salen del menu.
    for _ in range(60):
        p.keyboard.press('Tab')
        assert dentro(p, 'sideMenu'), 'Tab salió del menú: ' + activo_id(p)
    for _ in range(60):
        p.keyboard.press('Shift+Tab')
        assert dentro(p, 'sideMenu'), 'Shift+Tab salió del menú: ' + activo_id(p)
    # Escape cierra y devuelve el foco al boton que lo abrio.
    p.keyboard.press('Escape')
    p.wait_for_timeout(500)
    assert not p.evaluate("document.getElementById('sideMenu').classList.contains('open')"), 'Escape cierra'
    assert activo_id(p) == 'menuButton', 'el foco vuelve a «Abrir menú»: ' + activo_id(p)
    assert p.evaluate("document.getElementById('sideMenu').inert === true && !document.querySelector('.app').inert")
    s.limpio('menu'); s.cerrar()


@prueba
def menu_lateral_con_raton_cierra_con_x_y_fondo_y_devuelve_foco(nav):
    s = B.Sesion(nav)
    p = s.abrir()
    p.click('#menuButton'); p.wait_for_timeout(400)
    assert activo_id(p) == 'closeMenu'
    p.click('#closeMenu'); p.wait_for_timeout(400)
    assert activo_id(p) == 'menuButton'
    p.click('#menuButton'); p.wait_for_timeout(400)
    p.mouse.click(40, 400); p.wait_for_timeout(400)      # fondo oscuro (el menú está a la derecha)
    assert not p.evaluate("document.getElementById('sideMenu').classList.contains('open')")
    s.limpio('menu raton'); s.cerrar()


# ------------------------------------------------------------------ 2. modales unificados
def verificar_modal(p, id_, abrir, abridor, escape_cierra=True):
    abrir()
    p.wait_for_timeout(500)
    assert p.evaluate("(id) => !document.getElementById(id).hidden", id_), id_ + ' debe abrirse'
    assert dentro(p, id_), id_ + ': el foco inicial debe estar dentro: ' + activo_id(p)
    rol = p.evaluate("(id) => { const c = document.getElementById(id).querySelector('[role=dialog]') || document.getElementById(id); return [c.getAttribute('role'), c.getAttribute('aria-modal')]; }", id_)
    assert rol == ['dialog', 'true'], (id_, rol)
    assert p.evaluate("document.querySelector('.app').inert === true"), id_ + ': el fondo debe estar inert'
    for _ in range(30):
        p.keyboard.press('Tab')
        assert dentro(p, id_), id_ + ': Tab salió de la ventana: ' + activo_id(p)
    for _ in range(30):
        p.keyboard.press('Shift+Tab')
        assert dentro(p, id_), id_ + ': Shift+Tab salió de la ventana: ' + activo_id(p)
    if escape_cierra:
        p.keyboard.press('Escape')
        p.wait_for_timeout(700)
        assert p.evaluate("(id) => document.getElementById(id).hidden", id_), id_ + ': Escape debe cerrar'
        assert not p.evaluate("document.querySelector('.app').inert"), id_ + ': al cerrar el fondo vuelve a la vida'
        assert activo_id(p) == abridor, id_ + ': el foco debe volver a ' + abridor + ' y está en ' + activo_id(p)


def desde_menu(p, boton):
    p.click('#menuButton'); p.wait_for_timeout(400)
    p.click(boton)


@prueba
def modales_ayuda_bienvenida_cuenta_ajustes_con_un_solo_controlador(nav):
    s = B.Sesion(nav)
    p = s.abrir()
    verificar_modal(p, 'ayudaModal', lambda: p.click('#helpButton'), 'helpButton')
    verificar_modal(p, 'bienvenida', lambda: desde_menu(p, '#menuBienvenida'), 'menuButton')
    verificar_modal(p, 'authModal', lambda: desde_menu(p, '#accountLogin'), 'menuButton')
    p.evaluate("document.getElementById('accountGuest').hidden = true; document.getElementById('accountUser').hidden = false;")
    verificar_modal(p, 'settingsModal', lambda: desde_menu(p, '#accountSettings'), 'menuButton')
    s.limpio('modales'); s.cerrar()


@prueba
def modales_nombre_y_reporte_con_un_solo_controlador(nav):
    s = B.Sesion(nav)
    p = s.abrir()
    p.fill('#tripOrigin', ORIGEN); p.keyboard.press('Enter'); p.wait_for_timeout(700)
    p.fill('#tripDestination', DEST); p.keyboard.press('Enter'); p.wait_for_timeout(700)
    p.evaluate("document.querySelector('#planTrip').click()")
    B.esperar(p, "!document.getElementById('tripTools').hidden", 12000)
    p.wait_for_timeout(800)
    verificar_modal(p, 'nameModal', lambda: p.click('#saveTrip'), 'saveTrip')
    verificar_modal(p, 'reportModal', lambda: p.click('#reportProblem'), 'reportProblem')
    s.limpio('modales 2'); s.cerrar()


@prueba
def modales_escape_cierra_solo_la_ventana_de_arriba_y_no_hay_manejadores_contradictorios(nav):
    s = B.Sesion(nav)
    p = s.abrir()
    p.click('#helpButton'); p.wait_for_timeout(400)
    p.click('#ayudaBien'); p.wait_for_timeout(700)               # de la ayuda a la bienvenida
    assert not p.evaluate("document.getElementById('bienvenida').hidden") and p.evaluate("document.getElementById('ayudaModal').hidden")
    p.keyboard.press('Escape'); p.wait_for_timeout(700)
    assert p.evaluate("document.getElementById('bienvenida').hidden"), 'Escape cierra la bienvenida'
    assert not p.evaluate("document.querySelector('.app').inert") and not p.evaluate("document.body.classList.contains('modal-open')"), 'sin modales abiertos no queda nada bloqueado'
    # Un único controlador: ningún módulo de ventanas conserva su propio manejador global de Escape (se comprueba por comportamiento: una sola pulsación = un solo cierre).
    p.click('#helpButton'); p.wait_for_timeout(400)
    p.keyboard.press('Escape'); p.wait_for_timeout(500)
    assert p.evaluate("document.getElementById('ayudaModal').hidden")
    assert activo_id(p) == 'helpButton'
    s.limpio('escape'); s.cerrar()


# ------------------------------------------------------------------ 3. enlaces administrativos
def con_config(nav, manejador, **opciones):
    s = B.Sesion(nav, **opciones)
    s.ctx.route('**/api/cuenta/config', manejador)
    return s


@prueba
def admin_html_inicial_oculto_y_respuesta_lenta_no_muestra_destello(nav):
    s = B.Sesion(nav)
    html = s.ctx.request.get(B.URL).text()
    assert html.count('class="admin-only" hidden') == 6, 'los seis enlaces nacen con hidden en el HTML inicial'
    s.cerrar()
    retenidas = []
    s = con_config(nav, lambda route: retenidas.append(route))
    p = s.abrir(espera=500)
    assert retenidas, 'la página pidió /api/cuenta/config'
    p.wait_for_timeout(2500)                                      # respuesta lenta: mientras tanto NO se muestra ningún enlace
    assert all(enlaces_admin_ocultos(p)), 'con la respuesta pendiente los enlaces siguen ocultos: ' + str(enlaces_admin_ocultos(p))
    retenidas[0].fulfill(status=200, content_type='application/json', body=json.dumps({'adminConfigurado': False}))
    B.esperar(p, "[...document.querySelectorAll('.admin-only')].every((e) => !e.hidden)", 5000)   # compatibilidad histórica: sin administradores se ven
    s.cerrar()


@prueba
def admin_con_administradores_configurados_y_sin_sesion_siguen_ocultos(nav):
    s = con_config(nav, lambda route: route.fulfill(status=200, content_type='application/json', body=json.dumps({'adminConfigurado': True})))
    p = s.abrir()
    assert all(enlaces_admin_ocultos(p)), 'con administradores configurados y sin sesión de administrador, ocultos'
    s.limpio('admin'); s.cerrar()


@prueba
def admin_con_config_fallando_o_sin_confirmar_se_mantienen_ocultos(nav):
    for caso in ('500', 'abortar', 'basura'):
        def manejar(route, caso=caso):
            if caso == '500':
                route.fulfill(status=500, content_type='application/json', body='{"message":"boom"}')
            elif caso == 'abortar':
                route.abort()
            else:
                route.fulfill(status=200, content_type='application/json', body='{"otra":1}')
        s = con_config(nav, manejar)
        p = s.abrir()
        assert all(enlaces_admin_ocultos(p)), 'config en estado ' + caso + ': los enlaces deben seguir ocultos'
        # Un valor anterior solo vale si fue confirmado: con «true» confirmado se mantiene oculto, no se abre por fallo.
        s.cerrar()
    s = con_config(nav, lambda route: route.abort())
    s.ctx.add_init_script("try { localStorage.setItem('spevb-admin-conf', 'true'); } catch (e) {}")
    p = s.abrir()
    assert all(enlaces_admin_ocultos(p)), 'valor previo confirmado «hay administradores»: ocultos'
    s.cerrar()


# ------------------------------------------------------------------ 4. mensajes para pasajeros
def texto_publico(p):
    return p.evaluate("document.getElementById('tripStatus').innerText + ' ' + document.getElementById('tripResults').innerText + ' ' + [...document.querySelectorAll('.spevb-toast')].map((t) => t.innerText).join(' ')")


PROHIBIDAS = ['/api/estado', 'healthy', 'PostGIS', 'editor']


@prueba
def error_del_servidor_muestra_acciones_para_el_pasajero_sin_detalle_tecnico(nav):
    s = B.Sesion(nav)
    s.ctx.route('**/api/viajes/planificar', lambda route: route.fulfill(status=500, content_type='application/json', body='{"message":"Fallo interno: PostGIS no healthy, revisa /api/estado"}'))
    p = s.abrir()
    p.fill('#tripOrigin', ORIGEN); p.keyboard.press('Enter'); p.wait_for_timeout(700)
    p.fill('#tripDestination', DEST); p.keyboard.press('Enter'); p.wait_for_timeout(700)
    p.evaluate("document.querySelector('#planTrip').click()")
    B.esperar(p, "!!document.querySelector('#tripResults .recuperar-acciones')", 8000)
    txt = texto_publico(p)
    for mala in PROHIBIDAS:
        assert mala.lower() not in txt.lower(), 'el pasajero ve texto técnico «' + mala + '»: ' + txt[:300]
    botones = p.evaluate("[...document.querySelectorAll('#tripResults .recuperar-acciones button')].map((b) => b.textContent.trim())")
    assert botones == ['Reintentar', 'Revisar A y B', 'Buscar rutas cercanas', 'Reportar el problema'], botones
    # «Reportar el problema» abre el reporte; «Reintentar» vuelve a pedir.
    p.click('#tripResults .recuperar-acciones button:has-text("Reportar el problema")'); p.wait_for_timeout(500)
    assert not p.evaluate("document.getElementById('reportModal').hidden")
    p.keyboard.press('Escape'); p.wait_for_timeout(400)
    s.cerrar()
    # Con ?debug=1 el detalle técnico sí se puede ver (y siempre va a la consola).
    s = B.Sesion(nav)
    s.ctx.route('**/api/viajes/planificar', lambda route: route.fulfill(status=500, content_type='application/json', body='{"message":"detalle-interno-xyz"}'))
    avisos = []
    p = s.page
    p.on('console', lambda m: avisos.append(m.text) if m.type == 'warning' else None)
    p.goto(B.URL + '?debug=1', wait_until='domcontentloaded')
    B.esperar(p, "!!(window.Spevb && Spevb.maps && Spevb.maps.map)", 20000); p.wait_for_timeout(1200)
    p.fill('#tripOrigin', ORIGEN); p.keyboard.press('Enter'); p.wait_for_timeout(700)
    p.fill('#tripDestination', DEST); p.keyboard.press('Enter'); p.wait_for_timeout(700)
    p.evaluate("document.querySelector('#planTrip').click()")
    B.esperar(p, "!!document.querySelector('#tripResults .recuperar-acciones')", 8000)
    assert any('detalle-interno-xyz' in a for a in avisos), 'el detalle técnico va a la consola: ' + str(avisos)
    s.cerrar()


@prueba
def sin_rutas_no_pide_usar_el_editor_y_ofrece_acciones(nav):
    s = B.Sesion(nav)
    s.ctx.route('**/api/viajes/planificar', lambda route: route.fulfill(status=200, content_type='application/json', body=json.dumps(
        {'opciones': [], 'alternativas': [], 'transbordos': [], 'alcance': 'Prueba'})))
    p = s.abrir()
    p.fill('#tripOrigin', ORIGEN); p.keyboard.press('Enter'); p.wait_for_timeout(700)
    p.fill('#tripDestination', DEST); p.keyboard.press('Enter'); p.wait_for_timeout(700)
    p.evaluate("document.querySelector('#planTrip').click()")
    B.esperar(p, "!!document.querySelector('#tripResults .planner-empty')", 8000)
    txt = texto_publico(p)
    for mala in PROHIBIDAS:
        assert mala.lower() not in txt.lower(), 'texto para administradores en la vista pública «' + mala + '»: ' + txt[:300]
    botones = p.evaluate("[...document.querySelectorAll('#tripResults .planner-empty button')].map((b) => b.textContent.trim())")
    assert botones == ['Revisar A y B', 'Buscar rutas cercanas', 'Reportar el problema'], botones
    s.limpio('sin rutas'); s.cerrar()


# ------------------------------------------------------------------ 5. estado de carga y recuperación
def preparar_viaje_retenido(nav, **o):
    s = B.Sesion(nav, **o)
    retenidas = []
    s.ctx.route('**/api/viajes/planificar', lambda route: retenidas.append(route))
    p = s.abrir()
    p.fill('#tripOrigin', ORIGEN); p.keyboard.press('Enter'); p.wait_for_timeout(700)
    p.fill('#tripDestination', DEST); p.keyboard.press('Enter'); p.wait_for_timeout(700)
    return s, p, retenidas


@prueba
def calculo_del_viaje_estado_de_carga_etapas_cancelar_y_foco(nav):
    s, p, retenidas = preparar_viaje_retenido(nav)
    texto = p.inner_text('#planTrip')
    p.focus('#planTrip'); p.keyboard.press('Enter')
    p.wait_for_timeout(300)
    p.keyboard.press('Enter'); p.wait_for_timeout(100)             # doble toque: no hay una segunda solicitud
    assert len(retenidas) == 1, 'el doble toque no debe duplicar la solicitud: ' + str(len(retenidas))
    assert p.inner_text('#planTrip').strip() == 'Buscando…', p.inner_text('#planTrip')
    assert p.get_attribute('#tripResults', 'aria-busy') == 'true'
    assert p.locator('.carga-estado .carga-barra').count() == 1, 'progreso indeterminado'
    assert not p.locator('.carga-etapa').inner_text().strip(), 'antes de 2,5 s no hay explicación de etapa'
    p.wait_for_timeout(2600)
    assert p.locator('.carga-etapa').inner_text().strip(), 'después de 2,5 s se explica la etapa'
    assert p.locator('.carga-acciones button').count() == 0 or not p.locator('.carga-acciones').is_visible()
    p.wait_for_timeout(5700)
    assert p.locator('.carga-acciones button:has-text("Cancelar")').count() == 1 and p.locator('.carga-acciones button:has-text("Reintentar")').count() == 1, 'después de 8 s se ofrece cancelar o reintentar'
    p.click('.carga-acciones button:has-text("Cancelar")'); p.wait_for_timeout(500)
    assert p.inner_text('#planTrip').strip() == texto.strip(), 'se restaura el texto del botón'
    assert p.get_attribute('#tripResults', 'aria-busy') is None
    assert not p.locator('#planTrip').is_disabled() and p.locator('.carga-estado').count() == 0
    assert activo_id(p) == 'planTrip', 'el foco vuelve al botón: ' + activo_id(p)
    for r in retenidas:
        try: r.abort()
        except Exception: pass  # noqa: BLE001
    s.cerrar()


@prueba
def calculo_del_viaje_exito_restaura_boton_y_aria_busy(nav):
    s = B.Sesion(nav)
    p = s.abrir()
    p.fill('#tripOrigin', ORIGEN); p.keyboard.press('Enter'); p.wait_for_timeout(700)
    p.fill('#tripDestination', DEST); p.keyboard.press('Enter'); p.wait_for_timeout(700)
    p.evaluate("document.querySelector('#planTrip').click()")
    B.esperar(p, "!!document.querySelector('#tripResults .trip-option')", 12000)
    p.wait_for_timeout(500)
    assert p.inner_text('#planTrip').strip() == 'Buscar rutas'
    assert p.get_attribute('#tripResults', 'aria-busy') is None and p.locator('.carga-estado').count() == 0
    assert not p.locator('#planTrip').is_disabled()
    s.limpio('exito'); s.cerrar()


@prueba
def busqueda_de_direccion_estado_de_carga_sin_duplicados_y_restauracion(nav):
    s = B.Sesion(nav)
    retenidas = []
    s.ctx.route('**/api/lugares?q=*', lambda route: retenidas.append(route))
    p = s.abrir()
    p.fill('#tripOrigin', 'Calle 38 con carrera 22')
    p.focus('#searchOrigin')
    p.keyboard.press('Enter'); p.wait_for_timeout(200)
    p.keyboard.press('Enter'); p.wait_for_timeout(200)
    assert len(retenidas) == 1, 'doble toque sobre «Buscar»: una sola solicitud, hubo ' + str(len(retenidas))
    assert p.evaluate("document.getElementById('searchOrigin').dataset.etiqueta") == 'Buscando…'
    assert p.get_attribute('#searchOrigin', 'aria-label') == 'Buscando…'
    assert p.evaluate("document.querySelector('#tripOrigin').closest('.endpoint-block').getAttribute('aria-busy')") == 'true'
    assert p.locator('.carga-estado .carga-barra').count() == 1
    retenidas[0].fulfill(status=200, content_type='application/json', body=json.dumps({'lugares': [], 'estado': 'SIN_COINCIDENCIAS', 'avisos': []}))
    p.wait_for_timeout(600)
    assert p.evaluate("document.getElementById('searchOrigin').dataset.etiqueta") == 'Buscar'
    assert p.get_attribute('#searchOrigin', 'aria-label') == 'Buscar dirección de origen'
    assert p.evaluate("document.querySelector('#tripOrigin').closest('.endpoint-block').getAttribute('aria-busy')") is None
    assert not p.locator('#searchOrigin').is_disabled() and p.locator('.carga-estado').count() == 0
    assert activo_id(p) == 'searchOrigin', 'el foco vuelve al botón: ' + activo_id(p)
    s.cerrar()


@prueba
def progreso_respeta_movimiento_reducido(nav):
    s, p, retenidas = preparar_viaje_retenido(nav, reduced_motion='reduce')
    p.evaluate("document.querySelector('#planTrip').click()"); p.wait_for_timeout(400)
    anim = p.evaluate("getComputedStyle(document.querySelector('.carga-barra'), '::after').animationName")
    assert anim == 'none', 'con movimiento reducido la barra no se anima: ' + anim
    for r in retenidas:
        try: r.abort()
        except Exception: pass  # noqa: BLE001
    s.cerrar()


# ------------------------------------------------------------------ 6. marca visible
@prueba
def marca_visible_es_sevb_en_titulo_metadatos_y_textos(nav):
    s = B.Sesion(nav)
    p = s.abrir()
    assert p.title().startswith('SEVB'), p.title()
    meta = p.evaluate("[...document.querySelectorAll('meta[name=description], meta[name=apple-mobile-web-app-title]')].map((m) => m.content).join(' | ')")
    assert 'SPEVB' not in meta and 'SEVB' in meta, meta
    visible = p.evaluate("document.body.innerText + ' ' + [...document.querySelectorAll('[aria-label],[title]')].map((e) => (e.getAttribute('aria-label') || '') + ' ' + (e.title || '')).join(' ')")
    assert 'SPEVB' not in visible, 'queda «SPEVB» visible'
    assert p.evaluate("document.querySelector('.brand h1').textContent.trim()") == 'SEVB'
    s.cerrar()
    for ruta in ('editor.html', 'lugares.html', 'paraderos.html', 'puentes.html', 'pasos.html', 'reportes.html'):
        s = B.Sesion(nav)
        html = s.ctx.request.get(B.URL + ruta).text()
        assert 'SPEVB' not in html, ruta + ' conserva «SPEVB» visible'
        s.cerrar()


# ------------------------------------------------------------------ 7. áreas de toque
ID_TOQUE = ['searchOrigin', 'searchDestination', 'pickOrigin', 'pickDestination', 'useMyLocation', 'swapTrip', 'helpButton', 'menuButton', 'planTrip', 'locateMe', 'resetMap']


def medir_toque(p, ids):
    return p.evaluate("""(ids) => ids.map((id) => { const e = document.getElementById(id); if (!e) return [id, null]; const r = e.getBoundingClientRect(); return [id, [Math.round(r.width * 10) / 10, Math.round(r.height * 10) / 10]]; })""", ids)


def verificar_toque(nav, nombre, **o):
    s = B.Sesion(nav, **o)
    p = s.abrir()
    p.fill('#tripOrigin', ORIGEN); p.keyboard.press('Enter'); p.wait_for_timeout(700)    # habilita «Borrar origen»
    for id_, caja in medir_toque(p, ID_TOQUE + ['clearOrigin']):
        assert caja, nombre + ': falta #' + id_
        assert caja[0] >= 43.5 and caja[1] >= 43.5, nombre + ': #' + id_ + ' mide ' + str(caja) + ' (mínimo 44 x 44)'
    campo = p.evaluate("[...document.querySelectorAll('.endpoint-field input')].map((i) => Math.round(i.getBoundingClientRect().width))")
    minimo = 280 if o.get('is_mobile') else 302      # el ancho de antes de 4.13.5 (los campos no se estrechan)
    assert all(w >= minimo for w in campo), nombre + ': los campos A/B quedaron estrechos: ' + str(campo)
    p.click('#menuButton'); p.wait_for_timeout(400)
    for id_, caja in medir_toque(p, ['closeMenu']):
        assert caja[0] >= 43.5 and caja[1] >= 43.5, nombre + ': #closeMenu mide ' + str(caja)
    s.limpio(nombre); s.cerrar()


@prueba
def areas_de_toque_44px_en_movil_390x844(nav):
    verificar_toque(nav, 'móvil 390x844', **MOVIL)


@prueba
def areas_de_toque_44px_en_escritorio_1280x800(nav):
    verificar_toque(nav, 'escritorio 1280x800', viewport={'width': 1280, 'height': 800})


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
    print(f'\n{len(PRUEBAS) if not filtro else "?"} pruebas de accesibilidad y carga; fallos: {len(fallos)} {fallos}')
    return 1 if fallos else 0


if __name__ == '__main__':
    sys.exit(correr(sys.argv[1] if len(sys.argv) > 1 else None))
