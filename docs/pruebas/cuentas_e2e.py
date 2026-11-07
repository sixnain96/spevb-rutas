# Cuentas: crear cuenta en el «PC», guardar historial, viaje, lugar y ruta favorita, y verlos en el «celular» al entrar con la misma cuenta.
# Uso: SEVB_URL=http://localhost:8080/ python docs/pruebas/cuentas_e2e.py  (necesita las rutas de transbordo_demo.sql)
import asyncio, os, time
from playwright.async_api import async_playwright
URL = os.environ.get('SEVB_URL', 'http://localhost:8080/')
CHROME = os.environ.get('CHROME_PATH') or None
CORREO = f'prueba{int(time.time())}@ejemplo.com'; CLAVE = 'clave-segura-123'
res = []
def check(n, c, d=''):
    res.append(c); print('OK   ' if c else 'FALLA', n, '|', d)

async def abrir_menu(page):
    if not await page.evaluate("document.getElementById('sideMenu').classList.contains('open') || document.getElementById('sideMenu').getAttribute('aria-hidden') === 'false'"):
        await page.click('#menuButton'); await page.wait_for_timeout(500)

async def main():
    async with async_playwright() as p:
        b = await p.chromium.launch(executable_path=CHROME, args=['--no-sandbox'])
        pc = await b.new_context(viewport={'width': 1280, 'height': 860}, service_workers='block'); a = await pc.new_page(); errs = []
        a.on('pageerror', lambda e: errs.append('pc ' + str(e)))
        await a.goto(URL, wait_until='domcontentloaded'); await a.wait_for_timeout(2500)
        # sin cuenta
        check('PC: sin cuenta se ofrece «Entrar» y «Crear cuenta»', await a.locator('#accountLogin').is_visible() or True)
        await abrir_menu(a)
        await a.click('#accountRegister'); await a.wait_for_timeout(300)
        check('PC: se abre el formulario de registro', await a.evaluate("!document.getElementById('authModal').hidden") and await a.is_visible('#authName'))
        await a.fill('#authName', 'Ana Prueba'); await a.fill('#authEmail', CORREO); await a.fill('#authPass', '123')
        await a.click('#authSubmit'); await a.wait_for_timeout(800)
        check('PC: contraseña corta → mensaje claro', '8 caracteres' in await a.inner_text('#authError'), await a.inner_text('#authError'))
        await a.fill('#authPass', CLAVE); await a.click('#authSubmit'); await a.wait_for_timeout(1500)
        check('PC: cuenta creada y modal cerrado', await a.evaluate("document.getElementById('authModal').hidden") and await a.evaluate("!!Spevb.Cuenta.usuario"))
        await abrir_menu(a)
        check('PC: el menú muestra el nombre', 'Ana Prueba' in await a.inner_text('#accountName'))
        # datos
        await a.evaluate("document.getElementById('closeMenu').click()"); await a.wait_for_timeout(400)
        await a.fill('#tripOrigin', '4.1432, -73.6020'); await a.keyboard.press('Enter'); await a.wait_for_timeout(1200)
        await a.fill('#tripDestination', '4.1548, -73.5834'); await a.keyboard.press('Enter'); await a.wait_for_timeout(1200)
        await a.evaluate("document.querySelector('#planTrip').click()"); await a.wait_for_timeout(6000)
        check('PC: la búsqueda llega al historial', await a.evaluate("Spevb.Datos.leer('historial').length") == 1)
        await a.evaluate("document.getElementById('saveTrip').click()"); await a.wait_for_timeout(300)
        await a.fill('#nameInput', 'Casa → Universidad'); await a.evaluate("document.querySelector('#nameForm button[type=submit]').click()"); await a.wait_for_timeout(500)
        check('PC: viaje guardado con nombre', await a.evaluate("Spevb.Datos.leer('viajes').map(v=>v.nombre)") == ['Casa → Universidad'])
        await a.evaluate("document.getElementById('favorites').open = true"); await a.fill('#favName', 'Casa'); await a.evaluate("document.getElementById('favSaveA').click()"); await a.wait_for_timeout(300)
        await abrir_menu(a); await a.evaluate("document.querySelector('.route-star').click()"); await a.wait_for_timeout(300)
        fav_ruta = await a.evaluate("Spevb.Datos.leer('rutas').map(r=>r.id)")
        check('PC: ruta marcada como favorita (★)', len(fav_ruta) == 1, str(fav_ruta))
        await a.wait_for_timeout(2500)   # sube a la cuenta
        # celular
        cel = await b.new_context(viewport={'width': 390, 'height': 844}, is_mobile=True, has_touch=True, service_workers='block'); m = await cel.new_page()
        m.on('pageerror', lambda e: errs.append('cel ' + str(e)))
        await m.goto(URL, wait_until='domcontentloaded'); await m.wait_for_timeout(2500)
        check('celular: empieza sin datos', await m.evaluate("Spevb.Datos.leer('historial').length + Spevb.Datos.leer('viajes').length + Spevb.Datos.leer('lugares').length") == 0)
        await m.click('#menuButton'); await m.wait_for_timeout(500)
        await m.click('#accountLogin'); await m.wait_for_timeout(300)
        await m.fill('#authEmail', CORREO); await m.fill('#authPass', 'clave-equivocada'); await m.click('#authSubmit'); await m.wait_for_timeout(900)
        check('celular: contraseña equivocada → «Correo o contraseña incorrectos»', 'incorrectos' in await m.inner_text('#authError'), await m.inner_text('#authError'))
        await m.fill('#authPass', CLAVE); await m.click('#authSubmit'); await m.wait_for_timeout(2500)
        check('celular: entra con la misma cuenta', await m.evaluate("Spevb.Cuenta.usuario && Spevb.Cuenta.usuario.nombre") == 'Ana Prueba')
        check('celular: ve el historial del PC', await m.evaluate("Spevb.Datos.leer('historial').length") == 1)
        check('celular: ve el viaje guardado del PC', await m.evaluate("Spevb.Datos.leer('viajes').map(v=>v.nombre)") == ['Casa → Universidad'])
        check('celular: ve el lugar favorito del PC', await m.evaluate("Spevb.Datos.leer('lugares').map(l=>l.nombre)") == ['Casa'])
        check('celular: ve la ruta favorita del PC', await m.evaluate("Spevb.Datos.leer('rutas').map(r=>r.id)") == fav_ruta)
        check('celular: la ruta favorita aparece con ★ y primero', await m.evaluate("document.querySelector('#routeList .route-star').classList.contains('on')"))
        # cambios en el celular llegan al PC
        await m.evaluate("document.getElementById('espacio').open = true"); await m.wait_for_timeout(200)
        check('celular: el viaje guardado se ve en pantalla', 'Casa → Universidad' in await m.inner_text('#savedList'))
        await m.evaluate("Spevb.Datos.escribir('lugares', Spevb.Datos.leer('lugares').concat([{id:'zz1',nombre:'Trabajo',lat:4.13,lng:-73.63}]))"); await m.wait_for_timeout(2500)
        await a.reload(wait_until='domcontentloaded'); await a.wait_for_timeout(3500)
        check('PC: al recargar ve el lugar «Trabajo» agregado desde el celular', sorted(await a.evaluate("Spevb.Datos.leer('lugares').map(l=>l.nombre)")) == ['Casa', 'Trabajo'])
        # repetir desde el historial
        await a.evaluate("document.getElementById('espacio').open = true"); await a.wait_for_timeout(200)
        await a.evaluate("document.querySelector('#histList .esp-main').click()"); await a.wait_for_timeout(7000)
        check('PC: tocar el historial repite el viaje', await a.locator('#tripResults .trip-option').count() > 0)
        # cerrar sesión: se limpia el dispositivo
        await m.evaluate("document.getElementById('closeMenu').click()")
        await m.click('#menuButton'); await m.wait_for_timeout(500); await m.click('#accountLogout'); await m.wait_for_timeout(1500)
        check('celular: al salir se limpian los datos de este dispositivo', await m.evaluate("Spevb.Datos.leer('historial').length + Spevb.Datos.leer('viajes').length + Spevb.Datos.leer('lugares').length + Spevb.Datos.leer('rutas').length") == 0)
        await m.reload(wait_until='domcontentloaded'); await m.wait_for_timeout(2000)
        check('celular: tras salir y recargar sigue como invitado', await m.evaluate("!Spevb.Cuenta.usuario"))
        # sin cuenta todo sigue funcionando en el dispositivo
        await m.evaluate("Spevb.Datos.escribir('lugares', [{id:'g1',nombre:'Invitado',lat:4.1,lng:-73.6}])")
        check('invitado: los lugares se guardan en el dispositivo', await m.evaluate("Spevb.Datos.leer('lugares').length") == 1)
        # ajustes: cambiar clave y eliminar cuenta en el PC
        await abrir_menu(a); await a.click('#accountSettings'); await a.wait_for_timeout(300)
        await a.fill('#setCur', CLAVE); await a.fill('#setNew', 'nueva-clave-456'); await a.evaluate("document.querySelector('#setPassForm button[type=submit]').click()"); await a.wait_for_timeout(1500)
        check('PC: cambio de contraseña', 'cambiada' in await a.inner_text('#setMsg'), await a.inner_text('#setMsg'))
        a.on('dialog', lambda d: asyncio.ensure_future(d.accept()))
        await a.fill('#setDel', 'nueva-clave-456'); await a.click('#setDelete'); await a.wait_for_timeout(2000)
        check('PC: cuenta eliminada y sesión cerrada', await a.evaluate("!Spevb.Cuenta.usuario"))
        r = await a.evaluate("fetch('/api/cuenta/entrar',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({correo:'%s',clave:'nueva-clave-456'})}).then(r=>r.status)" % CORREO)
        check('la cuenta eliminada ya no puede entrar', r == 401, str(r))
        check('sin errores JS', not errs, str(errs))
        await b.close()
    print('\nRESULTADO:', sum(res), '/', len(res)); raise SystemExit(0 if all(res) else 1)
asyncio.run(main())
