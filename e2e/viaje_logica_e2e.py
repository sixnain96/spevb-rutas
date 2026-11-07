"""Pruebas de la LÓGICA del «Modo viaje» (viaje-nucleo.js) con recorridos GPS simulados, sin subirse a ningún bus.

El navegador real ejecuta el núcleo con lecturas generadas por e2e/simulador_gps.js (caminar → esperar → bus → bajar → caminar, con ruido,
saltos, lecturas malas, semáforos, túneles y transbordo). Todo es determinista: las lecturas llevan su propia marca de tiempo, así que un viaje
de 15 minutos se prueba en milisegundos. Uso: SEVB_E2E_URL=http://127.0.0.1:18080/ python e2e/viaje_logica_e2e.py [filtro]
"""
import math
import os
import sys

if hasattr(sys.stdout, 'reconfigure'):
    sys.stdout.reconfigure(encoding='utf-8', errors='replace')      # la consola de Windows (cp1252) no admite todos los símbolos de los mensajes
import traceback

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import sevb_e2e as B  # noqa: E402
from playwright.sync_api import sync_playwright  # noqa: E402

AQUI = os.path.dirname(os.path.abspath(__file__))
PRUEBAS = []


def prueba(f):
    PRUEBAS.append(f)
    return f


def linea(n, lng0=-73.6500, lat0=4.1500, paso=0.0005, onda=0.0004):
    return [[lng0 + i * paso, lat0 + onda * math.sin(i / 6)] for i in range(n)]


def punto(c):
    return {'lat': c[1], 'lng': c[0]}


def plan_directo():
    t = linea(49)                                               # ≈ 2,6 km de recorrido del bus
    return {'origen': {'lat': 4.1478, 'lng': -73.6556}, 'destino': {'lat': 4.1524, 'lng': -73.6250},
            'buses': [{'codigo': 'M01', 'nombre': 'Ruta de prueba', 'color': '#176650', 'subida': punto(t[0]), 'bajada': punto(t[-1]), 'tramo': t}]}


def plan_transbordo():
    t1 = linea(30)
    t2 = [[-73.6330 + i * 0.0005, 4.1545 - 0.0003 * math.sin(i / 5)] for i in range(40)]
    return {'origen': {'lat': 4.1478, 'lng': -73.6556}, 'destino': {'lat': 4.1560, 'lng': -73.6100},
            'buses': [{'codigo': 'M01', 'nombre': 'Primera', 'color': '#176650', 'subida': punto(t1[0]), 'bajada': punto(t1[-1]), 'tramo': t1},
                      {'codigo': 'M02', 'nombre': 'Segunda', 'color': '#1e5fbf', 'subida': punto(t2[0]), 'bajada': punto(t2[-1]), 'tramo': t2}]}


JS = """([plan, op, ruido, resp, cfg]) => {
    const base = SimGps.viaje(plan, op || {});
    const l = ruido ? SimGps.ruido(base, ruido) : base;
    const r = SimGps.correr(plan, l, resp || {}, cfg);
    return { seq: SimGps.secuencia(r.eventos), eventos: r.eventos, fase: r.info.fase, n: l.length, tipos: base.map((x) => x.tipo) };
}"""


def sesion(nav):
    s = B.Sesion(nav)
    s.ctx.add_init_script(path=os.path.join(AQUI, 'simulador_gps.js'))
    return s, s.abrir()


def simular(p, plan, op=None, ruido=None, resp=None, cfg=None):
    return p.evaluate(JS, [plan, op, ruido, resp, cfg])


DIRECTO = ['fase:caminar', 'fase:esperando', 'pregunta:subida', 'fase:en_bus', 'alerta:cerca', 'alerta:preparate', 'alerta:ahora', 'pregunta:bajada', 'fase:caminar_final', 'fase:llegaste', 'llego']


def sin_ruido_de_fase(seq):
    """Quita los eventos de ida y vuelta caminar↔esperando (legítimos al acercarse al paradero) para comparar el flujo."""
    return [x for x in seq if x not in ('desvio',)]


