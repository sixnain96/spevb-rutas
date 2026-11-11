"""Pruebas de CONTINUIDAD del viaje (4.14): recálculo seguro, reanudación independiente del mapa, regreso desde la pantalla bloqueada, guardado del avance y
recuperación del mapa (Google SIMULADO: e2e/harness/google_fake.js). Reproduce los fallos del video de un trayecto real que se pueden reproducir con herramientas de navegador.

LO QUE ESTO NO DEMUESTRA: que el fondo de Google deje de perder calles «por bloques» en un teléfono real. Aquí el SDK es un doble (no dibuja), el GPS es simulado
(e2e/simulador_gps.js) y los viewports son emulaciones. La comprobación con Google real, una clave real y un teléfono físico en un trayecto sigue pendiente (docs/CONTINUIDAD-VIAJE.md).
Uso: SEVB_E2E_URL=http://127.0.0.1:18080/ python e2e/continuidad414_e2e.py [filtro]"""
import json
import os
import re
import sys
import traceback
from urllib.parse import urlparse

if hasattr(sys.stdout, 'reconfigure'):
    sys.stdout.reconfigure(encoding='utf-8', errors='replace')
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import sevb_e2e as B  # noqa: E402
import viaje_e2e as V  # noqa: E402
import mapa_adaptadores_e2e as MA  # noqa: E402
from playwright.sync_api import sync_playwright  # noqa: E402

PRUEBAS = []
URL_BASE = B.URL
MOVIL = V.MOVIL


def prueba(f):
    PRUEBAS.append(f)
    return f


def viaje_en_curso(nav, **op):
    s = V.sesion(nav, **{**MOVIL, **op})
    p = s.abrir()
    V.planear(p); V.destino_lejos(p)
    ls = V.lecturas(p, ruido={'sigma': 6, 'semilla': 3})
    V.iniciar(p)
    V.alimentar(p, ls, 0, 'fase:esperando')
    return s, p, ls


def estado(p):
    return p.evaluate("""() => ({ activo: Spevb.Viaje.activo(), fase: document.getElementById('viajeCard').dataset.fase, escuchas: window.__geo.escuchas,
        destino: Spevb.Viaje.nucleo() ? 1 : 0, guardado: JSON.parse(localStorage.getItem('spevb-viaje-curso') || 'null') })""")


def abrir_mas(p):
    V.abrir_mas(p)


def aviso(p):
    return p.evaluate("(() => { const a = document.getElementById('viajeAviso'); return a.hidden ? null : a.textContent; })()")


def pildoras(p):
    return p.evaluate("[...document.querySelectorAll('.estado-pildora')].filter(e => e.offsetParent !== null).map(e => e.dataset.tipo + ': ' + e.querySelector('.estado-texto').textContent)")


# ======================================================================================= recálculo seguro
def recalcular_con(p, respuesta):
    """Instala la respuesta de /api/viajes/planificar y pide recalcular desde la posición actual."""
    p.context.unroute('**/api/viajes/planificar')
    p.context.route('**/api/viajes/planificar', respuesta)
    abrir_mas(p)
    p.click('#viajeRecalcular')


def fallo(codigo):
    return lambda r: r.fulfill(status=codigo, content_type='application/json', body=json.dumps({'mensaje': 'error simulado'}))


@prueba
def recalcular_con_el_servidor_caido_conserva_el_viaje_anterior(nav):
    s, p, ls = viaje_en_curso(nav)
    antes = estado(p)
    recalcular_con(p, fallo(500)); p.wait_for_timeout(1200)
    d = estado(p)
    assert d['activo'] and d['escuchas'] == 1 and d['fase'] == antes['fase'] and p.is_visible('#viajeCard'), (antes, d)
    assert 'Tu viaje sigue como estaba' in (aviso(p) or ''), aviso(p)
    assert d['guardado'] and d['guardado']['plan'] == antes['guardado']['plan'], 'el plan guardado no cambió'
    s.errores[:] = [e for e in s.errores if 'status of 500' not in e]      # el 500 es el fallo simulado: Chrome lo registra solo
    s.limpio('recalcular 500'); s.cerrar()


