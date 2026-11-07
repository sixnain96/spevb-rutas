"""Servidor SUSTITUTO para ejecutar las pruebas de navegador SIN Docker/PostGIS.

Sirve los archivos reales del frontend (src/main/resources/static, con la versión de pom.xml ya sustituida en sw.js y manifest, y con
las mismas cabeceras de seguridad que EditorSecurityFilter) y responde la API con datos de ejemplo coherentes con los DTO reales
(rutas R001/R002 demostrativas de db/demo). NO prueba el backend Java: eso lo hacen `mvn verify` y `run_e2e.py` (con Docker).

Uso: python e2e/stub_server.py [puerto]     (por defecto 18080)
"""
import json
import os
import mimetypes
import pathlib
import re
import sys
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlparse

RAIZ = pathlib.Path(__file__).resolve().parent.parent
STATIC = RAIZ / 'src' / 'main' / 'resources' / 'static'
VERSION = re.search(r'<artifactId>spevb-rutas</artifactId>\s*<version>([^<]+)</version>', (RAIZ / 'pom.xml').read_text(encoding='utf-8')).group(1)

def _csp(proveedor):
    """Las mismas políticas que ContentSecurityPolicy.java: se leen de src/test/resources/csp (Java las compara con su código)."""
    return (RAIZ / 'src' / 'test' / 'resources' / 'csp' / (proveedor + '.txt')).read_text(encoding='utf-8')


# Proveedor de mapa del servidor simulado (como MAP_PROVIDER): SEVB_STUB_MAPA=leaflet|google. Con google se usan claves de PRUEBA (no reales).
PROVEEDOR_MAPA = os.environ.get('SEVB_STUB_MAPA', 'leaflet')
CLAVE_NAVEGADOR_STUB = os.environ.get('SEVB_STUB_CLAVE_NAVEGADOR', 'AIzaSyEJEMPLO_NO_ES_UNA_CLAVE_REAL_0123')
MAP_ID_STUB = os.environ.get('SEVB_STUB_MAP_ID', 'DEMO_MAP_ID')
CSP = _csp(PROVEEDOR_MAPA)


def config_mapa():
    """Misma forma que GET /api/config/mapa (MapaConfig.Publica): con Leaflet no se envía nada de Google."""
    if PROVEEDOR_MAPA != 'google':
        return {'proveedor': 'leaflet', 'google': None}
    ok = bool(CLAVE_NAVEGADOR_STUB) and bool(MAP_ID_STUB)
    return {'proveedor': 'google', 'google': {'configurado': ok, 'claveNavegador': CLAVE_NAVEGADOR_STUB or None, 'mapId': MAP_ID_STUB or None,
                                              'version': 'quarterly', 'idioma': 'es', 'region': 'CO'}}


R1 = [(-73.6770, 4.0920), (-73.6705, 4.0985), (-73.6625, 4.1050), (-73.6550, 4.1110), (-73.6480, 4.1180), (-73.6410, 4.1260),
      (-73.6340, 4.1340), (-73.6280, 4.1400), (-73.6240, 4.1450)]
R2 = [(-73.6540, 4.1450), (-73.6490, 4.1440), (-73.6440, 4.1430), (-73.6390, 4.1425), (-73.6340, 4.1430), (-73.6290, 4.1440), (-73.6240, 4.1450)]
RUTAS = {
    1: dict(id=1, codigo='R001', nombre='Porfía - Centro (DEMO)', origen='Ciudad Porfía', destino='Centro de Villavicencio', color='#1565C0', coords=R1),
    2: dict(id=2, codigo='R002', nombre='Galán - Centro (DEMO)', origen='Sector Galán', destino='Centro de Villavicencio', color='#2E7D32', coords=R2),
}


def trazo_denso(n, fase=0.0):
    """Ruta sintética de n puntos (serpenteante, determinista) para probar el editor con 1.500 o 10.000 vértices."""
    import math
    return [(round(-73.670 + 0.060 * k / (n - 1), 7), round(4.1300 + 0.004 * math.sin(fase + k / (n / 12.0)), 7)) for k in range(n)]


RUTAS_EDITOR = {
    3: dict(id=3, codigo='X1500', nombre='Ruta de prueba 1.500 puntos', origen='A', destino='B', color='#6A1B9A', coords=trazo_denso(1500), denso=True),
    5: dict(id=5, codigo='R005', nombre='Ruta editable de prueba', origen='Inicio', destino='Fin', color='#C62828',
            coords=[(-73.6500, 4.1500), (-73.6400, 4.1520), (-73.6300, 4.1500), (-73.6200, 4.1480)],
            paraderos=[{'id': 501 + i, 'secuencia': i + 1, 'nombre': n, 'ubicacion': {'type': 'Point', 'coordinates': [x, y]}, 'distanciaRecorridoMetros': 0.0}
                       for i, (n, x, y) in enumerate([('Paradero Norte', -73.6450, 4.1510), ('Paradero Centro', -73.6350, 4.1510), ('Paradero Sur', -73.6250, 4.1490)])]),
    6: dict(id=6, codigo='R006', nombre='Ruta para cambiar de código', origen='Inicio', destino='Fin', color='#AD1457',
            coords=[(-73.6500, 4.1600), (-73.6400, 4.1620), (-73.6300, 4.1600), (-73.6200, 4.1580)]),
    4: dict(id=4, codigo='X10000', nombre='Ruta de prueba 10.000 puntos', origen='A', destino='B', color='#00838F', coords=trazo_denso(10000, 1.0), denso=True),
}


def densificar(coords, pasos=6):
    out = []
    for (x0, y0), (x1, y1) in zip(coords, coords[1:]):
        for k in range(pasos):
            out.append([x0 + (x1 - x0) * k / pasos, y0 + (y1 - y0) * k / pasos])
    out.append(list(coords[-1]))
    return out


def km(coords):
    import math
    return sum(math.hypot((b[0] - a[0]) * 111.32 * math.cos(math.radians(4.14)), (b[1] - a[1]) * 110.57) for a, b in zip(coords, coords[1:]))


def punto(c):
    return {'type': 'Point', 'coordinates': [round(c[0], 7), round(c[1], 7)]}


def cercano(coords, lng, lat):
    return min(range(len(coords)), key=lambda i: (coords[i][0] - lng) ** 2 + (coords[i][1] - lat) ** 2)


def camino(a, b, pasos=8):
    pts = [[a[0] + (b[0] - a[0]) * k / pasos, a[1] + (b[1] - a[1]) * k / pasos] for k in range(pasos + 1)]
    import math
    m = round(sum(math.hypot((q[0] - p[0]) * 111320 * math.cos(math.radians(4.14)), (q[1] - p[1]) * 110570) for p, q in zip(pts, pts[1:])))
    return {'coordinates': pts, 'metros': max(m, 5), 'minutos': max(1, round(m / 80))}


