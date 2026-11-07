'use strict';
/* Geometría pura de SEVB (sin DOM, sin Leaflet, sin Google). La usan los adaptadores y los módulos de la aplicación.
   Convención ÚNICA dentro de la aplicación: un punto es [lat, lng] (arreglo de dos números). GeoJSON y la API del servidor usan
   [lng, lat]; Google usa {lat, lng}. Todas las conversiones pasan por este archivo para que haya un solo lugar donde equivocarse
   y un solo lugar donde probarlo (e2e/mapa_geo_e2e.py).
   Una «caja» (bounds) es un objeto plano { s, w, n, e } (sur, oeste, norte, este) en grados. */
(() => {
    const RAD = Math.PI / 180;
    const R = 6371000;           // mismo radio que usa Leaflet (L.CRS.Earth): las distancias coinciden con las del código anterior

    const esNumero = (v) => typeof v === 'number' && Number.isFinite(v);

    /** Normaliza un punto: acepta [lat, lng], {lat, lng} y los LatLng de Google (lat()/lng()). Devuelve [lat, lng] o null. */
    function ll(p) {
        if (!p) return null;
        if (Array.isArray(p)) return esNumero(p[0]) && esNumero(p[1]) ? [p[0], p[1]] : null;
        const lat = typeof p.lat === 'function' ? p.lat() : p.lat, lng = typeof p.lng === 'function' ? p.lng() : p.lng;
        return esNumero(lat) && esNumero(lng) ? [lat, lng] : null;
    }
    /** Distancia en metros (haversine, radio de 6.371 km: igual que L.LatLng.distanceTo). */
    function distancia(a, b) {
        const p = ll(a), q = ll(b);
        if (!p || !q) return NaN;
        const dLat = (q[0] - p[0]) * RAD, dLng = (q[1] - p[1]) * RAD;
        const s = Math.sin(dLat / 2) ** 2 + Math.cos(p[0] * RAD) * Math.cos(q[0] * RAD) * Math.sin(dLng / 2) ** 2;
        return 2 * R * Math.asin(Math.min(1, Math.sqrt(s)));
    }
    /** Longitud en metros de una línea [[lat, lng], ...]. */
    function longitud(puntos) {
        let m = 0;
        for (let i = 1; i < puntos.length; i++) m += distancia(puntos[i - 1], puntos[i]);
        return m;
    }

    // ------------------------------------------------------------ conversiones GeoJSON ⇄ [lat, lng] ⇄ Google
    /** [[lng, lat], ...] (GeoJSON / API) → [[lat, lng], ...]. No redondea ni reordena: conserva orden y precisión. */
    const desdeGeoJSON = (coords) => (coords || []).map((c) => [c[1], c[0]]);
    /** [[lat, lng], ...] → [[lng, lat], ...] (para guardar o exportar). */
    const aGeoJSON = (puntos) => (puntos || []).map((p) => [p[1], p[0]]);
    /** [lat, lng] → {lat, lng} (literal de Google Maps). */
    const aGoogle = (p) => { const q = ll(p); return q ? { lat: q[0], lng: q[1] } : null; };
    /** LatLng o literal de Google → [lat, lng]. */
    const deGoogle = (g) => ll(g);

    // ------------------------------------------------------------ cajas
    /** Caja mínima que contiene los puntos; null si no hay ninguno válido. */
    function limites(puntos) {
        let b = null;
        (puntos || []).forEach((p) => { b = extender(b, p); });
        return b;
    }
    function extender(b, p) {
        const q = ll(p);
        if (!q) return b;
        if (!b) return { s: q[0], w: q[1], n: q[0], e: q[1] };
        return { s: Math.min(b.s, q[0]), w: Math.min(b.w, q[1]), n: Math.max(b.n, q[0]), e: Math.max(b.e, q[1]) };
    }
    function unir(a, b) {
        if (!a) return b || null;
        if (!b) return a;
        return { s: Math.min(a.s, b.s), w: Math.min(a.w, b.w), n: Math.max(a.n, b.n), e: Math.max(a.e, b.e) };
    }
    const esValida = (b) => !!b && esNumero(b.s) && esNumero(b.w) && esNumero(b.n) && esNumero(b.e) && b.s <= b.n && b.w <= b.e;
    function contiene(b, p) {
        const q = ll(p);
        return !!(esValida(b) && q && q[0] >= b.s && q[0] <= b.n && q[1] >= b.w && q[1] <= b.e);
    }
    const centro = (b) => (esValida(b) ? [(b.s + b.n) / 2, (b.w + b.e) / 2] : null);
    /** Caja desde el formato antiguo de la aplicación [[sur, oeste], [norte, este]]. */
    const desdePares = (pares) => ({ s: pares[0][0], w: pares[0][1], n: pares[1][0], e: pares[1][1] });
    /** [sur, oeste, norte, este] (formato que usan las llamadas a /api/paraderos y similares). */
    const aLista = (b) => [b.s, b.w, b.n, b.e];

    // ------------------------------------------------------------ proyección local sobre un segmento
    /** Punto del segmento a–b más cercano a p (aproximación plana local, suficiente a escala de ciudad).
        Devuelve { punto: [lat, lng], t: 0..1, distanciaM }. Sustituye a L.LineUtil.closestPointOnSegment + map.project. */
    function proyectarEnSegmento(p, a, b) {
        const P = ll(p), A = ll(a), B = ll(b);
        if (!P || !A || !B) return null;
        const k = Math.cos(P[0] * RAD);
        const ax = (A[1] - P[1]) * k, ay = A[0] - P[0], bx = (B[1] - P[1]) * k, by = B[0] - P[0];
        const vx = bx - ax, vy = by - ay, len2 = vx * vx + vy * vy;
        let t = len2 > 0 ? (-(ax * vx + ay * vy)) / len2 : 0;
        t = Math.max(0, Math.min(1, t));
        const punto = [A[0] + (B[0] - A[0]) * t, A[1] + (B[1] - A[1]) * t];
        return { punto, t, distanciaM: distancia(P, punto) };
    }

    // ------------------------------------------------------------ Web Mercator (misma proyección de Leaflet y de Google)
    /** [lat, lng] → coordenadas de mundo normalizadas (0..1). */
    function aMundo(p) {
        const q = ll(p), sen = Math.sin(Math.max(-0.9999, Math.min(0.9999, q[0] * RAD)));
        return [(q[1] + 180) / 360, 0.5 - Math.log((1 + sen) / (1 - sen)) / (4 * Math.PI)];
    }
    function deMundo(x, y) {
        const n = Math.PI - 2 * Math.PI * y;
        return [(180 / Math.PI) * Math.atan(0.5 * (Math.exp(n) - Math.exp(-n))), x * 360 - 180];
    }
    /** Centro que hay que dar al mapa para que `p` quede en el centro del ÁREA LIBRE (el contenedor menos los márgenes `pad`
        {top,left,bottom,right} que tapan tarjetas y paneles). Sirve para «seguirme» sin dejar la posición debajo de una tarjeta. */
    function centroConMargen(p, zoom, pad) {
        const e = 256 * 2 ** zoom, w = aMundo(p);
        const dx = ((pad && pad.left || 0) - (pad && pad.right || 0)) / 2, dy = ((pad && pad.top || 0) - (pad && pad.bottom || 0)) / 2;
        return deMundo(w[0] - dx / e, w[1] - dy / e);
    }

    window.Spevb = Object.assign(window.Spevb || {}, {
        Geo: { ll, distancia, longitud, desdeGeoJSON, aGeoJSON, aGoogle, deGoogle, limites, extender, unir, esValida, contiene, centro, desdePares, aLista, proyectarEnSegmento, aMundo, deMundo, centroConMargen, R },
    });
})();
