"""Medicion reproducible de la carga inicial en Chromium: 390x844 y 1280x800, red normal y red movil simulada (150 ms, ~1,6 Mbps).
Registra FCP, LCP, DOMContentLoaded, load, CLS, tareas largas, solicitudes, bytes y errores de consola (mediana de N repeticiones).

Uso: python e2e/rendimiento/medir_perfiles.py URL [repeticiones] [salida.json]
Necesita un servidor ya levantado (p. ej. `python -I e2e/stub_server.py 18090`). No toca bases de datos ni credenciales.
"""
import json
import os
import statistics
import sys

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
from playwright.sync_api import sync_playwright
import sevb_e2e as H

URL = sys.argv[1] if len(sys.argv) > 1 else 'http://127.0.0.1:18090/'
N = int(sys.argv[2]) if len(sys.argv) > 2 else 5
SALIDA = sys.argv[3] if len(sys.argv) > 3 else None
PERFILES = [('390x844 normal', 390, 844, None), ('390x844 red movil', 390, 844, 'movil'),
            ('1280x800 normal', 1280, 800, None), ('1280x800 red movil', 1280, 800, 'movil')]
OBS = """
window.__m = {lcp: 0, fcp: 0, largas: [], cls: 0};
new PerformanceObserver(l => { for (const e of l.getEntries()) window.__m.lcp = Math.max(window.__m.lcp, e.startTime); }).observe({type: 'largest-contentful-paint', buffered: true});
new PerformanceObserver(l => { for (const e of l.getEntries()) if (e.name === 'first-contentful-paint') window.__m.fcp = e.startTime; }).observe({type: 'paint', buffered: true});
new PerformanceObserver(l => { for (const e of l.getEntries()) window.__m.largas.push(Math.round(e.duration)); }).observe({type: 'longtask', buffered: true});
new PerformanceObserver(l => { for (const e of l.getEntries()) if (!e.hadRecentInput) window.__m.cls += e.value; }).observe({type: 'layout-shift', buffered: true});
"""
med = lambda xs: round(statistics.median(xs), 3) if xs else 0
salida = {}
with sync_playwright() as p:
    nav = H.navegador(p)
    for nombre, w, h, red in PERFILES:
        filas = []
        for _ in range(N):
            movil = w < 500
            ctx = nav.new_context(viewport={'width': w, 'height': h}, service_workers='block', is_mobile=movil, has_touch=movil)
            ctx.add_init_script("try{localStorage.setItem('spevb-voz','no');localStorage.setItem('spevb-bienvenida','2');}catch(e){}")
            ctx.route(lambda u: not u.startswith(URL.rstrip('/')) and not u.startswith('data:') and not u.startswith('blob:'),
                      lambda r: r.fulfill(status=200, content_type='image/png', body=H.PNG_1PX) if r.request.resource_type == 'image' else r.abort())
            pg = ctx.new_page()
            errores, bytes_, n = [], [0], [0]
            pg.on('console', lambda m: errores.append(m.text) if m.type == 'error' and 'Failed to load resource' not in m.text else None)
            pg.on('pageerror', lambda e: errores.append(str(e)))
            pg.on('requestfinished', lambda r: (n.__setitem__(0, n[0] + 1)))
            pg.add_init_script(OBS)
            cdp = ctx.new_cdp_session(pg)
            cdp.send('Network.enable'); cdp.send('Network.setCacheDisabled', {'cacheDisabled': True})
            if red == 'movil':
                cdp.send('Network.emulateNetworkConditions', {'offline': False, 'latency': 150, 'downloadThroughput': 1.6e6 / 8, 'uploadThroughput': 0.75e6 / 8})
            cdp.on('Network.loadingFinished', lambda e: bytes_.__setitem__(0, bytes_[0] + int(e.get('encodedDataLength', 0))))
            pg.goto(URL, wait_until='load', timeout=120000)
            pg.wait_for_timeout(2500)
            m = pg.evaluate("""() => { const n = performance.getEntriesByType('navigation')[0];
                return {dcl: n.domContentLoadedEventEnd, load: n.loadEventEnd, m: window.__m}; }""")
            filas.append(dict(fcp=m['m']['fcp'], lcp=m['m']['lcp'], dcl=m['dcl'], load=m['load'], cls=m['m']['cls'], largas=len(m['m']['largas']),
                              largas_ms=sum(m['m']['largas']), solicitudes=n[0], kb=round(bytes_[0] / 1024, 1), errores=len(errores)))
            ctx.close()
        salida[nombre] = {k: med([f[k] for f in filas]) for k in filas[0]}
        print(nombre, json.dumps(salida[nombre]))
if SALIDA:
    with open(SALIDA, 'w', encoding='utf-8') as f:
        json.dump(salida, f, indent=1, ensure_ascii=False)
