'use strict';
/* Paraderos de bus del sistema en el mapa principal. YA NO se muestran por defecto: los que importan (subir, transbordo, bajar) los dibuja el
   itinerario y los de una ruta salen al pedirlos en sus detalles. Esta capa general solo funciona si la persona la activa en el menú
   («Mostrar paraderos del sistema», ajuste paraderosMapa). Consulta por zona al acercar (zoom ≥ 14) con debounce, cancelación de la
   petición anterior, caché breve por cuadrícula y actualización por diferencias: los que siguen visibles no se borran ni se recrean. */
(() => {
    // El mapa del explorador se crea de forma asíncrona: este módulo arranca cuando el hito «explorador» está publicado.
    Spevb.esperar('explorador').then((E) => {
    if (!E || !E.map) return;
    const map = E.map;
    const MIN_ZOOM = 14, LIMITE = 300, CELDA = 0.01 /* ≈ 1,1 km */, TTL_MS = 60000;
    const capa = map.group();
    const visibles = new Map();     // id → marcador
    const cache = new Map();        // zona → { at, datos }
    let controller = null, timer = null, secuencia = 0;
    const activo = () => !!(window.Spevb && Spevb.Ajustes && Spevb.Ajustes.get('paraderosMapa'));
    const limpiar = () => { capa.clear(); visibles.clear(); };
    // La zona se ensancha a la cuadrícula: moverse un poco dentro de ella reutiliza la respuesta guardada.
    const zona = (b) => [Math.floor(b.s / CELDA), Math.floor(b.w / CELDA), Math.ceil(b.n / CELDA), Math.ceil(b.e / CELDA)].map((v) => v * CELDA);

    function pintar(datos) {
        const vigentes = new Set(datos.map((p) => p.id));
        visibles.forEach((m, id) => { if (!vigentes.has(id)) { m.remove(); visibles.delete(id); } });
        datos.forEach((p) => {
            if (visibles.has(p.id)) return;
            visibles.set(p.id, map.dot([p.lat, p.lng], { radius: 5, color: '#fff', weight: 1.5, fillColor: '#4a73b3', fillOpacity: 0.85, tooltip: Spevb.text('Paradero · ' + p.nombre) }).addTo(capa));
        });
    }

    async function cargar() {
        const mi = ++secuencia;
        if (controller) { controller.abort(); controller = null; }
        if (!activo() || map.getZoom() < MIN_ZOOM) { limpiar(); return; }
        const caja = map.getBounds();
        if (!caja) return;
        const [sur, oeste, norte, este] = zona(caja);
        const clave = [sur, oeste, norte, este].map((v) => v.toFixed(2)).join(',');
        const guardado = cache.get(clave);
        if (guardado && Date.now() - guardado.at < TTL_MS) { pintar(guardado.datos); return; }
        controller = new AbortController();
        try {
            const r = await fetch('/api/paraderos?' + new URLSearchParams({ sur, oeste, norte, este, limite: LIMITE }), { signal: controller.signal });
            if (!r.ok) return;
            const datos = await r.json();
            if (mi !== secuencia) return;
            cache.set(clave, { at: Date.now(), datos });
            if (cache.size > 20) cache.delete(cache.keys().next().value);
            pintar(datos);
        } catch { /* sin conexión o petición cancelada: simplemente no se ven */ }
    }

    capa.addTo(map);
    map.on('moveend', () => { if (!activo() && !visibles.size) return; clearTimeout(timer); timer = setTimeout(cargar, 300); });
    window.addEventListener('spevb-ajustes', (e) => { if (e.detail && e.detail.clave === 'paraderosMapa') cargar(); });
    if (activo()) cargar();
    });
})();