# ---------------------------------------------------------------------------------------------- el recorrido completo
@prueba
def viaje_directo_ideal_recorre_todas_las_fases_en_orden(nav):
    s, p = sesion(nav)
    r = simular(p, plan_directo())
    assert r['seq'] == DIRECTO, r['seq']
    assert r['fase'] == 'llegaste'
    s.limpio('viaje ideal'); s.cerrar()


@prueba
def con_un_gps_ruidoso_el_flujo_es_el_mismo_y_no_hay_preguntas_de_mas(nav):
    s, p = sesion(nav)
    for semilla, sigma in ((1, 8), (2, 12), (3, 15)):
        n = p.evaluate("([plan]) => SimGps.viaje(plan, {}).length", [plan_directo()])
        r = simular(p, plan_directo(), ruido={'sigma': sigma, 'semilla': semilla, 'saltos': [40, 400, 900], 'malas': list(range(100, n, 23))})
        assert r['seq'] == DIRECTO, f'sigma {sigma}: {r["seq"]}'
    s.limpio('gps ruidoso'); s.cerrar()


@prueba
def la_aceleracion_y_el_frenado_del_bus_no_confunden_las_fases(nav):
    s, p = sesion(nav)
    r = simular(p, plan_directo(), op={'vBus': 4.5, 'vCamina': 1.1}, ruido={'sigma': 6, 'semilla': 5})        # bus lento (16 km/h) y peatón lento
    assert r['seq'] == DIRECTO, r['seq']
    r = simular(p, plan_directo(), op={'vBus': 12}, ruido={'sigma': 6, 'semilla': 6})                          # bus rápido (43 km/h)
    assert r['seq'] == DIRECTO, r['seq']
    s.cerrar()


# ---------------------------------------------------------------------------------------------- falsos positivos y silencios
@prueba
def un_dispositivo_que_informa_velocidad_cero_siempre_no_resta_velocidad_real(nav):
    """El emulador de Android entrega coords.speed = 0 aunque la posición se mueva (verificado con `dumpsys location`: vel=0.0)."""
    s, p = sesion(nav)
    r = p.evaluate("""([plan]) => {
        const salida = {};
        for (const [nombre, op] of [['normal', {}], ['bus lento', { vBus: 4.5 }], ['peaton lento', { vCamina: 0.9 }]]) {
            const l = SimGps.ruido(SimGps.viaje(plan, op), { sigma: 6, semilla: 3 }).map((x) => ({ ...x, velocidad: 0 }));
            salida[nombre] = SimGps.secuencia(SimGps.correr(plan, l, {}).eventos);
        }
        return salida; }""", [plan_directo()])
    for nombre, seq in r.items():
        assert seq == DIRECTO, f'{nombre} con velocidad informada = 0: {seq}'
    s.cerrar()


@prueba
def caminar_o_esperar_mucho_tiempo_nunca_pregunta_si_te_subiste(nav):
    s, p = sesion(nav)
    r = simular(p, plan_directo(), op={'esperaSeg': 420}, ruido={'sigma': 10, 'semilla': 9}, resp={'subida': False})
    previos = sum(1 for t in r['tipos'] if t in ('caminar', 'espera'))           # lecturas de caminar y esperar (7 minutos quieto incluidos)
    preguntas = [e for e in r['eventos'] if e['tipo'] == 'pregunta' and e['id'] == 'subida']
    assert preguntas and preguntas[0]['i'] >= previos, f'ninguna pregunta mientras camina o espera: primera en {preguntas[0]["i"]}, el bus arranca en {previos}'
    # aunque se conteste «no» siempre, las preguntas siguientes respetan el silencio de 90 s
    huecos = [b['i'] - a['i'] for a, b in zip(preguntas, preguntas[1:])]
    assert all(h >= 90 for h in huecos), huecos
    s.cerrar()


@prueba
def ir_rapido_pero_lejos_de_la_ruta_no_dispara_la_pregunta(nav):
    s, p = sesion(nav)
    plan = plan_directo()
    lejos = p.evaluate("""([plan]) => { const kx = 111320 * Math.cos(4.15 * Math.PI / 180), ky = 110574;
        const pts = plan.buses[0].tramo.map((c) => ({ lat: c[1] - 600 / ky, lng: c[0] }));
        return SimGps.recorrer(pts, () => 7, 1, { t: 1700000000000 }, 'carro'); }""", [plan])
    r = p.evaluate("""([plan, l]) => { const r = SimGps.correr(plan, l, {}); return { seq: SimGps.secuencia(r.eventos), fase: r.info.fase }; }""", [plan, lejos])
    assert 'pregunta:subida' not in r['seq'] and r['fase'] in ('caminar', 'esperando'), r
    s.cerrar()


