# Inicio de sesión con Google, de punta a punta SIN usar Google de verdad: el script firma sus propios tokens, sirve sus claves públicas
# (JWKS) en http://localhost:18999/certs y simula el botón de Google. El servidor debe arrancar así:
#   GOOGLE_CLIENT_ID=test-client.apps.googleusercontent.com GOOGLE_JWKS_URL=http://localhost:18999/certs  (+ lo habitual)
# Uso: SEVB_URL=http://localhost:8080/ python docs/pruebas/google_e2e.py   (si lo repites, espera ≈ 1 minuto: el servidor limita las descargas de claves)
import asyncio, base64, json, os, threading, time, urllib.request
from http.server import BaseHTTPRequestHandler, HTTPServer
from cryptography.hazmat.primitives.asymmetric import rsa, padding
from cryptography.hazmat.primitives import hashes
from playwright.async_api import async_playwright
URL = os.environ.get('SEVB_URL', 'http://localhost:8080/')
CHROME = os.environ.get('CHROME_PATH') or None
CLIENT = 'test-client.apps.googleusercontent.com'
KID = 'k' + str(int(time.time()))
clave = rsa.generate_private_key(public_exponent=65537, key_size=2048)
otra = rsa.generate_private_key(public_exponent=65537, key_size=2048)
b64 = lambda b: base64.urlsafe_b64encode(b).rstrip(b'=').decode()
num = lambda n: b64(n.to_bytes((n.bit_length() + 7) // 8, 'big'))
pub = clave.public_key().public_numbers()
JWKS = json.dumps({'keys': [{'kty': 'RSA', 'kid': KID, 'alg': 'RS256', 'use': 'sig', 'n': num(pub.n), 'e': num(pub.e)}]}).encode()
class H(BaseHTTPRequestHandler):
    def do_GET(self):
        self.send_response(200); self.send_header('Content-Type', 'application/json'); self.send_header('Cache-Control', 'public, max-age=3600'); self.end_headers(); self.wfile.write(JWKS)
    def log_message(self, *a): pass
srv = HTTPServer(('127.0.0.1', 18999), H); threading.Thread(target=srv.serve_forever, daemon=True).start()

def token(sub, correo, nombre='Persona Google', k=None, **extra):
    ahora = int(time.time())
    c = {'iss': 'https://accounts.google.com', 'aud': CLIENT, 'sub': sub, 'email': correo, 'email_verified': True, 'name': nombre, 'iat': ahora, 'exp': ahora + 3000}
    c.update(extra)
    cuerpo = b64(json.dumps({'alg': 'RS256', 'kid': KID}).encode()) + '.' + b64(json.dumps(c).encode())
    return cuerpo + '.' + b64((k or clave).sign(cuerpo.encode(), padding.PKCS1v15(), hashes.SHA256()))

GIS_FALSO = """
window.google = { accounts: { id: {
  initialize(o) { window.__gis = o; },
  renderButton(el, o) { const b = document.createElement('button'); b.type = 'button'; b.id = 'gisFalso'; b.textContent = 'Continuar con Google'; b.dataset.ancho = o.width;
    b.onclick = () => window.__gis.callback({ credential: window.__cred }); el.append(b); }
} } };"""
res = []
def check(n, c, d=''):
    res.append(c); print('OK   ' if c else 'FALLA', n, '|', d)
def post(ruta, cuerpo, cookie=None):
    h = {'Content-Type': 'application/json'}; 
    if cookie: h['Cookie'] = cookie
    r = urllib.request.Request(URL + 'api/cuenta/' + ruta, data=json.dumps(cuerpo).encode(), headers=h, method='POST')
    try:
        with urllib.request.urlopen(r) as x: return x.status, json.loads(x.read() or b'{}'), x.headers.get('Set-Cookie')
    except urllib.error.HTTPError as e: return e.code, json.loads(e.read() or b'{}'), None

async def main():
    sufijo = str(int(time.time()))
    async with async_playwright() as p:
        b = await p.chromium.launch(executable_path=CHROME, args=['--no-sandbox'])
        ctx = await b.new_context(viewport={'width': 390, 'height': 844}, is_mobile=True, has_touch=True, service_workers='block'); pg = await ctx.new_page(); errs = []
        pg.on('pageerror', lambda e: errs.append(str(e)))
        pedidos = []
        pg.on('request', lambda r: pedidos.append(r.url) if 'accounts.google.com' in r.url else None)
        await pg.route('https://accounts.google.com/gsi/client', lambda r: r.fulfill(status=200, content_type='text/javascript', body=GIS_FALSO))
        await pg.goto(URL, wait_until='domcontentloaded'); await pg.wait_for_timeout(2500)
        check('el script de Google NO se carga hasta abrir el formulario', len(pedidos) == 0, str(pedidos))
        cfg = await pg.evaluate("fetch('/api/cuenta/config').then(r=>r.json())")
        check('el servidor publica el Client ID', cfg.get('google') == CLIENT, str(cfg))
        await pg.click('#menuButton'); await pg.wait_for_timeout(500); await pg.click('#accountLogin'); await pg.wait_for_timeout(1200)
        check('aparece el botón de Google', await pg.locator('#googleBox').is_visible() and await pg.locator('#gisFalso').count() == 1)
        check('el botón se ajusta al ancho de la pantalla', 200 <= int(await pg.get_attribute('#gisFalso', 'data-ancho')) <= 340, await pg.get_attribute('#gisFalso', 'data-ancho'))
        # cuenta nueva con Google
        correo = f'ana{sufijo}@gmail.com'
        await pg.evaluate("t => window.__cred = t", token('sub' + sufijo, correo, 'Ana Google'))
        await pg.click('#gisFalso'); await pg.wait_for_timeout(2500)
        u = await pg.evaluate("Spevb.Cuenta.usuario")
        check('Google crea la cuenta y entra', u and u['correo'] == correo and u['nombre'] == 'Ana Google' and u['conGoogle'] and not u['tieneClave'], str(u))
        check('se cierra el formulario', await pg.evaluate("document.getElementById('authModal').hidden"))
        await pg.evaluate("Spevb.Datos.escribir('lugares',[{id:'g1',nombre:'Casa Google',lat:4.14,lng:-73.62}])"); await pg.wait_for_timeout(2000)
        await pg.evaluate("document.getElementById('closeMenu').click()")
        await pg.click('#menuButton'); await pg.wait_for_timeout(500)
        check('el menú indica «Google»', 'Google' in await pg.inner_text('#accountEmail'))
        await pg.click('#accountLogout'); await pg.wait_for_timeout(1500)
        check('al salir se limpia el dispositivo', await pg.evaluate("Spevb.Datos.leer('lugares').length") == 0)
        # volver a entrar con Google: misma cuenta y mismos datos
        await pg.click('#menuButton'); await pg.wait_for_timeout(500); await pg.click('#accountLogin'); await pg.wait_for_timeout(800)
        await pg.evaluate("t => window.__cred = t", token('sub' + sufijo, correo, 'Ana Google')); await pg.click('#gisFalso'); await pg.wait_for_timeout(2500)
        check('al volver con Google recupera sus datos', await pg.evaluate("Spevb.Datos.leer('lugares').map(l=>l.nombre)") == ['Casa Google'])
        # esa cuenta no tiene contraseña: no se puede entrar con clave
        estado, cuerpo, _ = post('entrar', {'correo': correo, 'clave': 'cualquier-clave-1'})
        check('cuenta solo-Google: la contraseña no sirve (mensaje genérico)', estado == 401 and 'incorrectos' in cuerpo.get('message', ''), str(cuerpo)[:80])
        # crear contraseña desde ajustes
        await pg.evaluate("document.getElementById('closeMenu').click()"); await pg.click('#menuButton'); await pg.wait_for_timeout(500); await pg.click('#accountSettings'); await pg.wait_for_timeout(300)
        check('ajustes: aviso de Google y sin campo de contraseña actual', await pg.locator('#setGoogle').is_visible() and not await pg.locator('#setCurRow').is_visible() and 'Crear' in await pg.inner_text('#setPassTitle'))
        await pg.fill('#setNew', 'mi-clave-nueva-9'); await pg.evaluate("document.querySelector('#setPassForm button[type=submit]').click()"); await pg.wait_for_timeout(1500)
        check('crea una contraseña', 'creada' in await pg.inner_text('#setMsg'), await pg.inner_text('#setMsg'))
        estado, _, _ = post('entrar', {'correo': correo, 'clave': 'mi-clave-nueva-9'})
        check('ahora también entra con correo y contraseña', estado == 200)
        # tokens malos
        malos = {'firma ajena': token('x1', 'x1@gmail.com', k=otra), 'otra aplicación': token('x2', 'x2@gmail.com', aud='otra.apps.googleusercontent.com'),
                 'vencido': token('x3', 'x3@gmail.com', exp=int(time.time()) - 3600, iat=int(time.time()) - 7200), 'correo sin verificar': token('x4', 'x4@gmail.com', email_verified=False),
                 'emisor falso': token('x5', 'x5@gmail.com', iss='https://evil.com')}
        for nombre, t in malos.items():
            estado, cuerpo, cookie = post('google', {'credential': t})
            check('rechaza token: ' + nombre, estado == 401 and cookie is None, f'{estado} {cuerpo.get("message", "")[:60]}')
        # secuestro previo: alguien registra con contraseña el correo de otra persona antes de que ella use Google
        victima = f'victima{sufijo}@gmail.com'
        estado, _, cookie = post('registro', {'nombre': 'Atacante', 'correo': victima, 'clave': 'clave-del-atacante-1'})
        sesion_atacante = (cookie or '').split(';')[0]
        check('(preparación) el atacante registró el correo de la víctima', estado == 200 and sesion_atacante.startswith('spevb_sesion'))
        estado, cuerpo, cookie = post('google', {'credential': token('subvictima' + sufijo, victima, 'Víctima Real')})
        check('la víctima entra con Google y se vincula a esa cuenta', estado == 200 and cuerpo['usuario']['conGoogle'] and not cuerpo['usuario']['tieneClave'] and cuerpo['usuario']['nombre'] == 'Víctima Real', str(cuerpo)[:100])
        estado, _, _ = post('entrar', {'correo': victima, 'clave': 'clave-del-atacante-1'})
        check('la contraseña del atacante deja de servir', estado == 401, str(estado))
        req = urllib.request.Request(URL + 'api/cuenta/yo', headers={'Cookie': sesion_atacante}); yo = json.loads(urllib.request.urlopen(req).read())
        check('la sesión abierta del atacante se cerró', yo.get('autenticado') is False, str(yo))
        # eliminar cuenta solo-Google con el correo
        await pg.evaluate("document.getElementById('settingsClose').click()")
        nueva = f'solo{sufijo}@gmail.com'
        _, cu, ck = post('google', {'credential': token('subsolo' + sufijo, nueva, 'Solo Google')})
        sesion = (ck or '').split(';')[0]
        estado, cuerpo, _ = post('eliminar', {'clave': 'otro@correo.com'}, sesion)
        check('eliminar cuenta Google: correo equivocado → rechazo', estado == 400, str(cuerpo)[:80])
        estado, _, _ = post('eliminar', {'clave': nueva}, sesion)
        check('eliminar cuenta Google escribiendo su correo', estado == 200)
        check('sin errores JS', not errs, str(errs))
        await b.close()
    print('\nRESULTADO:', sum(res), '/', len(res)); raise SystemExit(0 if all(res) else 1)
asyncio.run(main())
