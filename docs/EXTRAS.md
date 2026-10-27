# Funciones añadidas (v4.1)

Todas se añaden sin cambiar el cálculo de rutas, caminatas ni transbordos.

## Compartir viaje
Botón **Compartir** en el resultado. Usa el menú de compartir del celular (WhatsApp, etc.) o copia el enlace. El enlace tiene la forma
`/?a=lat,lng&b=lat,lng&an=Nombre%20A&bn=Nombre%20B`; al abrirlo, la app pone A y B y busca sola. Solo acepta puntos dentro de Villavicencio.

## Mis lugares (favoritos)
Panel plegable bajo «Buscar rutas»: guarda el punto A o B con un nombre (Casa, Trabajo, Universidad…), hasta 12, en el teléfono (`localStorage`, clave `spevb-favoritos`). Cada favorito tiene botones **A** y **B** para usarlo como origen o destino, y ✕ para quitarlo.

## Avisarme al bajar
Con un resultado elegido, el botón **Avisarme al bajar** usa el GPS (el mismo módulo y filtro de `gps.js`) y avisa con mensaje, vibración y sonido:
- a ≈ 600 m: «Te acercas…»
- a ≈ 220 m: «¡Prepárate!» (aviso fijo)
- a ≈ 60 m: «Llegaste…» y pasa a la siguiente parada (en un transbordo: primero donde cambias de bus y luego donde bajas).
Mantiene la pantalla encendida cuando el navegador lo permite (Wake Lock). Si se elige otra opción, el aviso se reinicia con la nueva. Requiere HTTPS o localhost. Un navegador en segundo plano puede dejar de recibir GPS: hay que mantener abierta la app.

## Reportar un problema
Botón **Reportar** (ruta que no pasa por donde dice, caminata mala, dirección mal ubicada, falla, sugerencia). Se envía junto con la ruta elegida y los puntos A/B.
- Público: `POST /api/reportes` (máx. 5 cada 10 min por IP). Tabla `reportes`.
- Administración: página `/reportes.html` (pide EDITOR_TOKEN) o `GET /api/editor/reportes?estado=NUEVO|REVISADO|RESUELTO|DESCARTADO|TODOS` y `PATCH /api/editor/reportes/{id}` con `{"estado":"..."}`.
Los reportes son la mejor fuente para corregir rutas y pasos peatonales (ver `docs/RED-PEATONAL.md`).

## Instalar la aplicación
Botón en el menú (Android/Chrome/Edge) o instrucciones para iPhone (Safari → Compartir → Añadir a pantalla de inicio).

## Límite de solicitudes por IP
Protege el servidor al publicarlo: planificar 40/min, caminata 150/min, lugares/direcciones 120/min, el resto de `/api/` 400/min. Responde 429 con un mensaje claro. Se apaga con `LIMITE_SOLICITUDES=false`. Detrás de Caddy se usa la IP real del visitante (`compose.prod.yml` ya activa los encabezados reenviados).

## Pruebas
`docs/pruebas/extras_e2e.py` (enlace compartido, favoritos, avisos de GPS simulados, reporte de punta a punta). Tests de Java: `LimiteGeneralFilterTest`, `ReporteControllerTest`.
