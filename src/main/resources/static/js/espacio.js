'use strict';
/* «Mi espacio»: historial de viajes y viajes guardados. Usa Spevb.Datos: funciona en este dispositivo y, con cuenta,
   se sincroniza con el otro equipo. El historial solo guarda origen, destino y rutas (nunca el trayecto del GPS). */
(() => {
    const $ = (id) => document.getElementById(id);
    const S = window.Spevb;
    if (!S || !S.Datos || !$('histList')) return;
    const P = () => S.Planner;
    const nuevoId = () => Date.now().toString(36) + Math.random().toString(36).slice(2, 6);
    const hace = (t) => {
        const min = Math.max(0, Math.round((Date.now() - t) / 60000));
        if (min < 1) return 'ahora'; if (min < 60) return 'hace ' + min + ' min';
        if (min < 1440) return 'hace ' + Math.round(min / 60) + ' h'; return 'hace ' + Math.round(min / 1440) + ' d';
    };
    const cerca = (p, q) => Math.abs(p.lat - q.lat) < 0.00025 && Math.abs(p.lng - q.lng) < 0.00025;
    const mismoViaje = (x, a, b, sv) => cerca(x.a, a) && cerca(x.b, b) && !!x.soloValidadas === !!sv;
    const etiqueta = (a, b) => (a.nombre || 'A') + ' → ' + (b.nombre || 'B');
    const punto = (p) => ({ lat: p.lat, lng: p.lng, nombre: (p.nombre || '').slice(0, 80) });
    const comoPunto = (p) => ({ id: null, lat: p.lat, lng: p.lng, nombre: p.nombre || p.lat.toFixed(5) + ', ' + p.lng.toFixed(5), direccion: null, fuente: 'Guardado', confirmado: true });

    // ------------------------------------------------------------------ historial
    window.addEventListener('spevb-viaje', (e) => {
        const { a, b, soloValidadas, codigos } = e.detail;
        const lista = S.Datos.leer('historial').filter((x) => !mismoViaje(x, a, b, soloValidadas));
        lista.unshift({ id: nuevoId(), a: punto(a), b: punto(b), soloValidadas: !!soloValidadas, t: Date.now(), codigos: (codigos || []).slice(0, 3) });
        S.Datos.escribir('historial', lista);
    });

    function repetir(item) {
        $('onlyValidatedTrips').checked = !!item.soloValidadas;
        P().poner('origin', comoPunto(item.a));
        P().poner('destination', comoPunto(item.b));
        setTimeout(() => P().buscar(), 80);
        window.dispatchEvent(new CustomEvent('spevb-focus-map'));
    }

    // ------------------------------------------------------------------ guardados
    function guardarViaje(nombre) {
        const { origen, destino } = P().puntos();
        if (!origen || !destino) return;
        const sv = P().soloValidadas();
        const lista = S.Datos.leer('viajes').filter((x) => !mismoViaje(x, origen, destino, sv));
        if (lista.length >= 50) return S.toast('Máximo 50 viajes guardados. Quita alguno para añadir otro.', { tipo: 'info', ms: 5000 });
        lista.unshift({ id: nuevoId(), nombre: nombre.trim().slice(0, 60) || 'Viaje guardado', a: punto(origen), b: punto(destino), soloValidadas: sv, t: Date.now() });
        S.Datos.escribir('viajes', lista);
        S.toast('Viaje guardado' + (S.Cuenta.usuario ? ' en tu cuenta.' : ' en este dispositivo. Entra a tu cuenta para verlo también en tu otro equipo.'), { tipo: 'success', ms: 4500, clave: 'viaje-guardado' });
    }
    const abrirNombre = () => {
        const { origen, destino } = P().puntos();
        if (!origen || !destino) return S.toast('Primero elige A y B.', { tipo: 'info', ms: 3000 });
        $('nameRoute').textContent = etiqueta(origen, destino);
        $('nameInput').value = etiqueta(origen, destino).slice(0, 60);
        $('nameModal').hidden = false; document.body.classList.add('modal-open');
        setTimeout(() => { $('nameInput').focus(); $('nameInput').select(); }, 50);
    };
    const cerrarNombre = () => { $('nameModal').hidden = true; if (![...document.querySelectorAll('.modal-backdrop')].some((m) => !m.hidden)) document.body.classList.remove('modal-open'); };
    $('saveTrip').onclick = abrirNombre;
    $('nameCancel').onclick = cerrarNombre;
    $('nameForm').onsubmit = (e) => { e.preventDefault(); guardarViaje($('nameInput').value); cerrarNombre(); };

    // ------------------------------------------------------------------ pantalla
    function fila(item, { guardado }) {
        const f = document.createElement('div');
        f.className = 'fav-row esp-row';
        const txt = document.createElement('button');
        txt.type = 'button';
        txt.className = 'esp-main';
        const t1 = document.createElement('strong');
        t1.textContent = guardado ? item.nombre : etiqueta(item.a, item.b);
        const t2 = document.createElement('small');
        t2.textContent = (guardado ? etiqueta(item.a, item.b) : (item.codigos && item.codigos.length ? 'Ruta ' + item.codigos.join(' + ') + ' · ' : '') + hace(item.t));
        txt.append(t1, t2);
        txt.title = 'Repetir este viaje';
        txt.onclick = () => repetir(item);
        f.append(txt);
        const mk = (texto, etiq, fn, cls = '') => { const b = document.createElement('button'); b.type = 'button'; b.className = 'fav-btn ' + cls; b.textContent = texto; b.setAttribute('aria-label', etiq); b.title = etiq; b.onclick = fn; return b; };
        if (!guardado) f.append(mk('★', 'Guardar este viaje', () => { P().poner('origin', comoPunto(item.a)); P().poner('destination', comoPunto(item.b)); setTimeout(abrirNombre, 80); }));
        f.append(mk('✕', 'Quitar', () => S.Datos.escribir(guardado ? 'viajes' : 'historial', S.Datos.leer(guardado ? 'viajes' : 'historial').filter((x) => x.id !== item.id)), 'fav-del'));
        return f;
    }
    function pintar() {
        const guardados = S.Datos.leer('viajes'), historial = S.Datos.leer('historial');
        $('savedList').replaceChildren(...(guardados.length ? guardados.map((x) => fila(x, { guardado: true }))
            : [Object.assign(document.createElement('p'), { className: 'fav-empty', textContent: 'Aún no guardas viajes. Haz una búsqueda y toca «Guardar viaje».' })]));
        $('histList').replaceChildren(...(historial.length ? historial.slice(0, 20).map((x) => fila(x, { guardado: false }))
            : [Object.assign(document.createElement('p'), { className: 'fav-empty', textContent: 'Tus búsquedas aparecerán aquí.' })]));
        $('espCount').textContent = guardados.length ? String(guardados.length) : '';
        $('histClear').hidden = !historial.length;
        $('espHint').hidden = !!S.Cuenta.usuario;
    }
    $('histClear').onclick = () => { if (confirm('¿Borrar todo el historial de viajes?')) S.Datos.escribir('historial', []); };
    window.addEventListener('spevb-datos', (e) => { if (e.detail.clave === 'historial' || e.detail.clave === 'viajes') pintar(); });
    window.addEventListener('spevb-cuenta', pintar);
    pintar();
})();
