# Rendimiento de la conexión a la base (Supabase)

Qué se midió, qué se cambió y qué se decidió dejar como estaba. Todo se puede repetir con las herramientas de `scripts/supabase/medir/`.

## Resumen
- **Lo que más pesa no es el pool, es la distancia.** La aplicación hace pocas consultas en la mayoría de las peticiones (1 a 5) pero **unas 20 al planificar un viaje** (el cálculo de la ruta es en la base). Cada consulta es un viaje de ida y vuelta a la red. Con la base a 110 ms (Villavicencio → Virginia) planificar un viaje tarda ≈ 2,6 s; con la base local tarda ≈ 0,14 s. Con el servidor de la aplicación **en la misma región que Supabase** (East US) cada viaje cuesta 1 a 3 ms y el mismo cálculo queda en ≈ 0,2 s. **La mejora más grande para el despliegue es poner la aplicación en East US junto a la base.**
- Se añadió el perfil **`supabase`** (`application-supabase.properties`): se activa solo si `DB_URL` apunta a Supabase; con la base local no cambia nada. Ajusta el pool y el driver para una base remota (conexiones abiertas, comprobación periódica, plazos, nombre en el panel de Supabase). **No cambia ninguna respuesta de la aplicación** (comprobado, ver abajo).
- Se **descartaron** dos ajustes porque costaban funciones o robustez: reescribir los lotes de inserción y omitir la validación de conexiones por defecto.

## Cómo se midió
Banco de pruebas: una base PostGIS desechable con una **copia** de tus datos (15 rutas, etc.) detrás de un proxy que añade 55 ms por sentido (110 ms de ida y vuelta, como Villavicencio → Virginia), la aplicación real (jar) y peticiones de solo lectura. Cuenta las sentencias SQL reales leyendo el registro de PostgreSQL.
- `scripts/supabase/medir/proxy_latencia.py`: proxy con latencia fija.
- `scripts/supabase/medir/medir_api.py`: mide latencia (mediana y p95), sentencias por petición y rendimiento con 16 usuarios a la vez. Contra Supabase real: arranca la aplicación (`iniciar-supabase.ps1`) y ejecuta `python scripts/supabase/medir/medir_api.py http://127.0.0.1:8080 --pausa 1.5`.
- `scripts/supabase/informe.ps1`: incluye la **latencia real** de ida y vuelta a tu base (mediana de 15 consultas): menos de 10 ms = servidor cerca; más de 80 ms = lejos.

Sentencias SQL por petición (medidas): `/api/estado` 2, `/api/rutas` 1, `/api/rutas/nombres` 3, `/api/rutas/cercanas` 4, `/api/paraderos` 1, `/api/viajes/planificar` ≈ 20. La caché de planes (90 s) y la de rejillas de muestras (con su «huella» de validez) ya evitan repetir trabajo; no se tocaron.

## Lo que hace el perfil `supabase`
| Ajuste | Valor | Para qué |
|---|---|---|
| `maximum-pool-size` | 10 (`DB_POOL_MAX`) | Misma capacidad de atender usuarios a la vez que antes. |
| `minimum-idle` | 5 (`DB_POOL_MIN`) | Antes se mantenían SIEMPRE abiertas 10 conexiones por copia de la aplicación; el Session pooler de Supabase da pocas conexiones al servidor por proyecto, así que desarrollo + producción juntas podían agotarlas. Ahora 5 quedan abiertas y las demás se abren al necesitarlas y se cierran tras 5 min sin uso. |
| `keepalive-time` | 4 min | Comprueba las conexiones ociosas: ni el pooler ni un cortafuegos las cierran callados; la primera petición tras un rato sin uso no se topa con una conexión muerta. |
| `max-lifetime` | 25 min | Renueva conexiones antes de cualquier límite del servidor. |
| `validation-timeout`, `connection-timeout` | 3 s, 10 s | Plazos acotados: una red caída no deja peticiones colgadas. |
| `ApplicationName` | `SEVB` | Las conexiones se ven con nombre en el panel de Supabase (`pg_stat_activity`). |
| `tcpKeepAlive`, `connectTimeout`, `loginTimeout` | sí, 10 s, 15 s | Detecta conexiones TCP muertas y evita arranques colgados por red caída. |

