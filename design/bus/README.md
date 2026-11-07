# Buseta SEVB — modelo maestro (versión de la aplicación: 4.13.3)

Modelo 3D de la buseta urbana de Villavicencio para **SEVB** (nombre oficial; ver «Nombres» en el `README.md` de la raíz).

**Estado actual: modelo terminado e integrado en la aplicación web.** El modelo Blender (`sevb_bus_master.blend`) se terminó en la Fase 1 y se integró en la versión 4.9.1:

- **Modelo Blender terminado:** 25 objetos de malla (+ 1 raíz vacía), 4.040 vértices, 3.880 caras y 7.624 triángulos (medidos con los biseles evaluados).
- **Sprites de 32 direcciones:** `static/img/bus/bus_32_base.webp` y `bus_32_mask.webp` (vista 3D).
- **Color dinámico por ruta:** solo el material `MAT_RouteColor` cambia; en la aplicación la franja se tiñe con la máscara según el color de la ruta.
- **Vista 2D:** SVG cenital derivado del modelo (con respaldo `build2DBusClasico`).
- **Respaldo CSS 3D:** si el equipo es modesto (`lowEnd`/`saveData`) o los sprites no cargan, la aplicación usa el modelo CSS 3D anterior (`bus3d.css`).
- **Integración actual:** ver la sección «Integración en la app» más abajo.

> **Caras frente a triángulos.** Una *cara* es un polígono de la malla (aquí cuadriláteros y triángulos): 3.880. Un *triángulo* es lo que realmente dibuja la tarjeta gráfica; cada cuadrilátero se divide en dos, por eso hay más triángulos (7.624) que caras. El presupuesto de rendimiento se mide en triángulos.

## Resumen técnico

| Dato | Valor |
|---|---|
| Blender | 5.2.3 LTS (addon MCP for Blender 1.8, protocolo 15) |
| Archivo | `sevb_bus_master.blend` (144 KB; guardado como copia, sin cambiar el archivo abierto en Blender) |
| Unidades | 1 unidad = 1 m, escala uniforme 1.0 en todos los objetos, sin rotaciones ni escalas negativas |
| Ejes | **Frente = +Y**, derecha / puerta de pasajeros = **+X**, arriba = +Z |
| Origen | `SEVB_Bus_Root` en (0, 0, 0): centrado, centro longitudinal, suelo en Z = 0 |
| Largo | 7,87 m con parachoques (cuerpo 7,60 m) |
| Ancho | 2,30 m de carrocería · 2,42 m con ruedas · 2,92 m con espejos |
| Alto | 2,94 m |
| Ejes de ruedas | y = +2,35 (delantero) y −1,85 (trasero): distancia entre ejes 4,20 m · radio de rueda 0,46 m |
| Vértices | **4.040** evaluados (con biseles) |
| Caras | **3.880** evaluadas (polígonos; mezcla de triángulos y cuadriláteros) |
| Triángulos | **7.624** (objetivo 5.000–12.000; máximo 15.000), medidos con los biseles evaluados |
| Objetos | 25 mallas + 1 raíz vacía; todos hijos de `SEVB_Bus_Root` |
| Verificación | todas las mallas con volumen positivo (normales hacia fuera), sin aristas abiertas ni vértices sueltos, salvo `BUS_WheelArches` (discos de una sola cara, a propósito) |

## Colecciones

- `SEVB_BUS_MASTER`: el bus (`SEVB_Bus_Root` y sus 25 piezas `BUS_*`).
- `SEVB_BUS_PRESENTATION`: `SEVB_Bus_Preview_Camera` (ortográfica), luces `SEVB_Key`, `SEVB_Fill`, `SEVB_Rim` y `SEVB_TestPlane` (oculto en render).
- La escena `SEVB_PREVIEW` enlaza solo esas dos colecciones; los renders salen de ahí, así `Cube`, `Camera` y `Light` de la escena original ni aparecen ni se modifican.

## Materiales

`MAT_BodyWhite`, `MAT_RouteColor`, `MAT_RouteColorMask`, `MAT_Windows`, `MAT_WindowFrames`, `MAT_Tires`, `MAT_Rims`, `MAT_Chassis`, `MAT_Bumpers`, `MAT_Headlights`, `MAT_Taillights`, `MAT_Indicators`, `MAT_Grille`, `MAT_Plates`, `MAT_InteriorDark` y, añadido, `MAT_RoofHatch` (gris claro para las escotillas del techo; se separó porque `MAT_Rims` es metálico y se veía distinto sobre el techo).

Todos son Principled BSDF, sin texturas ni transparencias. `MAT_InteriorDark` se usa en las pasarruedas (no hay interior). `MAT_RouteColorMask` (emisión blanca) no está asignado a ningún objeto: tiene *fake user* y sirve para exportar la máscara de la franja.

## Cómo cambiar el color de ruta

Solo `MAT_RouteColor` es dinámico (franja lateral, franja del techo, trasera, letrero). El color del master es un gris azulado neutro (`#5B7C99`) a propósito: el verde institucional no está fijado.

