-- SEVB · migración Flyway. Idempotente a propósito: sobre una base creada antes por schema.sql no cambia ni borra datos.
-- Reportes de la gente.

-- Reportes de la gente: una ruta o caminata mal trazada, una dirección equivocada, una sugerencia…
CREATE TABLE IF NOT EXISTS reportes (
    id BIGSERIAL PRIMARY KEY,
    categoria VARCHAR(24) NOT NULL,
    mensaje VARCHAR(500) NOT NULL,
    ruta_codigo VARCHAR(40),
    origen_lat DOUBLE PRECISION, origen_lng DOUBLE PRECISION,
    destino_lat DOUBLE PRECISION, destino_lng DOUBLE PRECISION,
    contacto VARCHAR(120),
    agente VARCHAR(160),
    estado VARCHAR(12) NOT NULL DEFAULT 'NUEVO' CHECK (estado IN ('NUEVO', 'REVISADO', 'RESUELTO', 'DESCARTADO')),
    creado_en TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_reportes_estado ON reportes (estado, creado_en DESC);
