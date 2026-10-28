'use strict';
/* Páginas de administración (editor, pasos, lugares, reportes). Un administrador con sesión iniciada (correo verificado con Google y
   listado en ADMIN_CORREOS) no necesita escribir la clave: el servidor lo reconoce por su sesión. Los demás ven un aviso. */
(() => {
    const campos = ['editorToken', 'pasoToken', 'placeToken', 'repToken', 'paToken'].map((id) => document.getElementById(id)).filter(Boolean);
    const aviso = (texto, clase) => {
        const p = document.createElement('div');
        p.className = 'admin-banner ' + clase;
        p.textContent = texto;
        const main = document.querySelector('main');
        if (main) main.before(p); else document.body.prepend(p);       // fuera de la cuadrícula del editor: no mueve nada
    };
    fetch('/api/cuenta/yo', { credentials: 'same-origin' }).then((r) => r.json()).catch(() => null).then((r) => {
        if (r && r.autenticado && r.usuario.admin) {
            campos.forEach((i) => {
                i.value = 'sesion-de-administrador-activa-'.padEnd(40, 'x');     // el servidor ignora este valor: acepta la sesión
                i.hidden = true;
                const l = document.querySelector('label[for="' + i.id + '"]');
                if (l) l.hidden = true;
                const hint = i.nextElementSibling;
                if (hint && hint.classList.contains('hint')) hint.hidden = true;
            });
            aviso('✔ Sesión de administrador: ' + r.usuario.nombre + '. No necesitas escribir la clave.', 'ok');
        } else {
            aviso('Esta sección es para administradores. Entra con la cuenta de Google de un administrador, o escribe la clave de administración.', 'warn');
        }
    });
})();
