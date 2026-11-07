#!/usr/bin/env python3
"""Genera un archivo GPX con un viaje A→B de SEVB «como lo haría una persona»: camina al punto de subida, espera, se sube al bus, va por el
recorrido real de la ruta, se baja y camina al destino. Una lectura por segundo, con velocidades realistas.

Sirve para probar el «Modo viaje» sin salir a la calle: se importa en el emulador de Android Studio (Extended controls › Location › Routes)
o se envía con scripts/emulador_gps.py. Solo usa la biblioteca estándar de Python.

Ejemplos (PowerShell, parado en la raíz del proyecto):
    py scripts/gpx_viaje.py --origen 4.1470,-73.6530 --destino 4.1450,-73.6240 --salida viaje.gpx
    py scripts/gpx_viaje.py --api http://localhost:8080 --origen 4.1470,-73.6530 --destino 4.1450,-73.6240 --opcion 1 --ruido 6 --salida viaje.gpx
    py scripts/gpx_viaje.py --origen 4.1433,-73.6020 --destino 4.1548,-73.5834 --transbordo --salida con_transbordo.gpx
"""
import argparse
import json
import math
import random
import sys
import urllib.request
from datetime import datetime, timedelta, timezone

RADIO = 6371000.0


def distancia(a, b):
    """Metros entre dos puntos (lat, lng)."""
    r = math.pi / 180
    d_lat, d_lng = (b[0] - a[0]) * r, (b[1] - a[1]) * r
    h = math.sin(d_lat / 2) ** 2 + math.cos(a[0] * r) * math.cos(b[0] * r) * math.sin(d_lng / 2) ** 2
    return 2 * RADIO * math.asin(math.sqrt(h))


def recorrer(puntos, velocidad, intervalo=1.0, perfil=None):
    """Sigue una polilínea [(lat, lng)] avanzando velocidad·intervalo metros por lectura. `perfil(m_recorridos, total)` puede variar la velocidad."""
    if len(puntos) < 2:
        return []
    total = sum(distancia(puntos[i], puntos[i + 1]) for i in range(len(puntos) - 1))
    salida, seg, pos, hecho = [], 0, puntos[0], 0.0
    while seg < len(puntos) - 1:
        v = perfil(hecho, total) if perfil else velocidad
        avance = max(0.05, v) * intervalo
        hecho += avance
        while avance > 0 and seg < len(puntos) - 1:
            resto = distancia(pos, puntos[seg + 1])
            if resto <= avance:
                avance -= resto
                pos = puntos[seg + 1]
                seg += 1
            else:
                f = avance / resto
                pos = (pos[0] + (puntos[seg + 1][0] - pos[0]) * f, pos[1] + (puntos[seg + 1][1] - pos[1]) * f)
                avance = 0
        salida.append(pos)
    return salida


def perfil_bus(v_bus):
    """Un bus arranca y frena de forma gradual (≈ 1 m/s²) y entre medias va a velocidad de crucero."""
    return lambda m, total: max(0.5, min(v_bus, 0.5 + math.sqrt(2 * 1.0 * m), 0.5 + math.sqrt(2 * 1.2 * max(0.0, total - m))))


def ll(coords):
    """GeoJSON [lng, lat] → (lat, lng)."""
    return [(c[1], c[0]) for c in coords]


def pedir_plan(api, origen, destino):
    cuerpo = json.dumps({'origen': {'lat': origen[0], 'lng': origen[1]}, 'destino': {'lat': destino[0], 'lng': destino[1]}, 'soloValidadas': False}).encode()
    req = urllib.request.Request(api.rstrip('/') + '/api/viajes/planificar', data=cuerpo, headers={'Content-Type': 'application/json'}, method='POST')
    with urllib.request.urlopen(req, timeout=30) as r:
        return json.load(r)


def elegir(respuesta, indice, transbordo):
    """Devuelve (buses, caminata_inicio, caminata_fin) con la misma estructura que usa la aplicación para el Modo viaje."""
    if transbordo:
        lista = respuesta.get('transbordos') or []
        if not lista:
            raise SystemExit('El servidor no devolvió viajes con transbordo para esos puntos.')
        t = lista[min(indice, len(lista) - 1)]
        return [t['primerBus'], t['segundoBus']], (t.get('caminataInicio') or {}).get('coordinates'), (t.get('caminataFin') or {}).get('coordinates')
    lista = (respuesta.get('opciones') or []) + (respuesta.get('alternativas') or [])
    if not lista:
        raise SystemExit('El servidor no devolvió rutas directas para esos puntos (prueba con --transbordo).')
    o = lista[min(indice, len(lista) - 1)]
    return [o], (o.get('caminataInicioRuta') or {}).get('coordinates'), (o.get('caminataFinRuta') or {}).get('coordinates')