ALIAS = {}          # código anterior -> id de ruta (estado del stub, como la tabla ruta_aliases)


def detalle(r):
    dens = [list(c) for c in r['coords']] if r.get('denso') else densificar(r['coords'])
    return {'id': r['id'], 'codigo': r['codigo'], 'nombre': r['nombre'], 'origen': r['origen'], 'destino': r['destino'], 'sentido': 'IDA',
            'descripcion': r.get('descripcion', 'Geometría demostrativa.'), 'color': r['color'], 'distanciaKm': round(km(dens), 3),
            'activa': r.get('activa', True), 'demostrativa': True,
            'validada': r.get('validada', False), 'fuente': r.get('fuente', 'Datos demostrativos'), 'inicio': punto(dens[0]), 'fin': punto(dens[-1]),
            'recorrido': {'type': 'LineString', 'coordinates': dens}, 'paraderos': r.get('paraderos', []), 'version': r.get('version', 0),
            'actualizadoEn': '2026-01-01T00:00:00Z', 'notaValidacion': r.get('notaValidacion'), 'advertencias': []}


def resumen(r):
    return {k: v for k, v in {**detalle(r)}.items()
            if k in ('id', 'codigo', 'nombre', 'origen', 'destino', 'sentido', 'color', 'distanciaKm', 'activa', 'validada')}


def tramo_entre(r, a, b):
    dens = densificar(r['coords'])
    i, j = cercano(dens, a[0], a[1]), cercano(dens, b[0], b[1])
    if i > j:
        i, j = j, i
    if j - i < 2:
        j = min(len(dens) - 1, i + 2)
    return dens[i:j + 1]


def opcion(r, a, b):
    t = tramo_entre(r, (a['lng'], a['lat']), (b['lng'], b['lat']))
    return {'rutaId': r['id'], 'codigo': r['codigo'], 'nombre': r['nombre'], 'color': r['color'], 'validada': False,
            'acercamientoInicioMetros': 40, 'acercamientoFinMetros': 40, 'recorridoKm': round(km(t), 3), 'accesoTotalMetros': 80,
            'dentroLimite': True, 'sentidoViaje': 'IDA', 'sentidoRuta': 'IDA', 'sentidoPermitido': True,
            'puntoSubida': punto(t[0]), 'puntoBajada': punto(t[-1]), 'tramo': {'type': 'LineString', 'coordinates': t}, 'advertencia': None,
            'caminataPorCalles': True, 'paraderoSubida': None, 'paraderoBajada': None,
            'caminataInicioRuta': camino((a['lng'], a['lat']), t[0]), 'caminataFinRuta': camino(t[-1], (b['lng'], b['lat'])), 'variante': 'MENOS_CAMINATA'}


A_LEJOS = (-73.6525, 4.1480)     # origen que usan las pruebas para obtener una caminata larga (lng, lat)


def _puentes_iniciales():
    """Dos puentes fijos: uno sobre la caminata A_LEJOS → ruta R002 (4+ puntos) y otro lejano que ninguna caminata de prueba toca.
    Solo se publican los campos permitidos (id, nombre, metros, trazo): la API real tampoco devuelve la nota interna."""
    t = tramo_entre(RUTAS[2], A_LEJOS, (-73.6240, 4.1450))
    paso = camino(A_LEJOS, t[0])['coordinates']
    relevante = [[round(x, 7), round(y, 7)] for x, y in paso[2:7]]
    lejano = [[-73.6000, 4.1200], [-73.5990, 4.1205], [-73.5980, 4.1212]]
    return [{'id': 9001, 'nombre': 'Puente Esperanza', 'metros': 45, 'trazo': {'type': 'LineString', 'coordinates': relevante}},
            {'id': 9002, 'nombre': 'Puente Lejano', 'metros': 30, 'trazo': {'type': 'LineString', 'coordinates': lejano}}]


# ---- red vial para buses SIMULADA: cuadrícula de calles cada 0,001° (el cálculo real vive en el backend Java y tiene sus propias pruebas)
def _cands(lat, lng, max_m, maximo=3):
    import math
    kx, ky = 111320 * math.cos(math.radians(lat)), 110574
    lat_k, lng_k = round(lat / 0.001) * 0.001, round(lng / 0.001) * 0.001
    todos = [{'viaId': 1, 'tramo': 0, 'lat': round(lat_k, 7), 'lng': round(lng, 7), 'distanciaM': round(abs(lat - lat_k) * ky, 2), 'highway': 'residential',
              'nombre': 'Calle de prueba', 'sentido': 'AMBOS'},
             {'viaId': 2, 'tramo': 0, 'lat': round(lat, 7), 'lng': round(lng_k, 7), 'distanciaM': round(abs(lng - lng_k) * kx, 2), 'highway': 'secondary',
              'nombre': 'Carrera de prueba', 'sentido': 'AMBOS'}]
    todos.sort(key=lambda c: c['distanciaM'])
    return [c for c in todos if c['distanciaM'] <= max_m][:maximo], todos[0]['distanciaM']


