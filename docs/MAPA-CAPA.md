# Capa de mapa de SEVB (contrato entre la aplicación y los proveedores)

Desde la 4.13 la aplicación **no usa `L.*` (Leaflet) ni `google.maps.*` fuera de los adaptadores**. Todo módulo habla con una capa pequeña
que implementan dos adaptadores: `js/mapa/adaptador-leaflet.js` (proveedor anterior, `MAP_PROVIDER=leaflet`) y
`js/mapa/adaptador-google.js` (`MAP_PROVIDER=google`). Solo se descarga el del proveedor elegido.

No es una imitación de Leaflet: expone **solo** lo que SEVB necesita. Lo que un proveedor no puede hacer se declara en
`map.capacidades` (por ejemplo `capasBase`, `mapaSinConexion`, `lineaAnimada`, `mapaSobrePanel`).

## Archivos

| Archivo | Papel |
|---|---|
| `js/mapa/geo.js` | `Spevb.Geo`: geometría pura (distancia, cajas, conversiones GeoJSON ⇄ `[lat,lng]` ⇄ Google, proyección sobre segmento). Sin SDK. |
| `js/mapa/nucleo.js` | `Spevb.Mapa`: lee `/api/config/mapa`, carga el adaptador, clasifica errores, estado recuperable, `Grupo`/`Elemento`/`Emisor` comunes. |
| `js/mapa/adaptador-*.js` | Implementación por proveedor. |
| `js/common.js` | `Spevb.fitPadding`, `Spevb.fitMap`, `Spevb.endpoint(kind)`, `Spevb.coberturaPanel(map)` y el re-encuadre al cambiar el tamaño. |

## Convenciones

* Un punto es **`[lat, lng]`**. GeoJSON y la API del servidor usan `[lng, lat]`: convierte **solo** con `Spevb.Geo.desdeGeoJSON(coords)` y
  `Spevb.Geo.aGeoJSON(puntos)` (no reordena, no redondea). `Spevb.Geo.aGoogle(p)` → `{lat, lng}`; `Spevb.Geo.deGoogle(g)` → `[lat, lng]`.
* Una **caja** es `{ s, w, n, e }` (`Geo.limites(puntos)`, `Geo.unir(a, b)`, `Geo.contiene(b, p)`, `Geo.esValida(b)`).
* Distancias: `Spevb.Geo.distancia(a, b)` (haversine, R = 6.371 km, igual que `L.LatLng.distanceTo`). Nunca `map.distance`.
* Los textos que vienen de datos (nombres de rutas, paraderos…) **siempre** como texto: `tooltip: Spevb.text(x)` o una cadena. Nunca dentro de `html`.
  `html` solo lo construye el código de SEVB.

## Arranque de una página

```js
Spevb.conMapa('map', (map) => { /* todo el código que antes iba justo después de createMap */ },
              { alFallar: (error) => status(error.message, 'error') });
```

`conMapa` crea el mapa **una vez**, muestra un estado recuperable con «Reintentar» si falla (sin recargar la página) y llama a
`iniciar(map)` exactamente una vez. Para que otros módulos esperen el mapa de la página se publica un hito:
`Spevb.publicar('explorador', { map, … })` y los demás usan `Spevb.esperar('explorador').then(...)` (igual con `'editor'`).
Los errores llevan `error.codigo`, `error.titulo`, `error.reintentable`, `error.detalle` (solo diagnóstico).

## Mapa (`MapaSEVB`)