def generar(buses, origen, destino, camina_ini=None, camina_fin=None, v_camina=1.35, v_bus=7.0, espera=40, parada=15, espera_cambio=30, intervalo=1.0, ruido=0.0, semilla=1):
    """Lista de lecturas {lat, lng, t, fase} (t en segundos desde el inicio) del viaje completo."""
    rnd = random.Random(semilla)
    lecturas = []

    def agregar(puntos, fase):
        for p in puntos:
            lecturas.append({'lat': p[0], 'lng': p[1], 'fase': fase})

    def quieto(p, segundos, fase):
        for _ in range(int(segundos / intervalo)):
            lecturas.append({'lat': p[0], 'lng': p[1], 'fase': fase})

    pos = origen
    for i, b in enumerate(buses):
        subida = (b['puntoSubida']['coordinates'][1], b['puntoSubida']['coordinates'][0])
        bajada = (b['puntoBajada']['coordinates'][1], b['puntoBajada']['coordinates'][0])
        camino = ll(camina_ini) if (i == 0 and camina_ini) else [pos, subida]
        agregar(recorrer(camino, v_camina, intervalo), 'caminar' if i == 0 else 'camina_cambio')
        quieto(subida, espera if i == 0 else espera_cambio, 'espera')
        agregar(recorrer(ll(b['tramo']['coordinates']), v_bus, intervalo, perfil_bus(v_bus)), 'bus')
        quieto(bajada, parada, 'parada')
        pos = bajada
    final = ll(camina_fin) if camina_fin else [pos, destino]
    agregar(recorrer(final, v_camina, intervalo), 'camina_final')
    if ruido > 0:
        kx, ky = 111320 * math.cos(origen[0] * math.pi / 180), 110574
        for l in lecturas:
            l['lat'] += rnd.gauss(0, ruido) / ky
            l['lng'] += rnd.gauss(0, ruido) / kx
    for n, l in enumerate(lecturas):
        l['t'] = n * intervalo
    return lecturas


def a_gpx(lecturas, nombre='Viaje SEVB', inicio=None):
    inicio = inicio or datetime.now(timezone.utc).replace(microsecond=0)
    pts = []
    for l in lecturas:
        t = (inicio + timedelta(seconds=l['t'])).strftime('%Y-%m-%dT%H:%M:%SZ')
        pts.append(f'      <trkpt lat="{l["lat"]:.7f}" lon="{l["lng"]:.7f}"><time>{t}</time></trkpt>')
    return ('<?xml version="1.0" encoding="UTF-8"?>\n<gpx version="1.1" creator="SEVB gpx_viaje.py" xmlns="http://www.topografix.com/GPX/1/1">\n'
            f'  <metadata><name>{nombre}</name></metadata>\n  <trk><name>{nombre}</name><trkseg>\n' + '\n'.join(pts) + '\n  </trkseg></trk>\n</gpx>\n')


def resumen(lecturas):
    """Segundos de cada fase, para saber en qué momento del GPX ocurre cada cosa."""
    fases, ultimo = [], None
    for l in lecturas:
        if l['fase'] != ultimo:
            fases.append([l['fase'], l['t'], l['t']])
            ultimo = l['fase']
        fases[-1][2] = l['t']
    return [{'fase': f, 'desde_s': a, 'hasta_s': b} for f, a, b in fases]


def principal(argv=None):
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument('--api', default='http://localhost:8080', help='dirección del servidor SEVB (por defecto http://localhost:8080)')
    ap.add_argument('--origen', required=True, help='lat,lng del punto A')
    ap.add_argument('--destino', required=True, help='lat,lng del punto B')
    ap.add_argument('--opcion', type=int, default=0, help='qué opción usar (0 = la mejor; las alternativas en sentido contrario van después de las directas)')
    ap.add_argument('--transbordo', action='store_true', help='usar un viaje con transbordo (dos buses)')
    ap.add_argument('--camina', type=float, default=1.35, help='velocidad al caminar en m/s (1,35 ≈ 4,9 km/h)')
    ap.add_argument('--bus', type=float, default=7.0, help='velocidad de crucero del bus en m/s (7 ≈ 25 km/h)')
    ap.add_argument('--espera', type=float, default=40, help='segundos esperando el bus en el punto de subida')
    ap.add_argument('--parada', type=float, default=15, help='segundos quieto en la parada de bajada antes de caminar')
    ap.add_argument('--intervalo', type=float, default=1.0, help='segundos entre lecturas')
    ap.add_argument('--ruido', type=float, default=0.0, help='ruido del GPS en metros (0 = lecturas perfectas; 6-12 imita un teléfono real)')
    ap.add_argument('--salida', default='viaje.gpx')
    a = ap.parse_args(argv)
    if hasattr(sys.stdout, 'reconfigure'):
        sys.stdout.reconfigure(encoding='utf-8')
    par = lambda s: tuple(float(x) for x in s.split(','))
    origen, destino = par(a.origen), par(a.destino)
    respuesta = pedir_plan(a.api, origen, destino)
    buses, ini, fin = elegir(respuesta, a.opcion, a.transbordo)
    lecturas = generar(buses, origen, destino, ini, fin, a.camina, a.bus, a.espera, a.parada, intervalo=a.intervalo, ruido=a.ruido)
    with open(a.salida, 'w', encoding='utf-8', newline='\n') as f:
        f.write(a_gpx(lecturas, 'Viaje SEVB ' + '+'.join(b['codigo'] for b in buses)))
    dur = lecturas[-1]['t']
    print(f'GPX creado: {a.salida} · {len(lecturas)} puntos · {int(dur // 60)} min {int(dur % 60)} s · rutas {", ".join(b["codigo"] for b in buses)}')
    for f in resumen(lecturas):
        print(f'  {f["fase"]:<15} {int(f["desde_s"]):>5} s → {int(f["hasta_s"]):>5} s')
    return 0


if __name__ == '__main__':
    sys.exit(principal())
