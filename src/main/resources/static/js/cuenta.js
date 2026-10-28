'use strict';
/* Cuentas y datos personales.
   · Spevb.Datos: lugares, rutas favoritas, viajes guardados e historial. Siempre funcionan en este dispositivo (sin cuenta);
     con una cuenta se sincronizan con el servidor y aparecen en el otro equipo (celular ↔ computador).
   · Spevb.Cuenta: registro, inicio y cierre de sesión (cookie HttpOnly), cambio de clave y eliminación de la cuenta.
   La clave nunca se guarda en el navegador. Al cerrar sesión se borran los datos personales de ESTE dispositivo
   (siguen en la cuenta), para no dejar rastro en equipos compartidos. */
(() => {
    const S = window.Spevb || (window.Spevb = {});
    const $ = (id) => document.getElementById(id);
    const LOCAL = { lugares: 'spevb-favoritos', rutas: 'spevb-rutas-fav', viajes: 'spevb-viajes-guardados', historial: 'spevb-historial' };
    const MAXIMO = { lugares: 30, rutas: 100, viajes: 50, historial: 50 };
    const K_PENDIENTES = 'spevb-datos-pendientes', K_USUARIO = 'spevb-usuario';
    const leer = (k, v) => { try { return JSON.parse(localStorage.getItem(k)) ?? v; } catch { return v; } };
    const escribir = (k, v) => { try { localStorage.setItem(k, JSON.stringify(v)); return true; } catch { return false; } };
    const evento = (nombre, detalle) => window.dispatchEvent(new CustomEvent(nombre, { detail: detalle }));

    let usuario = null;          // { nombre, correo } o null
    let sinVerificar = false;    // se muestra el último usuario conocido porque no hubo conexión para comprobar la sesión
    const pendientes = new Set(leer(K_PENDIENTES, []));
    const guardarPendientes = () => escribir(K_PENDIENTES, [...pendientes]);

    // ------------------------------------------------------------------ pedidos
    async function pedir(url, { metodo = 'GET', cuerpo, espera = 15000 } = {}) {
        const control = new AbortController();
        const t = setTimeout(() => control.abort(), espera);
        try {
            const r = await fetch(url, {
                method: metodo, signal: control.signal, credentials: 'same-origin',
                headers: { Accept: 'application/json', ...(cuerpo !== undefined ? { 'Content-Type': 'application/json' } : {}) },
                body: cuerpo !== undefined ? JSON.stringify(cuerpo) : undefined,
            });
            let datos = null;
            try { datos = await r.json(); } catch { /* sin cuerpo */ }
            if (!r.ok) { const e = new Error(datos?.message || 'No se pudo completar la operación (HTTP ' + r.status + ').'); e.status = r.status; throw e; }
            return datos;
        } catch (e) {
            if (e.status) throw e;
            const err = new Error('Sin conexión con el servidor.');
            err.offline = true;
            throw err;
        } finally { clearTimeout(t); }
    }

    // ------------------------------------------------------------------ datos (local primero, servidor si hay cuenta)
    const empujes = {};
    const Datos = {
        leer(clave) { const v = leer(LOCAL[clave], []); return Array.isArray(v) ? v : []; },
        /** Guarda en este dispositivo y, con sesión, en la cuenta. */
        escribir(clave, lista, { remoto = true } = {}) {
            lista = lista.slice(0, MAXIMO[clave]);
            escribir(LOCAL[clave], lista);
            evento('spevb-datos', { clave });
            if (remoto && usuario) programar(clave);
            return lista;
        },
    };
    function programar(clave) {
        pendientes.add(clave); guardarPendientes();
        clearTimeout(empujes[clave]);
        empujes[clave] = setTimeout(() => empujar(clave), 700);
    }
    async function empujar(clave) {
        if (!usuario || sinVerificar) return;
        try {
            await pedir('/api/cuenta/datos/' + clave, { metodo: 'POST', cuerpo: { valor: Datos.leer(clave) } });
            pendientes.delete(clave); guardarPendientes();
        } catch (e) {
            if (e.status === 401) { cerrarLocal(); S.toast('Tu sesión expiró. Inicia sesión de nuevo para sincronizar.', { tipo: 'info', ms: 7000, clave: 'cuenta' }); }
            // sin conexión: queda pendiente y se reintenta al volver la señal
        }
    }
    window.addEventListener('online', () => { if (usuario) { verificar().then(() => [...pendientes].forEach(empujar)); } });

    const idDe = (x) => x.id;
    function fusionar(clave, locales, remotos) {
        const mapa = new Map();
        const cerca = (a, b) => Math.abs(a.lat - b.lat) < 0.0002 && Math.abs(a.lng - b.lng) < 0.0002;
        [...remotos, ...locales].forEach((x) => {
            if (!x || mapa.has(idDe(x))) return;
            if (clave === 'lugares' && [...mapa.values()].some((y) => y.nombre.toLowerCase() === x.nombre.toLowerCase() && cerca(y, x))) return;
            mapa.set(idDe(x), x);
        });
        let lista = [...mapa.values()];
        if (clave === 'historial' || clave === 'viajes') lista.sort((a, b) => (b.t || 0) - (a.t || 0));
        return lista.slice(0, MAXIMO[clave]);
    }
    /**
     * Sincroniza con la cuenta. {@code alEntrar}: une lo de este dispositivo con lo de la cuenta (nada se pierde).
     * Al abrir la app: manda lo que haya pendiente de subir y, del resto, lo del servidor manda.
     */
    async function sincronizar({ alEntrar = false } = {}) {
        if (!usuario || sinVerificar) return;
        let remoto;
        try { remoto = await pedir('/api/cuenta/datos'); } catch { return; }
        for (const clave of Object.keys(LOCAL)) {
            const local = Datos.leer(clave), doc = remoto[clave]?.valor;
            if (alEntrar) {
                const unida = fusionar(clave, local, Array.isArray(doc) ? doc : []);
                Datos.escribir(clave, unida, { remoto: false });
                if (JSON.stringify(unida) !== JSON.stringify(doc)) await empujar(clave).catch(() => {});
                else { pendientes.delete(clave); guardarPendientes(); }
            } else if (pendientes.has(clave)) {
                await empujar(clave);
            } else if (Array.isArray(doc)) {
                if (JSON.stringify(doc) !== JSON.stringify(local)) Datos.escribir(clave, doc, { remoto: false });
            } else if (local.length) {
                await empujar(clave);
            }
        }
    }

    // ------------------------------------------------------------------ sesión
    /** Enlaces de administración (editor, pasos, lugares, reportes): solo los ve un administrador. Si no hay administradores configurados, se ven como antes. */
    async function aplicarAdmin() {
        const cfg = await cargarConfig();
        let hay = cfg ? !!cfg.adminConfigurado : leer('spevb-admin-conf', false);
        if (cfg) escribir('spevb-admin-conf', hay);
        const ver = !hay || !!(usuario && usuario.admin);
        document.querySelectorAll('.admin-only').forEach((el) => { el.hidden = !ver; });
    }
    function cambio() {
        evento('spevb-cuenta', { usuario });
        pintarMenu();
        aplicarAdmin();
    }
    function cerrarLocal() {
        usuario = null; sinVerificar = false;
        try { localStorage.removeItem(K_USUARIO); } catch { /* nada */ }
        // Datos personales de este dispositivo: se quitan (siguen en la cuenta).
        Object.keys(LOCAL).forEach((c) => { try { localStorage.removeItem(LOCAL[c]); } catch { /* nada */ } evento('spevb-datos', { clave: c }); });
        ['spevb-recientes', 'spevb-caminatas', K_PENDIENTES].forEach((k) => { try { localStorage.removeItem(k); } catch { /* nada */ } });
        pendientes.clear();
        evento('spevb-recientes');
        cambio();
    }
    async function verificar() {
        try {
            const r = await pedir('/api/cuenta/yo', { espera: 8000 });
            sinVerificar = false;
            if (r.autenticado) { usuario = r.usuario; escribir(K_USUARIO, usuario); }
            else if (usuario || leer(K_USUARIO, null)) cerrarLocal();
        } catch {
            const cache = leer(K_USUARIO, null);
            if (cache && !usuario) { usuario = cache; sinVerificar = true; }
        }
        cambio();
    }
    async function entrada(ruta, cuerpo, mensaje) {
        const r = await pedir('/api/cuenta/' + ruta, { metodo: 'POST', cuerpo });
        usuario = r.usuario; sinVerificar = false;
        escribir(K_USUARIO, usuario);
        cambio();
        await sincronizar({ alEntrar: true });
        S.toast(mensaje(usuario), { tipo: 'success', ms: 4500, clave: 'cuenta' });
        return usuario;
    }
    const Cuenta = {
        get usuario() { return usuario; },
        get sinVerificar() { return sinVerificar; },
        registrar: (nombre, correo, clave) => entrada('registro', { nombre, correo, clave }, (u) => '¡Bienvenido, ' + u.nombre + '! Tu cuenta está lista.'),
        google: (credential) => entrada('google', { credential }, (u) => '¡Hola, ' + u.nombre + '! Entraste con Google.'),
        entrar: (correo, clave) => entrada('entrar', { correo, clave }, (u) => 'Hola de nuevo, ' + u.nombre + '.'),
        async salir(todas = false) {
            try { await pedir('/api/cuenta/salir', { metodo: 'POST', cuerpo: { todas } }); } catch { /* la cookie vence sola */ }
            cerrarLocal();
            S.toast('Sesión cerrada. Tus datos siguen en tu cuenta.', { tipo: 'info', ms: 4000, clave: 'cuenta' });
        },
        async cambiarClave(actual, nueva) { await pedir('/api/cuenta/clave', { metodo: 'POST', cuerpo: { actual, nueva } }); },
        async eliminar(clave) { await pedir('/api/cuenta/eliminar', { metodo: 'POST', cuerpo: { clave } }); cerrarLocal(); },
        sincronizar,
    };
    S.Datos = Datos;
    S.Cuenta = Cuenta;

    // ------------------------------------------------------------------ menú y ventanas
    function pintarMenu() {
        if (!$('accountGuest')) return;
        $('accountGuest').hidden = !!usuario;
        $('accountUser').hidden = !usuario;
        if (usuario) {
            $('accountName').textContent = usuario.nombre;
            $('accountEmail').textContent = usuario.correo + (usuario.conGoogle ? ' · Google' : '') + (sinVerificar ? ' · sin conexión' : '');
        }
    }
    const abrir = (el) => { el.hidden = false; document.body.classList.add('modal-open'); };
    const cerrar = (el) => { el.hidden = true; if (![...document.querySelectorAll('.modal-backdrop')].some((m) => !m.hidden)) document.body.classList.remove('modal-open'); };
    const cerrarMenu = () => { try { $('closeMenu')?.click(); } catch { /* nada */ } };


    // ------------------------------------------------------------------ Google
    let config = null, googleListo = null;
    async function cargarConfig() {
        if (config) return config;
        try { config = await pedir('/api/cuenta/config', { espera: 6000 }); } catch { config = null; }
        return config;
    }
    function cargarScriptGoogle() {
        if (googleListo) return googleListo;
        googleListo = new Promise((ok, fallo) => {
            if (window.google?.accounts?.id) return ok();
            const el = document.createElement('script');
            el.src = 'https://accounts.google.com/gsi/client';
            el.async = true;
            el.onload = ok;
            el.onerror = () => { googleListo = null; fallo(new Error('Sin Google')); };
            document.head.append(el);
        });
        return googleListo;
    }
    let botonGoogle = false;
    /** Muestra el botón «Continuar con Google» si el servidor tiene un Client ID y hay conexión; si no, queda oculto. */
    async function prepararGoogle() {
        const caja = $('googleBox');
        if (!caja) return;
        const cfg = await cargarConfig();
        if (!cfg || !cfg.google || navigator.onLine === false) { caja.hidden = true; return; }
        try {
            await cargarScriptGoogle();
            if (!botonGoogle) {
                window.google.accounts.id.initialize({
                    client_id: cfg.google, ux_mode: 'popup', auto_select: false, cancel_on_tap_outside: true, itp_support: true,
                    callback: async (resp) => {
                        $('authError').textContent = '';
                        try { await Cuenta.google(resp.credential); cerrar($('authModal')); }
                        catch (err) { $('authError').textContent = err.offline ? 'Sin conexión: necesitas internet para entrar con Google.' : err.message; }
                    },
                });
                botonGoogle = true;
            }
            const ancho = Math.max(200, Math.min(340, ($('authForm').clientWidth || 340) - 32));
            $('googleBtn').replaceChildren();
            window.google.accounts.id.renderButton($('googleBtn'), { type: 'standard', theme: 'outline', size: 'large', text: 'continue_with', shape: 'pill', locale: 'es', logo_alignment: 'left', width: ancho });
            caja.hidden = false;
        } catch { caja.hidden = true; }
    }

    let modo = 'entrar';
    function modoAuth(nuevo) {
        modo = nuevo;
        const registro = nuevo === 'registro';
        $('authTitle').textContent = registro ? 'Crear cuenta' : 'Entrar';
        $('authNameRow').hidden = !registro;
        $('authName').required = registro;
        $('authPass').autocomplete = registro ? 'new-password' : 'current-password';
        $('authSubmit').textContent = registro ? 'Crear cuenta' : 'Entrar';
        $('authSwitch').textContent = registro ? '¿Ya tienes cuenta? Entrar' : '¿No tienes cuenta? Crear una';
        $('authHint').hidden = !registro;
        $('authError').textContent = '';
    }
    function abrirAuth(m) { cerrarMenu(); modoAuth(m); $('authPass').value = ''; abrir($('authModal')); prepararGoogle(); setTimeout(() => (m === 'registro' ? $('authName') : $('authEmail')).focus(), 50); }

    function iniciarUI() {
        if (!$('authModal')) return;
        $('accountLogin').onclick = () => abrirAuth('entrar');
        $('accountRegister').onclick = () => abrirAuth('registro');
        document.querySelectorAll('[data-abrir-cuenta]').forEach((b) => { b.onclick = () => abrirAuth(b.dataset.abrirCuenta); });
        $('authSwitch').onclick = () => modoAuth(modo === 'entrar' ? 'registro' : 'entrar');
        $('authCancel').onclick = () => cerrar($('authModal'));
        $('authShow').onclick = () => { const v = $('authPass').type === 'password'; $('authPass').type = v ? 'text' : 'password'; $('authShow').textContent = v ? 'Ocultar' : 'Ver'; };
        $('authForm').onsubmit = async (e) => {
            e.preventDefault();
            const b = $('authSubmit');
            b.disabled = true; $('authError').textContent = '';
            try {
                if (modo === 'registro') await Cuenta.registrar($('authName').value, $('authEmail').value, $('authPass').value);
                else await Cuenta.entrar($('authEmail').value, $('authPass').value);
                $('authPass').value = '';
                cerrar($('authModal'));
            } catch (err) {
                $('authError').textContent = err.offline ? 'Sin conexión: necesitas internet para entrar o crear tu cuenta.' : err.message;
            } finally { b.disabled = false; }
        };
        $('accountLogout').onclick = () => { cerrarMenu(); Cuenta.salir(false); };
        $('accountSettings').onclick = () => {
            cerrarMenu();
            $('setName').textContent = usuario?.nombre || ''; $('setEmail').textContent = usuario?.correo || '';
            $('setMsg').textContent = ''; $('setCur').value = $('setNew').value = $('setDel').value = '';
            const conClave = !!usuario?.tieneClave;
            $('setGoogle').hidden = !usuario?.conGoogle;
            $('setCurRow').hidden = !conClave;
            $('setPassTitle').textContent = conClave ? 'Cambiar contraseña' : 'Crear una contraseña (opcional)';
            $('setPassBtn').textContent = conClave ? 'Cambiar contraseña' : 'Crear contraseña';
            $('setDelLabel').textContent = conClave ? 'Tu contraseña' : 'Escribe el correo de tu cuenta para confirmar';
            $('setDel').type = conClave ? 'password' : 'email';
            $('setDel').autocomplete = conClave ? 'current-password' : 'off';
            abrir($('settingsModal'));
        };
        $('settingsClose').onclick = () => cerrar($('settingsModal'));
        $('setPassForm').onsubmit = async (e) => {
            e.preventDefault();
            $('setMsg').className = 'muted';
            try { await Cuenta.cambiarClave($('setCur').value, $('setNew').value); $('setCur').value = $('setNew').value = ''; $('setMsg').textContent = usuario?.tieneClave ? 'Contraseña cambiada.' : 'Contraseña creada.'; $('setMsg').className = 'ok-msg'; if (usuario && !usuario.tieneClave) { usuario.tieneClave = true; escribir(K_USUARIO, usuario); } }
            catch (err) { $('setMsg').textContent = err.message; $('setMsg').className = 'error-msg'; }
        };
        $('setAll').onclick = async () => { cerrar($('settingsModal')); await Cuenta.salir(true); };
        $('setDelete').onclick = async () => {
            if (!$('setDel').value) { $('setMsg').textContent = usuario?.tieneClave ? 'Escribe tu contraseña para eliminar la cuenta.' : 'Escribe el correo de tu cuenta para confirmar.'; $('setMsg').className = 'error-msg'; return; }
            if (!confirm('Se borrará tu cuenta y todos sus datos (lugares, viajes guardados, historial). Esto no se puede deshacer. ¿Continuar?')) return;
            try { await Cuenta.eliminar($('setDel').value); cerrar($('settingsModal')); S.toast('Cuenta eliminada.', { tipo: 'info', ms: 5000, clave: 'cuenta' }); }
            catch (err) { $('setMsg').textContent = err.message; $('setMsg').className = 'error-msg'; }
        };
        document.querySelectorAll('.modal-backdrop').forEach((m) => m.addEventListener('click', (e) => { if (e.target === m) cerrar(m); }));
        document.addEventListener('keydown', (e) => { if (e.key === 'Escape') document.querySelectorAll('.modal-backdrop').forEach((m) => { if (!m.hidden && m.id !== 'reportModal') cerrar(m); }); });
        pintarMenu();
    }
    iniciarUI();

    // Al abrir la app: se comprueba la sesión y, si hay, se sincroniza. Los datos locales ya están disponibles desde el principio.
    S.Cuenta.listo = verificar().then(() => sincronizar());
})();
