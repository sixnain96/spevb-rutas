"""Humo de la API y de la seguridad (solo lectura y peticiones inválidas: no crea ni borra datos).
Uso: python humo_api.py [http://127.0.0.1:8081]"""
import json, sys, urllib.request, urllib.error, math

BASE = sys.argv[1] if len(sys.argv) > 1 else "http://127.0.0.1:8081"
res = []

def ok(nombre, cond, extra=""):
    res.append((nombre, bool(cond), str(extra)))

def http(metodo, ruta, cuerpo=None):
    datos = json.dumps(cuerpo).encode() if cuerpo is not None else None
    req = urllib.request.Request(BASE + ruta, datos, {"Content-Type": "application/json"} if datos else {}, method=metodo)
    try:
        r = urllib.request.urlopen(req, timeout=120)
        txt = r.read().decode("utf-8", "replace")
        return r.status, dict(r.headers), txt
    except urllib.error.HTTPError as e:
        return e.code, dict(e.headers), e.read().decode("utf-8", "replace")

def js(metodo, ruta, cuerpo=None):
    s, h, t = http(metodo, ruta, cuerpo)
    try: return s, json.loads(t)
    except Exception: return s, None

# --- páginas y recursos
for ruta in ["/", "/editor.html", "/paraderos.html", "/pasos.html", "/puentes.html", "/lugares.html", "/reportes.html", "/manifest.webmanifest", "/sw.js",
             "/css/motion.css", "/js/motion.js", "/img/bus/bus_32_base.webp", "/img/bus/bus_32_mask.webp", "/icons/icon-192.png"]:
    s, h, t = http("GET", ruta)
    ok("GET %s = 200" % ruta, s == 200, s)
s, h, t = http("GET", "/")
for clave in ["planTrip", "swapTrip", "useMyLocation", "locateMe", "shareTrip", "alightAlert", "saveTrip", "reportProblem", "colorGuide", "toggleRouteStops"]:
    ok("index.html tiene #%s" % clave, 'id="%s"' % clave in t)
ok("index.html: sin «Solo revisadas» ni casilla de paraderos", "Solo revisadas" not in t and 'id="showStops"' not in t)
# --- seguridad
for cab in ["Content-Security-Policy", "X-Content-Type-Options", "X-Frame-Options", "Referrer-Policy", "Permissions-Policy"]:
    ok("cabecera %s presente" % cab, cab.lower() in {k.lower() for k in h})
ok("CSP: sin 'unsafe-eval' ni comodines de script", "unsafe-eval" not in h.get("Content-Security-Policy", "") and "script-src 'self'" in h.get("Content-Security-Policy", ""))
for ruta in ["/api/editor/paraderos", "/api/editor/pasos"]:
    s, _, _ = http("GET", ruta)
    ok("GET %s sin credenciales no entra (401/403)" % ruta, s in (401, 403), s)
s, _, _ = http("POST", "/api/editor/pasos", {"tipo": "PUENTE", "nombre": "x", "coordenadas": [[-73.6, 4.1], [-73.601, 4.1]]})
ok("POST /api/editor/pasos sin credenciales no entra", s in (401, 403), s)
s, _, _ = http("GET", "/api/..%2f..%2fetc/passwd")
ok("ruta con %2f rechazada", s == 400, s)
# --- rutas
s, rutas = js("GET", "/api/rutas")
ok("GET /api/rutas: 10 rutas activas", s == 200 and len(rutas) == 10 and all(r["activa"] for r in rutas), len(rutas or []))
ok("las rutas conservan los campos internos (validada, color, sentido)", all({"validada", "color", "sentido", "codigo"} <= set(r) for r in rutas))
geo = {}
for r in rutas:
    s, d = js("GET", "/api/rutas/%d" % r["id"])
    geo[r["id"]] = d["recorrido"]["coordinates"]
    ok("GET /api/rutas/%d trae recorrido, paraderos, advertencias y revisión interna" % r["id"], s == 200 and {"recorrido", "paraderos", "advertencias", "validada", "fuente", "notaValidacion"} <= set(d), s)
s, c = js("GET", "/api/rutas/cercanas?lat=4.0716&lng=-73.6705&soloValidadas=false")
ok("GET /api/rutas/cercanas (soloValidadas=false) devuelve 8", s == 200 and len(c) == 8, len(c or []))
s, c2 = js("GET", "/api/rutas/cercanas?lat=4.0716&lng=-73.6705&soloValidadas=true")
ok("el filtro interno soloValidadas=true sigue funcionando", s == 200 and len(c2) < len(c), len(c2 or []))
# --- planificación
def plan(a, b, **kw):
    cuerpo = {"origen": {"lat": a[0], "lng": a[1]}, "destino": {"lat": b[0], "lng": b[1]}, "maxCaminataMetros": 1000, "soloValidadas": False}
    cuerpo.update(kw)
    return js("POST", "/api/viajes/planificar", cuerpo)
