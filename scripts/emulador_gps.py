#!/usr/bin/env python3
"""Mueve la ubicación de un emulador de Android punto por punto con `adb emu geo fix`, siguiendo un archivo GPX (el de scripts/gpx_viaje.py).

Es la alternativa por línea de comandos al panel «Extended controls › Location › Routes» del emulador. Permite acelerar la reproducción
(--velocidad 2 hace que 1 segundo de viaje dure 0,5 s reales; ojo: también duplica las velocidades que verá la app) y repetir el viaje en bucle.

    py scripts/emulador_gps.py viaje.gpx                       # 1x: tiempo real
    py scripts/emulador_gps.py viaje.gpx --velocidad 1.5       # un poco más rápido (más de 1,5x distorsiona las velocidades)
    py scripts/emulador_gps.py viaje.gpx --serial emulator-5554 --adb "<carpeta del SDK>/platform-tools/adb.exe"

Requisitos: emulador de Android Studio encendido y `adb` (platform-tools) en el PATH o indicado con --adb.
"""
import argparse
import shutil
import subprocess
import sys
import time
import xml.etree.ElementTree as ET


def leer_gpx(ruta):
    """Lista de (lat, lng) en el orden del archivo."""
    raiz = ET.parse(ruta).getroot()
    puntos = [(float(p.get('lat')), float(p.get('lon'))) for p in raiz.iter() if p.tag.endswith('trkpt') or p.tag.endswith('rtept') or p.tag.endswith('wpt')]
    if not puntos:
        raise SystemExit('El GPX no tiene puntos (trkpt/rtept/wpt).')
    return puntos


def fix(adb, serial, lat, lng):
    """Un punto de GPS. `adb emu geo fix` recibe LONGITUD primero y luego latitud."""
    cmd = [adb] + (['-s', serial] if serial else []) + ['emu', 'geo', 'fix', f'{lng:.7f}', f'{lat:.7f}']
    r = subprocess.run(cmd, capture_output=True, text=True, timeout=15)
    if r.returncode != 0 or 'KO' in r.stdout:
        raise SystemExit('adb no pudo mover la ubicación: ' + (r.stderr or r.stdout).strip())


def principal(argv=None):
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument('gpx')
    ap.add_argument('--velocidad', type=float, default=1.0, help='factor de aceleración (1 = tiempo real)')
    ap.add_argument('--intervalo', type=float, default=1.0, help='segundos de viaje entre un punto y el siguiente (como se generó el GPX)')
    ap.add_argument('--serial', help='emulador concreto, p. ej. emulator-5554 (si hay varios dispositivos)')
    ap.add_argument('--adb', default=shutil.which('adb') or 'adb')
    ap.add_argument('--bucle', action='store_true', help='repetir el viaje hasta pulsar Ctrl+C')
    ap.add_argument('--prueba', action='store_true', help='no llama a adb: solo muestra lo que enviaría')
    a = ap.parse_args(argv)
    if hasattr(sys.stdout, 'reconfigure'):
        sys.stdout.reconfigure(encoding='utf-8')      # la consola de Windows no siempre admite «≈» y tildes
    puntos = leer_gpx(a.gpx)
    if a.velocidad > 1.5:
        print('AVISO: el Modo viaje mide velocidades reales; con más de 1,5x una persona que camina parece ir en bus. Úsalo solo para ver la interfaz.')
    pausa = max(0.0, a.intervalo / max(0.01, a.velocidad))
    print(f'{len(puntos)} puntos · ≈ {len(puntos) * a.intervalo / 60 / a.velocidad:.1f} min de reproducción · adb: {a.adb}')
    try:
        while True:
            for n, (lat, lng) in enumerate(puntos):
                if a.prueba:
                    if n % 60 == 0:
                        print(f'  [{n}] adb emu geo fix {lng:.7f} {lat:.7f}')
                else:
                    fix(a.adb, a.serial, lat, lng)
                    time.sleep(pausa)
            if not a.bucle:
                break
    except KeyboardInterrupt:
        print('Detenido.')
    return 0


if __name__ == '__main__':
    sys.exit(principal())
