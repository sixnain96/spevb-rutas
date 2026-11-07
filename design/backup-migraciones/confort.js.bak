'use strict';
/* Comodidad de uso (4.14): mapa de noche automático y tamaño de la letra. Los dos se guardan en este dispositivo (Spevb.Ajustes) y se pueden cambiar
   desde el menú ☰ → «Pantalla». Nada de esto toca la lógica de rutas: solo añade una clase y un atributo al <html> que usa el CSS. */
(() => {
    const A = () => window.Spevb && window.Spevb.Ajustes;
    const MODOS = ['auto', 'claro', 'oscuro'], LETRAS = ['normal', 'grande', 'enorme'];
    /** De noche: de 6:30 p. m. a 5:30 a. m. (hora del dispositivo; en Villavicencio anochece hacia las 6 p. m.). */
    const esNoche = (fecha = new Date()) => { const m = fecha.getHours() * 60 + fecha.getMinutes(); return m >= 18 * 60 + 30 || m < 5 * 60 + 30; };
    const modo = () => { const v = A() && A().get('mapaNoche'); return MODOS.includes(v) ? v : 'auto'; };
    const letra = () => { const v = A() && A().get('letra'); return LETRAS.includes(v) ? v : 'normal'; };

    function aplicar() {
        const h = document.documentElement, m = modo();
        h.classList.toggle('mapa-noche', m === 'oscuro' || (m === 'auto' && esNoche()));
        h.dataset.letra = letra();
        document.querySelectorAll('select[data-confort="mapaNoche"]').forEach((s) => { if (s.value !== m) s.value = m; });
        document.querySelectorAll('button[data-letra]').forEach((b) => b.setAttribute('aria-pressed', String(b.dataset.letra === letra())));
    }
    window.Spevb = window.Spevb || {};
    window.Spevb.Confort = { esNoche, aplicar };

    document.addEventListener('DOMContentLoaded', () => {
        document.querySelectorAll('select[data-confort="mapaNoche"]').forEach((s) => s.addEventListener('change', () => { if (A()) A().set('mapaNoche', s.value); aplicar(); }));
        document.querySelectorAll('button[data-letra]').forEach((b) => b.addEventListener('click', () => { if (A()) A().set('letra', b.dataset.letra); aplicar(); }));
        aplicar();
    });
    aplicar();
    setInterval(aplicar, 60000);        // al llegar la noche (o el día) el mapa cambia solo, sin recargar
})();
