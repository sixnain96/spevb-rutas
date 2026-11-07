# Política de recomendación de viajes (versión 2)

**Intención de producto:** priorizar el punto donde la persona puede tomar el bus. Una ruta directa que obliga a caminar mucho para llegar a ella ya no gana solo por ser directa; un
transbordo que permite abordar claramente más cerca de A puede ser la recomendación principal, siempre que el viaje siga siendo razonable. Las directas se conservan como alternativas.

Código: `Recomendador` (puro, sin base de datos), `PoliticaRecomendacion` (parámetros), `ViajeService` (arma ids y respuesta), `TransbordoService` (genera candidatos).
Contrato con el frontend: [CONTRATO-RECOMENDACION-Y-BUSQUEDA.md](CONTRATO-RECOMENDACION-Y-BUSQUEDA.md). El frontend **no aplica ningún criterio propio**: usa `recomendacion.idRecomendada`.

## Qué se compara (siempre por separado)

| Dato | Significado |
|---|---|
| Caminata inicial | A → primer punto de subida (por la red peatonal; `RECTA` solo si es aproximada) |
| Caminata de cambio | bajada del 1.er bus → subida al 2.º (0 sin transbordo) |
| Caminata final | última bajada → B |
| Caminata total | suma de las tres |
| Tiempo estimado | caminar 80 m/min + bus 267 m/min; el transbordo suma **8 min de espera ESTIMADA** (no hay horarios) |
| Buses | 1 o 2. **No hay precios**: solo se informa «dos buses» |

## Las tres capas

1. **Viabilidad** (qué puede ser recomendado): se excluye el sentido contrario al registrado de la ruta y lo que no tiene camino peatonal. Una caminata inicial o final mayor de 1.800 m deja la opción
   fuera del puesto recomendado, salvo que ninguna cumpla (entonces se recomienda la menos mala: nunca se deja sin recomendación si hay algo en el sentido correcto).
2. **Prioridad de acceso:** la base es la mejor directa elegible (menor caminata total). Un transbordo **la reemplaza solo si cumple todo**:

   | Parámetro | Valor inicial | Condición |
   |---|---|---|
   | `reduccionAccesoMinimaMetros` | 300 m | caminata inicial del transbordo ≥ 300 m menor que la de la directa… |
   | `reduccionAccesoMinimaRelativa` | 0,5 | …y ≥ 50 % menor (se exige el mayor de los dos) |
   | `holguraCaminataTotalMetros` | 300 m | caminata total del transbordo ≤ la de la directa + 300 m |
   | `maxCaminataCambioMetros` | 400 m | caminata entre buses ≤ 400 m |
   | `maxTiempoExtraMinutos` | 15 min | tiempo del transbordo ≤ el de la directa + 15 min |
   | `maxCaminataExtremoMetros` | 1.800 m | límite de caminata inicial/final para ser recomendable |

   Sin directa elegible se recomienda el mejor transbordo (menor caminata inicial, luego total, tiempo e id).
3. **Desempates estables:** caminata total, tiempo, número de buses, tipo (directa antes que transbordo) e `id`. Mismo resultado en ejecuciones repetidas (probado con listas barajadas).

**Estos valores son hipótesis de producto**, no datos medidos en Villavicencio ni indicados por el profesor. Están en un solo lugar (`PoliticaRecomendacion.DEFECTO`) para ajustarlos con datos reales; al cambiar uno hay que subir `VERSION`.

## Ejemplos (casos sintéticos; no son rutas reales)

| Caso | Directa | Transbordo | Recomendada |
|---|---|---|---|
| A | 900 + 100 m · 30 min | 100 + 100 (cambio) + 100 m · 34 min | **Transbordo** (la directa se conserva como alternativa) |
| B | 150 + 100 m · 25 min | 130 + 250 + 100 m · 40 min | **Directa** (20 m menos no justifican el perjuicio) |
| C | 800 + 100 m · 30 min | 100 + 1.800 + 500 m · 65 min | **Directa** (no gana el transbordo solo por la primera caminata) |
| D | vía a 60 m en línea recta, 1.200 m por calles | otra opción tras 250 m reales | se compara la caminata **por calles** (la línea recta solo se marca como aproximada) |
| E | alternativa contraria al sentido registrado | transbordo válido | el sentido contrario **nunca** desplaza al transbordo viable |
| F | dos resultados equivalentes | | orden estable entre ejecuciones |

Antes, la prioridad era fija (directa → sentido contrario → transbordo): en A ganaba la directa de 900 m de caminata inicial.

### Sensibilidad (cada parámetro movido ±50 %)

A, B y C **no cambian** con ningún parámetro. Solo los casos de borde dependen de los umbrales: «directa 600+100/30 vs transbordo 250+150+100/36» deja de preferir el transbordo si `reduccionAccesoMinimaMetros` o `reduccionAccesoMinimaRelativa` suben 50 %;
«500+100/30 vs 200+350+100/40» además cambia con `maxCaminataCambioMetros` ×0,5 y `maxTiempoExtraMinutos` ×0,5. Es decir: las decisiones claras son robustas y las dudosas dependen de cuánto acceso se quiera exigir; ese es el parámetro que conviene calibrar con datos.

## Generación de candidatos (qué cambió en `TransbordoService`)

* Por par de rutas se conserva, además del candidato de **menor tiempo**, el de **subida más cercana a A** (red peatonal); antes se perdía. Finalistas: tope 8 + 4 (se miden en `TransbordoServiceTest`).
* `valeLaPena` conserva los transbordos que cumplen la política **o** el criterio anterior.
* La sustitución «camina hasta la segunda ruta» (`SOLO_SEGUNDA_RUTA`) ya no elimina un transbordo cuya caminata inicial baja de forma significativa: se conservan ambos.
* La clave de caché de planes lleva la versión de la política (`|p2`): un resultado calculado con reglas anteriores no se sirve como nuevo. «Recientes» del navegador guarda `politicaVersion` y avisa si es anterior.

## Límites conocidos

* No hay pruebas de este flujo con PostGIS real; las pruebas usan una red simulada y servidores simulados. En una red simulada de 8×8 el cálculo pasó de 0,71 a 0,90 ms (+27 %); no se midió un caso donde la subida cercana difiera de la de menor tiempo.
* La espera del cambio (8 min) y las velocidades son estimaciones. `MENOR_TIEMPO` compara tiempos que excluyen la espera inicial del primer bus, como ya hacía el sistema.
* Una ruta circular se trata como directa normal (sin lógica especial).
