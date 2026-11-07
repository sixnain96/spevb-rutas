"""Pruebas de navegador de 4.14 (aprender a usar la app): bienvenida animada, ayuda en contexto, viaje de práctica, «Tu viaje de siempre»,
resumen de la semana, pestañita «Ahora escribe B», sin voz ni emojis, controles del bus en círculo y lenguaje simple.

Uso: SEVB_E2E_URL=http://127.0.0.1:18080/ python e2e/aprender_e2e.py [filtro]      (lo normal es e2e/run_e2e.py)
"""
import json
import os
import re
import sys
import traceback

if hasattr(sys.stdout, 'reconfigure'):
    sys.stdout.reconfigure(encoding='utf-8', errors='replace')
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import sevb_e2e as B  # noqa: E402
import viaje_e2e as V  # noqa: E402
from playwright.sync_api import sync_playwright  # noqa: E402

PRUEBAS = []
DEST = '4.1450, -73.6240'
ORIGEN = '4.1475, -73.6530'
MOVIL = {'viewport': {'width': 384, 'height': 848}, 'is_mobile': True, 'has_touch': True, 'device_scale_factor': 2}
EJEMPLO = {'a': {'id': None, 'lat': 4.1475, 'lng': -73.6530, 'nombre': 'Ejemplo A', 'direccion': None, 'fuente': 'Práctica', 'confirmado': True},
           'b': {'id': None, 'lat': 4.1450, 'lng': -73.6240, 'nombre': 'Ejemplo B', 'direccion': None, 'fuente': 'Práctica', 'confirmado': True}}


def prueba(f):
    PRUEBAS.append(f)
    return f


def planear(p, espera=2000):
    p.fill('#tripOrigin', ORIGEN); p.keyboard.press('Enter'); p.wait_for_timeout(700)
    p.fill('#tripDestination', DEST); p.keyboard.press('Enter'); p.wait_for_timeout(700)
    p.evaluate("document.querySelector('#planTrip').click()")
    B.esperar(p, "document.querySelectorAll('#tripResults .trip-option').length > 0", 12000)
    p.wait_for_timeout(espera)


def con_bienvenida(s):
    """El arnés marca la bienvenida como «ya vista»; aquí se quita para probarla de verdad (el marcador del arnés evita que la vuelva a poner)."""
    p = s.abrir()
    p.evaluate("localStorage.removeItem('spevb-bienvenida')")
    p.reload(); p.wait_for_timeout(2200)
    return p


# ------------------------------------------------------------------------------------------------ bienvenida
# ------------------------------------------------------------------------------------------------ ayuda en contexto
# ------------------------------------------------------------------------------------------------ viaje de práctica
@prueba
def la_bienvenida_sale_la_primera_vez_tiene_cinco_escenas_y_no_vuelve(nav):
    s = B.Sesion(nav, **MOVIL)
    p = con_bienvenida(s)
    assert p.is_visible('#bienvenida'), 'sale sola la primera vez'
    t1 = p.inner_text('#bienTitulo')
    assert 'qué bus tomar' in t1 and p.inner_text('#bienPasoTexto') == 'Paso 1 de 5' and p.is_hidden('#bienAtras') and p.is_visible('#bienSaltar')
    p.click('#bienSiguiente')
    assert 'de dónde sales' in p.inner_text('#bienTitulo') and p.is_visible('#bienAtras')
    p.click('#bienAtras')
    assert p.inner_text('#bienTitulo') == t1, 'Atrás vuelve a la escena anterior'
    p.click('#bienSiguiente'); p.click('#bienSiguiente')
    assert 'Compara y elige' in p.inner_text('#bienTitulo')
    p.click('#bienSiguiente')
    assert 'acompaño' in p.inner_text('#bienTitulo')
    p.click('#bienSiguiente')
    assert 'Practica sin riesgo' in p.inner_text('#bienTitulo') and p.inner_text('#bienPasoTexto') == 'Paso 5 de 5'
    assert p.inner_text('#bienPractica') == 'Aprender con una práctica' and p.is_visible('#bienPractica'), 'la práctica es una opción'
    assert p.inner_text('#bienSiguiente') == 'Empezar a explorar' and p.is_hidden('#bienSaltar'), 'y explorar es la otra: nadie está obligado'
    assert p.evaluate("document.querySelectorAll('#bienPuntos i').length") == 5
    p.click('#bienSiguiente'); p.wait_for_timeout(400)
    assert p.is_hidden('#bienvenida') and p.evaluate("localStorage.getItem('spevb-bienvenida')") == '2'
    assert p.evaluate("!document.body.classList.contains('modal-open')") and p.evaluate("!document.getElementById('coach')"), 'explorar no empieza la práctica ni deja capas'
    p.reload(); p.wait_for_timeout(2200)
    assert p.is_hidden('#bienvenida'), 'ya vista: no vuelve a salir sola'
    s.limpio('bienvenida'); s.cerrar()


@prueba
def saltar_la_bienvenida_la_marca_como_vista_y_el_menu_la_muestra_otra_vez(nav):
    s = B.Sesion(nav, **MOVIL)
    p = con_bienvenida(s)
    p.click('#bienSaltar'); p.wait_for_timeout(400)
    assert p.is_hidden('#bienvenida')
    p.reload(); p.wait_for_timeout(2200)
    assert p.is_hidden('#bienvenida')
    p.click('#menuButton'); p.wait_for_timeout(500)
    p.click('#menuBienvenida'); p.wait_for_timeout(900)
    assert p.is_visible('#bienvenida') and p.inner_text('#bienPasoTexto') == 'Paso 1 de 5', 'el menú la muestra otra vez desde la escena 1'
    p.keyboard.press('Escape'); p.wait_for_timeout(400)
    assert p.is_hidden('#bienvenida'), 'Escape la cierra'
    assert p.evaluate("!document.body.classList.contains('modal-open')")
    s.limpio('saltar bienvenida'); s.cerrar()


@prueba
def la_bienvenida_se_maneja_con_teclado_y_atrapa_el_foco(nav):
    s = B.Sesion(nav, **MOVIL)
    p = con_bienvenida(s)
    assert p.evaluate("document.activeElement.id") == 'bienSiguiente', 'el foco entra a la ventana'
    p.keyboard.press('ArrowRight'); p.wait_for_timeout(200)
    assert p.inner_text('#bienPasoTexto') == 'Paso 2 de 5'
    p.keyboard.press('ArrowLeft'); p.wait_for_timeout(200)
    assert p.inner_text('#bienPasoTexto') == 'Paso 1 de 5'
    vistos = set()
    for _ in range(8):
        p.keyboard.press('Tab')
        vistos.add(p.evaluate("document.activeElement.id || document.activeElement.tagName"))
        assert p.evaluate("!!document.activeElement.closest('#bienvenida')"), 'con Tab el foco no se escapa de la ventana'
    assert {'bienSiguiente', 'bienSaltar'} <= vistos, vistos
    p.keyboard.press('Escape'); p.wait_for_timeout(400)
    s.limpio('teclado bienvenida'); s.cerrar()


