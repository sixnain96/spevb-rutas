# v4.6: cuadro de cambio de bus + insignias por tramo, modo daltónico, controles del bus ocultables, administradores por Google
# y editor de paraderos. Requiere: rutas de transbordo_demo.sql, servidor con EDITOR_ENABLED=true y
#   ADMIN_CORREOS=admin@gmail.com GOOGLE_CLIENT_ID=test-client.apps.googleusercontent.com GOOGLE_JWKS_URL=http://localhost:18999/certs
# Uso: SEVB_URL=http://localhost:8080/ EDITOR_TOKEN=... python docs/pruebas/mejoras_e2e.py
import asyncio, base64, json, os, threading, time, urllib.request
from http.server import BaseHTTPRequestHandler, HTTPServer
from cryptography.hazmat.primitives.asymmetric import rsa, padding
from cryptography.hazmat.primitives import hashes
from playwright.async_api import async_playwright
URL = os.environ.get('SEVB_URL', 'http://localhost:8080/')
TOKEN = os.environ.get('EDITOR_TOKEN', '0123456789abcdef0123456789abcdef0123')
CHROME = os.environ.get('CHROME_PATH') or None
CLIENT = 'test-client.apps.googleusercontent.com'; KID = 'm' + str(int(time.time()))
clave = rsa.generate_private_key(public_exponent=65537, key_size=2048)
b64 = lambda b: base64.urlsafe_b64encode(b).rstrip(b'=').decode()
num = lambda n: b64(n.to_bytes((n.bit_length() + 7) // 8, 'big'))
pub = clave.public_key().public_numbers()
JWKS = json.dumps({'keys': [{'kty': 'RSA', 'kid': KID, 'alg': 'RS256', 'use': 'sig', 'n': num(pub.n), 'e': num(pub.e)}]}).encode()
class H(BaseHTTPRequestHandler):
    def do_GET(self):
        self.send_response(200); self.send_header('Content-Type', 'application/json'); self.send_header('Cache-Control', 'public, max-age=3600'); self.end_headers(); self.wfile.write(JWKS)
    def log_message(self, *a): pass
HTTPServer.allow_reuse_address = True
threading.Thread(target=HTTPServer(('127.0.0.1', 18999), H).serve_forever, daemon=True).start()
def token(sub, correo, nombre):
    ahora = int(time.time()); c = {'iss': 'https://accounts.google.com', 'aud': CLIENT, 'sub': sub, 'email': correo, 'email_verified': True, 'name': nombre, 'iat': ahora, 'exp': ahora + 3000}
    cuerpo = b64(json.dumps({'alg': 'RS256', 'kid': KID}).encode()) + '.' + b64(json.dumps(c).encode())
    return cuerpo + '.' + b64(clave.sign(cuerpo.encode(), padding.PKCS1v15(), hashes.SHA256()))
GIS = "window.google={accounts:{id:{initialize(o){window.__gis=o},renderButton(el,o){const b=document.createElement('button');b.id='gisFalso';b.type='button';b.textContent='Google';b.onclick=()=>window.__gis.callback({credential:window.__cred});el.append(b)}}}};"
res = []
def check(n, c, d=''):
    res.append(c); print('OK   ' if c else 'FALLA', n, '|', d)

async def loguear_google(pg, sub, correo, nombre):
    await pg.route('https://accounts.google.com/gsi/client', lambda r: r.fulfill(status=200, content_type='text/javascript', body=GIS))
    await pg.click('#menuButton'); await pg.wait_for_timeout(500); await pg.click('#accountLogin'); await pg.wait_for_timeout(1000)
    await pg.evaluate("t => window.__cred = t", token(sub, correo, nombre)); await pg.click('#gisFalso'); await pg.wait_for_timeout(2500)
    await pg.evaluate("document.getElementById('closeMenu').click()"); await pg.wait_for_timeout(400)

async def main():
    sufijo = str(int(time.time()))
    async with async_playwright() as p:
        b = await p.chromium.launch(executable_path=CHROME, args=['--no-sandbox'])
        errs = []
        # ---------------------------------------------------------------- celular: transbordo, daltónico, panel del bus
        cel = await b.new_context(viewport={'width': 390, 'height': 844}, is_mobile=True, has_touch=True, service_workers='block'); m = await cel.new_page()
        m.on('pageerror', lambda e: errs.append('cel ' + str(e)))
        await m.goto(URL, wait_until='domcontentloaded'); await m.wait_for_timeout(3000)
        check('celular: los controles del bus empiezan ocultos (más mapa)', await m.evaluate("document.querySelector('.bus-panel') === null || document.querySelector('.bus-panel').classList.contains('collapsed') || document.querySelector('.bus-panel').hidden"))
        await m.fill('#tripOrigin', '4.1432, -73.6020'); await m.keyboard.press('Enter'); await m.wait_for_timeout(1200)
        await m.fill('#tripDestination', '4.1548, -73.5834'); await m.keyboard.press('Enter'); await m.wait_for_timeout(1200)
        await m.evaluate("document.querySelector('#planTrip').click()"); await m.wait_for_timeout(7000)
        check('panel del bus oculto: solo el botón 🚌', await m.evaluate("(()=>{const p=document.querySelector('.bus-panel');return !!p && p.classList.contains('collapsed') && getComputedStyle(p.querySelector('.bus-row')).display==='none'})()"))
        check('transbordo: aparece el cuadro «Cambio de bus»', await m.locator('.swap-callout').count() == 1)
        txt = await m.locator('.swap-callout').inner_text()
        check('el cuadro dice de qué ruta a cuál', 'PRUEBA-CAT' in txt and 'PRUEBA-C18' in txt and 'CAMBIO' in txt.upper(), txt.replace('\n', ' | '))
        check('insignias ① y ② sobre los tramos', await m.locator('.leg-badge').count() == 2 and (await m.locator('.leg-badge b').all_inner_texts()) == ['1', '2'])
        check('rótulos «Sube aquí» y «Bájate aquí»', await m.locator('.tag-up').count() == 1 and await m.locator('.tag-down').count() == 1)
        colores = lambda: m.evaluate("Object.values(Spevb.maps.map._layers).filter(l=>l.options&&l.options.weight===8&&l._latlngs).map(l=>l.options.color+(l.options.dashArray?'/'+l.options.dashArray:''))")
        c1 = await colores(); check('colores normales: rojo y azul', '#e53935' in c1 and '#1e5fbf' in c1, str(c1))
        await m.click('#menuButton'); await m.wait_for_timeout(500)
        await m.evaluate("document.querySelector('.menu-view [data-ajuste=daltonico]').click()"); await m.wait_for_timeout(2500)
        check('modo daltónico activo', await m.evaluate("document.documentElement.classList.contains('daltonico')"))
        c2 = await colores(); check('daltónico: naranja y azul, el 2.º tramo discontinuo, y se redibuja solo', '#D55E00' in c2 and any(x.startswith('#0072B2/') for x in c2), str(c2))
        await m.reload(wait_until='domcontentloaded'); await m.wait_for_timeout(2500)
        check('el modo daltónico se recuerda al recargar', await m.evaluate("document.documentElement.classList.contains('daltonico')"))
        await m.click('#menuButton'); await m.wait_for_timeout(500)
        await m.evaluate("document.querySelector('.menu-view [data-ajuste=busVisible]').click()"); await m.wait_for_timeout(500)
        await m.evaluate("document.getElementById('closeMenu').click()")
        check('desde el menú se muestran los controles del bus', await m.evaluate("(()=>{const p=document.querySelector('.bus-panel');return !p || !p.classList.contains('collapsed')})()"))
        check('existe la guía de colores', await m.locator('#colorGuide').count() == 1)
        await cel.close()
        # ---------------------------------------------------------------- PC: el panel del bus se ve por defecto
        pcx = await b.new_context(viewport={'width': 1280, 'height': 800}, service_workers='block'); a = await pcx.new_page()
        a.on('pageerror', lambda e: errs.append('pc ' + str(e)))
        await a.goto(URL + '?ruta=1', wait_until='domcontentloaded'); await a.wait_for_timeout(4000)
        check('PC: los controles del bus se ven por defecto', await a.evaluate("(()=>{const p=document.querySelector('.bus-panel');return !!p && !p.classList.contains('collapsed')})()"))
        await a.evaluate("document.querySelector('.bus-min').click()"); await a.wait_for_timeout(300)
        check('PC: el botón – oculta los controles', await a.evaluate("document.querySelector('.bus-panel').classList.contains('collapsed')"))
        await a.evaluate("document.querySelector('.bus-min').click()"); await a.wait_for_timeout(300)
        check('PC: y vuelve a mostrarlos', await a.evaluate("!document.querySelector('.bus-panel').classList.contains('collapsed')"))
        # ---------------------------------------------------------------- administradores
        vis = lambda pg: pg.evaluate("[...document.querySelectorAll('.menu-links a.admin-only')].filter(e=>e.offsetParent!==null).map(e=>e.getAttribute('href'))")
        await a.click('#menuButton'); await a.wait_for_timeout(500)
        check('invitado: no ve los enlaces de administración', await vis(a) == [], str(await vis(a)))
        await a.evaluate("document.getElementById('closeMenu').click()")
        await loguear_google(a, 'subu' + sufijo, f'usuario{sufijo}@gmail.com', 'Usuario Común')
        await a.click('#menuButton'); await a.wait_for_timeout(500)
        check('usuario común (Google): tampoco', await vis(a) == [], str(await vis(a)))
        await a.evaluate("document.getElementById('closeMenu').click()"); await a.wait_for_timeout(300)
        estado = await a.evaluate("fetch('/api/editor/rutas/1/paraderos',{method:'PUT',headers:{'Content-Type':'application/json','X-Editor-Token':'sesion-de-administrador-activa-xxxxxxxxxxxxxxxxxxx'},body:JSON.stringify({paraderos:[]})}).then(r=>r.status)")
        check('usuario común no puede editar aunque conozca el truco', estado == 401, str(estado))
        await a.click('#menuButton'); await a.wait_for_timeout(500); await a.click('#accountLogout'); await a.wait_for_timeout(1500)
        await loguear_google(a, 'subadmin' + sufijo, 'admin@gmail.com', 'Administrador Real')
        await a.click('#menuButton'); await a.wait_for_timeout(500)
        check('administrador (Google + ADMIN_CORREOS): ve las 5 secciones', sorted(await vis(a)) == ['/editor.html', '/lugares.html', '/paraderos.html', '/pasos.html', '/reportes.html'], str(await vis(a)))
        # ---------------------------------------------------------------- editor de paraderos
        await a.goto(URL + 'editor.html', wait_until='domcontentloaded'); await a.wait_for_timeout(3000)
        check('admin: el editor no pide la clave', not await a.is_visible('#editorToken') and 'administrador' in (await a.inner_text('.admin-banner')).lower())
        await a.select_option('#editorRoute', label=[x for x in await a.locator('#editorRoute option').all_inner_texts() if x.startswith('R001')][0]); await a.wait_for_timeout(2500)
        check('la sección de paraderos está activa', await a.is_enabled('#stopAdd'))
        await a.evaluate("document.getElementById('stopAdd').click()"); await a.wait_for_timeout(300)
        puntos = await a.evaluate("(()=>{const m=SpevbEditor.map,pts=SpevbEditor.points();const q=[0.2,0.5,0.8].map(f=>{const i=Math.floor((pts.length-1)*f);const p=m.latLngToContainerPoint(pts[i]);return [p.x,p.y]});return q})()")
        caja = await a.locator('#editorMap').bounding_box()
        for x, y in puntos:
            await a.mouse.click(caja['x'] + x, caja['y'] + y); await a.wait_for_timeout(900)
        check('3 clics sobre la línea → 3 paraderos', await a.locator('#stopList .stop-row').count() == 3)
        # clic lejos de la línea: se rechaza
        lejos = await a.evaluate("""(() => { const m = SpevbEditor.map, sz = m.getSize(), pts = SpevbEditor.points().map((p) => m.latLngToContainerPoint(p));
            const esq = [[20, 20], [sz.x - 20, 20], [20, sz.y - 20], [sz.x - 20, sz.y - 20]];
            const d = (e) => Math.min(...pts.map((p) => Math.hypot(p.x - e[0], p.y - e[1])));
            return esq.sort((x, y) => d(y) - d(x))[0]; })()""")
        await a.mouse.click(caja['x'] + lejos[0], caja['y'] + lejos[1]); await a.wait_for_timeout(500)
        check('un clic lejos de la ruta se rechaza', await a.locator('#stopList .stop-row').count() == 3 and 'más cerca' in await a.inner_text('#stopStatus'), await a.inner_text('#stopStatus'))
        nombres = ['Terminal Porfía', 'Parque Principal', 'Centro Comercial']
        for i, n in enumerate(nombres):
            await a.fill(f'#stopList .stop-row:nth-child({i + 1}) input', n)
        await a.evaluate("document.getElementById('stopSave').click()"); await a.wait_for_timeout(2000)
        check('guardar sin escribir la clave (sesión de administrador)', 'Guardados 3' in await a.inner_text('#stopStatus'), await a.inner_text('#stopStatus'))
        api = json.loads(urllib.request.urlopen(URL + 'api/rutas/1').read())
        check('en la base: 3 paraderos con sus nombres, ordenados por posición', sorted(p['nombre'] for p in api['paraderos']) == sorted(nombres) and [p['secuencia'] for p in api['paraderos']] == [1, 2, 3], str([(p['secuencia'], p['nombre']) for p in api['paraderos']]))
        await a.evaluate("document.querySelector('#stopList .stop-row:nth-child(2) .danger').click()"); await a.evaluate("document.getElementById('stopSave').click()"); await a.wait_for_timeout(2000)
        api = json.loads(urllib.request.urlopen(URL + 'api/rutas/1').read())
        check('quitar uno y guardar → quedan 2', len(api['paraderos']) == 2, str(len(api['paraderos'])))
        await a.reload(wait_until='domcontentloaded'); await a.wait_for_timeout(3000)
        await a.select_option('#editorRoute', label=[x for x in await a.locator('#editorRoute option').all_inner_texts() if x.startswith('R001')][0]); await a.wait_for_timeout(2500)
        check('al reabrir la ruta se cargan sus paraderos', await a.locator('#stopList .stop-row').count() == 2)
        # explorador
        await a.goto(URL + '?ruta=1', wait_until='domcontentloaded'); await a.wait_for_timeout(4000)
        check('el explorador muestra los paraderos nuevos', await a.evaluate("!document.getElementById('stopsBlock').classList.contains('hidden')"))
        # limpieza: dejar R001 como estaba
        req = urllib.request.Request(URL + 'api/editor/rutas/1/paraderos', data=json.dumps({'paraderos': []}).encode(), method='PUT', headers={'Content-Type': 'application/json', 'X-Editor-Token': TOKEN}); urllib.request.urlopen(req)
        check('sin errores JS', not errs, str(errs))
        await b.close()
    print('\nRESULTADO:', sum(res), '/', len(res)); raise SystemExit(0 if all(res) else 1)
asyncio.run(main())
