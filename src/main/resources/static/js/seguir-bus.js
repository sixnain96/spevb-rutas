'use strict';
/* «Seguir bus»: mientras el bus es seguido, un aviso flotante «Siguiendo el bus · Soltar» permite dejar de seguirlo aunque los
   controles del bus estén ocultos. El mapa SIGUE siendo arrastrable: el primer arrastre manual (evento dragstart de la capa de mapa, que
   bus.js escucha y que solo provoca la persona al arrastrar) cancela el seguimiento. Los recentrados automáticos (panTo/setView) no producen dragstart, así que nunca se
   confunden con un movimiento manual. */
(() => {
    let chip = null;
    function crearChip() {
        chip = document.createElement('button');
        chip.type = 'button'; chip.id = 'siguiendoBus'; chip.className = 'siguiendo-bus'; chip.hidden = true;
        chip.setAttribute('aria-label', 'Dejar de seguir el bus y mover el mapa');
        chip.innerHTML = '<span class="sb-punto" aria-hidden="true"></span><span>Siguiendo el bus</span><b>Soltar</b>';
        chip.addEventListener('click', () => {
            const caja = document.querySelector('.bus-follow input');
            if (caja && caja.checked) caja.click();          // pasa por el mismo camino que el interruptor del panel
            else poner(false);
        });
        (document.querySelector('.app') || document.body).appendChild(chip);
    }
    function poner(activo) {
        if (!chip) crearChip();
        document.body.classList.toggle('siguiendo-bus-activo', activo);
        chip.hidden = !activo;
    }
    window.addEventListener('spevb-follow', (e) => poner(!!e.detail));
})();
