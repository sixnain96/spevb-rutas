# Changelog

## V3 (3.0.0)

Cambios frente a la V2 publicada en `main`.

### Planificador de viaje (nuevo)

- Compara el origen A y el destino B con todas las rutas activas, sin límite de distancia: la primera opción es siempre la ruta registrada más cercana.
- Dibuja automáticamente la mejor opción: línea punteada desde A hasta el punto de subida, tramo del bus y línea punteada desde el punto de bajada hasta B.
- Respeta el sentido de circulación: las rutas `IDA` y `REGRESO` se usan en el sentido en que fueron dibujadas; un viaje al revés aparece como alternativa con advertencia. Las rutas `CIRCULAR` pueden dar la vuelta completa.
- «Ver rutas que pasan cerca del origen» devuelve las 8 rutas más cercanas y dibuja la primera.

### Direcciones y lugares (nuevo)

- Búsqueda de direcciones de Villavicencio con Nominatim/OpenStreetMap, acotada al área de la ciudad.
- Normalización de nomenclatura colombiana (`Cra`, `Cll`, `Av`, `Diag`, `Tv`, `No.`, `5 a 50`…) sin alterar palabras completas como `Avenida`, `Norte` o `Clínica`.
- Catálogo local persistente (`lugares`) para registrar puntos que no aparecen en el proveedor.

### Editor de recorridos

- Creación de rutas nuevas, no sólo edición de las DEMO.
- Historial de versiones en `ruta_revisiones` y control de concurrencia (`409 Conflict`).
- Botón «Invertir sentido» para corregir rutas dibujadas al revés.
- Se rechazan geometrías inválidas, fuera de Villavicencio o con más de 10.000 puntos.

### Seguridad y operación

- Operaciones de edición protegidas con `EDITOR_TOKEN`, cabeceras de seguridad y límite de tamaño.
- Las claves se leen de `.env`, que está en `.gitignore`; `docs/CAMBIAR-CLAVES.md` explica cómo reemplazarlas.
- `Dockerfile`, `compose.app.yml`, `backup.ps1`, `restore.ps1`, `start.ps1` y `diagnostico.ps1`.

### Pruebas

- Nueve clases de prueba en `src/test` (la V2 no tenía pruebas automáticas).

### Pendiente

- Trazar sobre las calles las 4 rutas aprobadas (Porfía, Galán, Catama y Álamos); hoy son geometrías DEMO.
- Validación oficial de rutas: pendiente de respuesta de las entidades públicas.
- Transbordos entre rutas y caminos peatonales por la red vial.
