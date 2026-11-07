# Google Maps en SEVB (opcional) · direcciones

SEVB sigue usando su **mapa** (OpenFreeMap) y sus **rutas, transbordos y caminata** propias. Google se suma **solo para buscar direcciones** y como enlace. Todo es opcional y está **apagado por defecto**.

## Qué hay

| Pieza | Qué hace | Necesita clave |
|---|---|---|
| Sugerencias y ubicación con Google (`GoogleDireccionesService`) | Al escribir una dirección salen también sugerencias de Google («Google Maps»); al elegir una, se ubica el punto (Places API «New»: autocompletar + detalle) | Sí, `GOOGLE_MAPS_API_KEY` |
| Botón **Google Maps** (en las herramientas del viaje) | Abre el mismo viaje A → B en Google Maps (modo transporte público) para compararlo o ver la calle | No (es un enlace público) |

Sugerencias: con clave, la lista (máximo 8) reserva cupo para cada fuente: base propia ≤ 3, **Google ≤ 3**, ArcGIS ≤ 2; si una fuente trae menos, las demás ocupan su cupo. Con **Enter o «Buscar»** también se usa la búsqueda por texto de Google (hasta 5 lugares). El autocompletar de Google no se cobra; el detalle del lugar elegido y la búsqueda por texto sí cuentan contra la cuota.

## Cómo activarlo (cuando tengas la clave)

1. En Google Cloud crea un proyecto, **activa la facturación** (pide tarjeta) y habilita **Places API (New)**.
2. Crea una clave de API y **restríngela**: solo «Places API (New)» y, si puedes, a la dirección IP de tu servidor.
3. En **Presupuestos y alertas** pon un tope (por ejemplo, 5 USD) con alertas por correo.
4. En tu `.env` agrega `GOOGLE_MAPS_API_KEY=tu-clave` y reinicia la app. Sin la línea (o vacía) Google queda apagado.

La clave **solo la lee el servidor** (Java) y viaja en una cabecera: nunca llega al navegador ni aparece en la URL ni en los registros.

## Cuota gratis (a la fecha de este documento)

Desde marzo de 2025 Google cobra por tipo de consulta, con un tope gratis mensual por tipo: unas **10 000 consultas** en las de categoría Essentials (p. ej. geocodificación), **5 000** en Pro y **1 000** en Enterprise; el tope se reinicia cada mes. El autocompletar es gratis, pero la consulta del detalle del lugar elegido se cobra aparte. Confirma las cifras vigentes en <https://developers.google.com/maps/billing-and-pricing/pricing> antes de decidir.

## Lo que respeta el código y lo que debe confirmar el equipo

- **No se guarda nada de Google:** ni el texto ni las coordenadas que devuelve van a la base de datos. Lo elegido pasa al planificador y, si la persona lo guarda en «Mis lugares», se guarda solo su punto (lat/lng) con el nombre que ella escriba.
- **Cada resultado dice su origen** («Google Maps») y nunca se marca como «verificado».
- **Pendiente de confirmar (condiciones de Google):** hasta donde se sabe, Google exige mostrar los resultados de Places sobre **un mapa de Google** y mostrar su atribución («Powered by Google») cuando se usa el autocompletar sin mapa de Google. Aquí el punto se dibuja en el mapa propio, así que **antes de usar Google en producción** hay que revisar los términos de Google Maps Platform y decidir: (a) aceptar el riesgo en un entorno académico, (b) cambiar también el mapa a Google (cambio grande: reescribir la capa de mapa y se pierde el modo sin conexión), o (c) dejar Google apagado y usar solo el botón «Google Maps».
- **Sin la clave, nada cambia:** la app se comporta igual que antes.

## Pruebas

`GoogleDireccionesServiceTest` (servidor simulado: clave en cabecera y no en la URL, ids inválidos descartados, solo Villavicencio, fallos sin exponer la clave), `LugarServiceGoogleTest` (sugerencias y elección con prefijo `g:`, ArcGIS no se consulta, Google opcional) y `e2e/mejoras414_e2e.py` (botón «Google Maps» y etiqueta de origen en las sugerencias). **No se probó contra el servicio real de Google** (requiere clave y facturación).