def _ajustar_ruta(d):
    pts, radio, anclas = d['puntos'], float(d.get('radioMetros') or 30), set(d.get('anclas') or [])
    estados, prop, desv = [], [], []
    for i, (lat, lng) in enumerate(pts):
        c, minimo = _cands(lat, lng, 1e9, 1)
        c = c[0]
        extremo = d.get('preservarExtremos') and i in (0, len(pts) - 1)
        if i in anclas or extremo:
            estados.append('OK'); prop.append([lat, lng]); desv.append(0.0)
        elif c['distanciaM'] <= radio:
            estados.append('OK'); prop.append([c['lat'], c['lng']]); desv.append(c['distanciaM'])
        elif c['distanciaM'] <= radio * 2:
            estados.append('DUDOSO'); prop.append([c['lat'], c['lng']]); desv.append(c['distanciaM'])
        else:
            estados.append('SIN_COINCIDENCIA'); prop.append([lat, lng]); desv.append(c['distanciaM'])
    tramos, i = [], 0
    while i < len(pts):
        j = i
        while j + 1 < len(pts) and estados[j + 1] == estados[i]:
            j += 1
        h = min(j + 1, len(pts) - 1)          # los tramos consecutivos comparten el vértice de unión
        seg = desv[i:h + 1]
        tramos.append({'desde': i, 'hasta': h, 'propDesde': i, 'propHasta': h, 'estado': estados[i], 'desvMediaM': round(sum(seg) / len(seg), 2),
                       'desvMaxM': round(max(seg), 2), 'motivo': {'OK': '', 'DUDOSO': 'Vía algo lejos del punto', 'SIN_COINCIDENCIA': 'No hay vía para buses cerca'}[estados[i]]})
        if h == len(pts) - 1:
            break
        i = h
    lo, lp = km(pts) * 1000, km(prop) * 1000
    m = {'puntosOriginales': len(pts), 'puntosPropuestos': len(prop), 'longitudOriginalM': round(lo, 1), 'longitudPropuestaM': round(lp, 1),
         'diferenciaPct': round((lp - lo) / lo * 100, 2) if lo else 0.0, 'desviacionMediaM': round(sum(desv) / len(desv), 2), 'desviacionMaxM': round(max(desv), 2),
         'pctAjustado': round(100 * sum(1 for e in estados if e != 'SIN_COINCIDENCIA') / len(estados), 1),
         'tramosDudosos': sum(1 for t in tramos if t['estado'] == 'DUDOSO'), 'puntosSinCoincidencia': estados.count('SIN_COINCIDENCIA'),
         'cambiosDeViaAmbiguos': 0, 'giroIlegales': 0}
    problemas = [k for k, t in enumerate(tramos) if t['estado'] != 'OK']
    return {'propuesta': prop, 'tramos': tramos, 'metricas': m, 'requiereRevisionManual': abs(m['diferenciaPct']) > 15 or m['puntosSinCoincidencia'] > 0,
            'problemas': problemas, 'aviso': 'Vía transitable según la red vial disponible. Requiere revisión del equipo.'}


def _calidad_de(r):
    todas = [list(c[::-1]) for c in (detalle(r)['recorrido']['coordinates'])]      # [lng,lat] -> [lat,lng]
    res = _ajustar_ruta({'puntos': todas, 'radioMetros': 30, 'preservarExtremos': True})
    m = res['metricas']
    n = len(todas)
    if m['pctAjustado'] < 60 or m['puntosSinCoincidencia'] > 0.1 * n:
        estado = 'CRITICA'
    elif res['problemas'] or m['desviacionMaxM'] > 30 or m['pctAjustado'] < 95:
        estado = 'REQUIERE_REVISION'
    else:
        estado = 'CORRECTA'
    prob = None
    if res['problemas']:
        t = res['tramos'][res['problemas'][0]]
        prob = {'desdeIndice': t['desde'], 'hastaIndice': t['hasta'], 'lat': todas[t['desde']][0], 'lng': todas[t['desde']][1], 'estado': t['estado'], 'motivo': t['motivo']}
    return {'id': r['id'], 'codigo': r['codigo'], 'nombre': r['nombre'], 'puntos': n, 'longitudM': m['longitudOriginalM'], 'pctSobreVias': m['pctAjustado'],
            'desviacionMediaM': m['desviacionMediaM'], 'desviacionMaxM': m['desviacionMaxM'], 'segmentosSospechosos': len(res['problemas']),
            'puntosSinCoincidencia': m['puntosSinCoincidencia'], 'giroIlegales': 0, 'sentidoPosiblementeIncorrecto': False, 'estado': estado, 'motivo': '', 'primerProblema': prob}


_PUENTES = None


def puentes_admin():
    """Estado de los puentes del stub (con nota interna y versión, como la lista administrativa del backend)."""
    global _PUENTES
    if _PUENTES is None:
        _PUENTES = [{'id': b['id'], 'tipo': 'PUENTE', 'nombre': b['nombre'], 'nota': 'Nota interna de ' + b['nombre'], 'version': 0, 'metros': b['metros'],
                     'trazo': b['trazo']} for b in _puentes_iniciales()]
    return _PUENTES


def puentes_publicos():
    """Proyección pública: solo id, nombre, metros y trazo (jamás la nota ni la versión)."""
    return [{k: b[k] for k in ('id', 'nombre', 'metros', 'trazo')} for b in puentes_admin()]


def red_peatonal_cuadricula(sur, oeste, norte, este):
    """Calles de ejemplo (cuadrícula cada ~110 m) para que el editor de puentes pueda pegar los extremos a una calle."""
    paso = 0.001
    segs = []
    lat = int(sur / paso) * paso
    while lat <= norte and len(segs) < 400:
        segs.append([round(oeste, 6), round(lat, 6), round(este, 6), round(lat, 6)])
        lat += paso
    lng = int(oeste / paso) * paso
    while lng <= este and len(segs) < 800:
        segs.append([round(lng, 6), round(sur, 6), round(lng, 6), round(norte, 6)])
        lng += paso
    return segs


RUTAS_MULTI = {}      # rutas de apoyo para los escenarios con varias opciones (no aparecen en la lista pública)
COLORES = ['#6A1B9A', '#00838F', '#AD1457', '#EF6C00', '#2E7D32', '#5D4037', '#455A64', '#C62828']


def _ruta_multi(i):
    base = RUTAS[2]
    r = RUTAS_MULTI.get(20 + i) or dict(id=20 + i, codigo='M%02d' % i, nombre='Ruta múltiple %d' % i, origen='Inicio %d' % i, destino='Fin %d' % i, color=COLORES[i % 8], coords=base['coords'])
    RUTAS_MULTI[20 + i] = r
    return r


def _directas(a, b, normales, contrarias):
    """Opciones directas deterministas: M01… (sentido normal, en el orden del servidor) y M05… (sentido contrario)."""
    opciones = [opcion(_ruta_multi(i + 1), a, b) for i in range(normales)]
    alternativas = []
    for i in range(contrarias):
        o = opcion(_ruta_multi(normales + i + 1), a, b)
        o.update(sentidoPermitido=False, sentidoViaje='REGRESO', advertencia='Esta ruta pasa en sentido contrario: úsala solo si el bus también regresa por esas calles.')
        alternativas.append(o)
    return opciones, alternativas


