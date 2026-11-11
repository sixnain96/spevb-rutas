# SEVB · Sistema de Enrutamiento y Visualización de Buses

Aplicación web para consultar y planificar viajes en **bus urbano en Villavicencio (Meta, Colombia)**. Proyecto académico de la Universidad Santo Tomás (USTA).

Indica de dónde sales y a dónde vas, y SEVB te propone qué ruta tomar, dónde subirte, dónde bajarte y cuánto caminar. Con **«Iniciar viaje»** te acompaña con el GPS del celular hasta tu destino.

## Qué hace

- **Planificador de viaje:** compara tu origen y destino con las rutas registradas, incluidos transbordos, y muestra el camino a pie hasta el bus y desde el bus hasta tu destino.
- **Modo viaje:** sigue tu avance, avisa antes de la parada de bajada y puede reanudarse si recargas la página.
- **Mapa:** Google Maps o Leaflet/OpenStreetMap como fondo, con un respaldo si uno falla.
- **Búsqueda de direcciones** con Google (configurable).
- **Editor de rutas** para el equipo, protegido con una clave y desactivado por defecto.
- **Pensado para el celular:** menús y paneles que no se estorban, textos grandes, uso con una mano.

## Tecnología

Java 21 · Spring Boot · PostgreSQL + PostGIS · Flyway · JavaScript sin framework en el navegador · Playwright para las pruebas de navegador.

## Cómo ejecutarlo en tu equipo

Necesitas Java 21, Maven y una base PostgreSQL con PostGIS (la forma más simple es Docker).

1. Copia `.env.example` como `.env` y define **tus propios valores** (contraseña de la base, clave del editor, claves de mapas si las usas). El archivo `.env` está ignorado por Git y **nunca debe subirse**.
2. Levanta la base y la aplicación:

```powershell
docker compose up -d
.\start.ps1 -NoDatabase
```

3. Abre `http://localhost:8080`.

Las variables disponibles están en [docs/CONFIGURACION.md](docs/CONFIGURACION.md).

## Pruebas

```bash
mvn clean verify
python e2e/run_e2e.py
```

Las pruebas de navegador usan un servidor simulado y no necesitan claves reales. Detalles en [docs/PRUEBAS.md](docs/PRUEBAS.md).

## Documentación

| Tema | Documento |
|---|---|
| Modo viaje (GPS) | [docs/MODO-VIAJE.md](docs/MODO-VIAJE.md) |
| Continuidad del viaje y límites de una web en el celular | [docs/CONTINUIDAD-VIAJE.md](docs/CONTINUIDAD-VIAJE.md) |
| UX móvil | [docs/UX-MOVIL-4.14.md](docs/UX-MOVIL-4.14.md) |
| Mapa de Google | [docs/GOOGLE-MAPS.md](docs/GOOGLE-MAPS.md) |
| Editor de rutas | [docs/EDITOR.md](docs/EDITOR.md) |
| Configuración | [docs/CONFIGURACION.md](docs/CONFIGURACION.md) |
| Base de datos y migraciones | [docs/MIGRACIONES.md](docs/MIGRACIONES.md) |
| Datos personales y privacidad | [docs/DATOS-PERSONALES.md](docs/DATOS-PERSONALES.md) |
| README técnico completo (histórico) | [docs/README-DETALLADO.md](docs/README-DETALLADO.md) |
| Cambios por versión | [CHANGELOG.md](CHANGELOG.md) |

## Aviso sobre los datos

Los recorridos pueden representar conocimiento de campo del equipo y **no son información oficial** de ninguna autoridad u operador de transporte. Los datos de mapas y direcciones pertenecen a sus proveedores (Google, OpenStreetMap, catastro municipal) y se usan según sus condiciones.

## Licencia

Proyecto académico. Consulta [LICENSE](LICENSE) para las condiciones vigentes y los componentes de terceros.
