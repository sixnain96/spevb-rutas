'use strict';
/* Movimiento de SEVB: lo que no se puede expresar solo con CSS. Los tiempos y curvas viven en css/motion.css.
   · Spevb.Motion.reduced: true si la persona pidió menos movimiento (prefers-reduced-motion) o si <html data-motion="reduce"> lo fuerza
     (sirve para comprobar este modo sin cambiar el sistema operativo). motion.js escribe html[data-motion] = "reduce" | "full".
   · FLIP de las tarjetas de resultados: al abrir o cerrar una, las demás se deslizan a su nuevo sitio en lugar de saltar. Solo usa
     transform (Web Animations API), no bloquea el clic y no hace nada con movimiento reducido. */
(() => {
    const S = window.Spevb || (window.Spevb = {});
    const root = document.documentElement;
    const mq = window.matchMedia ? window.matchMedia('(prefers-reduced-motion: reduce)') : null;
    const reduced = () => !!(mq && mq.matches) || root.dataset.motion === 'reduce';
    const sync = () => { root.dataset.motion = mq && mq.matches ? 'reduce' : 'full'; };
    sync();
    if (mq && mq.addEventListener) mq.addEventListener('change', sync);
    S.Motion = { get reduced() { return reduced(); } };

    const FLIP_MS = 200, FLIP_EASE = 'cubic-bezier(0.2, 0.8, 0.2, 1)';
    document.addEventListener('click', (e) => {
        const summary = e.target.closest && e.target.closest('#tripResults .trip-option > summary');
        if (!summary || reduced() || !root.animate) return;
        const card = summary.parentElement, box = card.closest('#tripResults');
        const others = [...box.querySelectorAll('.trip-option')].filter((c) => c !== card);
        const before = others.map((c) => c.getBoundingClientRect().top);       // First: antes de que el <details> cambie
        requestAnimationFrame(() => {                                          // Last: ya con el nuevo diseño
            const deltas = others.map((c, i) => (c.isConnected ? before[i] - c.getBoundingClientRect().top : 0));
            others.forEach((c, i) => {                                         // Invert + Play, sin leer layout entre medias
                if (Math.abs(deltas[i]) < 2 || Math.abs(deltas[i]) > 1200) return;
                c.animate([{ transform: 'translateY(' + deltas[i] + 'px)' }, { transform: 'none' }], { duration: FLIP_MS, easing: FLIP_EASE });
            });
        });
    }, true);
})();
