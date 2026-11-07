-- SEVB 4.10 · Alias de códigos de ruta: al cambiar el código de una ruta, el anterior sigue resolviendo hacia la MISMA ruta (el id no cambia).
-- Idempotente a propósito, igual que V1–V8.
CREATE TABLE IF NOT EXISTS ruta_aliases (
    codigo VARCHAR(20) PRIMARY KEY,
    ruta_id BIGINT NOT NULL REFERENCES rutas (id) ON DELETE CASCADE,
    creado_en TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_ruta_aliases_ruta ON ruta_aliases (ruta_id);
