# Contrato: política de recomendación y política de búsqueda (4.13.2)

Documento de trabajo que fija las interfaces entre backend y frontend para dos cambios: (1) recomendación que prioriza el acceso al primer bus
y (2) búsqueda con Google como primer proveedor. Los valores numéricos son **hipótesis de producto**, no datos del profesor ni de Villavicencio.

## A. Recomendación de viajes

### A.1 Respuesta de `POST /api/viajes/planificar` (cambios compatibles)

`ViajeResponse(opciones, alternativas, transbordos, alcance)` **se conserva** y se le añade al final `Recomendacion recomendacion`. Los constructores antiguos siguen existiendo
(recomendacion = null). Cada `Opcion` y cada `Transbordo` recibe un `String id` como último componente (constructor antiguo sin `id` se conserva; el servicio lo rellena).

* `id` de `Opcion`: `D:{rutaId}:{variante}` (variante `MENOS_CAMINATA`, `MAS_RAPIDA`, `BAJAR_Y_CAMINAR`, `SOLO_SEGUNDA_RUTA`); las de `alternativas` (sentido contrario) usan prefijo `A:`.
* `id` de `Transbordo`: `T:{rutaIdPrimerBus}>{rutaIdSegundoBus}`.

```
Recomendacion {
  int politicaVersion;            // 2 en esta entrega; subir cuando cambien las reglas (entra en la clave de caché del servidor y en «Recientes» del navegador)
  String idRecomendada;           // id de la opción recomendada, o null si no hay ninguna elegible
  String motivo;                  // frase en español con números reales, p. ej. «Tomas el primer bus a 120 m de tu ubicación. Requiere un transbordo.»
  List<Item> ranking;             // TODAS las opciones comparadas, en orden de recomendación (la 1.ª es idRecomendada si existe)
}
Item {
  String id; String tipo;         // DIRECTA | DIRECTA_MAS_RAPIDA | BAJAR_Y_CAMINAR | SOLO_SEGUNDA_RUTA | SENTIDO_CONTRARIO | TRANSBORDO
  int orden;                      // 1 = recomendada
  boolean elegible;               // false: no puede ser la recomendada (p. ej. sentido contrario al registrado, sin camino peatonal)
  String motivoNoElegible;        // null si elegible
  double caminataInicialMetros;   // A → primer punto de subida
  double caminataCambioMetros;    // bajada 1.er bus → subida 2.º bus (0 si no hay transbordo)
  double caminataFinalMetros;     // última bajada → B
  double caminataTotalMetros;     // suma de las tres
  double minutos;                 // estimación total (caminar 80 m/min, bus 267 m/min; el transbordo suma 8 min de ESPERA ESTIMADA: no hay horarios)
  int buses;                      // 1 o 2  (no hay precios: solo se informa «dos buses»)
  String calidadDistancia;        // CALLES (red peatonal) | RECTA (línea recta aproximada)
  List<String> etiquetas;         // SIN_TRANSBORDO, PRIMER_BUS_MAS_CERCANO, MENOR_CAMINATA_TOTAL, MENOR_TIEMPO — solo si de verdad se comparó y esa opción es la mejor sin empate
}
```

El frontend **no aplica ningún criterio propio**: la tarjeta inicial, el trazo dibujado, el historial, «Guardar», «Compartir» e «Iniciar viaje» usan `idRecomendada`.
Si `recomendacion` falta (respuesta guardada por una versión anterior), el frontend lo avisa («calculado con reglas anteriores, busca de nuevo») y usa el orden antiguo.

### A.2 Política (clase `PoliticaRecomendacion`, parámetros con unidades; versión 2)

Separada en tres capas:

1. **Viabilidad** (excluye del puesto recomendado): sentido contrario al registrado; falta de camino peatonal en algún tramo; caminata inicial o final mayor que `MAX_CAMINATA_EXTREMO_METROS` (1.800 m, ya existente).
2. **Prioridad de acceso** (decide si un transbordo reemplaza a la mejor directa). Un transbordo `T` es recomendado en vez de la mejor directa elegible `D` si cumple **todo**:
   * `reduccionAccesoMinimaMetros` = 300 y `reduccionAccesoMinimaRelativa` = 0,5 → `D.inicial − T.inicial ≥ max(300 m, 0,5 · D.inicial)`.
   * `holguraCaminataTotalMetros` = 300 → `T.total ≤ D.total + 300 m`.
   * `maxCaminataCambioMetros` = 400 → `T.cambio ≤ 400 m`.
   * `maxTiempoExtraMinutos` = 15 → `T.minutos ≤ D.minutos + 15`.
   Sin directa elegible, se recomienda el mejor transbordo elegible. Entre varios transbordos que cumplen: menor caminata inicial, luego menor total, menor tiempo, id.
3. **Desempates estables**: caminata total, tiempo, número de buses, tipo (directa antes que transbordo), `id` lexicográfico. Mismo resultado en ejecuciones repetidas.

