# Fase D — rutas revisadas

Respaldos en `backup/`. Pruebas: `RutasPublicasTest`, `ViajeServiceTest` (2 nuevas) y la comprobación contra la API real descrita en el informe de la fase.

Retirado de la interfaz pública: casilla `onlyValidatedTrips` («Solo revisadas»), opciones «Con revisión registrada» y «Pendientes de validar» del filtro de rutas,
`validationBadge`, `demoWarning` y los textos «Recorrido por validar» / «Alguna de las rutas está por validar».
Conservado: campos y columnas de revisión, parámetro `soloValidadas` (siempre `false` desde el frontend), `Planner.soloValidadas()` → `false`,
eventos `spevb-viaje`, recientes/historial/guardados (el campo se guarda y ya no distingue viajes), editor y herramientas de revisión.
Backend: `validada` ya no desempata entre rutas (se puede volver a usar como filtro administrativo cuando se quiera).