@prueba
def la_bienvenida_tiene_cinco_ilustraciones_propias_que_se_deslizan(nav):
    s = B.Sesion(nav, **MOVIL)
    p = con_bienvenida(s)
    r = p.evaluate("""() => ({ escenas: document.querySelectorAll('#bienIlustracion .bien-escena').length, svgs: document.querySelectorAll('#bienIlustracion svg.escena-svg').length,
        activas: document.querySelectorAll('#bienIlustracion .bien-escena.activa').length, mascota: document.querySelectorAll('#bienIlustracion image[href*="chiguiro"]').length,
        emojis: /\\p{Extended_Pictographic}/u.test(document.getElementById('bienIlustracion').textContent) })""")
    assert r['escenas'] == 5 and r['svgs'] == 5 and r['activas'] == 1 and not r['emojis'], r
    assert r['mascota'] == 2, 'la capibara solo sale en la bienvenida y en el cierre, de forma discreta'
    p.click('#bienSiguiente'); p.wait_for_timeout(900)
    clases = p.evaluate("[...document.querySelectorAll('#bienIlustracion .bien-escena')].map(e => e.className.replace('bien-escena', '').trim())")
    assert clases == ['pasada', 'activa', 'futura', 'futura', 'futura'], clases
    assert p.evaluate("document.getElementById('bienTextos').classList.contains('cambia')"), 'el texto entra con animación'
    p.click('#bienSiguiente'); p.wait_for_timeout(2300)
    assert p.evaluate("getComputedStyle(document.querySelector('.e3 .t2 .t-fondo')).stroke") == 'rgb(229, 57, 53)', 'la opción elegida queda resaltada'
    p.click('#bienSiguiente'); p.wait_for_timeout(6800)
    assert p.evaluate("getComputedStyle(document.querySelector('.e4 .n6 circle')).fill") == 'rgb(23, 102, 80)', 'el último hito del viaje queda marcado'
    pausadas = p.evaluate("[...document.querySelectorAll('#bienIlustracion svg.escena-svg')].map(s => s.animationsPaused())")
    assert pausadas == [True, True, True, False, True], f'solo se anima la escena que se ve: {pausadas}'
    s.limpio('ilustraciones'); s.cerrar()


@prueba
def la_bienvenida_respeta_reducir_movimiento(nav):
    s = B.Sesion(nav, reduced_motion='reduce', **MOVIL)
    p = con_bienvenida(s)
    assert p.is_visible('#bienvenida')
    assert p.evaluate("getComputedStyle(document.querySelector('#bienvenida .bienvenida-card')).animationName") == 'none'
    p.click('#bienSiguiente'); p.wait_for_timeout(300)
    assert p.evaluate("getComputedStyle(document.querySelector('.e2 .trazo-ab')).strokeDashoffset") in ('0px', '0'), 'con poco movimiento los trazos se ven completos'
    assert p.evaluate("getComputedStyle(document.querySelector('.e2 .tapa')).transform") != 'none', 'el texto de los campos se ve completo (sin «teclear»)'
    assert p.evaluate("[...document.querySelectorAll('#bienIlustracion svg.escena-svg')].every(s => s.animationsPaused())"), 'las animaciones SVG quedan quietas'
    p.click('#bienSaltar'); p.wait_for_timeout(300)
    assert p.is_hidden('#bienvenida')
    s.limpio('movimiento reducido'); s.cerrar()


@prueba
def la_bienvenida_cabe_en_celulares_chicos_tablet_escritorio_y_celular_girado(nav):
    for ancho, alto, movil in ((360, 640, True), (390, 844, True), (768, 1024, False), (1280, 800, False), (800, 360, True)):
        s = B.Sesion(nav, viewport={'width': ancho, 'height': alto}, is_mobile=movil, has_touch=movil)
        p = con_bienvenida(s)
        for i in range(5):
            r = p.evaluate("""() => { const c = document.querySelector('.bienvenida-card').getBoundingClientRect(), b = document.getElementById('bienSiguiente').getBoundingClientRect();
                return { ancho: document.documentElement.scrollWidth, vw: innerWidth, izq: c.left, der: c.right, arriba: c.top, abajo: c.bottom, vh: innerHeight, boton: b.height }; }""")
            assert r['ancho'] <= r['vw'] and r['izq'] >= -1 and r['der'] <= r['vw'] + 1, f'{ancho}x{alto} escena {i + 1}: desborda a los lados {r}'
            assert r['arriba'] >= -1 and r['abajo'] <= r['vh'] + 1, f'{ancho}x{alto} escena {i + 1}: la ventana se sale de la pantalla {r}'
            assert r['boton'] >= 44, f'{ancho}x{alto}: el botón es cómodo al tacto {r}'
            if i < 4: p.click('#bienSiguiente'); p.wait_for_timeout(250)
        s.limpio(f'bienvenida {ancho}x{alto}'); s.cerrar()


# ------------------------------------------------------------------------------------------------ ayuda en contexto
@prueba
def la_ayuda_cambia_segun_lo_que_se_ve_en_pantalla(nav):
    s = B.Sesion(nav, **MOVIL)
    p = s.abrir()
    assert p.is_visible('#helpButton') and p.get_attribute('#helpButton', 'aria-label').startswith('Ayuda')

    def ayuda():
        p.click('#helpButton'); p.wait_for_timeout(300)
        t = p.inner_text('#ayudaTitulo'); n = p.locator('#ayudaLista li').count(); demo = p.is_visible('#ayudaDemo')
        p.click('#ayudaCerrar'); p.wait_for_timeout(250)
        return t, n, demo
    t, n, demo = ayuda()
    assert t == 'Para empezar' and 3 <= n <= 4 and demo
    p.fill('#tripOrigin', ORIGEN); p.keyboard.press('Enter'); p.wait_for_timeout(900)
    assert ayuda()[0] == 'Te falta un punto'
    p.fill('#tripDestination', DEST); p.keyboard.press('Enter'); p.wait_for_timeout(900)
    assert ayuda()[0] == 'Ya casi'
    p.evaluate("document.querySelector('#planTrip').click()")
    B.esperar(p, "document.querySelectorAll('#tripResults .trip-option').length > 0", 12000)
    p.wait_for_timeout(800)
    assert ayuda()[0] in ('Tus opciones', 'Ya elegiste una ruta')
    p.evaluate("document.querySelector('#tripResults .trip-option summary').click()"); p.wait_for_timeout(700)
    t, n, demo = ayuda()
    assert t == 'Ya elegiste una ruta' and demo, 'con una ruta elegida la ayuda habla del mapa y de «Iniciar viaje»'
    p.click('#helpButton'); p.wait_for_timeout(300)
    p.keyboard.press('Escape'); p.wait_for_timeout(300)
    assert p.is_hidden('#ayudaModal'), 'Escape cierra la ayuda'
    s.limpio('ayuda'); s.cerrar()


