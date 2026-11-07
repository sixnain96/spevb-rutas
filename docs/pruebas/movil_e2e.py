# Prueba de extremo a extremo en celular emulado (Android e iPhone): elegir punto en el mapa con toques "temblorosos",
# avisos visibles del GPS y seguimiento. Uso: SEVB_URL=http://localhost:8080/ python docs/pruebas/movil_e2e.py
import asyncio, os
from playwright.async_api import async_playwright
URL = os.environ.get('SEVB_URL', 'http://localhost:8080/')
CHROME = os.environ.get('CHROME_PATH') or None
UAS = {'android': ('Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36', (412, 915)),
       'iphone': ('Mozilla/5.0 (iPhone; CPU iPhone OS 17_4 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.4 Mobile/15E148 Safari/604.1', (390, 844))}
res = []
def check(n, c, d=''):
    res.append(c); print('OK   ' if c else 'FALLA', n, '|', d)

async def main():
    async with async_playwright() as p:
        b = await p.chromium.launch(executable_path=CHROME, args=['--no-sandbox'])
        for nombre, (ua, (w, h)) in UAS.items():
            ctx = await b.new_context(viewport={'width': w, 'height': h}, is_mobile=True, has_touch=True, user_agent=ua,
                                      geolocation={'latitude': 4.15, 'longitude': -73.63287, 'accuracy': 18}, permissions=['geolocation'])
            page = await ctx.new_page(); errs = []; page.on('pageerror', lambda e: errs.append(str(e)))
            await page.goto(URL, wait_until='domcontentloaded'); await page.wait_for_timeout(2500)
            # 1) elegir origen con un toque que se mueve 18 px (más que el umbral de 3 px de Leaflet)
            await page.tap('#pickOrigin'); await page.wait_for_timeout(600)
            aviso = await page.locator('.spevb-toast').first.inner_text()
            check(nombre + ' aviso con «Confirmar» y «Cancelar»', 'Confirmar' in aviso and 'Cancelar' in aviso, aviso.replace('\n', ' '))
            check(nombre + ' pin central visible', await page.locator('.center-pin').is_visible())
            x, y = w // 2, 250
            await page.mouse.move(x, y); await page.mouse.down(); await page.mouse.move(x + 18, y, steps=3); await page.mouse.up()
            await page.wait_for_timeout(2500)
            check(nombre + ' toque tembloroso elige el origen', bool(await page.input_value('#tripOrigin')), (await page.input_value('#tripOrigin'))[:40])
            check(nombre + ' el aviso fijo y el pin desaparecen', await page.locator('.spevb-toast:has-text("Cancelar")').count() == 0 and not await page.locator('.center-pin').is_visible())
            # 2) cancelar la selección
            await page.tap('#pickDestination'); await page.wait_for_timeout(500)
            await page.tap('.spevb-toast button:has-text("Cancelar")'); await page.wait_for_timeout(400)
            check(nombre + ' «Cancelar» sale del modo elegir', await page.get_attribute('#pickDestination', 'aria-pressed') == 'false')
            # 3) arrastrar el mapa NO elige un punto
            await page.tap('#pickDestination'); await page.wait_for_timeout(500)
            await page.mouse.move(x, y); await page.mouse.down(); await page.mouse.move(x + 120, y + 60, steps=6); await page.mouse.up()
            await page.wait_for_timeout(1500)
            check(nombre + ' arrastrar el mapa no elige destino', not await page.input_value('#tripDestination'))
            # 3b) mover el mapa y confirmar con el pin central
            await page.mouse.move(x, y); await page.mouse.down(); await page.mouse.move(x - 60, y + 40, steps=6); await page.mouse.up()
            await page.wait_for_timeout(500)
            await page.tap('.spevb-toast button:has-text("Confirmar")'); await page.wait_for_timeout(2500)
            check(nombre + ' mover el mapa y «Confirmar» elige el destino', bool(await page.input_value('#tripDestination')), (await page.input_value('#tripDestination'))[:40])
            check(nombre + ' tras confirmar se cierra el modo elegir', not await page.locator('.center-pin').is_visible())
            # 4) GPS: el progreso y el resultado salen como aviso sobre el mapa
            await page.tap('#useMyLocation'); await page.wait_for_timeout(2500)
            toast = await page.locator('.spevb-toast').first.inner_text()
            check(nombre + ' GPS: aviso visible con el resultado', 'ubicación' in toast.lower() or 'precisión' in toast.lower(), toast.replace('\n', ' ')[:90])
            check(nombre + ' sin errores JS', not errs, str(errs))
            await ctx.close()
            # 5) permiso bloqueado: el error se ve como aviso (no solo al fondo del panel)
            ctx = await b.new_context(viewport={'width': w, 'height': h}, is_mobile=True, has_touch=True, user_agent=ua)
            page = await ctx.new_page(); await page.goto(URL, wait_until='domcontentloaded'); await page.wait_for_timeout(2000)
            await page.evaluate("navigator.geolocation.getCurrentPosition = (ok, err) => err({code: 1}); navigator.geolocation.watchPosition = (ok, err) => { setTimeout(() => err({code: 1}), 50); return 1; }")
            await page.tap('#useMyLocation'); await page.wait_for_timeout(1500)
            vis = await page.locator('.spevb-toast.error').is_visible()
            texto = await page.locator('.spevb-toast.error').inner_text() if vis else ''
            check(nombre + ' permiso denegado: aviso rojo con instrucciones', vis and 'bloqueado' in texto, texto[:80])
            await ctx.close()
        await b.close()
    print('\nRESUMEN', sum(res), 'de', len(res))
asyncio.run(main())
