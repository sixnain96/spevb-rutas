# Funciones añadidas: compartir, mis lugares, avisarme al bajar, reportes (envío y lista del administrador).
# Uso: SEVB_URL=http://localhost:8080/ EDITOR_TOKEN=... python docs/pruebas/extras_e2e.py  (necesita las rutas de transbordo_demo.sql)
import asyncio, os, json, urllib.request
from playwright.async_api import async_playwright
URL = os.environ.get('SEVB_URL', 'http://localhost:8080/')
TOKEN = os.environ.get('EDITOR_TOKEN', '0123456789abcdef0123456789abcdef0123')
CHROME = os.environ.get('CHROME_PATH') or None
res = []
def check(n, c, d=''):
    res.append(c); print('OK   ' if c else 'FALLA', n, '|', d)

async def main():
    async with async_playwright() as p:
        b = await p.chromium.launch(executable_path=CHROME, args=['--no-sandbox'])
        ctx = await b.new_context(viewport={'width': 390, 'height': 844}, is_mobile=True, has_touch=True,
                                  geolocation={'latitude': 4.1432, 'longitude': -73.6020, 'accuracy': 10}, permissions=['geolocation'])
        page = await ctx.new_page(); errs = []
        if os.environ.get('DEBUG_GPS'): await page.add_init_script("window.addEventListener('load',()=>{const o=Spevb.Gps.seguir;Spevb.Gps.seguir=(a)=>o({...a,onLectura:(l)=>{console.log('LEC '+l.lat.toFixed(5)+' '+l.precision.toFixed(1));a.onLectura(l)},onAviso:(e)=>{console.log('AVISO '+JSON.stringify(e));a.onAviso&&a.onAviso(e)}})})"); page.on('console', lambda m: print('  ', m.text[:100]) if m.text.startswith(('LEC','AVISO')) else None)
        page.on('pageerror', lambda e: errs.append(str(e)))
        # 1) enlace compartido: abre y busca solo
        await page.goto(URL + '?a=4.143200,-73.602000&b=4.154800,-73.583400&an=Origen%20prueba&bn=Destino%20prueba', wait_until='domcontentloaded')
        await page.wait_for_timeout(9000)
        check('enlace compartido: pone A y B', await page.input_value('#tripOrigin') == 'Origen prueba' and await page.input_value('#tripDestination') == 'Destino prueba')
        check('enlace compartido: busca solo', await page.locator('#tripResults .trip-option').count() > 0)
        check('enlace compartido: se limpian A y B de la dirección', not __import__('re').search(r'[?&][ab]=', page.url), page.url)
        check('aparece la barra de herramientas', await page.evaluate("!document.getElementById('tripTools').hidden"))
        # 2) generar enlace
        await page.evaluate("window.__c = null; navigator.share = undefined; navigator.clipboard.writeText = async (t) => { window.__c = t }")
        await page.evaluate("document.getElementById('shareTrip').click()"); await page.wait_for_timeout(500)
        link = await page.evaluate("window.__c")
        check('compartir genera un enlace con A y B', bool(link) and 'a=4.143200%2C-73.602000' in link and 'b=4.154800' in link, str(link))
        # 3) favoritos
        await page.evaluate("document.getElementById('favorites').open = true")
        await page.fill('#favName', 'Casa'); await page.evaluate("document.getElementById('favSaveA').click()"); await page.wait_for_timeout(300)
        await page.fill('#favName', 'Uni'); await page.evaluate("document.getElementById('favSaveB').click()"); await page.wait_for_timeout(300)
        check('guarda 2 favoritos', await page.locator('.fav-row').count() == 2)
        await page.evaluate("document.querySelectorAll('.fav-row')[0].querySelectorAll('.fav-btn')[1].click()"); await page.wait_for_timeout(600)  # «Uni» (el más nuevo va primero) como destino
        check('favorito usado como punto', len(await page.input_value('#tripDestination')) > 0)
        await page.reload(wait_until='domcontentloaded'); await page.wait_for_timeout(2500)
        check('los favoritos sobreviven a recargar', await page.locator('.fav-row').count() == 2, await page.evaluate("localStorage.getItem('spevb-favoritos')"))
        # 4) avisarme al bajar (GPS simulado)
        await page.fill('#tripOrigin', '4.1432, -73.6020'); await page.keyboard.press('Enter'); await page.wait_for_timeout(1200)
        await page.fill('#tripDestination', '4.1548, -73.5834'); await page.keyboard.press('Enter'); await page.wait_for_timeout(1200)
        await page.evaluate("document.querySelector('#planTrip').click()"); await page.wait_for_timeout(6000)
        sel = await page.evaluate("Spevb.Planner.seleccion")
        check('hay una selección con paradas', bool(sel) and len(sel['paradas']) >= 1, json.dumps(sel)[:120] if sel else '')
        ultima = sel['paradas'][0]          # la siguiente parada donde bajarse (con transbordo, la primera)
        lat0, lng0 = ultima['lat'] + 0.008, ultima['lng']          # ≈ 890 m al norte de donde hay que bajarse
        await ctx.set_geolocation({'latitude': lat0, 'longitude': lng0, 'accuracy': 8}); await page.wait_for_timeout(800)
        await page.evaluate("document.getElementById('alightAlert').click()"); await page.wait_for_timeout(1500)
        check('«Avisarme» queda activo', await page.get_attribute('#alightAlert', 'aria-pressed') == 'true')
        async def caminar(hasta_lat, segundos=1.0, paso=0.00035):        # ≈ 39 m por segundo: por debajo del límite de «salto imposible»
            nonlocal lat0
            while abs(lat0 - hasta_lat) > 1e-9:
                lat0 = max(lat0 - paso, hasta_lat) if lat0 > hasta_lat else min(lat0 + paso, hasta_lat)
                await ctx.set_geolocation({'latitude': lat0, 'longitude': lng0, 'accuracy': 8}); await page.wait_for_timeout(int(segundos * 1000))
            for i in range(8):                                             # parado: el filtro de GPS converge a la posición real
                await ctx.set_geolocation({'latitude': lat0 + (i % 2) * 1e-7, 'longitude': lng0, 'accuracy': 6}); await page.wait_for_timeout(700)
        toasts = lambda: page.locator('#spevbToasts').inner_text()
        await caminar(ultima['lat'] + 0.0045)                              # ≈ 500 m
        t1 = await toasts(); check('avisa «Te acercas» a ~500 m', 'acercas' in t1 or 'Prepárate' in t1, t1[:90])
        await caminar(ultima['lat'] + 0.0012)                              # ≈ 130 m
        t2 = await toasts(); check('avisa «Prepárate» a ~130 m', 'Prepárate' in t2, t2[:90])
        await caminar(ultima['lat'])
        t3 = await toasts()
        check('avisa «Llegaste» (y se apaga si era la última parada)', 'Llegaste' in t3 and (len(sel['paradas']) > 1 or await page.get_attribute('#alightAlert', 'aria-pressed') == 'false'), t3[:90])
        # 5) reporte
        await page.evaluate("document.getElementById('reportProblem').click()"); await page.wait_for_timeout(300)
        check('abre el formulario de reporte', await page.evaluate("!document.getElementById('reportModal').hidden"))
        await page.select_option('#reportType', 'CAMINATA'); await page.fill('#reportMessage', 'Prueba automática: la caminata cruza un canal.')
        await page.evaluate("document.getElementById('reportSend').click()"); await page.wait_for_timeout(1500)
        check('el formulario se cierra tras enviar', await page.evaluate("document.getElementById('reportModal').hidden"))
        req = urllib.request.Request(URL + 'api/editor/reportes?estado=NUEVO', headers={'X-Editor-Token': TOKEN})
        lista = json.loads(urllib.request.urlopen(req).read())
        mio = [r for r in lista if 'Prueba automática' in r['mensaje']]
        check('el reporte llega al administrador con ruta y puntos', bool(mio) and mio[0]['ruta_codigo'] and mio[0]['origen_lat'] is not None, str(mio[:1])[:160])
        check('sin errores JS', not errs, str(errs))
        await ctx.close(); await b.close()
    print('\nRESULTADO:', sum(res), '/', len(res)); raise SystemExit(0 if all(res) else 1)
asyncio.run(main())
