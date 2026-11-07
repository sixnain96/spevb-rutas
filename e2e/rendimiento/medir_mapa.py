"""Mide el comportamiento del mapa por proveedor con escenarios REPETIBLES (mismos datos del servidor simulado, mismo emulador, misma ráfaga de acciones).

Qué es comparable y qué no:
  · Leaflet: motor REAL (Leaflet + mapa vectorial/ráster sin Internet: las teselas externas se bloquean, así se mide el trabajo propio de SEVB).
  · Google: SDK SIMULADO (e2e/harness/google_fake.js). Sirve para medir el trabajo de SEVB alrededor del mapa (adaptador, planificador, panel, número de
    elementos y escuchas, solicitudes por interacción). NO mide el SDK real: descarga del SDK, teselas, dibujo y gestos de Google necesitan una clave real
    y un teléfono (ver docs/GOOGLE-MAPS.md «Prueba con tu clave»). Esas columnas se marcan «no medido».
  · CPU 4× más lenta (emulación de gama media) y viewport móvil de 390×844 en todas las mediciones.

Uso:  SEVB_E2E_URL=http://127.0.0.1:18080/ python e2e/rendimiento/medir_mapa.py [N=7] [salida.json]
"""
import json
import os
import statistics
import sys
import time

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), '..'))
import sevb_e2e as B  # noqa: E402
import mapa_adaptadores_e2e as M  # noqa: E402
from playwright.sync_api import sync_playwright  # noqa: E402

N = int(sys.argv[1]) if len(sys.argv) > 1 else 7
SALIDA = sys.argv[2] if len(sys.argv) > 2 else None
PUERTO_GOOGLE = int(os.environ.get('SEVB_E2E_PUERTO_PERF', '18127'))
MOVIL = {'viewport': {'width': 390, 'height': 844}, 'is_mobile': True, 'has_touch': True, 'device_scale_factor': 2}

INIT_TAREAS = """window.__tareas = []; try { new PerformanceObserver((l) => l.getEntries().forEach((e) => window.__tareas.push(e.duration))).observe({ type: 'longtask', buffered: true }); } catch (e) {}"""


def mediana(v):
    return round(statistics.median(v), 1) if v else None


def p90(v):
    if len(v) < 5:
        return None                      # con pocas repeticiones un percentil no significa nada
    return round(sorted(v)[max(0, int(round(0.9 * (len(v) - 1))))], 1)


def contexto(nav, base, google, **extra):
    B.URL = base
    if google:
        g = M.Google(nav, base, **{**MOVIL, **extra})
        s = g.s
    else:
        g = None
        s = B.Sesion(nav, **{**MOVIL, **extra})
    s.ctx.add_init_script(INIT_TAREAS)
    cdp = s.ctx.new_cdp_session(s.page)
    cdp.send('Emulation.setCPUThrottlingRate', {'rate': 4})
    return g, s, cdp


def heap(cdp):
    cdp.send('HeapProfiler.collectGarbage')
    m = {x['name']: x['value'] for x in cdp.send('Performance.getMetrics')['metrics']}
    return round(m.get('JSHeapUsedSize', 0) / 1048576, 2)


