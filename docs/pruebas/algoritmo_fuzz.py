# Prueba masiva del planificador: viajes aleatorios en la ciudad y reglas lógicas (sube antes de bajar, puntos sobre la línea, transbordos válidos…).
# Uso: SEVB_URL no se usa: edita U en este archivo si no es localhost:18080. Arranca el servidor con LIMITE_SOLICITUDES=false.  python docs/pruebas/algoritmo_fuzz.py 200
import json, math, random, time, urllib.request, collections
U='http://localhost:18080/api'
def get(p): return json.loads(urllib.request.urlopen(U+p).read())
def post(p,b):
    r=urllib.request.Request(U+p,data=json.dumps(b).encode(),headers={'Content-Type':'application/json'})
    t=time.time()
    try:
        x=urllib.request.urlopen(r); return x.status, json.loads(x.read()), time.time()-t
    except urllib.error.HTTPError as e: return e.code, json.loads(e.read() or b'{}'), time.time()-t
rutas={r['codigo']:get('/rutas/%d'%r['id']) for r in get('/rutas')}
def m(p,q): return math.hypot((q[0]-p[0])*111320*math.cos(math.radians(p[1])),(q[1]-p[1])*110574)
def dist_linea(pt, coords):
    best=1e9
    for i in range(1,len(coords)):
        a,b=coords[i-1],coords[i]
        # proyección plana
        ax,ay=0,0; bx,by=m(a,b) if False else ((b[0]-a[0])*111320*math.cos(math.radians(a[1])),(b[1]-a[1])*110574)
        px,py=((pt[0]-a[0])*111320*math.cos(math.radians(a[1])),(pt[1]-a[1])*110574)
        L2=bx*bx+by*by; t=0 if L2==0 else max(0,min(1,(px*bx+py*by)/L2))
        best=min(best, math.hypot(px-t*bx,py-t*by))
    return best
def pos_en_linea(pt, coords):
    best=(1e9,0); acc=0
    for i in range(1,len(coords)):
        a,b=coords[i-1],coords[i]
        bx,by=((b[0]-a[0])*111320*math.cos(math.radians(a[1])),(b[1]-a[1])*110574)
        px,py=((pt[0]-a[0])*111320*math.cos(math.radians(a[1])),(pt[1]-a[1])*110574)
        L2=bx*bx+by*by; t=0 if L2==0 else max(0,min(1,(px*bx+py*by)/L2)); d=math.hypot(px-t*bx,py-t*by); Lg=math.sqrt(L2)
        if d<best[0]: best=(d,acc+t*Lg)
        acc+=Lg
    return best[1]
