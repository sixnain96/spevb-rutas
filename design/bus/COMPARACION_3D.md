# Puerta C — SVG 2D y comparación de opciones para el 3D web

Todas las cifras son medidas reales, salvo las marcadas «estimado». Nada de esto está integrado en la aplicación.

## 1. SVG 2D (prototipo: `exports/bus2d.js`, prueba visual: `previews/svg_preview.html`)

| | Actual (`build2DBus`) | Prototipo |
|---|---|---|
| Nodos SVG | 54 | **40** |
| Peso del SVG generado | 4.436 B | **3.098 B** |
| Peso de la función en JS (sin comprimir / gzip) | — | 5.165 B / 1.883 B |
| Gradientes e IDs | 3 gradientes, 3 `id` fijos (chocan con varios buses) | **0 gradientes, 0 `id`** |
| Filtros dentro del SVG | no | no (se mantiene el `drop-shadow` de `.bus2d` en el CSS) |
| viewBox / tamaño | `-5 0 54 100`, 54×100 | igual (el anclaje no cambia) |

- Frente hacia arriba, `aria-hidden`, `focusable="false"`, sin scripts ni referencias externas.
- La carrocería es ≈ 33 % más ancha en proporción que la real (38×98 frente a 2,30×7,87 m) para que la silueta se lea mejor como marcador. El SVG actual es aún más ancho (42×98, ≈ 47 % más). Si prefieres la proporción exacta, se afina en la siguiente ronda.
- El color de ruta solo se aplica a las franjas del techo y a la banda del letrero. Los laterales son los cristales oscuros.
- Contraste automático por luminancia WCAG: > 0,45 (amarillo, blanco) añade contorno oscuro de 0,9; < 0,05 (negro) añade contorno claro; el resto, contorno sutil.
- Probado: `#176650`, `#d83a2a`, `#1d5fd1`, `#f4c430`, `#111111`, `#f2f2f2`, valor inválido (cae al verde `#176650`) y vacío (también).
- Rotaciones 0°, 45°, 90°, 180° y 270° y escalas 0,75 / 1 / 1,36 / 2,2 en fondo claro y oscuro: sin deformaciones, el frente se reconoce siempre.

## 2. Opciones para el 3D

| Criterio | A. CSS 3D mejorado | B/C. Sprites + máscara | D. GLB en tiempo real |
|---|---|---|---|
| Peso descargado | ~ +2–4 KB (estimado; hoy `bus.js` 23.351 B + `bus3d.css` 9.166 B) | **16 dir.: 38,6 KB** (base 25,5 + máscara 13,0, WebP) · **32 dir.: 79,9 KB** (52,6 + 27,3) | GLB **179 KB** + three.js **670.681 B (166 KB gzip)** + código propio |
| Peticiones nuevas | 0 | 2 (base y máscara), solo al activar 3D | 2+ |
| Nodos DOM | hoy 65 de 3D + 54 del SVG = **119** (ambos se crean siempre) | 1 `div` + 1 máscara (≈ 3 nodos) | 1 `canvas` (WebGL) |
| Memoria | baja | atlas 384×384 (16) o 768×384 (32) decodificado ≈ 0,6 / 1,2 MB | contexto WebGL + geometría 7.624 tris; un contexto por bus o uno compartido |
| Varios buses | sin límite práctico | sin límite práctico | costoso: un `canvas` por marcador |
| Color dinámico | variables CSS (`--c`) | `background-color` + `mask-image` en una capa (probado) | material |
| Calidad a 72×72 | la actual | la del modelo Blender real (7.624 tris) | la mejor, pero sin necesidad |
| Offline / PWA | ya funciona | añadir 2 archivos a `SHELL` en `sw.js` | añadir three.js al `SHELL` |
| Equipos modestos / `saveData` | igual que hoy | puede cargarse solo si no es `lowEnd`; si no, queda el CSS | peor caso |
| Dependencias nuevas | ninguna | ninguna | three.js |
| Mantenimiento | editar CSS a mano | regenerar con `scripts/sprites.py` | pipeline GLB |

Notas de la prueba de sprites (96 px por fotograma, cámara ortográfica a 32° de elevación como el `rotateX(58deg)` actual, 8,4 m por fotograma, iluminación fija):

- A 96 px el sprite se amplía como máximo ×1,2 al zoom 18 (escala 2,2 × 0,545), así que sale nítido. Con 128 px por fotograma el peso subiría ≈ 1,8×.
- AVIF pesa aún menos (16 dir.: 27,6 KB; 32 dir.: 55,0 KB), pero WebP tiene mejor soporte en navegadores móviles antiguos.
- La máscara de la franja se verificó aplicando color con `capa + mask-image`: funciona en las 16 direcciones (`previews/sprites_coloreado.png`). Limitación: el color queda plano; si se quiere conservar el sombreado, usar `mix-blend-mode: multiply` sobre una base con la franja en gris neutro.
- Con 32 direcciones el salto máximo entre fotogramas es 11,25° (22,5° con 16), que se nota al girar en curvas cerradas: el heading actual cambia suave con `diff × min(1, dt·8)`.

## 3. Recomendación

1. **2D:** reemplazar el SVG por el prototipo, tras tu aprobación. Menos nodos, sin IDs y mejor lectura.
2. **3D:** **opción B/C con 32 direcciones (≈ 80 KB en WebP), cargada solo al activar 3D, con el CSS 3D actual como fallback** (si no carga el recurso, `lowEnd` o `saveData`). Si el peso te parece alto, 16 direcciones cuestan 38,6 KB.
3. **No usar GLB:** supondría ~ 850 KB (three.js + modelo) y un contexto WebGL por bus, sin ganancia visible a 72×72 px.
4. **Mejora adicional (opcional, sin cambiar funciones):** en modo 2D hoy se construyen igualmente los 65 nodos del 3D, ocultos con `display:none`. Con el nuevo diseño se podrían crear solo al activar 3D.

## 4. Archivos que se tocarían al integrar (todavía NO modificados)

- `static/js/bus.js`: `build2DBus` y la carga de sprites en `build3DBus` (mismo retorno `{ wrap, rot, body }`).
- `static/css/bus3d.css`: estilos de la capa de sprites; el modelo CSS queda como fallback.
- `static/sw.js`: añadir los recursos nuevos a `SHELL` y subir `VERSION`.
- Nuevos: `static/img/bus/bus_32_base.webp`, `static/img/bus/bus_32_mask.webp` (o los de 16 direcciones).
