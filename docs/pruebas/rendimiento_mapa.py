# Mide el mapa en un celular simulado (CPU 4x más lenta, red tipo 3G rápida): datos descargados, tiempo hasta ver el mapa
# vectorial y fluidez al arrastrar/hacer zoom. Uso: SEVB_URL=http://localhost:8080/ python docs/pruebas/rendimiento_mapa.py
import asyncio, os, json, statistics
from playwright.async_api import async_playwright
URL = os.environ.get('SEVB_URL', 'http://localhost:8080/')
CHROME = os.environ.get('CHROME_PATH') or None
async def main():
    async with async_playwright() as p:
        kw = {}
        if os.environ.get('HTTPS_PROXY'): kw['proxy'] = {'server': os.environ['HTTPS_PROXY'], 'bypass': 'localhost,127.0.0.1'}
        b = await p.chromium.launch(executable_path=CHROME, args=['--no-sandbox'], **kw)
        ctx = await b.new_context(viewport={'width': 390, 'height': 844}, is_mobile=True, has_touch=True, device_scale_factor=3, service_workers='block')
        page = await ctx.new_page()
        cdp = await ctx.new_cdp_session(page)
        await cdp.send('Emulation.setCPUThrottlingRate', {'rate': 4})
        await cdp.send('Network.enable')
        await cdp.send('Network.emulateNetworkConditions', {'offline': False, 'latency': 150, 'downloadThroughput': 1.6 * 1024 * 1024 / 8, 'uploadThroughput': 750 * 1024 / 8})
        reqs = []
        page.on('response', lambda r: reqs.append((r.url, r.headers.get('content-length'))))
        cdp.on('Network.loadingFinished', lambda e: reqs.append(('fin', e['encodedDataLength'])))
        base = os.environ.get('BASE', 'Moderno')      # Moderno (vectorial), Claro o Calles (ráster)
        await page.add_init_script("try { localStorage.setItem('spevb-basemap', '%s') } catch (e) {}" % base)
        await page.add_init_script("""
            window.__t0 = performance.now(); window.__lt = [];
            try { new PerformanceObserver(l => l.getEntries().forEach(e => window.__lt.push(e.duration))).observe({entryTypes:['longtask']}); } catch(e) {}
        """)
        await page.goto(URL, wait_until='domcontentloaded')
        t_ready = None
        for i in range(160):
            await page.wait_for_timeout(250)
            ok = await page.evaluate("""(vector) => { const m = Spevb.maps && Spevb.maps.map; if (!m) return false; let gl = null;
                m.eachLayer(l => { if (l.getMaplibreMap) gl = l.getMaplibreMap(); }); return gl ? gl.loaded() : (vector ? false : 'raster') }""", base == 'Moderno')
            if ok is True or ok == 'raster': t_ready = (i + 1) * 0.25; break
        await page.wait_for_timeout(2500)
        enc = await page.evaluate("performance.getEntriesByType('resource').reduce((s,e)=>s+(e.transferSize||0),0)")
        n_tiles = await page.evaluate("performance.getEntriesByType('resource').filter(e=>/arcgisonline|openfreemap|openstreetmap/.test(e.name)).length")
        n_esri = await page.evaluate("performance.getEntriesByType('resource').filter(e=>/arcgisonline/.test(e.name)).length")
        # arrastre y zoom con frames medidos
        await page.evaluate("""() => { window.__f = []; let last = performance.now(); (function loop(){ const n = performance.now(); window.__f.push(n-last); last = n; requestAnimationFrame(loop); })(); }""")
        await page.evaluate("window.__lt.length = 0")
        for _ in range(4):
            await page.evaluate("Spevb.maps.map.panBy([160, 90], {animate: true, duration: 0.5, noMoveStart: false})"); await page.wait_for_timeout(700)
            await page.evaluate("Spevb.maps.map.panBy([-160, -90], {animate: true, duration: 0.5})"); await page.wait_for_timeout(700)
        for z in (14, 15, 13, 14):
            await page.evaluate(f"Spevb.maps.map.setZoom({z})"); await page.wait_for_timeout(900)
        f = await page.evaluate("window.__f.slice(5)")
        lt = await page.evaluate("window.__lt")
        f_sorted = sorted(f)
        out = {'listo_s': t_ready, 'transferido_KB': round(enc / 1024), 'peticiones_mapa': n_tiles, 'peticiones_esri_raster': n_esri,
               'frames': len(f), 'frame_mediana_ms': round(statistics.median(f), 1), 'frame_p95_ms': round(f_sorted[int(len(f) * .95)], 1),
               'frames_>50ms': sum(1 for x in f if x > 50), 'tareas_largas': len(lt), 'tareas_largas_total_ms': round(sum(lt))}
        print(json.dumps(out, ensure_ascii=False)); await b.close()
asyncio.run(main())
