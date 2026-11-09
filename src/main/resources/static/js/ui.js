'use strict';
/* Menu lateral: dialogo modal accesible. Abre con foco en «Cerrar menu», encierra Tab/Shift+Tab, Escape cierra, devuelve el foco al boton que lo abrio
   y, cerrado, lo deja `inert` (ningun descendiente entra al orden de tabulacion). La mecanica comun vive en modales.js. */
(() => {
    const menu = document.getElementById('sideMenu');
    const button = document.getElementById('menuButton');
    const close = document.getElementById('closeMenu');
    const backdrop = document.getElementById('menuBackdrop');
    if (!menu || !button || !close || !backdrop) return;
    const M = window.Spevb && window.Spevb.Modal;
    const abierto = () => menu.classList.contains('open');
    const pintar = (open) => {
        menu.classList.toggle('open', open);
        backdrop.classList.toggle('hidden', !open);
        button.setAttribute('aria-expanded', String(open));
        menu.setAttribute('aria-hidden', String(!open));
        menu.inert = !open;
        document.body.classList.toggle('menu-open', open);
    };
    const abrir = () => {
        if (abierto()) return;
        const abridor = M ? M.recordarAbridor() : button;
        if (!M) { pintar(true); close.focus(); return; }
        M.abrir(menu, {
            tarjeta: menu, foco: close, abridor: abridor || button, exentos: [backdrop],
            mostrar: () => pintar(true), ocultar: () => pintar(false), cerrar: cerrarMenu,
        });
    };
    const cerrarMenu = () => { if (!abierto()) return; if (M) M.cerrar(menu); else pintar(false); };
    pintar(false);
    button.addEventListener('click', () => (abierto() ? cerrarMenu() : abrir()));
    close.addEventListener('click', cerrarMenu);
    backdrop.addEventListener('click', cerrarMenu);
    if (!M) document.addEventListener('keydown', (e) => { if (e.key === 'Escape') cerrarMenu(); });
})();
