-- SEVB · migración Flyway. Idempotente a propósito: sobre una base creada antes por schema.sql no cambia ni borra datos.
-- Base propia de direcciones, barrios, confirmaciones, búsquedas sin resultado y lugares con nombre (POI).

-- ============================================================================
-- Base propia de direcciones de Villavicencio (V3.6)
-- Se alimenta del catastro municipal y de las confirmaciones de los usuarios.
-- Las direcciones del catastro vienen truncadas a 32 caracteres: el barrio se compara por prefijo.
-- ============================================================================
CREATE EXTENSION IF NOT EXISTS pg_trgm;

CREATE TABLE IF NOT EXISTS direcciones (
    id BIGSERIAL PRIMARY KEY,
    fuente VARCHAR(20) NOT NULL,
    fuente_id VARCHAR(64) NOT NULL,
    texto VARCHAR(220) NOT NULL,
    norm VARCHAR(220) NOT NULL,
    tipo_via VARCHAR(3) NOT NULL DEFAULT '',
    via VARCHAR(14) NOT NULL DEFAULT '',
    cuadrante VARCHAR(1) NOT NULL DEFAULT '',
    cruce VARCHAR(14) NOT NULL DEFAULT '',
    placa INTEGER NOT NULL DEFAULT 0,
    placa2 INTEGER NOT NULL DEFAULT 0,
    manzana VARCHAR(14) NOT NULL DEFAULT '',
    casa VARCHAR(14) NOT NULL DEFAULT '',
    barrio VARCHAR(80) NOT NULL DEFAULT '',
    ubicacion geometry(Point, 4326) NOT NULL,
    precision VARCHAR(30) NOT NULL DEFAULT 'PREDIO_APROXIMADO',
    confianza SMALLINT NOT NULL DEFAULT 70,
    popularidad INTEGER NOT NULL DEFAULT 0,
    actualizado_en TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (fuente, fuente_id)
);
CREATE INDEX IF NOT EXISTS idx_dir_clave ON direcciones (tipo_via, via, cruce, placa);
CREATE INDEX IF NOT EXISTS idx_dir_placa2 ON direcciones (tipo_via, via, cruce, placa2) WHERE placa2 > 0;
CREATE INDEX IF NOT EXISTS idx_dir_manzana ON direcciones (manzana, casa) WHERE manzana <> '';
CREATE INDEX IF NOT EXISTS idx_dir_norm_trgm ON direcciones USING GIN (norm gin_trgm_ops);
CREATE INDEX IF NOT EXISTS idx_dir_geom ON direcciones USING GIST (ubicacion);

CREATE TABLE IF NOT EXISTS barrios (
    nombre VARCHAR(80) PRIMARY KEY,
    predios INTEGER NOT NULL,
    ubicacion geometry(Point, 4326) NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_barrios_trgm ON barrios USING GIN (nombre gin_trgm_ops);

-- Cada vez que una persona escribe una dirección y luego fija el punto en el mapa, se guarda la pareja
-- (clave canónica → punto). Con 3 o más confirmaciones coherentes (≈ 50 m) el punto se usa en las búsquedas.
CREATE TABLE IF NOT EXISTS direccion_confirmaciones (
    id BIGSERIAL PRIMARY KEY,
    clave VARCHAR(80) NOT NULL,
    ubicacion geometry(Point, 4326) NOT NULL,
    creado_en TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_dir_conf_clave ON direccion_confirmaciones (clave);

-- Búsquedas sin resultado: lista de pendientes para completar la base (sin datos personales).
CREATE TABLE IF NOT EXISTS busquedas_sin_resultado (
    consulta VARCHAR(120) PRIMARY KEY,
    veces INTEGER NOT NULL DEFAULT 1,
    ultima TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS direcciones_importaciones (
    id BIGSERIAL PRIMARY KEY,
    fuente VARCHAR(20) NOT NULL,
    inicio TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    fin TIMESTAMPTZ,
    leidos INTEGER NOT NULL DEFAULT 0,
    guardados INTEGER NOT NULL DEFAULT 0,
    descartados INTEGER NOT NULL DEFAULT 0,
    estado VARCHAR(20) NOT NULL DEFAULT 'EN_CURSO',
    mensaje VARCHAR(300)
);

-- Lugares con nombre (OpenStreetMap + correcciones del equipo): colegios, parques, comercios, universidades, barrios…
CREATE TABLE IF NOT EXISTS pois (
    id BIGSERIAL PRIMARY KEY,
    fuente VARCHAR(10) NOT NULL,
    fuente_id VARCHAR(30) NOT NULL,
    nombre VARCHAR(160) NOT NULL,
    norm VARCHAR(160) NOT NULL,
    categoria VARCHAR(40) NOT NULL DEFAULT '',
    direccion VARCHAR(200) NOT NULL DEFAULT '',
    ubicacion geometry(Point, 4326) NOT NULL,
    relevancia SMALLINT NOT NULL DEFAULT 50,
    popularidad INTEGER NOT NULL DEFAULT 0,
    actualizado_en TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (fuente, fuente_id)
);
CREATE INDEX IF NOT EXISTS idx_pois_norm_trgm ON pois USING GIN (norm gin_trgm_ops);
CREATE INDEX IF NOT EXISTS idx_pois_geom ON pois USING GIST (ubicacion);
