"""Carga inicial REAL de la página principal en Chromium (Playwright): solicitudes, bytes, DCL/load, FCP/LCP, tareas largas, JS sin usar al cargar.
Uso: python e2e/rendimiento/medir_carga.py URL [CPU] [repeticiones] [ruta-salida.json]   (CPU = factor de ralentización, 1 = sin limitar; 4 = móvil de gama media)"""
import json, statistics, sys, time
import os
sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
from playwright.sync_api import sync_playwright
import sevb_e2e as H

URL = sys.argv[1] if len(sys.argv) > 1 else 'http://127.0.0.1:8080/'
CPU = float(sys.argv[2]) if len(sys.argv) > 2 else 1
N = int(sys.argv[3]) if len(sys.argv) > 3 else 5
SALIDA = sys.argv[4] if len(sys.argv) > 4 else None
OBS = """
window.__m = {lcp: 0, fcp: 0, largas: [], cls: 0};
new PerformanceObserver(l => { for (const e of l.getEntries()) window.__m.lcp = Math.max(window.__m.lcp, e.startTime); }).observe({type: 'largest-contentful-paint', buffered: true});
new PerformanceObserver(l => { for (const e of l.getEntries()) if (e.name === 'first-contentful-paint') window.__m.fcp = e.startTime; }).observe({type: 'paint', buffered: true});
new PerformanceObserver(l => { for (const e of l.getEntries()) window.__m.largas.push([Math.round(e.startTime), Math.round(e.duration)]); }).observe({type: 'longtask', buffered: true});
new PerformanceObserver(l => { for (const e of l.getEntries()) if (!e.hadRecentInput) window.__m.cls += e.value; }).observe({type: 'layout-shift', buffered: true});
"""

resultados = []
with sync_playwright() as p:
    nav = H.navegador(p)
    if nav is None:
        import inspect
        print([n for n, f in inspect.getmembers(H, inspect.isfunction)][:40]); sys.exit(2)
    for i in range(N):
        ctx = nav.new_context(viewport={'width': 390, 'height': 800}, service_workers='block', is_mobile=True, has_touch=True)
        ctx.add_init_script("try{localStorage.setItem('spevb-voz','no');localStorage.setItem('spevb-bienvenida','2');}catch(e){}")
        pg = ctx.new_page()
        pg.add_init_script(OBS)
        cdp = ctx.new_cdp_session(pg)
        if CPU > 1: cdp.send('Emulation.setCPUThrottlingRate', {'rate': CPU})
        cdp.send('Network.enable'); cdp.send('Network.setCacheDisabled', {'cacheDisabled': True})
        solicitudes = []
        pg.on('response', lambda r: solicitudes.append((r.url, r.status, r.headers.get('content-encoding', ''))))
        if i == 0:
            cdp.send('Profiler.enable'); cdp.send('Profiler.startPreciseCoverage', {'callCount': True, 'detailed': True})
        t0 = time.perf_counter()
        pg.goto(URL, wait_until='load')
        pg.wait_for_timeout(2500)                       # deja que termine lo diferido y los primeros renders
        m = pg.evaluate("""() => { const n = performance.getEntriesByType('navigation')[0];
            const rs = performance.getEntriesByType('resource');
            return {dcl: n.domContentLoadedEventEnd, load: n.loadEventEnd, res: rs.map(r => [r.name, r.transferSize, r.encodedBodySize, r.decodedBodySize, r.initiatorType, Math.round(r.startTime), Math.round(r.responseEnd)]),
                    m: window.__m, nodos: document.getElementsByTagName('*').length, heap: performance.memory ? performance.memory.usedJSHeapSize : 0}; }""")
        cov = None
        if i == 0:
            cov = cdp.send('Profiler.takePreciseCoverage')['result']
        res = m['res']
        js = [r for r in res if r[0].endswith('.js')]
        css = [r for r in res if r[0].endswith('.css')]
        fila = {'dcl': round(m['dcl']), 'load': round(m['load']), 'fcp': round(m['m']['fcp']), 'lcp': round(m['m']['lcp']), 'cls': round(m['m']['cls'], 4),
                'largas': len(m['m']['largas']), 'largas_ms': sum(d for _, d in m['m']['largas']), 'peor_larga': max([d for _, d in m['m']['largas']] or [0]),
                'solicitudes': len(res) + 1, 'bytes_red': sum(r[1] for r in res), 'bytes_js_red': sum(r[1] for r in js), 'bytes_css_red': sum(r[1] for r in css),
                'bytes_js_dec': sum(r[3] for r in js), 'nodos': m['nodos'], 'js_n': len(js), 'css_n': len(css)}
        resultados.append(fila)
        if i == 0:
            tamanos = {r[0]: r[3] for r in res if r[0].endswith('.js')}
            usados = {}
            for e in cov:
                if e['url'] not in tamanos: continue
                tam = tamanos[e['url']] or 1
                # un byte cuenta como ejecutado si lo cubre un rango con count > 0 y no lo anula un rango interior con count 0 (rangos anidados: el más interno manda)
                marca = bytearray(tam)
                for f in e['functions']:
                    for r_ in sorted(f['ranges'], key=lambda x: (x['startOffset'], -x['endOffset'])):
                        v = 1 if r_['count'] > 0 else 0
                        for k in range(r_['startOffset'], min(tam, r_['endOffset'])): marca[k] = v
                usados[e['url']] = (tam, sum(marca))
            if usados:
                print('--- JS sin ejecutar durante la carga (ordenado por bytes sin usar) ---')
                tot = sum(t for t, _ in usados.values()); uso = sum(u for _, u in usados.values())
                for u, (t, us) in sorted(usados.items(), key=lambda x: -(x[1][0] - x[1][1]))[:14]:
                    print(f'{t - us:8d} B sin usar de {t:8d} ({100 * (t - us) / max(1, t):3.0f} %)  {u.split("//")[-1].split("/", 1)[-1]}')
                print(f'TOTAL JS: {tot} B, ejecutado {uso} B ({100 * uso / max(1, tot):.0f} %)')
            print('--- 10 recursos más pesados (bytes en la red) ---')
            for r in sorted(res, key=lambda r: -r[1])[:10]: print(f'{r[1]:8d} B  dec {r[3]:8d}  {r[0].split("//")[-1].split("/", 1)[-1]}  [{r[4]}] fin {r[6]} ms')
            print('--- inicio de cada script (ms) ---')
            print(', '.join(f"{r[0].rsplit('/', 1)[-1]}:{r[5]}→{r[6]}" for r in js)[:900])
            print('--- tareas largas (inicio, ms) ---', m['m']['largas'][:14])
        ctx.close()
    nav.close()
def med(k): return round(statistics.median(r[k] for r in resultados))
print(f'\nMEDIANA de {N} cargas (CPU x{CPU:g}):')
for k in ('dcl', 'load', 'fcp', 'lcp', 'largas', 'largas_ms', 'peor_larga', 'solicitudes', 'bytes_red', 'bytes_js_red', 'bytes_css_red', 'bytes_js_dec', 'nodos', 'js_n', 'css_n'):
    print(f'  {k:14s} {med(k)}')
print('  cls            ', resultados[0]['cls'])
if SALIDA: json.dump(resultados, open(SALIDA, 'w'))
