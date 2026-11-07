'use strict';
/* PROTOTIPO (Puerta C) — vista cenital 2D de la buseta SEVB. NO está integrado en la aplicación.
   Misma firma y contrato que build2DBus de bus.js: recibe el color de la ruta y devuelve un <svg class="bus2d"> con
   viewBox "-5 0 54 100", 54×100 px, aria-hidden, frente hacia arriba, sin scripts, sin referencias externas y SIN
   gradientes ni filtros (por eso no hay identificadores que puedan chocar si hay varios buses en la página).
   El color de la ruta solo se aplica a: franjas del techo y banda del letrero. Con colores muy claros o muy oscuros se
   añade un contorno para que la franja no se pierda sobre la carrocería ni se confunda con ventanas, chasis o neumáticos. */
(() => {
    const NS = 'http://www.w3.org/2000/svg';
    const safeColor = (c) => (/^#[0-9a-f]{6}$/i.test(c || '') ? c : '#176650');
    const hex = (c) => [1, 3, 5].map((i) => parseInt(c.slice(i, i + 2), 16));
    const shade = (c, f) => '#' + hex(c).map((v) => {
        const x = f < 0 ? v * (1 + f) : v + (255 - v) * f;
        return Math.max(0, Math.min(255, Math.round(x))).toString(16).padStart(2, '0');
    }).join('');
    /** Luminancia relativa WCAG (0 negro … 1 blanco). */
    const luminance = (c) => {
        const [r, g, b] = hex(c).map((v) => { v /= 255; return v <= 0.03928 ? v / 12.92 : ((v + 0.055) / 1.055) ** 2.4; });
        return 0.2126 * r + 0.7152 * g + 0.0722 * b;
    };

    function buildBus2D(color) {
        color = safeColor(color);
        const L = luminance(color);
        const extreme = L > 0.45 || L < 0.05;                       // muy claro (amarillo, blanco) o muy oscuro (negro)
        const edge = L > 0.45 ? shade(color, -0.55) : L < 0.05 ? shade(color, 0.6) : shade(color, -0.38);
        const ew = extreme ? 0.9 : 0.45;
        const svg = document.createElementNS(NS, 'svg');
        svg.setAttribute('class', 'bus2d');
        svg.setAttribute('viewBox', '-5 0 54 100');
        svg.setAttribute('width', '54');
        svg.setAttribute('height', '100');
        svg.setAttribute('aria-hidden', 'true');
        svg.setAttribute('focusable', 'false');
        svg.innerHTML =
'<g fill="#16191a"><rect x="-.2" y="15" width="3.6" height="11.5" rx="1.6"/><rect x="40.6" y="15" width="3.6" height="11.5" rx="1.6"/>' +
'<rect x="-.2" y="67.5" width="3.6" height="11.5" rx="1.6"/><rect x="40.6" y="67.5" width="3.6" height="11.5" rx="1.6"/></g>' +
'<g fill="#2a2f2e"><rect x="-3.6" y="17.5" width="5.2" height="3" rx="1.2"/><rect x="41.4" y="17.5" width="5.2" height="3" rx="1.2"/></g>' +
'<rect x="3" y="14.5" width="38" height="84.5" rx="5.5" fill="#f6f5ef" stroke="#9aa09b" stroke-width=".6"/>' +
'<rect x="7" y="1.5" width="30" height="15" rx="5.5" fill="#f6f5ef" stroke="#9aa09b" stroke-width=".6"/>' +
'<path d="M22 6V15" stroke="#dcdfd9" stroke-width=".6"/>' +
'<rect x="8" y=".2" width="28" height="2.3" rx="1" fill="#3a403f"/><rect x="19.5" y=".5" width="5" height="1.5" rx=".5" fill="#ffd400"/>' +
'<rect x="8.6" y="3.4" width="7.4" height="3.4" rx="1.5" fill="#fff3c2" stroke="#c9a93a" stroke-width=".4"/><rect x="28" y="3.4" width="7.4" height="3.4" rx="1.5" fill="#fff3c2" stroke="#c9a93a" stroke-width=".4"/>' +
'<rect x="17.5" y="3.8" width="9" height="4" rx="1" fill="#2b302f"/>' +
'<rect x="9.5" y="9.4" width="5" height="2" rx="1" fill="#ffb02e"/><rect x="29.5" y="9.4" width="5" height="2" rx="1" fill="#ffb02e"/>' +
'<path d="M6.2 31 L8.4 17.2 H35.6 L37.8 31 Z" fill="#20384a" stroke="#0f1d26" stroke-width=".6"/>' +
'<path d="M9 18 H15 L11.4 30 H8.4 Z" fill="#fff" opacity=".2"/>' +
'<rect x="15" y="19.5" width="14" height="6" rx=".8" fill="#fffef6" stroke="#b8b8aa" stroke-width=".4"/>' +
'<rect x="15.8" y="20.3" width="12.4" height="2.6" rx=".4" fill="' + color + '" stroke="' + edge + '" stroke-width=".3"/>' +
'<rect x="5" y="31" width="34" height="63.5" rx="3.2" fill="#eceee9" stroke="#a9afaa" stroke-width=".5"/>' +
'<rect x="3.2" y="33" width="1.8" height="58" rx=".8" fill="#2a4252"/><rect x="39" y="33" width="1.8" height="58" rx=".8" fill="#2a4252"/>' +
'<rect x="6" y="32.5" width="4.6" height="60" rx="1.2" fill="' + color + '" stroke="' + edge + '" stroke-width="' + ew + '"/>' +
'<rect x="33.4" y="32.5" width="4.6" height="60" rx="1.2" fill="' + color + '" stroke="' + edge + '" stroke-width="' + ew + '"/>' +
'<g fill="#c4cac6" stroke="#9ea5a0" stroke-width=".5"><rect x="13" y="38" width="18" height="10" rx="2"/><rect x="13" y="62" width="18" height="10" rx="2"/><rect x="18.5" y="81" width="7" height="6" rx="1.2"/></g>' +
'<rect x="11" y="32" width="5" height="2" rx="1" fill="#ffb02e"/><rect x="28" y="32" width="5" height="2" rx="1" fill="#ffb02e"/>' +
'<path d="M39 33.6h2.4M39 52.4h2.4" stroke="#fff" stroke-width=".7"/><rect x="39.2" y="34.3" width="2" height="17.4" fill="#4a6c80"/>' +
'<path d="M13.5 94.8 H30.5 L29.3 97.4 H14.7 Z" fill="#20384a"/>' +
'<rect x="4.5" y="95.8" width="8" height="2.4" rx="1" fill="#d62d20"/><rect x="31.5" y="95.8" width="8" height="2.4" rx="1" fill="#d62d20"/>' +
'<rect x="7" y="98.2" width="30" height="1.7" rx=".8" fill="#3a403f"/>';
        return svg;
    }
    window.SpevbBus2D = { buildBus2D, luminance };
})();