@prueba
def responder_que_no_silencia_la_pregunta_un_tiempo_y_vuelve_a_preguntar(nav):
    s, p = sesion(nav)
    r = simular(p, plan_directo(), resp={'subida': [False, True]})
    preguntas = [e for e in r['eventos'] if e['tipo'] == 'pregunta' and e['id'] == 'subida']
    assert len(preguntas) == 2, f'pregunta, se responde «no» y, pasado el silencio de 90 s, vuelve a preguntar: {len(preguntas)}'
    assert preguntas[1]['i'] > preguntas[0]['i'] + 80, 'no repite la pregunta de inmediato'
    assert r['fase'] == 'llegaste'
    s.cerrar()


@prueba
def un_semaforo_o_un_atasco_dentro_del_bus_no_se_toma_por_haberse_bajado(nav):
    s, p = sesion(nav)
    r = simular(p, plan_directo(), op={'semaforos': [{'avance': 700, 'seg': 40}, {'avance': 1500, 'seg': 25}, {'avance': 2250, 'seg': 90}]}, ruido={'sigma': 8, 'semilla': 11})
    assert r['seq'].count('pregunta:bajada') == 1 and r['seq'] == DIRECTO, r['seq']
    s.cerrar()


@prueba
def una_pregunta_sin_respuesta_se_retira_sola_cuando_la_situacion_cambia(nav):
    s, p = sesion(nav)
    plan = plan_directo()
    # arranca como un bus (pregunta) pero se detiene y camina de vuelta: nadie contesta
    r = p.evaluate("""([plan]) => {
        const t = plan.buses[0].tramo, st = { t: 1700000000000 };
        const sub = { lat: t[0][1], lng: t[0][0] };
        const l = [...SimGps.quieto(sub, 30, 1, st, 'espera'),
                   ...SimGps.recorrer([sub, { lat: t[2][1], lng: t[2][0] }], (m) => Math.min(7, 1 + m / 6), 1, st, 'bus'),
                   ...SimGps.quieto({ lat: t[2][1], lng: t[2][0] }, 60, 1, st, 'espera')];
        const r = SimGps.correr(plan, l, { ignorar: true });
        return { seq: SimGps.secuencia(r.eventos), fase: r.info.fase, pregunta: r.info.pregunta }; }""", [plan])
    assert 'pregunta:subida' in r['seq'] and 'pregunta_retirada' in r['seq'], r
    assert r['pregunta'] is None
    s.cerrar()


# ---------------------------------------------------------------------------------------------- alertas, pasarse, señal
@prueba
def las_alertas_de_bajada_llegan_una_sola_vez_y_en_orden_con_distancias_decrecientes(nav):
    s, p = sesion(nav)
    r = simular(p, plan_directo(), ruido={'sigma': 7, 'semilla': 3})
    alertas = [e for e in r['eventos'] if e['tipo'] == 'alerta']
    assert [a['nivel'] for a in alertas] == ['cerca', 'preparate', 'ahora'], alertas
    d = [a['distancia'] for a in alertas]
    assert d[0] > d[1] > d[2] and 250 < d[0] <= 520 and 90 < d[1] <= 220 and d[2] <= 80, d
    s.cerrar()


@prueba
def si_el_bus_se_pasa_de_la_parada_avisa_y_no_asume_que_bajaste(nav):
    s, p = sesion(nav)
    plan = plan_directo()
    # el bus sigue 600 m más allá de la bajada prevista
    r = p.evaluate("""([plan]) => {
        const t = plan.buses[0].tramo, st = { t: 1700000000000 };
        const ext = [...t, ...Array.from({ length: 12 }, (_, i) => [t[t.length - 1][0] + (i + 1) * 0.0005, t[t.length - 1][1]])];
        const larga = { ...plan, buses: [{ ...plan.buses[0], tramo: ext }] };
        const l = SimGps.viaje(larga, {});
        const r = SimGps.correr(plan, l, {});
        return { seq: SimGps.secuencia(r.eventos), fase: r.info.fase }; }""", [plan])
    assert 'se_paso' in r['seq'], r
    assert r['seq'].index('se_paso') > r['seq'].index('alerta:ahora')
    s.cerrar()


