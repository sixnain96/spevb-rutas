# Líneas punteadas de caminata: siguen las calles, se mueven muy suave y respetan «reducir movimiento».
# Requiere la app corriendo con las rutas de ejemplo. Uso: SEVB_URL=http://localhost:8080/ python docs/pruebas/lineas_e2e.py
import asyncio, os
from playwright.async_api import async_playwright
URL = os.environ.get('SEVB_URL', 'http://localhost:8080/')
CHROME = os.environ.get('CHROME_PATH') or None
res = []
def check(n, c, d=''):
    res.append(c); print('OK   ' if c else 'FALLA', n, '|', d)

async def plan(b, **ctx_opts):
    ctx = await b.new_context(viewport={'width': 1280, 'height': 860}, **ctx_opts)
    page = await ctx.new_page(); errs = []; page.on('pageerror', lambda e: errs.append(str(e)))
    await page.goto(URL, wait_until='domcontentloaded'); await page.wait_for_timeout(2500)
    await page.fill('#tripOrigin', '4.1080, -73.6730'); await page.keyboard.press('Enter'); await page.wait_for_timeout(1200)
    await page.fill('#tripDestination', '4.1410, -73.6200'); await page.keyboard.press('Enter'); await page.wait_for_timeout(1200)
    await page.click('#planTrip'); await page.wait_for_timeout(5000)
    lines = await page.evaluate("""() => [...document.querySelectorAll('path.walk-line')].map(p => ({
        vertices: (p.getAttribute('d') || '').split('L').length, anim: getComputedStyle(p).animationName, respaldo: p.classList.contains('walk-fallback') }))""")
    return ctx, page, lines, errs

async def main():
    async with async_playwright() as p:
        b = await p.chromium.launch(executable_path=CHROME, args=['--no-sandbox'])
        ctx, page, lines, errs = await plan(b)
        check('hay líneas punteadas de caminata', len(lines) >= 1, str(lines))
        check('las líneas por calles se animan', any(l['anim'] == 'walk-flow' for l in lines if not l['respaldo']), str(lines))
        check('siguen las calles (varios vértices, no una recta)', any(l['vertices'] > 4 for l in lines), str([l['vertices'] for l in lines]))
        n = await page.evaluate("document.getAnimations().filter(a => a.animationName === 'walk-flow').length")
        check('animaciones CSS activas', n >= 1, str(n))
        check('sin errores JS', not errs, str(errs))
        await ctx.close()
        ctx, page, lines, errs = await plan(b, reduced_motion='reduce')
        check('con «reducir movimiento» las líneas quedan quietas', lines and all(l['anim'] == 'none' for l in lines), str(lines))
        await ctx.close(); await b.close()
    print('\nRESUMEN', sum(res), 'de', len(res))
asyncio.run(main())
