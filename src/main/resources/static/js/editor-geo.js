'use strict';
/* Geometría pura del editor de recorridos (sin DOM ni proveedor de mapa: sirve igual con Leaflet y con Google): selección del vértice o
   segmento más cercano, distancias y simplificación por zoom. Pensada para rutas de hasta 10.000 puntos: las búsquedas son O(n) y SOLO
   se ejecutan al hacer clic (nunca en cada movimiento del ratón). `proj` convierte [lat, lng] en [x, y] en píxeles del contenedor
   (en editor.js: map.toPoint, de la capa de mapa). */
(() => {
    const RAD = Math.PI / 180, R = 6371008.8;

    /** Distancia en metros entre dos puntos [lat, lng] (haversine). */
    function dist(a, b) {
        const dLat = (b[0] - a[0]) * RAD, dLng = (b[1] - a[1]) * RAD;
        const s = Math.sin(dLat / 2) ** 2 + Math.cos(a[0] * RAD) * Math.cos(b[0] * RAD) * Math.sin(dLng / 2) ** 2;
        return 2 * R * Math.asin(Math.sqrt(s));
    }

    function longitud(points) {
        let m = 0;
        for (let i = 1; i < points.length; i++) m += dist(points[i - 1], points[i]);
        return m;
    }

    /** Vértice más cercano a `p` en píxeles. Devuelve { index, distPx } o null si no hay puntos. */
    function verticeCercano(points, p, proj) {
        if (!points.length) return null;
        const q = proj(p);
        let best = -1, bestD = Infinity;
        for (let i = 0; i < points.length; i++) {
            const v = proj(points[i]), dx = v[0] - q[0], dy = v[1] - q[1], d = dx * dx + dy * dy;
            if (d < bestD) { bestD = d; best = i; }
        }
        return { index: best, distPx: Math.sqrt(bestD) };
    }

    /** Segmento más cercano a `p` en píxeles. Devuelve { index (i: entre i e i+1), t (0..1), punto [lat, lng], distPx } o null. */
    function segmentoCercano(points, p, proj) {
        if (points.length < 2) return null;
        const q = proj(p);
        let a = proj(points[0]), best = null, bestD = Infinity;
        for (let i = 0; i < points.length - 1; i++) {
            const b = proj(points[i + 1]);
            const vx = b[0] - a[0], vy = b[1] - a[1], len2 = vx * vx + vy * vy;
            let t = len2 > 0 ? ((q[0] - a[0]) * vx + (q[1] - a[1]) * vy) / len2 : 0;
            t = Math.max(0, Math.min(1, t));
            const dx = a[0] + vx * t - q[0], dy = a[1] + vy * t - q[1], d = dx * dx + dy * dy;
            if (d < bestD) { bestD = d; best = { index: i, t }; }
            a = b;
        }
        const A = points[best.index], B = points[best.index + 1];
        return { index: best.index, t: best.t, punto: [A[0] + (B[0] - A[0]) * best.t, A[1] + (B[1] - A[1]) * best.t], distPx: Math.sqrt(bestD) };
    }

    /** Qué se seleccionó con un clic: el vértice (si está a ≤ tolPx), si no el segmento (si está a ≤ tolPx), si no nada. */
    function seleccionar(points, p, proj, tolPx = 22) {
        const v = verticeCercano(points, p, proj);
        if (v && v.distPx <= tolPx) return { tipo: 'vertice', index: v.index, distPx: v.distPx };
        const s = segmentoCercano(points, p, proj);
        if (s && s.distPx <= tolPx) return { tipo: 'segmento', ...s };
        return null;
    }

    /** Datos del vértice i para mostrar: número, total, coordenadas y distancias al anterior y al siguiente (m). */
    function infoVertice(points, i) {
        if (i < 0 || i >= points.length) return null;
        return {
            numero: i + 1, total: points.length, lat: points[i][0], lng: points[i][1],
            anterior: i > 0 ? dist(points[i - 1], points[i]) : null,
            siguiente: i < points.length - 1 ? dist(points[i], points[i + 1]) : null,
        };
    }

    /** Índices a mostrar alrededor de i (±radio), recortados a la ruta. */
    function vecinos(i, n, radio = 5) {
        if (i < 0 || i >= n) return [];
        const out = [];
        for (let k = Math.max(0, i - radio); k <= Math.min(n - 1, i + radio); k++) out.push(k);
        return out;
    }

    /** Navegación: devuelve el nuevo índice (acotado) a partir del actual (-1 = ninguno). */
    function mover(actual, n, accion) {
        if (!n) return -1;
        const base = actual < 0 ? 0 : actual;
        const f = {
            primero: () => 0, ultimo: () => n - 1,
            anterior: () => (actual < 0 ? n - 1 : base - 1), siguiente: () => (actual < 0 ? 0 : base + 1),
            menos10: () => (actual < 0 ? n - 1 : base - 10), mas10: () => (actual < 0 ? 0 : base + 10),
        }[accion];
        return f ? Math.max(0, Math.min(n - 1, f())) : actual;
    }

    /** Douglas-Peucker iterativo sobre píxeles: devuelve los índices (ordenados) que se conservan con tolerancia tolPx.
     *  `obligatorios` (Set de índices) siempre se conservan. Para 10.000 puntos es O(n log n) típico. */
    function simplificar(points, proj, tolPx, obligatorios = new Set()) {
        const n = points.length;
        if (n <= 2) return points.map((_, i) => i);
        const xy = points.map(proj), keep = new Uint8Array(n);
        keep[0] = keep[n - 1] = 1;
        obligatorios.forEach((i) => { if (i >= 0 && i < n) keep[i] = 1; });
        const pila = [[0, n - 1]];
        while (pila.length) {
            const [s, e] = pila.pop();
            if (e <= s + 1) continue;
            let maxD = -1, idx = -1;
            const ax = xy[s][0], ay = xy[s][1], vx = xy[e][0] - ax, vy = xy[e][1] - ay, len2 = vx * vx + vy * vy;
            for (let i = s + 1; i < e; i++) {
                let t = len2 > 0 ? ((xy[i][0] - ax) * vx + (xy[i][1] - ay) * vy) / len2 : 0;
                t = Math.max(0, Math.min(1, t));
                const dx = ax + vx * t - xy[i][0], dy = ay + vy * t - xy[i][1], d = dx * dx + dy * dy;
                if (d > maxD) { maxD = d; idx = i; }
            }
            // Un obligatorio dentro del rango también parte el tramo (se conserva y se sigue simplificando a cada lado).
            let corte = -1;
            for (let i = s + 1; i < e; i++) if (keep[i] === 1 && obligatorios.has(i)) { corte = i; break; }
            if (corte >= 0) { pila.push([s, corte], [corte, e]); continue; }
            if (maxD > tolPx * tolPx) { keep[idx] = 1; pila.push([s, idx], [idx, e]); }
        }
        const out = [];
        for (let i = 0; i < n; i++) if (keep[i]) out.push(i);
        return out;
    }

    /** Recorta una lista de índices a un máximo (muestreo uniforme que conserva primero y último). */
    function limitar(indices, max) {
        if (indices.length <= max) return indices;
        const out = [];
        for (let k = 0; k < max; k++) out.push(indices[Math.round((k * (indices.length - 1)) / (max - 1))]);
        return [...new Set(out)];
    }

    /** Inserta un punto en el segmento `index` (entre index e index+1) y devuelve el índice nuevo; no muta si no es válido. */
    function insertar(points, index, punto) {
        if (index < 0 || index >= points.length - 1) return -1;
        points.splice(index + 1, 0, [punto[0], punto[1]]);
        return index + 1;
    }

    window.Spevb = window.Spevb || {};
    window.Spevb.EditorGeo = { dist, longitud, verticeCercano, segmentoCercano, seleccionar, infoVertice, vecinos, mover, simplificar, limitar, insertar };
})();
