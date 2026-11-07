-- SEVB · migración Flyway. Idempotente a propósito: sobre una base creada antes por schema.sql no cambia ni borra datos.
-- Tipo PUENTE en pasos_peatonales.

--   PUENTE  = puente peatonal (se puede cruzar a pie; se dibuja en el mapa y la red de calles lo usa como un paso).
ALTER TABLE pasos_peatonales DROP CONSTRAINT IF EXISTS pasos_peatonales_tipo_check;
ALTER TABLE pasos_peatonales ADD CONSTRAINT pasos_peatonales_tipo_check CHECK (tipo IN ('PASO', 'BLOQUEO', 'PUENTE'));
