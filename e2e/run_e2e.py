"""Ejecuta las pruebas de navegador (interfaz y service worker) SIN Docker.

Modo predeterminado (stub): arranca e2e/stub_server.py (frontend real + API simulada, datos deterministas, sin Internet, sin base de datos
y sin datos reales) y corre sevb_e2e.py y sw_e2e.py. Prueba el JavaScript, el service worker y las cabeceras CSP; NO prueba el backend Java.

Modo opcional con PostgreSQL/PostGIS LOCAL (integración): solo si defines TODAS estas variables:
    SEVB_E2E_DB_URL       jdbc:postgresql://127.0.0.1:5432/sevb_e2e      (el nombre de la base DEBE terminar en _e2e o _test)
    SEVB_E2E_DB_USER
    SEVB_E2E_DB_PASSWORD
Nunca se conecta a tu base principal, no crea ni destruye bases y no usa Docker: tú preparas una base dedicada de pruebas con PostGIS.
Arranca target/spevb-rutas-<versión>.jar con esa base (Flyway aplica las migraciones y DEMO_DATA=true carga las rutas demo en ESA base).

Uso:  python e2e/run_e2e.py [--sin-sw] [--filtro texto]
Requisitos: pip install playwright ; Edge o Chrome instalado.
"""
import argparse
import glob
import os
import pathlib
import re
import subprocess
import sys
import time
import urllib.request
from urllib.parse import urlparse

RAIZ = pathlib.Path(__file__).resolve().parent.parent
PUERTO_APP = 18080
BASE = f'http://127.0.0.1:{PUERTO_APP}/'
VARIABLES_DB = ('SEVB_E2E_DB_URL', 'SEVB_E2E_DB_USER', 'SEVB_E2E_DB_PASSWORD')


def esperar(fn, mensaje, segundos, proceso=None, registro=None):
    """Espera una condición; si el proceso hijo muere durante la espera falla de inmediato mostrando el final de su registro."""
    fin = time.time() + segundos
    while time.time() < fin:
        if proceso is not None and proceso.poll() is not None:
            cola = ''
            if registro and pathlib.Path(registro).exists():
                cola = '\n'.join(pathlib.Path(registro).read_text(encoding='utf-8', errors='replace').splitlines()[-25:])
            raise SystemExit(f'El proceso terminó durante el arranque (código {proceso.returncode}). Últimas líneas de {registro}:\n{cola}')
        try:
            if fn():
                return
        except Exception:  # noqa: BLE001
            pass
        time.sleep(1)
    raise SystemExit(f'Tiempo agotado: {mensaje}')


def base_dedicada(url):
    """Acepta solo URL JDBC cuya base termine en _e2e o _test; cualquier otra cosa detiene la ejecución."""
    m = re.fullmatch(r'jdbc:postgresql://([^/:?]+)(?::\d+)?/([^/?]+)(?:\?.*)?', url or '')
    if not m or not re.search(r'(_e2e|_test)$', m.group(2)):
        raise SystemExit('SEVB_E2E_DB_URL no parece una base dedicada de pruebas: el nombre de la base debe terminar en _e2e o _test. No se continúa.')
    if m.group(1) not in ('127.0.0.1', 'localhost', '::1'):
        raise SystemExit('SEVB_E2E_DB_URL debe apuntar a este equipo (127.0.0.1 o localhost).')


