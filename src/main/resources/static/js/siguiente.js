'use strict';
/* «Ahora escribe B» (4.14): al poner un punto (A o B) aparece una pestañita pequeña que dice cuál falta, SIN mover la pantalla y SIN abrir el teclado:
   la persona toca el campo cuando quiere. Mientras se espera, el campo que falta late suavemente para que se encuentre fácil.
   · Con los dos puntos puestos, o con un viaje en curso o en la práctica guiada, no aparece.
   · Tocar la pestañita sube el panel para ver el campo (sin escribir por la persona); se va sola a los pocos segundos. */
(() => {
    const S = window.Spevb;
    if (!S) return;
    const $ = (id) => document.getElementById(id);
    let pestana = null, timer = null, pista = null;

    function quitar() {
        clearTimeout(timer); clearTimeout(pista);
        if (pestana) { const p = pestana; pestana = null; p.classList.remove('visible'); setTimeout(() => p.remove(), 350); }
        document.querySelectorAll('.endpoint-block.pide-punto').forEach((b) => b.classList.remove('pide-punto'));
    }

    function mostrar(falta) {
        quitar();
        const letra = falta === 'destination' ? 'B' : 'A';
        const campo = $(falta === 'destination' ? 'tripDestination' : 'tripOrigin');
        const bloque = campo && campo.closest('.endpoint-block');
        const el = document.createElement('button');
        el.type = 'button'; el.className = 'pestana-siguiente'; el.setAttribute('role', 'status');
        el.setAttribute('aria-label', 'Ahora escribe ' + letra + (letra === 'B' ? ': a dónde vas' : ': de dónde sales') + '. Toca para ver el campo.');
        el.innerHTML = '<span class="pestana-letra ' + (letra === 'B' ? 'b' : 'a') + '" aria-hidden="true">' + letra + '</span>'
            + '<span class="pestana-texto"><b>Ahora escribe ' + letra + '</b><small>' + (letra === 'B' ? 'a dónde vas' : 'de dónde sales') + '</small></span>';
        el.addEventListener('click', () => { quitar(); window.dispatchEvent(new CustomEvent('spevb-ir-inicio')); });      // solo muestra el campo: no abre el teclado
        document.body.append(el);
        pestana = el;
        requestAnimationFrame(() => requestAnimationFrame(() => el.classList.add('visible')));
        if (bloque) { bloque.classList.add('pide-punto'); pista = setTimeout(() => bloque.classList.remove('pide-punto'), 6000); }
        timer = setTimeout(quitar, 5500);
    }

    function revisar() {
        const p = S.Planner && S.Planner.puntos ? S.Planner.puntos() : {};
        if (document.body.classList.contains('practicando') || document.body.classList.contains('viaje-activo')) return quitar();
        if (p.origen && p.destino) return quitar();
        if (!p.origen && !p.destino) return quitar();
        mostrar(p.origen ? 'destination' : 'origin');
    }
    window.addEventListener('spevb-point-selected', () => setTimeout(revisar, 120));
    ['spevb-point-cleared', 'spevb-viaje-modo', 'spevb-results'].forEach((n) => window.addEventListener(n, () => setTimeout(() => { const p = S.Planner && S.Planner.puntos ? S.Planner.puntos() : {}; if ((p.origen && p.destino) || (!p.origen && !p.destino)) quitar(); }, 60)));
    // En cuanto la persona toca el campo que falta, la pestañita ya cumplió su trabajo.
    document.addEventListener('focusin', (e) => { if (e.target && (e.target.id === 'tripDestination' || e.target.id === 'tripOrigin')) quitar(); });
    S.Siguiente = { revisar, quitar, activa: () => !!pestana };
})();