def len_poly(c): return sum(m(c[i-1],c[i]) for i in range(1,len(c)))
random.seed(7)
LAT=(4.085,4.165); LNG=(-73.69,-73.58)
viol=collections.defaultdict(list); n=0; tiempos=[]; vacios=0
for k in range(int(__import__('sys').argv[1]) if len(__import__('sys').argv)>1 else 200):
    A=[random.uniform(*LNG),random.uniform(*LAT)]; B=[random.uniform(*LNG),random.uniform(*LAT)]
    if m(A,B)<300: continue
    s,d,t=post('/viajes/planificar',{'origen':{'lat':A[1],'lng':A[0]},'destino':{'lat':B[1],'lng':B[0]},'soloValidadas':False}); tiempos.append(t); n+=1
    ctx=(round(A[1],5),round(A[0],5),round(B[1],5),round(B[0],5))
    if s!=200: viol['HTTP %d'%s].append((ctx,str(d)[:100])); continue
    ops=d.get('opciones',[]); alt=d.get('alternativas',[]); trs=d.get('transbordos',[])
    if not ops and not alt and not trs: vacios+=1
    # orden
    tot=[o['acercamientoInicioMetros']+o['acercamientoFinMetros'] for o in ops if o.get('variante')!='MAS_RAPIDA']
    for o in ops+alt:
        c=rutas[o['codigo']]['recorrido']['coordinates']
        su=o['puntoSubida']['coordinates']; ba=o['puntoBajada']['coordinates']
        ds=dist_linea(su,c); db=dist_linea(ba,c)
        if ds>30 or db>30: viol['punto de subida/bajada lejos de la línea (>30 m)'].append((ctx,o['codigo'],round(ds),round(db)))
        if o['sentidoPermitido']:
            ps,pb=pos_en_linea(su,c),pos_en_linea(ba,c)
            if pb<=ps+1: viol['sube DESPUÉS de bajar en sentido permitido'].append((ctx,o['codigo'],round(ps),round(pb)))
        rs=m([A[0],A[1]],su)
        if o['acercamientoInicioMetros']+5<rs: viol['caminata inicial menor que la línea recta'].append((ctx,o['codigo'],round(o['acercamientoInicioMetros']),round(rs)))
        rb=m(ba,B)
        if o['acercamientoFinMetros']+5<rb: viol['caminata final menor que la línea recta'].append((ctx,o['codigo'],round(o['acercamientoFinMetros']),round(rb)))
        ini=o.get('caminataInicioRuta')
        if ini and ini.get('coordinates'):
            e0=ini['coordinates'][0]; e1=ini['coordinates'][-1]
            if m(e1,su)>40 and m(e0,su)>40: viol['trazo a pie inicial no termina en el punto de subida'].append((ctx,o['codigo'],round(m(e1,su))))
            lp=len_poly(ini['coordinates'])
            if ini['metros']>0 and abs(lp-ini['metros'])>0.25*ini['metros']+25: viol['metros del trazo a pie no cuadran con su longitud'].append((ctx,o['codigo'],ini['metros'],round(lp)))
        if o['acercamientoInicioMetros']>3000 or o['acercamientoFinMetros']>3000: viol['caminata > 3 km en una opción'].append((ctx,o['codigo']))
    for o in alt:
        if o['sentidoPermitido']: viol['alternativa marcada como permitida'].append((ctx,o['codigo']))
    for o in ops:
        if not o['sentidoPermitido']: viol['opción directa con sentido NO permitido'].append((ctx,o['codigo']))
    for tr in trs:
        b1,b2=tr['primerBus'],tr['segundoBus']
        if b1['codigo']==b2['codigo']: viol['transbordo entre la misma ruta'].append((ctx,b1['codigo']))
        o1=b1['puntoBajada']['coordinates']; s2=b2['puntoSubida']['coordinates']
        if m(o1,s2)>300: viol['transbordo: bajada y subida a más de 300 m'].append((ctx,round(m(o1,s2))))
        c1=rutas[b1['codigo']]['recorrido']['coordinates']; c2=rutas[b2['codigo']]['recorrido']['coordinates']
        if pos_en_linea(b1['puntoBajada']['coordinates'],c1)<=pos_en_linea(b1['puntoSubida']['coordinates'],c1)+1: viol['transbordo: bus 1 baja antes de subir'].append((ctx,b1['codigo']))
        if pos_en_linea(b2['puntoBajada']['coordinates'],c2)<=pos_en_linea(b2['puntoSubida']['coordinates'],c2)+1: viol['transbordo: bus 2 baja antes de subir'].append((ctx,b2['codigo']))
        if ops:
            mejor_dir=min(o['acercamientoInicioMetros']+o['acercamientoFinMetros'] for o in ops)
            # un transbordo no debería ofrecerse si el directo es claramente mejor en todo
print('viajes probados:',n,'· sin ninguna opción:',vacios,'· tiempo medio %.2fs · máx %.2fs'%(sum(tiempos)/len(tiempos),max(tiempos)))
if not viol: print('SIN VIOLACIONES')
for k,v in sorted(viol.items(), key=lambda x:-len(x[1])):
    print('\n%3d × %s'%(len(v),k))
    for e in v[:4]: print('     ',e)
