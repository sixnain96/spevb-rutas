# Inventario de contratos públicos y cambios

Comparación de `SEVB-V3.4` (entrega anterior) contra la versión actual. Generado por `scripts/inventario_contratos.py`.

## Endpoints HTTP (58 actuales; 51 antes)

```
DELETE /api/editor/lugares/{id}
DELETE /api/editor/paraderos/{id}
DELETE /api/editor/pasos/{id}
GET /api/caminata
GET /api/caminata/estado
GET /api/caminata/red
GET /api/cuenta/config
GET /api/cuenta/datos
GET /api/cuenta/yo
GET /api/direcciones/estado
GET /api/editor/calidad
GET /api/editor/direcciones/sin-resultado
GET /api/editor/lugares
GET /api/editor/paraderos
GET /api/editor/paraderos/{id}
GET /api/editor/pasos
GET /api/editor/reportes
GET /api/editor/rutas/{id}/calidad
GET /api/editor/rutas/{id}/historial
GET /api/editor/vial/estado
GET /api/estado
GET /api/lugares
GET /api/lugares/reverso
GET /api/lugares/sugerencias
GET /api/paraderos
GET /api/puentes
GET /api/rutas
GET /api/rutas/cercanas
GET /api/rutas/codigo/{codigo}
GET /api/rutas/{id}
PATCH /api/editor/reportes/{id}
POST /api/cuenta/clave
POST /api/cuenta/datos/{clave}
POST /api/cuenta/eliminar
POST /api/cuenta/entrar
POST /api/cuenta/google
POST /api/cuenta/registro
POST /api/cuenta/salir
POST /api/editor/direcciones/corregir
POST /api/editor/direcciones/importar-catastro
POST /api/editor/direcciones/importar-osm
POST /api/editor/direcciones/sin-resultado/descartar
POST /api/editor/lugares
POST /api/editor/paraderos
POST /api/editor/paraderos/lote
POST /api/editor/pasos
POST /api/editor/red-peatonal/actualizar
POST /api/editor/rutas
POST /api/editor/vial/ajustar-ruta
POST /api/editor/vial/candidatos
POST /api/lugares/confirmaciones
POST /api/lugares/elecciones
POST /api/reportes
POST /api/viajes/planificar
PUT /api/editor/paraderos/{id}
PUT /api/editor/pasos/{id}
PUT /api/editor/rutas/{id}/paraderos
PUT /api/editor/rutas/{id}/recorrido
```

- **Eliminados:** ninguno
- **Añadidos:** ['GET /api/editor/calidad', 'GET /api/editor/rutas/{id}/calidad', 'GET /api/editor/vial/estado', 'GET /api/rutas/codigo/{codigo}', 'POST /api/editor/vial/ajustar-ruta', 'POST /api/editor/vial/candidatos', 'PUT /api/editor/pasos/{id}']

## DTO (21 actuales; 18 antes)

