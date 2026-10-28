# Inicio de sesión con Google

El botón **«Continuar con Google»** aparece en «Entrar / Crear cuenta» solo cuando el servidor tiene un `GOOGLE_CLIENT_ID`. Sin él, todo sigue funcionando con correo y contraseña. **No hace falta ningún secreto de Google**: solo el *Client ID*, que es público.

## 1. Obtener el Client ID (5–10 minutos, gratis)
> Los nombres de los menús de Google Cloud cambian de vez en cuando; si algo no coincide, busca «OAuth» en el buscador de la consola.

1. Entra a <https://console.cloud.google.com> con tu cuenta de Google y **crea un proyecto** (p. ej. «SPEVB»).
2. Menú **APIs y servicios → Pantalla de consentimiento de OAuth** (o «Google Auth Platform»):
   - Tipo de usuario: **Externo**.
   - Nombre de la app: *SPEVB Villavicencio*; correo de asistencia y de contacto: el tuyo.
   - Alcances: deja solo los básicos (`email`, `profile`, `openid`). No pidas nada más.
   - Estado de publicación: pásala a **«En producción»**. Mientras esté en «Pruebas» solo pueden entrar los correos que añadas como *usuarios de prueba* (máx. 100).
3. **Credenciales → Crear credenciales → ID de cliente de OAuth → Tipo: Aplicación web**.
   - **Orígenes de JavaScript autorizados** (uno por línea, sin `/` al final):
     - `http://localhost:8080`
     - `http://localhost` (si abres sin puerto)
     - tu dominio real: `https://rutas.midominio.com`
     - cada dirección de túnel que uses para probar: `https://xxxx.ngrok-free.app`
   - **URI de redireccionamiento: déjalo vacío** (se usa el modo emergente con token, no hace falta).
4. Copia el **ID de cliente** (`123456789-abcdef….apps.googleusercontent.com`).

## 2. Ponerlo en el sistema
En el archivo `.env` (el mismo donde está `DB_PASSWORD`):
```
GOOGLE_CLIENT_ID=123456789-abcdef.apps.googleusercontent.com
```
Reinicia la aplicación (`.\start.ps1 -NoDatabase`, o `docker compose -f compose.prod.yml up -d` en el servidor). Abre el menú → **Entrar**: debe verse el botón de Google.

## 3. Problemas típicos
| Síntoma | Causa |
|---|---|
| No aparece el botón | `GOOGLE_CLIENT_ID` vacío o la app no se reinició; sin internet; bloqueador de anuncios/privacidad que bloquea `accounts.google.com` |
| «Error 400: origin_mismatch» | La dirección desde la que abres la app no está en *Orígenes de JavaScript autorizados* (con ngrok gratis **cambia cada vez**: añádela o usa tu dominio estático de ngrok). Los cambios pueden tardar unos minutos en aplicarse |
| «Acceso bloqueado: esta app no ha sido verificada» / solo entran algunos | La pantalla de consentimiento sigue en «Pruebas»: publícala o añade usuarios de prueba |
| Falla dentro de Instagram/Facebook/WhatsApp | Esos navegadores integrados bloquean a Google: abrir el enlace en Chrome o Safari |
| «La credencial no es para esta aplicación» | El `GOOGLE_CLIENT_ID` del servidor no es el del botón/proyecto |

## 4. Cómo funciona y qué se comprueba (seguridad)
1. El botón oficial de Google (script `accounts.google.com/gsi/client`, cargado solo al abrir «Entrar») devuelve un **ID token** firmado por Google.
2. El servidor lo verifica **él mismo**, sin enviar el token a nadie: firma RS256 con las claves públicas de Google (descargadas y guardadas en caché), emisor `accounts.google.com`, destinatario = tu Client ID, vigencia y **correo verificado**.
3. Se abre una sesión propia (cookie HttpOnly, igual que con contraseña).
- **Cuenta nueva**: se crea con el nombre y correo de Google, sin contraseña (se puede crear una luego en «Ajustes de la cuenta»).
- **Mismo correo ya registrado con contraseña**: se vincula. Como el correo de las cuentas con contraseña no se verifica, quien lo demuestra con Google es el dueño real: **se anula la contraseña anterior, se cierran sus sesiones y se descartan nombre y datos** que hubiera puesto quien la creó (evita que alguien se «adelante» registrando el correo de otra persona).
- **Eliminar una cuenta de Google**: se confirma escribiendo el correo de la cuenta.
- La política de seguridad de contenido (CSP) solo permite `accounts.google.com/gsi/*` para el botón.

## 5. Administradores
Si pones `ADMIN_CORREOS=tucorreo@gmail.com` en el `.env`, quien entre con Google con ese correo es administrador: ve el editor, los pasos, los lugares y los reportes sin escribir la clave. Ver `docs/ADMIN-Y-PARADEROS.md`.

## 6. Pruebas
`docs/pruebas/google_e2e.py` prueba todo el flujo **sin usar Google**: firma sus propios tokens, sirve sus claves en `http://localhost:18999/certs` y simula el botón. Arranca el servidor con
`GOOGLE_CLIENT_ID=test-client.apps.googleusercontent.com GOOGLE_JWKS_URL=http://localhost:18999/certs`.
Además `GoogleTokenVerifierTest` (firma ajena, token alterado, `alg:none`, emisor, audiencia, vencido, correo sin verificar). Lo único que no se puede probar sin una cuenta real de Google es el botón oficial: pruébalo tú una vez con tu Client ID.