@prueba
def un_tunel_sin_senal_no_rompe_el_viaje_y_se_avisa_al_volver(nav):
    s, p = sesion(nav)
    plan = plan_directo()
    tipos = simular(p, plan)['tipos']
    ini = tipos.index('bus') + 100                              # a los 100 s de ir en el bus
    r = simular(p, plan, ruido={'sigma': 6, 'semilla': 4, 'huecos': [[ini, ini + 70]]})
    assert 'senal_recuperada' in r['seq'], r['seq']
    assert [x for x in r['seq'] if x != 'senal_recuperada'] == DIRECTO, r['seq']
    s.cerrar()


@prueba
def solo_lecturas_malas_avisan_senal_debil_y_no_cambian_de_fase(nav):
    s, p = sesion(nav)
    plan = plan_directo()
    r = p.evaluate("""([plan]) => {
        const st = { t: 1700000000000 }, v = SimGps.quieto(plan.origen, 3, 1, st, 'x');
        const malas = SimGps.quieto(plan.origen, 20, 1, st, 'x').map((l) => ({ ...l, precision: 300 }));
        const c = SimGps.correr(plan, [...v, ...malas], {});
        return { tipos: c.eventos.map((e) => e.tipo + (e.motivo ? ':' + e.motivo : '')), fase: c.info.fase }; }""", [plan])
    assert 'senal_debil' in r['tipos'] and r['fase'] in ('caminar', 'iniciando'), r
    s.cerrar()


@prueba
def si_la_parada_de_bajada_es_el_destino_pregunta_y_al_confirmar_llega(nav):
    s, p = sesion(nav)
    plan = plan_directo()
    plan['destino'] = dict(plan['buses'][0]['bajada'])                      # no hay nada que caminar después del bus
    r = simular(p, plan, op={'paradaSeg': 40}, ruido={'sigma': 7, 'semilla': 12})
    assert r['seq'] == DIRECTO, r['seq']
    s.cerrar()


# ---------------------------------------------------------------------------------------------- transbordo y casos de borde
@prueba
def con_transbordo_repite_el_flujo_para_el_segundo_bus(nav):
    s, p = sesion(nav)
    r = simular(p, plan_transbordo(), ruido={'sigma': 7, 'semilla': 8})
    esperado = ['fase:caminar', 'fase:esperando', 'pregunta:subida', 'fase:en_bus', 'alerta:cerca', 'alerta:preparate', 'alerta:ahora', 'pregunta:bajada',
                'fase:caminar', 'fase:esperando', 'pregunta:subida', 'fase:en_bus', 'alerta:cerca', 'alerta:preparate', 'alerta:ahora', 'pregunta:bajada',
                'fase:caminar_final', 'fase:llegaste', 'llego']
    assert [x for x in r['seq'] if x != 'desvio'] == esperado, r['seq']
    cambios = [e for e in r['eventos'] if e['tipo'] == 'fase' and e['fase'] == 'caminar' and e.get('transbordo')]
    assert len(cambios) == 1
    s.cerrar()


@prueba
def empezar_ya_en_el_paradero_pasa_directo_a_esperar(nav):
    s, p = sesion(nav)
    plan = plan_directo()
    r = simular(p, plan, op={'empezarEn': plan['buses'][0]['subida']})
    assert r['seq'][0] == 'fase:esperando' and r['seq'][-1] == 'llego', r['seq']
    s.cerrar()


