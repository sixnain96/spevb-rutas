const center = [4.1420, -73.6266];
const map = L.map('editorMap').setView(center, 13);
L.tileLayer('https://tile.openstreetmap.org/{z}/{x}/{y}.png', {maxZoom: 19, attribution: '&copy; OpenStreetMap contributors'}).addTo(map);

const select = document.getElementById('editorRoute');
const status = document.getElementById('editorStatus');
const origin = document.getElementById('editorOrigin');
const destination = document.getElementById('editorDestination');
const source = document.getElementById('editorSource');
const validated = document.getElementById('editorValidated');
let drawing = false;
let points = [];
let line = null;
let pointLayer = L.layerGroup().addTo(map);

function msg(text, type='info'){ status.textContent=text; status.className=`status ${type}`; }
function redraw(){
    if(line) map.removeLayer(line);
    pointLayer.clearLayers();
    if(points.length){
        line = L.polyline(points,{color:'#0b6e4f',weight:6}).addTo(map);
        points.forEach((p,i)=>L.circleMarker(p,{radius:5,weight:2,fillOpacity:1}).bindTooltip(String(i+1)).addTo(pointLayer));
    } else line=null;
}

async function loadRoutes(){
    const r=await fetch('/api/rutas'); const routes=await r.json();
    select.innerHTML='<option value="">Selecciona...</option>';
    routes.forEach(x=>{const o=document.createElement('option');o.value=x.id;o.textContent=`${x.codigo} · ${x.nombre}`;select.appendChild(o);});
}

select.addEventListener('change', async ()=>{
    points=[]; redraw();
    if(!select.value) return;
    const r=await fetch(`/api/rutas/${select.value}`); const route=await r.json();
    origin.value=route.origen||''; destination.value=route.destino||''; source.value=route.fuente||''; validated.checked=!!route.validada;
    points=(route.recorrido?.coordinates||[]).map(c=>[c[1],c[0]]);
    redraw();
    if(line) map.fitBounds(line.getBounds(),{padding:[40,40]});
    msg('Ruta cargada. Puedes editar punto por punto.','success');
});

document.getElementById('startDraw').onclick=()=>{drawing=!drawing;msg(drawing?'Modo dibujo activo: haz clic sobre la calle siguiendo el recorrido.':'Modo dibujo pausado.','info');};
map.on('click',e=>{if(!drawing)return;points.push([e.latlng.lat,e.latlng.lng]);redraw();});
document.getElementById('undoPoint').onclick=()=>{points.pop();redraw();};
document.getElementById('clearDraw').onclick=()=>{points=[];redraw();msg('Trazado limpio.','info');};

document.getElementById('saveDraw').onclick=async()=>{
    if(!select.value){msg('Selecciona una ruta.','error');return;}
    if(points.length<2){msg('Necesitas al menos 2 puntos.','error');return;}
    const recorrido={type:'LineString',coordinates:points.map(p=>[Number(p[1].toFixed(7)),Number(p[0].toFixed(7))])};
    const r=await fetch(`/api/editor/rutas/${select.value}/recorrido`,{
        method:'PUT',headers:{'Content-Type':'application/json'},
        body:JSON.stringify({recorrido,origen:origin.value,destino:destination.value,fuente:source.value,validada:validated.checked})
    });
    if(!r.ok){msg(`No se pudo guardar (HTTP ${r.status}).`,'error');return;}
    msg(`Guardado: ${points.length} puntos en PostGIS. Abre el mapa público para comprobarlo.`,'success');
};

loadRoutes().catch(e=>{console.error(e);msg('No se pudieron cargar las rutas.','error');});
