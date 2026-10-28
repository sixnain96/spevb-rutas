# v4.7: paraderos de bus independientes de las rutas (administración, capa pública) y su uso en el planificador.
# Requiere: rutas de transbordo_demo.sql, servidor con EDITOR_ENABLED=true y
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
    def api(ruta, cuerpo=None, metodo=None, tok=True):
        h = {'Content-Type': 'application/json'}
        if tok: h['X-Editor-Token'] = TOKEN
        r = urllib.request.Request(URL + 'api' + ruta, data=json.dumps(cuerpo).encode() if cuerpo is not None else None, headers=h, method=metodo or ('POST' if cuerpo is not None else 'GET'))
        try:
            with urllib.request.urlopen(r) as x: return x.status, json.loads(x.read() or b'{}')
        except urllib.error.HTTPError as e: return e.code, json.loads(e.read() or b'{}')
    for p in api('/editor/paraderos?limite=500')[1]: api('/editor/paraderos/%d' % p['id'], metodo='DELETE')     # limpio de pruebas anteriores
    ruta = [r for r in api('/rutas', tok=False)[1] if r['codigo'] == 'R001'][0]
    coords = api('/rutas/%d' % ruta['id'], tok=False)[1]['recorrido']['coordinates']
    errs = []
    async with async_playwright() as p:
        b = await p.chromium.launch(executable_path=CHROME, args=['--no-sandbox'])
        ctx = await b.new_context(viewport={'width': 1280, 'height': 800}, service_workers='block'); a = await ctx.new_page()
        a.on('pageerror', lambda e: errs.append(str(e)))
        await a.goto(URL, wait_until='domcontentloaded'); await a.wait_for_timeout(2500)
        await loguear_google(a, 'subadm' + sufijo, 'admin@gmail.com', 'Administrador Real')
        await a.goto(URL + 'paraderos.html', wait_until='domcontentloaded'); await a.wait_for_timeout(3500)
        check('admin: la página de paraderos no pide clave', not await a.is_visible('#paToken'))
        # centrar en un tramo de R001 con zoom suficiente y agregar tres paraderos SIN crear ninguna ruta
        medio = coords[len(coords) // 2]
        a0_, a1_ = coords[len(coords) // 2], coords[len(coords) // 2 + 1]
        centro = [a0_[1] + (a1_[1] - a0_[1]) * 0.04, a0_[0] + (a1_[0] - a0_[0]) * 0.04]
        await a.evaluate("([lat, lng]) => Spevb.maps.paradasMap.setView([lat, lng], 17, {animate: false})", centro); await a.wait_for_timeout(1500)
        await a.evaluate("document.getElementById('paAgregar').click()"); await a.wait_for_timeout(200)
        caja = await a.locator('#paradasMap').bounding_box()
        i0 = len(coords) // 2
        a0, a1 = coords[i0], coords[i0 + 1]
        puntos_ruta = [[a0[1] + (a1[1] - a0[1]) * t, a0[0] + (a1[0] - a0[0]) * t] for t in (0.0, 0.04, 0.08)]       # sobre la línea de R001, ~40 m entre uno y otro
        pts = await a.evaluate("""(ps) => { const m = Spevb.maps.paradasMap; return ps.map(([la, lo]) => { const p = m.latLngToContainerPoint([la, lo]); return [p.x, p.y]; }); }""", puntos_ruta)
        nombres = ['Universidad Santo Tomás', 'Parque Los Libertadores', 'Terminal de transporte']
        for (x, y), n in zip(pts, nombres):
            await a.mouse.click(caja['x'] + x, caja['y'] + y); await a.wait_for_timeout(500)
            check('clic → formulario de paradero nuevo (' + n + ')', 'nuevo' in (await a.get_attribute('#paModo', 'class')))
            await a.fill('#paNombre', n); await a.press('#paNombre', 'Enter'); await a.wait_for_timeout(1500)
        lista = api('/editor/paraderos?limite=100')[1]
        check('3 paraderos creados sin tocar ninguna ruta', sorted(x['nombre'] for x in lista) == sorted(nombres), str([x['nombre'] for x in lista]))
        check('el mapa los muestra (3 a la vista)', '3' in await a.inner_text('#paConteo'), await a.inner_text('#paConteo'))
        # fuera de la ciudad / nombre corto: el servidor rechaza
        check('el servidor rechaza un paradero fuera de la ciudad', api('/editor/paraderos', {'nombre': 'Lejos', 'lat': 10, 'lng': 0})[0] == 400)
        check('el servidor rechaza un nombre vacío', api('/editor/paraderos', {'nombre': ' ', 'lat': 4.14, 'lng': -73.62})[0] == 400)
        check('sin clave ni sesión no se puede crear', api('/editor/paraderos', {'nombre': 'Intruso', 'lat': 4.14, 'lng': -73.62}, tok=False)[0] in (401, 403))
        # buscar, editar y eliminar
        await a.fill('#paBuscar', 'terminal'); await a.wait_for_timeout(1200)
        check('la búsqueda ignora tildes y mayúsculas', await a.locator('#paLista .pa-fila').count() == 1)
        await a.fill('#paBuscar', 'universidad'); await a.wait_for_timeout(1200)
        await a.locator('#paLista .pa-fila').first.click(); await a.wait_for_timeout(1500)
        check('tocar uno de la lista lo abre para editar', 'editar' in (await a.get_attribute('#paModo', 'class')) and await a.input_value('#paNombre') == 'Universidad Santo Tomás')
        check('muestra qué rutas sirve', 'R001' in await a.inner_text('#paRutas'), await a.inner_text('#paRutas'))
        await a.fill('#paNombre', 'Universidad Santo Tomás · Cra. 22'); await a.fill('#paNota', 'frente a la entrada principal'); await a.click('#paGuardar'); await a.wait_for_timeout(1500)
        uni = [x for x in api('/editor/paraderos?q=universidad')[1]][0]
        check('el cambio de nombre y nota se guarda', uni['nombre'].endswith('Cra. 22') and uni['nota'] == 'frente a la entrada principal', str(uni['nombre']))
        a.on('dialog', lambda d: asyncio.ensure_future(d.accept()))
        await a.fill('#paBuscar', 'parque'); await a.wait_for_timeout(1200); await a.locator('#paLista .pa-fila').first.click(); await a.wait_for_timeout(1500)
        await a.click('#paEliminar'); await a.wait_for_timeout(1500)
        check('eliminar un paradero', len(api('/editor/paraderos?limite=100')[1]) == 2)
        # importación CSV (dos veces: la segunda se omiten)
        csv = os.path.join(os.environ.get('TMPDIR', '/tmp'), 'paraderos_prueba.csv')
        open(csv, 'w', encoding='utf-8').write('nombre,lat,lng,nota\nCentro Comercial Viva,%f,%f,\nHospital Departamental,%f,%f,urgencias\nSin coordenadas,,,\n' % (medio[1] + 0.001, medio[0], medio[1] - 0.001, medio[0]))
        await a.set_input_files('#paArchivo', csv); await a.wait_for_timeout(2000)
        check('importar CSV: crea los 2 válidos', len(api('/editor/paraderos?limite=100')[1]) == 4 and 'Importados 2' in await a.inner_text('#paStatus'), await a.inner_text('#paStatus'))
        await a.set_input_files('#paArchivo', csv); await a.wait_for_timeout(2000)
        check('importar el mismo archivo otra vez: se omiten los repetidos', len(api('/editor/paraderos?limite=100')[1]) == 4 and 'ya existían' in await a.inner_text('#paStatus'), await a.inner_text('#paStatus'))
        # capa pública en el explorador
        e = await ctx.new_page(); e.on('pageerror', lambda x: errs.append(str(x)))
        await e.goto(URL, wait_until='domcontentloaded'); await e.wait_for_timeout(3000)
        await e.evaluate("([lat, lng]) => SpevbExplorer.map.setView([lat, lng], 16, {animate: false})", [medio[1], medio[0]]); await e.wait_for_timeout(2500)
        check('el explorador dibuja los paraderos en el mapa', await e.evaluate("Object.values(SpevbExplorer.map._layers).filter(l => l._radius === 6 && l.options.fillColor === '#1e5fbf').length") >= 3)
        await e.evaluate("document.getElementById('showStops').click()"); await e.wait_for_timeout(1200)
        check('el interruptor «Paraderos» los oculta', await e.evaluate("Object.values(SpevbExplorer.map._layers).filter(l => l._radius === 6 && l.options.fillColor === '#1e5fbf').length") == 0)
        # planificador: usa el paradero si está cerca de lo óptimo
        s, d = api('/viajes/planificar', {'origen': {'lat': puntos_ruta[0][0] + 0.00004, 'lng': puntos_ruta[0][1]}, 'destino': {'lat': coords[-1][1] + 0.0003, 'lng': coords[-1][0]}, 'soloValidadas': False}, tok=False)
        r1 = [o for o in d['opciones'] if o['codigo'] == 'R001' and o.get('variante') == 'MENOS_CAMINATA'][0]
        todos = {x['nombre'] for x in api('/editor/paraderos?limite=100')[1]}
        check('el planificador sube en un paradero cercano a la ruta', r1['paraderoSubida'] in todos, str(r1['paraderoSubida']))
        # al desactivarlo deja de usarse
        for x in api('/editor/paraderos?limite=100')[1]: api('/editor/paraderos/%d' % x['id'], {'nombre': x['nombre'], 'lat': x['lat'], 'lng': x['lng'], 'activo': False}, 'PUT')
        s, d = api('/viajes/planificar', {'origen': {'lat': puntos_ruta[0][0] + 0.00004, 'lng': puntos_ruta[0][1]}, 'destino': {'lat': coords[-1][1] + 0.0003, 'lng': coords[-1][0]}, 'soloValidadas': False}, tok=False)
        r2 = [o for o in d['opciones'] if o['codigo'] == 'R001' and o.get('variante') == 'MENOS_CAMINATA'][0]
        check('con los paraderos desactivados ya no se usan (sin esperar la caché)', r2['paraderoSubida'] is None, str(r2['paraderoSubida']))
        for x in api('/editor/paraderos?limite=100')[1]: api('/editor/paraderos/%d' % x['id'], metodo='DELETE')
        check('sin errores JS', not errs, str(errs))
        await b.close()
    print('\nRESULTADO:', sum(res), '/', len(res)); raise SystemExit(0 if all(res) else 1)
asyncio.run(main())