@prueba
def lecturas_invalidas_repetidas_o_desordenadas_no_rompen_el_viaje(nav):
    s, p = sesion(nav)
    plan = plan_directo()
    r = p.evaluate("""([plan]) => {
        const l = SimGps.viaje(plan, {});
        const sucias = [];
        l.forEach((x, i) => { sucias.push(x); if (i % 17 === 0) sucias.push(x); if (i % 29 === 0) sucias.push({ ...x, t: x.t - 5000 });
                              if (i % 41 === 0) sucias.push({ lat: NaN, lng: x.lng, precision: 5, t: x.t + 1 }); if (i % 53 === 0) sucias.push({ ...x, precision: undefined, t: x.t + 2 }); });
        const c = SimGps.correr(plan, sucias, {});
        return { seq: SimGps.secuencia(c.eventos), motivos: [...new Set(c.eventos.filter((e) => e.tipo === 'descartada').map((e) => e.motivo))] }; }""", [plan])
    assert r['seq'] == DIRECTO, r['seq']
    assert set(r['motivos']) >= {'repetida', 'invalida', 'imprecisa'}, r['motivos']
    s.cerrar()


@prueba
def el_estado_se_guarda_y_se_restaura_en_la_misma_fase(nav):
    s, p = sesion(nav)
    plan = plan_transbordo()
    r = p.evaluate("""([plan]) => {
        const l = SimGps.viaje(plan, {});
        const media = l.findIndex((x) => x.tipo === 'camina_cambio') + 60;
        const c = SimGps.correr(plan, l.slice(0, media), {});
        const x = JSON.parse(JSON.stringify(c.v.serializar()));
        const v2 = Spevb.ViajeNucleo.crearViaje(plan); v2.restaurar(x);
        return { x, info: v2.info() }; }""", [plan])
    assert r['x']['fase'] == 'caminar' and r['x']['leg'] == 1 and r['info']['leg'] == 1 and r['info']['codigo'] == 'M02', r
    s.cerrar()


# ---------------------------------------------------------------------------------------------- 4.15.1: alertas por recorrido y confirmación manual
def plan_con_retorno():
    """Tramo en «U»: 1200 m al este, sube 60 m y regresa 600 m al oeste. El punto de bajada queda a 60 m EN LÍNEA RECTA de la mitad de la ida,
    pero a más de 1,2 km por el recorrido. Es el caso de un bus que pasa cerca de la bajada mucho antes de llegar a ella."""
    kx, ky, lng0, lat0 = 111040.0, 110574.0, -73.6500, 4.1500
    pts = [(0, 0), (1200, 0), (1200, 60), (600, 60)]
    t = []
    for (x0, y0), (x1, y1) in zip(pts, pts[1:]):
        n = max(2, int(math.hypot(x1 - x0, y1 - y0) / 25))
        for i in range(n):
            t.append([lng0 + (x0 + (x1 - x0) * i / n) / kx, lat0 + (y0 + (y1 - y0) * i / n) / ky])
    t.append([lng0 + 600 / kx, lat0 + 60 / ky])
    return {'origen': {'lat': lat0, 'lng': lng0}, 'destino': {'lat': lat0 + 60 / ky, 'lng': lng0 + 500 / kx},
            'buses': [{'codigo': 'M09', 'nombre': 'Ruta con retorno', 'color': '#176650', 'subida': punto(t[0]), 'bajada': punto(t[-1]), 'tramo': t}]}


@prueba
def un_bus_que_pasa_cerca_de_la_bajada_antes_de_llegar_no_dispara_avisos_urgentes(nav):
    s, p = sesion(nav)
    plan = plan_con_retorno()
    r = p.evaluate("""([plan]) => {
        const base = SimGps.viaje(plan, {}), l = SimGps.ruido(base, { sigma: 5, semilla: 11 });
        const res = SimGps.correr(plan, l, {});
        return { seq: SimGps.secuencia(res.eventos), eventos: res.eventos, lat: l.map((x) => x.lat) }; }""", [plan])
    alertas = [e for e in r['eventos'] if e['tipo'] == 'alerta']
    assert [a['nivel'] for a in alertas] == ['cerca', 'preparate', 'ahora'], alertas
    assert 'se_paso' not in r['seq'], r['seq']
    # El primer aviso llega en el tramo de regreso (a 60 m al norte), no al pasar a 60 m en línea recta por la ida.
    primera = alertas[0]
    assert (r['lat'][primera['i']] - 4.15) * 110574.0 > 30, f"primer aviso demasiado pronto: lectura {primera['i']}"
    assert 250 < primera['distancia'] <= 520, primera


