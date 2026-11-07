# Mapa optimizado: créditos compactos (solo «Leaflet» + ⓘ), mapa ráster de respaldo solo si hace falta, animaciones en pausa al mover.
# Uso: SEVB_URL=http://localhost:8080/ python docs/pruebas/mapa_e2e.py
import asyncio, os, time
from playwright.async_api import async_playwright
URL = os.environ.get('SEVB_URL', 'http://localhost:8080/')
CHROME = os.environ.get('CHROME_PATH') or None
res = []
def check(n, c, d=''):
    res.append(c); print('OK   ' if c else 'FALLA', n, '|', d)

async def main():
    async with async_playwright() as p:
        kw = {}
        if os.environ.get('HTTPS_PROXY'): kw['proxy'] = {'server': os.environ['HTTPS_PROXY'], 'bypass': 'localhost,127.0.0.1'}
        b = await p.chromium.launch(executable_path=CHROME, args=['--no-sandbox'], **kw)
        for nombre, o in [('celular', dict(viewport={'width': 390, 'height': 844}, is_mobile=True, has_touch=True, device_scale_factor=3)),
                          ('tablet', dict(viewport={'width': 820, 'height': 1180}, is_mobile=True, has_touch=True, device_scale_factor=2)),
                          ('computador', dict(viewport={'width': 1280, 'height': 800}))]:
            ctx = await b.new_context(service_workers='block', **o); page = await ctx.new_page(); errs = []
            page.on('pageerror', lambda e: errs.append(str(e)))
            await page.add_init_script("try{localStorage.setItem('spevb-basemap','Moderno')}catch(e){}")
            esri = []
            await page.goto(URL, wait_until='domcontentloaded')      # primera visita: llena la caché del navegador
            for _ in range(80):
                await page.wait_for_timeout(250)
                if await page.evaluate("(()=>{let g=null;window.Spevb?.maps?.map?.eachLayer(l=>{if(l.getMaplibreMap)g=l.getMaplibreMap()});return !!g&&g.loaded()})()"): break
            await page.wait_for_timeout(1500)
            page.on('request', lambda r: esri.append(r.url) if 'arcgisonline' in r.url else None)
            t_ini = time.time()
            await page.reload(wait_until='domcontentloaded')           # segunda visita: el vectorial llega antes del respaldo
            t_vec = None
            for _ in range(80):
                await page.wait_for_timeout(250)
                if await page.evaluate("(()=>{let g=null;window.Spevb?.maps?.map?.eachLayer(l=>{if(l.getMaplibreMap)g=l.getMaplibreMap()});return !!g&&g.loaded()})()"):
                    t_vec = time.time() - t_ini; break
            await page.wait_for_timeout(800)
            attr = page.locator('.leaflet-control-attribution')
            visible = (await attr.inner_text()).strip()
            check(nombre + ': los créditos cerrados muestran solo «Leaflet ⓘ»', visible.replace('\n', ' ').startswith('Leaflet') and 'OpenFreeMap' not in visible and 'OpenStreetMap' not in visible, repr(visible))
            check(nombre + ': los créditos ocupan poco ancho', (await attr.bounding_box())['width'] < 120, str(await attr.bounding_box()))
            await page.evaluate("document.querySelector('.leaflet-control-attribution').click()"); await page.wait_for_timeout(200)
            abierto = (await attr.inner_text())
            check(nombre + ': al tocar se ven OpenFreeMap y OpenStreetMap', 'OpenFreeMap' in abierto and 'OpenStreetMap' in abierto, abierto[:90])
            await page.evaluate("document.querySelector('.leaflet-control-attribution').click()"); await page.wait_for_timeout(200)
            check(nombre + ': un segundo toque los vuelve a cerrar', 'OpenFreeMap' not in await attr.inner_text())
            check(nombre + ': el mapa vectorial carga', await page.evaluate("(()=>{let g=null;window.Spevb?.maps?.map?.eachLayer(l=>{if(l.getMaplibreMap)g=l.getMaplibreMap()});return !!g&&g.loaded()})()"))
            check(nombre + ': no se descargó el mapa ráster de respaldo (segunda visita)', len(esri) == 0 or (t_vec or 99) > 1.3, str(len(esri)) + ' peticiones a Esri; el vectorial tardó %.1f s (el respaldo solo entra pasado 1,5 s)' % (t_vec or 99))
            w = await page.evaluate("(()=>{const c=document.querySelector('canvas.maplibregl-canvas');return [c.width, c.clientWidth, window.devicePixelRatio]})()")
            check(nombre + ': resolución del mapa limitada a 2×', w[0] / w[1] <= 2.01, str(w))
            await page.evaluate("Spevb.maps.map.fire('dragstart')"); await page.wait_for_timeout(100)
            check(nombre + ': al arrastrar se pausan las animaciones', await page.evaluate("document.querySelector('.leaflet-container').classList.contains('map-moving')"))
            await page.evaluate("Spevb.maps.map.fire('dragend')"); await page.wait_for_timeout(500)
            check(nombre + ': al soltar se reanudan', not await page.evaluate("document.querySelector('.leaflet-container').classList.contains('map-moving')"))
            check(nombre + ': sin errores JS', not errs, str(errs))
            await ctx.close()
        # mapa vectorial lento: aparece el respaldo ráster
        ctx = await b.new_context(viewport={'width': 390, 'height': 844}, is_mobile=True, has_touch=True, service_workers='block'); page = await ctx.new_page()
        await page.add_init_script("try{localStorage.setItem('spevb-basemap','Moderno')}catch(e){}")
        await page.route('**/maplibre-gl-csp.js', lambda r: asyncio.ensure_future(_lento(r)))
        esri2 = []; page.on('request', lambda r: esri2.append(r.url) if 'arcgisonline' in r.url else None)
        await page.goto(URL, wait_until='domcontentloaded'); await page.wait_for_timeout(4500)
        check('motor vectorial lento: entra el mapa ráster de respaldo', len(esri2) > 0, str(len(esri2)))
        await ctx.close(); await b.close()
    print('\nRESULTADO:', sum(res), '/', len(res)); raise SystemExit(0 if all(res) else 1)

async def _lento(route):
    await asyncio.sleep(3.5); await route.continue_()
asyncio.run(main())
