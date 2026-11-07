"""Prueba PARCIAL con el SDK REAL de Google Maps (necesita Internet). Solo se ejecuta con SEVB_E2E_GOOGLE_REAL=1.

Qué demuestra: que el SDK oficial se descarga y se inicializa bajo la CSP de producción del modo Google (sin violaciones al cargar el script,
las fuentes ni las imágenes), que NO se descarga nada de Leaflet/MapLibre/mapas anteriores, y que un problema real de clave se clasifica y se
muestra como estado recuperable (los códigos reales de error de Google llegan por consola / gm_authFailure).
Qué NO demuestra (necesita una clave y un Map ID reales del propietario): que el mapa se dibuje, que los marcadores avanzados funcionen, que los gestos,
la proyección, el modo noche o el rendimiento sean correctos. Para eso, el procedimiento manual de docs/GOOGLE-MAPS.md («Prueba con tu clave»).

Con una clave real de PRUEBA propia (restringida a localhost) pásala por SEVB_E2E_GOOGLE_KEY y SEVB_E2E_GOOGLE_MAP_ID: la prueba intentará además comprobar
que el mapa se crea. Esa clave solo vive en el entorno de tu terminal; nunca se escribe en archivos.
"""
import os
import pathlib
import subprocess
import sys
import time
import urllib.request

from playwright.sync_api import sync_playwright

RAIZ = pathlib.Path(__file__).resolve().parent.parent
sys.path.insert(0, str(RAIZ / 'e2e'))
import sevb_e2e as B  # noqa: E402

PUERTO = 18093
CLAVE_FALSA = 'AIzaSyCLAVE_DE_PRUEBA_INVALIDA_0000000'


def main():
    if os.environ.get('SEVB_E2E_GOOGLE_REAL') != '1':
        print('OMITIDA: define SEVB_E2E_GOOGLE_REAL=1 para probar con el SDK real de Google (necesita Internet).')
        return 0
    clave = os.environ.get('SEVB_E2E_GOOGLE_KEY') or CLAVE_FALSA
    map_id = os.environ.get('SEVB_E2E_GOOGLE_MAP_ID') or 'DEMO_MAP_ID'
    real = clave != CLAVE_FALSA
    entorno = {**os.environ, 'SEVB_STUB_MAPA': 'google', 'SEVB_STUB_CLAVE_NAVEGADOR': clave, 'SEVB_STUB_MAP_ID': map_id}
    pr = subprocess.Popen([sys.executable, '-I', str(RAIZ / 'e2e' / 'stub_server.py'), str(PUERTO)], cwd=RAIZ, env=entorno, stdin=subprocess.DEVNULL)
    base = f'http://127.0.0.1:{PUERTO}/'
    try:
        for _ in range(60):
            try:
                urllib.request.urlopen(base + 'api/estado', timeout=1)
                break
            except OSError:
                time.sleep(0.5)
        with sync_playwright() as p:
            nav = B.navegador(p)
            ctx = nav.new_context(viewport={'width': 1280, 'height': 860}, service_workers='block')
            ctx.add_init_script("window.__csp = []; document.addEventListener('securitypolicyviolation', (e) => window.__csp.push(e.violatedDirective + ' ' + e.blockedURI));")
            pag = ctx.new_page()
            peticiones, consola = [], []
            pag.on('request', lambda r: peticiones.append(r.url))
            pag.on('console', lambda m: consola.append(m.type + ': ' + m.text))
            pag.goto(base, wait_until='domcontentloaded')
            fin = time.time() + 25
            while time.time() < fin:
                if pag.evaluate("!!document.querySelector('.mapa-error') || !!(window.Spevb && Spevb.maps && Spevb.maps.map)"):
                    break
                pag.wait_for_timeout(300)
            pag.wait_for_timeout(2500)
            sdk = [u for u in peticiones if 'maps.googleapis.com/maps/api/js' in u]
            fallos = []
            if len(sdk) != 1:
                fallos.append(f'el SDK debe pedirse una vez: {len(sdk)}')
            if any('/vendor/leaflet' in u or '/vendor/maplibre' in u or 'openfreemap' in u or 'arcgisonline' in u for u in peticiones):
                fallos.append('se descargó Leaflet/MapLibre/mapas anteriores en modo Google')
            csp = pag.evaluate('window.__csp')
            if csp:
                fallos.append(f'violaciones de CSP con el SDK real: {csp}')
            panel = pag.evaluate("(document.querySelector('.mapa-error') || {}).innerText || ''")
            mapa = pag.evaluate('!!(window.Spevb && Spevb.maps && Spevb.maps.map)')
            print('Clave:', 'REAL de prueba' if real else 'inválida a propósito', '| mapa creado:', mapa, '| panel:', panel.replace('\n', ' / ')[:160])
            errores_google = [c for c in consola if 'Google Maps JavaScript API' in c or 'MapError' in c]
            print('Consola de Google:', [c[:140] for c in errores_google][:4])
            if real:
                if not mapa:
                    fallos.append('con una clave real el mapa debía crearse; panel: ' + panel)
            else:
                if mapa and not panel:
                    fallos.append('con una clave inválida debe verse el estado de error (no un mapa roto sin aviso)')
                if not panel:
                    fallos.append('no apareció el estado de error con una clave inválida')
                elif 'clave' not in panel.lower() and 'autorizado' not in panel.lower() and 'configurado' not in panel.lower():
                    fallos.append('el mensaje no explica el problema de clave: ' + panel)
            if pag.evaluate("document.querySelectorAll('script[data-mapa-sdk]').length") > 1:
                fallos.append('más de un script del SDK en el documento')
            nav.close()
        print('RESULTADO real-parcial:', 'correcto' if not fallos else 'con fallos')
        for f in fallos:
            print('  -', f)
        return 1 if fallos else 0
    finally:
        pr.terminate()


if __name__ == '__main__':
    sys.exit(main())
