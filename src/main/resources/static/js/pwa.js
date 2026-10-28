"use strict";
// Registra el service worker (sólo con HTTPS o localhost). Si falla, la aplicación sigue funcionando igual.
if ("serviceWorker" in navigator && (location.protocol === "https:" || location.hostname === "localhost" || location.hostname === "127.0.0.1")) {
    window.addEventListener("load", () => {
        const idle = window.requestIdleCallback || ((fn) => setTimeout(fn, 1500));
        idle(() => navigator.serviceWorker.register("/sw.js").catch(() => { /* sin soporte */ }));
    });
}

// Botón «Instalar la aplicación» (menú). Android/Chrome/Edge usan el aviso del navegador; en iPhone se explica cómo hacerlo en Safari.
(() => {
    const boton = document.getElementById('installApp');
    const ayuda = document.getElementById('installHint');
    if (!boton) return;
    const instalada = window.matchMedia('(display-mode: standalone)').matches || navigator.standalone === true;
    if (instalada) return;
    let evento = null;
    window.addEventListener('beforeinstallprompt', (e) => { e.preventDefault(); evento = e; boton.hidden = false; });
    window.addEventListener('appinstalled', () => { boton.hidden = true; if (ayuda) ayuda.hidden = true; });
    boton.addEventListener('click', async () => {
        if (!evento) return;
        evento.prompt();
        try { await evento.userChoice; } catch { /* nada */ }
        evento = null;
        boton.hidden = true;
    });
    const ios = /iphone|ipad|ipod/i.test(navigator.userAgent) || (navigator.platform === 'MacIntel' && navigator.maxTouchPoints > 1);
    if (ios && ayuda) ayuda.hidden = false;
})();
