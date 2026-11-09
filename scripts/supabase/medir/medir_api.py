"""Mide la API de SEVB contra una base remota: latencia (mediana y p95) y, si se indica el contenedor de la base, cuántas transacciones
(viajes de ida y vuelta, porque la aplicación usa autocommit) hace la base por petición.

Uso:  python medir_api.py URL_BASE [--contenedor NOMBRE] [--repeticiones 12] [--paralelo 16] [--etiqueta TEXTO] [--json archivo]
      python medir_api.py http://127.0.0.1:8080 --contenedor sevb-bench --etiqueta supabase-sim
Solo hace peticiones de LECTURA (GET y el POST de planificar un viaje, que no escribe).
"""
import argparse
import json
import statistics
import subprocess
import sys
import time
import urllib.request
from concurrent.futures import ThreadPoolExecutor

_n = [0]


def _jitter():
    _n[0] += 1
    return ((_n[0] * 37) % 100) / 100000.0          # hasta ~1 m de variacion: otro punto, misma zona


def _plan():
    j = _jitter()
    return {'origen': {'lat': 4.1420 + j, 'lng': -73.6266 - j}, 'destino': {'lat': 4.1500 - j, 'lng': -73.6350 + j}, 'soloValidadas': False}


def _cercanas():
    j = _jitter()
    return f'/api/rutas/cercanas?lat={4.1420 + j:.6f}&lng={-73.6266 - j:.6f}'


# (nombre, metodo, ruta o funcion que la genera, cuerpo o funcion que lo genera)
ESCENARIOS = [
    ('estado', 'GET', '/api/estado', None),
    ('rutas', 'GET', '/api/rutas', None),
    ('rutas/nombres', 'GET', '/api/rutas/nombres', None),
    ('rutas/cercanas', 'GET', _cercanas, None),
    ('paraderos', 'GET', '/api/paraderos?sur=4.10&oeste=-73.68&norte=4.18&este=-73.58', None),
    ('viajes/planificar', 'POST', '/api/viajes/planificar', _plan),
]


def pedir(base, metodo, ruta, cuerpo=None):
    ruta = ruta() if callable(ruta) else ruta
    cuerpo = cuerpo() if callable(cuerpo) else cuerpo
    datos = json.dumps(cuerpo).encode() if cuerpo is not None else None
    req = urllib.request.Request(base + ruta, data=datos, method=metodo, headers={'Content-Type': 'application/json'} if datos else {})
    t0 = time.perf_counter()
    try:
        with urllib.request.urlopen(req, timeout=60) as r:
            n = len(r.read())
            return (time.perf_counter() - t0) * 1000, r.status, n
    except Exception as e:  # noqa: BLE001
        return (time.perf_counter() - t0) * 1000, getattr(e, 'code', 0), 0


def transacciones(contenedor):
    """Cuenta las sentencias SQL que ha ejecutado la base (necesita `postgres -c log_statement=all`); cada una es un viaje de ida y vuelta."""
    if not contenedor:
        return None
    r = subprocess.run(['docker', 'logs', contenedor], capture_output=True, text=True, encoding='utf-8', errors='replace')
    texto = (r.stdout or '') + (r.stderr or '')
    return sum(1 for linea in texto.splitlines() if 'LOG:  execute ' in linea or 'LOG:  statement: ' in linea)


def p95(xs):
    xs = sorted(xs)
    return xs[min(len(xs) - 1, int(round(0.95 * (len(xs) - 1))))]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('base')
    ap.add_argument('--contenedor')
    ap.add_argument('--repeticiones', type=int, default=12)
    ap.add_argument('--paralelo', type=int, default=16)
    ap.add_argument('--pausa', type=float, default=0.0, help='segundos entre peticiones seguidas (una persona real las hace con segundos de diferencia; sin pausa la conexion nunca queda ociosa)')
    ap.add_argument('--etiqueta', default='')
    ap.add_argument('--json')
    a = ap.parse_args()
    resultado = {'etiqueta': a.etiqueta, 'escenarios': {}}
    print(f'== {a.etiqueta or a.base}')
    primera, st, _ = pedir(a.base, 'GET', '/api/estado')
    print(f'primera peticion (pool frio): {primera:.0f} ms (HTTP {st})')
    resultado['primera_ms'] = round(primera)
    for nombre, metodo, ruta, cuerpo in ESCENARIOS:
        pedir(a.base, metodo, ruta, cuerpo)            # calentamiento
        t_antes = transacciones(a.contenedor)
        tiempos, estados = [], set()
        for _ in range(a.repeticiones):
            if a.pausa: time.sleep(a.pausa)
            ms, st, _n = pedir(a.base, metodo, ruta, cuerpo)
            tiempos.append(ms); estados.add(st)
        t_despues = transacciones(a.contenedor)
        tx = None if t_antes is None or t_despues is None else (t_despues - t_antes) / a.repeticiones
        resultado['escenarios'][nombre] = {'mediana_ms': round(statistics.median(tiempos)), 'p95_ms': round(p95(tiempos)), 'sentencias_por_peticion': None if tx is None else round(tx, 1), 'http': sorted(estados)}
        print(f'{nombre:20} mediana {statistics.median(tiempos):7.0f} ms   p95 {p95(tiempos):7.0f} ms   sentencias SQL/peticion {"-" if tx is None else f"{tx:.1f}"}   HTTP {sorted(estados)}')
    # concurrencia: muchas personas a la vez (planificar y cercanas)
    for nombre, metodo, ruta, cuerpo in [e for e in ESCENARIOS if e[0] in ('rutas/cercanas', 'viajes/planificar')]:
        total = a.paralelo * 4
        t0 = time.perf_counter()
        with ThreadPoolExecutor(max_workers=a.paralelo) as ex:
            res = list(ex.map(lambda _: pedir(a.base, metodo, ruta, cuerpo), range(total)))
        dur = time.perf_counter() - t0
        tiempos = [r[0] for r in res]
        fallos = sum(1 for r in res if r[1] != 200)
        resultado['escenarios'][f'{nombre} x{a.paralelo} paralelas'] = {'peticiones_por_s': round(total / dur, 1), 'mediana_ms': round(statistics.median(tiempos)), 'p95_ms': round(p95(tiempos)), 'fallos': fallos}
        print(f'{nombre:20} {a.paralelo} en paralelo: {total / dur:6.1f} peticiones/s   mediana {statistics.median(tiempos):6.0f} ms   p95 {p95(tiempos):6.0f} ms   fallos {fallos}')
    if a.json:
        with open(a.json, 'w', encoding='utf-8') as f:
            json.dump(resultado, f, indent=2, ensure_ascii=False)


if __name__ == '__main__':
    sys.exit(main())
