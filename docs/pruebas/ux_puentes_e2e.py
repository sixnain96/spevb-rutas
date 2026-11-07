# v4.9: puentes peatonales (página de administración + capa en el mapa), panel que baja solo al buscar, «Seguir bus» que fija el mapa y
# controles del bus que se ocultan y se vuelven a abrir. Requiere las rutas de transbordo_demo.sql y EDITOR_ENABLED=true.
# Uso: SEVB_URL=http://localhost:8080/ EDITOR_TOKEN=... python docs/pruebas/ux_puentes_e2e.py
import asyncio, json, os, urllib.request
from playwright.async_api import async_playwright
URL = os.environ.get('SEVB_URL', 'http://localhost:8080/'); TOKEN = os.environ.get('EDITOR_TOKEN', '0123456789abcdef0123456789abcdef0123')
CHROME = os.environ.get('CHROME_PATH') or None
BASE = URL.rstrip('/')
res = []
def check(n, c, d=''):
    res.append(bool(c)); print('OK   ' if c else 'FALLA', n, '|', d)
def api(m, u, d=None):
    r = urllib.request.Request(BASE + u, method=m, data=json.dumps(d).encode() if d else None, headers={'X-Editor-Token': TOKEN, 'Content-Type': 'application/json'})
    with urllib.request.urlopen(r) as x:
        t = x.read(); return json.loads(t) if t else None

async def buscar(page):
    await page.fill('#tripOrigin', '4.1432, -73.6020'); await page.keyboard.press('Enter'); await page.wait_for_timeout(900)
    await page.fill('#tripDestination', '4.1548, -73.5834'); await page.keyboard.press('Enter'); await page.wait_for_timeout(900)
    await page.evaluate("document.querySelector('#planTrip').click()")