@prueba
def la_ayuda_reconoce_la_falta_de_conexion_y_la_busqueda_en_curso(nav):
    s = B.Sesion(nav, **MOVIL)
    p = s.abrir()
    p.fill('#tripOrigin', ORIGEN); p.keyboard.press('Enter'); p.wait_for_timeout(700)
    p.fill('#tripDestination', DEST); p.keyboard.press('Enter'); p.wait_for_timeout(700)
    p.evaluate("(() => { const st = document.getElementById('tripStatus'); st.textContent = 'Comparando A y B con todos los recorridos guardados…'; st.className = 'status info'; })()")
    p.click('#helpButton'); p.wait_for_timeout(300)
    assert p.inner_text('#ayudaTitulo') == 'Buscando tus rutas' and not p.is_visible('#ayudaDemo')
    p.click('#ayudaCerrar'); p.wait_for_timeout(200)
    s.ctx.set_offline(True); p.wait_for_timeout(300)
    p.click('#helpButton'); p.wait_for_timeout(300)
    assert p.inner_text('#ayudaTitulo') == 'Algo no salió bien' and 'conexión' in p.inner_text('#ayudaLista')
    p.click('#ayudaCerrar'); p.wait_for_timeout(200)
    s.ctx.set_offline(False)
    s.limpio('ayuda sin conexion'); s.cerrar()


@prueba
def la_ayuda_resalta_la_accion_recomendada_y_ofrece_una_demostracion_y_ayudas_rapidas(nav):
    s = B.Sesion(nav, **MOVIL)
    p = s.abrir()
    p.click('#helpButton'); p.wait_for_timeout(300)
    assert p.is_visible('#ayudaDemo') and p.inner_text('#ayudaDemo') == 'Ver cómo se busca'
    p.click('#ayudaCerrar'); p.wait_for_timeout(500)
    assert p.evaluate("document.getElementById('tripOrigin').classList.contains('ayuda-resalta')"), 'al cerrar se resalta el campo que toca llenar'
    assert p.evaluate("document.activeElement.id") == 'helpButton', 'el foco vuelve al botón «?»'
    # la demostración reutiliza una escena de la bienvenida (una sola, sin navegación) y no cuenta como bienvenida vista
    p.evaluate("localStorage.removeItem('spevb-bienvenida')")
    p.click('#helpButton'); p.wait_for_timeout(300)
    p.click('#ayudaDemo'); p.wait_for_timeout(900)
    assert p.is_visible('#bienvenida') and p.get_attribute('.bienvenida-card', 'data-modo') == 'leccion'
    assert p.inner_text('#bienPasoTexto') == 'Ayuda rápida' and p.inner_text('#bienSiguiente') == 'Entendido' and p.is_hidden('#bienAtras') and p.is_hidden('#bienPuntos') and p.is_hidden('#bienSaltar')
    p.click('#bienSiguiente'); p.wait_for_timeout(500)
    assert p.is_hidden('#bienvenida') and p.evaluate("localStorage.getItem('spevb-bienvenida')") is None, 'una ayuda rápida no marca la bienvenida como vista'
    # ayudas rápidas: siete temas (seis con ilustración y una con consejos)
    p.click('#helpButton'); p.wait_for_timeout(300)
    p.click('.ayuda-temas summary'); p.wait_for_timeout(200)
    assert p.locator('#ayudaTemas .tema-chip').count() == 7
    p.click('#ayudaTemas .tema-chip:has-text("Si no aparece una ruta")'); p.wait_for_timeout(300)
    assert p.inner_text('#ayudaTitulo') == 'Si no aparece una ruta' and p.locator('#ayudaLista li').count() == 3
    p.click('#ayudaCerrar'); p.wait_for_timeout(300)
    p.click('#helpButton'); p.wait_for_timeout(300); p.click('.ayuda-temas summary'); p.wait_for_timeout(200)
    p.click('#ayudaTemas .tema-chip:has-text("transbordos")'); p.wait_for_timeout(900)
    assert p.is_visible('#bienvenida') and 'transbordos' in p.inner_text('#bienTitulo') and p.get_attribute('.bienvenida-card', 'data-escena') == '2'
    p.keyboard.press('Escape'); p.wait_for_timeout(500)
    assert p.evaluate("document.activeElement.id") == 'helpButton' and p.evaluate("!document.body.classList.contains('modal-open')")
    s.limpio('ayuda demo'); s.cerrar()


# ------------------------------------------------------------------------------------------------ práctica guiada de 7 pasos
def hasta_paso(p, n, espera=15000):
    B.esperar(p, "!!document.querySelector('.coach-paso') && /paso " + str(n) + "( |$)/i.test(document.querySelector('.coach-paso').textContent)", espera)


def siguiente(p, veces=1):
    for _ in range(veces):
        p.click('.coach-siguiente'); p.wait_for_timeout(250)


AUTO = "(ej) => { window.__practica = Spevb.Guia.practica({ ejemplo: ej, pausa: 60 }); }"


@prueba
def el_viaje_de_practica_tiene_siete_pasos_con_acciones_reales_y_da_el_logro_aprendiz(nav):
    s = B.Sesion(nav, **MOVIL)
    p = s.abrir()
    p.evaluate(AUTO, EJEMPLO)
    hasta_paso(p, 3, 8000)
    assert p.input_value('#tripOrigin') == 'Ejemplo A' and p.input_value('#tripDestination') == 'Ejemplo B', 'los pasos 1 y 2 ponen A y B'
    assert 'Buscar rutas' in p.inner_text('.coach-texto') and p.is_hidden('.coach-siguiente'), 'paso 3: se pide tocar «Buscar rutas»'
    assert p.evaluate("getComputedStyle(document.querySelector('.coach-hueco')).display") == 'block', 'el botón queda resaltado'
    p.evaluate("document.querySelector('#planTrip').click()")
    hasta_paso(p, 4)
    assert p.evaluate("document.querySelector('.coach-progreso i.hecho') !== null && document.querySelectorAll('.coach-progreso i').length") == 7
    cosas = []
    for k in range(5):                                   # nombre, tiempo, caminata, subir y bajar, transbordo: una cosa por vez
        cosas.append(p.get_attribute('#coach', 'data-sel') or 'texto')
        assert f'{k + 1} de 5' in p.inner_text('.coach-paso').lower()
        siguiente(p)
    assert any('route-chip' in c for c in cosas) and any('opt-eta' in c for c in cosas) and any('result-metrics' in c for c in cosas), cosas
    hasta_paso(p, 5)
    siguiente(p)                                         # si no eligió una opción, la práctica la abre por ella
    for _ in range(4):
        if p.evaluate("/paso 6/.test(document.querySelector('.coach-paso').textContent)"): break
        siguiente(p)
    hasta_paso(p, 6, 4000)
    assert 'Iniciar viaje' in p.inner_text('.coach-texto') and 'no usa tu ubicación' in p.inner_text('.coach-texto')
    assert p.evaluate("!document.getElementById('viajeCard') || document.getElementById('viajeCard').hidden"), 'la práctica no inicia ningún viaje real'
    siguiente(p); hasta_paso(p, 7)
    assert p.inner_text('.coach-siguiente') == 'Terminar práctica' and p.is_visible('.coach-alt') and p.inner_text('.coach-alt') == 'Hacer mi viaje real'
    p.click('.coach-siguiente'); p.wait_for_timeout(900)
    assert p.evaluate("!document.getElementById('coach')"), 'la capa guiada se retira'
    assert p.evaluate("Spevb.Logros.leer().hitos.includes('practica')"), 'logro «Aprendiz»'
    assert p.evaluate("Spevb.Datos.leer('historial').length") == 0 and p.evaluate("Spevb.Siempre.leer().length") == 0, 'la búsqueda de práctica no ensucia el historial ni «Tu viaje de siempre»'
    s.limpio('practica'); s.cerrar()


