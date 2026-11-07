-- SEVB 4.14 · Nombre popular de la ruta: como la conoce la gente en Villavicencio («Catama», «Kirpas», «la 876»), el mismo que va en el cartel del bus.
-- Se muestra en lugar del código (R001, R08…) al decir «súbete a la ruta Catama». Opcional: si está vacío se sigue usando el código.
-- Idempotente a propósito, igual que V1–V10.
ALTER TABLE rutas ADD COLUMN IF NOT EXISTS nombre_popular VARCHAR(60);
