"""Pruebas del generador de recorridos GPX (scripts/gpx_viaje.py) y del emulador por línea de comandos (scripts/emulador_gps.py).

El GPX que se importaría en el emulador de Android Studio se genera contra el stub, se lee de nuevo y se le pasa al núcleo del «Modo viaje» en un
navegador real: el viaje del archivo tiene que recorrer todas las fases, con y sin ruido. Uso: SEVB_E2E_URL=http://127.0.0.1:18080/ python e2e/gpx_e2e.py
"""
import os
import sys

if hasattr(sys.stdout, 'reconfigure'):
    sys.stdout.reconfigure(encoding='utf-8', errors='replace')      # la consola de Windows (cp1252) no admite todos los símbolos de los mensajes
import tempfile
import traceback

AQUI = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, AQUI)
sys.path.insert(0, os.path.join(AQUI, '..', 'scripts'))
import sevb_e2e as B  # noqa: E402
import gpx_viaje as G  # noqa: E402
import emulador_gps as E  # noqa: E402
from playwright.sync_api import sync_playwright  # noqa: E402

API = B.URL.rstrip('/')
PRUEBAS = []
DIRECTO = ['fase:caminar', 'fase:esperando', 'pregunta:subida', 'fase:en_bus', 'alerta:cerca', 'alerta:preparate', 'alerta:ahora', 'pregunta:bajada', 'fase:caminar_final', 'fase:llegaste', 'llego']
TRANSBORDO = ['fase:caminar', 'fase:esperando', 'pregunta:subida', 'fase:en_bus', 'alerta:cerca', 'alerta:preparate', 'alerta:ahora', 'pregunta:bajada',
              'fase:caminar', 'fase:esperando', 'pregunta:subida', 'fase:en_bus', 'alerta:cerca', 'alerta:preparate', 'alerta:ahora', 'pregunta:bajada',
              'fase:caminar_final', 'fase:llegaste', 'llego']


def prueba(f):
    PRUEBAS.append(f)
    return f


def plan_de(buses, origen, destino):
    """Misma estructura que Spevb.Planner.seleccion.viaje (planner.js · viajeDe)."""
    return {'origen': {'lat': origen[0], 'lng': origen[1]}, 'destino': {'lat': destino[0], 'lng': destino[1]},
            'buses': [{'codigo': b['codigo'], 'nombre': b['nombre'], 'color': b['color'],
                       'subida': {'lat': b['puntoSubida']['coordinates'][1], 'lng': b['puntoSubida']['coordinates'][0]},
                       'bajada': {'lat': b['puntoBajada']['coordinates'][1], 'lng': b['puntoBajada']['coordinates'][0]}, 'tramo': b['tramo']['coordinates']} for b in buses]}


def generar_viaje(origen, destino, transbordo=False, ruido=0.0):
    resp = G.pedir_plan(API, origen, destino)
    buses, ini, fin = G.elegir(resp, 0, transbordo)
    lecturas = G.generar(buses, origen, destino, ini, fin, ruido=ruido)
    ruta = os.path.join(tempfile.mkdtemp(prefix='sevb_gpx_'), 'viaje.gpx')
    with open(ruta, 'w', encoding='utf-8', newline='\n') as f:
        f.write(G.a_gpx(lecturas, 'prueba'))
    return plan_de(buses, origen, destino), lecturas, ruta


def correr_en_navegador(nav, plan, puntos):
    ctx = nav.new_context(service_workers='block')
    ctx.add_init_script(path=os.path.join(AQUI, 'simulador_gps.js'))
    page = ctx.new_page()
    page.goto(B.URL, wait_until='domcontentloaded')
    B.esperar(page, "!!(window.Spevb && Spevb.ViajeNucleo)", 20000)
    lec = [{'lat': la, 'lng': lo, 'precision': 6, 't': 1700000000000 + n * 1000} for n, (la, lo) in enumerate(puntos)]
    r = page.evaluate("([plan, l]) => { const r = SimGps.correr(plan, l, {}); return { seq: SimGps.secuencia(r.eventos), fase: r.info.fase }; }", [plan, lec])
    ctx.close()
    return r