@prueba
def la_practica_no_pide_ni_usa_el_gps(nav):
    s = B.Sesion(nav, **MOVIL)
    s.ctx.add_init_script("window.__gps = 0; if (navigator.geolocation) { for (const m of ['getCurrentPosition', 'watchPosition']) { const o = navigator.geolocation[m].bind(navigator.geolocation); navigator.geolocation[m] = (...a) => { window.__gps++; return o(...a); }; } }")
    p = s.abrir()
    p.evaluate(AUTO, EJEMPLO)
    hasta_paso(p, 3, 8000)
    p.evaluate("document.querySelector('#planTrip').click()"); hasta_paso(p, 4)
    siguiente(p, 5)
    for _ in range(4):
        if p.evaluate("!document.getElementById('coach')"): break
        siguiente(p)
    p.wait_for_timeout(800)
    assert p.evaluate("window.__gps") == 0, 'la práctica no llama al GPS'
    assert p.evaluate("!Spevb.Viaje || !Spevb.Viaje.activo()")
    s.limpio('practica sin gps'); s.cerrar()


@prueba
def salir_de_la_practica_en_cualquier_momento_no_da_logro_ni_deja_restos(nav):
    s = B.Sesion(nav, **MOVIL)
    p = s.abrir()
    p.evaluate(AUTO, EJEMPLO)
    B.esperar(p, "!!document.getElementById('coach')", 5000)
    p.click('.coach-salir'); p.wait_for_timeout(900)
    assert p.evaluate("!document.getElementById('coach')") and not p.evaluate("document.body.classList.contains('practicando')")
    assert not p.evaluate("Spevb.Logros.leer().hitos.includes('practica')")
    assert p.input_value('#tripOrigin') == '', 'sin nada previo, queda vacío'
    # también con Escape y desde el menú
    p.click('#menuButton'); p.wait_for_timeout(500)
    p.click('#menuPractica'); p.wait_for_timeout(1200)
    assert p.evaluate("!!document.getElementById('coach')"), 'el menú inicia la práctica'
    p.keyboard.press('Escape'); p.wait_for_timeout(900)
    assert p.evaluate("!document.getElementById('coach')")
    assert p.evaluate("document.querySelectorAll('.coach, .coach-fin, .coach-demo, .coach-bloqueo').length") == 0, 'no quedan capas sobre la pantalla'
    s.limpio('salir practica'); s.cerrar()


@prueba
def la_practica_conserva_y_devuelve_lo_que_la_persona_tenia(nav):
    s = B.Sesion(nav, **MOVIL)
    p = s.abrir()
    planear(p, 800)
    antes = p.evaluate("({ a: Spevb.Planner.puntos().origen.lat, b: Spevb.Planner.puntos().destino.lat, nombreA: document.getElementById('tripOrigin').value, nombreB: document.getElementById('tripDestination').value })")
    p.evaluate(AUTO, EJEMPLO)
    hasta_paso(p, 3, 8000)
    assert p.input_value('#tripOrigin') == 'Ejemplo A', 'durante la práctica se usan los puntos de ejemplo'
    p.click('.coach-salir'); p.wait_for_timeout(900)
    despues = p.evaluate("({ a: Spevb.Planner.puntos().origen.lat, b: Spevb.Planner.puntos().destino.lat, nombreA: document.getElementById('tripOrigin').value, nombreB: document.getElementById('tripDestination').value })")
    assert despues == antes, f'al salir vuelven tu origen y tu destino: {antes} / {despues}'
    B.esperar(p, "document.querySelectorAll('#tripResults .trip-option').length > 0", 12000)      # y los resultados que tenías
    assert p.evaluate("Spevb.Datos.leer('historial').length") == 1, 'el viaje real anterior sigue en el historial una sola vez'
    s.limpio('practica restaura'); s.cerrar()


@prueba
def en_la_practica_se_puede_volver_atras_y_la_persona_puede_poner_el_punto_por_su_cuenta(nav):
    s = B.Sesion(nav, **MOVIL)
    p = s.abrir()
    p.evaluate("(ej) => { window.__practica = Spevb.Guia.practica({ ejemplo: ej }); }", EJEMPLO)       # sin «pausa»: la persona hace los pasos
    hasta_paso(p, 1, 6000)
    assert p.is_hidden('.coach-atras') and p.inner_text('.coach-siguiente') == 'Poner un ejemplo'
    p.fill('#tripOrigin', ORIGEN); p.keyboard.press('Enter')                                       # lo hace ella misma
    hasta_paso(p, 2, 8000)
    assert 'Origen puesto' in p.inner_text('.coach-ok') or p.evaluate("Spevb.Planner.puntos().origen !== null"), 'se confirma lo que hizo'
    assert p.is_visible('.coach-atras')
    p.click('.coach-atras'); hasta_paso(p, 1, 4000)
    assert 'Ya tienes tu origen' in p.inner_text('.coach-texto') and p.inner_text('.coach-siguiente') == 'Siguiente', 'al volver no se pierde lo hecho'
    p.click('.coach-siguiente'); hasta_paso(p, 2, 4000)
    p.click('.coach-siguiente')                                                                  # «Poner un ejemplo» para el destino
    hasta_paso(p, 3, 8000)
    assert p.input_value('#tripDestination') != ''
    p.click('.coach-salir'); p.wait_for_timeout(700)
    s.limpio('practica atras'); s.cerrar()