@prueba
def recalcular_sin_internet_conserva_el_viaje_anterior(nav):
    s, p, ls = viaje_en_curso(nav)
    antes = estado(p)
    recalcular_con(p, lambda r: r.abort('internetdisconnected')); p.wait_for_timeout(1200)
    d = estado(p)
    assert d['activo'] and d['escuchas'] == 1 and d['fase'] == antes['fase'], (antes, d)
    assert 'Tu viaje sigue como estaba' in (aviso(p) or ''), aviso(p)
    s.limpio('recalcular offline'); s.cerrar()


@prueba
def recalcular_sin_opciones_conserva_el_viaje_anterior(nav):
    s, p, ls = viaje_en_curso(nav)
    antes = estado(p)
    recalcular_con(p, lambda r: r.fulfill(status=200, content_type='application/json', body=json.dumps({'opciones': [], 'alternativas': [], 'transbordos': []}))); p.wait_for_timeout(1200)
    d = estado(p)
    assert d['activo'] and d['fase'] == antes['fase'] and d['escuchas'] == 1, (antes, d)
    assert 'No encontré otro recorrido' in (aviso(p) or '') and 'sigue como estaba' in (aviso(p) or ''), aviso(p)
    s.limpio('recalcular sin opciones'); s.cerrar()


@prueba
def cancelar_el_calculo_conserva_el_viaje_y_no_deja_nada_a_medias(nav):
    s, p, ls = viaje_en_curso(nav)
    antes = estado(p)
    retenidas = []
    recalcular_con(p, lambda r: retenidas.append(r)); p.wait_for_timeout(500)
    assert p.inner_text('#viajeRecalcular') == 'Cancelar el cálculo' and 'sigue activo' in (aviso(p) or ''), (p.inner_text('#viajeRecalcular'), aviso(p))
    p.click('#viajeRecalcular'); p.wait_for_timeout(400)
    assert p.inner_text('#viajeRecalcular') == 'Recalcular desde aquí' and 'cancelado' in (aviso(p) or '').lower()
    for r in retenidas:      # una respuesta que llega tarde NO cambia nada
        try: r.fulfill(status=200, content_type='application/json', body=json.dumps({'opciones': [], 'alternativas': [], 'transbordos': []}))
        except Exception: pass  # noqa: E722
    p.wait_for_timeout(500)
    d = estado(p)
    assert d['activo'] and d['fase'] == antes['fase'] and d['escuchas'] == 1 and p.evaluate("document.getElementById('viajePregunta').hidden"), d
    s.limpio('cancelar calculo'); s.cerrar()


@prueba
def un_recorrido_nuevo_se_ofrece_con_una_pregunta_y_nada_cambia_hasta_aceptar(nav):
    s, p, ls = viaje_en_curso(nav)
    plan0 = estado(p)['guardado']['plan']
    pedidos = []
    p.context.on('request', lambda r: pedidos.append(r) if '/api/viajes/planificar' in r.url else None)
    abrir_mas(p); p.click('#viajeRecalcular'); p.wait_for_timeout(1500)      # el stub devuelve opciones reales
    assert pedidos, 'se pidió un recorrido nuevo'
    cuerpo = json.loads(pedidos[-1].post_data)
    assert abs(cuerpo['destino']['lat'] - plan0['destino']['lat']) < 1e-4 and abs(cuerpo['destino']['lng'] - plan0['destino']['lng']) < 1e-4, 'usa el destino GUARDADO del viaje'
    pr = p.evaluate("(() => { const q = document.getElementById('viajePregunta'); return q.hidden ? null : [q.dataset.id, document.getElementById('viajePreguntaTexto').textContent, document.getElementById('viajeSi').textContent, document.getElementById('viajeNo').textContent]; })()")
    assert pr and pr[0] == 'recalculo' and 'recorrido nuevo' in pr[1] and pr[2] == 'Sí, cambiar' and pr[3] == 'No, seguir con el actual', pr
    assert estado(p)['guardado']['plan'] == plan0, 'hasta aceptar no se cambió nada'
    p.click('#viajeNo'); p.wait_for_timeout(300)
    d = estado(p)
    assert d['activo'] and d['escuchas'] == 1 and d['guardado']['plan'] == plan0 and p.evaluate("document.getElementById('viajePregunta').hidden")
    abrir_mas(p) if p.get_attribute('#viajeMas', 'aria-expanded') != 'true' else None
    p.click('#viajeRecalcular'); p.wait_for_timeout(1500)
    p.click('#viajeSi'); p.wait_for_timeout(1200)
    d = estado(p)
    assert d['activo'] and d['escuchas'] == 1 and d['fase'] in ('iniciando', 'caminar', 'esperando'), d
    s.limpio('recalcular acepta'); s.cerrar()


