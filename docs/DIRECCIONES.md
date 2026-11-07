# Base propia de direcciones de Villavicencio (V3.6)

SEVB ya no depende de servicios externos para encontrar una dirección: guarda su **propia base** en PostGIS y
sólo recurre a ArcGIS/Nominatim cuando la dirección no está.

## De dónde salen las direcciones
| Fuente | Qué aporta | Precisión |
|---|---|---|
| **Catastro municipal** (capa pública U_TERRENO) | ≈ 100.000 predios con su dirección | Centro del predio, no la puerta |
| **OpenStreetMap** (Overpass) | ≈ 1.800 lugares con nombre (colegios, universidades, parques, comercios, hoteles, barrios y veredas) y las pocas direcciones con número de puerta que existan (≈ 150) | Puntual |
| **Confirmaciones de usuarios** | Quien escribe una dirección y fija el punto a mano deja una «confirmación» | Mejora sola con el uso |
| Puntos confirmados por el equipo (`/lugares.html`) | Lugares y direcciones validados | La más alta |
| ArcGIS / Nominatim | Sólo si la base propia no resuelve | Variable |

La importación es automática: al primer arranque (y cada 30 días) se descarga el catastro en segundo plano
(≈ 5 minutos, 53 páginas de 2.000 predios). Es idempotente: repetirla actualiza lo existente. Para lanzarla a mano:

```bash
curl -X POST -H "X-Editor-Token: TU_CLAVE" http://localhost:8080/api/editor/direcciones/importar-catastro
curl http://localhost:8080/api/direcciones/estado          # progreso y total
curl -H "X-Editor-Token: TU_CLAVE" http://localhost:8080/api/editor/direcciones/sin-resultado   # búsquedas que no se pudieron resolver
```

### OpenStreetMap
Se consulta Overpass en consultas pequeñas y separadas (los servidores públicos rechazan las grandes con 429/504), probando varios
servidores en orden (`OVERPASS_URLS`) y descartando los que fallan. Si ninguno responde y la tabla está vacía, se carga la
**instantánea incluida** (`src/main/resources/osm/villavicencio-osm.json.gz`, 17-oct-2026) para que el buscador funcione desde el primer arranque.
Los datos son © colaboradores de OpenStreetMap (ODbL): cada resultado muestra la fuente. Para refrescar a mano:
`POST /api/editor/direcciones/importar-osm`.

## Qué entiende el buscador
- Variantes de escritura: `Carrera 48 #19-15 Sur`, `cra 48 No. 19 15`, `Kr48 19-15`, `Calle 17 Sur # 39-03`.
- Formato del catastro: `C 17SUR 39 03 MZ B CS 10 URB SAN J` (vía Sur pegada, manzana, casa, barrio truncado a 32 caracteres).
- **Esquinas:** `Calle 40 con Carrera 25`.
- **Manzana y casa:** `barrio San Marcos manzana C casa 5`.
- **Barrios** (autocompletado y búsqueda).
- **Placas que no existen en el catastro:** se estima la posición entre las dos vecinas de la misma vía y paridad
  (`INTERPOLADA`), o se usa la vecina más cercana (`VECINA_APROXIMADA`), o la zona entre cruces conocidos (`VIA_APROXIMADA`).
  Siempre se avisa en pantalla de que es una estimación.

## Pantalla de administración (`/lugares.html`)
Con la clave de edición, la sección «Base de direcciones y pendientes» permite:
- ver cuántas direcciones y lugares hay y el estado de cada importación, y lanzar «Actualizar catastro» / «Actualizar OpenStreetMap»;
- ver las **búsquedas sin resultado** (las más repetidas primero): «Ubicar» → marcar el punto en el mapa → «Guardar lugar / corregir dirección»;
- **corregir una dirección completa**: el punto queda como fuente `EQUIPO` (confianza 95) y pasa a ser el primer resultado de esa dirección,
  por encima del catastro; además la dirección sale de la lista de pendientes.

## Cómo aprende
1. **Confirmaciones:** si una persona escribe `Carrera 30 #12-34` y luego fija el punto en el mapa, se guarda la pareja
   (clave canónica → punto). Con **3 o más confirmaciones coherentes (≈ 55 m)** ese punto pasa a ser el primer resultado
   (`CONFIRMADA_POR_USUARIOS`). Se rechazan confirmaciones a más de 1,5 km de lo que la base ya sabe y se limita a 30 por minuto por IP.
2. **Popularidad:** las sugerencias elegidas suben en el orden del autocompletado.
3. **Pendientes:** lo que nadie encontró queda en `busquedas_sin_resultado`: es la lista de huecos a completar.

## Calidad medida (con los datos reales)
- **Lectura y búsqueda de formato:** 96,3 % de los predios del catastro se interpretan como dirección completa (el resto son
  parques, lotes, zonas verdes y cesiones sin placa).
- **Encontrar el predio correcto:** de 300 direcciones al azar escritas de tres maneras distintas, el predio verdadero aparece en los
  3 primeros resultados a ≤ 40 m el **93,7 %** de las veces. Los fallos son placas repetidas en conjuntos o bloques.
- **Interpolación** (se quita un predio y se estima entre sus vecinos): mediana ≈ 1 m, el 90 % a ≤ 6 m, el 96 % a ≤ 30 m.
  Mide la coherencia de la numeración del catastro; **no** la distancia a la puerta real.
- **Velocidad:** 10–30 ms por búsqueda o sugerencia, sin cuota ni red.

## Límites
- El punto del catastro es el **centro del lote**, no la entrada.
- Urbanizaciones nuevas posteriores a la última edición de la capa (2023) no estarán hasta que se confirmen por usuarios o por el equipo.
- Revisa los términos de uso de los datos del catastro municipal y cita la fuente en tu documentación.
- Los datos de OpenStreetMap son © colaboradores de OpenStreetMap (ODbL): mantén visible la atribución.
- Los servidores públicos de Overpass a veces responden 429/504: la importación reintenta, prueba otros servidores y, si ninguno responde, usa la instantánea incluida.
