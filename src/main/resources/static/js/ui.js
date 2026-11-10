'use strict';
/* Menú lateral. Desde 4.14 su estado lo gobierna el coordinador de superficies (superficies.js): abrir, cerrar, foco, inert y bloqueo de scroll
   salen de UN solo estado. Este archivo queda como respaldo: si el coordinador no cargó, el menú sigue funcionando como antes. */
(() => {
    if (window.Spevb && window.Spevb.Superficies) return;
    const menu = document.getElementById('sideMenu');
    const button = document.getElementById('menuButton');
    const close = document.getElementById('closeMenu');
    const backdrop = document.getElementById('menuBackdrop');
    if (!menu || !button || !close || !backdrop) return;
    const setOpen = (open) => {
        menu.classList.toggle('open', open);
        backdrop.classList.toggle('hidden', !open);
        button.setAttribute('aria-expanded', String(open));
        menu.setAttribute('aria-hidden', String(!open));
        document.body.classList.toggle('menu-open', open);
    };
    button.addEventListener('click', () => setOpen(!menu.classList.contains('open')));
    close.addEventListener('click', () => setOpen(false));
    backdrop.addEventListener('click', () => setOpen(false));
    document.addEventListener('keydown', (e) => { if (e.key === 'Escape') setOpen(false); });
})();
