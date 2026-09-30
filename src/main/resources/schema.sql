CREATE EXTENSION IF NOT EXISTS postgis;

CREATE TABLE IF NOT EXISTS rutas (
    id BIGSERIAL PRIMARY KEY,
    codigo VARCHAR(20) NOT NULL UNIQUE,
    nombre VARCHAR(120) NOT NULL,
    origen VARCHAR(160) NOT NULL,
    destino VARCHAR(160) NOT NULL,
    descripcion TEXT,
    color_hex VARCHAR(7) NOT NULL DEFAULT '#1565C0',
    activa BOOLEAN NOT NULL DEFAULT TRUE,
    demostrativa BOOLEAN NOT NULL DEFAULT TRUE,
    recorrido geometry(LineString, 4326) NOT NULL,
    creado_en TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    actualizado_en TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

ALTER TABLE rutas ADD COLUMN IF NOT EXISTS sentido VARCHAR(20) NOT NULL DEFAULT 'IDA';
ALTER TABLE rutas ADD COLUMN IF NOT EXISTS fuente VARCHAR(300);
ALTER TABLE rutas ADD COLUMN IF NOT EXISTS validada BOOLEAN NOT NULL DEFAULT FALSE;

CREATE INDEX IF NOT EXISTS idx_rutas_recorrido_gist
    ON rutas USING GIST (recorrido);

CREATE TABLE IF NOT EXISTS paraderos (
    id BIGSERIAL PRIMARY KEY,
    ruta_id BIGINT NOT NULL REFERENCES rutas(id) ON DELETE CASCADE,
    secuencia INTEGER NOT NULL,
    nombre VARCHAR(160) NOT NULL,
    ubicacion geometry(Point, 4326) NOT NULL,
    UNIQUE (ruta_id, secuencia)
);

CREATE INDEX IF NOT EXISTS idx_paraderos_ubicacion_gist
    ON paraderos USING GIST (ubicacion);