@prueba
def el_texto_de_bajada_no_manda_bajar_con_el_bus_en_movimiento(nav):
    s, p = sesion(nav)
    r = simular(p, plan_directo(), ruido={'sigma': 6, 'semilla': 5})
    textos = [e.get('texto', '') for e in r['eventos'] if e['tipo'] == 'alerta']
    assert len(textos) == 3, textos
    assert 'Te acercas a tu punto de bajada' in textos[0], textos
    assert 'Prepárate para bajar' in textos[1], textos
    assert 'Baja cuando el bus se detenga' in textos[2] and 'Bájate ahora' not in ' '.join(textos), textos
    s.cerrar()


@prueba
def la_confirmacion_manual_usa_las_transiciones_del_nucleo_y_no_duplica_estados(nav):
    s, p = sesion(nav)
    r = p.evaluate("""([plan]) => {
        const v = Spevb.ViajeNucleo.crearViaje(plan), base = SimGps.viaje(plan, {}), out = {};
        out.sinLectura = v.confirmarSubida().length;
        for (const l of base) { v.procesar(l); if (v.info().fase === 'esperando') break; }
        out.faseAntes = v.info().fase;
        out.bajadaAntes = v.confirmarBajada().length;
        const a = v.confirmarSubida(); out.fase1 = v.info().fase; out.ev1 = a.map((e) => e.tipo + ':' + (e.fase || ''));
        out.otraVez = v.confirmarSubida().length;
        out.pregunta = v.info().pregunta;
        const b = v.confirmarBajada(); out.fase2 = v.info().fase; out.ev2 = b.map((e) => e.tipo + ':' + (e.fase || ''));
        out.bajadaOtraVez = v.confirmarBajada().length;
        return out; }""", [plan_directo()])
    assert r['sinLectura'] == 0 and r['faseAntes'] == 'esperando' and r['bajadaAntes'] == 0, r
    assert r['fase1'] == 'en_bus' and r['ev1'] == ['fase:en_bus'] and r['otraVez'] == 0 and r['pregunta'] is None, r
    assert r['fase2'] == 'caminar_final' and r['ev2'] == ['fase:caminar_final'] and r['bajadaOtraVez'] == 0, r
    s.cerrar()


@prueba
def con_transbordo_la_confirmacion_manual_pasa_al_segundo_bus_y_reinicia_sus_avisos(nav):
    s, p = sesion(nav)
    r = p.evaluate("""([plan]) => {
        const v = Spevb.ViajeNucleo.crearViaje(plan), base = SimGps.viaje(plan, {});
        for (const l of base) { v.procesar(l); if (v.info().fase === 'esperando') break; }
        v.confirmarSubida(); v.confirmarBajada();
        const i = v.info();
        return { fase: i.fase, leg: i.leg, etiqueta: i.etiqueta, alertas: i.alertas }; }""", [plan_transbordo()])
    assert r['fase'] == 'caminar' and r['leg'] == 1 and r['etiqueta'] == 'M02', r
    assert r['alertas'] == {'cerca': False, 'preparate': False, 'ahora': False}, r
    s.cerrar()


# ---------------------------------------------------------------------------------------------- el filtro de lecturas
@prueba
def el_filtro_descarta_lo_imprecio_los_saltos_aislados_y_acepta_un_salto_confirmado(nav):
    s, p = sesion(nav)
    r = p.evaluate("""() => {
        const F = new Spevb.ViajeNucleo.FiltroLecturas(); const t0 = 1700000000000;
        const lec = (i, lat, lng, acc = 8) => ({ lat, lng, precision: acc, t: t0 + i * 1000 });
        const res = {};
        for (let i = 0; i < 6; i++) F.procesar(lec(i, 4.15, -73.65));
        res.mala = F.procesar(lec(6, 4.15, -73.65, 400)).motivo;
        res.salto = F.procesar(lec(7, 4.1560, -73.6440)).motivo;                       // ≈ 800 m en 1 s
        res.vuelve = F.procesar(lec(8, 4.15, -73.65)).ok;                               // la lectura siguiente es normal: el salto fue ruido
        const G = new Spevb.ViajeNucleo.FiltroLecturas();
        for (let i = 0; i < 6; i++) G.procesar(lec(i, 4.15, -73.65));
        const tres = [7, 8, 9].map((i) => G.procesar(lec(i, 4.16, -73.64)));            // tres lecturas seguidas en otro sitio: el cambio es real
        res.confirmado = tres.map((x) => x.ok);
        return res; }""")
    assert r['mala'] == 'imprecisa' and r['salto'] == 'salto' and r['vuelve'] is True, r
    assert r['confirmado'] == [False, False, True], r
    s.cerrar()