@prueba
def si_el_servicio_de_rutas_falla_la_practica_sigue_con_un_ejemplo_marcado_como_simulacion(nav):
    s = B.Sesion(nav, **MOVIL)
    p = s.abrir()
    p.route('**/api/viajes/planificar', lambda r: r.fulfill(status=503, content_type='application/json', body='{"status":503,"message":"Servicio no disponible"}'))
    p.evaluate(AUTO, EJEMPLO)
    hasta_paso(p, 3, 8000)
    p.evaluate("document.querySelector('#planTrip').click()")
    B.esperar(p, "!document.querySelector('.coach-alt').hidden", 15000)
    assert 'Ahora no pude traer rutas reales' in p.inner_text('.coach-texto') and p.inner_text('.coach-alt') == 'Ver un ejemplo ilustrado'
    p.click('.coach-alt'); hasta_paso(p, 4, 4000)
    assert p.is_visible('.coach-simulacion') and 'simulación' in p.inner_text('.coach-simulacion'), 'se dice claramente que es una simulación'
    assert p.evaluate("!!document.querySelector('#coachDemo .demo-sello') && /simulaci/i.test(document.querySelector('#coachDemo .demo-sello').textContent)")
    assert p.evaluate("document.querySelectorAll('#tripResults .trip-option').length") == 0, 'no se mezclan resultados ficticios con los reales'
    for _ in range(5): siguiente(p)
    hasta_paso(p, 5, 4000)
    assert p.evaluate("document.querySelector('.coach').dataset.demo") == 'mapa' and p.is_visible('#coachDemo .demo-mapa')
    siguiente(p); hasta_paso(p, 6, 4000)
    assert p.evaluate("document.querySelector('.coach').dataset.demo") == 'iniciar'
    siguiente(p); hasta_paso(p, 7, 4000)
    p.click('.coach-siguiente'); p.wait_for_timeout(900)
    assert p.evaluate("Spevb.Logros.leer().hitos.includes('practica')"), 'también se completa con la demostración'
    assert p.evaluate("!document.getElementById('coach') && !document.getElementById('coachDemo')")
    s.errores[:] = [e for e in s.errores if '503' not in e]        # el 503 lo provoca la propia prueba
    s.limpio('practica servicio caido'); s.cerrar()


@prueba
def durante_la_practica_el_menu_y_el_resto_de_la_pantalla_quedan_bloqueados_tambien_con_teclado(nav):
    s = B.Sesion(nav, **MOVIL)
    p = s.abrir()
    p.evaluate(AUTO, EJEMPLO)
    hasta_paso(p, 3, 8000)
    p.wait_for_timeout(700)
    r = p.evaluate("""() => { const quien = (id) => { const b = document.getElementById(id).getBoundingClientRect(); const e = document.elementFromPoint(b.left + b.width / 2, b.top + b.height / 2);
            return e ? (e.className || e.id || e.tagName) : null; };
        const plan = document.getElementById('planTrip').getBoundingClientRect(), e = document.elementFromPoint(plan.left + plan.width / 2, plan.top + plan.height / 2);
        return { menu: quien('menuButton'), ayuda: quien('helpButton'), buscarLibre: e === document.getElementById('planTrip') || document.getElementById('planTrip').contains(e) }; }""")
    assert 'coach-bloqueo' in str(r['menu']) and 'coach-bloqueo' in str(r['ayuda']), f'el menú y «?» quedan bloqueados: {r}'
    assert r['buscarLibre'], 'el botón señalado sí se puede tocar'
    for _ in range(7):                                    # Tab solo recorre lo señalado y los botones de la tarjeta
        p.keyboard.press('Tab')
        assert p.evaluate("!!document.activeElement.closest('#planTrip, .coach-tarjeta') || document.activeElement.id === 'planTrip'"), p.evaluate("document.activeElement.id || document.activeElement.className")
    assert not p.evaluate("document.getElementById('sideMenu').classList.contains('open')")
    assert p.evaluate("document.querySelectorAll('.coach-dedo svg').length") == 1 and p.evaluate("!!document.querySelector('.coach-guia')")
    assert p.get_attribute('.coach-tarjeta', 'role') == 'dialog' and p.get_attribute('.coach-texto', 'aria-live') == 'polite'
    p.click('.coach-salir'); p.wait_for_timeout(700)
    assert p.evaluate("!document.getElementById('coach')")
    s.limpio('practica bloqueada'); s.cerrar()


@prueba
def el_coach_no_mide_la_pantalla_en_cada_cuadro_y_sigue_al_elemento_cuando_se_mueve(nav):
    s = B.Sesion(nav, **MOVIL)
    p = s.abrir()
    p.add_init_script("window.__raf = 0; const o = window.requestAnimationFrame.bind(window); window.requestAnimationFrame = (f) => { if (new Error().stack.includes('guia.js')) window.__raf++; return o(f); };")
    p.reload(); p.wait_for_timeout(1500)
    p.evaluate(AUTO, EJEMPLO)
    hasta_paso(p, 3, 8000)
    for _ in range(40):                                   # se espera a que se calme lo que mueve la pantalla (animaciones que terminan)
        c0 = p.evaluate("window.__raf"); p.wait_for_timeout(1200)
        if p.evaluate("window.__raf") == c0: break
    antes = p.evaluate("window.__raf"); p.wait_for_timeout(3000); despues = p.evaluate("window.__raf")
    assert despues - antes < 25, f'en reposo el coach casi no pide cuadros: {despues - antes} en 3 s (antes eran ≈ 180 solo de él)'
    x0 = p.evaluate("document.querySelector('.coach-hueco').getBoundingClientRect().top")
    p.set_viewport_size({'width': 384, 'height': 560}); p.wait_for_timeout(900)          # cambia el tamaño (teclado, giro…): el resaltado sigue al botón
    r = p.evaluate("""() => { const h = document.querySelector('.coach-hueco').getBoundingClientRect(), b = document.getElementById('planTrip').getBoundingClientRect(),
        t = document.querySelector('.coach-tarjeta').getBoundingClientRect();
        return { dif: Math.abs((h.top + h.height / 2) - (b.top + b.height / 2)), tapa: !(t.bottom <= b.top || t.top >= b.bottom), vh: innerHeight, abajo: t.bottom }; }""")
    assert r['dif'] < 8 and not r['tapa'] and r['abajo'] <= r['vh'] + 1, f'el resaltado sigue al botón y la tarjeta no lo tapa: {r}'
    p.click('.coach-salir'); p.wait_for_timeout(600)
    antes = p.evaluate("window.__raf"); p.wait_for_timeout(1500)
    assert p.evaluate("window.__raf") - antes < 6, 'al salir no queda ningún bucle de animación del coach'
    s.limpio('coach eventos'); s.cerrar()