A, B = (4.0716, -73.6705), (4.1560, -73.6630)
s, x = plan(A, B)
ok("planificar: claves opciones/alternativas/transbordos/alcance", s == 200 and {"opciones", "alternativas", "transbordos", "alcance"} <= set(x), s)
o = x["opciones"][0]
ok("la opción conserva todos los campos del contrato", {"rutaId", "codigo", "puntoSubida", "puntoBajada", "tramo", "caminataInicioRuta", "variante", "validada", "advertencia"} <= set(o))
ok("rutas directas devueltas", len(x["opciones"]) >= 3, len(x["opciones"]))
s2, xt = plan((4.1405863, -73.5952789), (4.1274090, -73.6357916))      # viaje con transbordos (R008 entre otras)
ok("transbordos devueltos con sus tres caminatas", s2 == 200 and len(xt["transbordos"]) >= 1 and all({"caminataInicio", "caminataCambio", "caminataFin", "pasajes"} <= set(t) for t in xt["transbordos"]), len(xt["transbordos"]))
ok("las alternativas «baja y camina / una sola ruta» llevan sus caminatas y su mensaje", all(o2.get("caminataInicioRuta") and "aproximadamente" in o2["advertencia"] for o2 in xt["opciones"] if o2.get("variante") in ("BAJAR_Y_CAMINAR", "SOLO_SEGUNDA_RUTA")))
ok("rutas sin validar participan (validada=false presente)", any(not o2["validada"] for o2 in x["opciones"]))
# sentido contrario: del final al inicio de la ruta 1
r1 = geo[rutas[0]["id"]]
s, y = plan((r1[-5][1], r1[-5][0]), (r1[5][1], r1[5][0]))
ok("viaje en sentido contrario: aparece en opciones o alternativas", s == 200 and (len(y["alternativas"]) >= 1 or len(y["opciones"]) >= 1), "alternativas=%d" % len(y["alternativas"]))
# validación de datos
for nombre, cuerpo in [("latitud 91", {"origen": {"lat": 91, "lng": 0}, "destino": {"lat": 4.1, "lng": -73.6}}),
                       ("fuera de Villavicencio", {"origen": {"lat": 4.7, "lng": -74.1}, "destino": {"lat": 4.1, "lng": -73.6}}),
                       ("caminata máxima 9999", {"origen": {"lat": 4.1, "lng": -73.6}, "destino": {"lat": 4.2, "lng": -73.6}, "maxCaminataMetros": 9999}),
                       ("sin destino", {"origen": {"lat": 4.1, "lng": -73.6}})]:
    s, _ = js("POST", "/api/viajes/planificar", cuerpo)
    ok("planificar rechaza: %s (400)" % nombre, s == 400, s)
s, _ = js("POST", "/api/viajes/planificar", {})
ok("planificar con cuerpo vacío: 400", s == 400, s)
# --- caminata, paraderos, puentes
s, e = js("GET", "/api/caminata/estado")
ok("red peatonal LISTA", s == 200 and e["estado"] == "LISTA", e)
s, w = js("GET", "/api/caminata?olat=4.1430&olng=-73.6270&dlat=4.1445&dlng=-73.6240")
ok("GET /api/caminata devuelve trazado y metros", s == 200 and len(w["coordinates"]) > 2 and w["metros"] > 0, s)
s, p = js("GET", "/api/paraderos?sur=4.05&oeste=-73.70&norte=4.20&este=-73.55&limite=300")
ok("GET /api/paraderos: 26 paraderos con id, nombre, lat, lng", s == 200 and len(p) == 26 and {"id", "nombre", "lat", "lng"} <= set(p[0]), len(p or []))
s, pu = js("GET", "/api/puentes")
ok("GET /api/puentes: 8 puentes con trazo", s == 200 and len(pu) == 8 and all("trazo" in q for q in pu), len(pu or []))
# --- cuenta y reportes (solo respuestas, sin crear nada)
s, _ = js("GET", "/api/cuenta/yo")
ok("GET /api/cuenta/yo anónimo responde", s == 200, s)
s, _ = js("POST", "/api/reportes", {"mensaje": ""})
ok("POST /api/reportes inválido: 400 (no crea nada)", s == 400, s)

fallos = [r for r in res if not r[1]]
print("humo API: %d comprobaciones, %d correctas, %d fallos" % (len(res), len(res) - len(fallos), len(fallos)))
for n, c, x in fallos: print("  FALLA:", n, "->", x)
sys.exit(1 if fallos else 0)