Compromiso medido del `minimum-idle`: en una ráfaga en frío de 16 usuarios a 110 ms, con 10 conexiones listas se atienden 22,8 peticiones/s; con 4, 17,9; con 2, 14,6 (abrir cada conexión cuesta ≈ 0,7 s a esa distancia y el pool las abre de una en una). En un servidor cercano a Supabase abrirlas es mucho más rápido. Si prefieres máxima respuesta en ráfagas sobre ahorrar conexiones: `DB_POOL_MIN=10`.

## Comprobación de que nada cambia
Las mismas 10 peticiones (rutas, nombres, paraderos, rutas cercanas, dos planificaciones de viaje, puentes, configuración de cuenta y de mapa) contra la misma base, **sin** el perfil y **con** él, dieron respuestas **idénticas byte a byte** (incluida la planificación, de 60 KB). Con el perfil las conexiones aparecen como `SEVB` y quedan 5 en reposo (antes 10 como «PostgreSQL JDBC Driver»). La detección de Supabase por la URL y sus límites tienen pruebas propias (`BaseRemotaSupabaseTest`).

## Lo que se probó y NO se dejó activado
- **`reWriteBatchedInserts` (lotes de inserción reescritos):** la importación usa `INSERT … ON CONFLICT DO UPDATE`; reescrito en una sola sentencia, una fila repetida dentro del mismo lote (puede pasar con los lugares de OpenStreetMap) hace fallar la importación entera con «cannot affect row a second time». Además los lotes ya viajan en un solo bloque de red, así que la ganancia era mínima. No se activó.
- **Omitir la validación de conexiones (`DB_VALIDACION_OMITIR_MS`):** HikariCP valida con una consulta vacía cada conexión usada hace más de 0,5 s; con pausas de una persona real eso es **un viaje extra por petición** (a 110 ms: `/api/estado` 390 ms frente a 271 ms sin validar; `/api/rutas` 269 frente a 148; `/api/rutas/cercanas` 524 frente a 405). Pero si el servidor corta las conexiones, sin validar fallan **tantas peticiones como conexiones muertas haya** (medido: 2 peticiones seguidas con 503 hasta 3 s después del corte; con la validación normal solo falla una y solo si llega en los primeros milisegundos). En un servidor cercano a Supabase la ganancia sería de 1 a 2 ms. Por eso queda **apagado por defecto** y solo se activa si lo pides (`DB_VALIDACION_OMITIR_MS=5000`), recomendable únicamente si la aplicación corre lejos de Supabase.

## Recomendaciones de despliegue
1. **Pon la aplicación en East US (North Virginia)**, la misma región que el proyecto de Supabase. Es lo que más reduce la latencia de cada petición.
2. Usa el **Session pooler** (puerto 5432); nunca el Transaction pooler (rompe las consultas preparadas). Si el servidor tiene IPv6 también sirve la conexión directa (`db.<id>.supabase.co`), que evita un salto.
3. No levantes más copias de la aplicación de las necesarias: cada una puede usar hasta 10 conexiones (`DB_POOL_MAX`). Revisa el cupo de tu plan en Supabase.
4. El control de salud de Docker (`/api/estado` cada 30 s) mantiene además una conexión caliente.
5. Tras desplegar, corre `informe.ps1` desde el servidor: la latencia mediana debería ser de unos pocos ms.

## No verificado
Los números de latencia son de un **proxy simulado** (110 ms), no de tu Supabase real; la distancia real se mide con `informe.ps1`. El cupo exacto de conexiones del Session pooler de tu plan no se consultó. El perfil `supabase` se probó con la base local de pruebas y su activación automática por URL, con pruebas unitarias; arrancando contra tu Supabase real se verá en el registro (`Pool SEVB-Supabase`) y en `pg_stat_activity` (`SEVB`).