**Dominadas:** una opción con más (o igual) caminata inicial, total, tiempo y buses que otra, y estrictamente peor en al menos uno, se marca `elegible=true` pero no se vuelve recomendada; una transbordo claramente dominada por una directa se omite de la lista de transbordos.
Las directas, las alternativas «baja y camina», «una sola ruta» y el sentido contrario **se conservan** como alternativas (no se borran datos ni geometrías).

Casos de referencia (se cubren en `RecomendadorTest`): A) 900+100 m/30 min vs 100+100+100 m/34 min → transbordo; B) 150+100/25 vs 130+250+100/40 → directa; C) 800+100/30 vs 100+1.800+500/65 → directa; D) acceso por calles real vs línea recta: se compara la caminata por calles; E) sentido contrario nunca desplaza a un transbordo viable; F) empate → orden estable.

### A.3 Generación de candidatos

`TransbordoService` debe conservar, por par de rutas, además del candidato de menor tiempo, el de **menor caminata inicial** (la subida más cercana a A), y la sustitución «camina hasta la segunda ruta» (`SOLO_SEGUNDA_RUTA`) no debe eliminar un transbordo cuya caminata inicial sea mucho menor (criterio de `reduccionAcceso*`). `valeLaPena` conserva los transbordos que cumplen la política **o** el criterio anterior (para ofrecerlos al menos como alternativa). Límites de cálculo se mantienen (finalistas, máximo de opciones) y se mide el efecto.

## B. Búsqueda de direcciones

### B.1 Prioridad por proveedor (no mezcla con cupos)

Para texto nuevo: **1) Google** (si `MAP_PROVIDER=google` y hay `GOOGLE_MAPS_API_KEY`) → **2) ArcGIS** (si Google falla, no aporta resultados utilizables o está en pausa) → **3) respaldo local**: índice local de direcciones/catastro/OSM importado y, solo en búsqueda **explícita** (Enter o «Buscar»), Nominatim. **Nominatim público nunca se consulta para autocompletar** (sus condiciones lo prohíben).
Si Google devuelve resultados utilizables **no se consulta ArcGIS «para rellenar»** ni se espera a proveedores secundarios. Coordenadas escritas, favoritos y puntos elegidos en el mapa se resuelven directamente.
Sin Google: ArcGIS → índice local (en autocompletar); Nominatim solo en explícita.

**Resultado utilizable:** dentro de `CityArea`; coordenadas válidas cuando se resuelve el punto (una predicción de autocompletado vale sin coordenadas hasta pedir su detalle); relacionado con la consulta; con nivel de detalle apropiado (una ciudad o barrio no cuenta como dirección exacta cuando se escribió una dirección con número).

### B.2 Estados y errores tipados

Las respuestas de `/api/lugares/sugerencias` y `/api/lugares` añaden: `estado` ∈ `OK | SIN_COINCIDENCIAS | TIMEOUT | RED | CUOTA | CLAVE | FUERA_DE_CIUDAD | RESPUESTA_INVALIDA | PAUSADO`, `proveedor` (el que respondió: `google | arcgis | local | nominatim | ninguno`) y `intentos` (lista `{proveedor, estado}` de lo consultado). «Sin coincidencias» ya no se confunde con un fallo.
Presupuesto total por búsqueda (`presupuestoTotalMs`, p. ej. 3.500 ms para sugerencias y 8.000 ms para explícita) y límite por proveedor (`timeoutProveedorMs`); sin esperas en cascada. Un proveedor con fallos repetidos (3 seguidos) entra en pausa breve (30 s) y se reintenta con una sola consulta de prueba (cortacircuitos).

### B.3 Elección de una sugerencia

`GET /api/lugares?q=…&magicKey=…`: con `magicKey` se resuelve **ese** identificador (prefijo `g:` = Google Place; si no, ArcGIS). Si falla, la respuesta lleva `eleccionFallida: true`, `estado` del fallo y **ninguna sustitución silenciosa** por otro resultado; el frontend informa y permite reintentar o elegir otra. Con `magicKey` no se consulta el resto de fuentes.

### B.4 Places: sesión y campos

Autocomplete + Place Details de una misma búsqueda comparten un `sessionToken` (UUID generado por el navegador al empezar a escribir en un campo, descartado al confirmar o abandonar; query `sesion=`). El token no se reutiliza entre búsquedas. Detalle con máscara de campos mínima (`id,displayName,formattedAddress,location,types`). No se guarda contenido de Google en PostgreSQL ni se cachea más allá de lo permitido.

## C. Estado de cada campo A/B (frontend)

`vacio → editando → consultando → mostrando → resolviendo → confirmado | error`. Al **confirmar** (sugerencia elegida, coordenadas, favorito, punto en mapa, ubicación actual, búsqueda explícita resuelta): cancelar debounce y solicitudes, invalidar la generación del campo, cerrar y vaciar sugerencias, actualizar `aria-expanded`/`aria-activedescendant`, guardar texto, coordenadas, fuente y precisión, no consultar por foco/blur/cambios de layout; el campo sigue siendo editable y solo una **edición real** (no foco) invalida el punto. Sin consultas durante composición IME. Cada campo A/B tiene su propio control de solicitudes y su propio `sessionToken`.
