# Sin conexión: guarda el mapa, consulta un viaje, corta la red y comprueba que app, rutas, mapa y viaje reciente siguen funcionando.
# Uso: SEVB_URL=http://localhost:8080/ python docs/pruebas/offline_e2e.py   (CHROME_PATH opcional; necesita internet para descargar el mapa)
import asyncio, os
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
        ctx = await b.new_context(viewport={'width': 390, 'height': 844}, is_mobile=True, has_touch=True, ignore_https_errors=True, service_workers='allow')
        page = await ctx.new_page(); errs = []
        page.on('pageerror', lambda e: errs.append(str(e)))
        await page.goto(URL, wait_until='domcontentloaded'); await page.wait_for_timeout(3000)
        await page.evaluate("navigator.serviceWorker.ready.then(() => 1)")
        await page.reload(wait_until='domcontentloaded'); await page.wait_for_timeout(3000)
        check('el service worker controla la página', await page.evaluate("!!navigator.serviceWorker.controller"))
        # 1) descarga del mapa
        meta = await page.evaluate("""async () => { const m = await Spevb.Offline.descargarMapa({}); return m }""")
        check('el mapa se descarga y se guarda', meta['archivos'] > 50 and meta['bytes'] > 100000, str(meta))
        await page.evaluate("Spevb.Offline.guardarRutas({forzar: true})"); await page.wait_for_timeout(1500)
        # 2) un viaje con red
        await page.fill('#tripOrigin', '4.1432, -73.6020'); await page.keyboard.press('Enter'); await page.wait_for_timeout(1200)
        await page.fill('#tripDestination', '4.1548, -73.5834'); await page.keyboard.press('Enter'); await page.wait_for_timeout(1200)
        await page.evaluate("document.querySelector('#planTrip').click()"); await page.wait_for_timeout(5000)
        n_online = await page.locator('#tripResults .trip-option').count()
        check('con red hay resultados', n_online > 0, str(n_online))
        check('queda guardado en «Recientes»', await page.evaluate("Spevb.Recientes.listar().length") == 1)
        await page.wait_for_timeout(1500)
        # 3) sin red
        await ctx.set_offline(True)
        await page.reload(wait_until='domcontentloaded'); await page.wait_for_timeout(5000)
        check('sin red: la aplicación abre', await page.locator('#planTrip').count() == 1)
        check('sin red: aparece el chip de recientes con texto legible', await page.locator('.recent-item').count() == 1 and '→' in await page.locator('.recent-item').inner_text() and 'object' not in await page.locator('.recent-item').inner_text())
        check('sin red: lista de rutas desde lo guardado', await page.evaluate("document.querySelectorAll('#routeList > *').length") > 0,
              await page.evaluate("document.getElementById('routeCount').textContent"))
        check('sin red: aviso de conexión', await page.locator('#spevbToasts').count() == 1 and 'Sin conexión' in await page.locator('#spevbToasts').inner_text())
        await page.evaluate("document.querySelector('.recent-item').click()"); await page.wait_for_timeout(5000)
        n_off = await page.locator('#tripResults .trip-option').count()
        check('sin red: el viaje reciente se muestra', n_off == n_online and n_off > 0, f'{n_off} vs {n_online}')
        check('sin red: nota de resultado guardado', await page.locator('.saved-trip-note').count() == 1)
        check('sin red: líneas de caminata dibujadas', await page.evaluate("document.querySelectorAll('path.walk-line').length") >= 2)
        check('sin red: se dibuja el bus en el mapa', await page.evaluate("Object.values(Spevb.maps.map._layers).some(l => l.options && l.options.color && l._latlngs && l._latlngs.length > 20)"))
        # 4) mapa vectorial desde lo guardado
        base = await page.evaluate("Spevb.maps.map.activeBase()")
        check('sin red: mapa vectorial activo', base in ('Moderno', 'Minimalista'), base)
        await page.wait_for_timeout(2500)
        ok = await page.evaluate("""async () => { const c = await caches.open('spevb-mapa-v1'); const k = await c.keys(); return k.length }""")
        check('paquete de mapa en caché', ok > 50, str(ok))
        pintado = await page.evaluate("""() => { const c = document.querySelector('.leaflet-maplibre-gl canvas, canvas.maplibregl-canvas'); return !!c }""")
        check('sin red: lienzo del mapa vectorial presente', pintado)
        await page.screenshot(path=os.environ.get('SHOT', 'offline.png'))
        # 5) un viaje NUEVO sin red: mensaje claro, sin romper
        await page.fill('#tripOrigin', '4.1300, -73.6300'); await page.keyboard.press('Enter'); await page.wait_for_timeout(800)
        await page.fill('#tripDestination', '4.1100, -73.6100'); await page.keyboard.press('Enter'); await page.wait_for_timeout(800)
        await page.evaluate("document.querySelector('#planTrip').click()"); await page.wait_for_timeout(4000)
        st = await page.locator('#tripStatus').inner_text()
        check('viaje nuevo sin red: mensaje claro', 'Sin conexión' in st or 'lenta' in st, st)
        # 6) vuelve la red
        await ctx.set_offline(False); await page.wait_for_timeout(1500)
        check('vuelve la red: sin errores JS', not errs, str(errs))
        await ctx.close(); await b.close()
    print('\nRESULTADO:', sum(res), '/', len(res)); raise SystemExit(0 if all(res) else 1)
asyncio.run(main())