- `ActualizarRecorridoRequest`: recorrido, 120
- `BusLeg`: rutaId, codigo, nombre, color, validada, puntoSubida, puntoBajada, tramo, recorridoKm, paraderoSubida, paraderoBajada
- `CrearLugarRequest`: 160
- `CrearRutaRequest`: "[A-Za-z0-9_-]{2, 20}"
- `LugarResponse`: id, nombre, direccion, lat, lng, fuente, confirmado, precision
- `Opcion`: rutaId, codigo, nombre, color, validada, acercamientoInicioMetros, acercamientoFinMetros, recorridoKm, accesoTotalMetros, dentroLimite, sentidoViaje, sentidoRuta, sentidoPermitido, puntoSubida, puntoBajada, tramo, advertencia, caminataPorCalles, paraderoSubida, paraderoBajada, caminataInicioRuta, caminataFinRuta, variante
- `ParaderoResponse`: id, secuencia, nombre, ubicacion, distanciaRecorridoMetros
- `PasoPeatonalActualizarRequest`: 3, 120
- `PasoPeatonalRequest`: tipo, 3, 120
- `PlanificarViajeRequest`: origen, destino, @Min(200
- `PuentePublicoResponse`: id, nombre, metros, trazo
- `Punto`: @DecimalMin("-90"
- `RevisionResponse`: version, archivadoEn, datos
- `RutaCercanaResponse`: rutaId, codigo, nombre, origen, destino, color, validada, distanciaMetros, puntoCercano, caminataPorCalles
- `RutaCodigoResponse`: id, codigo, nombre, aliasUsado, codigoConsultado
- `RutaDetalleResponse`: id, codigo, nombre, origen, destino, sentido, descripcion, color, distanciaKm, activa, demostrativa, validada, fuente, inicio, fin, recorrido, paraderos, version, actualizadoEn, notaValidacion, advertencias
- `RutaResumenResponse`: id, codigo, nombre, origen, destino, sentido, color, distanciaKm, activa, validada
- `SugerenciaResponse`: texto, detalle, magicKey, lat, lng, id, fuente, precision
- `Transbordo`: minutos, caminataMetros, busKm, pasajes, esperaCambioMinutos, primerBus, segundoBus, caminataInicio, caminataCambio, caminataFin, advertencia
- `ViajeResponse`: opciones, alternativas, transbordos, alcance
- `WalkLeg`: coordinates, metros, minutos

- **DTO eliminados:** ninguno
- **Campos eliminados:** ninguno
- **Campos añadidos:** ninguno
- **DTO nuevos:** ['PasoPeatonalActualizarRequest', 'PuentePublicoResponse', 'RutaCodigoResponse']

## API pública de JavaScript (`Spevb.*`; 46 miembros actuales, 35 antes)

- **Eliminados:** ninguno
- **Añadidos:** ['editor-geo.js: Spevb.EditorGeo', 'editor-geo.js: Spevb.EditorGeo.dist', 'editor-geo.js: Spevb.EditorGeo.infoVertice', 'editor-geo.js: Spevb.EditorGeo.limitar', 'editor-geo.js: Spevb.EditorGeo.longitud', 'editor-geo.js: Spevb.EditorGeo.mover', 'editor-geo.js: Spevb.EditorGeo.segmentoCercano', 'editor-geo.js: Spevb.EditorGeo.seleccionar', 'editor-geo.js: Spevb.EditorGeo.simplificar', 'editor-geo.js: Spevb.EditorGeo.vecinos', 'editor-geo.js: Spevb.EditorGeo.verticeCercano']

## Archivos

Añadidos 68 · modificados 44 · eliminados 3 (se excluyen `target/`, `.idea/`, `.claude/`, `.env`, ZIP y logs).

### Modificados
- `.dockerignore`
- `.env.example`
- `.gitignore`
- `CHANGELOG.md`
- `CONTINUIDAD.md`
- `Dockerfile`
- `LEEME_PRIMERO.txt`
- `README.md`
- `backup.ps1`
- `compose.app.yml`
- `compose.prod.yml`
- `deploy/backup.sh`
- `deploy/restore.sh`
- `design/bus/README.md`
- `docs/API.md`
- `docs/VERIFICACION-POSTGIS.md`
- `docs/VERIFICACION.md`
- `pom.xml`
- `restore.ps1`
- `src/main/java/co/edu/usta/spevb/controller/PasoPeatonalController.java`
- `src/main/java/co/edu/usta/spevb/controller/PuenteController.java`
- `src/main/java/co/edu/usta/spevb/controller/RutaController.java`
- `src/main/java/co/edu/usta/spevb/dto/ActualizarRecorridoRequest.java`
- `src/main/java/co/edu/usta/spevb/exception/GlobalExceptionHandler.java`
- `src/main/java/co/edu/usta/spevb/repository/PasoPeatonalRepository.java`
- `src/main/java/co/edu/usta/spevb/repository/RutaRepository.java`
- `src/main/java/co/edu/usta/spevb/service/CaminataService.java`
- `src/main/java/co/edu/usta/spevb/service/PasoPeatonalService.java`
- `src/main/java/co/edu/usta/spevb/service/RutaService.java`
- `src/main/resources/application.properties`
- `src/main/resources/static/css/styles.css`
- `src/main/resources/static/editor.html`
- `src/main/resources/static/js/app.js`
- `src/main/resources/static/js/bus.js`
- `src/main/resources/static/js/editor.js`
- `src/main/resources/static/js/paraderos-editor.js`
- `src/main/resources/static/js/planner.js`
- `src/main/resources/static/js/puentes.js`
- `src/main/resources/static/js/seguir-bus.js`
- `src/main/resources/static/manifest.webmanifest`
- `src/main/resources/static/puentes.html`
- `src/main/resources/static/sw.js`
- `src/test/java/co/edu/usta/spevb/config/TransbordosPresentacionTest.java`
- `start.ps1`

### Añadidos
- `LICENSE`
- `deploy/verify-restore.sh`
- `docs/CONFIGURACION.md`
- `docs/DATOS-PERSONALES.md`
- `docs/DOCKER-Y-OPERACION.md`
- `docs/EDITOR.md`
- `docs/EMPAQUETADO.md`
- `docs/INVENTARIO-CONTRATOS.md`
- `docs/MIGRACIONES.md`
- `docs/PRUEBAS.md`
- `e2e/__pycache__/editor_e2e.cpython-314.pyc`
- `e2e/__pycache__/sevb_e2e.cpython-314.pyc`
- `e2e/editor_e2e.py`
- `e2e/js_syntax.py`
- `e2e/run_e2e.py`
- `e2e/sevb_e2e.py`
- `e2e/stub_server.py`
- `e2e/sw_e2e.py`
- `scripts/inventario_contratos.py`
- `scripts/osv_scan.py`
- `scripts/package-release.ps1`
- `scripts/verify-restore.ps1`
- `src/main/java/co/edu/usta/spevb/config/DemoDataFlywayConfig.java`
- `src/main/java/co/edu/usta/spevb/config/LogPrivado.java`
- `src/main/java/co/edu/usta/spevb/controller/VialController.java`
- `src/main/java/co/edu/usta/spevb/dto/PasoPeatonalActualizarRequest.java`
- `src/main/java/co/edu/usta/spevb/dto/PuentePublicoResponse.java`
- `src/main/java/co/edu/usta/spevb/dto/RutaCodigoResponse.java`
- `src/main/java/co/edu/usta/spevb/vial/CalidadRutasService.java`
- `src/main/java/co/edu/usta/spevb/vial/Enrutador.java`
- `src/main/java/co/edu/usta/spevb/vial/MapMatchingBus.java`
- `src/main/java/co/edu/usta/spevb/vial/ProveedorMatchingExterno.java`
- `src/main/java/co/edu/usta/spevb/vial/RedVialBus.java`
- `src/main/java/co/edu/usta/spevb/vial/RedVialBusService.java`
- `src/main/java/co/edu/usta/spevb/vial/ReglasBus.java`
- `src/main/java/co/edu/usta/spevb/vial/ViaOsm.java`
- `src/main/resources/db/demo/R__datos_demostrativos.sql`
- `src/main/resources/db/migration/V10__pasos_version.sql`
- `src/main/resources/db/migration/V1__esquema_base.sql`
- `src/main/resources/db/migration/V2__direcciones_y_lugares_osm.sql`
- `src/main/resources/db/migration/V3__pasos_peatonales.sql`
- `src/main/resources/db/migration/V4__puentes_peatonales.sql`
- `src/main/resources/db/migration/V5__reportes.sql`
- `src/main/resources/db/migration/V6__cuentas.sql`
- `src/main/resources/db/migration/V7__inicio_de_sesion_google.sql`
- `src/main/resources/db/migration/V8__paraderos_del_sistema.sql`
- `src/main/resources/db/migration/V9__ruta_aliases.sql`
- `src/main/resources/static/js/editor-ajuste.js`
- `src/main/resources/static/js/editor-geo.js`
- `src/test/java/co/edu/usta/spevb/config/ConfiguracionArranqueTest.java`
- `src/test/java/co/edu/usta/spevb/config/ContratosPublicosTest.java`
- `src/test/java/co/edu/usta/spevb/config/DependenciasSegurasTest.java`
- `src/test/java/co/edu/usta/spevb/config/LogPrivadoTest.java`
- `src/test/java/co/edu/usta/spevb/config/VersionConsistenciaTest.java`
- `src/test/java/co/edu/usta/spevb/controller/PuenteControllerTest.java`
- `src/test/java/co/edu/usta/spevb/db/MigracionesPostgisTest.java`
- `src/test/java/co/edu/usta/spevb/service/PuenteEdicionTest.java`
- `src/test/java/co/edu/usta/spevb/service/RutaCodigoAliasTest.java`
- `src/test/java/co/edu/usta/spevb/vial/MapMatchingBusTest.java`
- `src/test/java/co/edu/usta/spevb/vial/ProveedorMatchingExternoTest.java`
- `src/test/java/co/edu/usta/spevb/vial/RedVialBusServiceTest.java`
- `src/test/java/co/edu/usta/spevb/vial/RedVialBusTest.java`
- `src/test/java/co/edu/usta/spevb/vial/ReglasBusTest.java`
- `src/test/java/co/edu/usta/spevb/vial/Sinteticas.java`
- `src/test/java/co/edu/usta/spevb/vial/VialEditorApiTest.java`
- `src/test/resources/contratos/endpoints-4.10.0.txt`
- `src/test/resources/legacy/data-4.9.6.sql`
- `src/test/resources/legacy/schema-4.9.6.sql`

### Eliminados
- `src/main/resources/data.sql`
- `src/main/resources/schema.sql`
- `src/test/resources/mockito-extensions/org.mockito.plugins.MockMaker`

## Veredicto

**Ninguna función pública fue eliminada** (endpoints, DTO, campos de DTO y API de JavaScript conservados).
