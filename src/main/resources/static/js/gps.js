'use strict';
/* Ubicación del usuario (GPS) para computador y celular.
   El navegador es quien calcula la posición (GPS del teléfono, Wi-Fi, antenas o IP): una página web no puede triangular
   por su cuenta. Lo que sí hace este módulo es obtener la mejor lectura posible: pide alta precisión, espera a que la
   lectura converja, descarta lecturas viejas o saltos imposibles, suaviza el movimiento (filtro de Kalman) y dice con
   claridad qué precisión tiene el resultado y qué hacer si el permiso está bloqueado. */
(() => {
    const S = window.Spevb || (window.Spevb = {});

    const LECTURA_MAXIMA_EDAD_MS = 30000;   // una lectura más vieja es una posición en caché, no la actual
    const VELOCIDAD_IMPOSIBLE = 70;         // m/s (≈ 250 km/h): un salto así es ruido, no un desplazamiento

    // ------------------------------------------------------------------ utilidades puras
    function distancia(a, b) {
        const r = 6371000, rad = Math.PI / 180;
        const dLat = (b.lat - a.lat) * rad, dLng = (b.lng - a.lng) * rad;
        const h = Math.sin(dLat / 2) ** 2 + Math.cos(a.lat * rad) * Math.cos(b.lat * rad) * Math.sin(dLng / 2) ** 2;
        return 2 * r * Math.asin(Math.sqrt(h));
    }

    /** Calidad según el radio de error (m) que informa el dispositivo. */
    function calidad(precision) {
        if (precision <= 25) return { clave: 'EXCELENTE', texto: 'excelente' };
        if (precision <= 60) return { clave: 'BUENA', texto: 'buena' };
        if (precision <= 200) return { clave: 'REGULAR', texto: 'regular' };
        return { clave: 'BAJA', texto: 'baja' };
    }

    function dentroDeCiudad(lat, lng) {
        const [[minLat, minLng], [maxLat, maxLng]] = S.cityBounds || [[3.95, -73.85], [4.35, -73.45]];
        return Number.isFinite(lat) && Number.isFinite(lng) && lat >= minLat && lat <= maxLat && lng >= minLng && lng <= maxLng;
    }

    function plataforma() {
        const ua = (navigator.userAgent || '') + ' ' + (navigator.platform || '');
        if (/iPhone|iPad|iPod/i.test(ua) || (/Mac/i.test(ua) && navigator.maxTouchPoints > 1)) return 'ios';
        if (/Android/i.test(ua)) return 'android';
        return 'escritorio';
    }

    const AYUDA_PERMISO = {
        ios: 'En el iPhone: Ajustes › Privacidad y seguridad › Localización › activa «Localización» y, en tu navegador (Safari/Chrome), elige «Al usar la app». '
            + 'En Safari también puedes tocar «aA» junto a la dirección › Ajustes del sitio web › Ubicación › Permitir. Luego recarga la página.',
        android: 'En Android: toca el candado (o ⋮ › Configuración del sitio) junto a la dirección › Permisos › Ubicación › Permitir. '
            + 'Comprueba que la ubicación del teléfono esté activada (modo de alta precisión). Luego recarga la página.',
        escritorio: 'En el computador: haz clic en el candado junto a la dirección › Ubicación › Permitir, y recarga la página. '
            + 'En Windows revisa también Configuración › Privacidad y seguridad › Ubicación (activada para tu navegador).',
    };

    function crearError(codigo, mensaje, ayuda) {
        const e = new Error(mensaje);
        e.codigo = codigo;
        e.ayuda = ayuda || '';
        return e;
    }

    /** Problemas que impiden pedir la ubicación (sin llegar a pedirla). */
    function problema() {
        if (!('geolocation' in navigator)) {
            return crearError('NO_SOPORTADO', 'Este navegador no ofrece geolocalización. Usa «Elegir en el mapa» o escribe una dirección.');
        }
        if (window.isSecureContext === false) {
            return crearError('INSEGURO', 'El navegador bloquea el GPS en páginas sin HTTPS.',
                'Abre SPEVB con una dirección https:// o desde http://localhost. Una dirección como http://192.168.x.x (la de tu red local) NO puede usar el GPS en el celular.');
        }
        return null;
    }

    /** Convierte el error nativo en uno con mensaje y ayuda en español. */
    function errorGps(err) {
        const code = err && err.code;
        if (code === 1) return crearError('DENEGADO', 'El permiso de ubicación está bloqueado para esta página.', AYUDA_PERMISO[plataforma()]);
        if (code === 2) return crearError('NO_DISPONIBLE', 'El dispositivo no pudo calcular tu ubicación.',
            'Activa la ubicación del equipo (en el celular, con alta precisión), acércate a una ventana o sal a cielo abierto, y vuelve a intentar.');
        if (code === 3) return crearError('TIEMPO', 'La ubicación tardó demasiado en responder.',
            'Revisa que la ubicación del equipo esté activada y vuelve a intentar. Dentro de edificios el GPS puede tardar más.');
        return crearError('DESCONOCIDO', 'No fue posible obtener tu ubicación.');
    }

    function normalizar(pos) {
        const c = pos && pos.coords;
        if (!c || !Number.isFinite(c.latitude) || !Number.isFinite(c.longitude)) return null;
        const precision = Number.isFinite(c.accuracy) ? Math.max(1, c.accuracy) : 9999;
        return {
            lat: c.latitude, lng: c.longitude, precision,
            rumbo: Number.isFinite(c.heading) ? c.heading : null,
            velocidad: Number.isFinite(c.speed) ? c.speed : null,
            marcaTiempo: Number.isFinite(pos.timestamp) ? pos.timestamp : Date.now(),
            calidad: calidad(precision).clave,
        };
    }

    // ------------------------------------------------------------------ filtro de Kalman (posición)
    /** Mezcla cada lectura con la estimación anterior según su confianza: lecturas precisas pesan más que las borrosas. */
    class Filtro {
        constructor(ruidoProceso = 3) { this.q = ruidoProceso; this.reiniciar(); }
        reiniciar() { this.lat = null; this.lng = null; this.varianza = -1; this.t = 0; }
        procesar(lat, lng, precision, t) {
            const acc = Math.max(precision, 1);
            if (this.varianza < 0) {
                this.lat = lat; this.lng = lng; this.varianza = acc * acc; this.t = t;
            } else {
                const dt = Math.max(0, (t - this.t) / 1000);
                this.varianza += dt * this.q * this.q;           // con el tiempo crece la incertidumbre de la estimación
                const k = this.varianza / (this.varianza + acc * acc);
                this.lat += k * (lat - this.lat);
                this.lng += k * (lng - this.lng);
                this.varianza = (1 - k) * this.varianza;
                this.t = t;
            }
            return { lat: this.lat, lng: this.lng, precision: Math.sqrt(this.varianza) };
        }
    }

    // ------------------------------------------------------------------ permiso
    async function permiso() {
        try {
            if (!navigator.permissions || !navigator.permissions.query) return 'desconocido';
            return (await navigator.permissions.query({ name: 'geolocation' })).state;   // granted | prompt | denied
        } catch {
            return 'desconocido';
        }
    }

    // ------------------------------------------------------------------ lectura única de buena calidad
    /**
     * Obtiene la mejor posición posible. Escucha varias lecturas y termina cuando la precisión llega al objetivo, cuando
     * deja de mejorar o al agotar el tiempo (con la mejor lectura que haya). Escucha a la vez el GPS
     * y la ubicación por red (Wi-Fi/antenas), que llega en segundos aunque sea aproximada.
     * {@code onProgreso(lectura)} se llama con la mejor lectura hasta el momento.
     */
    function obtener({ objetivo = 40, esperaMax = 20000, onProgreso, signal } = {}) {
        return new Promise((resolve, reject) => {
            const prob = problema();
            if (prob) { reject(prob); return; }
            let mejor = null, terminado = false, ultimoError = null, ultimaMejora = Date.now();
            const ids = [], inicio = Date.now();
            let tick = null, tope = null;

            const terminar = (fn, valor) => {
                if (terminado) return;
                terminado = true;
                ids.forEach((id) => navigator.geolocation.clearWatch(id));
                clearInterval(tick); clearTimeout(tope);
                fn(valor);
            };
            const alLeer = (pos) => {
                if (terminado) return;    // una lectura rezagada de la otra escucha no debe pisar el resultado final
                const f = normalizar(pos);
                if (!f || Date.now() - f.marcaTiempo > LECTURA_MAXIMA_EDAD_MS) return;
                if (!mejor || f.precision < mejor.precision - 1) { mejor = f; ultimaMejora = Date.now(); }
                if (onProgreso) onProgreso(mejor);
                if (mejor.precision <= objetivo) terminar(resolve, mejor);
            };
            const alFallar = (err) => {
                if (terminado) return;
                ultimoError = err;
                if (err && err.code === 1) terminar(reject, errorGps(err));    // permiso denegado: no tiene sentido seguir esperando
            };
            const escuchar = (altaPrecision) => ids.push(navigator.geolocation.watchPosition(alLeer, alFallar,
                { enableHighAccuracy: altaPrecision, maximumAge: 0, timeout: esperaMax }));

            // Se escuchan a la vez el GPS (alta precisión, tarda pero afina) y la ubicación por red (Wi-Fi/antenas, llega en
            // segundos aunque sea aproximada): se muestra lo primero que llegue y se queda la lectura más precisa.
            escuchar(true);
            escuchar(false);
            tick = setInterval(() => {
                const t = Date.now() - inicio;
                // Si la lectura ya no mejora se termina: con buena precisión a los 4 s sin mejora; con precisión baja se espera más
                // (un GPS en frío puede empezar con una lectura por antenas y afinar después), pero sin agotar todo el tiempo.
                const paciencia = mejor && mejor.precision <= 150 ? 4000 : 9000;
                if (mejor && t >= 8000 && Date.now() - ultimaMejora >= paciencia) terminar(resolve, mejor);
            }, 500);
            tope = setTimeout(() => (mejor ? terminar(resolve, mejor) : terminar(reject, errorGps(ultimoError || { code: 3 }))), esperaMax);
            if (signal) signal.addEventListener('abort', () => terminar(reject, crearError('CANCELADO', 'Cancelado.')));
        });
    }

    // ------------------------------------------------------------------ seguimiento continuo
    /**
     * Sigue la ubicación mientras se mueve. Descarta lecturas borrosas cuando ya había una buena y saltos imposibles,
     * y suaviza el recorrido con el filtro. Devuelve {@code { detener }}.
     */
    function seguir({ onLectura, onAviso, onError, precisionMaxima = 200 } = {}) {
        const prob = problema();
        if (prob) { if (onError) onError(prob); return { detener() {} }; }
        const filtro = new Filtro();
        let ultimo = null, id = null, detenido = false;
        const alLeer = (pos) => {
            const f = normalizar(pos);
            if (!f) return;
            if (ultimo && f.precision > precisionMaxima && ultimo.precision <= 60) return;                 // lectura borrosa
            if (ultimo) {
                const dt = (f.marcaTiempo - ultimo.marcaTiempo) / 1000;
                if (dt > 0 && distancia(ultimo, f) / dt > VELOCIDAD_IMPOSIBLE && f.precision >= ultimo.precision) return;   // salto imposible
            }
            const s = filtro.procesar(f.lat, f.lng, f.precision, f.marcaTiempo);
            ultimo = { ...f, lat: s.lat, lng: s.lng, precision: Math.min(f.precision, s.precision), calidad: calidad(Math.min(f.precision, s.precision)).clave };
            if (onLectura) onLectura(ultimo);
        };
        // Solo el permiso denegado detiene el seguimiento. Perder la señal un momento (túnel, edificio) o un tiempo de espera
        // son pasajeros: se avisa y se sigue escuchando (tras un tiempo de espera se reinicia la escucha).
        const alFallar = (err) => {
            if (detenido) return;
            if (err && err.code === 1) { detener(); if (onError) onError(errorGps(err)); return; }
            if (onAviso) onAviso(errorGps(err));
            if (err && err.code === 3) { navigator.geolocation.clearWatch(id); iniciar(); }
        };
        const iniciar = () => { id = navigator.geolocation.watchPosition(alLeer, alFallar, { enableHighAccuracy: true, maximumAge: 0, timeout: 30000 }); };
        const detener = () => { detenido = true; navigator.geolocation.clearWatch(id); };
        iniciar();
        return { detener };
    }

    S.Gps = { obtener, seguir, permiso, calidad, dentroDeCiudad, distancia, errorGps, problema, plataforma, Filtro, AYUDA_PERMISO };
})();
