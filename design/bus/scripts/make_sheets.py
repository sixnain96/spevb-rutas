"""SEVB · Hojas de comparación a partir de los renders de la buseta (se ejecuta dentro de Blender; usa solo bpy + numpy).

Genera en design/bus/previews/:
  contacto_vistas.png    cenital + isométricas + lateral + frontal + trasera
  colores_ruta.png       el mismo bus con las rutas #176650, #d83a2a, #1d5fd1, #f4c430
  tamano_real.png        vista cenital a 50, 64 y 100 px de largo (≈ 27×50, 36×64, 54×100) sobre mapa claro y oscuro, con halo y sombra
  tamano_real_x3.png     lo mismo ampliado ×3 (vecino más cercano) para poder revisar el detalle
"""
import os
import numpy as np
import bpy

from pathlib import Path


def _raiz_bus():
    """Carpeta design/bus/ sin rutas personales. Orden: variable BUS_ROOT (inyectada en el espacio de nombres del exec o ya definida),
    variable de entorno SEVB_BUS_ROOT, ubicación de este archivo (__file__ no existe con exec()), carpeta del .blend abierto y
    carpeta de trabajo (raíz del proyecto o design/bus)."""
    candidatas = []
    g = globals()
    if g.get("BUS_ROOT"):
        candidatas.append(Path(g["BUS_ROOT"]))
    if os.environ.get("SEVB_BUS_ROOT"):
        candidatas.append(Path(os.environ["SEVB_BUS_ROOT"]))
    if g.get("__file__"):
        candidatas.append(Path(g["__file__"]).resolve().parent.parent)
    if bpy.data.filepath:
        candidatas.append(Path(bpy.data.filepath).resolve().parent)
    candidatas += [Path.cwd() / "design" / "bus", Path.cwd()]
    for c in candidatas:
        if (c / "scripts" / "make_sheets.py").is_file():
            return c.resolve()
    raise RuntimeError("No encuentro design/bus. Define BUS_ROOT (p. ej. ns = {'BUS_ROOT': '<ruta>/design/bus'}), la variable de entorno "
                       "SEVB_BUS_ROOT, o abre sevb_bus_master.blend / ejecuta Blender desde la raíz del proyecto.")


B = _raiz_bus().as_posix() + "/"
BUS_PX_PER_M = 1000 / 8.8          # cenital: 1000 px de alto = 8,8 m
BUS_LEN_PX = 7.88 * BUS_PX_PER_M   # largo del bus (con parachoques) en el render cenital


def load(path):
    im = bpy.data.images.load(path)
    im.alpha_mode = "STRAIGHT"
    w, h = im.size
    a = np.array(im.pixels[:], dtype=np.float32).reshape(h, w, 4)
    bpy.data.images.remove(im)
    return a


def save(arr, path):
    h, w = arr.shape[:2]
    im = bpy.data.images.new("_sheet", w, h, alpha=True)
    im.pixels.foreach_set(np.clip(arr, 0, 1).astype(np.float32).ravel())
    im.filepath_raw = path
    im.file_format = "PNG"
    im.save()
    bpy.data.images.remove(im)


def _area(old, new):
    m = np.zeros((new, old), dtype=np.float32)
    r = old / new
    for i in range(new):
        a, b = i * r, (i + 1) * r
        for j in range(int(a), min(old, int(np.ceil(b)))):
            m[i, j] = max(0.0, min(b, j + 1) - max(a, j))
    return m / m.sum(axis=1, keepdims=True)


def resize(arr, w, h):
    """Reducción por promedio de área con alfa premultiplicado (sin halos)."""
    pm = arr.copy()
    pm[..., :3] *= pm[..., 3:4]
    my, mx = _area(arr.shape[0], h), _area(arr.shape[1], w)
    out = np.einsum("ij,jkc->ikc", my, pm)
    out = np.einsum("kl,ilc->ikc", mx, out)
    a = out[..., 3:4]
    out[..., :3] = np.where(a > 1e-6, out[..., :3] / np.maximum(a, 1e-6), 0)
    return out