@prueba
def la_practica_se_puede_repetir_y_con_poco_movimiento_no_anima(nav):
    s = B.Sesion(nav, reduced_motion='reduce', **MOVIL)
    p = s.abrir()
    for vuelta in range(2):
        p.evaluate(AUTO, EJEMPLO)
        hasta_paso(p, 3, 8000)
        assert p.evaluate("getComputedStyle(document.querySelector('.coach-hueco .onda')).animationName") == 'none', 'sin animaciones con poco movimiento'
        p.click('.coach-salir'); p.wait_for_timeout(700)
        assert p.evaluate("!document.getElementById('coach')"), f'vuelta {vuelta + 1}: se retira'
    p.evaluate(AUTO, EJEMPLO); hasta_paso(p, 3, 8000)
    p.evaluate("document.querySelector('#planTrip').click()"); hasta_paso(p, 4)
    siguiente(p, 5)
    for _ in range(4):
        if p.evaluate("!document.getElementById('coach')"): break
        siguiente(p)
    p.wait_for_timeout(900)
    assert p.evaluate("Spevb.Logros.leer().hitos.includes('practica')") and p.evaluate("getComputedStyle(document.querySelector('#coachFin .fin-chulo circle')).animationName") == 'none'
    s.limpio('practica repetida'); s.cerrar()


@prueba
def la_practica_funciona_en_celular_pequeno_y_la_tarjeta_nunca_tapa_lo_que_ensena(nav):
    for ancho, alto in ((360, 640), (390, 844), (768, 1024), (1280, 800)):
        s = B.Sesion(nav, viewport={'width': ancho, 'height': alto}, is_mobile=ancho < 600, has_touch=ancho < 600)
        p = s.abrir()
        p.evaluate(AUTO, EJEMPLO)
        hasta_paso(p, 3, 8000)
        p.wait_for_timeout(800)
        r = p.evaluate("""() => { const b = document.getElementById('planTrip').getBoundingClientRect(), t = document.querySelector('.coach-tarjeta').getBoundingClientRect();
            return { tapa: !(t.bottom <= b.top + 1 || t.top >= b.bottom - 1), dentro: t.top >= -1 && t.bottom <= innerHeight + 1 && t.left >= -1 && t.right <= innerWidth + 1, ancho: document.documentElement.scrollWidth <= innerWidth }; }""")
        assert not r['tapa'] and r['dentro'] and r['ancho'], f'{ancho}x{alto}: {r}'
        p.click('.coach-salir'); p.wait_for_timeout(600)
        s.limpio(f'practica {ancho}x{alto}'); s.cerrar()


# ------------------------------------------------------------------------------------------------ tu viaje de siempre
@prueba
def tu_viaje_de_siempre_se_ofrece_si_se_repite_a_esa_hora_y_se_busca_con_un_toque(nav):
    s = B.Sesion(nav, **MOVIL)
    p = s.abrir()
    r = p.evaluate("""([ej]) => { const S = Spevb.Siempre, ahora = new Date(2026, 5, 10, 7, 30), dia = 86400000;
        const hoy7 = new Date(2026, 5, 10, 7, 40).getTime();
        const nada = S.candidato(ahora);
        S.registrar(ej.a, ej.b, hoy7 - 1 * dia);
        const una = S.candidato(ahora);
        S.registrar(ej.a, ej.b, hoy7 - 2 * dia);
        const dos = S.candidato(ahora);
        const tarde = S.candidato(new Date(2026, 5, 10, 19, 0));            // a las 7 p. m. no es «su hora»
        return { nada, una, dos: dos && dos.aEstaHora, tarde }; }""", [EJEMPLO])
    assert r['nada'] is None and r['una'] is None and r['dos'] == 2 and r['tarde'] is None, r
    # 4 veces a cualquier hora también cuenta
    p.evaluate("localStorage.removeItem('spevb-habitos')")
    r = p.evaluate("""([ej]) => { const S = Spevb.Siempre, dia = 86400000, base = new Date(2026, 5, 10, 12, 0).getTime();
        for (let i = 1; i <= 4; i++) S.registrar(ej.a, ej.b, base - i * dia - i * 3 * 3600000);
        return { cualquiera: !!S.candidato(new Date(2026, 5, 10, 23, 0)), viejo: (() => { localStorage.removeItem('spevb-habitos'); S.registrar(ej.a, ej.b, base - 60 * dia); S.registrar(ej.a, ej.b, base - 61 * dia); return S.candidato(new Date(2026, 5, 10, 12, 0)); })() }; }""", [EJEMPLO])
    assert r['cualquiera'] is True and r['viejo'] is None, f'los hábitos de hace más de 45 días no cuentan: {r}'
    # ahora con la hora real: tres veces a esta hora → aparece la tarjeta
    p.evaluate("""([ej]) => { localStorage.removeItem('spevb-habitos'); const S = Spevb.Siempre, dia = 86400000;
        for (let i = 1; i <= 3; i++) S.registrar(ej.a, ej.b, Date.now() - i * dia); S.pintar(); }""", [EJEMPLO])
    assert p.is_visible('#siempre') and 'Ejemplo A' in p.inner_text('#siempreRuta') and 'Ejemplo B' in p.inner_text('#siempreRuta')
    p.click('#siempreBuscar')
    B.esperar(p, "document.querySelectorAll('#tripResults .trip-option').length > 0", 15000)
    assert p.input_value('#tripOrigin') == 'Ejemplo A' and p.input_value('#tripDestination') == 'Ejemplo B'
    assert p.is_hidden('#siempre'), 'con el viaje puesto la tarjeta se oculta'
    s.limpio('viaje de siempre'); s.cerrar()


@prueba
def ahora_no_oculta_tu_viaje_de_siempre_por_hoy(nav):
    s = B.Sesion(nav, **MOVIL)
    p = s.abrir()
    p.evaluate("""([ej]) => { const S = Spevb.Siempre, dia = 86400000;
        for (let i = 1; i <= 3; i++) S.registrar(ej.a, ej.b, Date.now() - i * dia); S.pintar(); }""", [EJEMPLO])
    assert p.is_visible('#siempre')
    p.click('#siempreNo'); p.wait_for_timeout(200)
    assert p.is_hidden('#siempre')
    p.evaluate("Spevb.Siempre.pintar()")
    assert p.is_hidden('#siempre'), 'no vuelve a salir hoy'
    p.reload(); p.wait_for_timeout(1500)
    assert p.is_hidden('#siempre'), 'ni al recargar'
    s.limpio('ahora no'); s.cerrar()