ORIGEN, DESTINO = (4.1470, -73.6530), (4.1479, -73.6240)          # el destino queda a ≈ 320 m de la ruta: hay caminata final


@prueba
def el_gpx_es_valido_tiene_un_punto_por_segundo_y_velocidades_realistas(nav):
    plan, lecturas, ruta = generar_viaje(ORIGEN, DESTINO)
    puntos = E.leer_gpx(ruta)
    assert len(puntos) == len(lecturas) > 600
    assert G.distancia(puntos[0], ORIGEN) < 5 and G.distancia(puntos[-1], DESTINO) < 5, 'empieza en A y termina en B'
    tiempos = [l['t'] for l in lecturas]
    assert all(b - a == 1 for a, b in zip(tiempos, tiempos[1:])), 'una lectura por segundo'
    por_fase = {}
    for a, b, l in zip(lecturas, lecturas[1:], lecturas[1:]):
        por_fase.setdefault(l['fase'], []).append(G.distancia((a['lat'], a['lng']), (b['lat'], b['lng'])))
    assert 1.2 <= sum(por_fase['caminar']) / len(por_fase['caminar']) <= 1.5, 'camina a ≈ 1,35 m/s'
    assert max(por_fase['bus']) <= 7.2 and sum(por_fase['bus']) / len(por_fase['bus']) > 5.5, 'el bus va a ≈ 7 m/s con arranque y frenado graduales'
    assert max(por_fase['espera']) < 0.01, 'esperando está quieto'
    assert [f['fase'] for f in G.resumen(lecturas)] == ['caminar', 'espera', 'bus', 'parada', 'camina_final']


@prueba
def el_viaje_del_gpx_recorre_todas_las_fases_perfecto_y_con_ruido(nav):
    for ruido in (0.0, 8.0):
        plan, lecturas, ruta = generar_viaje(ORIGEN, DESTINO, ruido=ruido)
        r = correr_en_navegador(nav, plan, E.leer_gpx(ruta))
        assert r['seq'] == DIRECTO and r['fase'] == 'llegaste', f'ruido {ruido}: {r}'


@prueba
def el_viaje_con_transbordo_del_gpx_recorre_los_dos_buses(nav):
    plan, lecturas, ruta = generar_viaje((4.1433, -73.6020), (4.1560, -73.5800), transbordo=True, ruido=6.0)
    assert len(plan['buses']) == 2
    r = correr_en_navegador(nav, plan, E.leer_gpx(ruta))
    assert [x for x in r['seq'] if x not in ('desvio',)] == TRANSBORDO and r['fase'] == 'llegaste', r


@prueba
def el_emulador_por_linea_de_comandos_envia_longitud_primero_y_acepta_el_modo_de_prueba(nav):
    plan, lecturas, ruta = generar_viaje(ORIGEN, DESTINO)
    enviados = []
    original = E.subprocess.run

    class R:
        returncode, stdout, stderr = 0, 'OK', ''
    E.subprocess.run = lambda cmd, **k: (enviados.append(cmd), R())[1]
    try:
        E.fix('adb', 'emulator-5554', 4.1470, -73.6530)
    finally:
        E.subprocess.run = original
    assert enviados[0] == ['adb', '-s', 'emulator-5554', 'emu', 'geo', 'fix', '-73.6530000', '4.1470000'], enviados
    assert E.principal([ruta, '--prueba', '--velocidad', '1000']) == 0


def correr():
    fallos = []
    with sync_playwright() as p:
        nav = B.navegador(p)
        print('Navegador:', nav.browser_type.name, nav.version, '| URL:', B.URL)
        for f in PRUEBAS:
            try:
                f(nav)
                print('OK    ', f.__name__)
            except Exception as e:  # noqa: BLE001
                fallos.append(f.__name__)
                print('FALLA ', f.__name__, '|', str(e).splitlines()[0][:600] if str(e) else repr(e))
                traceback.print_exc(limit=3)
        nav.close()
    print(f'\n{len(PRUEBAS)} pruebas del generador GPX y del emulador; fallos: {len(fallos)} {fallos}')
    return 1 if fallos else 0


if __name__ == '__main__':
    sys.exit(correr())
