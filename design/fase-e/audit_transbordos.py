"""Mide los transbordos que devuelve una instancia de la app sobre viajes fijos (semilla 11) y, para cada uno, aplica la regla nueva
(comparar el segundo bus contra bajarse y caminar por la red real) para saber cuántos se descartarían. Solo lectura.
Uso: python audit_e.py http://127.0.0.1:8080 [pares]"""
import json, math, random, sys, time, urllib.request, urllib.error, urllib.parse

BASE = sys.argv[1] if len(sys.argv) > 1 else "http://127.0.0.1:8080"
N = int(sys.argv[2]) if len(sys.argv) > 2 else 90
CAM, BUS, ESPERA = 80.0, 267.0, 8.0
MIN_UTIL, RAZ, AHORRO, PEN_T, PEN_P, PEN_KM = 900.0, 1100.0, 5.0, 3.0, 4.0, 6.0

def http(path, data=None):
    for intento in range(6):
        try:
            req = urllib.request.Request(BASE + path, json.dumps(data).encode() if data else None, {"Content-Type": "application/json"} if data else {})
            return json.load(urllib.request.urlopen(req, timeout=120))
        except urllib.error.HTTPError as e:
            if e.code in (429, 503):
                time.sleep(2 + intento * 2); continue
            return {"_http": e.code}
        except Exception as e:
            return {"_error": str(e)}
    return {"_http": 429}

def decidir(km2, cambio_m, fin_m, walk_m):
    if walk_m is None: return "SEGUNDO_BUS"
    t_cam = walk_m / CAM
    t_seg = cambio_m / CAM + ESPERA + km2 * 1000 / BUS + fin_m / CAM
    if km2 * 1000 < MIN_UTIL and walk_m <= RAZ: return "CAMINAR"
    costo_seg = t_seg + PEN_T + PEN_P
    costo_cam = t_cam + max(0, walk_m - RAZ) / 1000 * PEN_KM
    return "SEGUNDO_BUS" if (t_cam - t_seg >= AHORRO and costo_seg < costo_cam) else "CAMINAR"

rutas = http("/api/rutas")
geo = {r["id"]: http("/api/rutas/%d" % r["id"])["recorrido"]["coordinates"] for r in rutas}
random.seed(11)
allp = [(c[1], c[0]) for g in geo.values() for c in g]
jig = lambda p: (p[0] + random.uniform(-500, 500) / 111000, p[1] + random.uniform(-500, 500) / 111000)
pares, usados, fallos = 0, [], {}
filas, alternativas, tiempos = [], [], []
while pares < N:
    a, b = jig(random.choice(allp)), jig(random.choice(allp))
    if math.hypot(a[0] - b[0], a[1] - b[1]) * 111000 < 2500: continue
    pares += 1
    t0 = time.time()
    x = http("/api/viajes/planificar", {"origen": {"lat": a[0], "lng": a[1]}, "destino": {"lat": b[0], "lng": b[1]}, "maxCaminataMetros": 1000, "soloValidadas": False})
    tiempos.append(time.time() - t0)
    time.sleep(0.45)
    if "_http" in x or "_error" in x:
        k = str(x.get("_http") or x.get("_error"))[:40]; fallos[k] = fallos.get(k, 0) + 1; continue
    usados.append((a, b))
    for o in x["opciones"] + x["alternativas"]:
        if o.get("variante") in ("BAJAR_Y_CAMINAR", "SOLO_SEGUNDA_RUTA"):
            alternativas.append((o["variante"], o["codigo"], round(o["acercamientoInicioMetros"]), round(o["acercamientoFinMetros"]), round(o["recorridoKm"], 2)))
    for t in x["transbordos"]:
        out1 = t["primerBus"]["puntoBajada"]["coordinates"]
        q = urllib.parse.urlencode({"olat": out1[1], "olng": out1[0], "dlat": b[0], "dlng": b[1]})
        w = http("/api/caminata?" + q); time.sleep(0.2)
        walk_m = w.get("metros") if isinstance(w, dict) and "coordinates" in w else None
        filas.append(dict(km1=t["primerBus"]["recorridoKm"], km2=t["segundoBus"]["recorridoKm"], cambio=t["caminataCambio"]["metros"], fin=t["caminataFin"]["metros"],
                          walk=walk_m, minutos=t["minutos"], decision=decidir(t["segundoBus"]["recorridoKm"], t["caminataCambio"]["metros"], t["caminataFin"]["metros"], walk_m)))
print("instancia:", BASE)
print("viajes consultados: %d válidos de %d | fallos: %s | planificar medio %.2f s (máx %.2f s)" % (len(usados), pares, fallos or "ninguno", sum(tiempos) / len(tiempos), max(tiempos)))
print("transbordos devueltos:", len(filas))
if filas:
    k1 = sorted(f["km1"] for f in filas); k2 = sorted(f["km2"] for f in filas)
    print("km primer bus: mín %.2f mediana %.2f | km segundo bus: mín %.2f mediana %.2f" % (k1[0], k1[len(k1) // 2], k2[0], k2[len(k2) // 2]))
    print("segundo bus < 0,9 km: %d | < 1,5 km: %d | primer bus < 0,9 km: %d" % (sum(v < .9 for v in k2), sum(v < 1.5 for v in k2), sum(v < .9 for v in k1)))
    desc = [f for f in filas if f["decision"] == "CAMINAR"]
    print("con la regla nueva se descartarían (conviene bajarse y caminar): %d de %d" % (len(desc), len(filas)))
    for f in desc[:8]:
        print("   bus1 %.2f km · bus2 %.2f km · camina directo %s m · transbordo %.0f min" % (f["km1"], f["km2"], f["walk"] and round(f["walk"]), f["minutos"]))
    sinr = [f for f in filas if f["walk"] is None]
    print("sin camino a pie desde donde se baja el bus 1 (se conservan):", len(sinr))
print("alternativas «baja y camina / una sola ruta» en la respuesta:", len(alternativas), alternativas[:6])