def _transbordos(a, b, n):
    r1, r2 = RUTAS[1], RUTAS[2]
    t1 = tramo_entre(r1, (-73.6340, 4.1340), (-73.6240, 4.1450))
    t2 = tramo_entre(r2, (-73.6490, 4.1440), (-73.6240, 4.1450))

    def pata(r, t):
        return {'rutaId': r['id'], 'codigo': r['codigo'], 'nombre': r['nombre'], 'color': r['color'], 'validada': False, 'puntoSubida': punto(t[0]),
                'puntoBajada': punto(t[-1]), 'tramo': {'type': 'LineString', 'coordinates': t}, 'recorridoKm': round(km(t), 3),
                'paraderoSubida': None, 'paraderoBajada': None}

    lista = [{'minutos': 38.0 + 2 * k, 'caminataMetros': 420.0 + 10 * k, 'busKm': 5.2, 'pasajes': 2, 'esperaCambioMinutos': 8.0, 'primerBus': pata(r1, t1), 'segundoBus': pata(r2, t2),
             'caminataInicio': camino((a['lng'], a['lat']), t1[0]), 'caminataCambio': camino(t1[-1], t2[0]), 'caminataFin': camino(t2[-1], (b['lng'], b['lat'])), 'advertencia': None}
            for k in range(n)]
    for k, t in enumerate(lista):      # cifras de caminata creíbles (la geometría de prueba no coincide con las distancias reales)
        t['caminataInicio']['metros'], t['caminataCambio']['metros'], t['caminataFin']['metros'] = 120, 150, 150 + 10 * k
    return lista


# ---- Política de recomendación SIMULADA (espejo de docs/CONTRATO-RECOMENDACION-Y-BUSQUEDA.md §A.2; el cálculo real vive en el backend Java)
POLITICA = dict(version=2, reduccionAccesoMinimaMetros=300.0, reduccionAccesoMinimaRelativa=0.5, holguraCaminataTotalMetros=300.0,
                maxCaminataCambioMetros=400.0, maxTiempoExtraMinutos=15.0, maxCaminataExtremoMetros=1800.0)


def _id_opcion(o, contraria=False):
    return '%s:%d:%s' % ('A' if contraria else 'D', o['rutaId'], o.get('variante') or 'MENOS_CAMINATA')


def _item_opcion(o, contraria):
    ini, fin = float(o['acercamientoInicioMetros']), float(o['acercamientoFinMetros'])
    tipo = 'SENTIDO_CONTRARIO' if contraria else {'MENOS_CAMINATA': 'DIRECTA', 'MAS_RAPIDA': 'DIRECTA_MAS_RAPIDA', 'BAJAR_Y_CAMINAR': 'BAJAR_Y_CAMINAR',
                                                  'SOLO_SEGUNDA_RUTA': 'SOLO_SEGUNDA_RUTA'}.get(o.get('variante'), 'DIRECTA')
    return {'id': o['id'], 'tipo': tipo, 'orden': 0, 'elegible': not contraria and ini <= POLITICA['maxCaminataExtremoMetros'] and fin <= POLITICA['maxCaminataExtremoMetros'],
            'motivoNoElegible': 'Va en sentido contrario al de la ruta registrada.' if contraria else None,
            'caminataInicialMetros': ini, 'caminataCambioMetros': 0.0, 'caminataFinalMetros': fin, 'caminataTotalMetros': ini + fin,
            'minutos': round((ini + fin) / 80.0 + o['recorridoKm'] * 1000 / 267.0, 2), 'buses': 1,
            'calidadDistancia': 'CALLES' if o.get('caminataPorCalles') else 'RECTA', 'etiquetas': []}


def _item_transbordo(t):
    ini, cam, fin = float(t['caminataInicio']['metros']), float(t['caminataCambio']['metros']), float(t['caminataFin']['metros'])
    return {'id': t['id'], 'tipo': 'TRANSBORDO', 'orden': 0, 'elegible': max(ini, fin) <= POLITICA['maxCaminataExtremoMetros'], 'motivoNoElegible': None,
            'caminataInicialMetros': ini, 'caminataCambioMetros': cam, 'caminataFinalMetros': fin, 'caminataTotalMetros': ini + cam + fin,
            'minutos': float(t['minutos']), 'buses': 2, 'calidadDistancia': 'CALLES', 'etiquetas': []}


def _clave_estable(i):
    return (i['caminataTotalMetros'], i['minutos'], i['buses'], 0 if i['tipo'] != 'TRANSBORDO' else 1, i['id'])


def _cumple_acceso(t, d):
    return (d['caminataInicialMetros'] - t['caminataInicialMetros'] >= max(POLITICA['reduccionAccesoMinimaMetros'], POLITICA['reduccionAccesoMinimaRelativa'] * d['caminataInicialMetros'])
            and t['caminataTotalMetros'] <= d['caminataTotalMetros'] + POLITICA['holguraCaminataTotalMetros']
            and t['caminataCambioMetros'] <= POLITICA['maxCaminataCambioMetros']
            and t['minutos'] <= d['minutos'] + POLITICA['maxTiempoExtraMinutos'])


def recomendar(opciones, alternativas, transbordos):
    """Recomendación simulada: ranking completo, elegibilidad, etiquetas verdaderas y motivo con números reales."""
    for o in opciones:
        o['id'] = _id_opcion(o)
    for o in alternativas:
        o['id'] = _id_opcion(o, True)
    vistos = {}
    for t in transbordos:
        base = 'T:%d>%d' % (t['primerBus']['rutaId'], t['segundoBus']['rutaId'])
        vistos[base] = vistos.get(base, 0) + 1
        t['id'] = base if vistos[base] == 1 else '%s:%d' % (base, vistos[base])
    items = [_item_opcion(o, False) for o in opciones] + [_item_opcion(o, True) for o in alternativas] + [_item_transbordo(t) for t in transbordos]
    elegibles = [i for i in items if i['elegible']]
    directas = sorted([i for i in elegibles if i['buses'] == 1], key=_clave_estable)
    trans = [i for i in elegibles if i['tipo'] == 'TRANSBORDO']
    mejor_d = directas[0] if directas else None
    recomendada = None
    if mejor_d:
        buenos = sorted([t for t in trans if _cumple_acceso(t, mejor_d)], key=lambda t: (t['caminataInicialMetros'], t['caminataTotalMetros'], t['minutos'], t['id']))
        recomendada = buenos[0] if buenos else mejor_d
    elif trans:
        recomendada = sorted(trans, key=lambda t: (t['caminataInicialMetros'], t['caminataTotalMetros'], t['minutos'], t['id']))[0]
    resto = sorted([i for i in items if i is not recomendada], key=lambda i: (not i['elegible'],) + _clave_estable(i))
    ranking = ([recomendada] if recomendada else []) + resto
    for n, i in enumerate(ranking, 1):
        i['orden'] = n
    if len(elegibles) >= 2:
        def unico(clave):
            mejor = min(clave(i) for i in elegibles)
            ganadores = [i for i in elegibles if clave(i) == mejor]
            return ganadores[0] if len(ganadores) == 1 else None
        if trans:
            for i in elegibles:
                if i['buses'] == 1:
                    i['etiquetas'].append('SIN_TRANSBORDO')
        for etiqueta, clave in (('PRIMER_BUS_MAS_CERCANO', lambda i: i['caminataInicialMetros']), ('MENOR_CAMINATA_TOTAL', lambda i: i['caminataTotalMetros']),
                                ('MENOR_TIEMPO', lambda i: i['minutos'])):
            g = unico(clave)
            if g:
                g['etiquetas'].append(etiqueta)
    motivo = None
    if recomendada and recomendada['tipo'] == 'TRANSBORDO':
        motivo = 'Recomendada: tomas el primer bus a %d m de tu ubicación. Requiere un transbordo.' % round(recomendada['caminataInicialMetros'])
        if mejor_d:
            motivo += ' La ruta directa te obligaba a caminar %d m hasta subir.' % round(mejor_d['caminataInicialMetros'])
    elif recomendada:
        motivo = 'Recomendada: subes al bus a %d m de tu ubicación, sin transbordo.' % round(recomendada['caminataInicialMetros'])
    return {'politicaVersion': POLITICA['version'], 'idRecomendada': recomendada['id'] if recomendada else None, 'motivo': motivo, 'ranking': ranking}


