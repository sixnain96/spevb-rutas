"""Pruebas de navegador de 4.14: nombre popular de las rutas, mapa de noche, letra grande, línea de tiempo, «Ir a Casa/Trabajo», logros y WhatsApp.

Uso: SEVB_E2E_URL=http://127.0.0.1:18080/ python e2e/mejoras414_e2e.py [filtro]      (lo normal es e2e/run_e2e.py)
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
ORIGEN = '4.1475, -73.6530'
MOVIL = {'viewport': {'width': 384, 'height': 848}, 'is_mobile': True, 'has_touch': True, 'device_scale_factor': 2}
NOMBRES = {f'M{n:02d}': f'Catama{n}' for n in range(1, 40)}


def prueba(f):
    PRUEBAS.append(f)
    return f


def con_nombres(s):
    s.ctx.route('**/api/rutas/nombres', lambda r: r.fulfill(status=200, content_type='application/json', body=json.dumps(NOMBRES)))


def planear(p, espera=2500):
    p.fill('#tripOrigin', ORIGEN); p.keyboard.press('Enter'); p.wait_for_timeout(700)
    p.fill('#tripDestination', DEST); p.keyboard.press('Enter'); p.wait_for_timeout(700)
    p.evaluate("document.querySelector('#planTrip').click()")
    B.esperar(p, "document.querySelectorAll('#tripResults .trip-option').length > 0", 12000)
    p.wait_for_timeout(espera)


@prueba
def el_nombre_popular_reemplaza_al_codigo_en_resultados_y_mapa(nav):
    s = B.Sesion(nav, **MOVIL)
    con_nombres(s)
    p = s.abrir()
    p.wait_for_timeout(600)
    assert p.evaluate("Spevb.rutaNombre('M02')") == 'Catama2', 'el mapa de nombres se carga'
    assert p.evaluate("Spevb.rutaNombre('R999')") == 'R999', 'sin nombre popular se conserva el código'
    planear(p)
    chip = p.evaluate("document.querySelector('#tripResults .trip-option .route-chip').textContent")
    assert chip.startswith('Catama'), f'la insignia muestra el nombre popular y no el código: {chip!r}'
    estado = p.inner_text('#tripStatus')
    assert 'Catama' in estado and 'M0' not in estado, f'el aviso dice la ruta como la conoce la gente: {estado!r}'
    etiquetas = p.evaluate("[...document.querySelectorAll('.map-tag, .tag-up')].map(e => e.textContent).join(' | ')")
    assert 'M0' not in etiquetas, f'los rótulos del mapa no muestran el código: {etiquetas!r}'
    s.limpio('nombre popular'); s.cerrar()


@prueba
def sin_nombres_populares_todo_sigue_mostrando_el_codigo(nav):
    s = B.Sesion(nav, **MOVIL)
    s.ctx.route('**/api/rutas/nombres', lambda r: r.fulfill(status=200, content_type='application/json', body='{}'))
    p = s.abrir()
    planear(p)
    chip = p.evaluate("document.querySelector('#tripResults .trip-option .route-chip').textContent")
    assert chip.startswith('M0'), f'sin nombre popular se ve el código: {chip!r}'
    s.limpio('sin nombre popular'); s.cerrar()


@prueba
def si_el_servicio_de_nombres_falla_la_aplicacion_sigue_funcionando(nav):
    s = B.Sesion(nav, **MOVIL)
    s.ctx.route('**/api/rutas/nombres', lambda r: r.fulfill(status=500, content_type='application/json', body='{"mensaje":"x"}'))
    p = s.abrir()
    planear(p)
    assert p.evaluate("document.querySelectorAll('#tripResults .trip-option').length") > 0
    s.cerrar()


@prueba
def la_linea_de_tiempo_muestra_caminata_bus_caminata_en_minutos(nav):
    s = B.Sesion(nav, **MOVIL)
    p = s.abrir()
    planear(p)
    p.evaluate("document.querySelector('#tripResults .trip-option').open = true")
    p.wait_for_timeout(500)
    r = p.evaluate("""() => { const t = document.querySelector('#tripResults .trip-option .trip-timeline');
        if (!t) return null; return { items: t.querySelectorAll('.tl-item').length, bus: t.querySelectorAll('.tl-item.bus').length, texto: t.textContent, aria: t.getAttribute('aria-label') }; }""")
    assert r and r['bus'] == 1 and r['items'] >= 2, f'línea de tiempo con un tramo en bus: {r}'
    assert 'min' in r['texto'] and ('cuadra' in r['texto'] or 'bus' in r['texto'].lower()), r
    assert r['aria'].startswith('Tu viaje:'), r
    s.limpio('línea de tiempo'); s.cerrar()


@prueba
def la_letra_grande_se_elige_en_el_menu_y_se_recuerda(nav):
    s = B.Sesion(nav, **MOVIL)
    p = s.abrir()
    assert p.evaluate("document.documentElement.dataset.letra") == 'normal'
    p.click('#menuButton'); p.wait_for_timeout(500)
    p.click('button[data-letra="grande"]'); p.wait_for_timeout(300)
    assert p.evaluate("document.documentElement.dataset.letra") == 'grande'
    assert p.evaluate("document.querySelector('button[data-letra=\"grande\"]').getAttribute('aria-pressed')") == 'true'
    p.reload(); p.wait_for_timeout(1200)
    assert p.evaluate("document.documentElement.dataset.letra") == 'grande', 'se recuerda al volver a entrar'
    p.evaluate("Spevb.Ajustes.set('letra', 'normal'); Spevb.Confort.aplicar()")
    s.limpio('letra'); s.cerrar()


@prueba
def el_mapa_se_oscurece_de_noche_y_se_puede_forzar(nav):
    s = B.Sesion(nav)
    p = s.abrir()
    r = p.evaluate("""() => ({ a: Spevb.Confort.esNoche(new Date(2026, 0, 5, 21, 0)), b: Spevb.Confort.esNoche(new Date(2026, 0, 5, 5, 0)),
        c: Spevb.Confort.esNoche(new Date(2026, 0, 5, 12, 0)), d: Spevb.Confort.esNoche(new Date(2026, 0, 5, 18, 29)), e: Spevb.Confort.esNoche(new Date(2026, 0, 5, 18, 30)),
        f: Spevb.Confort.esNoche(new Date(2026, 0, 5, 5, 30)) })""")
    assert r == {'a': True, 'b': True, 'c': False, 'd': False, 'e': True, 'f': False}, r
    p.evaluate("Spevb.Ajustes.set('mapaNoche', 'oscuro'); Spevb.Confort.aplicar()")
    assert p.evaluate("document.documentElement.classList.contains('mapa-noche')")
    p.evaluate("Spevb.Ajustes.set('mapaNoche', 'claro'); Spevb.Confort.aplicar()")
    assert not p.evaluate("document.documentElement.classList.contains('mapa-noche')")
    p.click('#menuButton'); p.wait_for_timeout(400)
    p.select_option('select[data-confort="mapaNoche"]', 'oscuro'); p.wait_for_timeout(200)
    assert p.evaluate("document.documentElement.classList.contains('mapa-noche')")
    p.evaluate("Spevb.Ajustes.set('mapaNoche', 'auto'); Spevb.Confort.aplicar()")
    s.limpio('mapa de noche'); s.cerrar()


@prueba
def casa_y_trabajo_viven_en_el_menu_se_guardan_una_vez_y_despues_son_un_toque(nav):
    s = B.Sesion(nav, **MOVIL)
    p = s.abrir()
    assert p.evaluate("!!document.querySelector('#atajos') && !!document.querySelector('#atajos').closest('#sideMenu')"), 'los atajos están en el menú lateral'
    assert not p.evaluate("!!document.querySelector('.trip-panel #atajos')"), 'ya no están en la pantalla principal'
    p.click('#menuButton'); p.wait_for_timeout(500)
    assert 'Guardar mi Casa' in p.inner_text('#atajos')
    p.click('#atajos .atajo[data-atajo="Casa"]'); p.wait_for_timeout(700)
    assert not p.evaluate("document.getElementById('sideMenu').classList.contains('open')"), 'el menú se cierra para escribir la dirección'
    p.fill('#tripDestination', DEST); p.keyboard.press('Enter'); p.wait_for_timeout(900)
    lugares = p.evaluate("Spevb.Datos.leer('lugares').map(f => f.nombre)")
    assert 'Casa' in lugares, f'se guardó como Casa: {lugares}'
    p.click('#clearDestination'); p.wait_for_timeout(500)
    p.click('#menuButton'); p.wait_for_timeout(500)
    assert 'Ir a Casa' in p.inner_text('#atajos')
    p.click('#atajos .atajo[data-atajo="Casa"]'); p.wait_for_timeout(700)
    assert p.input_value('#tripDestination') == 'Casa', 'un toque pone la Casa como destino'
    s.limpio('atajos'); s.cerrar()


@prueba
def los_logros_cuentan_viajes_racha_y_avisan(nav):
    s = B.Sesion(nav, **MOVIL)
    p = s.abrir()
    r = p.evaluate("""() => { const L = Spevb.Logros;
        const a = L.registrarViaje({ buses: 2 }, new Date(2026, 0, 5, 6, 30)).map(x => x.id);
        const b = L.registrarViaje({ buses: 1 }, new Date(2026, 0, 6, 8, 0)).map(x => x.id);
        const c = L.registrarViaje({ buses: 1 }, new Date(2026, 0, 7, 20, 0)).map(x => x.id);
        return { a, b, c, e: L.leer() }; }""")
    assert set(r['a']) == {'primero', 'transbordo', 'madrugador'}, r
    assert r['c'] and 'racha3' in r['c'] and 'noche' in r['c'], r
    assert r['e']['viajes'] == 3 and r['e']['racha'] == 3, r
    p.evaluate("Spevb.Logros.registrarViaje({ buses: 1 }, new Date(2026, 0, 20, 9, 0))")
    assert p.evaluate("Spevb.Logros.leer().racha") == 1, 'la racha se reinicia si pasa un día sin viajar'
    p.evaluate("window.dispatchEvent(new CustomEvent('spevb-viaje-llegada', { detail: { buses: 1 } }))")
    assert p.evaluate("Spevb.Logros.leer().viajes") == 5, 'el aviso de llegada del modo viaje suma un viaje'
    p.click('#menuButton'); p.wait_for_timeout(400)
    assert 'Has terminado 5 viajes' in p.inner_text('#logrosResumen')
    assert p.locator('#logrosLista .logro.ganado').count() >= 5
    s.limpio('logros'); s.cerrar()


@prueba
def compartir_por_whatsapp_abre_el_mensaje_con_la_ruta_popular(nav):
    s = B.Sesion(nav, **MOVIL)
    con_nombres(s)
    p = s.abrir()
    p.evaluate("window.__abierto = null; window.open = (u) => { window.__abierto = u; return null; };")
    planear(p)
    p.evaluate("document.querySelector('#shareWhats').click()")
    url = p.evaluate("window.__abierto")
    assert url and url.startswith('https://wa.me/?text='), url
    assert 'Catama' in json.loads('"' + p.evaluate("decodeURIComponent(window.__abierto.slice(20))").replace('\n', '\\n').replace('"', '\\"') + '"'), url
    s.limpio('whatsapp'); s.cerrar()


@prueba
def el_boton_google_maps_abre_el_viaje_a_b_sin_clave(nav):
    s = B.Sesion(nav, **MOVIL)
    p = s.abrir()
    p.evaluate("window.__abierto = null; window.open = (u) => { window.__abierto = u; return null; };")
    planear(p)
    p.evaluate("document.querySelector('#openGoogleMaps').click()")
    url = p.evaluate("window.__abierto")
    assert url and url.startswith('https://www.google.com/maps/dir/?api=1&origin=4.1475') and 'destination=4.145' in url and 'key=' not in url, url
    s.limpio('google maps'); s.cerrar()


@prueba
def las_sugerencias_de_google_dicen_su_origen(nav):
    s = B.Sesion(nav, **MOVIL)
    s.ctx.route('**/api/lugares/sugerencias*', lambda r: r.fulfill(status=200, content_type='application/json', body=json.dumps(
        {'sugerencias': [{'texto': 'Unicentro, Villavicencio', 'detalle': 'Google Maps', 'magicKey': 'g:ChIJabcdefghij123', 'lat': None, 'lng': None, 'id': None, 'fuente': 'Google Maps', 'precision': None}],
         'arcgisConfigurado': True, 'direccionesLocales': True})))
    p = s.abrir()
    p.fill('#tripDestination', 'Unicen'); p.wait_for_timeout(1200)
    txt = p.inner_text('#destinationResults')
    assert 'Unicentro' in txt and 'Google Maps' in txt, txt
    s.limpio('sugerencias google'); s.cerrar()


SUGERENCIAS_6 = {'sugerencias': [{'texto': t, 'detalle': 'OpenStreetMap', 'magicKey': None, 'lat': 4.14 + i / 1000, 'lng': -73.62, 'id': None, 'fuente': 'OpenStreetMap', 'precision': 'LUGAR'}
                                  for i, t in enumerate(['Unicentro', 'Cine Procinal C.C. Unicentro', 'Royal Films Multicine', 'Punto de carga El Triángulo', 'Unicentro Parqueadero', 'Unico Almacen'])],
                 'arcgisConfigurado': True, 'direccionesLocales': True}

JS_TAPABLE = """() => { const lista = document.querySelector('.place-results.suggestion-open'); if (!lista) return { abierta: false };
    const opciones = [...lista.querySelectorAll('.place-option')];
    const caja = lista.getBoundingClientRect();      // la lista se desplaza: solo cuentan las opciones que se ven dentro de ella
    const tapadas = opciones.filter((o) => { const r = o.getBoundingClientRect(); if (r.height === 0) return false;
        const x = r.left + r.width / 2, y = r.top + r.height / 2;
        if (y < 0 || y > innerHeight || y < caja.top || y > caja.bottom) return false;
        const e = document.elementFromPoint(x, y); return !(e && (e === o || o.contains(e))); }).map((o) => o.textContent.slice(0, 30));
    return { abierta: true, total: opciones.length, tapadas }; }"""


@prueba
def la_lista_de_sugerencias_no_queda_tapada_por_los_botones_buscar_en_el_mapa(nav):
    for nombre, op in (('384x848', MOVIL), ('320x568', {'viewport': {'width': 320, 'height': 568}, 'is_mobile': True, 'has_touch': True})):
        for campo in ('#tripOrigin', '#tripDestination'):
            s = B.Sesion(nav, **op)
            s.ctx.route('**/api/lugares/sugerencias*', lambda r: r.fulfill(status=200, content_type='application/json', body=json.dumps(SUGERENCIAS_6)))
            p = s.abrir()
            p.click(campo); p.keyboard.type('unic', delay=40); p.wait_for_timeout(1500)
            r = p.evaluate(JS_TAPABLE)
            assert r['abierta'] and r['total'] >= 4, f'{nombre} {campo}: la lista se abre: {r}'
            assert not r['tapadas'], f'{nombre} {campo}: opciones tapadas por otros botones: {r}'
            s.cerrar()


LUGARES_VARIOS = {'lugares': [
    {'id': None, 'nombre': 'Aeropuerto Vanguardia', 'direccion': 'Villavicencio', 'lat': 4.1644, 'lng': -73.6184, 'fuente': 'Google Maps', 'confirmado': False, 'precision': 'LUGAR'},
    {'id': None, 'nombre': 'Colegio Adventista de Villavicencio', 'direccion': 'x', 'lat': 4.14, 'lng': -73.63, 'fuente': 'OpenStreetMap', 'confirmado': False, 'precision': 'LUGAR'},
    {'id': None, 'nombre': 'Antonio Villavicencio', 'direccion': 'x', 'lat': 4.13, 'lng': -73.60, 'fuente': 'OpenStreetMap', 'confirmado': False, 'precision': 'LUGAR'}],
    'avisos': [], 'catastroConfigurado': True, 'arcgisConfigurado': True, 'direccionesHabilitadas': True, 'reversoHabilitado': True, 'ciudad': 'Villavicencio'}
SUG_GOOGLE = {'sugerencias': [{'texto': 'Aeropuerto Vanguardia- Villavicencio, Villavicencio, Meta, Colombia', 'detalle': 'Google Maps', 'magicKey': 'g:ChIJabcdefghij123',
                               'lat': None, 'lng': None, 'id': None, 'fuente': 'Google Maps', 'precision': None}], 'arcgisConfigurado': True, 'direccionesLocales': True}


@prueba
def elegir_una_sugerencia_ubica_el_punto_y_no_abre_un_segundo_menu(nav):
    for campo, mod in (('#tripOrigin', 'origin'), ('#tripDestination', 'destination')):
        s = B.Sesion(nav, **MOVIL)
        s.ctx.route('**/api/lugares/sugerencias*', lambda r: r.fulfill(status=200, content_type='application/json', body=json.dumps(SUG_GOOGLE)))
        s.ctx.route(lambda u: u.split('?')[0].endswith('/api/lugares'), lambda r: r.fulfill(status=200, content_type='application/json', body=json.dumps(LUGARES_VARIOS)))
        p = s.abrir()
        p.click(campo); p.keyboard.type('aeropuerto', delay=40); p.wait_for_timeout(1500)
        p.click('.place-results.suggestion-open .place-option'); p.wait_for_timeout(1200)
        assert p.input_value(campo) == 'Aeropuerto Vanguardia', f'{mod}: el punto quedó puesto con el lugar elegido: {p.input_value(campo)!r}'
        abiertas = p.evaluate("document.querySelectorAll('.place-results .place-option').length")
        assert abiertas == 0, f'{mod}: no debe quedar ningún menú de coincidencias abierto ({abiertas} opciones)'
        s.limpio('sin segundo menu ' + mod); s.cerrar()


@prueba
def buscar_con_enter_muestra_un_solo_menu_desplegable_y_se_cierra_al_cambiar_de_campo(nav):
    s = B.Sesion(nav, **MOVIL)
    s.ctx.route('**/api/lugares/sugerencias*', lambda r: r.fulfill(status=200, content_type='application/json', body=json.dumps({'sugerencias': [], 'arcgisConfigurado': True, 'direccionesLocales': True})))
    s.ctx.route(lambda u: u.split('?')[0].endswith('/api/lugares'), lambda r: r.fulfill(status=200, content_type='application/json', body=json.dumps(LUGARES_VARIOS)))
    p = s.abrir()
    p.click('#tripOrigin'); p.keyboard.type('aeropuerto vanguard', delay=30); p.keyboard.press('Enter'); p.wait_for_timeout(1500)
    r = p.evaluate(JS_TAPABLE)
    assert r['abierta'] and r['total'] == 3 and not r['tapadas'], f'una sola lista, completa y sin tapar: {r}'
    assert p.evaluate("document.querySelectorAll('.place-results.suggestion-open').length") == 1
    p.evaluate("document.getElementById('tripDestination').focus()"); p.wait_for_timeout(600)      # Tab / toque fuera de la lista: pasa al otro campo
    assert p.evaluate("document.querySelectorAll('.place-results.suggestion-open').length") == 0, 'al pasar al otro campo la lista del primero se cierra'
    s.limpio('un solo menu'); s.cerrar()


@prueba
def la_linea_de_caminata_se_ve_de_noche_con_color_claro_y_de_dia_no_cambia(nav):
    s = B.Sesion(nav, **MOVIL)
    p = s.abrir()
    p.evaluate("Spevb.Ajustes.set('mapaNoche', 'claro'); Spevb.Confort.aplicar()")
    planear(p, espera=3500)
    B.esperar(p, "document.querySelectorAll('path.walk-line').length > 0", 12000)
    dia = p.evaluate("getComputedStyle(document.querySelector('path.walk-line')).stroke")
    assert dia == 'rgb(27, 42, 51)', f'de día la caminata usa el color oscuro propio (#1b2a33, unificado en 4.13.3): {dia}'
    p.evaluate("Spevb.Ajustes.set('mapaNoche', 'oscuro'); Spevb.Confort.aplicar()"); p.wait_for_timeout(400)
    noche = p.evaluate("(() => { const e = document.querySelector('path.walk-line'); const c = getComputedStyle(e); return { stroke: c.stroke, filtro: c.filter }; })()")
    assert noche['stroke'] == 'rgb(255, 244, 184)' and 'drop-shadow' in noche['filtro'], f'de noche se ve clara y con borde oscuro: {noche}'
    p.evaluate("Spevb.Ajustes.set('mapaNoche', 'claro'); Spevb.Confort.aplicar()"); p.wait_for_timeout(300)
    assert p.evaluate("getComputedStyle(document.querySelector('path.walk-line')).stroke") == 'rgb(27, 42, 51)', 'al volver al día vuelve el color de día'
    s.limpio('linea de noche'); s.cerrar()


@prueba
def el_editor_tiene_el_campo_de_nombre_popular(nav):
    s = B.Sesion(nav)
    con_nombres(s)
    p = s.page
    p.goto(B.URL + 'editor.html'); p.wait_for_timeout(800)
    assert p.locator('#editorPopular').count() == 1
    assert p.get_attribute('#editorPopular', 'maxlength') == '60'
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
    print(f'\n{len(PRUEBAS) if not filtro else "?"} pruebas de 4.14 (nombre popular, noche, letra, tiempo, atajos, logros); fallos: {len(fallos)} {fallos}')
    return 1 if fallos else 0


if __name__ == '__main__':
    sys.exit(correr(sys.argv[1] if len(sys.argv) > 1 else None))