```
map.proveedor            'leaflet' | 'google'
map.el                   elemento contenedor (en lugar de getContainer())
map.capacidades          { proveedor, mapaSinConexion, capasBase, lineaAnimada, modoNocheCss, mapaSobrePanel, … }

// vista
map.setView(ll, zoom?, { animate })      map.panTo(ll, { animate })       map.stop()
map.fitBounds(caja, { padding: n | {top,left,bottom,right}, maxZoom, animate })     // para SEVB prefiere Spevb.fitMap(map, caja, { maxZoom })
map.getZoom()  map.getCenter() → [lat,lng]  map.getBounds() → caja  map.getSize() → {x,y}  map.resize()
map.setDobleClicZoom(bool)

// proyección (con Google solo existe tras el primer dibujo: puede devolver null)
map.toPoint(ll) → {x,y} | null    map.toLatLng({x,y}) → [lat,lng] | null    map.eventLatLng(eventoDom) → [lat,lng] | null

// eventos del mapa: click dblclick moveend zoomend zoomstart dragstart dragend resize
const off = map.on('moveend zoomend', (ev) => …)    map.off(tipos, fn)
//   ev = { latlng: [lat,lng] | null, originalEvent }.  dragstart/dragend solo los provoca la persona (nunca setView/panTo).

// elementos (se crean con la fábrica del mapa y se añaden con addTo(mapa | grupo))
map.marker(ll, { html, className, size:[w,h], anchor:[x,y], z, interactive, keyboard, draggable, tooltip, tooltipOpciones, popup })
map.dot(ll, { radius, color, weight, fillColor, fillOpacity, tooltip, interactive, className })     // radio en PÍXELES (circleMarker)
map.circle(ll, radioMetros, { color, weight, fillColor, fillOpacity, interactive })
map.polyline(puntos, { color, weight, opacity, dashArray, lineCap, interactive, capa:'caminata', z, lienzo:'propio', tooltip, tooltipPegajoso })
map.group()                                   // Grupo: add, has, quitar, clear, size, addTo
map.add(el) map.quitar(el) map.has(el)        // quitar = ocultar sin destruir; el.remove() = destruir y liberar listeners
map.addControl(elementoDom, 'topright'|'topleft'|'bottomright'|'bottomleft') → función que lo retira
map.destroy()
```

Elementos:

```
el.addTo(padre) → el           el.remove()                 el.on('click'|'dblclick'|'dragstart'|'dragend', fn) → off()
marcador: setPosition(ll) getPosition() getElement() setClase(c, on) setTooltip(t, opc) openTooltip() setPopup(t) openPopup() setArrastrable(bool)
punto:    setStyle({color,weight,fillColor,fillOpacity,radius}) setPosition(ll) getPosition()
círculo:  setPosition(ll) setRadius(m) getBounds()
línea:    setPath(puntos, { crece }) getPath()   // crece:true = mismo arreglo, solo se agregaron puntos al final (estela del bus): se agregan solo los nuevos
           setStyle({color,weight,opacity,dashArray}) getBounds() setTooltip(t) setClase(c, on)
grupo:    add(el) has(el) quitar(el) clear() size
eventos de elemento: ev = { latlng, originalEvent, target, detener() }   (detener() evita que el clic llegue al mapa; con Google ya no llega)
```

Notas de comportamiento:

* `capa:'caminata'` dibuja la línea por encima de las rutas (con Leaflet en SVG, para la animación CSS `.walk-line`). Con Google el punteado es estático
  (`capacidades.lineaAnimada === false`): las polilíneas de Google no admiten animación por CSS.
* Los guiones de `dashArray` se traducen a símbolos repetidos en Google (`'1 10'` = puntos; `'8 6'` = guiones).
* `lienzo:'propio'` (Leaflet) pinta la línea en un canvas aparte; Google lo ignora.
* El orden de creación se conserva (el «borde blanco» se crea antes que la línea de color).
* Los marcadores llevan la clase `sevb-marcador` y los controles `mapa-control` (para excluirlos al elegir un punto con un toque).
* Para `Spevb.createBusPlayer(map)`: el panel de controles es un control propio (`addControl`), no una capa.

## Capacidades solo de un proveedor

* Leaflet: `map.baseActiva()`, `map.usarBase(nombre)` (capas base y respaldo sin conexión), «Guardar mapa» (caché de teselas propias).
* Google: sin selector de capas, sin «Guardar mapa» (los términos de Google no permiten almacenar sus teselas), modo noche por `colorScheme` solo al abrir la página.