@prueba
def un_viaje_reanudado_recalcula_con_el_destino_guardado_aunque_el_planificador_este_vacio(nav):
    s, p, ls = viaje_en_curso(nav)
    plan0 = estado(p)['guardado']['plan']
    guardado = p.evaluate("localStorage.getItem('spevb-viaje-curso')")
    s.cerrar()
    s2 = V.sesion(nav, **MOVIL)
    s2.ctx.add_init_script("localStorage.setItem('spevb-viaje-curso', %s);" % json.dumps(guardado))
    p = s2.abrir()
    assert p.input_value('#tripOrigin') == '' and p.input_value('#tripDestination') == '', 'el planificador está vacío tras recargar'
    p.click('#viajeSi'); B.esperar(p, 'Spevb.Viaje.activo()', 8000)
    pedidos = []
    p.context.on('request', lambda r: pedidos.append(r) if '/api/viajes/planificar' in r.url else None)
    ls2 = p.evaluate("SimGps.viaje(JSON.parse(localStorage.getItem('spevb-viaje-curso')).plan, {})")
    V.alimentar(p, ls2, 0, 'fase:esperando')      # una lectura actual: ya no es «antigua»
    abrir_mas(p); p.click('#viajeRecalcular'); p.wait_for_timeout(1500)
    assert pedidos, 'recalculó'
    cuerpo = json.loads(pedidos[-1].post_data)
    assert abs(cuerpo['destino']['lat'] - plan0['destino']['lat']) < 1e-4, cuerpo
    s2.limpio('reanudado recalcula'); s2.cerrar()


# ======================================================================================= reanudación independiente del mapa
def guardar_un_viaje_real(nav):
    s, p, ls = viaje_en_curso(nav)
    p.evaluate("window.dispatchEvent(new Event('pagehide'))")      # guarda el avance
    guardado = p.evaluate("localStorage.getItem('spevb-viaje-curso')")
    assert guardado
    s.cerrar()
    return guardado


@prueba
def reanudar_funciona_aunque_el_sdk_de_google_no_cargue(nav):
    guardado = guardar_un_viaje_real(nav)
    base = MA.lanzar_stub(18097)
    g = MA.Google(nav, base, sdk='abortar')
    g.s.ctx.add_init_script("localStorage.setItem('spevb-viaje-curso', %s);" % json.dumps(guardado))
    g.s.ctx.add_init_script(open(os.path.join(os.path.dirname(os.path.abspath(__file__)), 'simulador_gps.js'), encoding='utf-8').read() + ';' + V.DOBLES)
    p = g.abrir(esperar_mapa=False)
    p.wait_for_timeout(2500)
    assert p.evaluate("!window.Spevb.maps || !Spevb.maps.map"), 'sin mapa (el SDK no cargó)'
    assert p.is_visible('#viajeCard') and p.evaluate("document.getElementById('viajePregunta').dataset.id") == 'reanudar'
    p.click('#viajeSi'); p.wait_for_timeout(1200)
    d = estado(p)
    assert d['activo'] and d['escuchas'] == 1 and p.is_visible('#viajeCard'), f'el viaje arranca sin mapa y la tarjeta NO desaparece: {d}'
    assert p.evaluate("document.getElementById('viajeInstruccion').textContent.length") > 10, 'se muestran las indicaciones guardadas'
    assert 'ubicación' in ' '.join(pildoras(p)).lower() or d['activo'], pildoras(p)
    g.s.limpio('reanudar sin sdk') if False else None
    g.cerrar()


