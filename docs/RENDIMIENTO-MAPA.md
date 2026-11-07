# Mapa más rápido y compacto (v4.3)

Cambios solo de rendimiento y presentación: ninguna función se quitó.

| Cambio | Efecto |
|---|---|
| Créditos compactos: se ve «Leaflet ⓘ» (≈ 68 px en vez de ≈ 360) y al tocar se despliegan OpenFreeMap, OpenMapTiles, OpenStreetMap, Esri… (se cierran solos a los 8 s) | Menos espacio ocupado. Los créditos siguen a un toque, como exigen las licencias ODbL/OpenMapTiles/Esri |
| El mapa ráster de respaldo solo aparece si el vectorial tarda más de 1,5 s (o falla) | Con buena conexión ya no se descargan dos mapas a la vez: ahorra datos y trabajo |
| Resolución del mapa vectorial limitada a 2× (1,5× en equipos modestos) | Pantallas de 3× pintan ≈ 55 % menos píxeles sin pérdida visible |
| Sin antialias de la malla, sin refresco de teselas vencidas, actualización menos frecuente en equipos modestos, fundido corto | Menos trabajo de GPU/CPU al mover el mapa |
| Se pausan las líneas punteadas animadas y el halo del bus mientras se arrastra o se hace zoom | El dedo manda: el movimiento es más fluido |
| Ya existente y se mantiene: motor vectorial cargado solo si se usa, teselas en caché (service worker), modo ráster ligero en equipos modestos, bus a 30 fps en equipos de gama baja | |

## Medición (celular simulado: CPU 4× más lenta, red 3G rápida, WebGL por software)
`docs/pruebas/rendimiento_mapa.py` (antes → después, arrastrar y hacer zoom durante ≈ 14 s):
- Cuadros dibujados: 156–335 → 687
- Cuadro «lento» (percentil 95): 580–2650 ms → 230 ms
- Tiempo bloqueando el hilo principal: 23–30 s → 15 s
Es una medición relativa en un entorno sin GPU: en un teléfono real las cifras absolutas serán mejores, pero la mejora debe notarse igual.

## Pruebas
`docs/pruebas/mapa_e2e.py` (créditos, respaldo ráster, resolución, pausa de animaciones, en celular, tablet y computador).