def carga(nav, base, google, caliente):
    """Una carga de la página: marcas de tiempo desde el inicio de la navegación y recursos pedidos."""
    g, s, cdp = contexto(nav, base, google)
    cdp.send('Performance.enable')
    p = s.page
    peticiones = []
    p.on('request', lambda r: peticiones.append(r.url))
    p.goto(B.URL, wait_until='domcontentloaded')
    B.esperar(p, '!!(window.Spevb && Spevb.maps && Spevb.maps.map)', 30000)
    if caliente:
        peticiones.clear()
        p.reload(wait_until='domcontentloaded')
        B.esperar(p, '!!(window.Spevb && Spevb.maps && Spevb.maps.map)', 30000)
    p.wait_for_timeout(800)
    r = p.evaluate("""() => { const t = (n) => { const e = performance.getEntriesByName(n)[0]; return e ? Math.round(e.startTime) : null; };
        const rec = performance.getEntriesByType('resource');
        return { config: t('sevb-config-lista'), adaptador: t('sevb-adaptador-listo'), sdk: t('sevb-sdk-listo'), mapa: t('sevb-mapa-listo'),
                 dcl: Math.round(performance.timing.domContentLoadedEventEnd - performance.timing.navigationStart),
                 js_kb: Math.round(rec.filter((x) => /\\.js(\\?|$)/.test(x.name)).reduce((a, x) => a + (x.encodedBodySize || 0), 0) / 1024),
                 solicitudes: rec.length, tareas: window.__tareas.length, tareas_ms: Math.round(window.__tareas.reduce((a, b) => a + b, 0)) }; }""")
    r['externas'] = len(M.externas(peticiones))
    r['heap_mb'] = heap(cdp)
    (g.cerrar() if g else s.cerrar())
    return r


def interacciones(nav, base, google):
    g, s, cdp = contexto(nav, base, google)
    cdp.send('Performance.enable')
    p = s.page
    api = []
    p.on('request', lambda r: api.append(r.url) if '/api/' in r.url else None)
    p.goto(B.URL, wait_until='domcontentloaded')
    B.esperar(p, '!!(window.Spevb && Spevb.maps && Spevb.maps.map)', 30000)
    p.wait_for_timeout(800)
    out = {}
    out['heap_inicio_mb'] = heap(cdp)
    # 1) planificar A→B: tiempo hasta que el planificador termina de dibujar la opción recomendada y solicitudes de API que provoca
    antes = len(api)
    p.fill('#tripOrigin', B.A_LEJOS); p.keyboard.press('Enter'); p.wait_for_timeout(700)
    p.fill('#tripDestination', B.B); p.keyboard.press('Enter'); p.wait_for_timeout(700)
    api_pre = len(api)
    t0 = time.perf_counter()
    p.evaluate("document.querySelector('#planTrip').click()")
    B.esperar(p, '!!(Spevb.Planner.seleccion && Spevb.Planner.seleccion.viaje)', 30000, 50)
    out['planificar_ms'] = round((time.perf_counter() - t0) * 1000)
    p.wait_for_timeout(1500)
    out['api_al_planificar'] = len(api) - api_pre
    out['api_al_escribir_a_y_b'] = api_pre - antes
    # 2) abrir y cerrar el panel 10 veces: tareas largas y cambios de tamaño del contenedor
    p.evaluate('window.__tareas.length = 0')
    for _ in range(10):
        p.locator('#panelHide').click(); p.wait_for_timeout(120); p.locator('#panelShow').click(); p.wait_for_timeout(120)
    out['panel_tareas_largas'] = p.evaluate('window.__tareas.length')
    # 3) ráfaga de movimientos de cámara (30 desplazamientos y 10 zooms): trabajo propio + del motor (solo comparable con Leaflet real)
    p.evaluate('window.__tareas.length = 0')
    r = p.evaluate("""async () => { const m = Spevb.maps.map, c = m.getCenter(), t0 = performance.now(), cuadros = [];
        let ult = performance.now();
        for (let i = 0; i < 30; i++) { m.setView([c[0] + i * 0.0004, c[1] + i * 0.0004], 15, { animate: false }); await new Promise((ok) => requestAnimationFrame(() => { const a = performance.now(); cuadros.push(a - ult); ult = a; ok(); })); }
        for (let i = 0; i < 10; i++) { m.setView(m.getCenter(), 13 + (i % 4), { animate: false }); await new Promise((ok) => requestAnimationFrame(() => { const a = performance.now(); cuadros.push(a - ult); ult = a; ok(); })); }
        cuadros.sort((a, b) => a - b);
        return { total_ms: Math.round(performance.now() - t0), cuadro_med: Math.round(cuadros[Math.floor(cuadros.length / 2)]), cuadro_max: Math.round(cuadros.at(-1)) }; }""")
    out['camara'] = r
    out['camara_tareas_largas'] = p.evaluate('window.__tareas.length')
    # 4) estabilidad: 10 búsquedas repetidas → elementos, escuchas e instancias de Map
    base_el = p.evaluate('Spevb.maps.map.elementos().length')
    p.evaluate('window.__tareas.length = 0')
    for _ in range(10):
        p.evaluate("document.querySelector('#planTrip').click()")
        p.wait_for_timeout(1400)
    out['elementos_tras_10_busquedas'] = p.evaluate('Spevb.maps.map.elementos().length')
    out['elementos_tras_la_primera'] = base_el
    out['tareas_largas_10_busquedas'] = p.evaluate('window.__tareas.length')
    if google:
        out['instancias_de_map'] = p.evaluate('__gfake.mapas')
        out['escuchas_activas'] = p.evaluate('__gfake.escuchas')
        out['marcadores_visibles'] = p.evaluate('__gfake.marcadores.filter(m => m.map).length')
        out['polilineas_visibles'] = p.evaluate('__gfake.polilineas.filter(l => l._map).length')
    else:
        out['instancias_de_map'] = 1
    out['heap_final_mb'] = heap(cdp)
    (g.cerrar() if g else s.cerrar())
    return out


