# Cuentas, historial, viajes guardados y favoritos (v4.4)

Funciona igual en computador, celular y tablet. **Sin cuenta todo sigue funcionando** en ese dispositivo; con cuenta, los mismos datos aparecen en tus otros equipos.

## Qué se guarda
| Dato | Dónde se ve | Límite |
|---|---|---|
| **Historial** de viajes (origen, destino, rutas; nunca el trayecto del GPS) | «Historial y viajes guardados» → tocar repite el viaje; ★ lo guarda; «Borrar historial» | 50 |
| **Viajes guardados** (con nombre, p. ej. «Casa → Universidad») | Botón **Guardar viaje** tras buscar; lista en el mismo panel | 50 |
| **Mis lugares** (Casa, Trabajo…) | Panel «Mis lugares» | 30 |
| **Rutas favoritas** (★) | Menú → Explorar recorridos: ★ en cada ruta; las favoritas van primero y hay filtro «★ Mis rutas favoritas» | 100 |

## Cuenta
- Menú → **Crear cuenta** / **Entrar** (nombre, correo, contraseña ≥ 8 caracteres, no solo números).
- **Ajustes de la cuenta**: cambiar contraseña, cerrar sesión en todos los dispositivos, **eliminar la cuenta** (borra todo; pide la contraseña).
- Al **entrar**, lo que ya tenías en ese dispositivo se **une** con lo de la cuenta (no se pierde nada). Al abrir la app con sesión, lo del servidor se actualiza solo; lo que hayas cambiado sin conexión se sube al volver la señal.
- Al **cerrar sesión** se borran los datos personales de ESE dispositivo (siguen en la cuenta): no queda rastro en equipos compartidos.
- Con dos dispositivos editando a la vez gana el último cambio guardado (por tipo de dato).

## Entrar con Google (opcional)
Botón «Continuar con Google» en «Entrar / Crear cuenta», con un solo toque y sin inventar contraseña. Se activa poniendo `GOOGLE_CLIENT_ID` en el `.env`: ver `docs/GOOGLE.md` (paso a paso). Una cuenta de Google se crea sola la primera vez; si ese correo ya tenía cuenta con contraseña se vincula (y la contraseña anterior se anula por seguridad).

## Seguridad (qué se hizo)
- Contraseña: **PBKDF2-HMAC-SHA256, 600 000 iteraciones**, sal aleatoria por clave, comparación en tiempo constante. Nunca se guarda ni se envía de vuelta.
- Sesión: token aleatorio de 256 bits en cookie **HttpOnly + SameSite=Lax** (y `Secure` en HTTPS); en la base solo se guarda su **hash SHA-256**. Dura 30 días (se renueva), máx. 10 sesiones por persona.
- Inicio de sesión: mismo mensaje y mismo tiempo si el correo no existe («Correo o contraseña incorrectos»).
- Límites: 20 intentos/10 min por IP y 8/10 min por correo; 6 registros/hora por IP.
- CSRF: las operaciones que cambian algo rechazan `Sec-Fetch-Site: cross-site` y un `Origin` distinto del sitio, además del `Content-Type: application/json` obligatorio.
- Los datos subidos se **validan y limpian** en el servidor (solo los campos esperados, rangos y largos, tope por tipo) y se muestran siempre con `textContent`.
- Con HTTPS detrás de Caddy (`compose.prod.yml`) la cookie queda `Secure` automáticamente.

## Lo que NO incluye (a propósito)
- **Recuperar contraseña por correo** y **verificar el correo**: necesitan un servidor de correo (SMTP). Si lo configuras, se añaden en una pequeña ampliación. Mientras tanto, quien olvide su clave puede crear otra cuenta (o el administrador puede borrar la fila de `usuarios`).
- Inicio con Facebook u otros proveedores (Google sí está, ver arriba).

## API (`/api/cuenta`)
`POST /registro` · `POST /entrar` · `POST /salir {todas}` · `GET /yo` (siempre 200) · `POST /clave` · `POST /eliminar` · `GET /datos` · `POST /datos/{lugares|rutas|viajes|historial} {valor:[…]}`.
Tablas: `usuarios`, `sesiones`, `usuario_datos` (JSONB por persona y tipo).

## Privacidad
El historial guarda dónde sales y a dónde vas. Cada persona solo ve lo suyo, puede borrarlo cuando quiera y eliminar la cuenta lo borra del servidor. Si publicas el sistema, avisa de esto a los usuarios (política de privacidad).

## Pruebas
`docs/pruebas/cuentas_e2e.py` (PC y celular con la misma cuenta, de punta a punta) y `CuentaSeguridadTest` (cifrado, validaciones y limpieza de datos).