@prueba
def el_filtro_estabiliza_un_punto_quieto_y_mide_bien_la_velocidad(nav):
    s, p = sesion(nav)
    r = p.evaluate("""() => {
        const sim = SimGps.ruido(SimGps.quieto({ lat: 4.15, lng: -73.65 }, 120, 1, { t: 1700000000000 }, 'q'), { sigma: 8, semilla: 7 });
        const F = new Spevb.ViajeNucleo.FiltroLecturas(); const out = sim.map((l) => F.procesar(l)).filter((x) => x.ok).map((x) => x.lectura);
        const dist = Spevb.ViajeNucleo.dist, c = { lat: 4.15, lng: -73.65 };
        const quieto = out.slice(20);
        const crudo = sim.slice(20).map((l) => dist(c, l));
        // movimiento a 7 m/s
        const st = { t: 1700000000000 }, pts = [{ lat: 4.15, lng: -73.65 }, { lat: 4.15, lng: -73.62 }];
        const mov = SimGps.ruido(SimGps.recorrer(pts, () => 7, 1, st, 'b'), { sigma: 6, semilla: 8 });
        const G = new Spevb.ViajeNucleo.FiltroLecturas(); const v = mov.map((l) => G.procesar(l)).filter((x) => x.ok).map((x) => x.lectura.vel);
        return { maxQuieto: Math.max(...quieto.map((l) => dist(c, l))), maxCrudo: Math.max(...crudo), velQuieto: Math.max(...quieto.map((l) => l.vel)), vMov: v.slice(30, 300) }; }""")
    assert r['maxQuieto'] < r['maxCrudo'] * 0.6, f'el punto filtrado tiembla bastante menos que el crudo: {r}'
    assert r['velQuieto'] < 0.7, 'parado no marca velocidad'
    media = sum(r['vMov']) / len(r['vMov'])
    assert 6.0 <= media <= 7.8, f'velocidad media a 7 m/s: {media}'
    s.cerrar()


@prueba
def la_proyeccion_sobre_la_ruta_da_avance_y_distancia(nav):
    s, p = sesion(nav)
    r = p.evaluate("""() => {
        const N = Spevb.ViajeNucleo, l = N.prepararLinea([[-73.65, 4.15], [-73.64, 4.15], [-73.64, 4.16]]);
        const a = N.proyectar(l, { lat: 4.1503, lng: -73.645 }), b = N.proyectar(l, { lat: 4.155, lng: -73.6395 });
        return { total: l.total, a, b }; }""")
    assert 2150 < r['total'] < 2300, r
    assert 20 < r['a']['dist'] < 45 and 540 < r['a']['avance'] < 580, r
    assert r['b']['dist'] < 60 and r['b']['avance'] > r['total'] * 0.7, r
    s.cerrar()


def correr(filtro=None):
    fallos = []
    with sync_playwright() as p:
        nav = B.navegador(p)
        print('Navegador:', nav.browser_type.name, nav.version, '| URL:', B.URL)
        for f in PRUEBAS:
            if filtro and filtro not in f.__name__:
                continue
            try:
                f(nav)
                print('OK    ', f.__name__)
            except Exception as e:  # noqa: BLE001
                fallos.append(f.__name__)
                print('FALLA ', f.__name__, '|', str(e).splitlines()[0][:600] if str(e) else repr(e))
                traceback.print_exc(limit=3)
        nav.close()
    print(f'\n{len(PRUEBAS) if not filtro else "?"} pruebas de la lógica del modo viaje; fallos: {len(fallos)} {fallos}')
    return 1 if fallos else 0


if __name__ == '__main__':
    sys.exit(correr(sys.argv[1] if len(sys.argv) > 1 else None))
