# Transbordo: con las rutas de docs/pruebas/transbordo_demo.sql cargadas, un viaje que solo se puede hacer con dos buses.
# Uso: SEVB_URL=http://localhost:8080/ python docs/pruebas/transbordo_e2e.py   (CHROME_PATH opcional)
import asyncio, os
from playwright.async_api import async_playwright
URL = os.environ.get('SEVB_URL', 'http://localhost:8080/')
CHROME = os.environ.get('CHROME_PATH') or None
res = []
def check(n, c, d=''):
    res.append(c); print('OK   ' if c else 'FALLA', n, '|', d)

async def main():
    async with async_playwright() as p:
        b = await p.chromium.launch(executable_path=CHROME, args=['--no-sandbox'])
        for nombre, opts in [('celular', dict(viewport={'width': 390, 'height': 844}, is_mobile=True, has_touch=True)), ('computador', dict(viewport={'width': 1280, 'height': 860}))]:
            ctx = await b.new_context(**opts); page = await ctx.new_page(); errs = []; page.on('pageerror', lambda e: errs.append(str(e)))
            await page.goto(URL, wait_until='domcontentloaded'); await page.wait_for_timeout(2500)
            await page.fill('#tripOrigin', '4.1432, -73.6020'); await page.keyboard.press('Enter'); await page.wait_for_timeout(1200)
            await page.fill('#tripDestination', '4.1548, -73.5834'); await page.keyboard.press('Enter'); await page.wait_for_timeout(1200)
            await page.evaluate("document.querySelector('#planTrip').click()"); await page.wait_for_timeout(6000)
            grupos = await page.evaluate("[...document.querySelectorAll('.results-group > summary strong')].map(e => e.textContent)")
            check(nombre + ': aparece el grupo «Con transbordo»', any('transbordo' in g for g in grupos), str(grupos))
            check(nombre + ': el transbordo va primero', bool(grupos) and 'transbordo' in grupos[0])
            check(nombre + ': tarjeta con 2 pasajes y el cambio', await page.locator('.transfer-option .steps li.swap').count() == 1 and '2 pasajes' in await page.locator('.transfer-option').first.inner_text())
            check(nombre + ': se dibuja el transbordo (3 tramos a pie y 2 buses)', await page.evaluate("document.querySelectorAll('path.walk-line').length") == 3, '')
            await page.wait_for_timeout(500)
            est = await page.evaluate("""() => { const m = Spevb.maps.map, b = m.getBounds(); return {zoom: m.getZoom(),
                A: b.contains([4.1432, -73.6020]), B: b.contains([4.1548, -73.5834])} }""")
            check(nombre + ': A y B quedan dentro del mapa visible', est['A'] and est['B'], str(est))
            check(nombre + ': sin errores JS', not errs, str(errs))
            await ctx.close()
        await b.close()
    print('\nRESUMEN', sum(res), 'de', len(res))
asyncio.run(main())
