'use strict';
/* Nombre popular de las rutas (4.14). En Villavicencio la gente no dice «R08» sino «la Catama», «la Kirpas» o «la 876»: el nombre que va en grande
   en el cartel del bus. El editor lo guarda (opcional) y aquí se pide una vez al servidor; si no hay red se usa la última copia guardada.
   Sin nombre popular se sigue mostrando el código, así que nada cambia para las rutas que aún no lo tienen. */
(() => {
    const CLAVE = 'spevb-ruta-nombres';
    let mapa = {};
    const limpiar = (m) => {
        const r = {};
        if (m && typeof m === 'object' && !Array.isArray(m)) {
            for (const [k, v] of Object.entries(m)) if (typeof v === 'string' && v.trim()) r[String(k).toUpperCase()] = v.trim().slice(0, 60);
        }
        return r;
    };
    try { mapa = limpiar(JSON.parse(localStorage.getItem(CLAVE) || '{}')); } catch { mapa = {}; }
    const guardar = () => { try { localStorage.setItem(CLAVE, JSON.stringify(mapa)); } catch { /* sin almacenamiento: se usa solo en esta sesión */ } };
    const popular = (codigo) => mapa[String(codigo || '').toUpperCase()] || '';
    /** Lo que se le dice a la persona: el nombre popular, o el código si la ruta aún no lo tiene. */
    const nombre = (codigo) => popular(codigo) || String(codigo || '');
    async function cargar() {
        try {
            mapa = limpiar(await Spevb.api('/api/rutas/nombres'));
            guardar();
            window.dispatchEvent(new CustomEvent('spevb-nombres-rutas'));
        } catch { /* sin red o sin servicio: queda la copia guardada */ }
        return mapa;
    }
    /** Para el editor: tras guardar, la pantalla ya muestra el nombre nuevo sin esperar otra consulta. */
    function fijar(codigo, valor) {
        const c = String(codigo || '').toUpperCase(), v = String(valor || '').trim().slice(0, 60);
        if (v) mapa[c] = v; else delete mapa[c];
        guardar();
        window.dispatchEvent(new CustomEvent('spevb-nombres-rutas'));
    }
    Object.assign(window.Spevb, { rutaNombre: nombre, rutaPopular: popular, cargarNombresRutas: cargar, fijarNombreRuta: fijar });
    cargar();
})();
