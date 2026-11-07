"""Regresión de resultados: guarda (o compara) la respuesta COMPLETA de /api/viajes/planificar para pares fijos.
Uso:  python e2e/rendimiento/comparar_resultados.py guardar|comparar ARCHIVO [N=60] [semilla=123] [URL]
Una optimización de rendimiento no puede cambiar ni un metro de lo que devuelve: la comparación es de igualdad exacta."""
import json, math, random, sys, time, urllib.request
BASE = sys.argv[5] if len(sys.argv) > 5 else 'http://127.0.0.1:8080'
modo, archivo = sys.argv[1], sys.argv[2]
N = int(sys.argv[3]) if len(sys.argv) > 3 else 60
semilla = int(sys.argv[4]) if len(sys.argv) > 4 else 123
def get(r): return json.load(urllib.request.urlopen(BASE + r, timeout=120))
def plan(a, b):
    body = json.dumps({'origen': {'lat': a[0], 'lng': a[1]}, 'destino': {'lat': b[0], 'lng': b[1]}, 'soloValidadas': False}).encode()
    rq = urllib.request.Request(BASE + '/api/viajes/planificar', body, {'Content-Type': 'application/json'})
    return json.load(urllib.request.urlopen(rq, timeout=180))
random.seed(semilla)
pts = []
for r in get('/api/rutas'):
    c = get('/api/rutas/%d' % r['id'])['recorrido']['coordinates']; pts += [(p[1], p[0]) for p in c[::10]]
def mover(p):
    ang = random.random() * 2 * math.pi; d = random.uniform(30, 450)
    return (round(p[0] + d * math.cos(ang) / 110574, 6), round(p[1] + d * math.sin(ang) / 111320, 6))
pares = []
while len(pares) < N:
    a, b = random.sample(pts, 2)
    if math.hypot((a[0] - b[0]) * 110574, (a[1] - b[1]) * 111320) > 1200: pares.append((mover(a), mover(b)))
t0 = time.perf_counter(); res = [plan(a, b) for a, b in pares]; dt = time.perf_counter() - t0
print(f'{N} planes en {dt:.1f} s ({1000*dt/N:.0f} ms de media)')
if modo == 'guardar':
    json.dump({'pares': pares, 'res': res}, open(archivo, 'w'))
    print('guardado', archivo, '| opciones totales:', sum(len(r['opciones']) + len(r.get('transbordos', [])) for r in res))
else:
    ref = json.load(open(archivo))
    if [list(map(list, p)) for p in ref['pares']] != [list(map(list, p)) for p in pares]: print('los pares no coinciden: otra red de rutas'); sys.exit(2)
    distintos = [i for i, (x, y) in enumerate(zip(ref['res'], res)) if x != y]
    print('IDÉNTICOS' if not distintos else f'DIFERENCIAS en {len(distintos)} de {N}: {distintos[:10]}')
    sys.exit(1 if distintos else 0)
