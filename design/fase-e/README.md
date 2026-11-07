# Fase E — transbordos inteligentes

Respaldos en `backup/`. Detalle de reglas y constantes: `docs/TRANSBORDOS.md`. Pruebas: `TransbordoServiceTest` (24 → 26, con redes de calles de prueba:
normal, con calles cortas, con «río» con y sin puente), `ViajeServiceTest`, `TransbordosPresentacionTest`.

Archivos: `TransbordoService` (decisión, `Resultado`, `calcularConAlternativas`), `ViajeService` (añade las alternativas al final de las directas),
`ViajeResponse` (solo comentario de `variante`), `planner.js` (rótulos y texto de las variantes), `sw.js`.
Medición «antes/después» con los mismos 70 viajes: `design/fase-e/audit_transbordos.py` (se puede repetir: `python audit_transbordos.py http://127.0.0.1:8080 70`).