# ------------------------------------------------------------------------------------------------ resumen de la semana
@prueba
def el_resumen_de_la_semana_suma_los_ultimos_7_dias_y_avisa_una_vez(nav):
    s = B.Sesion(nav, **MOVIL)
    p = s.abrir()
    r = p.evaluate("""() => { const L = Spevb.Logros, ahora = new Date(2026, 5, 10, 18, 0), dia = 86400000;
        const vacio = L.textoSemana(ahora);
        L.registrarViaje({ buses: 1, resumen: { minutos: 25, caminaMetros: 400 } }, new Date(ahora.getTime() - 8 * dia));    // hace 8 días: no cuenta
        L.registrarViaje({ buses: 1, resumen: { minutos: 25, caminaMetros: 400 } }, new Date(ahora.getTime() - 2 * dia));
        L.registrarViaje({ buses: 2, resumen: { minutos: 50, caminaMetros: 1300 } }, new Date(ahora.getTime() - 1 * dia));
        return { vacio, texto: L.textoSemana(ahora), r: L.resumenSemana(ahora), primera: L.avisoSemanal(ahora), segunda: L.avisoSemanal(ahora) }; }""")
    assert r['vacio'] == '' and r['r'] == {'viajes': 2, 'caminaMetros': 1700, 'minutos': 75}, r
    assert '2 viajes' in r['texto'] and '1,7 km' in r['texto'] and '1 h 15 min' in r['texto'], r['texto']
    assert r['primera'] is True and r['segunda'] is False, 'el aviso sale una sola vez por semana'
    p.evaluate("Spevb.Logros.registrarViaje({ buses: 1, resumen: { minutos: 20, caminaMetros: 300 } })")      # un viaje de HOY: el menú usa la fecha real
    p.click('#menuButton'); p.wait_for_timeout(500)
    assert p.is_visible('#semanaResumen') and 'viaje' in p.inner_text('#semanaResumen') and 'caminaste 300 m' in p.inner_text('#semanaResumen'), p.inner_text('#semanaResumen')
    s.limpio('semana'); s.cerrar()


# ------------------------------------------------------------------------------------------------ lenguaje simple del modo viaje
@prueba
def los_mensajes_del_viaje_usan_paradero_letrero_y_cuadras(nav):
    s = V.sesion(nav)
    p = s.abrir()
    V.planear(p); V.destino_lejos(p)
    ls = V.lecturas(p, ruido={'sigma': 6, 'semilla': 3})
    V.iniciar(p)
    i = V.alimentar(p, ls, 0, 'fase:caminar')
    c = V.card(p)
    assert 'paradero' in c['instruccion'] and 'M01' in c['instruccion'] and 'cuadra' in c['instruccion'] and ' m.' not in c['instruccion'], c
    i = V.alimentar(p, ls, i, 'fase:esperando')
    c = V.card(p)
    assert 'letrero' in c['instruccion'] and 'M01' in c['instruccion'], c
    i = V.alimentar(p, ls, i, 'pregunta')
    p.click('#viajeSi')
    i = V.alimentar(p, ls, i, 'alerta:alerta')
    aviso = V.card(p)['aviso']
    assert 'Te acercas' in aviso and 'cuadra' in aviso and ' m.' not in aviso, aviso
    i = V.alimentar(p, ls, i, 'alerta:urgente')
    s.limpio('lenguaje simple'); s.cerrar()


# ------------------------------------------------------------------------------------------------ bienvenida con ilustraciones animadas
# ------------------------------------------------------------------------------------------------ práctica robusta
@prueba
def la_practica_desde_el_menu_cierra_el_menu_antes_de_empezar(nav):
    s = B.Sesion(nav, **MOVIL)
    p = s.abrir()
    p.click('#menuButton'); p.wait_for_timeout(600)
    p.click('#menuPractica'); p.wait_for_timeout(1800)
    assert p.evaluate("!!document.getElementById('coach')")
    assert not p.evaluate("document.getElementById('sideMenu').classList.contains('open')"), 'el menú ya cerró'
    assert p.evaluate("getComputedStyle(document.getElementById('menuBackdrop')).display") == 'none' or p.evaluate("document.getElementById('menuBackdrop').classList.contains('hidden')")
    p.click('.coach-salir'); p.wait_for_timeout(600)
    s.limpio('practica desde menu'); s.cerrar()


@prueba
def al_terminar_la_practica_hay_una_celebracion_con_animacion_que_se_va_sola(nav):
    s = B.Sesion(nav, **MOVIL)
    p = s.abrir()
    p.evaluate("(ej) => { window.__practica = Spevb.Guia.practica({ ejemplo: ej, pausa: 60 }); }", EJEMPLO)
    B.esperar(p, "/paso 3/.test(document.querySelector('.coach-paso').textContent)", 8000)
    p.evaluate("document.querySelector('#planTrip').click()")
    B.esperar(p, "/paso 4/.test(document.querySelector('.coach-paso').textContent)", 15000)
    assert p.evaluate("document.querySelectorAll('.coach-progreso i.hecho').length") == 3, 'el progreso va marcando los pasos'
    siguiente(p, 5)
    for _ in range(4):
        if p.evaluate("!document.getElementById('coach')"): break
        siguiente(p)
    p.wait_for_timeout(900)
    assert p.is_visible('#coachFin') and 'Muy bien' in p.inner_text('#coachFin') and p.evaluate("document.querySelectorAll('#coachFin .fin-confeti i').length") == 12
    assert p.evaluate("getComputedStyle(document.getElementById('coachFin')).pointerEvents") == 'none' and p.evaluate("getComputedStyle(document.getElementById('coachFin')).backgroundColor") == 'rgba(0, 0, 0, 0)', 'la celebración no oscurece ni bloquea la pantalla'
    assert p.evaluate("document.querySelectorAll('#coachFin svg.fin-chulo').length") == 1
    B.esperar(p, "!document.getElementById('coachFin')", 8000)
    s.limpio('celebracion'); s.cerrar()


# ------------------------------------------------------------------------------------------------ pestañita «Ahora escribe B»
@prueba
def al_poner_un_punto_aparece_una_pestanita_sin_mover_la_pantalla_ni_abrir_el_teclado(nav):
    s = B.Sesion(nav, **MOVIL)
    p = s.abrir()
    p.click('#tripOrigin'); p.fill('#tripOrigin', ORIGEN); p.wait_for_timeout(900)          # el panel sube al escribir (teclado abierto)
    antes = p.evaluate("(() => { const r = document.getElementById('sheet').getBoundingClientRect(); return { top: Math.round(r.top), h: Math.round(r.height) }; })()")
    p.keyboard.press('Enter'); p.wait_for_timeout(1300)
    t = p.evaluate("(() => { const e = document.querySelector('.pestana-siguiente'); return e ? { texto: e.textContent, visible: e.classList.contains('visible') } : null; })()")
    assert t and t['visible'] and 'Ahora escribe B' in t['texto'] and 'a dónde vas' in t['texto'], t
    despues = p.evaluate("(() => { const r = document.getElementById('sheet').getBoundingClientRect(); return { top: Math.round(r.top), h: Math.round(r.height) }; })()")
    assert abs(despues['top'] - antes['top']) <= 4, f'el panel no se movió: {antes} → {despues}'
    assert p.evaluate("document.activeElement && document.activeElement.id") != 'tripDestination', 'no se abre el teclado en B'
    assert p.evaluate("document.querySelectorAll('.endpoint-block.pide-punto').length") == 1, 'el campo B late suavemente'
    p.fill('#tripDestination', DEST); p.keyboard.press('Enter'); p.wait_for_timeout(1200)
    assert p.evaluate("!document.querySelector('.pestana-siguiente')"), 'con los dos puntos la pestañita se va'
    s.limpio('pestana'); s.cerrar()


