# Caminata por calles: servicio propio (opcional)

SEVB calcula la caminata a pie con un servidor OSRM (perfil peatonal). Por defecto usa el servicio público
`routing.openstreetmap.de`, que es gratuito pero limita el uso (HTTP 429) y a veces tarda varios segundos.
Cuando eso ocurre SEVB usa la línea recta, sin romper nada. Para un uso continuo conviene montar tu propio OSRM
con el mapa de Colombia (una sola vez, ≈ 1 GB de disco y unos minutos de proceso).

```bash
mkdir osrm && cd osrm
curl -LO https://download.geofabrik.de/south-america/colombia-latest.osm.pbf
docker run --rm -v "$PWD:/data" ghcr.io/project-osrm/osrm-backend osrm-extract -p /opt/foot.lua /data/colombia-latest.osm.pbf
docker run --rm -v "$PWD:/data" ghcr.io/project-osrm/osrm-backend osrm-partition /data/colombia-latest.osrm
docker run --rm -v "$PWD:/data" ghcr.io/project-osrm/osrm-backend osrm-customize /data/colombia-latest.osrm
docker run -d --name osrm-foot -p 127.0.0.1:5000:5000 -v "$PWD:/data" ghcr.io/project-osrm/osrm-backend \
  osrm-routed --algorithm mld --max-table-size 200 /data/colombia-latest.osrm
```

Luego, en `.env`:

```env
WALKING_URL=http://127.0.0.1:5000/route/v1/foot
```

Con un servidor propio puedes subir `RUTAS_A_REFINAR` y `CANDIDATOS_POR_RUTA` en `ViajeService` sin riesgo de límites.