def over(dst, src, x, y):
    """Compone src (RGBA recto) sobre dst en (x, y) — origen abajo-izquierda como Blender."""
    h, w = src.shape[:2]
    H, W = dst.shape[:2]
    x0, y0 = max(0, x), max(0, y)
    x1, y1 = min(W, x + w), min(H, y + h)
    if x1 <= x0 or y1 <= y0:
        return
    s = src[y0 - y:y1 - y, x0 - x:x1 - x]
    d = dst[y0:y1, x0:x1]
    a = s[..., 3:4]
    d[..., :3] = s[..., :3] * a + d[..., :3] * (1 - a)
    d[..., 3:4] = a + d[..., 3:4] * (1 - a)


def fill(w, h, hexc):
    c = [int(hexc[i:i + 2], 16) / 255 for i in (1, 3, 5)]
    a = np.ones((h, w, 4), dtype=np.float32)
    a[..., :3] = c
    return a


def blur3(a):
    p = np.pad(a, ((1, 1), (1, 1), (0, 0)), mode="edge")
    return sum(p[i:i + a.shape[0], j:j + a.shape[1]] for i in range(3) for j in range(3)) / 9


def marker(bg_hex, color_hex, length, cell=120):
    """Celda de mapa con halo, sombra suave y la buseta cenital a `length` px de largo."""
    cellimg = fill(cell, cell, bg_hex)
    yy, xx = np.mgrid[0:cell, 0:cell]
    rr = np.hypot(xx - cell / 2, yy - cell / 2)
    halo = np.zeros((cell, cell, 4), dtype=np.float32)
    c = [int(color_hex[i:i + 2], 16) / 255 for i in (1, 3, 5)]
    halo[..., :3] = c
    halo[..., 3] = (rr < 22 * length / 50).astype(np.float32) * 0.22
    over(cellimg, halo, 0, 0)
    s = length / BUS_LEN_PX
    bus = resize(load(B + "exports/top_" + color_hex[1:] + ".png"), max(2, round(540 * s)), max(2, round(1000 * s)))
    sh = np.zeros_like(bus)
    sh[..., 3] = blur3(bus[..., 3:4])[..., 0] * 0.38
    ox, oy = round(cell / 2 - bus.shape[1] / 2), round(cell / 2 - bus.shape[0] / 2)
    over(cellimg, sh, ox + 1, oy - 2)
    over(cellimg, bus, ox, oy)
    # contorno del contenedor de 72×72 (referencia)
    o = (cell - 72) // 2
    edge = np.array([0.5, 0.5, 0.5, 1], dtype=np.float32)
    for t in range(72):
        for (px, py) in ((o + t, o), (o + t, o + 71), (o, o + t), (o + 71, o + t)):
            if t % 4 == 0:
                cellimg[py, px] = edge
    return cellimg


def strip(imgs, gap=8, bg=(0.93, 0.93, 0.93, 1)):
    h = max(i.shape[0] for i in imgs)
    w = sum(i.shape[1] for i in imgs) + gap * (len(imgs) + 1)
    out = np.ones((h + 2 * gap, w, 4), dtype=np.float32) * np.array(bg, dtype=np.float32)
    x = gap
    for i in imgs:
        over(out, i, x, gap)
        x += i.shape[1] + gap
    return out


def run():
    P = B + "previews/"
    # tamaño real
    colors = ["#176650", "#d83a2a", "#1d5fd1", "#f4c430"]
    rows = []
    for bg in ("#E9E6DC", "#2A3036"):
        for col in colors:
            rows.append(strip([marker(bg, col, L) for L in (50, 64, 100)], gap=4))
    wmax = max(r.shape[1] for r in rows)
    sheet = np.ones((sum(r.shape[0] for r in rows), wmax, 4), dtype=np.float32)
    y = 0
    for r in reversed(rows):
        over(sheet, r, 0, y)
        y += r.shape[0]
    save(sheet, P + "tamano_real.png")
    save(np.repeat(np.repeat(sheet, 3, axis=0), 3, axis=1), P + "tamano_real_x3.png")
    # contacto de vistas y colores
    sc = lambda n, h: (lambda a: resize(a, round(a.shape[1] * h / a.shape[0]), h))(load(P + n))
    save(strip([sc(n, 360) for n in ("cenital.png", "iso_frontal_derecha.png", "iso_frontal_izquierda.png")]), P + "contacto_vistas.png")
    save(strip([sc("iso_ruta_%s.png" % c[1:], 300) for c in colors]), P + "colores_ruta.png")


if not globals().get("SKIP_RUN"):
    run()
