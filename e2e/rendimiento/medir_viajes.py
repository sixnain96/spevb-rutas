"""Uso: python e2e/rendimiento/medir_viajes.py [URL] [pares=30] [semilla=5]
Mide la latencia REAL del cálculo de viajes (POST /api/viajes/planificar) con pares aleatorios de la ciudad. Solo lectura sobre la base.
Para que cada medición sea en frío se mueven los puntos unos metros (la caché de planes guarda 90 s por coordenadas exactas)."""
import json, math, random, statistics, sys, time, urllib.request
BASE = sys.argv[1] if len(sys.argv) > 1 else 'http://127.0.0.1:8080'   # 127.0.0.1 y NO «localhost»: en Windows «localhost» prueba IPv6 primero y suma ≈ 2 s a CADA solicitud
N = int(sys.argv[2]) if len(sys.argv) > 2 else 30
def get(r): return json.load(urllib.request.urlopen(BASE + r, timeout=120))
def plan(a, b):
    body = json.dumps({'origen': {'lat': a[0], 'lng': a[1]}, 'destino': {'lat': b[0], 'lng': b[1]}, 'soloValidadas': False}).encode()
    rq = urllib.request.Request(BASE + '/api/viajes/planificar', body, {'Content-Type': 'application/json'})
    t = time.perf_counter(); r = json.load(urllib.request.urlopen(rq, timeout=180)); return (time.perf_counter() - t) * 1000, r
random.seed(int(sys.argv[3]) if len(sys.argv) > 3 else 5)
rutas = get('/api/rutas')
pts = []
for r in rutas:
    c = get('/api/rutas/%d' % r['id'])['recorrido']['coordinates']; pts += [(p[1], p[0]) for p in c[::10]]
def mover(p):
    ang = random.random() * 2 * math.pi; d = random.uniform(100, 400)
    return (p[0] + d * math.cos(ang) / 110574, p[1] + d * math.sin(ang) / 111320)
pares = []
while len(pares) < N:
    a, b = random.sample(pts, 2)
    if math.hypot((a[0] - b[0]) * 110574, (a[1] - b[1]) * 111320) > 2500: pares.append((mover(a), mover(b)))
plan(*pares[0])                                         # calentamiento de la JVM, de las cachés de mapas y del pool
frio, hits = [], []
for a, b in pares:
    t, r = plan(a, b); frio.append(t)
    t2, _ = plan(a, b); hits.append(t2)                  # repetido: sale de la caché de 90 s
def resumen(x): x = sorted(x); return f"media {statistics.mean(x):7.0f} ms | mediana {x[len(x)//2]:7.0f} | p90 {x[int(len(x)*.9)-1]:7.0f} | máx {x[-1]:7.0f}"
print(f'{N} viajes distintos (en frío, sin caché de planes):', resumen(frio))
print(f'los mismos repetidos (caché de planes):              ', resumen(hits))
print('direcciones: ', end='')
t = time.perf_counter(); json.load(urllib.request.urlopen(BASE + '/api/lugares/sugerencias?q=Unicentro', timeout=60)); print(f'sugerencias «Unicentro» {1000*(time.perf_counter()-t):.0f} ms', end=' | ')
t = time.perf_counter(); json.load(urllib.request.urlopen(BASE + '/api/rutas', timeout=60)); print(f'lista de rutas {1000*(time.perf_counter()-t):.0f} ms')
