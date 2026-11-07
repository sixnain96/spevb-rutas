'use strict';
/* Puentes peatonales. Ya NO se dibujan todos en el mapa: siguen formando parte de la red de calles que usa la caminata (backend) y aquí solo se
   destacan los que una caminata calculada realmente cruza (Spevb.Puentes.alRecorrer), con el aviso «Cruza el puente peatonal …».
   Un puente nunca es un punto de subir ni de bajar del bus. Los datos se piden una vez, cuando hacen falta, y se reutilizan 10 minutos.
   «Cruza» significa que el trayecto recorre el puente de forma continua: un tramo seguido de puntos a ≤ 12 m del puente que cubre al menos el
   70 % de su largo. Pasar por debajo, al lado o rodearlo por los extremos no cuenta. */
(() => {
    const S = window.Spevb || (window.Spevb = {});
    const TTL_MS = 10 * 60 * 1000, PASO_M = 5, CERCA_M = 12, COBERTURA = 0.7;
    let guardado = null, pendiente = null;
    const icono = L.divIcon({
        className: 'puente-icono-wrap', iconSize: [28, 28], iconAnchor: [14, 14],
        html: '<span class="puente-icono" aria-hidden="true"><svg viewBox="0 0 24 24" width="16" height="16"><path d="M2 17h20M4 17v-5a8 8 0 0 1 16 0v5M8 17v-4M12 17V9M16 17v-4" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/></svg></span>',
    });

    function cargar() {
        if (guardado && Date.now() - guardado.at < TTL_MS) return Promise.resolve(guardado.lista);
        if (!pendiente) {
            pendiente = fetch('/api/puentes').then((r) => (r.ok ? r.json() : [])).then((lista) => { guardado = { at: Date.now(), lista }; return lista; })
                .catch(() => (guardado ? guardado.lista : [])).finally(() => { pendiente = null; });
        }
        return pendiente;
    }

    // ---- geometría en metros (plano local): suficiente para decidir si una caminata recorre un puente
    const M = 111320;
    const proyectar = (lat, lng, lat0) => [lng * M * Math.cos((lat0 * Math.PI) / 180), lat * M];
    /** Punto de la polilínea más cercano a p: distancia y posición (metros desde su inicio). */
    function cercano(p, xy, acum) {
        let mejor = { d: Infinity, s: 0 };
        for (let i = 1; i < xy.length; i++) {
            const ax = xy[i - 1][0], ay = xy[i - 1][1], dx = xy[i][0] - ax, dy = xy[i][1] - ay, l2 = dx * dx + dy * dy;
            const t = l2 ? Math.max(0, Math.min(1, ((p[0] - ax) * dx + (p[1] - ay) * dy) / l2)) : 0;
            const d = Math.hypot(p[0] - (ax + t * dx), p[1] - (ay + t * dy));
            if (d < mejor.d) mejor = { d, s: acum[i - 1] + t * Math.sqrt(l2) };
        }
        return mejor;
    }
    /** ¿El trayecto {@code xyPath} recorre el puente {@code bxy} de forma continua? */
    function cruza(xyPath, bxy) {
        const acum = [0];
        for (let i = 1; i < bxy.length; i++) acum.push(acum[i - 1] + Math.hypot(bxy[i][0] - bxy[i - 1][0], bxy[i][1] - bxy[i - 1][1]));
        const largo = acum[acum.length - 1];
        if (largo < 5) return false;
        const margen = CERCA_M + PASO_M;
        const minx = Math.min(...bxy.map((p) => p[0])) - margen, maxx = Math.max(...bxy.map((p) => p[0])) + margen;
        const miny = Math.min(...bxy.map((p) => p[1])) - margen, maxy = Math.max(...bxy.map((p) => p[1])) + margen;
        let tramo = null;                                                    // { min, max }: posición sobre el puente del tramo seguido
        const cerrar = () => { const ok = !!tramo && tramo.max - tramo.min >= COBERTURA * largo; tramo = null; return ok; };
        for (let i = 1; i < xyPath.length; i++) {
            const a = xyPath[i - 1], b = xyPath[i];
            if (Math.max(a[0], b[0]) < minx || Math.min(a[0], b[0]) > maxx || Math.max(a[1], b[1]) < miny || Math.min(a[1], b[1]) > maxy) {
                if (cerrar()) return true;
                continue;
            }
            const n = Math.max(1, Math.ceil(Math.hypot(b[0] - a[0], b[1] - a[1]) / PASO_M));
            for (let k = 0; k <= n; k++) {
                const c = cercano([a[0] + ((b[0] - a[0]) * k) / n, a[1] + ((b[1] - a[1]) * k) / n], bxy, acum);
                if (c.d <= CERCA_M) tramo = tramo ? { min: Math.min(tramo.min, c.s), max: Math.max(tramo.max, c.s) } : { min: c.s, max: c.s };
                else if (cerrar()) return true;
            }
        }
        return cerrar();
    }
    /** Puentes que la caminata {@code path} ([[lat, lng], …]) realmente recorre. */
    function usados(path, lista) {
        if (!Array.isArray(path) || path.length < 2 || !Array.isArray(lista)) return [];
        const lat0 = path[0][0], xy = path.map((p) => proyectar(p[0], p[1], lat0));
        return lista.filter((b) => {
            const cs = b.trazo && b.trazo.coordinates;
            return !!cs && cs.length >= 2 && cruza(xy, cs.map((c) => proyectar(c[1], c[0], lat0)));
        });
    }

    function dibujar(b, layer) {
        if (layer.getLayers().some((l) => l._puenteId === b.id)) return;
        const lls = b.trazo.coordinates.map((c) => [c[1], c[0]]);
        const texto = Spevb.text('Puente peatonal · ' + b.nombre + ' (tu caminata lo cruza)');
        const borde = L.polyline(lls, { color: '#fff', weight: 9, opacity: 0.95, interactive: false, lineCap: 'round' }).addTo(layer);
        borde._puenteId = b.id;
        L.polyline(lls, { color: '#1e5fbf', weight: 5, opacity: 0.95, lineCap: 'round' }).bindTooltip(texto, { sticky: true }).addTo(layer);
        L.marker(lls[Math.floor(lls.length / 2)], { icon: icono, keyboard: false }).bindTooltip(texto).addTo(layer);
    }

    /** Destaca en {@code layer} los puentes que cruza la caminata y devuelve la lista (vacía si ninguno). */
    async function alRecorrer(path, layer) {
        const lista = usados(path, await cargar());
        if (layer) lista.forEach((b) => dibujar(b, layer));
        return lista;
    }

    /** Puentes con algún punto a ≤ {@code radio} m de alguno de los {@code puntos} ([lat, lng]). */
    async function cercanos(puntos, radio) {
        const lista = await cargar();
        if (!Array.isArray(lista) || !puntos || !puntos.length) return [];
        return lista.filter((b) => {
            const cs = b.trazo && b.trazo.coordinates;
            return !!cs && cs.length >= 2 && puntos.some((p) => {
                const kx = Math.cos(p[0] * Math.PI / 180) * M, ky = 110574;
                return cs.some((c) => Math.hypot((c[0] - p[1]) * kx, (c[1] - p[0]) * ky) <= radio);
            });
        });
    }
    /** Símbolo del puente con su nombre (sin dibujar la línea): para los que están cerca de la caminata pero no se cruzan. */
    function marcar(b, layer) {
        if (layer.getLayers().some((l) => l._puenteIconoId === b.id || l._puenteId === b.id)) return;
        const lls = b.trazo.coordinates.map((c) => [c[1], c[0]]);
        const m = L.marker(lls[Math.floor(lls.length / 2)], { icon: icono, keyboard: false, zIndexOffset: 500 }).bindTooltip(Spevb.text('Puente peatonal · ' + b.nombre + ' (cerca de tu caminata)')).addTo(layer);
        m._puenteIconoId = b.id;
    }

    S.Puentes = { alRecorrer, usados, cargar, cercanos, marcar };
})();
