-- IMPORTANTE: estas rutas son demostrativas. La aplicación está preparada
-- para almacenar geometrías densas que sigan exactamente las calles, pero una
-- geometría sólo puede considerarse oficial después de validarla con la fuente competente.

INSERT INTO rutas (codigo, nombre, origen, destino, descripcion, color_hex, demostrativa, sentido, fuente, validada, recorrido)
VALUES (
    'R001', 'Porfía - Centro (DEMO)', 'Ciudad Porfía', 'Centro de Villavicencio',
    'Geometría demostrativa para validar la plataforma. Debe sustituirse por un trazado validado.',
    '#1565C0', TRUE, 'IDA', 'Datos demostrativos SPEVB', FALSE,
    ST_GeomFromText('LINESTRING(-73.6770 4.0920,-73.6705 4.0985,-73.6625 4.1050,-73.6550 4.1110,-73.6480 4.1180,-73.6410 4.1260,-73.6340 4.1340,-73.6280 4.1400,-73.6240 4.1450)', 4326)
) ON CONFLICT (codigo) DO NOTHING;

INSERT INTO rutas (codigo, nombre, origen, destino, descripcion, color_hex, demostrativa, sentido, fuente, validada, recorrido)
VALUES (
    'R002', 'Galán - Centro (DEMO)', 'Sector Galán', 'Centro de Villavicencio',
    'Geometría demostrativa. Sustituir por el trazado validado.', '#2E7D32', TRUE, 'IDA',
    'Datos demostrativos SPEVB', FALSE,
    ST_GeomFromText('LINESTRING(-73.6540 4.1450,-73.6490 4.1440,-73.6440 4.1430,-73.6390 4.1425,-73.6340 4.1430,-73.6290 4.1440,-73.6240 4.1450)', 4326)
) ON CONFLICT (codigo) DO NOTHING;

INSERT INTO rutas (codigo, nombre, origen, destino, descripcion, color_hex, demostrativa, sentido, fuente, validada, recorrido)
VALUES (
    'R003', 'Catama - Centro (DEMO)', 'Sector Catama', 'Centro de Villavicencio',
    'Geometría demostrativa. Sustituir por el trazado validado.', '#EF6C00', TRUE, 'IDA',
    'Datos demostrativos SPEVB', FALSE,
    ST_GeomFromText('LINESTRING(-73.5740 4.1490,-73.5820 4.1485,-73.5900 4.1478,-73.5980 4.1470,-73.6070 4.1465,-73.6160 4.1460,-73.6240 4.1450)', 4326)
) ON CONFLICT (codigo) DO NOTHING;

INSERT INTO rutas (codigo, nombre, origen, destino, descripcion, color_hex, demostrativa, sentido, fuente, validada, recorrido)
VALUES (
    'R004', 'Álamos - Centro (DEMO)', 'Sector Álamos', 'Centro de Villavicencio',
    'Geometría demostrativa. Sustituir por el trazado validado.', '#7B1FA2', TRUE, 'IDA',
    'Datos demostrativos SPEVB', FALSE,
    ST_GeomFromText('LINESTRING(-73.6120 4.1810,-73.6140 4.1750,-73.6160 4.1690,-73.6180 4.1620,-73.6200 4.1560,-73.6220 4.1500,-73.6240 4.1450)', 4326)
) ON CONFLICT (codigo) DO NOTHING;

-- Paraderos DEMO únicamente para comprobar el renderizado del módulo.
INSERT INTO paraderos (ruta_id, secuencia, nombre, ubicacion)
SELECT id, 1, 'Paradero demo 1', ST_SetSRID(ST_MakePoint(-73.6500, 4.1432), 4326)
FROM rutas WHERE codigo='R002'
ON CONFLICT (ruta_id, secuencia) DO NOTHING;

INSERT INTO paraderos (ruta_id, secuencia, nombre, ubicacion)
SELECT id, 2, 'Paradero demo 2', ST_SetSRID(ST_MakePoint(-73.6380, 4.1426), 4326)
FROM rutas WHERE codigo='R002'
ON CONFLICT (ruta_id, secuencia) DO NOTHING;


-- Corrección de nombre para bases creadas con versiones anteriores.
UPDATE rutas
SET nombre = REPLACE(nombre, 'Sálamos', 'Álamos'),
    origen = REPLACE(origen, 'Sálamos', 'Álamos'),
    actualizado_en = NOW()
WHERE codigo = 'R004' AND (nombre ILIKE '%Sálamos%' OR origen ILIKE '%Sálamos%');