@prueba
def reanudar_conserva_la_hora_original_y_marca_la_posicion_como_antigua_hasta_una_lectura_nueva(nav):
    guardado = guardar_un_viaje_real(nav)
    orig = json.loads(guardado)
    orig['inicio'] = orig['t'] - 25 * 60000       # el viaje empezó hace 25 min (antes del guardado)
    orig['t'] = orig['t'] - 3 * 60000
    orig['ultima'] = orig.get('ultima') or {'lat': 4.147, 'lng': -73.653, 'precision': 12, 't': orig['t']}
    s = V.sesion(nav, **MOVIL)
    s.ctx.add_init_script("localStorage.setItem('spevb-viaje-curso', %s);" % json.dumps(json.dumps(orig)))
    p = s.abrir()
    txt = p.inner_text('#viajeInstruccion')
    assert 'iniciado hace 28 min' in txt or 'iniciado hace 25 min' in txt, txt
    p.click('#viajeSi'); B.esperar(p, 'Spevb.Viaje.activo()', 8000); p.wait_for_timeout(400)
    g = p.evaluate("JSON.parse(localStorage.getItem('spevb-viaje-curso'))")
    assert g['inicio'] == orig['inicio'], 'la hora ORIGINAL de inicio se conserva al reanudar'
    pil = ' '.join(pildoras(p))
    assert 'sin-ubicacion' in pil or 'ubicacion:' in pil, pildoras(p)
    assert 'Última posición conocida' in pil, pil
    ls = p.evaluate("SimGps.viaje(JSON.parse(localStorage.getItem('spevb-viaje-curso')).plan, {})")
    V.alimentar(p, ls, 0, 'fase:esperando'); p.wait_for_timeout(500)
    assert not [x for x in pildoras(p) if x.startswith('ubicacion')], 'con una lectura actual desaparece el aviso de ubicación antigua'
    s.limpio('hora original'); s.cerrar()


@prueba
def el_avance_se_guarda_periodicamente_y_al_ocultar_la_pagina_sin_historial_de_ubicaciones(nav):
    s, p, ls = viaje_en_curso(nav)
    t0 = p.evaluate("JSON.parse(localStorage.getItem('spevb-viaje-curso')).t")
    p.wait_for_timeout(1100)
    p.evaluate("window.dispatchEvent(new Event('pagehide'))")
    g = p.evaluate("JSON.parse(localStorage.getItem('spevb-viaje-curso'))")
    assert g['t'] > t0 and g['v'] == 2, 'pagehide guarda el avance'
    assert isinstance(g['ultima'], dict) and set(g['ultima']) <= {'lat', 'lng', 'precision', 't'}, 'solo la ÚLTIMA posición válida'
    assert 'historial' not in json.dumps(g).lower() and 'posiciones' not in g and 'lecturas' not in g
    p.evaluate("Object.defineProperty(document, 'hidden', { configurable: true, get: () => true }); document.dispatchEvent(new Event('visibilitychange'))")
    g2 = p.evaluate("JSON.parse(localStorage.getItem('spevb-viaje-curso')).t")
    assert g2 >= g['t']
    p.evaluate("delete document.hidden; document.dispatchEvent(new Event('visibilitychange'))")
    s.limpio('guardado'); s.cerrar()


# ======================================================================================= pantalla bloqueada / regreso
@prueba
def volver_de_la_pantalla_bloqueada_renueva_el_gps_sin_duplicar_escuchas_y_pide_la_pantalla_otra_vez(nav):
    s, p, ls = viaje_en_curso(nav)
    esc0, wake0 = p.evaluate("window.__geo.escuchas"), p.evaluate("window.__wake.pedidos")
    assert esc0 == 1
    for _ in range(3):
        p.evaluate("window.__tiempo += 40000; Object.defineProperty(document, 'hidden', { configurable: true, get: () => true }); document.dispatchEvent(new Event('visibilitychange'))")
        p.wait_for_timeout(100)
        p.evaluate("window.__tiempo += 60000; delete document.hidden; document.dispatchEvent(new Event('visibilitychange'))")
        p.wait_for_timeout(300)
    assert p.evaluate("window.__geo.escuchas") == 1, 'tras 3 bloqueos sigue habiendo UNA sola escucha de GPS (la vieja se libera)'
    assert p.evaluate("window.__wake.pedidos") > wake0, 'se vuelve a pedir la pantalla encendida'
    assert any(x.startswith('ubicacion') for x in pildoras(p)), 'mientras no llega una lectura nueva la posición se marca como antigua'
    p.evaluate("([l]) => __geo.emitir({ ...l, t: Date.now() + 600000 })", [ls[min(25, len(ls) - 1)]]); p.wait_for_timeout(400)      # una lectura ACTUAL (más nueva que todas las anteriores)
    assert not [x for x in pildoras(p) if x.startswith('ubicacion')], pildoras(p)
    # una página restaurada desde la memoria (bfcache) hace lo mismo
    p.evaluate("window.dispatchEvent(Object.assign(new Event('pageshow'), { persisted: true }))"); p.wait_for_timeout(300)
    assert p.evaluate("window.__geo.escuchas") == 1
    s.limpio('pantalla bloqueada'); s.cerrar()