async def main():
    async with async_playwright() as p:
        b = await p.chromium.launch(executable_path=CHROME, args=['--no-sandbox'])
        # ---------- puentes: página de administración
        for nombre, opts in [('computador', dict(viewport={'width': 1280, 'height': 860})), ('celular', dict(viewport={'width': 390, 'height': 844}, is_mobile=True, has_touch=True))]:
            ctx = await b.new_context(**opts); pg = await ctx.new_page(); errs = []; pg.on('pageerror', lambda e: errs.append(str(e)))
            await pg.goto(BASE + '/puentes.html'); await pg.wait_for_timeout(1500)
            await pg.evaluate("document.getElementById('puenteToken').value = '%s'" % TOKEN)
            await pg.evaluate("SpevbPuentes.map.setView([4.1442, -73.6336], 17, {animate:false})"); await pg.wait_for_timeout(2500)
            check(nombre + ': la página de puentes muestra las calles', await pg.evaluate("document.getElementById('puenteSubtitulo').textContent.includes('puentes en azul')"))
            async def tocar(lat, lng):
                xy = await pg.evaluate("(() => { const p = SpevbPuentes.map.latLngToContainerPoint([%s, %s]), r = document.getElementById('puentesMap').getBoundingClientRect(); return [r.x + p.x, r.y + p.y] })()" % (lat, lng))
                await pg.mouse.click(xy[0], xy[1]); await pg.wait_for_timeout(250)
            await tocar(4.14417, -73.63449); await tocar(4.14437, -73.63271)     # dos puntos junto a calles (se pegan solos)
            conexion = await pg.inner_text('#puenteConexion')
            check(nombre + ': los dos extremos se pegan a la calle', 'Inicio conectado' in conexion and 'Final conectado' in conexion, conexion)
            await pg.fill('#puenteNombre', 'Puente de la prueba'); await pg.click('#puenteGuardar'); await pg.wait_for_timeout(2500)
            est = await pg.inner_text('#puenteStatus')
            check(nombre + ': guarda el puente y avisa que ya se puede cruzar', 'Puente guardado' in est, est)
            check(nombre + ': aparece en la lista', 'Puente de la prueba' in await pg.inner_text('#puenteLista'))
            pg.on('dialog', lambda d: asyncio.ensure_future(d.accept()))
            await pg.click('#puenteLista button:has-text("Borrar")'); await pg.wait_for_timeout(2500)
            check(nombre + ': se borra desde la lista', not any(x['nombre'] == 'Puente de la prueba' for x in api('GET', '/api/puentes')))
            check(nombre + ': sin errores JS', not errs, str(errs)); await ctx.close()
        # ---------- puente por API → aparece en el mapa principal
        r = api('POST', '/api/editor/pasos', {'tipo': 'PUENTE', 'nombre': 'Puente e2e', 'nota': 'prueba', 'coordenadas': [[-73.6344904, 4.1441575], [-73.632702, 4.144369]]})
        check('el servidor guarda el puente conectado por los 2 extremos', r['conectados'] == 2, r['mensaje'])
        pub = api('GET', '/api/puentes')
        check('lista pública de puentes', any(x['nombre'] == 'Puente e2e' for x in pub))
        ctx = await b.new_context(viewport={'width': 1280, 'height': 860}); pg = await ctx.new_page(); errs = []; pg.on('pageerror', lambda e: errs.append(str(e)))
        await pg.goto(BASE + '/'); await pg.wait_for_timeout(2500)
        await pg.evaluate("Spevb.maps.map.setView([4.1442, -73.6336], 17, {animate:false})"); await pg.wait_for_timeout(1800)
        # v4.9.3: los puentes ya no se dibujan todos; solo se destaca el que una caminata realmente cruza
        check('el puente NO se dibuja en el mapa principal si ninguna caminata lo cruza', await pg.locator('.puente-icono').count() == 0)
        check('una caminata que lo recorre lo reconoce (Spevb.Puentes.usados)', await pg.evaluate(
            "async () => { const l = await Spevb.Puentes.cargar(); const b = l.find(x => x.nombre === 'Puente e2e'); "
            "return !!b && Spevb.Puentes.usados(b.trazo.coordinates.map(c => [c[1], c[0]]), l).some(x => x.nombre === 'Puente e2e'); }"))
        check('sin errores JS en el mapa principal', not errs, str(errs)); await ctx.close()
        api('DELETE', '/api/editor/pasos/%d' % r['id'])
        check('el puente borrado ya no aparece', not any(x['nombre'] == 'Puente e2e' for x in api('GET', '/api/puentes')))

        # ---------- celular: panel que baja solo, mapa libre y «Seguir bus»
        ctx = await b.new_context(viewport={'width': 390, 'height': 844}, is_mobile=True, has_touch=True); pg = await ctx.new_page(); errs = []; pg.on('pageerror', lambda e: errs.append(str(e)))
        await pg.goto(BASE + '/'); await pg.wait_for_timeout(2500)
        check('celular: el ajuste «bajar el panel» viene activado', await pg.evaluate("Spevb.Ajustes.get('panelAuto')") is True)
        await buscar(pg); await pg.wait_for_timeout(1800)
        await pg.wait_for_timeout(2200)
        est = await pg.evaluate("({oculto: document.body.classList.contains('panel-oculto'), txt: document.querySelector('#panelShow span').textContent, n: document.querySelectorAll('.trip-option').length})")
        check('celular: al encontrar rutas el panel baja solo', est['oculto'] and est['n'] > 0, str(est))
        check('celular: el botón dice «Ver rutas»', est['txt'] == 'Ver rutas')
        c0 = await pg.evaluate("Spevb.maps.map.getCenter()")
        await pg.mouse.move(200, 300); await pg.mouse.down(); await pg.mouse.move(120, 380, steps=8); await pg.mouse.up(); await pg.wait_for_timeout(500)
        c1 = await pg.evaluate("Spevb.maps.map.getCenter()")
        check('celular: el mapa se puede arrastrar con las rutas mostradas', abs(c0['lat'] - c1['lat']) + abs(c0['lng'] - c1['lng']) > 1e-4, '%s -> %s' % (c0, c1))
        await pg.click('#panelShow'); await pg.wait_for_timeout(700)
        check('celular: «Ver rutas» abre otra vez el panel con las tarjetas', await pg.evaluate("getComputedStyle(sheet).visibility") == 'visible' and await pg.locator('.trip-option').count() > 0)
        # abrir una ruta → bus
        await pg.evaluate("document.querySelector('.trip-option > summary')?.click()"); await pg.wait_for_timeout(2500)
        await pg.click('#panelHide'); await pg.wait_for_timeout(600)
        bm = pg.locator('.bus-min')
        check('celular: los controles del bus empiezan ocultos con botón claro «Controles»', 'Controles' in (await bm.inner_text()), await bm.inner_text())
        await bm.click(); await pg.wait_for_timeout(400)
        check('celular: se abren los controles del bus', not await pg.evaluate("document.querySelector('.bus-panel').classList.contains('collapsed')"))
        for sel in ('.bus-3d', '.bus-follow input'):
            pass
        await pg.click('.bus-follow'); await pg.wait_for_timeout(1200)
        est = await pg.evaluate("({drag: Spevb.maps.map.dragging.enabled(), chip: !document.getElementById('siguiendoBus').hidden})")
        check('celular: «Seguir bus» fija el mapa y muestra el aviso «Soltar»', (not est['drag']) and est['chip'], str(est))
        zoom0 = await pg.evaluate("Spevb.maps.map.getZoom()")
        await pg.evaluate("Spevb.maps.map.setZoom(%s + 1)" % zoom0); await pg.wait_for_timeout(500)
        check('celular: con el mapa fijo se puede seguir acercando', await pg.evaluate("Spevb.maps.map.getZoom()") == zoom0 + 1)
        await pg.click('#siguiendoBus'); await pg.wait_for_timeout(500)
        est = await pg.evaluate("({drag: Spevb.maps.map.dragging.enabled(), chip: !document.getElementById('siguiendoBus').hidden, box: document.querySelector('.bus-follow input').checked})")
        check('celular: «Soltar» devuelve el mapa libre', est['drag'] and not est['chip'] and not est['box'], str(est))
        await pg.click('.bus-min'); await pg.wait_for_timeout(400)
        check('celular: los controles del bus se vuelven a ocultar', await pg.evaluate("document.querySelector('.bus-panel').classList.contains('collapsed')"))
        # ajuste apagado: el panel no baja solo
        await pg.evaluate("Spevb.Ajustes.set('panelAuto', false)"); await pg.click('#panelShow') if await pg.evaluate("document.body.classList.contains('panel-oculto')") else None
        await pg.reload(); await pg.wait_for_timeout(2500); await buscar(pg); await pg.wait_for_timeout(4500)
        check('celular: con el ajuste apagado el panel se queda visible', not await pg.evaluate("document.body.classList.contains('panel-oculto')"))
        check('celular: sin errores JS', not errs, str(errs)); await ctx.close()

        # ---------- computador: el panel no baja solo
        ctx = await b.new_context(viewport={'width': 1280, 'height': 860}); pg = await ctx.new_page(); errs = []; pg.on('pageerror', lambda e: errs.append(str(e)))
        await pg.goto(BASE + '/'); await pg.wait_for_timeout(2500); await buscar(pg); await pg.wait_for_timeout(5000)
        check('computador: el panel sigue visible tras buscar', not await pg.evaluate("document.body.classList.contains('panel-oculto')"))
        await pg.evaluate("document.querySelector('.trip-option > summary')?.click()"); await pg.wait_for_timeout(2500)
        await pg.click('.bus-follow'); await pg.wait_for_timeout(1000)
        check('computador: «Seguir bus» fija el mapa', not await pg.evaluate("Spevb.maps.map.dragging.enabled()"))
        await pg.click('#siguiendoBus'); await pg.wait_for_timeout(400)
        check('computador: «Soltar» lo libera', await pg.evaluate("Spevb.maps.map.dragging.enabled()"))
        check('computador: sin errores JS', not errs, str(errs)); await ctx.close()
        await b.close()
    print('\nRESULTADO:', sum(res), '/', len(res))
asyncio.run(main())
