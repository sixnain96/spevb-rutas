'use strict';
/* Ajustes de pantalla de este dispositivo: controles del bus (mostrar/ocultar) y modo para daltónicos.
   Se recuerdan en el navegador; en el celular los controles del bus empiezan ocultos para dejar más mapa. */
(() => {
    const S = window.Spevb || (window.Spevb = {});
    const K = 'spevb-ajustes';
    const leer = () => { try { return JSON.parse(localStorage.getItem(K)) || {}; } catch { return {}; } };
    let valores = leer();
    // Primera vez: en pantallas pequeñas el panel del bus empieza oculto.
    if (valores.busMin === undefined) valores.busMin = window.matchMedia('(max-width: 700px), (max-height: 520px)').matches;
    // 4.12.0: tras buscar, el panel se queda compacto con «Iniciar viaje» (antes bajaba solo y había que pedir «Ver rutas»). Bajarlo solo es opcional
    // y está apagado: la clave es NUEVA (`panelBajar`) porque la anterior (`panelAuto`) quedó guardada como «activada» en los celulares que ya usaron la app.
    if (valores.panelBajar === undefined) valores.panelBajar = false;
    const aplicar = () => {
        document.documentElement.classList.toggle('daltonico', !!valores.daltonico);
        document.querySelectorAll('[data-ajuste]').forEach((el) => {
            const clave = el.dataset.ajuste;
            const valor = clave === 'busVisible' ? !valores.busMin : !!valores[clave];
            if (el.checked !== valor) el.checked = valor;
        });
    };
    S.Ajustes = {
        get(clave) { return valores[clave]; },
        set(clave, valor) {
            valores[clave] = valor;
            try { localStorage.setItem(K, JSON.stringify(valores)); } catch { /* sin almacenamiento */ }
            aplicar();
            window.dispatchEvent(new CustomEvent('spevb-ajustes', { detail: { clave, valor } }));
        },
    };
    aplicar();
    document.addEventListener('DOMContentLoaded', () => {
        document.querySelectorAll('[data-ajuste]').forEach((el) => {
            el.addEventListener('change', () => {
                const clave = el.dataset.ajuste;
                S.Ajustes.set(clave === 'busVisible' ? 'busMin' : clave, clave === 'busVisible' ? !el.checked : el.checked);
            });
        });
        aplicar();
    });
})();
