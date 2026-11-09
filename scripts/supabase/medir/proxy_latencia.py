"""Proxy TCP que añade latencia fija a cada mensaje, en los dos sentidos, para simular una base remota (p. ej. Supabase en Virginia
desde Villavicencio: unos 100 a 150 ms de ida y vuelta) delante de una base local de pruebas.

Uso:  python proxy_latencia.py PUERTO_LOCAL HOST_DESTINO PUERTO_DESTINO [MS_POR_SENTIDO]
      python proxy_latencia.py 55434 127.0.0.1 55433 55        (55 ms por sentido = 110 ms de ida y vuelta)

El orden de los bytes se conserva (cola FIFO por sentido). No cifra ni modifica nada; es solo una herramienta de medición.
"""
import asyncio
import sys
import time


async def bombear(lector, escritor, retardo):
    cola = asyncio.Queue()

    async def entregar():
        while True:
            instante, datos = await cola.get()
            if datos is None:
                break
            espera = instante - time.monotonic()
            if espera > 0:
                await asyncio.sleep(espera)
            escritor.write(datos)
            await escritor.drain()
        escritor.close()

    tarea = asyncio.create_task(entregar())
    try:
        while True:
            datos = await lector.read(65536)
            if not datos:
                break
            cola.put_nowait((time.monotonic() + retardo, datos))
    except (ConnectionError, asyncio.CancelledError):
        pass
    finally:
        cola.put_nowait((time.monotonic() + retardo, None))
        await tarea


async def atender(lector, escritor, host, puerto, retardo):
    try:
        destino_l, destino_e = await asyncio.open_connection(host, puerto)
    except OSError:
        escritor.close()
        return
    await asyncio.gather(bombear(lector, destino_e, retardo), bombear(destino_l, escritor, retardo), return_exceptions=True)


async def principal(local, host, puerto, ms):
    servidor = await asyncio.start_server(lambda l, e: atender(l, e, host, puerto, ms / 1000.0), '127.0.0.1', local)
    print(f'Proxy 127.0.0.1:{local} -> {host}:{puerto} con {ms} ms por sentido', flush=True)
    async with servidor:
        await servidor.serve_forever()


if __name__ == '__main__':
    if len(sys.argv) < 4:
        sys.exit(__doc__)
    asyncio.run(principal(int(sys.argv[1]), sys.argv[2], int(sys.argv[3]), float(sys.argv[4]) if len(sys.argv) > 4 else 55.0))