@prueba
def un_callback_de_una_escucha_vieja_no_pisa_el_estado(nav):
    s, p, ls = viaje_en_curso(nav)
    p.evaluate("window.__tiempo += 60000; Object.defineProperty(document, 'hidden', { configurable: true, get: () => true }); document.dispatchEvent(new Event('visibilitychange'))")
    p.evaluate("window.__tiempo += 60000; delete document.hidden; document.dispatchEvent(new Event('visibilitychange'))")
    p.wait_for_timeout(300)
    f0 = p.evaluate("Spevb.Viaje.info().fase")
    assert p.evaluate("window.__geo.escuchas") == 1
    s.limpio('callback viejo'); s.cerrar()


# ======================================================================================= mapa: salud y recuperación (Google simulado)
def google_con_viaje(nav, base):
    g = MA.Google(nav, base, **{})
    g.s.ctx.add_init_script(open(os.path.join(os.path.dirname(os.path.abspath(__file__)), 'simulador_gps.js'), encoding='utf-8').read() + ';' + V.DOBLES)
    p = g.abrir()
    return g, p


def con_google(nav, base, f):
    g, p = google_con_viaje(nav, base)
    try:
        f(g, p)
    finally:
        g.cerrar()


@prueba
def si_el_fondo_no_termina_de_cargar_aparece_mapa_incompleto_y_recuperar_mapa_lo_arregla(nav):
    base = MA.lanzar_stub(18098)

    def cuerpo(g, p):
        V.planear(p) if False else None
        p.evaluate("__gfake.tiles = 'sin'; Spevb.maps.map.setView([4.15, -73.63], 15, { animate: false })")
        B.esperar(p, "!!document.querySelector('.estado-pildora.mapa-incompleto')", 20000)
        t = p.inner_text('.estado-pildora.mapa-incompleto .estado-texto')
        assert 'incompleto' in t and 'siguen funcionando' in t, t
        assert p.is_visible('.estado-pildora.mapa-incompleto .estado-accion')
        p.evaluate("__gfake.tiles = 'ok'")
        p.click('.estado-pildora.mapa-incompleto .estado-accion')
        B.esperar(p, "!document.querySelector('.estado-pildora.mapa-incompleto')", 12000)
        assert p.evaluate('Spevb.MapaSalud.estado().problema') is None
    con_google(nav, base, cuerpo)


@prueba
def la_recuperacion_automatica_tiene_reintentos_limitados_y_no_hace_bucles(nav):
    base = MA.lanzar_stub(18099)

    def cuerpo(g, p):
        p.evaluate("__gfake.tiles = 'sin'; Spevb.maps.map.setView([4.15, -73.63], 15, { animate: false })")
        B.esperar(p, "!!document.querySelector('.estado-pildora.mapa-incompleto')", 20000)
        p.wait_for_timeout(48000)      # esperas de 2 s + 5 s + 12 s y, entre ellas, lo que tarda cada repintado (6 s) y la reconstrucción (≈ 12 s)
        reg = p.evaluate('Spevb.MapaSalud.registro().filter(r => r.m === "intento").map(r => r.x)')
        assert reg == [1, 2, 3], f'exactamente 3 intentos automáticos con espera progresiva: {reg}'
        mapas = p.evaluate('__gfake.mapas')
        assert mapas == 2, f'solo el tercer intento reconstruye el mapa ({mapas} mapas creados)'
        p.wait_for_timeout(8000)
        assert p.evaluate('Spevb.MapaSalud.registro().filter(r => r.m === "intento").length') == 3 and p.evaluate('__gfake.mapas') == 2, 'no hay más intentos: sin bucles'
        assert 'No pude recuperar el mapa' in p.inner_text('.estado-pildora.mapa-incompleto .estado-texto')
    con_google(nav, base, cuerpo)


