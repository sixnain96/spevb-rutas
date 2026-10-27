# Prueba de extremo a extremo del GPS (simula ubicaciones y permisos en Chromium).
# Requisitos: pip install playwright ; playwright install chromium ; la app corriendo.
# Uso: SEVB_URL=http://localhost:8080/ python docs/pruebas/gps_e2e.py   (CHROME_PATH opcional)
import asyncio
from playwright.async_api import async_playwright
import os
URL=os.environ.get('SEVB_URL','http://localhost:8080/')
CHROME=os.environ.get('CHROME_PATH') or None
CENTRO={'latitude':4.15000,'longitude':-73.63287}
res=[]
def check(nombre, cond, detalle=''):
    res.append((nombre,cond)); print(('OK   ' if cond else 'FALLA'), nombre, '|', detalle)
async def nueva(b, geo=None, permisos=True):
    ctx = await b.new_context(viewport={'width':1280,'height':860}, geolocation=geo, permissions=['geolocation'] if permisos else [])
    page = await ctx.new_page(); errs=[]
    page.on('pageerror', lambda e: errs.append(str(e)))
    await page.goto(URL, wait_until='domcontentloaded'); await page.wait_for_timeout(2000)
    return ctx, page, errs
async def estado(page): return await page.locator('#tripStatus').inner_text()
async def main():
    async with async_playwright() as p:
        b = await p.chromium.launch(executable_path=CHROME, args=['--no-sandbox'])
        # 1) buena precisión dentro de la ciudad
        ctx,page,errs = await nueva(b, {**CENTRO,'accuracy':15})
        await page.click('#useMyLocation'); await page.wait_for_timeout(2500)
        st=await estado(page); origen=await page.input_value('#tripOrigin')
        check('1 buena precisión: origen asignado', bool(origen), origen)
        check('1 estado muestra precisión excelente ±15', 'excelente' in st and '15' in st, st)
        check('1 marcador azul en el mapa', await page.locator('.gps-dot').count()==1)
        check('1 sin errores JS', not errs, str(errs))
        await ctx.close()
        # 2) precisión regular (400 m): se usa pero avisa
        ctx,page,errs = await nueva(b, {**CENTRO,'accuracy':400})
        await page.click('#useMyLocation'); await page.wait_for_timeout(12500)
        st=await estado(page)
        check('2 precisión 400 m: usa origen y avisa aproximada', bool(await page.input_value('#tripOrigin')) and 'aproximada' in st, st)
        await ctx.close()
        # 3) muy aproximada (3 km): NO se usa como origen
        ctx,page,errs = await nueva(b, {**CENTRO,'accuracy':3000})
        await page.click('#useMyLocation'); await page.wait_for_timeout(12500)
        st=await estado(page)
        check('3 precisión 3 km: no asigna origen', not await page.input_value('#tripOrigin') and 'muy aproximada' in st, st)
        await ctx.close()
        # 4) fuera de Villavicencio
        ctx,page,errs = await nueva(b, {'latitude':4.711,'longitude':-74.0721,'accuracy':10})
        await page.click('#useMyLocation'); await page.wait_for_timeout(2500)
        st=await estado(page)
        check('4 fuera de la ciudad: no asigna y avisa', not await page.input_value('#tripOrigin') and 'fuera de Villavicencio' in st, st)
        await ctx.close()
        # 5) permiso denegado
        ctx,page,errs = await nueva(b, None, permisos=False)
        await page.click('#useMyLocation'); await page.wait_for_timeout(2500)
        st=await estado(page)
        check('5 permiso denegado: explica cómo permitirlo', 'bloqueado' in st and 'candado' in st, st[:160])
        await ctx.close()
        # 6) mejora progresiva: primero 300 m, luego 20 m
        ctx,page,errs = await nueva(b, {**CENTRO,'accuracy':300})
        await page.click('#useMyLocation'); await page.wait_for_timeout(2500)
        mid=await estado(page)
        await ctx.set_geolocation({**CENTRO,'accuracy':20}); await page.wait_for_timeout(3000)
        st=await estado(page)
        check('6 muestra progreso con ±300 mientras mejora', 'Mejorando' in mid and '300' in mid, mid)
        check('6 termina con la mejor lectura ±20', '20' in st and 'quedó como origen' in st, st)
        await ctx.close()
        # 7) seguimiento en vivo con el botón flotante
        ctx,page,errs = await nueva(b, {**CENTRO,'accuracy':10})
        await page.click('#locateMe'); await page.wait_for_timeout(1500)
        check('7 botón activo y marcador visible', await page.get_attribute('#locateMe','aria-pressed')=='true' and await page.locator('.gps-dot').count()==1)
        pos1=await page.locator('.gps-dot').bounding_box()
        await ctx.set_geolocation({'latitude':4.15040,'longitude':-73.63287,'accuracy':10}); await page.wait_for_timeout(3000)
        await page.evaluate("document.querySelector('#map')"); 
        pos2=await page.locator('.gps-dot').bounding_box()
        check('7 el marcador se mueve al cambiar la posición', abs(pos1['x']-pos2['x'])>2 or abs(pos1['y']-pos2['y'])>2, f"{pos1['x']:.0f},{pos1['y']:.0f} -> {pos2['x']:.0f},{pos2['y']:.0f}")
        await page.click('#locateMe'); await page.wait_for_timeout(500)
        check('7 segundo toque detiene el seguimiento', await page.get_attribute('#locateMe','aria-pressed')=='false')
        await page.screenshot(path='gps_follow.png')
        check('7 sin errores JS', not errs, str(errs))
        await ctx.close()
        # 8) contexto no seguro (HTTP en red local): mensaje claro
        ctx,page,errs = await nueva(b, {**CENTRO,'accuracy':10})
        await page.evaluate("Object.defineProperty(window,'isSecureContext',{value:false,configurable:true})")
        await page.click('#useMyLocation'); await page.wait_for_timeout(800)
        st=await estado(page)
        check('8 sin HTTPS: explica por qué y qué hacer', 'HTTPS' in st and 'localhost' in st, st[:200])
        await ctx.close()
        # 9) página de lugares
        ctx = await b.new_context(geolocation={**CENTRO,'accuracy':12}, permissions=['geolocation'])
        pg = await ctx.new_page(); pe=[]; pg.on('pageerror', lambda e: pe.append(str(e)))
        await pg.goto(URL+'lugares.html', wait_until='domcontentloaded'); await pg.wait_for_timeout(1500)
        await pg.click('#useCurrentPlace'); await pg.wait_for_timeout(3500)
        check('9 lugares.html: toma coordenadas y precisión', (await pg.input_value('#placeLat')).startswith('4.15') , (await pg.locator('#placeStatus').inner_text())[:140])
        check('9 sin errores JS', not pe, str(pe))
        await ctx.close()
        await b.close()
    print('\nRESUMEN', sum(1 for _,c in res if c), 'de', len(res), 'correctas')
asyncio.run(main())
