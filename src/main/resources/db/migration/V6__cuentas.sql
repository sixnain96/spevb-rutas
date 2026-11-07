-- SEVB · migración Flyway. Idempotente a propósito: sobre una base creada antes por schema.sql no cambia ni borra datos.
-- Cuentas, sesiones y datos sincronizados por persona.

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
