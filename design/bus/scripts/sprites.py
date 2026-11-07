"""SEVB · Sprites direccionales de prueba (opción B/C de la Puerta C): atlas base + máscara de la franja de ruta.

Ejecutar en Blender tras build_bus.py y presentation.py. Cámara ortográfica con la misma inclinación que el 3D CSS actual
(rotateX 58° ⇒ 32° de elevación), mirando hacia el norte; el bus gira sobre Z (rumbo horario). Iluminación fija entre fotogramas.
Salida en design/bus/exports/sprites_test/ (NO en static/ hasta que se apruebe la integración).
"""
import math
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
ns = {"SKIP_RUN": True, "BUS_ROOT": B}
exec(open(B + "scripts/make_sheets.py", encoding="utf-8").read(), ns)
resize, save_np = ns["resize"], ns["save"]
OUT = B + "exports/sprites_test/"
os.makedirs(OUT, exist_ok=True)
FRAME, SUPER, ELEV = 96, 2, 32.0
ORTHO = 8.4                                  # m que cubre el lado del fotograma (bus de 7,87 m girando sin recortarse)
pres = None


def _render_frames(n, tag, render):
    root = bpy.data.objects["SEVB_Bus_Root"]
    d = (0, -math.cos(math.radians(ELEV)), math.sin(math.radians(ELEV)))
    frames = []
    for k in range(n):
        root.rotation_euler.z = -math.radians(360.0 * k / n)
        p = OUT + "_tmp_%s_%02d.png" % (tag, k)
        render(p, d, ORTHO, FRAME * SUPER, FRAME * SUPER)
        im = bpy.data.images.load(p)
        im.alpha_mode = "STRAIGHT"
        a = np.array(im.pixels[:], dtype=np.float32).reshape(FRAME * SUPER, FRAME * SUPER, 4)
        bpy.data.images.remove(im)
        os.remove(p)
        frames.append(resize(a, FRAME, FRAME))
    root.rotation_euler.z = 0
    return frames


def _atlas(frames, cols):
    n = len(frames)
    rows = math.ceil(n / cols)
    a = np.zeros((rows * FRAME, cols * FRAME, 4), dtype=np.float32)
    for k, f in enumerate(frames):
        r, c = divmod(k, cols)
        y0 = (rows - 1 - r) * FRAME
        a[y0:y0 + FRAME, c * FRAME:(c + 1) * FRAME] = f
    return a


def _save_fmt(arr, stem):
    sizes = {}
    h, w = arr.shape[:2]
    sc = bpy.data.scenes["SEVB_PREVIEW"]
    for fmt, ext, q in (("PNG", "png", None), ("WEBP", "webp", 82), ("AVIF", "avif", 60)):
        im = bpy.data.images.new("_a", w, h, alpha=True)
        im.pixels.foreach_set(np.clip(arr, 0, 1).astype(np.float32).ravel())
        s = sc.render.image_settings
        s.file_format, s.color_mode = fmt, "RGBA"
        if q is not None:
            s.quality = q
        path = OUT + stem + "." + ext
        try:
            im.save_render(path, scene=sc)
            sizes[ext] = os.path.getsize(path)
        except Exception as e:  # formato no disponible
            sizes[ext] = "error: %s" % e
        bpy.data.images.remove(im)
    sc.render.image_settings.file_format = "PNG"
    return sizes


def run(render):
    mats = [m for m in bpy.data.materials if m.name.startswith("MAT_") and m.name != "MAT_RouteColorMask"]
    result = {}
    base32 = _render_frames(32, "b", render)
    saved = {}
    for m in mats:                                     # máscara: solo la franja/letrero en blanco; todo lo demás, negro sin brillo
        b = next(n for n in m.node_tree.nodes if n.type == "BSDF_PRINCIPLED")
        saved[m.name] = {k: (tuple(b.inputs[k].default_value) if hasattr(b.inputs[k].default_value, "__len__") else b.inputs[k].default_value)
                         for k in ("Base Color", "Roughness", "Metallic", "Emission Color", "Emission Strength", "Specular IOR Level") if k in b.inputs}
        for k, v in (("Base Color", (0, 0, 0, 1)), ("Metallic", 0.0), ("Roughness", 1.0), ("Specular IOR Level", 0.0),
                     ("Emission Color", (1, 1, 1, 1) if m.name == "MAT_RouteColor" else (0, 0, 0, 1)),
                     ("Emission Strength", 1.0 if m.name == "MAT_RouteColor" else 0.0)):
            if k in b.inputs:
                b.inputs[k].default_value = v
    try:
        mask32 = _render_frames(32, "m", render)
    finally:
        for m in mats:
            b = next(n for n in m.node_tree.nodes if n.type == "BSDF_PRINCIPLED")
            for k, v in saved[m.name].items():
                b.inputs[k].default_value = v
    for f in mask32:                                   # alfa = cobertura de la franja; RGB blanco
        cov = f[..., 0:1] * f[..., 3:4]
        f[..., :3] = 1.0
        f[..., 3:4] = cov
    for n, step, cols in ((16, 2, 4), (32, 1, 8)):
        b = _atlas(base32[::step], cols)
        m = _atlas(mask32[::step], cols)
        result["base_%d" % n] = _save_fmt(b, "bus_%d_base" % n)
        result["mask_%d" % n] = _save_fmt(m, "bus_%d_mask" % n)
    return result
