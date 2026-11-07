-- SEVB 4.10 · Control de concurrencia optimista para pasos peatonales y puentes editados (PUT). Conserva el id y los datos existentes.
ALTER TABLE pasos_peatonales ADD COLUMN IF NOT EXISTS version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE pasos_peatonales ADD COLUMN IF NOT EXISTS actualizado_en TIMESTAMPTZ NOT NULL DEFAULT NOW();