@prueba
def la_pestanita_pide_a_si_se_puso_primero_b_y_no_sale_durante_la_practica(nav):
    s = B.Sesion(nav, **MOVIL)
    p = s.abrir()
    p.fill('#tripDestination', DEST); p.keyboard.press('Enter'); p.wait_for_timeout(1300)
    assert p.evaluate("(document.querySelector('.pestana-siguiente') || {}).textContent || ''").startswith('A') and 'Ahora escribe A' in p.inner_text('.pestana-siguiente')
    p.click('.pestana-siguiente'); p.wait_for_timeout(500)
    assert p.evaluate("!document.querySelector('.pestana-siguiente.visible')") and p.evaluate("document.activeElement && document.activeElement.id") != 'tripOrigin', 'tocarla muestra el campo sin abrir el teclado'
    s.cerrar()
    s = B.Sesion(nav, **MOVIL)
    p = s.abrir()
    p.evaluate("(ej) => { window.__practica = Spevb.Guia.practica({ ejemplo: ej, pausa: 60 }); }", EJEMPLO)
    B.esperar(p, "/paso 3/.test(document.querySelector('.coach-paso').textContent)", 8000)
    assert p.evaluate("!document.querySelector('.pestana-siguiente')"), 'durante la práctica no sale'
    p.click('.coach-salir'); p.wait_for_timeout(600)
    s.cerrar()


# ------------------------------------------------------------------------------------------------ sin voz, sin emojis, controles del bus
@prueba
def la_voz_ya_no_existe_en_ninguna_parte(nav):
    s = B.Sesion(nav, **MOVIL)
    p = s.abrir()
    assert p.evaluate("!document.getElementById('vozModal') && !document.getElementById('vozAjuste') && !document.getElementById('vozProbarMenu') && !window.Spevb.Voz")
    p.click('#menuButton'); p.wait_for_timeout(500)
    assert 'voz' not in p.inner_text('#sideMenu').lower(), 'el menú no habla de voz'
    s.limpio('sin voz'); s.cerrar()
    s = V.sesion(nav)
    p = s.abrir()
    V.planear(p); V.destino_lejos(p)
    V.iniciar(p)
    assert p.locator('#viajeVoz').count() == 0, 'la tarjeta del viaje no tiene botón de voz'
    assert p.evaluate("window.__voz.length") == 0
    s.limpio('viaje sin voz'); s.cerrar()


EMOJI = re.compile('[\U0001F300-\U0001FAFF\U00002600-\U000027BF\U0001F000-\U0001F2FF\U00002B00-\U00002BFF\U0000FE0F]')


@prueba
def no_hay_emojis_en_la_pantalla_principal_ni_en_el_menu_ni_en_las_ventanas(nav):
    s = B.Sesion(nav, **MOVIL)
    p = con_bienvenida(s)
    textos = {'bienvenida': p.evaluate("document.getElementById('bienvenida').innerText")}
    p.click('#bienSaltar'); p.wait_for_timeout(500)
    planear(p)
    p.evaluate("document.querySelector('#tripResults .trip-option').open = true")
    textos['principal'] = p.evaluate("document.getElementById('sheet').innerText")
    p.evaluate("Spevb.Logros.registrarViaje({ buses: 2 }, new Date(2026, 0, 5, 6, 30)); Spevb.Logros.marcarPractica();")
    p.click('#menuButton'); p.wait_for_timeout(500)
    textos['menu'] = p.evaluate("document.getElementById('sideMenu').innerText")
    for nombre in ('authModal', 'settingsModal', 'ayudaModal', 'reportModal', 'nameModal'):
        textos[nombre] = p.evaluate("(id) => document.getElementById(id).textContent", nombre)
    textos['toasts'] = p.evaluate("document.body.innerText")
    for nombre, t in textos.items():
        m = EMOJI.findall(t.replace('☰', ''))
        assert not m, f'emojis en «{nombre}»: {m}'
    assert p.evaluate("document.querySelectorAll('.logro-ico svg').length") >= 8, 'los logros usan iconos propios (SVG)'
    assert p.evaluate("document.querySelectorAll('.tl-ico svg').length") >= 0
    s.limpio('sin emojis'); s.cerrar()


@prueba
def los_controles_del_bus_son_solo_un_circulo_con_el_icono_del_bus(nav):
    s = B.Sesion(nav, **MOVIL)
    p = s.abrir()
    planear(p)                                                                             # el bus aparece cuando hay una ruta en el mapa
    p.evaluate("Spevb.Ajustes.set('busMin', true)"); p.wait_for_timeout(700)
    r = p.evaluate("""() => { const b = document.querySelector('.bus-panel.collapsed .bus-min'); if (!b) return null; const r = b.getBoundingClientRect();
        return { w: Math.round(r.width), h: Math.round(r.height), texto: b.textContent.trim(), svg: b.querySelectorAll('svg').length, etiqueta: b.getAttribute('aria-label'), radio: getComputedStyle(b).borderTopLeftRadius }; }""")
    assert r and r['texto'] == '' and r['svg'] == 1 and r['w'] == r['h'] and 44 <= r['w'] <= 56 and 'controles del bus' in r['etiqueta'].lower(), r
    assert 'Controles' not in p.inner_text('.bus-panel'), 'ya no dice «Controles»'
    p.click('.bus-panel.collapsed .bus-min'); p.wait_for_timeout(500)
    assert p.evaluate("!document.querySelector('.bus-panel').classList.contains('collapsed')"), 'al tocarlo se abren los controles'
    play = p.evaluate("(() => { const b = document.querySelector('.bus-panel .bus-btn'); return b ? { svg: b.querySelectorAll('svg').length, texto: b.textContent.trim() } : null; })()")
    assert play and play['svg'] >= 1, play
    s.limpio('controles bus'); s.cerrar()



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
    print(f'\n{len(PRUEBAS) if not filtro else "?"} pruebas para aprender a usar la app; fallos: {len(fallos)} {fallos}')
    return 1 if fallos else 0


if __name__ == '__main__':
    sys.exit(correr(sys.argv[1] if len(sys.argv) > 1 else None))
