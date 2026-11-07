#!/usr/bin/env python3
"""Joystick para mover la ubicación del emulador de Android EN TIEMPO REAL (como las apps que mueven el GPS en Pokémon Go), para demostrar el Modo viaje.

Abre una página en tu navegador con un joystick (ratón, dedo, teclado WASD / flechas o un control de Xbox/PlayStation), un mapa y botones de velocidad
(a pie, bus lento, bus). Cada segundo envía la posición al emulador con `adb emu geo fix`, así que la app de SEVB que corre dentro del teléfono virtual
«camina», «se sube a un bus» y «se baja» según lo que hagas con el joystick. Solo usa la biblioteca estándar de Python y escucha únicamente en tu
propio equipo (127.0.0.1).

Uso (PowerShell, en la raíz del proyecto; el emulador debe estar encendido):
    py scripts/joystick_gps.py --lat 4.121275 --lng -73.628899 --abrir
    py scripts/joystick_gps.py --lat 4.121275 --lng -73.628899 --ruta viaje.gpx --abrir     # dibuja el recorrido como guía
    py scripts/joystick_gps.py --prueba                                                      # sin emulador: solo muestra lo que enviaría
"""
import argparse
import json
import os
import re
import shutil
import subprocess
import sys
import threading
import webbrowser
import xml.etree.ElementTree as ET
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlparse

AQUI = os.path.dirname(os.path.abspath(__file__))
PAGINA = os.path.join(AQUI, 'joystick')
LEAFLET = os.path.normpath(os.path.join(AQUI, '..', 'src', 'main', 'resources', 'static', 'vendor', 'leaflet'))
TIPOS = {'.html': 'text/html; charset=utf-8', '.js': 'text/javascript; charset=utf-8', '.css': 'text/css; charset=utf-8', '.png': 'image/png', '.json': 'application/json'}


def adb_por_defecto():
    """`adb` del PATH o, si no está, el del SDK de Android Studio en su carpeta habitual de Windows."""
    en_path = shutil.which('adb')
    if en_path:
        return en_path
    local = os.environ.get('LOCALAPPDATA')
    if local:
        candidato = os.path.join(local, 'Android', 'Sdk', 'platform-tools', 'adb.exe')
        if os.path.isfile(candidato):
            return candidato
    return 'adb'


def leer_gpx(ruta):
    """Puntos [lat, lng] de un GPX (trkpt, rtept o wpt)."""
    raiz = ET.parse(ruta).getroot()
    return [[float(p.get('lat')), float(p.get('lon'))] for p in raiz.iter() if p.tag.rsplit('}', 1)[-1] in ('trkpt', 'rtept', 'wpt')]


def leer_gps_del_telefono(texto):
    """Última ubicación del proveedor «gps» según `adb shell dumpsys location` → (lat, lng, precision_m) o None."""
    m = re.search(r'last location=Location\[gps (-?\d+\.\d+),(-?\d+\.\d+) hAcc=([\d.]+)', texto)
    return (float(m.group(1)), float(m.group(2)), float(m.group(3))) if m else None


def comando_fix(adb, serial, lat, lng):
    """`adb emu geo fix` recibe la LONGITUD primero y luego la latitud. Se pasa como lista (nunca por un shell)."""
    return [adb] + (['-s', serial] if serial else []) + ['emu', 'geo', 'fix', f'{lng:.7f}', f'{lat:.7f}']


def coordenada_valida(lat, lng):
    return isinstance(lat, (int, float)) and isinstance(lng, (int, float)) and not isinstance(lat, bool) and not isinstance(lng, bool) \
        and -90 <= lat <= 90 and -180 <= lng <= 180


class Emisor:
    """Envía las posiciones al emulador de una en una (si llegan más rápido de lo que adb responde, las intermedias no importan)."""

    def __init__(self, adb, serial, prueba=False):
        self.adb, self.serial, self.prueba = adb, serial, prueba
        self.bloqueo = threading.Lock()
        self.ultimo = None
        self.enviados = 0
        self.fixes = []          # solo en modo prueba
        self.error = None

    def dispositivos(self):
        if self.prueba:
            return ['prueba']
        try:
            r = subprocess.run([self.adb, 'devices'], capture_output=True, text=True, timeout=8)
        except (OSError, subprocess.SubprocessError) as e:
            self.error = f'No se pudo ejecutar adb: {e}'
            return []
        return [l.split('\t')[0] for l in r.stdout.splitlines()[1:] if l.strip().endswith('device')]

    def telefono(self):
        """Lo que el sistema Android del emulador tiene como ubicación del GPS (comprobación independiente de lo que enviamos)."""
        if self.prueba:
            return {'lat': self.ultimo[0], 'lng': self.ultimo[1], 'precision': 5.0} if self.ultimo else None
        try:
            r = subprocess.run([self.adb] + (['-s', self.serial] if self.serial else []) + ['shell', 'dumpsys', 'location'], capture_output=True, text=True, timeout=10, errors='replace')
        except (OSError, subprocess.SubprocessError):
            return None
        g = leer_gps_del_telefono(r.stdout)
        return {'lat': g[0], 'lng': g[1], 'precision': g[2]} if g else None

    def enviar(self, lat, lng):
        if not coordenada_valida(lat, lng):
            return False, 'Coordenada inválida.'
        with self.bloqueo:
            if self.prueba:
                self.fixes.append([lat, lng])
            else:
                try:
                    r = subprocess.run(comando_fix(self.adb, self.serial, lat, lng), capture_output=True, text=True, timeout=8)
                except (OSError, subprocess.SubprocessError) as e:
                    self.error = f'adb falló: {e}'
                    return False, self.error
                if r.returncode != 0 or 'KO' in r.stdout:
                    self.error = (r.stderr or r.stdout).strip() or 'adb no pudo mover la ubicación.'
                    return False, self.error
            self.error = None
            self.ultimo = [lat, lng]
            self.enviados += 1
        return True, None


