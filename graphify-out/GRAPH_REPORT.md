# SEVB — Mapa del grafo de código

Nodos: 186 · Aristas: 537

## Capas (nº de nodos)

- **test**: 53
- **static**: 48
- **service**: 23
- **dto**: 16
- **db**: 11
- **controller**: 10
- **vial**: 8
- **config**: 7
- **repository**: 5
- **exception**: 4
- **spevb**: 1

## Nodos más usados (god nodes, por dependientes entrantes)

- `CityArea` (config) ← 35
- `RutaController` (controller) ← 21
- `ViajeController` (controller) ← 19
- `ReporteController` (controller) ← 19
- `LugarController` (controller) ← 19
- `ParaderoBusController` (controller) ← 18
- `DireccionController` (controller) ← 18
- `CaminataService` (service) ← 12
- `RedPeatonalService` (service) ← 11
- `ViajeService` (service) ← 10
- `VialController` (controller) ← 9
- `F:styles.css` (static) ← 8
- `F:common.js` (static) ← 8
- `RutaNoEncontradaException` (exception) ← 7
- `RedVialBus` (vial) ← 7

## Dependencias entre capas (uses)

- service → service: 21
- controller → service: 15
- service → dto: 14
- service → config: 13
- controller → dto: 12
- vial → vial: 8
- service → repository: 8
- repository → dto: 5
- controller → config: 4
- controller → vial: 4
- service → exception: 4
- exception → exception: 3
- vial → exception: 1
- vial → config: 1
- vial → dto: 1
- config → service: 1
- config → config: 1
- controller → repository: 1
- repository → service: 1
- controller → exception: 1
- vial → service: 1
- dto → dto: 1

## Archivos más grandes

- `src/main/resources/static/js/planner.js` — 1702 líneas
- `src/main/resources/static/css/styles.css` — 1530 líneas
- `src/main/resources/static/css/home.css` — 609 líneas
- `src/main/resources/static/js/bus.js` — 587 líneas
- `src/main/java/co/edu/usta/spevb/service/RedPeatonal.java` — 499 líneas
- `src/main/resources/static/js/viaje.js` — 488 líneas
- `src/main/java/co/edu/usta/spevb/vial/MapMatchingBus.java` — 487 líneas
- `src/main/resources/static/js/editor.js` — 445 líneas
- `src/main/java/co/edu/usta/spevb/service/ViajeService.java` — 417 líneas
- `src/main/resources/static/js/common.js` — 415 líneas

## Clases de service/controller/vial sin test que las referencie

- `CuentaController`
- `DireccionController`
- `ParaderoBusController`
- `CatastroImportService`
- `Enrutador`