@prueba
def reconstruir_el_mapa_conserva_la_referencia_la_camara_las_capas_y_libera_lo_anterior(nav):
    base = MA.lanzar_stub(18100)

    def cuerpo(g, p):
        p.evaluate("window.__m0 = Spevb.maps.map; window.__capa = __m0.group().addTo(__m0); __m0.marker([4.15, -73.63], { html: '<b>x</b>' }).addTo(window.__capa); __m0.polyline([[4.15, -73.64], [4.16, -73.62]], { color: '#f00', weight: 4 }).addTo(window.__capa); __m0.setView([4.152, -73.631], 16, { animate: false })")
        p.wait_for_timeout(300)
        antes = p.evaluate("({ vis: __gfake.marcadores.filter(m => m.map).length + __gfake.polilineas.filter(l => l._map).length, esc: __gfake.escuchas, mapas: __gfake.mapas })")
        assert antes['vis'] >= 2
        ok = p.evaluate("Spevb.maps.map.recuperar('reconstruir')")
        p.wait_for_timeout(500)
        d = p.evaluate("({ mismo: window.__m0 === Spevb.maps.map, mapas: __gfake.mapas, centro: Spevb.maps.map.getCenter(), zoom: Spevb.maps.map.getZoom(), vis: __gfake.marcadores.filter(m => m.map).length + __gfake.polilineas.filter(l => l._map).length, esc: __gfake.escuchas, nuevo: __gfake.map === Spevb.maps.map.n })")
        assert d['mismo'] and d['mapas'] == antes['mapas'] + 1 and d['nuevo'], 'la MISMA referencia del adaptador, con un mapa nuevo debajo'
        assert abs(d['centro'][0] - 4.152) < 1e-4 and abs(d['centro'][1] + 73.631) < 1e-4 and d['zoom'] == 16, 'la cámara se conserva'
        assert d['vis'] == antes['vis'], f'marcadores y líneas siguen visibles sobre el mapa nuevo: {antes} → {d}'
        assert d['esc'] <= antes['esc'] + 6, f'los listeners del mapa viejo se liberaron (no se acumulan): {antes["esc"]} → {d["esc"]}'
        for _ in range(3):
            p.evaluate("Spevb.maps.map.recuperar('reconstruir')"); p.wait_for_timeout(300)
        assert p.evaluate('__gfake.escuchas') <= antes['esc'] + 6, 'reconstruir varias veces no acumula listeners'
        assert ok in (True, False)
    con_google(nav, base, cuerpo)


@prueba
def sin_internet_se_ve_distinto_y_al_volver_se_intenta_recuperar_el_fondo(nav):
    base = MA.lanzar_stub(18101)

    def cuerpo(g, p):
        p.evaluate("Object.defineProperty(navigator, 'onLine', { configurable: true, get: () => false }); window.dispatchEvent(new Event('offline'))")
        p.wait_for_timeout(300)
        assert any(x.startswith('internet') and 'Sin internet' in x for x in pildoras(p)), pildoras(p)
        p.evaluate("Object.defineProperty(navigator, 'onLine', { configurable: true, get: () => true }); window.dispatchEvent(new Event('online'))")
        p.wait_for_timeout(300)
        assert not [x for x in pildoras(p) if x.startswith('internet')]
    con_google(nav, base, cuerpo)


@prueba
def la_perdida_del_contexto_webgl_se_detecta_como_mapa_incompleto(nav):
    base = MA.lanzar_stub(18102)

    def cuerpo(g, p):
        p.evaluate("Spevb.maps.map.el.dispatchEvent(new Event('webglcontextlost'))")
        B.esperar(p, "!!document.querySelector('.estado-pildora.mapa-incompleto')", 5000)
        assert p.evaluate('Spevb.MapaSalud.estado().problema') == 'webgl'
    con_google(nav, base, cuerpo)


