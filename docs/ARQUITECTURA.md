# Arquitectura y modelo de comunicación · SEVB

## En una frase

SEVB es una **aplicación cliente-servidor clásica**: un **monolito modular de Spring Boot** (Java 21) que expone una **API REST síncrona** (petición → respuesta, JSON sobre HTTP) y una **aplicación web estática** (JavaScript puro + Leaflet, instalable como PWA) que vive en el navegador. La base de datos es **PostgreSQL con PostGIS**.

> **No usa Pub/Sub, ni colas, ni Kafka/Quix, ni RabbitMQ, ni WebSocket, ni Server-Sent Events.** Se comprobó en el código y en el `pom.xml`: no hay ninguna dependencia ni clase de mensajería. Toda la comunicación entre el navegador y el servidor es petición/respuesta.

## Diagrama

```
┌───────────────────────────── Teléfono / navegador ─────────────────────────────┐
│  Página estática (HTML + CSS + JS puro) · capa de mapa → Leaflet|Google     │
│                                                                                  │
│  Módulos: planner · app (explorador) · bus · viaje (modo viaje) · gps · sheet   │
│        ▲ ▼  «bus de eventos» local: CustomEvent en window (Pub/Sub EN MEMORIA)   │
│                                                                                  │
│  Modo viaje: GPS del teléfono → filtro → máquina de fases  (todo local)          │
│  Service Worker (PWA): caché de la app, de las rutas y de los mapas              │
│  localStorage / Cache Storage: historial, favoritos, viaje en curso, ajustes     │
└──────────────────────────────────┬───────────────────────────────────────────────┘
                                   │  HTTP/HTTPS · REST · JSON (gzip) · síncrono
                                   ▼
┌──────────────────────── Servidor: Spring Boot 4 (un solo proceso) ──────────────┐
│  Filtros: límite de solicitudes (429) · seguridad del editor · log privado       │
│  controller (10)  →  service (22)  →  repository (5, JdbcTemplate)               │
│   58 endpoints        reglas: viajes, transbordos, caminata,    consultas SQL    │
│                       red peatonal, red vial de buses, lugares                    │
│  Concurrencia interna: CompletableFuture + hilos virtuales · cachés en memoria   │
│  Tareas de arranque: @EventListener(ApplicationReadyEvent) (carga de redes)      │
└───────────────┬───────────────────────────────┬──────────────────────────────────┘
                │ JDBC (pool Hikari, 10)        │ HTTPS (java.net.http.HttpClient)
                ▼                               ▼
   PostgreSQL 17 + PostGIS 3.5         Servicios externos (opcionales)
   (Flyway: migraciones V1–V10)        Nominatim / ArcGIS (direcciones), Overpass/OSM
   archivos locales data/*.bin.gz      (importaciones), Google (inicio de sesión)
   (red peatonal, red vial de buses)
```

## Capas del servidor

| Capa | Paquete | Qué hace |
|---|---|---|
| Controladores | `controller` (10 clases, 58 endpoints) | Reciben HTTP, validan y devuelven JSON. No tienen reglas de negocio. |
| Servicios | `service` (22) y `vial` (8) | Reglas: planificar viajes, transbordos, caminata por calles, puntos de subida óptimos, direcciones, red de buses. |
| Repositorios | `repository` (5) | SQL con `JdbcTemplate` y funciones PostGIS (`ST_LineLocatePoint`, `ST_LineSubstring`…). **No usa JPA/ORM.** |
| DTO | `dto` (16) | Contratos de entrada y salida. |
| Configuración | `config` | Filtros, límites, caché web, área de la ciudad. |
| Datos | Flyway `V1`–`V10` | Las tablas se crean y actualizan por migraciones versionadas. |

## Modelo de comunicación, canal por canal

| Entre… | Cómo | Estilo |
|---|---|---|
| Navegador ↔ servidor | HTTP/HTTPS, REST, JSON comprimido (gzip) | **Síncrono, petición/respuesta** |
| Servidor ↔ base de datos | JDBC con pool Hikari (10 conexiones) | Síncrono |
| Servidor ↔ servicios externos | HTTPS con `HttpClient`, tiempos de espera cortos | Síncrono, con caché y deduplicación de llamadas en vuelo |
| Dentro del servidor | Llamadas directas entre clases; `CompletableFuture` con **hilos virtuales** para calcular tramos a pie en paralelo; `ApplicationReadyEvent` para tareas de arranque | En un solo proceso, sin broker |
| Dentro del navegador | **`CustomEvent` sobre `window`** (21 eventos, p. ej. `spevb-seleccion`, `spevb-results`, `spevb-viaje-modo`) | **Pub/Sub local en memoria**: desacopla los módulos de la página; no sale por la red |
| Modo viaje | GPS del teléfono → filtro → máquina de fases, todo **en el teléfono** | La posición **no se envía al servidor** |
| Sin conexión | Service Worker: la app y las rutas se sirven desde caché | Red primero con respaldo en caché |

### Por qué no hay colas ni Pub/Sub

El servidor hace trabajo **corto y por demanda** (calcular un viaje tarda del orden de 0,25 s) y no mantiene conversaciones con el cliente. No hay tareas largas que deban salir del ciclo de la petición ni varios servicios que necesiten enterarse unos de otros. Agregar un broker (Kafka, RabbitMQ, Pub/Sub) añadiría un componente más que operar, sin resolver ningún problema que hoy exista.

### Cuándo sí tendría sentido (futuro)

| Necesidad nueva | Qué se agregaría |
|---|---|
| Ver **buses reales moviéndose** o recibir avisos «tu bus llega en 3 min» | Posiciones de buses enviadas por un canal de tiempo real (SSE o WebSocket) y, si hay muchos buses, un broker o un stream para recibirlas |
| Notificaciones push con la app cerrada | Servicio de notificaciones (Web Push / FCM) |
| Varias instancias del servidor | Caché compartida (por ejemplo Redis) en lugar de las cachés en memoria, y un balanceador |
| Importaciones muy largas (OSM, catastro) como tarea de fondo con progreso | Una cola de trabajos |

## Despliegue

* **Desarrollo:** PostGIS en Docker (`docker-compose.yml`) y la app con `start.ps1` (Maven) o IntelliJ.
* **Producción:** `compose.prod.yml` con la app, PostGIS y **Caddy** (proxy inverso con HTTPS automático). Imagen de la app: Eclipse Temurin 21 (JRE).
* **Estado:** el servidor guarda estado solo en la base de datos, en `data/` (redes de calles) y en cachés en memoria que se pueden reconstruir; por eso se puede reiniciar sin perder información.

## Seguridad y protección

* `LimiteGeneralFilter`: límite de solicitudes (responde 429).
* `EditorSecurityFilter`: el editor y la administración requieren activación explícita y token.
* `LogPrivado`: los registros no escriben datos personales.
* Las claves van en `.env` (nunca en el paquete de entrega).

## Eventos del navegador (pub/sub local)

`spevb-seleccion` (opción elegida), `spevb-results` (resultados listos), `spevb-route-change` (el explorador cambia de ruta), `spevb-viaje` (viaje calculado, para el historial), `spevb-viaje-modo` (modo viaje activo/inactivo), `spevb-ver-opciones`, `spevb-picking`, `spevb-point-selected`, `spevb-point-cleared`, `spevb-focus-map`, `spevb-follow`, `spevb-ajustes`, `spevb-recientes`, `spevb-datos-guardados`, `spevb-resultados-interaccion`, `spevb-planner-listo`, `spevb-sin-red` y los del editor (`spevb-editor-*`).
