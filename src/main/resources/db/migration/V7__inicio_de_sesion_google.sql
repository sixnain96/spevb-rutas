-- SEVB · migración Flyway. Idempotente a propósito: sobre una base creada antes por schema.sql no cambia ni borra datos.
-- Cuentas sin contraseña identificadas por el «sub» de Google.

-- Inicio de sesión con Google: la cuenta puede no tener contraseña y se identifica por el «sub» que entrega Google.
ALTER TABLE usuarios ALTER COLUMN clave_hash DROP NOT NULL;
ALTER TABLE usuarios ADD COLUMN IF NOT EXISTS google_sub VARCHAR(40);
ALTER TABLE usuarios ADD COLUMN IF NOT EXISTS correo_verificado BOOLEAN NOT NULL DEFAULT FALSE;
CREATE UNIQUE INDEX IF NOT EXISTS ux_usuarios_google ON usuarios (google_sub) WHERE google_sub IS NOT NULL;