```python
ns = {}
exec(open(r"<raíz del proyecto>/design/bus/scripts/build_bus.py", encoding="utf-8").read(), ns)
ns["set_route_color"]("#d83a2a")
```

O en Blender: *Material Properties → MAT_RouteColor → Base Color*.

## Regenerar todo

Desde la consola de Python de Blender (o por el MCP `execute_blender_code`), en este orden:

**Ejecución portable (sin rutas personales).** Los scripts calculan la raíz `design/bus/` solos. Con `exec()` no existe `__file__`, así que buscan, en este orden: la variable `BUS_ROOT` (la que tú inyectes), la variable de entorno `SEVB_BUS_ROOT`, la ubicación del script, la carpeta del `.blend` abierto (`sevb_bus_master.blend` vive en `design/bus/`) y la carpeta de trabajo (raíz del proyecto o `design/bus`). Si no encuentran nada, fallan con un mensaje claro. Forma recomendada, válida en cualquier carpeta o cuenta de Windows:

```python
from pathlib import Path
RAIZ = Path(r"<carpeta donde descomprimiste>/SEVB-<versión>/design/bus")   # solo si el .blend no está abierto desde ahí
ns = {"BUS_ROOT": str(RAIZ)}
exec((RAIZ / "scripts" / "make_sheets.py").read_text(encoding="utf-8"), ns)
```

Desde la línea de comandos (PowerShell), parado en la raíz del proyecto: `blender --background design/bus/sevb_bus_master.blend --python design/bus/scripts/make_sheets.py` (con `--python` sí existe `__file__`). `e2e`/Maven vigilan que ningún script contenga rutas personales.

1. `scripts/build_bus.py`: reconstruye `SEVB_BUS_MASTER` y los materiales `MAT_*` (borra solo lo propio). `ns["stats"]()` devuelve triángulos y caja envolvente.
2. `scripts/presentation.py`: cámara, luces y escena `SEVB_PREVIEW`. `ns["render"](ruta, dirección, ortho, ancho, alto, plane=None)` renderiza.
3. `scripts/make_sheets.py`: hojas comparativas en `previews/` (solo `bpy` y `numpy`; no requiere Pillow).

Renders cenitales con transparencia en `exports/top_<color>.png` (540×1000, 113,6 px por metro, frente arriba).

## Previsualizaciones (`previews/`)

`cenital`, `iso_frontal_derecha`, `iso_frontal_izquierda`, `lateral_derecho`, `frontal`, `trasera`, `mapa_claro`, `mapa_oscuro`, `iso_ruta_<color>` (#176650, #d83a2a, #1d5fd1, #f4c430), `contacto_vistas`, `colores_ruta`, `tamano_real` y `tamano_real_x3` (cenital a 50, 64 y 100 px de largo, es decir ≈ 27×50, 36×64 y 54×100, sobre mapa claro y oscuro con halo y sombra; el recuadro punteado es el contenedor de 72×72).

## Integración en la app (Puerta D, versión 4.9.1; vigente en 4.9.6)

- `static/js/bus.js`: nuevo `build2DBus` (el anterior queda como `build2DBusClasico`, respaldo); el modelo CSS 3D pasa a construirse solo en `ensure3D()`; capa de sprites (`SPR`, `loadSprites`, `useSprites`, `setSprite`); `apply3D()` elige 2D / sprites / CSS; `render()` llama a `st.model.setSprite(...)`; `st.model` guarda el modelo. API pública y retorno `{ wrap, rot, body }` intactos (con tres campos extra).
- `static/css/bus3d.css`: reglas `.sprite`, `.bus3d-sprite`, `.bs-*` al final del bloque 2D.
- `static/sw.js`: los dos WebP en `SHELL` y `VERSION` (hoy la versión del proyecto, filtrada por Maven desde `pom.xml`).
- Nuevos: `static/img/bus/bus_32_base.webp` (52.610 B) y `bus_32_mask.webp` (27.302 B). Regenerables con `scripts/sprites.py`.
- Los respaldos de los tres archivos originales (`backup/static/`) existían solo en la carpeta de trabajo del autor y **no forman parte del paquete de entrega**; el contenido vigente es el de `static/`.
- Arnés de pruebas con el código real: `test/harness.html` + `test/serve_harness.py` (`/__harness.html?low=1|rm=1|badspr=1|dark=1&z=N`; `runTests()` en consola).

## Límites y fallback

Pesos vigentes: SVG ≤ 6 KB; sprites 79,9 KB en total (límite 120 KB). El modelo CSS (`bus.js` + `bus3d.css`) **sigue siendo el respaldo** del 3D.

## Licencia y procedencia

Modelo, materiales y scripts creados desde cero para SEVB con geometría primitiva. **No se usó ningún recurso externo**: ni Poly Haven, ni Sketchfab, ni Poly Pizza, ni generadores (Meshy, Tripo, Hunyuan, Hyper3D), ni texturas, ni modelos de terceros, ni marcas, logotipos o matrículas reales. La matrícula es un rectángulo amarillo sin caracteres. Licencia: la del proyecto SEVB.
