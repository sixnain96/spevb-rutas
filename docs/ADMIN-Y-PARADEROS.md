# Administración y paraderos (v4.6 · v4.7)

## Paraderos de bus del sistema (v4.7): independientes de las rutas
Página **Paraderos de bus** (`/paraderos.html`, menú → «Paraderos de bus»). Sirve para registrar los paraderos oficiales **sin crear ni editar ninguna ruta**:
1. **Agregar paraderos** → clic en el mapa (zoom 15 o más) → nombre (+ nota opcional) → Enter. Sigue haciendo clic para agregar más.
2. Verás en colores las rutas registradas para ubicar el paradero sobre ellas, y al guardar el sistema te dice **«Sirve a: R001 (3 m)»** (las rutas que pasan a ≤ 30 m). Si ninguna pasa, te avisa que no se usará todavía.
3. Toca un paradero del mapa o de la lista para **editarlo** (nombre, nota, mover arrastrando, activar/desactivar) o **eliminarlo**. La búsqueda ignora tildes y mayúsculas.
4. **Importar muchos**: archivo GeoJSON (puntos con `name`/`nombre`) o CSV (`nombre,lat,lng[,nota]`), hasta 500 por vez; los repetidos (mismo nombre a menos de 5 m) se omiten.
5. La gente los ve en el mapa principal (interruptor «Paraderos», zoom 14 o más).

### Cómo los usa el planificador (subir y bajar)
- Un paradero **sirve a una ruta si pasa a ≤ 30 m de ella** (esto vale para los paraderos del sistema y para los que tiene cada ruta; uno que quede más lejos de la línea no se usa para subir).
- Se prefiere el paradero si **no obliga a caminar más de ≈ 25 m extra** que el mejor punto libre de la ruta (si está al lado del punto ideal, gana siempre). Si el paradero queda más lejos que eso, se usa el punto libre que menos hace caminar.
- Es lo mismo para **subir, bajar y transbordar** (en el transbordo la ventaja equivale a ≈ 24 m).
- Los cambios en paraderos se aplican al instante (se vacía la caché de 90 s de los planes).
- API: `GET /api/paraderos?sur&oeste&norte&este` (público, activos) · `GET/POST /api/editor/paraderos`, `GET/PUT/DELETE /api/editor/paraderos/{id}`, `POST /api/editor/paraderos/lote`.



## Quién es administrador
- En `.env`: `ADMIN_CORREOS=tucorreo@gmail.com` (varios, separados por coma).
- Es administrador quien **entra con Google** usando uno de esos correos. Debe ser con Google porque así el correo queda **verificado**: nadie puede hacerse administrador registrando ese correo con contraseña.
- **Qué cambia**
  - Los enlaces «Direcciones y lugares», «Pasos peatonales», «Reportes» y «Editor de recorridos» **solo los ve un administrador**. Si no hay `ADMIN_CORREOS`, se ven para todos, como antes.
  - Las páginas de administración **ya no piden la clave** a un administrador con sesión: el servidor lo reconoce por su sesión.
  - Cualquier otra persona sigue necesitando la clave `EDITOR_TOKEN` (para eso el editor debe estar activo: `EDITOR_ENABLED=true`).
- **Si aún no tienes Google funcionando**: los enlaces quedan ocultos en el menú, pero puedes entrar escribiendo la dirección (`/editor.html`, `/pasos.html`…) y usar la clave como siempre.

## Editor de paraderos (en `/editor.html`, sección 04)
1. Elige la ruta (debe estar guardada).
2. **Agregar paraderos** → haz clic sobre la línea de la ruta. El paradero se pega solo al recorrido (si haces clic a más de 80 m de la línea, se rechaza) y se numera según su posición. El sistema sugiere el nombre con la dirección más cercana; puedes cambiarlo en la lista.
3. Arrastra un paradero para ajustarlo; **⌖** lo centra; **✕** lo quita; «Quitar todos» limpia la lista.
4. **Guardar paraderos**: se guardan **aparte del trazado** (no crean una versión nueva de la ruta). El servidor valida todo de una vez (nombre de 2 a 160 letras, dentro de Villavicencio, a menos de 30 m del recorrido, máximo 200) y calcula la secuencia por la posición a lo largo de la ruta.
5. Los paraderos aparecen enseguida en el explorador («Paraderos») y el planificador los usa para decir «sube en el paradero X».

API: `PUT /api/editor/rutas/{id}/paraderos` con `{"paraderos":[{"nombre","lat","lng"}]}` (reemplaza la lista completa).

## Para quien usa la app
- **Cuadro «Cambio de bus»** en el punto del transbordo (de qué ruta a cuál), **insignias ① y ②** sobre cada tramo, y rótulos «Sube aquí / Bájate aquí».
- **Colores para daltónicos** (menú → Mapa y accesibilidad): tramos azul y naranja, el segundo bus con línea discontinua y cada tramo numerado. También hay una **Guía de colores** plegable bajo «Buscar rutas».
- **Controles del bus ocultables**: botón – en el panel (o interruptor en el menú). En el celular empiezan ocultos para dejar más mapa; el bus sigue animándose. Se recuerda la elección.

## Pruebas
`docs/pruebas/mejoras_e2e.py` (29 comprobaciones) y `AdminCuentaTest`.