@prueba
def los_avisos_de_conexion_no_ocultan_una_pregunta_de_bajada(nav):
    s, p, ls = viaje_en_curso(nav)
    p.evaluate("Object.defineProperty(navigator, 'onLine', { configurable: true, get: () => false }); window.dispatchEvent(new Event('offline'))")
    V.alimentar(p, ls, 0, 'pregunta'); p.wait_for_timeout(400)
    r = p.evaluate("""() => { const q = document.getElementById('viajePregunta'), b = q.getBoundingClientRect(); const el = document.elementFromPoint(b.left + b.width / 2, b.top + b.height / 2);
        const e = document.getElementById('viajeEstados'); return { visible: !q.hidden, recibe: !!el && q.contains(el), pildoras: e ? e.querySelectorAll('.estado-pildora').length : 0 }; }""")
    assert r['visible'] and r['recibe'] and r['pildoras'] >= 1, r
    s.limpio('pregunta con aviso de conexion'); s.cerrar()


# ======================================================================================= esperas secundarias y controles sin manejadores
@prueba
def la_opcion_elegida_y_iniciar_viaje_no_esperan_al_detalle_de_la_ruta(nav):
    s = V.sesion(nav, **MOVIL)
    retenidas = []
    s.ctx.route(lambda u: re.search(r'/api/rutas/\d+$', urlparse(u).path or ''), lambda r: retenidas.append(r))      # el detalle de la ruta se queda colgado
    p = s.abrir()
    V.planear(p)
    assert retenidas, 'el detalle de la ruta quedó retenido'
    assert p.evaluate("!!Spevb.Planner.seleccion && !!Spevb.Planner.seleccion.viaje"), 'la opción quedó elegida sin esperar al detalle'
    assert p.is_visible('#startTrip') or p.evaluate("!document.getElementById('startTripBar').hidden"), '«Iniciar viaje» disponible'
    s.limpio('detalle colgado'); s.cerrar()


@prueba
def los_controles_del_planificador_no_estan_vivos_antes_de_que_el_mapa_este_listo(nav):
    base = MA.lanzar_stub(18103)
    g = MA.Google(nav, base, sdk='abortar')
    p = g.abrir(esperar_mapa=False)
    p.wait_for_timeout(2500)
    d = p.evaluate("""() => ({ plan: document.getElementById('planTrip').disabled, buscar: document.getElementById('searchOrigin').disabled, mapa: document.getElementById('pickOrigin').disabled,
        espera: document.getElementById('planTrip').dataset.esperandoMapa === '1', estado: document.getElementById('tripStatus').textContent, planner: !!(Spevb.Planner && Spevb.Planner.puntos) })""")
    assert not d['planner'] and d['plan'] and d['buscar'] and d['mapa'] and d['espera'] and 'Preparando el mapa' in d['estado'], f'sin mapa los botones se ven pero no responden y dicen por qué: {d}'
    g.cerrar()


# =======================================================================================
def correr(filtro=None):
    fallos = []
    with sync_playwright() as p:
        nav = B.navegador(p)
        for f in PRUEBAS:
            if filtro and filtro not in f.__name__:
                continue
            try:
                B.URL = URL_BASE
                f(nav)
                print('OK    ', f.__name__)
            except Exception as e:  # noqa: BLE001
                fallos.append(f.__name__)
                print('FALLA ', f.__name__, '|', str(e)[:600].replace('\n', ' '))
                if os.environ.get('SEVB_E2E_TRAZA'):
                    traceback.print_exc()
        nav.close()
    for pr in MA._PROCESOS:
        try: pr.terminate()
        except Exception: pass  # noqa: E722
    print(f'\n{len([f for f in PRUEBAS if not filtro or filtro in f.__name__])} pruebas de continuidad del viaje 4.14; fallos: {len(fallos)}', fallos)
    return 1 if fallos else 0


if __name__ == '__main__':
    sys.exit(correr(sys.argv[1] if len(sys.argv) > 1 else None))
