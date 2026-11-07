'use strict';
/* Ajuste a vías para buses (editor de recorridos). TODO es propuesta: nada se guarda ni cambia el trazado hasta que la persona acepta, y
   aun así queda como cambio pendiente en el borrador (Guardar recorrido es un paso aparte; «validada» se desmarca). La red vial para buses
   es SEPARADA de la peatonal (andenes, senderos, escaleras y puentes peatonales nunca cuentan). Aviso fijo: «Vía transitable según la red
   vial disponible. Requiere revisión del equipo». */
(() => {
    const $ = (id) => document.getElementById(id);
    const E = () => window.SpevbEditor;
    if (!$('snapVertex')) return;
    const AVISO = 'Vía transitable según la red vial disponible. Requiere revisión del equipo.';
    const G = Spevb.EditorGeo;
    const COLOR = { ORIGINAL: '#e65100', OK: '#2e7d32', DUDOSO: '#f9a825', SIN_COINCIDENCIA: '#c62828' };
    const LIMITE_SALTO_M = 2000, LIMITE_CAMBIO_PCT = 15;
    // Las capas de previsualización se crean una sola vez, cuando el editor (y su mapa) ya existe: ver capas().
    let previa = null, comparacion = null;
    let ctlCand = null, candTimer = null, candidatos = [], elegido = -1, propuesta = null, decisiones = [], foco = -1;

    const msg = (t, tipo = 'info') => { const el = $('snapStatus'); el.hidden = !t; el.textContent = t || ''; el.className = 'status ' + tipo; };
    const token = () => $('editorToken').value;
    function conClave() {
        if (token().length < 32) { msg('Introduce la clave de administración (en «Guardar y documentar») o entra como administrador.', 'error'); return false; }
        return true;
    }
    const post = (url, cuerpo, signal) => Spevb.api(url, { method: 'POST', signal, headers: { 'Content-Type': 'application/json', 'X-Editor-Token': token() }, body: JSON.stringify(cuerpo) });
    const fm = (m) => (m == null ? '—' : m >= 1000 ? (m / 1000).toLocaleString('es-CO', { maximumFractionDigits: 2 }) + ' km' : Math.round(m).toLocaleString('es-CO') + ' m');
    const maxSnap = () => Math.max(5, Math.min(100, Number($('snapMax').value) || 30));
    const capas = () => {
        if (!E()) return false;
        if (!previa) { previa = E().map.group().addTo(E().map); comparacion = E().map.group().addTo(E().map); }
        return true;
    };

    // ------------------------------------------------------------------ distancia a la vía más cercana (al elegir un vértice)
    window.addEventListener('spevb-editor-vertice', (e) => {
        clearTimeout(candTimer); ctlCand?.abort();
        const info = (t) => E().setVertexInfo(t);
        if (token().length < 32) { info(''); return; }
        const { index, punto } = e.detail;
        info('Vía para buses más cercana: consultando…');
        candTimer = setTimeout(async () => {
            ctlCand = new AbortController();
            try {
                const r = await post('/api/editor/vial/candidatos', { lat: punto[0], lng: punto[1], maxMetros: 100, max: 3 }, ctlCand.signal);
                if (E().selected() !== index) return;
                const c = (r.candidatos || [])[0];
                info(c ? 'Vía para buses más cercana: ' + fm(c.distanciaM) + ' (' + (c.nombre || c.highway) + '). ' + AVISO : 'Sin vía para buses a menos de 100 m. ' + AVISO);
            } catch (err) { if (err.name !== 'AbortError') info('Red vial para buses no disponible: ' + err.message); }
        }, 300);
    });

    // ------------------------------------------------------------------ ajuste manual de un vértice
    function limpiarPrevia() { if (previa) previa.clear(); candidatos = []; elegido = -1; $('snapPanel').hidden = true; $('snapList').replaceChildren(); }
    function pintarPrevia() {
        if (!capas()) return;
        previa.clear();
        const i = E().selected(), p = E().points()[i], map = E().map;
        candidatos.forEach((c, k) => {
            const sel = k === elegido;
            map.polyline([p, [c.lat, c.lng]], { color: sel ? '#2e7d32' : '#8d6e63', weight: sel ? 3 : 2, dashArray: '4 6', interactive: false }).addTo(previa);
            map.dot([c.lat, c.lng], { radius: sel ? 8 : 6, color: sel ? '#1b5e20' : '#5d4037', fillColor: sel ? '#66bb6a' : '#d7ccc8', fillOpacity: 1, weight: 3, interactive: false }).addTo(previa);
        });
    }
    $('snapVertex').onclick = async () => {
        if (!capas() || E().busy() || !E().route()) return;
        const i = E().selected();
        limpiarPrevia();
        if (i < 0) { msg('Elige primero un vértice (clic sobre la línea o «Ir al vértice número»).', 'error'); return; }
        if (!conClave()) return;
        msg('Buscando vías para buses cercanas…');
        try {
            const p = E().points()[i], limite = maxSnap();
            const r = await post('/api/editor/vial/candidatos', { lat: p[0], lng: p[1], maxMetros: limite, max: 3 });
            candidatos = r.candidatos || [];
            if (!candidatos.length) {
                const cerca = r.masCercanaM != null ? ' La vía más cercana está a ' + fm(r.masCercanaM) + '.' : '';
                msg('No hay una vía para buses a menos de ' + limite + ' m del vértice ' + (i + 1) + '; el punto se conserva.' + cerca + ' ' + AVISO, 'error');
                return;
            }
            elegido = 0; $('snapPanel').hidden = false; pintarPrevia();
            const lista = $('snapList');
            candidatos.forEach((c, k) => {
                const fila = document.createElement('label'); fila.className = 'snap-row';
                const radio = document.createElement('input'); radio.type = 'radio'; radio.name = 'snapCand'; radio.checked = k === 0;
                radio.onchange = () => { elegido = k; pintarPrevia(); };
                const t = document.createElement('span'); t.textContent = ' ' + fm(c.distanciaM) + ' · ' + (c.nombre || c.highway || 'vía') + (c.highway ? ' (' + c.highway + ')' : '');
                fila.append(radio, t); lista.append(fila);
            });
            msg(candidatos.length + (candidatos.length > 1 ? ' vías candidatas: elige la correcta y confirma. ' : ' vía candidata: confirma para aplicar. ') + AVISO, 'info');
        } catch (err) { msg(err.message, 'error'); }
    };
    $('snapApply').onclick = () => {
        const i = E().selected(), c = candidatos[elegido];
        if (i < 0 || !c) return;
        E().aplicarPunto(i, [c.lat, c.lng]);
        limpiarPrevia();
        msg('Vértice ' + (i + 1) + ' ajustado a la vía (' + fm(c.distanciaM) + '). Es un cambio pendiente: puedes deshacerlo y solo se guarda al pulsar Guardar. «Revisada» quedó desmarcada.', 'success');
    };
    $('snapCancel').onclick = () => { limpiarPrevia(); msg('Ajuste cancelado: el punto no cambió.'); };

    // ------------------------------------------------------------------ anclas (vértices que el ajuste automático no puede mover)
    $('anchorToggle').onclick = () => {
        const i = E().selected();
        if (i < 0) { msg('Elige un vértice para bloquearlo o desbloquearlo.', 'error'); return; }
        const bloqueado = E().alternarAncla(i);
        msg('Vértice ' + (i + 1) + (bloqueado ? ' bloqueado: el ajuste automático no lo moverá.' : ' desbloqueado.'), 'success');
    };

    // ------------------------------------------------------------------ ajuste de la ruta completa (comparación antes de aplicar)
    function cerrarComparacion() {
        if (comparacion) comparacion.clear();
        propuesta = null; decisiones = []; foco = -1;
        $('matchPanel').hidden = true; $('matchSummary').replaceChildren(); $('matchReview').checked = false;
    }
    const tramoOriginal = (t) => E().points().slice(t.desde, t.hasta + 1);
    const tramoPropuesto = (t) => (t.estado === 'SIN_COINCIDENCIA' ? tramoOriginal(t) : propuesta.propuesta.slice(t.propDesde, t.propHasta + 1));
    function pintarComparacion() {
        if (!capas()) return;
        comparacion.clear();
        const map = E().map;
        map.polyline(E().points(), { color: COLOR.ORIGINAL, weight: 4, dashArray: '8 6', opacity: 0.95, interactive: false }).addTo(comparacion);
        propuesta.tramos.forEach((t, k) => {
            const pts = t.estado === 'SIN_COINCIDENCIA' ? tramoOriginal(t) : tramoPropuesto(t);
            const resaltado = k === foco, rechazado = decisiones[k] === 'original';
            map.polyline(pts, { color: COLOR[t.estado] || COLOR.OK, weight: resaltado ? 9 : 5, opacity: rechazado ? 0.35 : 0.95, interactive: false }).addTo(comparacion);
        });
    }
    function resumen() {
        const m = propuesta.metricas, box = $('matchSummary');
        const filas = [
            ['Puntos', m.puntosOriginales + ' → ' + m.puntosPropuestos],
            ['Longitud', fm(m.longitudOriginalM) + ' → ' + fm(m.longitudPropuestaM) + ' (' + (m.diferenciaPct >= 0 ? '+' : '') + m.diferenciaPct.toFixed(1) + ' %)'],
            ['Desviación promedio / máxima', fm(m.desviacionMediaM) + ' / ' + fm(m.desviacionMaxM)],
            ['Recorrido ajustado', m.pctAjustado.toFixed(0) + ' %'],
            ['Segmentos dudosos', String(m.tramosDudosos)],
            ['Puntos sin coincidencia', String(m.puntosSinCoincidencia)],
            ['Cambios de vía ambiguos', String(m.cambiosDeViaAmbiguos)],
            ['Posibles giros ilegales', String(m.giroIlegales)],
        ];
        box.replaceChildren();
        filas.forEach(([k, v]) => { const d = document.createElement('div'); const a = document.createElement('span'); a.textContent = k + ': '; const b = document.createElement('b'); b.textContent = v; d.append(a, b); box.append(d); });
        const leyenda = document.createElement('p'); leyenda.className = 'hint';
        leyenda.textContent = 'Naranja discontinua = original · Verde = propuesta · Amarillo = dudoso · Rojo = sin vía (se conserva el original). ' + AVISO;
        box.append(leyenda);
    }
    function validarPropuesta(pts) {
        if (pts.length < 2 || pts.length > 10000) throw new Error('El recorrido debe tener entre 2 y 10.000 puntos (la propuesta tiene ' + pts.length + ').');
        for (let i = 0; i < pts.length; i++) {
            if (!pts[i].every(Number.isFinite)) throw new Error('La propuesta contiene coordenadas inválidas (punto ' + (i + 1) + ').');
            if (!Spevb.Geo.contiene(Spevb.cityBoundsCaja, pts[i])) throw new Error('La propuesta sale de Villavicencio (punto ' + (i + 1) + ').');
            if (i && G.dist(pts[i - 1], pts[i]) > LIMITE_SALTO_M) throw new Error('La propuesta tiene un salto de más de ' + LIMITE_SALTO_M + ' m entre los puntos ' + i + ' y ' + (i + 1) + ': revísala.');
        }
    }
    function sinDuplicados(pts) { return pts.filter((p, i) => i === 0 || p[0] !== pts[i - 1][0] || p[1] !== pts[i - 1][1]); }
    function armar(modo) {          // modo 'todo' o 'decisiones': tramos aceptados usan la propuesta; el resto conserva el original
        const out = [];
        propuesta.tramos.forEach((t, k) => {
            const aceptar = modo === 'todo' ? t.estado !== 'SIN_COINCIDENCIA' : decisiones[k] === 'aceptar' && t.estado !== 'SIN_COINCIDENCIA';
            (aceptar ? tramoPropuesto(t) : tramoOriginal(t)).forEach((p) => out.push([p[0], p[1]]));
        });
        return sinDuplicados(out);
    }
    function aplicar(modo) {
        try {
            const nuevos = armar(modo), m = propuesta.metricas;
            validarPropuesta(nuevos);
            const largo = G.longitud(nuevos), base = G.longitud(E().points());
            const cambio = base ? Math.abs(largo - base) / base * 100 : 0;
            if ((propuesta.requiereRevisionManual || cambio > LIMITE_CAMBIO_PCT) && !$('matchReview').checked) {
                msg('La longitud cambia ' + cambio.toFixed(1) + ' % (o hay tramos con problemas): marca «Revisé la comparación en el mapa» para aceptar.', 'error'); return;
            }
            const inicio = E().points()[0], fin = E().points().at(-1);
            if (G.dist(inicio, nuevos[0]) > 0.5 || G.dist(fin, nuevos.at(-1)) > 0.5) {
                if (!confirm('El ajuste cambia el inicio o el final del recorrido. ¿Confirmas moverlos?')) return;
            }
            E().aplicarPuntos(nuevos);
            cerrarComparacion();
            msg('Propuesta aplicada como cambio pendiente (' + m.puntosOriginales + ' → ' + nuevos.length + ' puntos). Puedes deshacerla; solo se guarda al pulsar «Guardar recorrido». «Revisada» quedó desmarcada y la ruta requiere revisión manual.', 'success');
        } catch (err) { msg(err.message, 'error'); }
    }
    function irAProblema(delta) {
        const lista = propuesta.problemas || [];
        if (!lista.length) { msg('No hay tramos con problemas.', 'success'); return; }
        const pos = lista.indexOf(foco);
        foco = lista[(pos + delta + lista.length * 2) % lista.length];
        const t = propuesta.tramos[foco];
        pintarComparacion();
        const caja = Spevb.Geo.limites(tramoOriginal(t));
        if (caja) E().map.fitBounds(caja, { padding: 80, maxZoom: 18 });
        msg('Problema ' + (lista.indexOf(foco) + 1) + ' de ' + lista.length + ': ' + (t.estado === 'SIN_COINCIDENCIA' ? 'sin vía para buses' : 'tramo dudoso') + (t.motivo ? ' · ' + t.motivo : '') + ' (vértices ' + (t.desde + 1) + '–' + (t.hasta + 1) + ').', 'info');
    }
    $('matchRoute').onclick = async () => {
        if (!capas() || E().busy() || !E().route()) return;
        const pts = E().points();
        cerrarComparacion(); limpiarPrevia();
        if (pts.length < 2) { msg('Necesitas al menos dos puntos para analizar el recorrido.', 'error'); return; }
        if (!conClave()) return;
        $('matchRoute').disabled = true; msg('Analizando el recorrido contra la red vial para buses… (no se modifica nada todavía)');
        try {
            const r = await post('/api/editor/vial/ajustar-ruta', { puntos: pts, anclas: E().anclas(), preservarExtremos: true, radioMetros: maxSnap() });
            propuesta = r; decisiones = r.tramos.map(() => 'pendiente');
            $('matchPanel').hidden = false; resumen(); pintarComparacion();
            const cajaRuta = Spevb.Geo.limites(pts);
            if (cajaRuta) E().map.fitBounds(cajaRuta, { padding: 40 });
            const n = (r.problemas || []).length;
            msg((r.requiereRevisionManual ? 'Requiere revisión manual. ' : '') + 'Propuesta lista: compárala en el mapa. ' + (n ? n + ' tramo(s) con problemas.' : 'Sin tramos problemáticos.') + ' Nada se aplicó ni se guardó. ' + AVISO, r.requiereRevisionManual ? 'error' : 'info');
        } catch (err) { msg(err.message, 'error'); }
        finally { $('matchRoute').disabled = false; }
    };
    $('matchAcceptAll').onclick = () => { if (propuesta) aplicar('todo'); };
    $('matchApplySel').onclick = () => { if (propuesta) aplicar('decisiones'); };
    $('matchReject').onclick = () => { cerrarComparacion(); msg('Propuesta rechazada: el recorrido no cambió.'); };
    $('matchNext').onclick = () => { if (propuesta) irAProblema(1); };
    $('matchAcceptSeg').onclick = () => { if (propuesta && foco >= 0) { decisiones[foco] = 'aceptar'; pintarComparacion(); msg('Tramo aceptado (se aplicará con «Aplicar tramos aceptados»).', 'success'); } };
    $('matchKeepSeg').onclick = () => { if (propuesta && foco >= 0) { decisiones[foco] = 'original'; pintarComparacion(); msg('Se conservará el tramo original (se aplicará con «Aplicar tramos aceptados»).', 'success'); } };
    // ------------------------------------------------------------------ auditoría de calidad (solo lectura)
    const ETIQUETA = { CORRECTA: 'Correcta', REQUIERE_REVISION: 'Requiere revisión', CRITICA: 'Crítica' };
    async function abrirRuta(r, alProblema) {
        const sel = $('editorRoute');
        sel.value = String(r.id);
        if (sel.value !== String(r.id)) { msg('La ruta ' + r.codigo + ' no está en la lista del editor.', 'error'); return; }
        if (alProblema && r.primerProblema) {
            const p = r.primerProblema;
            window.addEventListener('spevb-editor-ruta', function una() {
                window.removeEventListener('spevb-editor-ruta', una);
                setTimeout(() => { E().select(Math.min(p.desdeIndice, E().points().length - 1)); msg('Primer segmento problemático: vértices ' + (p.desdeIndice + 1) + '–' + (p.hastaIndice + 1) + ' (' + (p.motivo || p.estado) + ').', 'info'); }, 50);
            });
        }
        sel.dispatchEvent(new Event('change'));
    }
    $('auditRoutes').onclick = async () => {
        const panel = $('auditPanel'), lista = $('auditList'), resumen = $('auditSummary');
        if (!panel.hidden) { panel.hidden = true; return; }
        if (token().length < 32) { panel.hidden = false; resumen.className = 'status error'; resumen.textContent = 'Introduce la clave de administración (en «Guardar y documentar») o entra como administrador.'; lista.replaceChildren(); return; }
        panel.hidden = false; lista.replaceChildren(); resumen.className = 'status info'; resumen.textContent = 'Analizando todos los recorridos… (no se modifica nada)';
        try {
            const r = await Spevb.api('/api/editor/calidad', { headers: { 'X-Editor-Token': token() } });
            resumen.className = 'status ' + (r.criticas ? 'error' : r.requierenRevision ? 'info' : 'success');
            resumen.textContent = r.rutas.length + ' rutas: ' + r.correctas + ' correctas · ' + r.requierenRevision + ' requieren revisión · ' + r.criticas + ' críticas. ' + (r.aviso || AVISO);
            r.rutas.forEach((c) => {
                const fila = document.createElement('div'); fila.className = 'stop-row audit-row'; fila.dataset.estado = c.estado;
                const t = document.createElement('span'); t.className = 'stop-name';
                t.textContent = c.codigo + ' · ' + c.nombre + ' — ' + ETIQUETA[c.estado] + ' · ' + c.puntos + ' pts · ' + fm(c.longitudM) + ' · ' + c.pctSobreVias.toFixed(0) + ' % sobre vías · desv. ' + fm(c.desviacionMediaM) + ' / ' + fm(c.desviacionMaxM) +
                    ' · sospechosos ' + c.segmentosSospechosos + (c.sentidoPosiblementeIncorrecto ? ' · posible sentido incorrecto' : '');
                const abrir = document.createElement('button'); abrir.type = 'button'; abrir.className = 'secondary stop-btn'; abrir.textContent = 'Abrir';
                abrir.onclick = () => abrirRuta(c, false);
                fila.append(t, abrir);
                if (c.primerProblema) {
                    const ir = document.createElement('button'); ir.type = 'button'; ir.className = 'secondary stop-btn'; ir.textContent = 'Ir al primer problema';
                    ir.onclick = () => abrirRuta(c, true);
                    fila.append(ir);
                }
                lista.append(fila);
            });
        } catch (err) { resumen.className = 'status error'; resumen.textContent = err.message; }
    };
    window.addEventListener('spevb-editor-ruta', () => { cerrarComparacion(); limpiarPrevia(); msg(''); });
    window.SpevbAjuste = { propuesta: () => propuesta, validarPropuesta };
})();