def crear_servidor(puerto, emisor, config, ruta_gpx=None):
    ruta = leer_gpx(ruta_gpx) if ruta_gpx else []

    class H(BaseHTTPRequestHandler):
        server_version = 'SEVB-joystick'

        def log_message(self, *a):
            pass

        def _enviar(self, codigo, cuerpo, tipo):
            data = cuerpo if isinstance(cuerpo, bytes) else cuerpo.encode('utf-8')
            self.send_response(codigo)
            self.send_header('Content-Type', tipo)
            self.send_header('Content-Length', str(len(data)))
            self.send_header('Cache-Control', 'no-store')
            self.send_header('X-Content-Type-Options', 'nosniff')
            self.end_headers()
            if self.command != 'HEAD':
                self.wfile.write(data)

        def _json(self, obj, codigo=200):
            self._enviar(codigo, json.dumps(obj), 'application/json')

        def _archivo(self, base, relativa):
            # Solo se sirven archivos que estén dentro de la carpeta indicada (nada de «..»).
            destino = os.path.normpath(os.path.join(base, relativa))
            if os.path.commonpath([base, destino]) != base or not os.path.isfile(destino):
                return self._enviar(404, 'No encontrado', 'text/plain; charset=utf-8')
            with open(destino, 'rb') as f:
                self._enviar(200, f.read(), TIPOS.get(os.path.splitext(destino)[1].lower(), 'application/octet-stream'))

        def do_GET(self):
            u = urlparse(self.path).path
            if u == '/api/estado':
                return self._json({'modo': 'prueba' if emisor.prueba else 'adb', 'dispositivos': emisor.dispositivos(), 'ultimo': emisor.ultimo,
                                   'enviados': emisor.enviados, 'error': emisor.error, 'adb': emisor.adb})
            if u == '/api/telefono':
                return self._json({'telefono': emisor.telefono(), 'enviado': emisor.ultimo})
            if u == '/api/config':
                return self._json(config)
            if u == '/api/ruta':
                return self._json(ruta)
            if u == '/api/fixes' and emisor.prueba:
                return self._json(emisor.fixes)
            if u.startswith('/vendor/leaflet/'):
                return self._archivo(LEAFLET, u[len('/vendor/leaflet/'):])
            self._archivo(PAGINA, 'index.html' if u in ('/', '') else u.lstrip('/'))

        def do_POST(self):
            if urlparse(self.path).path != '/api/fix':
                return self._json({'ok': False, 'error': 'No encontrado'}, 404)
            try:
                n = int(self.headers.get('Content-Length') or 0)
                cuerpo = json.loads(self.rfile.read(min(n, 4096)) or b'{}')
            except (ValueError, json.JSONDecodeError):
                return self._json({'ok': False, 'error': 'JSON inválido'}, 400)
            ok, error = emisor.enviar(cuerpo.get('lat'), cuerpo.get('lng'))
            self._json({'ok': ok, 'error': error}, 200 if ok else 400)

    return ThreadingHTTPServer(('127.0.0.1', puerto), H)


def principal(argv=None):
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument('--lat', type=float, default=4.1370, help='latitud donde empieza el teléfono (por defecto, centro de Villavicencio)')
    ap.add_argument('--lng', type=float, default=-73.6266, help='longitud donde empieza el teléfono')
    ap.add_argument('--ruta', help='GPX opcional (el de scripts/gpx_viaje.py) para dibujar el recorrido como guía')
    ap.add_argument('--puerto', type=int, default=8765)
    ap.add_argument('--intervalo', type=float, default=1.0, help='segundos entre posiciones enviadas (1 = como un GPS real)')
    ap.add_argument('--serial', help='emulador concreto, p. ej. emulator-5554 (si hay varios dispositivos)')
    ap.add_argument('--adb', default=adb_por_defecto())
    ap.add_argument('--prueba', action='store_true', help='no llama a adb: guarda las posiciones en memoria (para las pruebas automáticas)')
    ap.add_argument('--abrir', action='store_true', help='abre la página en tu navegador')
    a = ap.parse_args(argv)
    if hasattr(sys.stdout, 'reconfigure'):
        sys.stdout.reconfigure(encoding='utf-8')
    if not coordenada_valida(a.lat, a.lng):
        raise SystemExit('Coordenada inicial inválida.')
    emisor = Emisor(a.adb, a.serial, a.prueba)
    config = {'lat': a.lat, 'lng': a.lng, 'intervalo': a.intervalo}
    srv = crear_servidor(a.puerto, emisor, config, a.ruta)
    url = f'http://127.0.0.1:{a.puerto}/'
    disp = emisor.dispositivos()
    print(f'Joystick de GPS en {url}')
    print('Emulador:', ', '.join(disp) if disp else 'NO se ve ningún emulador encendido (enciéndelo y vuelve a abrir la página).')
    print('Detener: Ctrl+C')
    if a.abrir:
        webbrowser.open(url)
    try:
        srv.serve_forever()
    except KeyboardInterrupt:
        print('Detenido.')
    return 0


if __name__ == '__main__':
    sys.exit(principal())
