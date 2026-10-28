'use strict';
/* Reproductor de recorrido: un bus recorre la línea con estela, giro según el rumbo,
   controles de pausa, velocidad, reinicio, barra de avance y seguimiento de cámara. */
(() => {
    const SPEEDS = [1, 2, 4];
    const fmtKm = new Intl.NumberFormat('es-CO', { minimumFractionDigits: 1, maximumFractionDigits: 1 });
    const reducedMotion = window.matchMedia?.('(prefers-reduced-motion: reduce)').matches;
    const safeColor = (c) => (/^#[0-9a-f]{6}$/i.test(c || '') ? c : '#176650');

    // ---- Bus en 3D: capas apiladas en el eje Z bajo una cámara inclinada (CSS 3D, sin librerías) ----
    const hex = (c) => [1, 3, 5].map((i) => parseInt(c.slice(i, i + 2), 16));
    const shade = (c, f) => '#' + hex(c).map((v) => {
        const x = f < 0 ? v * (1 + f) : v + (255 - v) * f;
        return Math.max(0, Math.min(255, Math.round(x))).toString(16).padStart(2, '0');
    }).join('');
    const part = (cls, style) => {
        const e = document.createElement('i');
        e.className = cls;
        if (style) Object.entries(style).forEach(([k, v]) => e.style.setProperty(k, v));
        return e;
    };
    // ---- Modelo del bus: una buseta blanca de Villavicencio (trompa corta, parabrisas grande con letrero, ventanas
    //      corridas, puerta delantera derecha y franjas del color de la ruta). Medidas en unidades del modelo (2 px por
    //      unidad lógica: el conjunto se reduce a la mitad con la escala del mapa para que los detalles queden nítidos).
    const NS = 'http://www.w3.org/2000/svg';
    const DIM = { w: 44, lm: 84, ls: 14, h: 34, hs: 16, clear: 6 };    // ancho, largo del cuerpo, largo de la trompa, altos, luz al piso
    const LEN = DIM.lm + DIM.ls;                                        // largo total (98)

    /** Caja con caras con nombre del bus: nose (frente), rear, right, left, top. Centro en (x, y, z); y crece hacia atrás. */
    function cube(cls, { w, h, d, x = 0, y = 0, z = 0 }) {
        const g = part('cube ' + cls);
        g.style.transform = 'translate3d(' + x + 'px,' + y + 'px,' + z + 'px) rotateX(-90deg)';
        const faces = {};
        const face = (name, fw, fh, tf) => {
            const e = part('face ' + name);
            e.style.width = fw + 'px'; e.style.height = fh + 'px';
            e.style.left = -fw / 2 + 'px'; e.style.top = -fh / 2 + 'px';
            e.style.transform = tf;
            g.append(e);
            faces[name] = e;
        };
        face('rear', w, h, 'translateZ(' + d / 2 + 'px)');
        face('nose', w, h, 'rotateY(180deg) translateZ(' + d / 2 + 'px)');
        face('right', d, h, 'rotateY(90deg) translateZ(' + w / 2 + 'px)');
        face('left', d, h, 'rotateY(-90deg) translateZ(' + w / 2 + 'px)');
        face('top', w, d, 'rotateX(90deg) translateZ(' + h / 2 + 'px)');
        return { g, faces };
    }
    const addTo = (face, ...cls) => cls.map((c) => { const e = part(c); face.append(e); return e; });

    /** Vista cenital (2D): la buseta vista desde arriba, con el frente hacia arriba. */
    function build2DBus(color) {
        const dark = shade(color, -0.35), light = shade(color, 0.35);
        const svg = document.createElementNS(NS, 'svg');
        svg.setAttribute('class', 'bus2d');
        svg.setAttribute('viewBox', '-5 0 54 100');
        svg.setAttribute('width', '54');
        svg.setAttribute('height', '100');
        svg.setAttribute('aria-hidden', 'true');
        svg.innerHTML = `
<defs>
  <linearGradient id="g-body" x1="0" x2="1"><stop offset="0" stop-color="#d9dcd7"/><stop offset=".18" stop-color="#fbfbf8"/><stop offset=".82" stop-color="#fbfbf8"/><stop offset="1" stop-color="#d4d7d2"/></linearGradient>
  <linearGradient id="g-roof" x1="0" x2="1" y1="0" y2="1"><stop offset="0" stop-color="#f4f5f2"/><stop offset="1" stop-color="#dfe2dd"/></linearGradient>
  <linearGradient id="g-glass" x1="0" y1="0" x2="1" y2="1"><stop offset="0" stop-color="#3b5a6d"/><stop offset=".55" stop-color="#1c3341"/><stop offset="1" stop-color="#162936"/></linearGradient>
</defs>
<!-- ruedas y espejos (asoman a los lados) -->
<rect x="-1.5" y="24" width="4.5" height="13" rx="1.6" fill="#1a1d1c"/><rect x="41" y="24" width="4.5" height="13" rx="1.6" fill="#1a1d1c"/>
<rect x="-1.5" y="68" width="4.5" height="13" rx="1.6" fill="#1a1d1c"/><rect x="41" y="68" width="4.5" height="13" rx="1.6" fill="#1a1d1c"/>
<rect x="-3.2" y="17.5" width="5.4" height="3.4" rx="1.2" fill="#202423"/><rect x="41.8" y="17.5" width="5.4" height="3.4" rx="1.2" fill="#202423"/>
<!-- carrocería: trompa corta al frente y cuerpo más ancho -->
<path d="M13 1.2 H31 Q37.5 1.2 37.5 7 V13.5 Q37.5 14.5 38.5 14.5 H41 Q43 14.5 43 17 V93.5 Q43 98.8 37.5 98.8 H6.5 Q1 98.8 1 93.5 V17 Q1 14.5 3 14.5 H5.5 Q6.5 14.5 6.5 13.5 V7 Q6.5 1.2 13 1.2 Z" fill="url(#g-body)" stroke="#9aa09b" stroke-width=".6"/>
<!-- faros, rejilla y luces ámbar -->
<rect x="9" y="1.8" width="7" height="3.2" rx="1.4" fill="#fff6c4" stroke="#c9a93a" stroke-width=".4"/><rect x="28" y="1.8" width="7" height="3.2" rx="1.4" fill="#fff6c4" stroke="#c9a93a" stroke-width=".4"/>
<rect x="17.5" y="2.6" width="9" height="2.4" rx="1" fill="#2b302f"/>
<rect x="9.5" y="7.5" width="6" height="2.4" rx="1.1" fill="#ffb02e"/><rect x="28.5" y="7.5" width="6" height="2.4" rx="1.1" fill="#ffb02e"/>
<!-- capó y parabrisas con letrero -->
<path d="M8 13.5 H36 V15 H8 Z" fill="#c7cbc6"/>
<path d="M6.5 28.5 L9.2 15.4 H34.8 L37.5 28.5 Z" fill="url(#g-glass)" stroke="#0f1d26" stroke-width=".6"/>
<path d="M9.4 16.2 L14.5 16.2 L10.6 27.6 L8.2 27.6 Z" fill="#fff" opacity=".22"/><path d="M19.5 16.2 L22 16.2 L18 27.6 L15.5 27.6 Z" fill="#fff" opacity=".12"/>
<rect x="16.5" y="18.6" width="11" height="6.2" rx=".8" fill="#fffef6" stroke="#b8b8aa" stroke-width=".4"/>
<rect x="17.6" y="19.7" width="8.8" height="1.7" fill="#d23a2a"/><rect x="18.4" y="22.4" width="7.2" height="1" fill="#2d3a40"/>
<!-- techo: tapas de ventilación, nervaduras y franjas con el color de la ruta -->
<rect x="4" y="30.5" width="36" height="66" rx="3.2" fill="url(#g-roof)" stroke="#aeb3ae" stroke-width=".5"/>
<rect x="1.6" y="30" width="3.4" height="67" rx="1.2" fill="${color}"/><rect x="39" y="30" width="3.4" height="67" rx="1.2" fill="${color}"/>
<rect x="5" y="30" width=".9" height="67" fill="${dark}" opacity=".55"/><rect x="38.1" y="30" width=".9" height="67" fill="${dark}" opacity=".55"/>
<g stroke="#c4c8c3" stroke-width=".7"><path d="M8 52 H36"/><path d="M8 63 H36"/><path d="M8 85 H36"/></g>
<rect x="12" y="38" width="20" height="11.5" rx="2.2" fill="#eef1ee" stroke="#a9afaa" stroke-width=".6"/><rect x="14.5" y="40.3" width="15" height="6.9" rx="1.4" fill="#d7dcd8"/>
<rect x="12" y="66" width="20" height="11.5" rx="2.2" fill="#eef1ee" stroke="#a9afaa" stroke-width=".6"/><rect x="14.5" y="68.3" width="15" height="6.9" rx="1.4" fill="#d7dcd8"/>
<rect x="19.5" y="84" width="5" height="9" rx="1.2" fill="#e6e9e5" stroke="#a9afaa" stroke-width=".5"/>
<rect x="8" y="31.4" width="5.4" height="2.4" rx="1.1" fill="#ffb02e"/><rect x="30.6" y="31.4" width="5.4" height="2.4" rx="1.1" fill="#ffb02e"/>
<!-- puerta delantera derecha, luneta y luces traseras -->
<rect x="40.6" y="34" width="2.1" height="17" rx=".8" fill="#2a4252"/>
<path d="M9 94.2 H35 L33.6 97 H10.4 Z" fill="url(#g-glass)"/>
<rect x="2.6" y="96.2" width="6.5" height="2" rx=".9" fill="#d62d20"/><rect x="34.9" y="96.2" width="6.5" height="2" rx=".9" fill="#d62d20"/>
<rect x="1.6" y="90" width="40.8" height="3.2" rx="1" fill="${light}" opacity=".0"/>
`;
        return svg;
    }

    /** Vista 3D: cajas con caras (techo, laterales, frente, trasera), ruedas y espejos. */
    function build3DBus(color) {
        const wrap = document.createElement('div');
        wrap.className = 'bus3d';
        wrap.style.setProperty('--c', color);
        wrap.style.setProperty('--cd', shade(color, -0.38));
        wrap.style.setProperty('--cl', shade(color, 0.4));
        const ground = part('bus-halo', { '--bus-color': color });
        const stage = part('bus3d-stage');
        const rot = part('bus3d-rot');          // gira con el rumbo (rotateZ)
        const body = part('bus3d-body');        // se inclina en las curvas (rotateY)
        rot.append(part('bus3d-shadow'), part('bus3d-beam'));

        const { w, lm, ls, h, hs, clear } = DIM;
        const yMain = -LEN / 2 + ls + lm / 2;        // centro del cuerpo (el frente queda en y = -LEN/2)
        const ySnout = -LEN / 2 + ls / 2;

        // chasis oscuro bajo el cuerpo
        body.append(cube('chassis', { w: w - 6, h: clear + 2, d: LEN - 8, y: 0, z: (clear + 2) / 2 }).g);
        // cuerpo principal
        const main = cube('main', { w, h, d: lm, y: yMain, z: clear + h / 2 });
        body.append(main.g);
        // trompa (capó) más baja y angosta
        const snout = cube('snout', { w: w - 4, h: hs, d: ls, y: ySnout, z: clear + hs / 2 });
        body.append(snout.g);

        // frente: parabrisas con letrero; trompa con faros y rejilla
        addTo(main.faces.nose, 'glassfront', 'placard', 'wipers');
        addTo(snout.faces.nose, 'headlamp l', 'headlamp r', 'grille', 'plate', 'bumper');
        addTo(snout.faces.left, 'sidemark');
        addTo(snout.faces.right, 'sidemark');
        // laterales: ventanas, franja, puerta (solo derecha)
        addTo(main.faces.right, 'door');
        addTo(main.faces.rear, 'glassrear', 'tail l', 'tail r', 'plate');
        // techo: tapas de ventilación y luces ámbar
        addTo(main.faces.top, 'hatch a', 'hatch b', 'hatch c', 'beacon l', 'beacon r');

        // ruedas (disco con su pasarruedas, a cada lado) y espejos
        const wheelR = 9;
        [[-1, -LEN / 2 + 30], [1, -LEN / 2 + 30], [-1, LEN / 2 - 22], [1, LEN / 2 - 22]].forEach(([side, y]) => {
            [['arch', 0.3], ['wheel', 0.9]].forEach(([cls, out]) => {
                const disc = part(cls);
                disc.style.transform = 'translate3d(' + side * (w / 2 + out) + 'px,' + y + 'px,' + wheelR + 'px) rotateY(' + side * 90 + 'deg)';
                body.append(disc);
            });
        });
        [-1, 1].forEach((side) => {
            const mirror = cube('mirror', { w: 3.6, h: 9, d: 2.2, x: side * (w / 2 + 3), y: -LEN / 2 + ls + 5, z: clear + h - 10 });
            body.append(mirror.g);
        });
        rot.append(body);
        body.append(build2DBus(color));
        stage.append(rot);
        wrap.append(ground, stage);
        return { wrap, rot, body };
    }

    function createBusPlayer(map) {
        let st = null;
        // Lienzo propio: la estela se repinta sin redibujar las demás capas del mapa.
        const renderer = L.canvas({ padding: 0.2 });
        let panel, btnPlay, btnSpeed, btnRestart, btn3d, range, readout, followBox;
        // El modelo 2D es el predeterminado; el 3D solo se usa si la persona lo activa (se recuerda en este navegador).
        let flat = true;
        try { flat = localStorage.getItem('spevb-bus-modelo') !== '3d'; } catch { /* sin almacenamiento */ }
        const nav = navigator, lowEnd = ((nav.hardwareConcurrency || 8) <= 4 && (nav.deviceMemory || 4) <= 2) || nav.connection?.saveData;

        const control = L.control({ position: 'topright' });
        control.onAdd = () => {
            panel = document.createElement('div');
            panel.className = 'bus-panel';
            panel.setAttribute('role', 'group');
            panel.setAttribute('aria-label', 'Simulación del recorrido del bus');
            const mk = (cls, label) => {
                const b = document.createElement('button');
                b.type = 'button';
                b.className = cls;
                b.setAttribute('aria-label', label);
                b.title = label;
                return b;
            };
            btnPlay = mk('bus-btn bus-play', 'Pausar o reanudar el bus');
            btnRestart = mk('bus-btn', 'Reiniciar recorrido');
            btnRestart.textContent = '↺';
            btnSpeed = mk('bus-btn bus-speed', 'Cambiar velocidad');
            btn3d = mk('bus-btn bus-3d', 'Alternar vista 3D del bus');
            btn3d.textContent = '3D';
            range = document.createElement('input');
            range.type = 'range';
            range.min = '0';
            range.max = '1000';
            range.value = '0';
            range.className = 'bus-range';
            range.setAttribute('aria-label', 'Avance del recorrido');
            readout = document.createElement('span');
            readout.className = 'bus-readout';
            const label = document.createElement('label');
            label.className = 'bus-follow';
            followBox = document.createElement('input');
            followBox.type = 'checkbox';
            label.append(followBox, document.createTextNode(' Seguir bus'));
            const row = document.createElement('div');
            row.className = 'bus-row';
            row.append(btnPlay, btnRestart, btnSpeed, btn3d, range);
            const row2 = document.createElement('div');
            row2.className = 'bus-row bus-row-meta';
            row2.append(readout, label);
            // Botón para ocultar los controles del bus (el bus sigue animándose en el mapa): libera espacio, sobre todo en el celular.
            const btnMin = mk('bus-min', 'Ocultar o mostrar los controles del bus');
            btnMin.textContent = '–';
            btnMin.addEventListener('click', () => window.Spevb.Ajustes && window.Spevb.Ajustes.set('busMin', !panel.classList.contains('collapsed')));
            panel.append(btnMin, row, row2);
            const aplicarMin = () => {
                const min = window.Spevb.Ajustes ? window.Spevb.Ajustes.get('busMin') : false;
                panel.classList.toggle('collapsed', !!min);
                btnMin.textContent = min ? '🚌' : '–';
                btnMin.title = btnMin.ariaLabel = min ? 'Mostrar los controles del bus' : 'Ocultar los controles del bus';
            };
            window.addEventListener('spevb-ajustes', aplicarMin);
            aplicarMin();

            btnPlay.addEventListener('click', () => togglePlay());
            btnRestart.addEventListener('click', () => {
                if (!st) return;
                st.d = 0;
                st.wrap = false;
                st.playing = true;
                st.dirty = true;
                render();
                syncButtons();
            });
            btn3d.addEventListener('click', () => {
                flat = !flat;
                try { localStorage.setItem('spevb-bus-modelo', flat ? '2d' : '3d'); } catch { /* sin almacenamiento */ }
                if (st) apply3D();
                syncButtons();
            });
            btnSpeed.addEventListener('click', () => {
                if (!st) return;
                st.speedIdx = (st.speedIdx + 1) % SPEEDS.length;
                syncButtons();
            });
            range.addEventListener('input', () => {
                if (!st) return;
                st.d = (Number(range.value) / 1000) * st.total;
                st.wrap = false;
                st.dirty = true;
                render();
            });
            followBox.addEventListener('change', () => {
                if (!st) return;
                st.follow = followBox.checked;
                if (st.follow) {
                    const p = at(st.d);
                    map.setView([p.lat, p.lng], Math.max(map.getZoom(), 16), { animate: true });
                }
            });
            L.DomEvent.disableClickPropagation(panel);
            L.DomEvent.disableScrollPropagation(panel);
            return panel;
        };
        control.addTo(map);
        panel.hidden = true;
        map.on('dragstart', () => {
            if (st?.follow) {
                st.follow = false;
                followBox.checked = false;
            }
        });

        const busScale = () => Math.max(0.75, Math.min(2.2, 0.75 + (map.getZoom() - 12) * 0.22));
        function apply3D() {
            if (!st) return;
            st.box.classList.toggle('flat', flat);
            st.box.style.setProperty('--bus-scale', busScale().toFixed(2));
        }
        map.on('zoomend', () => apply3D());

        function syncButtons() {
            if (!st) return;
            btnPlay.textContent = st.playing ? '❚❚' : '▶';
            btnPlay.setAttribute('aria-pressed', String(st.playing));
            btnSpeed.textContent = SPEEDS[st.speedIdx] + '×';
            btn3d.setAttribute('aria-pressed', String(!flat));
            btn3d.classList.toggle('on', !flat);
        }

        function togglePlay() {
            if (!st) return;
            if (!st.playing && st.d >= st.total) st.d = 0;
            st.playing = !st.playing;
            st.wrap = false;
            st.dirty = true;
            syncButtons();
        }

        function at(d) {
            const cum = st.cum;
            let lo = 0, hi = cum.length - 1;
            while (hi - lo > 1) {
                const mid = (lo + hi) >> 1;
                if (cum[mid] <= d) lo = mid; else hi = mid;
            }
            const a = st.pts[lo], b = st.pts[hi];
            const span = cum[hi] - cum[lo];
            const t = span > 0 ? Math.min(1, Math.max(0, (d - cum[lo]) / span)) : 0;
            return { lat: a.lat + (b.lat - a.lat) * t, lng: a.lng + (b.lng - a.lng) * t, i: lo };
        }

        function render(dt = 1) {
            const d = Math.min(Math.max(st.d, 0), st.total);
            const p = at(d);
            const p1 = at(Math.max(0, d - 25)), p2 = at(Math.min(st.total, d + 25));
            const dx = (p2.lng - p1.lng) * Math.cos((p.lat * Math.PI) / 180), dy = p2.lat - p1.lat;
            if (dx || dy) {
                const target = (Math.atan2(dx, dy) * 180) / Math.PI;
                const diff = ((target - st.heading + 540) % 360) - 180;
                const step = diff * Math.min(1, dt * 8);
                st.heading += step;
                st.dHeading = step / Math.max(dt, 0.001) / 60;
            }
            st.marker.setLatLng([p.lat, p.lng]);
            // Inclinación en las curvas (como una moto o un bus que se carga hacia fuera), suavizada.
            const turn = Math.max(-9, Math.min(9, st.dHeading * 40));
            st.lean += (turn - st.lean) * Math.min(1, dt * 6);
            st.rot.style.transform = 'rotateZ(' + st.heading.toFixed(1) + 'deg)';
            st.body.style.transform = 'rotateY(' + st.lean.toFixed(1) + 'deg)';
            if (p.i !== st.trailIdx) {
                const now = performance.now();
                // La estela crece sin copiar el arreglo y se repinta como máximo cada 80 ms.
                if (p.i < st.trailIdx || now - st.trailAt > 80 || p.i === 0) {
                    if (p.i < st.trailIdx) st.trailPts.length = 0;
                    for (let k = st.trailPts.length; k <= p.i; k++) st.trailPts.push(st.pts[k]);
                    st.trailIdx = p.i;
                    st.trailAt = now;
                    st.trail.setLatLngs(st.trailPts);
                }
            }
            st.head.setLatLngs([...st.pts.slice(Math.max(st.trailIdx, 0), p.i + 1), [p.lat, p.lng]]);
            range.value = String(Math.round((d / st.total) * 1000));
            readout.textContent = fmtKm.format(d / 1000) + ' / ' + fmtKm.format(st.total / 1000) + ' km';
            if (st.follow) map.panTo([p.lat, p.lng], { animate: false });
        }

        function frame(ts) {
            if (!st) return;
            if (lowEnd && ts - st.last < 30) { st.raf = requestAnimationFrame(frame); return; }   // ≈ 30 fps en equipos modestos
            const dt = Math.min((ts - st.last) / 1000, 0.1);
            st.last = ts;
            if (st.wrap && ts >= st.pauseUntil) {
                st.d = 0;
                st.wrap = false;
            }
            if (st.playing && !st.wrap) {
                st.d += st.speed * SPEEDS[st.speedIdx] * dt;
                if (st.d >= st.total) {
                    st.d = st.total;
                    if (st.loop) {
                        st.wrap = true;
                        st.pauseUntil = ts + 1500;
                    } else {
                        st.playing = false;
                        syncButtons();
                    }
                }
            }
            if ((st.playing && !st.wrap) || st.dirty) {
                st.dirty = false;
                render(dt);
            }
            st.raf = requestAnimationFrame(frame);
        }

        function stop() {
            if (!st) return;
            cancelAnimationFrame(st.raf);
            [st.marker, st.trail, st.head].forEach((l) => map.removeLayer(l));
            st = null;
            panel.hidden = true;
            followBox.checked = false;
        }

        function play(coordinates, opts = {}) {
            stop();
            const lines = Array.isArray(coordinates?.[0]?.[0]) ? coordinates.flat() : coordinates;
            const pts = (lines || [])
                .filter((c) => Array.isArray(c) && Number.isFinite(c[0]) && Number.isFinite(c[1]))
                .map((c) => L.latLng(c[1], c[0]));
            if (pts.length < 2) return;
            const cum = [0];
            for (let i = 1; i < pts.length; i++) cum.push(cum[i - 1] + pts[i - 1].distanceTo(pts[i]));
            const total = cum[cum.length - 1];
            if (!(total > 0)) return;
            const color = safeColor(opts.color);

            const built = build3DBus(color);
            const marker = L.marker(pts[0], {
                icon: L.divIcon({ className: 'bus-wrapper', html: built.wrap, iconSize: [72, 72], iconAnchor: [36, 36] }),
                zIndexOffset: 2000,
                keyboard: false,
                interactive: false, // el bus nunca debe interceptar clics del mapa (p. ej. «Elegir en mapa»)
            }).addTo(map);
            const lineOpts = { color: '#fff', weight: 4, opacity: 0.95, interactive: false, renderer };
            const trail = L.polyline([pts[0]], lineOpts).addTo(map);
            const head = L.polyline([pts[0], pts[0]], lineOpts).addTo(map);

            const seconds = Math.min(45, Math.max(12, total / 200));
            st = {
                pts, cum, total, marker, rot: built.rot, body: built.body, box: built.wrap, lean: 0, dHeading: 0, trail, head,
                d: 0, heading: 0, trailIdx: -1, trailAt: 0, trailPts: [], dirty: true, speedIdx: 0,
                speed: total / seconds, loop: opts.loop !== false,
                playing: !reducedMotion, follow: false, wrap: false, pauseUntil: 0,
                last: performance.now(), raf: 0,
            };
            panel.hidden = false;
            followBox.checked = false;
            apply3D();
            syncButtons();
            render();
            st.raf = requestAnimationFrame(frame);
        }

        return { play, stop };
    }

    window.Spevb = Object.assign(window.Spevb || {}, { createBusPlayer, busModel: { build3DBus } });
})();