def _ajustar_opcion(o, ini, fin, minutos):
    o.update(acercamientoInicioMetros=ini, acercamientoFinMetros=fin, accesoTotalMetros=ini + fin)
    o['recorridoKm'] = round(max(0.1, minutos - (ini + fin) / 80.0) * 267 / 1000.0, 3)
    return o


def _ajustar_transbordo(t, ini, cam, fin, minutos):
    t['caminataInicio']['metros'], t['caminataCambio']['metros'], t['caminataFin']['metros'] = ini, cam, fin
    t['caminataMetros'], t['minutos'] = ini + cam + fin, minutos
    return t


# Escenarios sintéticos del contrato (casos A–G): se piden con origen lat 4.1501…4.1507 y lng ≈ -73.6535.
# Los números son ejemplos para comprobar la intención de la regla, NO rutas reales de Villavicencio.
CASOS = {4.1501: 'A', 4.1502: 'B', 4.1503: 'C', 4.1504: 'D', 4.1505: 'E', 4.1506: 'F', 4.1507: 'G'}


def _caso(letra, a, b):
    def d(i, ini, fin, min_):
        return _ajustar_opcion(opcion(_ruta_multi(i), a, b), ini, fin, min_)

    def t(ini, cam, fin, min_):
        return _ajustar_transbordo(_transbordos(a, b, 1)[0], ini, cam, fin, min_)
    if letra == 'A':
        return [d(1, 900, 100, 30)], [], [t(100, 100, 100, 34)]
    if letra == 'B':
        return [d(1, 150, 100, 25)], [], [t(130, 250, 100, 40)]
    if letra == 'C':
        return [d(1, 800, 100, 30)], [], [t(100, 1800, 500, 65)]
    if letra == 'D':       # la vía está a 60 m en línea recta pero hay que rodear 1.200 m por calles; el otro bus se aborda tras 250 m reales
        return [d(1, 1200, 100, 30)], [], [t(250, 100, 100, 34)]
    if letra == 'E':       # alternativa contraria + transbordo válido, sin directa en sentido correcto
        alt = d(1, 100, 100, 25)
        alt.update(sentidoPermitido=False, sentidoViaje='REGRESO', advertencia='Esta ruta pasa en sentido contrario.')
        return [], [alt], [t(200, 100, 100, 40)]
    if letra == 'F':       # dos resultados equivalentes: el orden debe ser estable
        return [d(2, 200, 100, 28), d(1, 200, 100, 28)], [], []
    return [], [], []      # G: sin ninguna ruta


def planificar(cuerpo):
    r = _planificar(cuerpo)
    for t in r['transbordos']:      # la geometría de prueba del caso «solo transbordo» no refleja distancias reales: se usan cifras creíbles
        if t['caminataInicio']['metros'] > POLITICA['maxCaminataExtremoMetros']:
            _ajustar_transbordo(t, 120, 150, 150, t['minutos'])
    r['recomendacion'] = recomendar(r['opciones'], r['alternativas'], r['transbordos'])
    return r


def _planificar(cuerpo):
    a, b = cuerpo['origen'], cuerpo['destino']
    letra = CASOS.get(round(a['lat'], 4)) if abs(a['lng'] + 73.6535) < 0.0005 else None
    if letra:
        opciones, alternativas, transbordos = _caso(letra, a, b)
        return {'opciones': opciones, 'alternativas': alternativas, 'transbordos': transbordos, 'alcance': 'CASO_' + letra}
    escenario = {4.1470: (4, 3, 0), 4.1475: (2, 0, 0), 4.1485: (0, 2, 0), 4.1433: (0, 0, 3), 4.1490: (2, 0, 2)}.get(round(a['lat'], 4))
    if escenario and abs(a['lng'] + 73.6530) < 0.002 or (escenario and round(a['lat'], 4) == 4.1433):
        normales, contrarias, trans = escenario
        opciones, alternativas = _directas(a, b, normales, contrarias)
        return {'opciones': opciones, 'alternativas': alternativas, 'transbordos': _transbordos(a, b, trans), 'alcance': 'ESCENARIO_PRUEBA'}
    if abs(a['lat'] - 4.1432) < 0.002 and abs(a['lng'] + 73.602) < 0.002:     # el caso «solo con transbordo» de las pruebas
        r1, r2 = RUTAS[1], RUTAS[2]
        t1 = tramo_entre(r1, (-73.6340, 4.1340), (-73.6240, 4.1450))
        t2 = tramo_entre(r2, (-73.6490, 4.1440), (-73.6240, 4.1450))

        def pata(r, t):
            return {'rutaId': r['id'], 'codigo': r['codigo'], 'nombre': r['nombre'], 'color': r['color'], 'validada': False, 'puntoSubida': punto(t[0]),
                    'puntoBajada': punto(t[-1]), 'tramo': {'type': 'LineString', 'coordinates': t}, 'recorridoKm': round(km(t), 3),
                    'paraderoSubida': None, 'paraderoBajada': None}

        return {'opciones': [], 'alternativas': [], 'alcance': 'TRANSBORDO', 'transbordos': [{
            'minutos': 38.0, 'caminataMetros': 420.0, 'busKm': 5.2, 'pasajes': 2, 'esperaCambioMinutos': 8.0, 'primerBus': pata(r1, t1),
            'segundoBus': pata(r2, t2), 'caminataInicio': camino((a['lng'], a['lat']), t1[0]), 'caminataCambio': camino(t1[-1], t2[0]),
            'caminataFin': camino(t2[-1], (b['lng'], b['lat'])), 'advertencia': None}]}
    mejor = min(RUTAS.values(), key=lambda r: min((c[0] - a['lng']) ** 2 + (c[1] - a['lat']) ** 2 for c in r['coords']))
    return {'opciones': [opcion(mejor, a, b)], 'alternativas': [], 'transbordos': [], 'alcance': 'LOCAL'}


