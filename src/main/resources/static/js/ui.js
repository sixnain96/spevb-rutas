'use strict';
(() => {
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
