"""SEVB · Presentación del modelo de la buseta: cámara ortográfica, luces y escena de previsualización.

Ejecutar en Blender DESPUÉS de build_bus.py:  ns = {}; exec(open(r"<ruta>/presentation.py", encoding="utf-8").read(), ns)
Crea la colección SEVB_BUS_PRESENTATION (cámara SEVB_Bus_Preview_Camera, 3 luces, plano de prueba) y una escena aparte
«SEVB_PREVIEW» que enlaza solo el bus y la presentación, de modo que Cube/Camera/Light de la escena original no aparecen
en los renders ni se modifican.
"""
import math
import bpy
from mathutils import Vector

PCOL, PSCENE = "SEVB_BUS_PRESENTATION", "SEVB_PREVIEW"
TARGET = Vector((0, 0, 1.3))


def _purge(col):
    for o in list(col.all_objects):
        d, t = o.data, o.type
        bpy.data.objects.remove(o, do_unlink=True)
        if d is not None and d.users == 0:
            {"CAMERA": bpy.data.cameras, "LIGHT": bpy.data.lights, "MESH": bpy.data.meshes}[t].remove(d)
    bpy.data.collections.remove(col)


def _look(obj, frm, to=TARGET):
    obj.location = frm
    obj.rotation_euler = (to - Vector(frm)).to_track_quat("-Z", "Y").to_euler()


def setup():
    old = bpy.data.collections.get(PCOL)
    if old:
        _purge(old)
    pcol = bpy.data.collections.new(PCOL)
    bpy.context.scene.collection.children.link(pcol)

    cd = bpy.data.cameras.new("SEVB_Bus_Preview_Camera")
    cd.type, cd.ortho_scale, cd.clip_start, cd.clip_end = "ORTHO", 9.0, 0.1, 100
    cam = bpy.data.objects.new("SEVB_Bus_Preview_Camera", cd)
    pcol.objects.link(cam)
    _look(cam, (0, 0, 30))

    def light(name, kind, energy, pos, **kw):
        ld = bpy.data.lights.new(name, kind)
        ld.energy = energy
        for k, v in kw.items():
            setattr(ld, k, v)
        lo = bpy.data.objects.new(name, ld)
        pcol.objects.link(lo)
        _look(lo, pos)
        return lo

    light("SEVB_Key", "SUN", 2.2, (6, 8, 12), angle=math.radians(14))           # luz principal suave, desde delante-derecha-arriba
    light("SEVB_Fill", "AREA", 260, (-9, 4, 6), size=8.0)                         # relleno amplio desde la izquierda
    light("SEVB_Rim", "SUN", 0.7, (-3, -9, 7), angle=math.radians(20))           # contorno muy moderado desde atrás

    me = bpy.data.meshes.new("SEVB_TestPlane")
    s = 40
    me.from_pydata([(-s, -s, 0), (s, -s, 0), (s, s, 0), (-s, s, 0)], [], [(0, 1, 2, 3)])
    plane = bpy.data.objects.new("SEVB_TestPlane", me)
    plane.hide_render = True                                                       # solo se muestra al renderizar sobre mapa
    pcol.objects.link(plane)
    pm = bpy.data.materials.get("SEVB_TestPlane_Mat") or bpy.data.materials.new("SEVB_TestPlane_Mat")
    pm.use_nodes = True
    me.materials.append(pm)

    sc = bpy.data.scenes.get(PSCENE) or bpy.data.scenes.new(PSCENE)
    for c in list(sc.collection.children):
        sc.collection.children.unlink(c)
    sc.collection.children.link(bpy.data.collections["SEVB_BUS_MASTER"])
    sc.collection.children.link(pcol)
    sc.camera = cam
    w = bpy.data.worlds.get("SEVB_PREVIEW_World") or bpy.data.worlds.new("SEVB_PREVIEW_World")
    w.use_nodes = True
    bg = next(n for n in w.node_tree.nodes if n.type == "BACKGROUND")
    bg.inputs["Color"].default_value = (0.85, 0.87, 0.9, 1)
    bg.inputs["Strength"].default_value = 0.45
    sc.world = w
    sc.render.film_transparent = True
    sc.render.image_settings.file_format = "PNG"
    sc.render.image_settings.color_mode = "RGBA"
    try:
        sc.view_settings.view_transform = "Standard"
    except TypeError:
        pass
    return sc


def plane_color(hex_color):
    pm = bpy.data.materials["SEVB_TestPlane_Mat"]
    b = next(n for n in pm.node_tree.nodes if n.type == "BSDF_PRINCIPLED")
    h = hex_color.lstrip("#")
    c = [int(h[i:i + 2], 16) / 255 for i in (0, 2, 4)]
    f = lambda v: v / 12.92 if v <= 0.04045 else ((v + 0.055) / 1.055) ** 2.4
    b.inputs["Base Color"].default_value = (f(c[0]), f(c[1]), f(c[2]), 1)
    b.inputs["Roughness"].default_value = 1.0


def render(path, direction, ortho, w, h, plane=None):
    """Renderiza desde `direction` (vector hacia la cámara) en ortográfica. plane=None → fondo transparente."""
    sc = bpy.data.scenes[PSCENE]
    cam = bpy.data.objects["SEVB_Bus_Preview_Camera"]
    d = Vector(direction).normalized() * 40
    _look(cam, TARGET + d)
    if abs(d.x) < 1e-6 and abs(d.y) < 1e-6:                                        # cenital: el frente (+Y) queda arriba
        cam.rotation_euler = (0, 0, 0)
    cam.data.ortho_scale = ortho
    sc.render.resolution_x, sc.render.resolution_y, sc.render.resolution_percentage = w, h, 100
    sc.render.filepath = path
    tp = bpy.data.objects["SEVB_TestPlane"]
    tp.hide_render = plane is None
    if plane:
        plane_color(plane)
    sc.render.film_transparent = plane is None
    bpy.ops.render.render(write_still=True, scene=PSCENE)
    tp.hide_render = True


if __name__ == "__main__" or True:
    setup()
