CREATE EXTENSION IF NOT EXISTS postgis;

CREATE TABLE IF NOT EXISTS rutas (
    id BIGSERIAL PRIMARY KEY,
    codigo VARCHAR(20) NOT NULL UNIQUE,
    nombre VARCHAR(120) NOT NULL,
    origen VARCHAR(160) NOT NULL,
    destino VARCHAR(160) NOT NULL,
    descripcion TEXT,
    color_hex VARCHAR(7) NOT NULL DEFAULT '#176650',
    activa BOOLEAN NOT NULL DEFAULT TRUE,
    demostrativa BOOLEAN NOT NULL DEFAULT TRUE,
    recorrido geometry(LineString, 4326) NOT NULL,
    creado_en TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    actualizado_en TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

ALTER TABLE rutas ADD COLUMN IF NOT EXISTS sentido VARCHAR(20) NOT NULL DEFAULT 'IDA';
ALTER TABLE rutas ADD COLUMN IF NOT EXISTS fuente VARCHAR(300);
ALTER TABLE rutas ADD COLUMN IF NOT EXISTS validada BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE rutas ADD COLUMN IF NOT EXISTS version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE rutas ADD COLUMN IF NOT EXISTS nota_validacion VARCHAR(500);

CREATE TABLE IF NOT EXISTS ruta_revisiones (
    id BIGSERIAL PRIMARY KEY,
    ruta_id BIGINT NOT NULL REFERENCES rutas(id) ON DELETE CASCADE,
    version BIGINT NOT NULL,
    datos JSONB NOT NULL,
    archivado_en TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (ruta_id, version)
);

CREATE INDEX IF NOT EXISTS idx_rutas_recorrido_gist ON rutas USING GIST (recorrido);
CREATE INDEX IF NOT EXISTS idx_rutas_recorrido_geography ON rutas USING GIST ((recorrido::geography));

CREATE TABLE IF NOT EXISTS paraderos (
    id BIGSERIAL PRIMARY KEY,
    ruta_id BIGINT NOT NULL REFERENCES rutas(id) ON DELETE CASCADE,
    secuencia INTEGER NOT NULL,
    nombre VARCHAR(160) NOT NULL,
    ubicacion geometry(Point, 4326) NOT NULL,
    UNIQUE (ruta_id, secuencia)
);

CREATE INDEX IF NOT EXISTS idx_paraderos_ubicacion_gist ON paraderos USING GIST (ubicacion);

-- Catálogo persistente de direcciones y lugares de Villavicencio.
-- Los resultados del geocodificador se guardan como no confirmados; los puntos
-- agregados desde la administración quedan confirmados por el equipo del proyecto.
CREATE TABLE IF NOT EXISTS lugares (
    id BIGSERIAL PRIMARY KEY,
    nombre VARCHAR(160) NOT NULL,
    direccion VARCHAR(240),
    ubicacion geometry(Point, 4326) NOT NULL,
    fuente VARCHAR(200),
    confirmado BOOLEAN NOT NULL DEFAULT FALSE,
    external_id VARCHAR(120) UNIQUE,
    creado_en TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    actualizado_en TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_lugares_ubicacion_gist ON lugares USING GIST (ubicacion);
CREATE INDEX IF NOT EXISTS idx_lugares_nombre_lower ON lugares ((LOWER(nombre)));
