# API SPEVB 4

Todas las geometrías usan WGS84 / EPSG:4326. En GeoJSON el orden es `[longitud, latitud]`. SPEVB restringe las operaciones espaciales al área configurada para Villavicencio.

| Método | Ruta | Uso |
|---|---|---|
| GET | `/api/rutas` | Lista de rutas activas |
| GET | `/api/rutas/{id}` | Recorrido completo, extremos, paraderos, fuente y versión |
| GET | `/api/rutas/cercanas?lat=4.14&lng=-73.62` | Las 8 rutas más cercanas a un punto, sin límite de distancia |
| GET | `/api/lugares?q=Cra%2033` | Catálogo local + geocodificación de Villavicencio |
| GET | `/api/lugares/reverso?lat=4.14&lng=-73.62` | Nombre/dirección aproximada de un punto |
| POST | `/api/viajes/planificar` | Rutas registradas que conectan origen y destino |
| POST | `/api/editor/rutas` | Crea una ruta nueva con su LineString |
| PUT | `/api/editor/rutas/{id}/recorrido` | Actualiza metadatos y recorrido; archiva la versión anterior |
| GET | `/api/editor/rutas/{id}/historial` | Revisa hasta 30 versiones anteriores |
| GET | `/api/editor/lugares` | Lista el catálogo persistente |
| POST | `/api/editor/lugares` | Guarda un lugar manual confirmado |
| DELETE | `/api/editor/lugares/{id}` | Elimina un lugar del catálogo |
| GET | `/api/estado` | Comprueba base, versión, geocodificador y conteo de lugares |

Los endpoints `/api/editor/` requieren `EDITOR_ENABLED=true` y cabecera `X-Editor-Token`.

## Crear una ruta

```json
{
  "codigo": "R005",
  "nombre": "Galán - Centro",
  "origen": "Sector Galán",
  "destino": "Centro de Villavicencio",
  "sentido": "IDA",
  "descripcion": "Recorrido levantado manualmente por el equipo",
  "color": "#176650",
  "fuente": "Observación y conocimiento del equipo SPEVB",
  "validada": false,
  "notaValidacion": null,
  "recorrido": {
    "type": "LineString",
    "coordinates": [
      [-73.654, 4.145],
      [-73.650, 4.144],
      [-73.644, 4.143]
    ]
  }
}
```

`codigo` debe ser único. El recorrido debe tener entre 2 y 10.000 puntos y todos deben estar en el área de Villavicencio. Las rutas creadas por el editor se almacenan como no demostrativas, aunque pueden permanecer sin validación oficial.

## Actualizar una ruta

```json
{
  "recorrido": {"type":"LineString","coordinates":[[-73.654,4.145],[-73.624,4.145]]},
  "nombre": "Galán - Centro",
  "origen": "Sector Galán",
  "destino": "Centro de Villavicencio",
  "sentido": "IDA",
  "descripcion": "Recorrido ajustado manualmente",
  "color": "#176650",
  "fuente": "Observación del equipo",
  "validada": false,
  "version": 3,
  "notaValidacion": null
}
```

`version` debe coincidir con la versión actual. Una actualización archiva primero el estado anterior y luego incrementa la versión. Un conflicto devuelve 409.

## Guardar una dirección o lugar

```json
{
  "nombre": "Casa de referencia",
  "direccion": "Barrio X, Villavicencio",
  "lat": 4.1423,
  "lng": -73.6268,
  "fuente": "Registrado manualmente por el equipo SPEVB"
}
```

Los lugares creados por el editor quedan `confirmado=true`. Los resultados externos almacenados automáticamente quedan `confirmado=false`.

## Rutas cercanas

Ejemplo:

```text
GET /api/rutas/cercanas?lat=4.1423&lng=-73.6268&soloValidadas=false
```

Sin `radioMetros` devuelve las 8 rutas más cercanas, estén a la distancia que estén. Si se envía `radioMetros` (100–5000), sólo devuelve las que quedan dentro de ese radio.

La respuesta incluye distancia aproximada a la geometría y `puntoCercano`, que puede utilizarse para dibujar la conexión entre el usuario y el recorrido.

## Planificar viaje

```json
{
  "origen": {"lat": 4.145, "lng": -73.654},
  "destino": {"lat": 4.145, "lng": -73.624},
  "soloValidadas": false
}
```

El sistema compara los dos puntos con todos los recorridos activos, sin límite de distancia, así que la primera opción es siempre la ruta registrada más cercana. Cada ruta se usa en el sentido en que fue dibujada (las `CIRCULAR` pueden dar la vuelta completa). Devuelve hasta 12 opciones y hasta 5 alternativas que sólo conectan A y B recorriéndolas al revés. `maxCaminataMetros` (200–3000) es opcional y ya no filtra: sólo determina si `dentroLimite` es `true` (por defecto 1000 m). Cada opción incluye `sentidoRuta` y `sentidoPermitido`; cuando `sentidoPermitido` es `false`, `advertencia` explica por qué no se recomienda. Las opciones se ordenan por la suma de la distancia del origen a la línea y de la línea al destino. No calcula transbordos, horarios en tiempo real ni caminos peatonales.

## Búsqueda de direcciones

`GET /api/lugares?q=...` consulta primero el catálogo persistente. Si hay pocos resultados y existe un geocodificador configurado, consulta el proveedor externo restringido a Villavicencio. Los resultados externos se guardan para búsquedas futuras.

`GET /api/lugares/reverso` intenta primero encontrar un lugar local a menos de 45 m; si no existe, usa el proveedor inverso configurado.

## Errores relevantes

- `400`: JSON, campos, área o geometría inválidos.
- `401`: clave de administración ausente o incorrecta.
- `403`: editor deshabilitado u operación cross-site bloqueada.
- `404`: ruta inexistente.
- `409`: conflicto de versión.
- `413`: JSON superior a 2 MB.
- `415`: escritura JSON con tipo de contenido incorrecto.
- `429`: proveedor externo solicita reducir la frecuencia.
- `502`: proveedor geográfico no disponible o respuesta inválida.
- `503`: base de datos no disponible.