def lanzar(cmd, **kw):
    # stdin cerrado: evita que el hijo (Playwright/Java) se quede esperando entrada cuando el lanzador corre sin terminal.
    return subprocess.Popen(cmd, cwd=RAIZ, stdin=subprocess.DEVNULL, **kw)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--sin-sw', action='store_true')
    ap.add_argument('--filtro')
    args = ap.parse_args()

    try:      # un servidor viejo en el puerto (con estado de una corrida anterior) falsearía las pruebas: se exige el puerto libre
        urllib.request.urlopen(BASE + 'api/estado', timeout=2)
        raise SystemExit(f'El puerto {PUERTO_APP} ya está en uso (¿un servidor stub o la aplicación de una corrida anterior?). Ciérralo y vuelve a intentar.')
    except OSError:
        pass
    definidas = [v for v in VARIABLES_DB if os.environ.get(v)]
    modo_db = len(definidas) == len(VARIABLES_DB)
    if definidas and not modo_db:
        raise SystemExit('Modo PostGIS local incompleto: define las tres variables ' + ', '.join(VARIABLES_DB) + ' o ninguna.')
    app = None
    registro = None
    (RAIZ / 'target').mkdir(exist_ok=True)
    try:
        if modo_db:
            base_dedicada(os.environ['SEVB_E2E_DB_URL'])
            jars = [j for j in glob.glob(str(RAIZ / 'target' / 'spevb-rutas-*.jar'))]
            if not jars:
                raise SystemExit('Falta target/spevb-rutas-*.jar: ejecuta «mvn -DskipTests package».')
            entorno = {k: v for k, v in os.environ.items() if k not in ('DB_PASSWORD', 'EDITOR_TOKEN', 'ARCGIS_API_KEY', 'ADMIN_CORREOS', 'GOOGLE_CLIENT_ID')}
            entorno.update({
                'DB_URL': os.environ['SEVB_E2E_DB_URL'], 'DB_USER': os.environ['SEVB_E2E_DB_USER'], 'DB_PASSWORD': os.environ['SEVB_E2E_DB_PASSWORD'],
                'SERVER_PORT': str(PUERTO_APP), 'SERVER_ADDRESS': '127.0.0.1', 'EDITOR_ENABLED': 'false', 'DEMO_DATA': 'true',
                'DIRECCIONES_IMPORT_AL_ARRANCAR': 'false', 'OSM_IMPORT_AL_ARRANCAR': 'false', 'LIMITE_SOLICITUDES': 'false',
                'WALKING_URL': '', 'GEOCODER_URL': '', 'GEOCODER_REVERSE_URL': '', 'VILLAVO_CATASTRO_URL': '',
                'RED_PEATONAL_ARCHIVO': str(RAIZ / 'target' / 'e2e-red-peatonal.bin.gz'),
            })
            registro = RAIZ / 'target' / 'e2e-app.log'
            with open(registro, 'w', encoding='utf-8') as f:
                app = lanzar(['java', '-jar', sorted(jars)[-1]], env=entorno, stdout=f, stderr=subprocess.STDOUT)
                esperar(lambda: urllib.request.urlopen(BASE + 'api/estado', timeout=3).status == 200,
                        'la aplicación no respondió (ver target/e2e-app.log)', 240, app, registro)
            print('Modo PostGIS local (integración) sobre', urlparse(os.environ['SEVB_E2E_DB_URL'].replace('jdbc:', '')).path)
        else:
            app = lanzar([sys.executable, '-I', str(RAIZ / 'e2e' / 'stub_server.py'), str(PUERTO_APP)])
            esperar(lambda: urllib.request.urlopen(BASE + 'api/estado', timeout=3).status == 200, 'el servidor stub no respondió', 30, app)
            print('Modo stub (sin Docker, sin base de datos, sin Internet): frontend real + API simulada.')
        entorno_pruebas = {**os.environ, 'SEVB_E2E_URL': BASE}
        codigos = []
        cmd = [sys.executable, '-I', str(RAIZ / 'e2e' / 'sevb_e2e.py')] + ([args.filtro] if args.filtro else [])
        codigos.append(lanzar(cmd, env=entorno_pruebas).wait())
        codigos.append(lanzar([sys.executable, '-I', str(RAIZ / 'e2e' / 'resultados_e2e.py')] + ([args.filtro] if args.filtro else []), env=entorno_pruebas).wait())
        codigos.append(lanzar([sys.executable, '-I', str(RAIZ / 'e2e' / 'estados_e2e.py')] + ([args.filtro] if args.filtro else []), env=entorno_pruebas).wait())
        codigos.append(lanzar([sys.executable, '-I', str(RAIZ / 'e2e' / 'viaje_logica_e2e.py')] + ([args.filtro] if args.filtro else []), env=entorno_pruebas).wait())
        codigos.append(lanzar([sys.executable, '-I', str(RAIZ / 'e2e' / 'viaje_e2e.py')] + ([args.filtro] if args.filtro else []), env=entorno_pruebas).wait())
        codigos.append(lanzar([sys.executable, '-I', str(RAIZ / 'e2e' / 'movil_e2e.py')] + ([args.filtro] if args.filtro else []), env=entorno_pruebas).wait())
        codigos.append(lanzar([sys.executable, '-I', str(RAIZ / 'e2e' / 'continuidad414_e2e.py')] + ([args.filtro] if args.filtro else []), env=entorno_pruebas).wait())
        codigos.append(lanzar([sys.executable, '-I', str(RAIZ / 'e2e' / 'ux414_e2e.py')] + ([args.filtro] if args.filtro else []), env=entorno_pruebas).wait())
        codigos.append(lanzar([sys.executable, '-I', str(RAIZ / 'e2e' / 'gpx_e2e.py')], env=entorno_pruebas).wait())
        codigos.append(lanzar([sys.executable, '-I', str(RAIZ / 'e2e' / 'joystick_e2e.py')], env=entorno_pruebas).wait())
        codigos.append(lanzar([sys.executable, '-I', str(RAIZ / 'e2e' / 'puntos_e2e.py')] + ([args.filtro] if args.filtro else []), env=entorno_pruebas).wait())
        codigos.append(lanzar([sys.executable, '-I', str(RAIZ / 'e2e' / 'aprender_e2e.py')] + ([args.filtro] if args.filtro else []), env=entorno_pruebas).wait())
        codigos.append(lanzar([sys.executable, '-I', str(RAIZ / 'e2e' / 'mejoras414_e2e.py')] + ([args.filtro] if args.filtro else []), env=entorno_pruebas).wait())
        codigos.append(lanzar([sys.executable, '-I', str(RAIZ / 'e2e' / 'editor_e2e.py')] + ([args.filtro] if args.filtro else []), env=entorno_pruebas).wait())
        codigos.append(lanzar([sys.executable, '-I', str(RAIZ / 'e2e' / 'busqueda_e2e.py')] + ([args.filtro] if args.filtro else []), env=entorno_pruebas).wait())
        codigos.append(lanzar([sys.executable, '-I', str(RAIZ / 'e2e' / 'panel_gestos_e2e.py')] + ([args.filtro] if args.filtro else []), env=entorno_pruebas).wait())
        codigos.append(lanzar([sys.executable, '-I', str(RAIZ / 'e2e' / 'layout_panel_e2e.py')] + ([args.filtro] if args.filtro else []), env=entorno_pruebas).wait())
        codigos.append(lanzar([sys.executable, '-I', str(RAIZ / 'e2e' / 'mapa_adaptadores_e2e.py')] + ([args.filtro] if args.filtro else []), env=entorno_pruebas).wait())
        codigos.append(lanzar([sys.executable, '-I', str(RAIZ / 'e2e' / 'accesibilidad_e2e.py')] + ([args.filtro] if args.filtro else []), env=entorno_pruebas).wait())
        if not args.sin_sw:
            codigos.append(lanzar([sys.executable, '-I', str(RAIZ / 'e2e' / 'sw_e2e.py')], env=entorno_pruebas).wait())
        code = 0 if all(c == 0 for c in codigos) else 1
        print('RESULTADO E2E:', 'correcto' if code == 0 else 'con fallos', codigos)
        sys.exit(code)
    finally:
        if app is not None and app.poll() is None:
            app.terminate()
            try:
                app.wait(15)
            except subprocess.TimeoutExpired:
                app.kill()
        if modo_db:
            for tmp in (RAIZ / 'target' / 'e2e-red-peatonal.bin.gz',):
                tmp.unlink(missing_ok=True)


if __name__ == '__main__':
    main()
