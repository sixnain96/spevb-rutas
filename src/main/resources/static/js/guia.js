'use strict';
/* Aprender a usar SEVB (4.15): bienvenida de 5 escenas con ilustraciones animadas, ayuda en contexto (botón «?») con 9 estados y «ayudas rápidas», y práctica guiada
   de 7 pasos sobre la app real.
   · La bienvenida sale solo la primera vez y se puede ver otra vez desde el menú → Ayuda. Nadie está obligado a hacer la práctica.
   · Las «ayudas rápidas» reutilizan las mismas escenas (una sola ilustración, sin navegación) para consultar algo puntual.
   · La práctica usa los campos y botones reales. NO usa el GPS. Guarda lo que la persona tenía (A, B y si había resultados) y lo devuelve al terminar o salir.
     Si el servicio de rutas no responde, enseña con un ejemplo ilustrado marcado como simulación (nunca lo presenta como un recorrido real).
   · El coach (capa que ilumina lo que se enseña) se actualiza solo cuando algo cambia (eventos), no en cada cuadro de pantalla.
   Sin emojis y sin voz: todo el dibujo es propio (SVG, ver iconos.js) y la guía es el chigüiro del proyecto, que solo aparece donde aporta. */
(() => {
    const S = window.Spevb;
    const $ = (id) => document.getElementById(id);
    if (!S || !$('bienvenida')) return;
    const K = 'spevb-bienvenida', VERSION = '2';
    const visto = () => { try { return localStorage.getItem(K) === VERSION; } catch { return true; } };
    const marcarVisto = () => { try { localStorage.setItem(K, VERSION); } catch { /* sin almacenamiento */ } };
    const hayModalAbierto = () => [...document.querySelectorAll('.modal-backdrop')].some((m) => !m.hidden);
    const reducido = () => document.documentElement.dataset.motion === 'reduce' || (window.matchMedia && window.matchMedia('(prefers-reduced-motion: reduce)').matches);
    const icono = (n, tam = 20) => (S.Iconos ? S.Iconos.svg(n, { tam }) : '');
    const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
    const visible = (el) => !!el && !el.hidden && el.getClientRects().length > 0;
    const FOCO = 'button:not([hidden]):not([disabled]), summary, a[href], input:not([disabled]), select, textarea, [tabindex]:not([tabindex="-1"])';

    // ------------------------------------------------------------------ foco de las ventanas (trampa de Tab y regreso al botón que las abrió)
    let abridor = null;
    const recordarAbridor = () => { const a = document.activeElement; abridor = a && a !== document.body ? a : null; };
    const devolverFoco = () => { const a = abridor; abridor = null; if (a && document.contains(a) && visible(a)) { try { a.focus({ preventScroll: true }); } catch { /* sin foco */ } } };
    function atrapar(e, ventana) {
        if (e.key !== 'Tab') return;
        const f = [...ventana.querySelectorAll(FOCO)].filter(visible);
        if (!f.length) return;
        const i = f.indexOf(document.activeElement);
        const sig = e.shiftKey ? (i <= 0 ? f.length - 1 : i - 1) : (i === f.length - 1 ? 0 : i + 1);
        e.preventDefault(); f[sig].focus();
    }

    // ------------------------------------------------------------------ ilustraciones animadas (SVG propio, mismo trazo y paleta que el resto de SEVB)
    const V = '#176650', ROJO = '#e53935', NARANJA = '#d9822b';
    const PIN = (clase, letra, color) => '<g class="pin ' + clase + '"><g class="pin-cae"><path d="M0 0c-9-9-14-16-14-24a14 14 0 0 1 28 0c0 8-5 15-14 24z" fill="' + color + '" stroke="#fff" stroke-width="2.5"/>'
        + '<circle cx="0" cy="-24" r="8" fill="#fff"/><text x="0" y="-20.2" text-anchor="middle" font-size="11" font-weight="800" fill="' + color + '">' + letra + '</text></g></g>';
    const CALLES = '<path class="calle" d="M-10 150C50 128 105 170 165 138S270 122 330 144"/><path class="calle" d="M-10 62 330 96"/><path class="calle" d="M118-10 150 210"/><path class="calle" d="M232-10 214 210"/>';
    const MANZANAS = [[14, 100, 62, 30], [100, 98, 52, 24], [172, 104, 48, 22], [250, 100, 56, 26], [14, 156, 64, 28], [128, 156, 70, 26], [224, 156, 82, 28]]
        .map((m) => '<rect class="manzana" x="' + m[0] + '" y="' + m[1] + '" width="' + m[2] + '" height="' + m[3] + '" rx="7"/>').join('');
    const FONDO = '<rect width="320" height="200" rx="22" class="fondo"/>';
    const ABRE = (n) => '<svg viewBox="0 0 320 200" class="escena-svg e' + n + '" aria-hidden="true" focusable="false">';
    const ic = (nombre, x, y, tam = 22) => '<g class="ico" transform="translate(' + (x - tam / 2) + ' ' + (y - tam / 2) + ')">' + icono(nombre, tam) + '</g>';
    const BUS_ILUS = '<g transform="translate(-17 -11)"><rect width="34" height="20" rx="6" fill="' + ROJO + '" stroke="#fff" stroke-width="2.4"/>'
        + '<rect x="5" y="5" width="7" height="6" rx="1.6" fill="#fff"/><rect x="14" y="5" width="7" height="6" rx="1.6" fill="#fff"/><rect x="23" y="5" width="6" height="6" rx="1.6" fill="#fff"/>'
        + '<circle cx="9" cy="20" r="3.4" fill="#14281f" stroke="#fff" stroke-width="1.6"/><circle cx="25" cy="20" r="3.4" fill="#14281f" stroke="#fff" stroke-width="1.6"/></g>';
    const MASCOTA = (id, cx, cy, r) => '<circle class="medallon" cx="' + cx + '" cy="' + cy + '" r="' + (r + 3) + '"/><clipPath id="' + id + '"><circle cx="' + cx + '" cy="' + cy + '" r="' + r + '"/></clipPath>'
        + '<g clip-path="url(#' + id + ')"><image class="mascota" href="/img/marca/sevb-chiguiro-96.png" x="' + (cx - r) + '" y="' + (cy - r) + '" width="' + 2 * r + '" height="' + 2 * r + '"/></g>';
    const TARJETA = (cls, y, chip, color, nombre, min, extra = '') => '<g class="tarjeta ' + cls + '" style="--y:' + y + 'px"><rect class="t-fondo" x="10" y="' + y + '" width="152" height="40" rx="12"/>'
        + '<rect x="18" y="' + (y + 9) + '" width="30" height="22" rx="7" fill="' + color + '"/><text x="33" y="' + (y + 24) + '" text-anchor="middle" class="t-chip">' + chip + '</text>'
        + '<text x="56" y="' + (y + 17) + '" class="t-nombre">' + nombre + '</text><text x="56" y="' + (y + 31) + '" class="t-min">' + min + '</text>' + extra + '</g>';
    const NODOS = [[50, 52, 'caminar', 'Camina'], [160, 52, 'paradero', 'Paradero'], [270, 52, 'bus', 'Sube'], [270, 140, 'flecha', 'Viaja'], [160, 140, 'bajar', 'Bájate'], [50, 140, 'pin', 'Llegas']];
    const RUTA_VIAJE = 'M50 52H270C300 52 300 140 270 140H50';

    const ESCENAS = [
        {
            clave: 'que-es', titulo: 'Aquí sabes qué bus tomar',
            texto: 'SEVB te dice qué ruta de bus tomar en Villavicencio, dónde subirte y dónde bajarte. Sin adivinar.',
            svg: ABRE(1) + FONDO + '<path class="monte" d="M0 76 46 42 84 64 130 32 176 68 222 40 268 66 320 46V0H0z"/><circle class="sol" cx="286" cy="24" r="12"/>' + MANZANAS + CALLES
                + '<path id="rutaE1" class="linea-ruta" pathLength="1" d="M26 170C78 170 92 126 150 126S238 160 294 110"/>'
                + '<circle class="parada sube" cx="26" cy="170" r="6"/><circle class="parada baja" cx="294" cy="110" r="6"/>'
                + '<g class="marca"><rect x="12" y="12" width="78" height="26" rx="13"/><g class="ico" transform="translate(18 15)">' + icono('bus', 20) + '</g><text x="60" y="29.5" text-anchor="middle">SEVB</text></g>'
                + '<g class="bus-mov">' + BUS_ILUS + '<animateMotion dur="7s" repeatCount="indefinite" calcMode="spline" keyTimes="0;1" keySplines=".4 0 .3 1"><mpath href="#rutaE1"/></animateMotion></g>'
                + '<g class="saludo">' + MASCOTA('recorteE1', 290, 170, 17) + '</g></svg>',
        },
        {
            clave: 'buscar', titulo: 'Dime de dónde sales y a dónde vas',
            texto: 'Escribe el lugar de donde sales (A) y a dónde llegas (B), o márcalos en el mapa. Después toca el botón verde «Buscar rutas».',
            svg: ABRE(2) + FONDO + MANZANAS + CALLES
                + '<g class="campo ca"><rect x="14" y="10" width="292" height="32" rx="16"/><circle cx="32" cy="26" r="10" fill="' + V + '"/><text x="32" y="30.2" text-anchor="middle" class="letra">A</text>'
                + '<text x="50" y="30" class="valor">Serramonte</text><rect class="tapa" x="48" y="14" width="250" height="24"/></g>'
                + '<g class="campo cb"><rect x="14" y="48" width="292" height="32" rx="16"/><circle cx="32" cy="64" r="10" fill="' + NARANJA + '"/><text x="32" y="68.2" text-anchor="middle" class="letra">B</text>'
                + '<text x="50" y="68" class="valor">Terminal de transportes</text><rect class="tapa" x="48" y="52" width="250" height="24"/></g>'
                + '<path class="trazo-ab" pathLength="1" d="M66 150C116 100 190 170 252 120"/>'
                + '<g transform="translate(66 150)"><ellipse class="sombra" cx="0" cy="2" rx="11" ry="3.2"/>' + PIN('pin-a', 'A', V) + '</g>'
                + '<g transform="translate(252 120)"><ellipse class="sombra" cx="0" cy="2" rx="11" ry="3.2"/>' + PIN('pin-b', 'B', NARANJA) + '</g>'
                + '<g class="boton-buscar"><rect x="100" y="164" width="120" height="30" rx="15"/><text x="160" y="183.5" text-anchor="middle">Buscar rutas</text></g></svg>',
        },
        {
            clave: 'elegir', titulo: 'Compara y elige tu bus',
            texto: 'Cada opción muestra el nombre de la ruta, los minutos y cuánto caminas. Toca una para verla en el mapa. Si hace falta cambiar de bus, verás «2 buses».',
            svg: ABRE(3) + FONDO
                + TARJETA('t1', 12, 'R08', '#1565c0', 'Ruta Kirpas', '24 min')
                + TARJETA('t2', 58, 'R12', ROJO, 'Ruta Catama', '12 min · 3 cuadras')
                + '<g class="tarjeta t3" style="--y:104px"><rect class="t-fondo" x="10" y="104" width="152" height="40" rx="12"/><rect x="18" y="113" width="22" height="22" rx="7" fill="#7b1fa2"/><rect x="44" y="113" width="22" height="22" rx="7" fill="#ef6c00"/>'
                + '<path d="M40 124h4" stroke="#55645d" stroke-width="2"/><text x="74" y="121" class="t-nombre">2 buses</text><text x="74" y="135" class="t-min">Transbordo</text></g>'
                + '<rect class="mapita" x="170" y="12" width="140" height="132" rx="14"/>'
                + '<path class="gris" d="M184 130C200 96 226 120 244 84S292 40 300 26"/><path class="gris" d="M182 40C214 56 232 52 252 96S290 128 302 132"/>'
                + '<path id="rutaE3" class="linea-ruta" pathLength="1" d="M184 114C212 112 224 76 250 70S288 52 298 30"/>'
                + '<circle class="parada sube" cx="184" cy="114" r="6"/><circle class="parada baja" cx="298" cy="30" r="6"/>'
                + '<g class="etiqueta et-sube"><rect x="176" y="122" width="60" height="18" rx="9"/><text x="206" y="134.5" text-anchor="middle">Sube aquí</text></g>'
                + '<g class="etiqueta et-baja"><rect x="236" y="14" width="68" height="18" rx="9"/><text x="270" y="26.5" text-anchor="middle">Bájate aquí</text></g>'
                + '<g class="pildora p-min"><rect x="14" y="156" width="92" height="30" rx="15"/><g class="ico" transform="translate(21 160)">' + icono('reloj', 22) + '</g><text x="68" y="175.5" text-anchor="middle">12 min</text></g>'
                + '<g class="pildora p-camina"><rect x="114" y="156" width="190" height="30" rx="15"/><g class="ico" transform="translate(121 160)">' + icono('caminar', 22) + '</g><text x="216" y="175.5" text-anchor="middle">Caminas unas 3 cuadras</text></g></svg>',
        },
        {
            clave: 'viaje', titulo: 'Te acompaño en el viaje',
            texto: 'Toca «Iniciar viaje» y te aviso en cada paso: cuándo caminar, cuándo subir y cuándo bajarte. Tú solo sigue las indicaciones.',
            svg: ABRE(4) + FONDO + '<path class="riel" d="' + RUTA_VIAJE + '"/><path id="rutaE4" class="riel-hecho" pathLength="1" d="' + RUTA_VIAJE + '"/>'
                + NODOS.map((n, i) => '<g class="nodo n' + (i + 1) + '"><circle cx="' + n[0] + '" cy="' + n[1] + '" r="20"/>' + ic(n[2], n[0], n[1]) + '<text x="' + n[0] + '" y="' + (n[1] + (n[1] < 100 ? 38 : 38)) + '" text-anchor="middle">' + n[3] + '</text></g>').join('')
                + '<circle class="viajero" r="7"><animateMotion dur="5.2s" begin="0.5s" repeatCount="1" fill="freeze" calcMode="linear"><mpath href="#rutaE4"/></animateMotion></circle></svg>',
        },
        {
            clave: 'practicar', titulo: 'Practica sin riesgo',
            texto: 'Puedes hacer una práctica de dos minutos con un ejemplo: no usa tu ubicación ni gasta nada. O empieza a explorar cuando quieras.',
            svg: ABRE(5) + FONDO + MANZANAS + '<g class="saludo">' + MASCOTA('recorteE5', 160, 62, 38) + '</g>'
                + '<g class="insignia-ok"><circle cx="196" cy="94" r="13"/><path d="M190 94.5 194.6 99 202 89.5"/></g>'
                + '<g class="op op1"><rect x="14" y="124" width="140" height="62" rx="16"/><g class="ico" transform="translate(24 134)">' + icono('birrete', 24) + '</g><text x="56" y="150" class="op-t">Práctica</text><text x="56" y="166" class="op-s">guiada · 2 min</text></g>'
                + '<g class="op op2"><rect x="166" y="124" width="140" height="62" rx="16"/><g class="ico" transform="translate(176 134)">' + icono('brujula', 24) + '</g><text x="208" y="150" class="op-t">Explorar</text><text x="208" y="166" class="op-s">a tu ritmo</text></g></svg>',
        },
    ];
    // Ayudas rápidas: cada tema reutiliza una escena (no hay una ilustración nueva por tema).
    const TEMAS = [
        { t: 'Cómo buscar una ruta', escena: 1, titulo: 'Cómo buscar una ruta', texto: 'Escribe de dónde sales (A) y a dónde llegas (B), o toca «En el mapa» y márcalos con el dedo. Después toca «Buscar rutas».' },
        { t: 'Cómo elegir entre varias opciones', escena: 2, titulo: 'Cómo elegir entre varias opciones', texto: 'Mira el nombre de la ruta, los minutos y cuánto caminas. Toca una opción para verla en el mapa y compara.' },
        { t: 'Dónde subir y dónde bajar', escena: 2, titulo: 'Dónde subir y dónde bajar', texto: 'En el mapa, «Sube aquí» es el paradero donde subes y «Bájate aquí» donde bajas. Camina por la línea punteada hasta el primero.' },
        { t: 'Cómo funcionan los transbordos', escena: 2, titulo: 'Cómo funcionan los transbordos', texto: 'Si no hay un bus directo, verás «2 buses»: bajas del primero en el punto marcado y subes al segundo. El tiempo ya incluye una espera aproximada.' },
        { t: 'Cómo iniciar un viaje', escena: 3, titulo: 'Cómo iniciar un viaje', texto: 'Toca «Iniciar viaje» cuando estés en el paradero. Te aviso cuándo caminar, subir y bajar. La práctica no usa tu GPS.' },
        { t: 'Cómo usar el mapa', escena: 0, titulo: 'Cómo usar el mapa', texto: 'Arrastra para moverte y junta o separa dos dedos para acercar. La línea roja es el bus y la punteada es lo que caminas.' },
        { t: 'Si no aparece una ruta', escena: null, titulo: 'Si no aparece una ruta',
            tips: ['Mueve A o B hacia una calle grande, cerca de donde pasan los buses.', 'Toca «En el mapa» y marca el punto con el dedo para estar seguro de dónde quedó.', 'Prueba con otro lugar cercano. Hay zonas con pocas rutas registradas.'] },
    ];

    // ------------------------------------------------------------------ bienvenida y lecciones
    const bien = $('bienvenida'), tarjetaBien = bien.querySelector('.bienvenida-card');
    let paso = 0, anterior = -1, modo = 'bienvenida', leccionActual = null;
    function montarEscenas() {
        const cont = $('bienIlustracion');
        if (cont.childElementCount) return;
        ESCENAS.forEach((e, i) => { const d = document.createElement('div'); d.className = 'bien-escena'; d.dataset.i = String(i); d.dataset.clave = e.clave; d.innerHTML = e.svg; cont.append(d); });
    }
    /** Detiene lo que se mueve en las escenas que no se ven (las animaciones SMIL no se pausan solas) y reanuda la activa. */
    function animarSoloLaActiva(activa) {
        document.querySelectorAll('#bienIlustracion .bien-escena').forEach((d, i) => {
            const svg = d.querySelector('svg');
            if (svg && svg.pauseAnimations) { if (i === activa && !reducido()) { if (svg.dataset.vista !== '1') { svg.setCurrentTime(0); svg.dataset.vista = '1'; } svg.unpauseAnimations(); } else { svg.pauseAnimations(); if (i !== activa) delete svg.dataset.vista; } }
        });
    }
    function pintarBienvenida() {
        montarEscenas();
        const leccion = modo === 'leccion', p = leccion ? leccionActual : ESCENAS[paso], ultimo = !leccion && paso === ESCENAS.length - 1;
        tarjetaBien.dataset.modo = modo; tarjetaBien.dataset.escena = String(paso);
        document.querySelectorAll('#bienIlustracion .bien-escena').forEach((d, i) => {
            d.classList.toggle('activa', i === paso); d.classList.toggle('pasada', i < paso); d.classList.toggle('futura', i > paso);
        });
        animarSoloLaActiva(paso);
        const texto = $('bienTextos');
        texto.classList.remove('cambia'); void texto.offsetWidth; texto.classList.add('cambia');          // reinicia la animación de entrada del texto
        texto.dataset.dir = anterior > paso ? 'atras' : 'adelante';
        $('bienTitulo').textContent = p.titulo;
        $('bienTexto').textContent = p.texto;
        $('bienPuntos').replaceChildren(...ESCENAS.map((_, i) => { const d = document.createElement('i'); if (i === paso) d.className = 'activo'; else if (i < paso) d.className = 'hecho'; return d; }));
        $('bienAtras').hidden = leccion || paso === 0;
        $('bienSiguiente').textContent = leccion ? 'Entendido' : ultimo ? 'Empezar a explorar' : 'Siguiente';
        $('bienSiguiente').className = ultimo ? 'secondary' : 'primary-plan-button';
        $('bienPractica').hidden = !ultimo;
        $('bienSaltar').hidden = leccion || ultimo;
        $('bienPasoTexto').textContent = leccion ? 'Ayuda rápida' : 'Paso ' + (paso + 1) + ' de ' + ESCENAS.length;
        anterior = paso;
    }
    function abrirVentanaBien(foco) {
        if (!abridor) recordarAbridor();
        bien.hidden = false; bien.classList.remove('cerrando'); document.body.classList.add('modal-open'); pintarBienvenida();
        setTimeout(() => { if (!bien.hidden) $(foco).focus({ preventScroll: true }); }, 60);
    }
    function abrirBienvenida() { modo = 'bienvenida'; leccionActual = null; paso = 0; anterior = -1; abrirVentanaBien('bienSiguiente'); }
    /** Una sola escena con su explicación (ayudas rápidas): no cuenta como «bienvenida vista». */
    function abrirLeccion(tema) { modo = 'leccion'; leccionActual = tema; paso = tema.escena; anterior = -1; abrirVentanaBien('bienSiguiente'); }
    function cerrarBienvenida({ marcar = true } = {}) {
        if (marcar && modo === 'bienvenida') marcarVisto();
        bien.classList.add('cerrando');
        setTimeout(() => {
            bien.hidden = true; bien.classList.remove('cerrando'); animarSoloLaActiva(-1);
            if (!hayModalAbierto()) document.body.classList.remove('modal-open');
            devolverFoco();
        }, reducido() ? 0 : 260);
    }
    const irA = (n) => { if (n < 0 || n > ESCENAS.length - 1 || modo === 'leccion') return; paso = n; pintarBienvenida(); };
    $('bienSiguiente').onclick = () => { if (modo === 'bienvenida' && paso < ESCENAS.length - 1) irA(paso + 1); else cerrarBienvenida(); };
    $('bienAtras').onclick = () => irA(paso - 1);
    $('bienSaltar').onclick = () => cerrarBienvenida();
    $('bienPractica').onclick = () => { abridor = null; cerrarBienvenida(); setTimeout(() => practica(), 450); };
    document.addEventListener('keydown', (e) => {
        if (bien.hidden) return;
        if (e.key === 'Escape') cerrarBienvenida();
        else if (e.key === 'ArrowRight') irA(paso + 1);
        else if (e.key === 'ArrowLeft') irA(paso - 1);
        else atrapar(e, tarjetaBien);
    });
    // Deslizar con el dedo entre las escenas de la bienvenida.
    let x0 = null;
    bien.addEventListener('touchstart', (e) => { x0 = e.touches[0].clientX; }, { passive: true });
    bien.addEventListener('touchend', (e) => {
        if (x0 === null) return;
        const dx = e.changedTouches[0].clientX - x0; x0 = null;
        if (dx < -50) irA(paso + 1); else if (dx > 50) irA(paso - 1);
    }, { passive: true });

    // ------------------------------------------------------------------ ayuda en contexto (9 estados)
    const puntosAhora = () => (S.Planner && S.Planner.puntos ? S.Planner.puntos() : {});
    const DEMO_BUSCAR = { texto: 'Ver cómo se busca', tema: 0 }, DEMO_ELEGIR = { texto: 'Ver cómo se elige', tema: 1 }, DEMO_VIAJE = { texto: 'Ver cómo es el viaje', tema: 4 };
    const AYUDAS = {
        inicio: { titulo: 'Para empezar', tips: ['Escribe dónde estás en «¿De dónde sales?», o toca «Mi ubicación».', 'Escribe a dónde vas en «¿A dónde vas?».',
            'Si no encuentras la dirección, toca «En el mapa» y marca el punto con el dedo.', 'Después toca el botón verde «Buscar rutas».'], accion: '#tripOrigin', demo: DEMO_BUSCAR },
        uno: { titulo: 'Te falta un punto', tips: ['Ya tienes uno de los dos puntos. Falta el otro: escríbelo, o toca «En el mapa» para marcarlo.', 'Cuando estén los dos, toca «Buscar rutas».'],
            accion: () => (puntosAhora().origen ? '#tripDestination' : '#tripOrigin'), demo: DEMO_BUSCAR },
        listos: { titulo: 'Ya casi', tips: ['Ya están tus dos puntos. Toca el botón verde «Buscar rutas» para ver qué bus tomar.'], accion: '#planTrip', demo: DEMO_BUSCAR },
        buscando: { titulo: 'Buscando tus rutas', tips: ['Estoy comparando tu salida y tu llegada con todas las rutas. Son unos segundos.', 'No cierres la página: las opciones aparecen solas.'], accion: null, demo: null },
        resultados: { titulo: 'Tus opciones', tips: ['Cada opción dice qué ruta tomar, cuánto caminas y cuánto tardas, más o menos.', 'Toca una opción para verla en el mapa.',
            'El tiempo es aproximado y no incluye la espera del bus.'], accion: '#tripResults .trip-option summary', demo: DEMO_ELEGIR },
        ruta: { titulo: 'Ya elegiste una ruta', tips: ['La línea roja es el bus y la punteada es lo que caminas.', 'Sube en «Sube aquí» y bájate en «Bájate aquí».',
            'Cuando estés en el paradero, toca «Iniciar viaje» y te acompaño paso a paso.'], accion: '#startTrip', demo: DEMO_VIAJE },
        sinrutas: { titulo: 'No apareció una ruta', tips: ['Mueve A o B hacia una calle grande, cerca de donde pasan los buses.', 'Toca «En el mapa» y marca el punto con el dedo para ver dónde quedó.',
            'Prueba con otro lugar cercano: hay zonas con pocas rutas registradas.'], accion: '#tripOrigin', demo: null },
        error: { titulo: 'Algo no salió bien', tips: ['Revisa tu conexión a internet.', 'Los viajes que ya consultaste están en «Recientes», en el menú ☰.', 'Vuelve a tocar «Buscar rutas».'], accion: '#planTrip', demo: null },
        viaje: { titulo: 'Durante tu viaje', tips: ['Sigue la tarjeta del mapa: te dice qué hacer ahora.', 'Si te pregunta «¿Vas en la ruta…?», toca «Sí» o «No».',
            'Si te pierdes, toca «Más» y luego «Recalcular desde aquí».', 'Para terminar antes, toca «Terminar viaje».'], accion: null, demo: null },
    };
    function estadoActual() {
        if (S.Viaje && S.Viaje.activo()) return 'viaje';
        const estado = $('tripStatus'), msg = estado ? estado.textContent.trim() : '';
        const p = puntosAhora(), hayOpciones = !!document.querySelector('#tripResults .trip-option');
        if (navigator.onLine === false) return 'error';
        if (!hayOpciones && /^Comparando/.test(msg)) return 'buscando';
        if (document.querySelector('.trip-option.selected')) return 'ruta';
        if (hayOpciones) return 'resultados';
        if (/Todavía no hay una ruta/.test(($('tripResults') || {}).textContent || '')) return 'sinrutas';
        if ((estado && estado.classList.contains('error') && !/^Primero selecciona/.test(msg) && p.origen && p.destino) || navigator.onLine === false) return 'error';
        if (p.origen && p.destino) return 'listos';
        if (p.origen || p.destino) return 'uno';
        return 'inicio';
    }
    const ayuda = $('ayudaModal');
    let accionPendiente = null, demoPendiente = null, temporizadorResalte = 0;
    function llenarLista(tips) { $('ayudaLista').replaceChildren(...tips.map((t) => { const li = document.createElement('li'); li.textContent = t; return li; })); }
    function montarTemas() {
        const cont = $('ayudaTemas');
        if (cont.childElementCount) return;
        TEMAS.forEach((t, i) => {
            const b = document.createElement('button'); b.type = 'button'; b.className = 'tema-chip'; b.dataset.tema = String(i);
            b.textContent = t.t;
            b.onclick = () => {
                if (t.escena === null) { $('ayudaTitulo').textContent = t.titulo; llenarLista(t.tips); accionPendiente = '#tripOrigin'; demoPendiente = null; $('ayudaDemo').hidden = true; return; }
                cerrarAyuda({ devolver: false }); setTimeout(() => abrirLeccion(t), 200);
            };
            cont.append(b);
        });
    }
    /** Resalta unos segundos la acción recomendada (con poco movimiento: solo un borde fijo). */
    function resaltar(selector) {
        const el = selector ? document.querySelector(selector) : null;
        if (!el || !visible(el)) return;
        document.querySelectorAll('.ayuda-resalta').forEach((x) => x.classList.remove('ayuda-resalta'));
        el.classList.add('ayuda-resalta');
        const r = el.getBoundingClientRect();
        if (r.top < 0 || r.bottom > innerHeight) el.scrollIntoView({ block: 'center', behavior: reducido() ? 'auto' : 'smooth' });
        clearTimeout(temporizadorResalte);
        temporizadorResalte = setTimeout(() => el.classList.remove('ayuda-resalta'), 3600);
    }
    function abrirAyuda() {
        const a = AYUDAS[estadoActual()];
        $('ayudaTitulo').textContent = a.titulo;
        llenarLista(a.tips);
        accionPendiente = typeof a.accion === 'function' ? a.accion() : a.accion;
        demoPendiente = a.demo ? TEMAS[a.demo.tema] : null;
        $('ayudaDemo').hidden = !demoPendiente;
        if (demoPendiente) $('ayudaDemo').textContent = a.demo.texto;
        montarTemas();
        const det = ayuda.querySelector('.ayuda-temas'); if (det) det.open = false;
        recordarAbridor();
        ayuda.hidden = false; document.body.classList.add('modal-open');
        setTimeout(() => $('ayudaCerrar').focus({ preventScroll: true }), 50);
    }
    function cerrarAyuda({ devolver = true, resaltarAccion = false } = {}) {
        ayuda.hidden = true; if (!hayModalAbierto()) document.body.classList.remove('modal-open');
        if (devolver) devolverFoco();
        if (resaltarAccion && accionPendiente) setTimeout(() => resaltar(accionPendiente), 120);
    }
    $('helpButton').addEventListener('click', abrirAyuda);
    $('ayudaCerrar').onclick = () => cerrarAyuda({ resaltarAccion: true });
    $('ayudaDemo').onclick = () => { const t = demoPendiente; if (!t) return; cerrarAyuda({ devolver: false }); setTimeout(() => abrirLeccion(t), 200); };
    $('ayudaBien').onclick = () => { cerrarAyuda({ devolver: false }); abrirBienvenida(); };
    $('ayudaPractica').onclick = () => { cerrarAyuda({ devolver: false }); setTimeout(() => practica(), 250); };
    ayuda.addEventListener('click', (e) => { if (e.target === ayuda) cerrarAyuda(); });
    document.addEventListener('keydown', (e) => {
        if (ayuda.hidden) return;
        if (e.key === 'Escape') cerrarAyuda(); else atrapar(e, ayuda.querySelector('.modal-card'));
    });
    const menuAbierto = () => { const m = $('sideMenu'); return !!m && m.classList.contains('open'); };
    async function cerrarMenu() { if (!menuAbierto()) return; const c = $('closeMenu'); if (c) c.click(); for (let i = 0; i < 20 && menuAbierto(); i++) await sleep(50); await sleep(reducido() ? 0 : 380); }
    $('menuBienvenida').addEventListener('click', async () => { await cerrarMenu(); abrirBienvenida(); });
    $('menuPractica').addEventListener('click', async () => { await cerrarMenu(); practica(); });

    // ------------------------------------------------------------------ práctica guiada (7 pasos sobre la app real)
    // Dos puntos de ejemplo con ruta registrada. Si en la ciudad no hay ruta entre ellos (o el servicio no responde), la práctica sigue con un ejemplo ilustrado.
    const EJEMPLO = {
        a: { id: null, lat: 4.1165, lng: -73.6505, nombre: 'Serramonte (ejemplo)', direccion: null, fuente: 'Práctica', confirmado: true },
        b: { id: null, lat: 4.1330, lng: -73.6065, nombre: 'Cerca de la Terminal (ejemplo)', direccion: null, fuente: 'Práctica', confirmado: true },
    };
    const TOTAL = 7;
    let coach = null;
    const LEYENDA = '<ul class="coach-leyenda"><li><svg viewBox="0 0 36 12" width="36" height="12" aria-hidden="true"><path d="M3 6h30" stroke="' + ROJO + '" stroke-width="7" stroke-linecap="round"/></svg><span><b>Línea roja:</b> por aquí va el bus</span></li>'
        + '<li><svg viewBox="0 0 36 12" width="36" height="12" aria-hidden="true"><path d="M3 6h30" stroke="#2f3640" stroke-width="4" stroke-linecap="round" stroke-dasharray="1 8"/></svg><span><b>Línea punteada:</b> lo que caminas</span></li>'
        + '<li><svg viewBox="0 0 36 12" width="36" height="12" aria-hidden="true"><circle cx="9" cy="6" r="5" fill="' + V + '"/><circle cx="27" cy="6" r="5" fill="' + NARANJA + '"/></svg><span><b>A verde, B naranja:</b> de dónde sales y a dónde llegas</span></li>'
        + '<li><svg viewBox="0 0 36 12" width="36" height="12" aria-hidden="true"><rect x="2" y="1" width="32" height="10" rx="5" fill="#fff" stroke="#b6c4bf"/></svg><span><b>«Sube aquí»</b> y <b>«Bájate aquí»</b>: los paraderos</span></li></ul>';
    const MINI_VIAJE = '<div class="coach-mini" aria-label="Demostración de cómo se ve el viaje"><p class="mini-rotulo">Así se ve durante el viaje (demostración)</p><ol class="mini-viaje">'
        + [['caminar', 'Camina al paradero'], ['bus', 'Sube al bus'], ['bajar', 'Bájate aquí'], ['pin', 'Llegaste']].map((x, i) => '<li style="--k:' + i + '"><span class="mv-ico">' + icono(x[0], 20) + '</span><span class="mv-txt">' + x[1] + '</span></li>').join('') + '</ol></div>';
    const MINI_TRANSBORDO = '<div class="coach-mini"><p class="mini-rotulo">Transbordo: dos buses</p><div class="mini-tb"><span class="chip c1">R12</span><span class="flecha">' + icono('flecha', 18) + '</span><span class="chip c2">R08</span><span class="mini-nota">Bajas del primero y subes al segundo</span></div></div>';

    function crearCoach() {
        const c = document.createElement('div');
        c.id = 'coach'; c.className = 'coach';
        c.innerHTML = '<div class="coach-bloqueo b-arriba"></div><div class="coach-bloqueo b-abajo"></div><div class="coach-bloqueo b-izq"></div><div class="coach-bloqueo b-der"></div><div class="coach-bloqueo coach-escudo" hidden></div>'
            + '<div class="coach-hueco" aria-hidden="true"><i class="onda"></i><i class="onda o2"></i></div>'
            + '<div class="coach-dedo" aria-hidden="true">' + icono('mano', 34) + '<i class="toque"></i></div>'
            + '<div class="coach-tarjeta" role="dialog" aria-modal="false" aria-label="Práctica guiada" tabindex="-1" data-pos="abajo"><div class="coach-cab"><img class="coach-guia" src="/img/marca/sevb-chiguiro-96.png" width="44" height="44" alt="" decoding="async">'
            + '<div class="coach-cab-txt"><p class="coach-paso"></p><div class="coach-progreso" aria-hidden="true">' + '<i></i>'.repeat(TOTAL) + '</div></div></div>'
            + '<p class="coach-simulacion" hidden><b>Ejemplo ilustrado:</b> es una simulación, no un recorrido real.</p>'
            + '<p class="coach-texto" aria-live="polite"></p><div class="coach-extra"></div>'
            + '<p class="coach-ok" role="status" hidden><span class="ok-chulo">' + icono('check', 18) + '</span><span class="ok-txt"></span></p>'
            + '<div class="coach-botones"><button type="button" class="secondary coach-salir">Salir</button><button type="button" class="secondary coach-atras" hidden>Atrás</button>'
            + '<button type="button" class="secondary coach-alt" hidden></button><button type="button" class="primary-plan-button coach-siguiente">Siguiente</button></div></div>';
        document.body.append(c);
        return c;
    }
    /**
     * El coach sigue al elemento señalado SOLO cuando algo cambia: tamaño de la ventana, desplazamiento, teclado virtual, cambios en la página,
     * el elemento que cambia de tamaño o transiciones/animaciones que lo mueven (se sigue unos instantes por cuadros y se vuelve a dormir).
     * Antes se medía en cada cuadro de pantalla durante toda la práctica.
     */
    function seguir(c) {
        let vivo = true, raf = 0, hasta = 0, ultimo = '', objetivo = null, desplazado = false;
        const hueco = c.querySelector('.coach-hueco'), dedo = c.querySelector('.coach-dedo'), tarjeta = c.querySelector('.coach-tarjeta'), escudo = c.querySelector('.coach-escudo');
        const bl = { arriba: c.querySelector('.b-arriba'), abajo: c.querySelector('.b-abajo'), izq: c.querySelector('.b-izq'), der: c.querySelector('.b-der') };
        const vv = window.visualViewport;
        const W = () => window.innerWidth, H = () => window.innerHeight;
        function bloquear(x, y, w, h) {
            Object.assign(bl.arriba.style, { left: '0px', top: '0px', width: W() + 'px', height: Math.max(0, y) + 'px' });
            Object.assign(bl.abajo.style, { left: '0px', top: (y + h) + 'px', width: W() + 'px', height: Math.max(0, H() - y - h) + 'px' });
            Object.assign(bl.izq.style, { left: '0px', top: y + 'px', width: Math.max(0, x) + 'px', height: h + 'px' });
            Object.assign(bl.der.style, { left: (x + w) + 'px', top: y + 'px', width: Math.max(0, W() - x - w) + 'px', height: h + 'px' });
        }
        /** La tarjeta va del lado donde quepa sin tapar lo señalado (usa el área realmente visible: teclado, barras y zonas seguras). */
        function colocar(r) {
            const vTop = vv ? vv.offsetTop : 0, vAlto = vv ? vv.height : H(), margen = 10;
            const tope = vTop + margen, fondo = vTop + vAlto - margen;
            tarjeta.style.maxHeight = Math.max(160, fondo - tope) + 'px';
            const alto = Math.min(tarjeta.offsetHeight, fondo - tope);
            let pos = 'abajo';
            if (r) {
                const libreArriba = r.top - tope, libreAbajo = fondo - r.bottom, quepeArriba = libreArriba >= alto + 8, quepeAbajo = libreAbajo >= alto + 8;
                const prefiereArriba = (r.top + r.height / 2) > vTop + vAlto * 0.5;
                if (prefiereArriba ? quepeArriba : !quepeAbajo && quepeArriba) pos = 'arriba';
                else if (quepeAbajo) pos = 'abajo';
                else pos = libreArriba > libreAbajo ? 'arriba' : 'abajo';
            }
            tarjeta.dataset.pos = pos;
            tarjeta.style.bottom = 'auto';
            tarjeta.style.top = Math.round(pos === 'arriba' ? tope : fondo - alto) + 'px';
        }
        function actualizar() {
            if (!vivo) return;
            const sel = c.dataset.sel, el = sel ? document.querySelector(sel) : null;
            if (el !== objetivo) { objetivo = el; if (ro) { ro.disconnect(); if (el) ro.observe(el); } desplazado = false; }
            const r = el && visible(el) ? el.getBoundingClientRect() : null;
            if (r && (r.width > 0 || r.height > 0)) {
                const m = 6, x = Math.max(-m, r.left - m), y = Math.max(-m, r.top - m), w = Math.min(W() + 2 * m, r.width + 2 * m), h = Math.min(H() + 2 * m, r.height + 2 * m);
                const clave = [x, y, w, h].map(Math.round).join(',') + c.dataset.dedo + c.dataset.escudo;
                if (clave !== ultimo) {
                    ultimo = clave;
                    Object.assign(hueco.style, { display: 'block', left: x + 'px', top: y + 'px', width: w + 'px', height: h + 'px' });
                    bloquear(x, y, w, h);
                    escudo.hidden = c.dataset.escudo !== '1';
                    if (!escudo.hidden) Object.assign(escudo.style, { left: x + 'px', top: y + 'px', width: w + 'px', height: h + 'px' });
                    dedo.style.display = c.dataset.dedo === '1' ? 'block' : 'none';
                    dedo.style.left = (x + w * 0.62) + 'px'; dedo.style.top = (y + h * 0.55) + 'px';
                }
                colocar(r);
                if ((r.top < 0 || r.bottom > H()) && !desplazado) { desplazado = true; el.scrollIntoView({ block: 'center', behavior: reducido() ? 'auto' : 'smooth' }); rafla(900); }
            } else {
                ultimo = ''; hueco.style.display = 'none'; dedo.style.display = 'none'; escudo.hidden = true;       // sin nada señalado: todo bloqueado menos la tarjeta
                bloquear(0, 0, 0, 0); Object.assign(bl.arriba.style, { height: H() + 'px' });
                colocar(null);
            }
        }
        const pedir = () => { if (!raf && vivo) raf = requestAnimationFrame(() => { raf = 0; actualizar(); if (performance.now() < hasta) pedir(); }); };
        function rafla(ms = 700) { hasta = Math.max(hasta, performance.now() + ms); pedir(); }
        const ro = typeof ResizeObserver === 'function' ? new ResizeObserver(() => rafla(250)) : null;
        const dentro = (n) => n && n.nodeType === 1 && c.contains(n);
        // Los cambios dentro del mapa (el bus que anima, los marcadores que se mueven) ocurren en cada cuadro y no mueven lo que se señala: no cuentan.
        const delMapa = (n) => n && n.nodeType === 1 && !!n.closest('#map');
        const mo = new MutationObserver((lista) => { if (lista.some((m) => !dentro(m.target) && !delMapa(m.target))) rafla(450); });
        mo.observe(document.body, { subtree: true, childList: true, attributes: true, attributeFilter: ['class', 'hidden', 'open'] });
        const alMover = (e) => { if (!dentro(e.target) && !delMapa(e.target)) rafla(750); };
        const alCambiar = () => rafla(300);
        window.addEventListener('resize', alCambiar); window.addEventListener('orientationchange', alCambiar);
        window.addEventListener('scroll', pedir, { capture: true, passive: true });
        if (vv) { vv.addEventListener('resize', alCambiar); vv.addEventListener('scroll', pedir); }
        ['transitionrun', 'transitionend', 'animationend'].forEach((n) => document.addEventListener(n, alMover, true));
        const reposo = setInterval(pedir, 900);               // por si algo se mueve sin avisar (una imagen que carga): una vez cada casi segundo, no cada cuadro
        pedir();
        return {
            rafla,
            detener() {
                vivo = false; cancelAnimationFrame(raf); clearInterval(reposo); mo.disconnect(); if (ro) ro.disconnect();
                window.removeEventListener('resize', alCambiar); window.removeEventListener('orientationchange', alCambiar); window.removeEventListener('scroll', pedir, true);
                if (vv) { vv.removeEventListener('resize', alCambiar); vv.removeEventListener('scroll', pedir); }
                ['transitionrun', 'transitionend', 'animationend'].forEach((n) => document.removeEventListener(n, alMover, true));
            },
        };
    }
    function celebrar() {
        document.getElementById('coachFin')?.remove();
        const f = document.createElement('div');
        f.id = 'coachFin'; f.className = 'coach-fin'; f.setAttribute('role', 'status');
        const confeti = Array.from({ length: 12 }, (_, i) => '<i style="--i:' + i + '"></i>').join('');
        f.innerHTML = '<div class="fin-confeti" aria-hidden="true">' + confeti + '</div><div class="fin-tarjeta"><svg class="fin-chulo" viewBox="0 0 52 52" aria-hidden="true"><circle cx="26" cy="26" r="23"/><path d="M15 27.5 22.5 35 37.5 18"/></svg>'
            + '<h2>¡Muy bien!</h2><p>Ya sabes usar SEVB. Cuando quieras, busca tu viaje de verdad.</p><span class="fin-logro">' + icono('birrete', 20) + 'Logro: Aprendiz</span></div>';
        document.body.append(f);
        const quitar = () => { f.classList.add('saliendo'); setTimeout(() => f.remove(), 320); };
        f.addEventListener('click', quitar);
        setTimeout(quitar, 3400);
    }
    /** Ejemplo ilustrado (solo si el servicio de rutas no responde): todo está marcado como simulación y nada de esto es un recorrido real. */
    function panelDemo() {
        const d = document.createElement('div');
        d.id = 'coachDemo'; d.className = 'coach-demo'; d.setAttribute('aria-label', 'Ejemplo ilustrado (simulación)');
        d.innerHTML = '<p class="demo-sello">Ejemplo ilustrado · simulación</p>'
            + '<div class="demo-tarjeta"><span class="demo-chip">R12</span><span class="demo-main"><strong>Serramonte → Terminal</strong><small class="demo-camina">caminas unas 4 cuadras · bus 6,4 km</small></span><span class="demo-eta"><b>21</b><small>min</small></span></div>'
            + '<ol class="demo-pasos"><li>Camina unas 3 cuadras hasta el paradero.</li><li>Sube a la ruta R12.</li><li>Bájate en «Terminal».</li><li>Camina una cuadra hasta B.</li></ol>'
            + '<div class="demo-transbordo"><span class="demo-chip dos">2 buses</span><small>Si hay que cambiar de bus, se ve así.</small></div>'
            + '<svg class="demo-mapa" viewBox="0 0 300 110" aria-hidden="true" focusable="false"><rect width="300" height="110" rx="14" fill="#e6efe8"/><path d="M-5 70 305 40M60-5 90 115M190-5 170 115" stroke="#fff" stroke-width="9" fill="none"/>'
            + '<path d="M30 92 55 92C100 92 110 52 160 52S238 30 270 22" stroke="#2f3640" stroke-width="4" stroke-dasharray="1 8" stroke-linecap="round" fill="none" opacity="0"/>'
            + '<path d="M52 88C96 86 112 50 160 50S236 34 270 24" stroke="' + ROJO + '" stroke-width="8" stroke-linecap="round" fill="none"/>'
            + '<path d="M22 100 52 88" stroke="#2f3640" stroke-width="4" stroke-dasharray="1 8" stroke-linecap="round" fill="none"/>'
            + '<circle cx="52" cy="88" r="6" fill="' + V + '" stroke="#fff" stroke-width="2.5"/><circle cx="270" cy="24" r="6" fill="#8e1b17" stroke="#fff" stroke-width="2.5"/>'
            + '<circle cx="22" cy="100" r="8" fill="' + V + '"/><text x="22" y="104" text-anchor="middle" font-size="10" font-weight="800" fill="#fff">A</text><circle cx="286" cy="12" r="8" fill="' + NARANJA + '"/><text x="286" y="16" text-anchor="middle" font-size="10" font-weight="800" fill="#fff">B</text></svg>'
            + '<div class="demo-iniciar" aria-hidden="true">Iniciar viaje</div>';
        return d;
    }

    /** opciones.ejemplo: otros puntos de ejemplo. opciones.pausa: milisegundos entre pasos automáticos; con 200 o menos (o con opciones.auto) la práctica se hace sola (pruebas). */
    async function practica(opciones = {}) {
        if (coach) return;
        if (S.Viaje && S.Viaje.activo()) return S.toast('Ya tienes un viaje en curso: termínalo antes de practicar.', { tipo: 'info', ms: 4000 });
        await cerrarMenu();
        for (const id of ['ayudaModal', 'bienvenida']) { const m = $(id); if (m && !m.hidden) m.hidden = true; }
        if (!hayModalAbierto()) document.body.classList.remove('modal-open');
        const ej = opciones.ejemplo || EJEMPLO, P = S.Planner, pausa = opciones.pausa ?? 700;
        const auto = opciones.auto === true || pausa <= 200, tecleo = !auto && !reducido() ? 38 : 0;
        // Lo que la persona tenía antes: se devuelve al terminar o al salir.
        const previo = { puntos: P.puntos(), resultados: !!document.querySelector('#tripResults .trip-option') };
        recordarAbridor();
        document.body.dataset.sinRegistro = '1';                // la búsqueda de práctica no cuenta como historial ni como hábito
        coach = crearCoach();
        document.body.classList.add('practicando');
        const seg = seguir(coach);
        const q = (s) => coach.querySelector(s);
        const botonSig = q('.coach-siguiente'), botonAtras = q('.coach-atras'), botonAlt = q('.coach-alt'), tarjeta = q('.coach-tarjeta');
        let salir = false, despertar = null, demo = false;
        const esperas = new Map();           // temporizadores pendientes: todos se cancelan al salir
        const dormir = (ms) => new Promise((res) => { if (salir || ms <= 0) return res(); const t = setTimeout(() => { esperas.delete(t); res(); }, ms); esperas.set(t, res); });
        const fin = () => { salir = true; esperas.forEach((res, t) => { clearTimeout(t); res(); }); esperas.clear(); if (despertar) despertar('salir'); };
        q('.coach-salir').onclick = fin;
        const onEsc = (e) => { if (e.key === 'Escape') { e.stopPropagation(); fin(); } };
        /** Con Tab solo se puede ir a lo señalado y a los botones de la tarjeta (todo lo demás está bloqueado también para teclado y lectores). */
        const onTab = (e) => {
            if (e.key !== 'Tab') return;
            const sel = coach.dataset.sel, el = sel && coach.dataset.escudo !== '1' ? document.querySelector(sel) : null;
            const lista = [...(el ? (el.matches(FOCO) ? [el] : [...el.querySelectorAll(FOCO)]) : []), ...tarjeta.querySelectorAll(FOCO)].filter(visible);
            if (!lista.length) return;
            const i = lista.indexOf(document.activeElement);
            const sig = e.shiftKey ? (i <= 0 ? lista.length - 1 : i - 1) : (i === lista.length - 1 ? 0 : i + 1);
            e.preventDefault(); lista[sig].focus({ preventScroll: true });
        };
        document.addEventListener('keydown', onEsc, true); document.addEventListener('keydown', onTab, true);
        const verPanel = (evento = 'spevb-ir-inicio') => window.dispatchEvent(new CustomEvent(evento));
        const hayOpciones = () => !!document.querySelector('#tripResults .trip-option');
        let okTimer = 0;
        const confirmar = (texto) => {
            const ok = q('.coach-ok'); ok.hidden = false; q('.ok-txt').textContent = texto;
            ok.classList.remove('entra'); void ok.offsetWidth; ok.classList.add('entra');
            clearTimeout(okTimer); okTimer = setTimeout(() => { ok.hidden = true; }, 1400); esperas.set(okTimer, () => {});
        };
        /** Muestra un paso y espera: el botón principal, «Atrás», el botón extra o que se cumpla la condición. Devuelve 'sig', 'atras', 'alt', 'cond' o 'salir'. */
        function mostrar({ n, texto, sel = null, boton = null, atras = true, dedo = false, escudo = false, extra = '', alt = null, cond = null, foco = true, sub = '' }) {
            coach.dataset.sel = sel || ''; coach.dataset.dedo = dedo ? '1' : '0'; coach.dataset.escudo = escudo ? '1' : '0';
            const pd = sel && /demo-(\w+)/.exec(sel); coach.dataset.demo = pd ? ({ chip: 'tarjeta', eta: 'tarjeta', camina: 'tarjeta' }[pd[1]] || pd[1]) : '';
            tarjeta.classList.remove('cambia'); void tarjeta.offsetWidth; tarjeta.classList.add('cambia');
            q('.coach-paso').textContent = 'Práctica · paso ' + n + ' de ' + TOTAL + (sub ? ' · ' + sub : '');
            coach.querySelectorAll('.coach-progreso i').forEach((i, k) => { i.className = k + 1 < n ? 'hecho' : k + 1 === n ? 'actual' : ''; });
            q('.coach-texto').textContent = texto;
            q('.coach-extra').innerHTML = extra;
            q('.coach-simulacion').hidden = !demo;
            botonSig.hidden = !boton; if (boton) botonSig.textContent = boton;
            botonAtras.hidden = !atras || n === 1 && !sub;
            botonAlt.hidden = !alt; if (alt) botonAlt.textContent = alt;
            seg.rafla(900);
            if (foco) setTimeout(() => { if (!salir && coach) { const b = [botonSig, botonAlt].find(visible); (b || tarjeta).focus({ preventScroll: true }); } }, 80);
            return new Promise((resolver) => {
                let id = 0;
                const r = (v) => { clearInterval(id); despertar = null; resolver(v); };
                despertar = r;
                botonSig.onclick = () => r('sig'); botonAtras.onclick = () => r('atras'); botonAlt.onclick = () => r('alt');
                if (cond) id = setInterval(() => { if (!salir && cond()) r('cond'); }, 200);
            });
        }
        const escribir = async (campo, punto, modoPunto) => {
            const input = $(campo);
            if (input && tecleo > 0) { input.classList.add('coach-escribiendo'); for (let i = 1; i <= punto.nombre.length && !salir; i++) { input.value = punto.nombre.slice(0, i); await dormir(tecleo); } input.classList.remove('coach-escribiendo'); }
            if (!salir) P.poner(modoPunto, punto);
        };
        /** Pasos 1 y 2: la persona pone el punto ella misma o pide el ejemplo con un botón; se confirma y se sigue. */
        async function pasoPunto(n, clave, campo, modoPunto, punto, texto, etiqueta) {
            verPanel(); await dormir(reducido() || auto ? 0 : 380);
            const hecho = () => !!P.puntos()[clave];
            if (auto && !hecho()) { coach.dataset.sel = '#' + campo; await dormir(Math.min(pausa, 300)); await escribir(campo, punto, modoPunto); await dormir(Math.min(pausa, 300)); return 'sig'; }
            const r = await mostrar({ n, texto: hecho() ? 'Ya tienes ' + etiqueta + '. Sigue con el paso siguiente.' : texto, sel: '#' + campo, boton: hecho() ? 'Siguiente' : 'Poner un ejemplo', cond: hecho, foco: !hecho() });
            if (r === 'sig' && !hecho()) { coach.dataset.sel = '#' + campo; botonSig.hidden = true; await escribir(campo, punto, modoPunto); }
            if (r === 'sig' || r === 'cond') { if (!salir && hecho()) { confirmar(etiqueta[0].toUpperCase() + etiqueta.slice(1) + ' puesto'); await dormir(auto ? 150 : 900); } return 'sig'; }
            return r;
        }
        const paso1 = () => pasoPunto(1, 'origen', 'tripOrigin', 'origin', ej.a, 'Aquí va el lugar de donde sales: es el punto A, en verde. Puedes escribir una dirección o tocar «Poner un ejemplo».', 'tu origen');
        const paso2 = () => pasoPunto(2, 'destino', 'tripDestination', 'destination', ej.b, 'Aquí va a dónde quieres llegar: es el punto B, en naranja. El origen es de donde sales; el destino, a donde llegas.', 'tu destino');
        async function paso3() {
            verPanel(); await dormir(reducido() || auto ? 0 : 420);
            if (hayOpciones()) { demo = false; return mostrar({ n: 3, texto: 'Ya buscaste y aparecieron opciones. Sigue para aprender a leerlas.', sel: '#tripResults', boton: 'Siguiente', foco: false }); }
            const inicio = { t: 0 };
            const estado = () => {
                if (hayOpciones()) return 'ok';
                const st = $('tripStatus'), msg = st ? st.textContent.trim() : '';
                if (/Todavía no hay una ruta/.test(($('tripResults') || {}).textContent || '')) return 'vacio';
                if (st && st.classList.contains('error') && !/^Primero selecciona/.test(msg)) return 'fallo';
                if (/^Comparando/.test(msg)) { if (!inicio.t) inicio.t = Date.now(); if (Date.now() - inicio.t > 14000) return 'lento'; return 'buscando'; }
                return 'espera';
            };
            let ultimo = '';
            const r = await mostrar({ n: 3, texto: 'Ahora toca el botón verde «Buscar rutas». Tócalo tú.', sel: '#planTrip', dedo: true, foco: false,
                cond: () => {
                    const e = estado();
                    if (e !== ultimo) {
                        ultimo = e;
                        if (e === 'buscando') { q('.coach-texto').textContent = 'Buscando tus rutas… un momento.'; coach.dataset.dedo = '0'; botonAlt.hidden = true; }
                        if (e === 'lento' || e === 'fallo' || e === 'vacio') { q('.coach-texto').textContent = e === 'vacio' ? 'Para este ejemplo no hay una ruta registrada.' : 'Ahora no pude traer rutas reales.'; botonAlt.hidden = false; botonAlt.textContent = 'Ver un ejemplo ilustrado'; }
                    }
                    return e === 'ok';
                } });
            if (r === 'alt') { demo = true; return 'sig'; }
            if (r === 'cond') { demo = false; confirmar('Ya hay rutas'); await dormir(auto ? 300 : 900); verPanel('spevb-ver-opciones'); await dormir(reducido() || auto ? 0 : 450); return 'sig'; }
            return r;
        }
        /** Paso 4: se explica una cosa por vez (nombre, tiempo, caminata, subir y bajar, transbordo). */
        async function paso4(desdeAtras) {
            let subs;
            if (demo) {
                document.getElementById('coachDemo')?.remove();
                const d = panelDemo(); coach.append(d);
                subs = [['Este es el nombre de la ruta. Es el que ves escrito en el letrero del bus.', '#coachDemo .demo-chip'], ['Aquí está el tiempo aproximado, en minutos. No incluye la espera del bus.', '#coachDemo .demo-eta'],
                    ['Y aquí, cuánto caminas y cuánto vas en el bus.', '#coachDemo .demo-camina'], ['Aquí ves dónde subir, dónde bajar y cuánto caminas en cada tramo.', '#coachDemo .demo-pasos'], ['Si no hay un bus directo, verás «2 buses»: es un transbordo.', '#coachDemo .demo-transbordo', '']];
            } else {
                const op = '#tripResults .trip-option', hayTransbordo = !!document.querySelector('#tripResults .transfer-option');
                subs = [['Este es el nombre de la ruta. Es el que ves escrito en el letrero del bus.', op + ' .route-chip'], ['Aquí está el tiempo aproximado, en minutos. No incluye la espera del bus.', op + ' .opt-eta'],
                    ['Y aquí, cuánto caminas y cuánto vas en el bus.', op + ' .opt-main small'], ['Aquí ves dónde subir y dónde bajar, paso por paso.', op + '[open] .result-metrics', 'abrir'],
                    hayTransbordo ? ['Esta opción es un transbordo: «2 buses». Bajas del primero y subes al segundo.', '#tripResults .transfer-option'] : ['Si no hay un bus directo, verás «2 buses»: es un transbordo.', null, MINI_TRANSBORDO]];
            }
            let j = desdeAtras ? subs.length - 1 : 0, r = 'sig';
            while (!salir && j >= 0 && j < subs.length) {
                const [texto, sel, accion] = subs[j];
                if (accion === 'abrir') { const d = document.querySelector('#tripResults .trip-option'); if (d) d.open = true; await dormir(reducido() || auto ? 0 : 350); }
                r = await mostrar({ n: 4, sub: (j + 1) + ' de ' + subs.length, texto, sel, boton: j === subs.length - 1 ? 'Siguiente paso' : 'Siguiente', extra: accion === MINI_TRANSBORDO || (typeof accion === 'string' && accion.startsWith('<')) ? accion : '', atras: true, foco: true });
                if (r === 'atras') { if (j === 0) { return 'atras'; } j--; } else if (r === 'sig' || r === 'cond') j++; else return r;
            }
            return r === 'salir' ? 'salir' : 'sig';
        }
        async function paso5() {
            if (!demo) {
                const sel = '#tripResults .trip-option summary';
                if (!document.querySelector('.trip-option.selected')) {
                    const r = await mostrar({ n: 5, sub: '1 de 2', texto: 'Toca una opción para ver su recorrido en el mapa.', sel, boton: 'Siguiente', dedo: true, cond: () => !!document.querySelector('.trip-option.selected') });
                    if (r === 'atras' || r === 'salir') return r;
                    if (r === 'cond') { confirmar('Ruta elegida'); await dormir(auto ? 200 : 900); }
                    else if (r === 'sig' && !document.querySelector('.trip-option.selected')) { document.querySelector(sel)?.click(); await dormir(auto ? 200 : 500); }
                }
            }
            verPanel('spevb-focus-map'); await dormir(reducido() || auto ? 0 : 600);
            return mostrar({ n: 5, sub: demo ? '' : '2 de 2', texto: demo ? 'Así se ve el recorrido en el mapa. Mira los colores y los símbolos.' : 'Este es el recorrido en el mapa. Mira los colores y los símbolos.', sel: demo ? '#coachDemo .demo-mapa' : '#map', boton: 'Siguiente', extra: LEYENDA });
        }
        async function paso6() {
            if (!demo && !document.querySelector('.trip-option.selected')) { document.querySelector('#tripResults .trip-option summary')?.click(); await dormir(auto ? 150 : 500); }
            verPanel(); await dormir(reducido() || auto ? 0 : 420);
            const real = !demo && visible($('startTrip'));
            return mostrar({ n: 6, texto: 'Aquí empieza tu viaje: cuando estés en el paradero, toca «Iniciar viaje» y te aviso cuándo caminar, subir y bajar. Ahora no lo toques: la práctica no usa tu ubicación.',
                sel: real ? '#startTrip' : '#coachDemo .demo-iniciar', escudo: true, boton: 'Siguiente', extra: MINI_VIAJE });
        }
        async function paso7() {
            return mostrar({ n: 7, texto: '¡Listo! Ya sabes poner tu salida y tu llegada, buscar, elegir un bus y empezar el viaje. ¿Hacemos uno de verdad?', sel: null, boton: 'Terminar práctica', alt: 'Hacer mi viaje real', foco: true });
        }
        let completo = false, viajeReal = false;
        const restaurar = () => {
            try {
                P.limpiar();
                const { origen, destino } = previo.puntos;
                if (origen) P.poner('origin', origen);
                if (destino) P.poner('destination', destino);
                if (previo.resultados && origen && destino) setTimeout(() => { const b = $('planTrip'); if (b && !document.body.classList.contains('practicando')) b.click(); }, 300);
            } catch { /* sin planificador */ }
            setTimeout(() => { delete document.body.dataset.sinRegistro; }, previo.resultados ? 9000 : 400);
        };
        try {
            P.limpiar();
            await dormir(180);
            let i = 1, volver = false;
            const pasos = [null, paso1, paso2, paso3, () => paso4(volver), paso5, paso6, paso7];
            while (!salir && i <= TOTAL) {
                const r = await pasos[i]();
                if (salir || r === 'salir') break;
                volver = r === 'atras';
                if (r === 'alt' && i === TOTAL) { viajeReal = true; completo = true; break; }
                if (r === 'atras') i = Math.max(1, i - 1); else { if (i === TOTAL) { completo = true; break; } i++; }
            }
        } finally {
            const cancelado = salir && !completo;
            fin(); seg.detener(); clearTimeout(okTimer);
            document.removeEventListener('keydown', onEsc, true); document.removeEventListener('keydown', onTab, true);
            coach.remove(); coach = null; document.body.classList.remove('practicando');
            restaurar();
            if (completo) { if (S.Logros && S.Logros.marcarPractica) S.Logros.marcarPractica(); celebrar(); }
            else S.toast(cancelado ? 'Saliste de la práctica. Tus puntos siguen como estaban.' : 'Cuando quieras, busca tu viaje de verdad.', { tipo: 'info', ms: 5000, clave: 'practica' });
            if (viajeReal) { abridor = null; setTimeout(() => { verPanel(); $('tripOrigin')?.focus({ preventScroll: true }); }, 400); } else devolverFoco();
        }
    }

    window.Spevb.Guia = { abrirBienvenida, abrirAyuda, abrirLeccion, practica, estadoActual, visto, EJEMPLO, ESCENAS, TEMAS };

    // La primera vez, la bienvenida sale sola (no si se abrió un enlace compartido ni si ya hay un viaje en curso).
    document.addEventListener('DOMContentLoaded', () => {
        setTimeout(() => {
            const compartido = /[?&]a=/.test(location.search) && /[?&]b=/.test(location.search);
            if (!visto() && !compartido && !hayModalAbierto() && !(S.Viaje && S.Viaje.activo())) abrirBienvenida();
        }, 900);
    });
})();
