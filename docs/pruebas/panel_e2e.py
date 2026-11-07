# v4.8: panel del planificador ocultable (celular y computador). Uso: SEVB_URL=http://localhost:8080/ python docs/pruebas/panel_e2e.py
import asyncio, os
from playwright.async_api import async_playwright
URL = os.environ.get('SEVB_URL', 'http://localhost:8080/'); CHROME = os.environ.get('CHROME_PATH') or None
res = []
def ok(n, c, d=''): res.append(c); print(('OK   ' if c else 'FALLA'), n, '|', d)
async def main():
    async with async_playwright() as p:
        b = await p.chromium.launch(executable_path=CHROME, args=['--no-sandbox'])
        for nombre, vp, mob in (('celular', {'width': 390, 'height': 844}, True), ('computador', {'width': 1280, 'height': 800}, False)):
            c = await b.new_context(viewport=vp, is_mobile=mob, has_touch=mob); pg = await c.new_page(); errs = []
            pg.on('pageerror', lambda e: errs.append(str(e)))
            await pg.goto(URL); await pg.wait_for_timeout(2500)
            await pg.fill('#tripOrigin', 'Parque Los Libertadores')
            await pg.click('#panelHide'); await pg.wait_for_timeout(700)
            est = await pg.evaluate("({v:getComputedStyle(sheet).visibility,top:sheet.getBoundingClientRect().top,inert:sheet.inert,btn:!panelShow.hidden,fab:document.querySelector('.map-fabs').getBoundingClientRect().bottom})")
            ok(nombre + ': el panel se oculta', est['v'] == 'hidden' and est['inert'] and est['btn'], str(est))
            ok(nombre + ': el mapa queda libre (botones abajo)', est['fab'] > vp['height'] - 40 if mob else True, str(est['fab']))
            await pg.click('#panelShow'); await pg.wait_for_timeout(700)
            est = await pg.evaluate("({v:getComputedStyle(sheet).visibility,val:tripOrigin.value,inert:sheet.inert,btn:!panelShow.hidden,top:sheet.getBoundingClientRect().top})")
            ok(nombre + ': vuelve con lo escrito intacto', est['v'] == 'visible' and est['val'] == 'Parque Los Libertadores' and not est['inert'] and not est['btn'], str(est))
            await pg.click('#panelHide'); await pg.wait_for_timeout(500)
            await pg.evaluate("Spevb.Ajustes.set('panelAuto', false)")      # con el ajuste apagado (v4.9) un resultado nuevo muestra el panel, como siempre
            await pg.evaluate("window.dispatchEvent(new Event('spevb-results'))"); await pg.wait_for_timeout(700)
            ok(nombre + ': un resultado nuevo lo vuelve a mostrar', await pg.evaluate("getComputedStyle(sheet).visibility") == 'visible')
            if mob:
                await pg.evaluate("window.dispatchEvent(new Event('spevb-focus-map'))"); await pg.wait_for_timeout(500)
                box = await pg.evaluate("(()=>{const r=sheetHandle.getBoundingClientRect();return [r.x+r.width/2,r.y+10]})()")
                await pg.mouse.move(*box); await pg.mouse.down(); await pg.mouse.move(box[0], box[1] + 120, steps=6); await pg.mouse.up(); await pg.wait_for_timeout(700)
                ok(nombre + ': deslizar hacia abajo lo oculta', await pg.evaluate("document.body.classList.contains('panel-oculto')"))
            if await pg.evaluate("document.body.classList.contains('panel-oculto')"): await pg.click('#panelShow')
            await pg.click('#menuButton'); await pg.wait_for_timeout(500)
            ok(nombre + ': el menú sigue abriendo', await pg.evaluate("document.getElementById('sideMenu').classList.contains('open')"))
            ok(nombre + ': sin errores JS', not errs, str(errs))
        await b.close()
asyncio.run(main()); print('RESULTADO:', sum(res), '/', len(res))
