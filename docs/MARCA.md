# Identidad visual (mascota y logos)

Originales (no se modifican): `branding-originales/`. Los recursos finales se generan con `python docs/marca/preparar_imagenes.py`
(Pillow + numpy): separan la tinta del fondo de cuadritos (que viene dentro de los píxeles, sin transparencia real), recortan márgenes,
no agrandan nada y optimizan el peso.

| Recurso | Original | Resultado | Dónde se usa |
|---|---|---|---|
| Icono de la app | `icono-app-cabeza-blanca-cuadro-azul.png` | `img/marca/sevb-icono-app*.png`, `icons/icon-192/512`, `icon-maskable-512`, `apple-touch-icon`, `favicon-32/48`, `favicon.ico` | Cabecera de todas las páginas (cuadro de la marca), favicon, manifiesto (iconos de instalación), iOS |
| Símbolo compacto (cabeza azul) | `simbolo-cabeza-azul.webp` | `img/marca/sevb-cabeza.png`, `sevb-cabeza-96.png` | Formulario de entrar/crear cuenta, pie de página de las páginas de administración |
| Mascota sin texto | `mascota-sin-texto.webp` | `img/marca/sevb-mascota.png` | Cierre del menú lateral, junto al nombre «SEVB» escrito en HTML |
| Logo horizontal blanco | `logo-horizontal-blanco-SEVB.webp` | `img/marca/sevb-horizontal-blanco.png` | Preparado para superficies oscuras (hoy la aplicación no tiene ninguna) |
| Logo vertical | `logo-vertical-SEBV-nombre-incorrecto.png` | — | **No se usa**: dice «SEBV» (el nombre es SEVB). Se compone con la mascota + texto HTML |

Color: todos los recursos usan el verde de la marca del proyecto (`#176650`, el mismo `--brand` de la interfaz); el icono de la app cambia el azul del original por ese verde conservando la forma y los bordes. El logo horizontal sigue siendo blanco (para fondos oscuros).

El texto «SPEVB» de las cabeceras no se tocó (nombre actual del proyecto en la interfaz).
