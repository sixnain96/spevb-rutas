-- SEVB · migración Flyway. Idempotente a propósito: sobre una base creada antes por schema.sql no cambia ni borra datos.
-- Paraderos de bus del sistema, independientes de las rutas.

-- Paraderos de bus del sistema (zonas de parada oficiales). Son INDEPENDIENTES de las rutas: se ponen en el mapa sin crear ninguna ruta,
-- y el planificador los usa para cualquier ruta que pase a menos de 25 m (ver ViajeRepository).
CREATE TABLE IF NOT EXISTS paraderos_buses (
    id BIGSERIAL PRIMARY KEY,
    nombre VARCHAR(160) NOT NULL,
    nota VARCHAR(300),
    ubicacion geometry(Point, 4326) NOT NULL,
    activo BOOLEAN NOT NULL DEFAULT TRUE,
    creado_en TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    actualizado_en TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_paraderos_buses_gist ON paraderos_buses USING GIST (ubicacion);
