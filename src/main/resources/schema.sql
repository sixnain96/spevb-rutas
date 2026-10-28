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

-- Cuentas de usuario: la clave se guarda con PBKDF2 (nunca en claro) y la sesión con el hash de un token aleatorio.
CREATE TABLE IF NOT EXISTS usuarios (
    id BIGSERIAL PRIMARY KEY,
    correo VARCHAR(120) NOT NULL,
    nombre VARCHAR(60) NOT NULL,
    clave_hash VARCHAR(200) NOT NULL,
    creado_en TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    ultimo_acceso TIMESTAMPTZ
);
CREATE UNIQUE INDEX IF NOT EXISTS ux_usuarios_correo ON usuarios (LOWER(correo));

CREATE TABLE IF NOT EXISTS sesiones (
    id BIGSERIAL PRIMARY KEY,
    usuario_id BIGINT NOT NULL REFERENCES usuarios (id) ON DELETE CASCADE,
    token_hash CHAR(64) NOT NULL UNIQUE,
    agente VARCHAR(160),
    creada_en TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    vista_en TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    expira_en TIMESTAMPTZ NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_sesiones_usuario ON sesiones (usuario_id);

-- Datos de cada persona que se sincronizan entre sus dispositivos: lugares, rutas favoritas, viajes guardados e historial.
CREATE TABLE IF NOT EXISTS usuario_datos (
    usuario_id BIGINT NOT NULL REFERENCES usuarios (id) ON DELETE CASCADE,
    clave VARCHAR(20) NOT NULL,
    valor JSONB NOT NULL,
    actualizado_en TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (usuario_id, clave)
);

-- Inicio de sesión con Google: la cuenta puede no tener contraseña y se identifica por el «sub» que entrega Google.
ALTER TABLE usuarios ALTER COLUMN clave_hash DROP NOT NULL;
ALTER TABLE usuarios ADD COLUMN IF NOT EXISTS google_sub VARCHAR(40);
ALTER TABLE usuarios ADD COLUMN IF NOT EXISTS correo_verificado BOOLEAN NOT NULL DEFAULT FALSE;
CREATE UNIQUE INDEX IF NOT EXISTS ux_usuarios_google ON usuarios (google_sub) WHERE google_sub IS NOT NULL;

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
