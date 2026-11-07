# Puentes peatonales (v4.9)

Los puentes peatonales de Villavicencio casi no están en OpenStreetMap. El administrador los agrega a mano y desde ese momento:
- **Se pueden cruzar a pie**: la red de calles los usa como cualquier camino, así que la caminata de «Sube aquí / Bájate aquí» y los transbordos los toman si acortan el trayecto (prueba: una caminata de 473 m pasó a 200 m con un puente y volvió a 473 m al borrarlo).
- **Se ven en el mapa**: línea azul con borde blanco e icono de puente al acercar (zoom ≥ 15); al tocarlos muestran su nombre.

## Cómo agregar uno (`/puentes.html`, menú ☰ → «Puentes peatonales»)
1. Acerca el mapa hasta ver las calles en gris.
2. Toca el **inicio** del puente, los puntos intermedios si da vueltas y el **final**.
   Los dos extremos se pegan solos a la calle más cercana (hasta 25 m). Debajo aparece «✔ Inicio conectado a la calle · ✔ Final conectado a la calle». Si dice ✖, acerca más y vuelve a tocar sobre la calle.
3. Escribe el nombre y pulsa **Guardar puente**. Si entraste con Google como administrador no pide clave; si no, escribe `EDITOR_TOKEN`.

Para borrar uno: lista «Puentes guardados» → **Borrar**. La red se recalcula y las caminatas dejan de usarlo.

## Detalles técnicos
- Se guardan en `pasos_peatonales` con `tipo = 'PUENTE'` (la restricción se actualiza sola al arrancar). La red los aplica como un «paso»: sus extremos se enlazan a calles a ≤ 12 m y la longitud máxima es 600 m.
- `GET /api/puentes` (público, caché 1 min) lista los puentes; guardar/borrar usa `POST/DELETE /api/editor/pasos` (administrador o `EDITOR_TOKEN`).
- Archivos: `puentes.html`, `js/puentes.js` (administración), `js/puentes-publicos.js` (capa del mapa), `PuenteController`.
- Prueba: `docs/pruebas/ux_puentes_e2e.py`.
