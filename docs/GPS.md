# GPS (computador y celular)

## Qué hace
- **«Usar mi ubicación»** (botón de la mira junto al origen, en la pantalla principal y en `lugares.html`): pide permiso, espera a que la lectura mejore (hasta 20 s; termina antes si llega a ±40 m o si deja de mejorar), muestra el avance («Mejorando la precisión… ±120 m») y deja la ubicación como origen.
- **Botón flotante de la mira** (mapa): sigue tu ubicación mientras te mueves; un segundo toque lo detiene. Punto azul con círculo de precisión y flecha de rumbo cuando te desplazas.
- Calidad según el error que informa el dispositivo: excelente ≤ 25 m · buena ≤ 60 m · regular ≤ 200 m · baja > 200 m.
- Más de ±150 m: se usa pero avisa que es aproximada. Más de ±1 km: **no** se usa como origen (típico de un computador sin GPS ubicado por Wi-Fi o IP).
- Fuera de Villavicencio: lo dice y no lo usa.
- Permiso bloqueado, sin señal, tiempo agotado, sin HTTPS: mensaje claro con los pasos para arreglarlo, según iPhone, Android o computador.

## Elegir el origen o el destino en el celular
Con el pin junto a A o B: aparece un pin fijo en el centro del mapa. **Mueve el mapa** hasta dejar la punta del pin en el lugar exacto y pulsa **Confirmar** (o «Cancelar»). En computador basta hacer clic en el mapa.

## Cómo se obtiene la posición (triangulación)
Una página web **no puede triangular por su cuenta**: el navegador pide la posición al sistema operativo, que combina GPS/satélites, Wi-Fi, antenas móviles e IP, y entrega un punto con su margen de error. SPEVB saca el máximo de eso:
1. Pide alta precisión y escucha varias lecturas, no solo la primera.
2. Descarta lecturas viejas (> 30 s, posición en caché) y se queda con la de menor error.
3. En seguimiento: ignora lecturas borrosas cuando ya había una buena, descarta saltos imposibles (> 250 km/h) y suaviza el recorrido con un filtro de Kalman que pesa cada lectura según su precisión.
4. Perder la señal un momento no detiene el seguimiento; solo lo detiene un permiso denegado.

## Lo que NO depende de la página
- **Computador:** casi ninguno tiene GPS; la precisión típica es de 50 m a 2 km (Wi-Fi/IP). Para precisión real, usa un celular. En el computador, si la ubicación no coincide, usa «Elegir en el mapa».
- **Celular:** activa la ubicación del teléfono en alta precisión y, en el primer uso, elige «Permitir» al navegador. A cielo abierto converge en segundos; bajo techo puede tardar o no ser posible.

## IMPORTANTE: HTTPS en el celular
Los navegadores **bloquean el GPS en páginas sin HTTPS**, salvo `http://localhost`. Por eso:
- En el computador donde corre la app, `http://localhost:8080` funciona.
- Desde un celular, `http://192.168.x.x:8080` (tu red local) **no funciona para el GPS** (además la app escucha solo en 127.0.0.1 por seguridad).
- Para probar en el celular, publica la app con HTTPS:
  - Rápido, para pruebas: `cloudflared tunnel --url http://localhost:8080` (o `ngrok http 8080`) entrega una dirección `https://…` que abre en el celular con el GPS habilitado. Los datos pasan por ese servicio: no lo uses con información sensible ni lo dejes abierto.
  - Definitivo: poner la app detrás de un servidor con certificado (Nginx/Caddy + Let's Encrypt, o un servicio de nube).

## Prueba automática
`docs/pruebas/movil_e2e.py` (celular emulado, pin central, avisos) y `docs/pruebas/gps_e2e.py` simulan ubicaciones y permisos en Chromium (17 comprobaciones: buena/mala precisión, fuera de la ciudad, permiso denegado, mejora progresiva, seguimiento, sin HTTPS, `lugares.html`).
Uso: `pip install playwright && playwright install chromium`, app corriendo, `SEVB_URL=http://localhost:8080/ python docs/pruebas/gps_e2e.py`.
