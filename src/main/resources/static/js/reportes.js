'use strict';
(() => {
    const $ = (id) => document.getElementById(id);
    const NOMBRES = { RUTA: 'Ruta', CAMINATA: 'Caminata', DIRECCION: 'Dirección', APP: 'Aplicación', SUGERENCIA: 'Sugerencia', OTRO: 'Otro' };
    const status = (m, t = 'info') => { $('repStatus').textContent = m; $('repStatus').className = 'status ' + t; };
    const headers = () => ({ 'Content-Type': 'application/json', 'X-Editor-Token': $('repToken').value });
    const mapa = (lat, lng) => 'https://www.openstreetmap.org/?mlat=' + lat + '&mlon=' + lng + '#map=17/' + lat + '/' + lng;
    async function cargar() {
        if ($('repToken').value.length < 32) return status('Introduce la clave de administración (32 caracteres o más).', 'error');
        status('Cargando…');
        try {
            const datos = await Spevb.api('/api/editor/reportes?estado=' + $('repEstado').value, { headers: headers() });
            const lista = $('repLista');
            lista.replaceChildren();
            status(datos.length ? datos.length + ' reporte(s).' : 'No hay reportes en este estado.', datos.length ? 'success' : 'info');
            datos.forEach((r) => {
                const tarjeta = document.createElement('article');
                tarjeta.className = 'catalog-row';
                const cab = document.createElement('strong');
                cab.textContent = (NOMBRES[r.categoria] || r.categoria) + ' · ' + new Date(r.creado_en).toLocaleString('es-CO') + ' · ' + r.estado;
                const msg = document.createElement('p');
                msg.textContent = r.mensaje;
                msg.style.whiteSpace = 'pre-wrap';
                const det = document.createElement('small');
                det.textContent = [r.ruta_codigo && 'Ruta: ' + r.ruta_codigo, r.contacto && 'Contacto: ' + r.contacto].filter(Boolean).join(' · ');
                tarjeta.append(cab, msg, det);
                const acciones = document.createElement('div');
                acciones.className = 'button-row';
                if (r.origen_lat != null) { const a = document.createElement('a'); a.href = mapa(r.origen_lat, r.origen_lng); a.target = '_blank'; a.rel = 'noopener'; a.textContent = 'Ver A'; acciones.append(a); }
                if (r.destino_lat != null) { const a = document.createElement('a'); a.href = mapa(r.destino_lat, r.destino_lng); a.target = '_blank'; a.rel = 'noopener'; a.textContent = 'Ver B'; acciones.append(a); }
                [['REVISADO', 'Revisado'], ['RESUELTO', 'Resuelto'], ['DESCARTADO', 'Descartar']].forEach(([estado, txt]) => {
                    if (r.estado === estado) return;
                    const b = document.createElement('button'); b.type = 'button'; b.className = 'secondary'; b.textContent = txt;
                    b.onclick = async () => { try { await Spevb.api('/api/editor/reportes/' + r.id, { method: 'PATCH', headers: headers(), body: JSON.stringify({ estado }) }); cargar(); } catch (e) { status(e.message, 'error'); } };
                    acciones.append(b);
                });
                tarjeta.append(acciones);
                lista.append(tarjeta);
            });
        } catch (e) { status(e.message, 'error'); }
    }
    $('repCargar').onclick = cargar;
    $('repEstado').onchange = () => { if ($('repToken').value.length >= 32) cargar(); };
})();
