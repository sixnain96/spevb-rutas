-- SEVB · migración Flyway. Idempotente a propósito: sobre una base creada antes por schema.sql no cambia ni borra datos.
-- Pasos peatonales propios (PASO y BLOQUEO).

-- Pasos peatonales propios del equipo: corrigen la red de calles de OpenStreetMap.
--   PASO    = camino que existe en la realidad y falta en OSM (atajo, puente, sendero, andén).
--   BLOQUEO = tramo de OSM que en la realidad no se puede recorrer a pie (cerrado, sin paso, canal).
CREATE TABLE IF NOT EXISTS pasos_peatonales (
    id BIGSERIAL PRIMARY KEY,
    tipo VARCHAR(10) NOT NULL CHECK (tipo IN ('PASO', 'BLOQUEO')),
    nombre VARCHAR(120) NOT NULL,
    nota VARCHAR(300),
    trazo geometry(LineString, 4326) NOT NULL,
    creado_en TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_pasos_trazo_gist ON pasos_peatonales USING GIST (trazo);