def resumen(lista, clave):
    v = [x[clave] for x in lista if x.get(clave) is not None]
    return {'mediana': mediana(v), 'p90': p90(v), 'n': len(v)}


def main():
    res = {'N': N, 'condiciones': 'Edge/Chromium headless, CPU 4× más lenta, 390×844 @2×, servidor simulado local sin latencia de red', 'fecha': time.strftime('%Y-%m-%d')}
    base_leaflet = M.URL_LEAFLET
    base_google = M.lanzar_stub(PUERTO_GOOGLE)
    try:
        with sync_playwright() as p:
            nav = B.navegador(p)
            res['navegador'] = nav.browser_type.name + ' ' + nav.version
            for nombre, base, google in (('leaflet', base_leaflet, False), ('google_simulado', base_google, True)):
                fr = [carga(nav, base, google, False) for _ in range(N)]
                ca = [carga(nav, base, google, True) for _ in range(N)]
                inter = [interacciones(nav, base, google) for _ in range(max(3, N // 2))]
                claves_carga = ('config', 'adaptador', 'sdk', 'mapa', 'dcl', 'js_kb', 'solicitudes', 'externas', 'tareas', 'tareas_ms', 'heap_mb')
                res[nombre] = {
                    'carga_fria': {k: resumen(fr, k) for k in claves_carga},
                    'carga_caliente': {k: resumen(ca, k) for k in claves_carga},
                    'interaccion': {k: resumen(inter, k) for k in ('planificar_ms', 'api_al_planificar', 'api_al_escribir_a_y_b', 'panel_tareas_largas', 'camara_tareas_largas',
                                                                  'elementos_tras_la_primera', 'elementos_tras_10_busquedas', 'tareas_largas_10_busquedas',
                                                                  'heap_inicio_mb', 'heap_final_mb', 'instancias_de_map', 'escuchas_activas', 'marcadores_visibles', 'polilineas_visibles')},
                    'camara': {k: mediana([i['camara'][k] for i in inter]) for k in ('total_ms', 'cuadro_med', 'cuadro_max')},
                }
            nav.close()
    finally:
        for pr in M._PROCESOS:
            pr.terminate()
    txt = json.dumps(res, ensure_ascii=False, indent=2)
    print(txt)
    if SALIDA:
        open(SALIDA, 'w', encoding='utf-8').write(txt)


if __name__ == '__main__':
    M.BASES = {}
    main()