# Gancho de pruebas del service worker: /__fallo/<código> hace que /api/rutas y /api/rutas/{id} respondan con ese estado (0 = normal).
FORZAR = {'estado': 0}
LOG = []      # peticiones de búsqueda recibidas (para comprobar sesion=, magicKey y cuántas consultas hizo la página): GET /__log/busqueda


class H(BaseHTTPRequestHandler):
    server_version = 'SEVB-stub'

    def log_message(self, *a):
        pass

    def _enviar(self, codigo, cuerpo, tipo, extra=None):
        data = cuerpo if isinstance(cuerpo, bytes) else cuerpo.encode('utf-8')
        self.send_response(codigo)
        self.send_header('Content-Type', tipo)
        self.send_header('Content-Length', str(len(data)))
        self.send_header('X-Content-Type-Options', 'nosniff')
        self.send_header('Content-Security-Policy', CSP)
        for k, v in (extra or {}).items():
            self.send_header(k, v)
        self.end_headers()
        if self.command != 'HEAD':
            self.wfile.write(data)

    def _json(self, obj, codigo=200):
        self._enviar(codigo, json.dumps(obj), 'application/json', {'Cache-Control': 'no-store'})

    def _busqueda(self, q, mk, sugerencias):
        """Respuesta de búsqueda con el contrato B.2/B.3. Los prefijos del texto provocan cada estado: «tiempo», «redca», «cuota», «clave»,
        «nada», «fuera», «pausa» y «basura» (JSON inválido). «goo…» devuelve sugerencias de Google (con magicKey g:…); magicKey g:fallo
        simula que el detalle del lugar elegido falla; g:sust simula un backend que sustituye el lugar por otro de ArcGIS."""
        low = q.lower()
        estados = {'tiempo': 'TIMEOUT', 'redca': 'RED', 'cuota': 'CUOTA', 'clave': 'CLAVE', 'nada': 'SIN_COINCIDENCIAS', 'fuera': 'FUERA_DE_CIUDAD', 'pausa': 'PAUSADO'}
        if low.startswith('basura'):
            return self._enviar(200, 'esto no es json', 'application/json', {'Cache-Control': 'no-store'})
        if sugerencias:
            for pref, est in estados.items():
                if low.startswith(pref):
                    return self._json({'arcgisConfigurado': True, 'direccionesLocales': True, 'sugerencias': [], 'estado': est, 'proveedor': 'ninguno',
                                       'intentos': [{'proveedor': 'google', 'estado': est}]})
            if not re.match(r'^[A-Za-zÁÉÍÓÚáéíóúñÑ]', q):
                return self._json({'arcgisConfigurado': True, 'direccionesLocales': True, 'sugerencias': [], 'estado': 'SIN_COINCIDENCIAS', 'proveedor': 'ninguno', 'intentos': []})
            if low.startswith('goo'):
                items = [{'texto': q + ' (Google)', 'detalle': 'Google Maps', 'magicKey': 'g:ok' + str(sum(map(ord, q)) % 10**6), 'lat': None, 'lng': None, 'fuente': 'Google Maps', 'precision': None, 'id': None}]
                if low.startswith('goofallo'):
                    items[0]['magicKey'] = 'g:fallo1'
                if low.startswith('goosust'):
                    items[0]['magicKey'] = 'g:sust1'
                return self._json({'arcgisConfigurado': True, 'direccionesLocales': True, 'sugerencias': items, 'estado': 'OK', 'proveedor': 'google',
                                   'intentos': [{'proveedor': 'google', 'estado': 'OK'}]})
            items = [{'texto': q + ' (prueba)', 'detalle': 'Villavicencio', 'lat': 4.145, 'lng': -73.654, 'fuente': 'Stub', 'precision': 'DIRECCION_PUNTUAL', 'id': None}]
            return self._json({'arcgisConfigurado': True, 'direccionesLocales': True, 'sugerencias': items, 'estado': 'OK', 'proveedor': 'local', 'intentos': []})
        # búsqueda explícita / elección de una sugerencia
        if mk:
            if mk.startswith('g:fallo'):
                return self._json({'lugares': [], 'avisos': ['Google no respondió.'], 'eleccionFallida': True, 'estado': 'TIMEOUT', 'proveedor': 'ninguno',
                                   'intentos': [{'proveedor': 'google', 'estado': 'TIMEOUT'}]})
            if mk.startswith('g:sust'):      # backend defectuoso: devuelve un lugar de OTRO proveedor sin avisar
                return self._json({'lugares': [{'id': None, 'nombre': 'OTRO LUGAR (ArcGIS)', 'direccion': 'otro', 'lat': 4.1530, 'lng': -73.6510, 'fuente': 'ArcGIS World Geocoding',
                                                'confirmado': False, 'precision': 'DIRECCION_PUNTUAL'}], 'avisos': [], 'estado': 'OK', 'proveedor': 'arcgis', 'intentos': []})
            prov = 'google' if mk.startswith('g:') else 'arcgis'
            return self._json({'lugares': [{'id': None, 'nombre': q, 'direccion': q, 'lat': 4.1462, 'lng': -73.6528, 'fuente': 'Google Maps' if prov == 'google' else 'ArcGIS World Geocoding',
                                            'confirmado': False, 'precision': 'DIRECCION_PUNTUAL'}], 'avisos': [], 'estado': 'OK', 'proveedor': prov,
                               'intentos': [{'proveedor': prov, 'estado': 'OK'}]})
        for pref, est in estados.items():
            if low.startswith(pref):
                return self._json({'lugares': [], 'avisos': [], 'estado': est, 'proveedor': 'ninguno', 'intentos': [{'proveedor': 'google', 'estado': est}]})
        return self._json({'lugares': [{'id': None, 'nombre': q, 'direccion': q, 'lat': 4.146, 'lng': -73.652, 'fuente': 'Stub', 'confirmado': False, 'precision': 'PREDIO_APROXIMADO'}] if q else [],
                           'avisos': [], 'estado': 'OK' if q else 'SIN_COINCIDENCIAS', 'proveedor': 'local', 'intentos': []})

    def _api(self, ruta, consulta, cuerpo):
        if ruta == '/api/config/mapa':
            return self._json(config_mapa())
        if ruta == '/api/estado':
            return self._json({'servicio': 'SEVB - Rutas de Villavicencio (stub de pruebas)', 'version': VERSION, 'estado': 'OK', 'baseDatos': 'STUB', 'lugaresGuardados': 0, 'geocodificador': 'SOLO_LOCAL'})
        if FORZAR['estado'] and re.fullmatch(r'/api/rutas(/\d+)?', ruta):
            return self._json({'status': FORZAR['estado'], 'message': 'Fallo simulado del servidor.'}, FORZAR['estado'])
        if ruta == '/api/rutas/nombres':
            return self._json({})      # sin nombres populares (cada prueba que los necesita los inyecta)
        if ruta == '/api/rutas':
            return self._json([resumen(r) for r in RUTAS.values()] + [resumen(r) for r in RUTAS_EDITOR.values()])
        m = re.fullmatch(r'/api/rutas/(\d+)', ruta)
        if m:
            r = RUTAS.get(int(m.group(1))) or RUTAS_EDITOR.get(int(m.group(1))) or RUTAS_MULTI.get(int(m.group(1)))
            return self._json(detalle(r)) if r else self._json({'status': 404, 'message': 'Ruta no encontrada'}, 404)
        mc = re.fullmatch(r'/api/rutas/codigo/([A-Za-z0-9_-]{2,20})', ruta)
        if mc:
            c = mc.group(1).upper()
            todas = {**RUTAS, **RUTAS_EDITOR}
            r = next((x for x in todas.values() if x['codigo'].upper() == c), None) or todas.get(ALIAS.get(c))
            if not r:
                return self._json({'status': 404, 'message': 'Código desconocido'}, 404)
            return self._json({'id': r['id'], 'codigo': r['codigo'], 'nombre': r['nombre'], 'aliasUsado': r['codigo'].upper() != c, 'codigoConsultado': mc.group(1)})
        me = re.fullmatch(r'/api/editor/rutas/(\d+)/recorrido', ruta)
        if me and self.command == 'PUT':
            return self._guardar_ruta(int(me.group(1)), json.loads(cuerpo or b'{}'))
        if ruta == '/api/editor/calidad' and self.command == 'GET':
            lista = [_calidad_de(r) for r in {**RUTAS, **RUTAS_EDITOR}.values()]
            orden = {'CRITICA': 0, 'REQUIERE_REVISION': 1, 'CORRECTA': 2}
            lista.sort(key=lambda c: (orden[c['estado']], c['codigo']))
            return self._json({'rutas': lista, 'correctas': sum(c['estado'] == 'CORRECTA' for c in lista), 'requierenRevision': sum(c['estado'] == 'REQUIERE_REVISION' for c in lista),
                               'criticas': sum(c['estado'] == 'CRITICA' for c in lista), 'aviso': 'Vía transitable según la red vial disponible. Requiere revisión del equipo'})
        if ruta == '/api/editor/vial/estado':
            return self._json({'estado': 'LISTA', 'nodos': 0, 'aristas': 0, 'aviso': 'Red vial simulada (stub).'})
        if ruta == '/api/editor/vial/candidatos' and self.command == 'POST':
            d = json.loads(cuerpo or b'{}')
            c, minimo = _cands(d['lat'], d['lng'], float(d.get('maxMetros') or 30), int(d.get('max') or 3))
            return self._json({'candidatos': c, 'masCercanaM': minimo, 'maxMetros': d.get('maxMetros'), 'aviso': 'Vía transitable según la red vial disponible. Requiere revisión del equipo.'})
        if ruta == '/api/editor/vial/ajustar-ruta' and self.command == 'POST':
            return self._json(_ajustar_ruta(json.loads(cuerpo or b'{}')))
        if ruta == '/api/viajes/planificar':
            return self._json(planificar(json.loads(cuerpo or b'{}')))
        if ruta == '/api/caminata':
            q = {k: float(v[0]) for k, v in consulta.items()}
            return self._json({**camino((q['olng'], q['olat']), (q['dlng'], q['dlat'])), 'fuente': 'stub'})
        if ruta == '/api/caminata/estado':
            return self._json({'estado': 'LISTA', 'nodos': 0, 'aristas': 0, 'mensaje': 'stub'})
        if ruta == '/api/puentes':
            return self._json(puentes_publicos())
        if ruta == '/api/editor/pasos' and self.command == 'GET':
            return self._json(puentes_admin())
        mp = re.fullmatch(r'/api/editor/pasos/(\d+)', ruta)
        if mp and self.command == 'PUT':
            return self._guardar_puente(int(mp.group(1)), json.loads(cuerpo or b'{}'))
        if ruta == '/api/caminata/red':
            q = {k: float(v[0]) for k, v in consulta.items()}
            return self._json(red_peatonal_cuadricula(q['sur'], q['oeste'], q['norte'], q['este']))
        if ruta == '/api/paraderos':
            try:
                sur, oeste, norte, este = [float(consulta[k][0]) for k in ('sur', 'oeste', 'norte', 'este')]
            except (KeyError, ValueError):
                return self._json([])
            # un paradero de prueba ≈ 45 m al norte del centro de la zona pedida (sirve para probar «llega al paradero …»)
            return self._json([{'id': 9001, 'nombre': 'Paradero de prueba', 'lat': (sur + norte) / 2 + 0.0004, 'lng': (oeste + este) / 2}])
        if ruta == '/api/rutas/cercanas':
            q = {k: float(v[0]) for k, v in consulta.items() if k in ('lat', 'lng')}
            r = RUTAS[2]
            t = tramo_entre(r, (q['lng'], q['lat']), (q['lng'], q['lat']))
            return self._json([{'rutaId': r['id'], 'codigo': r['codigo'], 'nombre': r['nombre'], 'origen': r['origen'], 'destino': r['destino'], 'color': r['color'],
                                'validada': False, 'distanciaMetros': 120.0, 'puntoCercano': punto(t[0]), 'caminataPorCalles': False}])
        if ruta == '/api/lugares/sugerencias':
            q = (consulta.get('q') or [''])[0]
            LOG.append({'ruta': 'sugerencias', 'q': q, 'magicKey': '', 'sesion': (consulta.get('sesion') or [''])[0]})
            return self._busqueda(q, '', sugerencias=True)
        if ruta == '/api/lugares/reverso':
            lat, lng = float(consulta['lat'][0]), float(consulta['lng'][0])
            return self._json({'id': None, 'nombre': 'Sitio de prueba %.4f, %.4f' % (lat, lng), 'direccion': 'Villavicencio', 'lat': lat, 'lng': lng, 'fuente': 'Stub', 'confirmado': False})
        if ruta == '/api/lugares' and self.command == 'GET':
            q = (consulta.get('q') or [''])[0]
            mk = (consulta.get('magicKey') or [''])[0]
            LOG.append({'ruta': 'lugares', 'q': q, 'magicKey': mk, 'sesion': (consulta.get('sesion') or [''])[0]})
            return self._busqueda(q, mk, sugerencias=False)
        if ruta.startswith('/api/lugares'):
            return self._json({'lugares': [], 'sugerencias': [], 'dirigida': False})
        if ruta == '/api/cuenta/yo':
            return self._json({'autenticado': False})
        if ruta == '/api/cuenta/config':
            return self._json({'adminConfigurado': False})
        if ruta.startswith('/api/cuenta'):
            return self._json({'status': 401, 'message': 'Inicia sesión para continuar.'}, 401)
        return self._json({'status': 404, 'message': 'stub: sin respuesta para ' + ruta}, 404)

    def _guardar_ruta(self, rid, d):
        """PUT de ruta como el backend: versión optimista (409), código único, alias del código anterior, confirmación del cambio de código."""
        todas = {**RUTAS, **RUTAS_EDITOR}
        r = todas.get(rid)
        if not r:
            return self._json({'status': 404, 'message': 'Ruta no encontrada'}, 404)
        if d.get('version') != r.get('version', 0):
            return self._json({'status': 409, 'message': 'La ruta fue modificada por otra persona. Recarga la versión actual.'}, 409)
        nuevo = (d.get('codigo') or r['codigo']).strip().upper()
        if nuevo != r['codigo'].upper():
            if not d.get('confirmarCambioCodigo'):
                return self._json({'status': 400, 'message': 'Cambiar el código requiere confirmación explícita.'}, 400)
            if any(x['codigo'].upper() == nuevo and x['id'] != rid for x in todas.values()):
                return self._json({'status': 400, 'message': 'Ya existe una ruta con el código ' + nuevo + '.'}, 400)
            if ALIAS.get(nuevo, rid) != rid:
                return self._json({'status': 400, 'message': 'El código ' + nuevo + ' perteneció a otra ruta; elige uno distinto.'}, 400)
            ALIAS[r['codigo'].upper()] = rid
            ALIAS.pop(nuevo, None)
            r['codigo'] = nuevo
        coords = d['recorrido']['coordinates']
        r.update(nombre=d['nombre'], origen=d['origen'], destino=d['destino'], color=d['color'], coords=[tuple(c) for c in coords],
                 descripcion=d.get('descripcion', ''), fuente=d.get('fuente', ''), validada=bool(d.get('validada')), activa=d.get('activa', True) is not False,
                 notaValidacion=d.get('notaValidacion'), version=r.get('version', 0) + 1, denso=True)
        return self._json(detalle(r))

    def _guardar_puente(self, pid, d):
        """PUT de puente como el backend: mismo id y tipo, versión optimista (409), validaciones de trazo."""
        b = next((x for x in puentes_admin() if x['id'] == pid), None)
        if not b:
            return self._json({'status': 404, 'message': 'No existe'}, 404)
        coords = d.get('coordenadas') or []
        if len(coords) < 2 or len((d.get('nombre') or '').strip()) < 3:
            return self._json({'status': 400, 'message': 'Datos inválidos.'}, 400)
        largo = round(km([(c[0], c[1]) for c in coords]) * 1000)
        if largo < 3:
            return self._json({'status': 400, 'message': 'El trazo es demasiado corto.'}, 400)
        if d.get('version') != b['version']:
            return self._json({'status': 409, 'message': 'Otra persona modificó este puente. Recarga la lista.'}, 409)
        b.update(nombre=d['nombre'].strip(), nota=(d.get('nota') or '').strip(), metros=largo, version=b['version'] + 1,
                 trazo={'type': 'LineString', 'coordinates': [[c[0], c[1]] for c in coords]})
        return self._json({'id': pid, 'tipo': 'PUENTE', 'metros': largo, 'conectados': 2, 'version': b['version'], 'mensaje': 'Puente actualizado: sigue conectado a la red peatonal por sus dos extremos.'})

    def _estatico(self, ruta):
        if ruta in ('/', ''):
            ruta = '/index.html'
        archivo = (STATIC / ruta.lstrip('/')).resolve()
        if STATIC.resolve() not in archivo.parents or not archivo.is_file():
            return self._enviar(404, 'No encontrado', 'text/plain; charset=utf-8')
        datos = archivo.read_bytes()
        tipo = {'.webmanifest': 'application/manifest+json', '.js': 'text/javascript; charset=utf-8', '.css': 'text/css; charset=utf-8',
                '.html': 'text/html; charset=utf-8'}.get(archivo.suffix) or mimetypes.guess_type(str(archivo))[0] or 'application/octet-stream'
        if archivo.name in ('sw.js', 'manifest.webmanifest'):
            datos = datos.replace(b'@project.version@', VERSION.encode())
        extra = {'Cache-Control': 'no-cache'}
        self._enviar(200, datos, tipo, extra)

    def do_GET(self):
        u = urlparse(self.path)
        if u.path == '/__log/busqueda':
            return self._json(list(LOG))
        if u.path == '/__log/limpiar':
            LOG.clear()
            return self._json({'ok': True})
        m = re.fullmatch(r'/__fallo/(\d{1,3})', u.path)
        if m:
            FORZAR['estado'] = int(m.group(1))
            return self._json({'forzar': FORZAR['estado']})
        if u.path.startswith('/api/'):
            return self._api(u.path, parse_qs(u.query), b'')
        self._estatico(u.path)

    do_HEAD = do_GET

    def do_PUT(self):
        n = int(self.headers.get('Content-Length') or 0)
        cuerpo = self.rfile.read(n) if n else b''
        u = urlparse(self.path)
        if u.path.startswith('/api/'):
            return self._api(u.path, parse_qs(u.query), cuerpo)
        self._enviar(405, 'No permitido', 'text/plain')

    do_DELETE = do_PUT

    def do_POST(self):
        u = urlparse(self.path)
        n = int(self.headers.get('Content-Length') or 0)
        cuerpo = self.rfile.read(n) if n else b''
        if u.path.startswith('/api/'):
            return self._api(u.path, parse_qs(u.query), cuerpo)
        self._enviar(405, 'No permitido', 'text/plain')


def servir(puerto=18080):
    srv = ThreadingHTTPServer(('127.0.0.1', puerto), H)
    threading.Thread(target=srv.serve_forever, daemon=True).start()
    return srv


if __name__ == '__main__':
    p = int(sys.argv[1]) if len(sys.argv) > 1 else 18080
    srv = ThreadingHTTPServer(('127.0.0.1', p), H)
    print(f'Stub SEVB {VERSION} en http://127.0.0.1:{p}/')
    srv.serve_forever()
