'use strict';
/* Paraderos de bus del sistema en el mapa principal (se cargan por zona al acercar). Se muestran con el interruptor «Paraderos». */
(() => {
    const E = window.SpevbExplorer;
    if (!E || !E.map) return;
    const map = E.map, $ = (id) => document.getElementById(id);
    const MIN_ZOOM = 14;
    const capa = L.layerGroup();
    let secuencia = 0, timer = null;
    const activo = () => !$('showStops') || $('showStops').checked;
    async function cargar() {
        const mi = ++secuencia;
        if (!activo() || map.getZoom() < MIN_ZOOM) { capa.clearLayers(); return; }
        const b = map.getBounds();
        try {
            const q = new URLSearchParams({ sur: b.getSouth(), oeste: b.getWest(), norte: b.getNorth(), este: b.getEast(), limite: 1500 });
            const r = await fetch('/api/paraderos?' + q);
            if (!r.ok) return;
            const datos = await r.json();
            if (mi !== secuencia) return;
            capa.clearLayers();
            datos.forEach((p) => L.circleMarker([p.lat, p.lng], { radius: 6, color: '#fff', weight: 2, fillColor: '#1e5fbf', fillOpacity: 1 })
                .bindTooltip(Spevb.text('Paradero · ' + p.nombre)).addTo(capa));
        } catch { /* sin conexión: simplemente no se ven */ }
    }
    capa.addTo(map);
    map.on('moveend', () => { clearTimeout(timer); timer = setTimeout(cargar, 300); });
    if ($('showStops')) $('showStops').addEventListener('change', cargar);
    cargar();
})();
