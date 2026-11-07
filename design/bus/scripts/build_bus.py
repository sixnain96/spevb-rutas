"""SEVB · Buseta urbana de Villavicencio — constructor del modelo maestro (Blender 5.2 LTS).

Ejecutar dentro de Blender:  exec(open(r"<ruta>/design/bus/scripts/build_bus.py", encoding="utf-8").read())

Convenciones: 1 unidad = 1 m · frente = +Y · derecha (puerta) = +X · Z arriba · origen en el suelo (Z=0), centrado.
Solo toca las colecciones SEVB_BUS_MASTER (la reconstruye) y sus materiales MAT_*. No toca Cube, Camera, Light ni otras colecciones.
Sin recursos externos: toda la geometría y los materiales salen de este archivo.
"""
import math
import bpy
import bmesh
from mathutils import Matrix

COL_NAME = "SEVB_BUS_MASTER"
DEFAULT_ROUTE_COLOR = "#5B7C99"      # marcador neutro; el color real de cada ruta se aplica con set_route_color()

# ------------------------------------------------------------------ medidas (m)
BW = 1.15                 # medio ancho del cuerpo (ancho 2,30)
Y_REAR, Y_MAIN_FRONT, Y_FRONT = -3.80, 2.70, 3.80
Z_BODY0, Z_BODY1 = 0.43, 2.74
HOOD_W, HOOD_TOP = 1.05, 1.60
AXLE_F, AXLE_R, WHEEL_R, WHEEL_W, WHEEL_X = 2.35, -1.85, 0.46, 0.28, 1.07


def lin(h):
    h = h.lstrip("#")
    c = [int(h[i:i + 2], 16) / 255 for i in (0, 2, 4)]
    f = lambda v: v / 12.92 if v <= 0.04045 else ((v + 0.055) / 1.055) ** 2.4
    return (f(c[0]), f(c[1]), f(c[2]), 1.0)


# ------------------------------------------------------------------ limpieza (solo lo propio)
def clean():
    col = bpy.data.collections.get(COL_NAME)
    if col:
        for o in list(col.all_objects):
            me = o.data if o.type == "MESH" else None
            bpy.data.objects.remove(o, do_unlink=True)
            if me is not None and me.users == 0:
                bpy.data.meshes.remove(me)
        bpy.data.collections.remove(col)


# ------------------------------------------------------------------ materiales
MATS = {}


def material(name, color, rough=0.5, metal=0.0, emit=None, strength=0.0, fake=False):
    m = bpy.data.materials.get(name) or bpy.data.materials.new(name)
    m.use_nodes = True
    nt = m.node_tree
    b = next((n for n in nt.nodes if n.type == "BSDF_PRINCIPLED"), None)
    if b is None:
        b = nt.nodes.new("ShaderNodeBsdfPrincipled")
        out = next((n for n in nt.nodes if n.type == "OUTPUT_MATERIAL"), None) or nt.nodes.new("ShaderNodeOutputMaterial")
        nt.links.new(b.outputs["BSDF"], out.inputs["Surface"])
    b.inputs["Base Color"].default_value = lin(color)
    b.inputs["Roughness"].default_value = rough
    b.inputs["Metallic"].default_value = metal
    for k, v in (("Emission Color", lin(emit) if emit else (0, 0, 0, 1)), ("Emission Strength", strength if emit else 0.0)):
        if k in b.inputs:
            b.inputs[k].default_value = v
    m.diffuse_color = lin(color)
    m.use_fake_user = fake
    MATS[name] = m
    return m


def make_materials():
    material("MAT_BodyWhite", "#EDEAE0", 0.45)
    material("MAT_RouteColor", DEFAULT_ROUTE_COLOR, 0.40)
    material("MAT_RouteColorMask", "#000000", 1.0, emit="#FFFFFF", strength=1.0, fake=True)   # solo para exportar la máscara
    material("MAT_Windows", "#0E1B26", 0.12)
    material("MAT_WindowFrames", "#1A1D1C", 0.60)
    material("MAT_Tires", "#111312", 0.85)
    material("MAT_Rims", "#A9AFAB", 0.35, metal=0.8)
    material("MAT_Chassis", "#2A2F2E", 0.70)
    material("MAT_Bumpers", "#3A403F", 0.55)
    material("MAT_Headlights", "#FFF3C2", 0.20, emit="#FFF3C2", strength=0.6)
    material("MAT_Taillights", "#D62D20", 0.25, emit="#D62D20", strength=0.5)
    material("MAT_Indicators", "#FFB02E", 0.30, emit="#FFB02E", strength=0.5)
    material("MAT_Grille", "#1E2322", 0.50)
    material("MAT_Plates", "#FFD400", 0.50)
    material("MAT_InteriorDark", "#0A0C0C", 0.90)
    material("MAT_RoofHatch", "#A3ACA7", 0.50)


def set_route_color(hex_color):
    """Cambia el color de la franja y del letrero (MAT_RouteColor) — el único material dinámico."""
    m = bpy.data.materials["MAT_RouteColor"]
    b = next(n for n in m.node_tree.nodes if n.type == "BSDF_PRINCIPLED")
    b.inputs["Base Color"].default_value = lin(hex_color)
    m.diffuse_color = lin(hex_color)


# ------------------------------------------------------------------ primitivas bmesh
def box(bm, x0, x1, y0, y1, z0, z1, m=0):
    v = [bm.verts.new((x, y, z)) for z in (z0, z1) for y in (y0, y1) for x in (x0, x1)]
    g = lambda xi, yi, zi: v[zi * 4 + yi * 2 + xi]
    quads = [
        (g(0, 0, 0), g(0, 1, 0), g(1, 1, 0), g(1, 0, 0)), (g(0, 0, 1), g(1, 0, 1), g(1, 1, 1), g(0, 1, 1)),
        (g(0, 1, 0), g(0, 1, 1), g(1, 1, 1), g(1, 1, 0)), (g(0, 0, 0), g(1, 0, 0), g(1, 0, 1), g(0, 0, 1)),
        (g(0, 0, 0), g(0, 0, 1), g(0, 1, 1), g(0, 1, 0)), (g(1, 0, 0), g(1, 1, 0), g(1, 1, 1), g(1, 0, 1)),
    ]
    for q in quads:
        bm.faces.new(q).material_index = m


def mbox(bm, x0, x1, y0, y1, z0, z1, m=0):
    """Caja y su espejo en X (x0 < x1, ambos del lado derecho +X)."""
    box(bm, x0, x1, y0, y1, z0, z1, m)
    box(bm, -x1, -x0, y0, y1, z0, z1, m)


def cyl_x(bm, cx, cy, cz, r, width, seg=20, m=0):
    """Cilindro con eje en X."""
    res = bmesh.ops.create_cone(bm, cap_ends=True, cap_tris=False, segments=seg, radius1=r, radius2=r, depth=width,
                                matrix=Matrix.Translation((cx, cy, cz)) @ Matrix.Rotation(math.pi / 2, 4, "Y"), calc_uvs=False)
    for f in {f for v in res["verts"] for f in v.link_faces}:
        f.material_index = m


def arch(bm, side, cy, r=0.56, cz=WHEEL_R, zcut=Z_BODY0):
    """Pasarruedas: disco oscuro apenas saliente del costado, recortado al borde inferior del cuerpo."""
    res = bmesh.ops.create_circle(bm, cap_ends=True, cap_tris=True, segments=24, radius=r,
                                  matrix=Matrix.Translation((side * (BW + 0.006), cy, cz)) @ Matrix.Rotation(side * math.pi / 2, 4, "Y"), calc_uvs=False)
    verts = res["verts"]
    geom = list(verts) + list({e for v in verts for e in v.link_edges}) + list({f for v in verts for f in v.link_faces})
    bmesh.ops.bisect_plane(bm, geom=geom, dist=1e-5, plane_co=(0, 0, zcut), plane_no=(0, 0, 1), clear_inner=True)


# ------------------------------------------------------------------ creación de objetos
col = root = None


def finish(name, bm, mats, bevel=None, loc=(0, 0, 0)):
    bmesh.ops.recalc_face_normals(bm, faces=bm.faces[:])
    me = bpy.data.meshes.new(name)
    bm.to_mesh(me)
    bm.free()
    for mn in mats:
        me.materials.append(MATS[mn])
    for p in me.polygons:
        p.use_smooth = True
    ob = bpy.data.objects.new(name, me)
    col.objects.link(ob)
    ob.parent = root
    ob.location = loc
    if bevel:
        md = ob.modifiers.new("Bevel", "BEVEL")
        md.width, md.segments = bevel[0], bevel[1]
        md.limit_method = "ANGLE"
        md.angle_limit = math.radians(35)
        try:
            md.harden_normals = True
        except Exception:
            pass
    return ob


def build():
    global col, root
    clean()
    make_materials()
    col = bpy.data.collections.new(COL_NAME)
    bpy.context.scene.collection.children.link(col)
    root = bpy.data.objects.new("SEVB_Bus_Root", None)
    root.empty_display_type = "ARROWS"
    root.empty_display_size = 1.5
    col.objects.link(root)
    root["sevb_units"] = "1 unidad = 1 m"
    root["sevb_front_axis"] = "+Y"
    root["sevb_right_axis"] = "+X (puerta de pasajeros)"
    root["sevb_route_color_material"] = "MAT_RouteColor"
    new = bmesh.new

    # -------- carrocería
    bm = new(); box(bm, -BW, BW, Y_REAR, Y_MAIN_FRONT, Z_BODY0, Z_BODY1)
    finish("BUS_Body", bm, ["MAT_BodyWhite"], (0.16, 3))

    # techo levemente curvo (subdivisión transversal + caída en los bordes)
    bm = new(); box(bm, -1.10, 1.10, -3.72, 2.62, Z_BODY1, 2.90)
    for cx in (-0.72, -0.36, 0.0, 0.36, 0.72):
        bmesh.ops.bisect_plane(bm, geom=bm.verts[:] + bm.edges[:] + bm.faces[:], dist=1e-5, plane_co=(cx, 0, 0), plane_no=(1, 0, 0))
    for v in bm.verts:
        if v.co.z > 2.85:
            v.co.z = 2.90 - 0.10 * (v.co.x / 1.10) ** 2
    finish("BUS_Roof", bm, ["MAT_BodyWhite"], (0.06, 2))

    bm = new(); box(bm, -HOOD_W, HOOD_W, Y_MAIN_FRONT, Y_FRONT, 0.45, HOOD_TOP)
    finish("BUS_Hood", bm, ["MAT_BodyWhite"], (0.10, 3))

    # visera y tablero (marco del parabrisas) / panel trasero oscuro
    bm = new()
    box(bm, -1.08, 1.08, Y_MAIN_FRONT, 2.82, 2.56, 2.66, 0)
    box(bm, -1.02, 1.02, Y_MAIN_FRONT, 2.77, HOOD_TOP, 1.74, 1)
    finish("BUS_Front", bm, ["MAT_BodyWhite", "MAT_Bumpers"], (0.03, 2))
    bm = new(); box(bm, -0.98, 0.98, -3.82, Y_REAR, 0.72, 1.25)
    finish("BUS_Rear", bm, ["MAT_Chassis"], (0.02, 1))

    # -------- cristales y marcos
    panes = [(1.60, 2.55), (0.15, 1.45), (-1.25, 0.00), (-2.65, -1.40), (-3.55, -2.80)]
    bm = new()
    box(bm, -1.02, 1.02, Y_MAIN_FRONT, 2.735, 1.76, 2.55)                 # parabrisas
    box(bm, -0.90, 0.90, Y_REAR - 0.025, Y_REAR, 1.60, 2.45)              # luneta
    for i, (y0, y1) in enumerate(panes):
        dx = 0.04 if i == 0 else 0.0                                      # el cristal de la puerta va sobre el panel de la puerta
        box(bm, BW + dx + 0.015, BW + dx + 0.03, y0, y1, 1.55, 2.52)
        box(bm, -BW - 0.03, -BW - 0.015, y0, y1, 1.55, 2.52)
    finish("BUS_Windows", bm, ["MAT_Windows"], (0.008, 1))

    bm = new()
    box(bm, -1.07, 1.07, Y_MAIN_FRONT, 2.72, 1.72, 2.60)                  # marco del parabrisas
    box(bm, -0.025, 0.025, Y_MAIN_FRONT, 2.75, 1.72, 2.60)                # montante central
    box(bm, -0.95, 0.95, Y_REAR - 0.015, Y_REAR, 1.55, 2.50)              # marco de la luneta
    for i, (y0, y1) in enumerate(panes):
        dx = 0.04 if i == 0 else 0.0
        box(bm, BW + dx, BW + dx + 0.015, y0 - 0.05, y1 + 0.05, 1.50, 2.57)
        box(bm, -BW - 0.015, -BW, y0 - 0.05, y1 + 0.05, 1.50, 2.57)
    finish("BUS_WindowFrames", bm, ["MAT_WindowFrames"], (0.008, 1))

    # -------- puerta derecha (panel blanco con junta oscura y línea central)
    bm = new()
    box(bm, BW, BW + 0.04, 1.55, 2.62, 0.55, 2.58, 0)
    box(bm, BW, BW + 0.015, 1.52, 2.65, 0.52, 2.61, 1)
    box(bm, BW + 0.04, BW + 0.05, 2.075, 2.095, 0.58, 1.50, 1)
    finish("BUS_Door_Right", bm, ["MAT_BodyWhite", "MAT_WindowFrames"], (0.012, 1))

    # -------- chasis, parachoques, rejilla
    bm = new(); box(bm, -0.90, 0.90, -3.50, 3.50, 0.20, Z_BODY0)
    finish("BUS_Chassis", bm, ["MAT_Chassis"], (0.03, 1))
    bm = new(); box(bm, -1.08, 1.08, Y_FRONT, 3.93, 0.32, 0.62)
    finish("BUS_Bumper_Front", bm, ["MAT_Bumpers"], (0.05, 2))
    bm = new(); box(bm, -1.13, 1.13, -3.93, Y_REAR, 0.32, 0.62)
    finish("BUS_Bumper_Rear", bm, ["MAT_Bumpers"], (0.05, 2))
    bm = new()
    box(bm, -0.42, 0.42, Y_FRONT, 3.825, 0.78, 1.18, 0)
    for z in (0.86, 0.98, 1.10):
        box(bm, -0.38, 0.38, 3.825, 3.842, z, z + 0.035, 1)
    finish("BUS_Grille", bm, ["MAT_Grille", "MAT_Bumpers"], (0.012, 1))

    # -------- luces
    bm = new(); mbox(bm, 0.52, 0.94, Y_FRONT, 3.83, 0.82, 1.14)
    finish("BUS_Headlights", bm, ["MAT_Headlights"], (0.03, 2))
    bm = new(); mbox(bm, 0.55, 0.96, -3.845, -3.82, 0.78, 1.18)
    finish("BUS_Taillights", bm, ["MAT_Taillights"], (0.03, 2))
    bm = new()
    mbox(bm, 0.60, 0.90, Y_FRONT, 3.82, 1.22, 1.34)                        # direccionales delanteras
    mbox(bm, 0.55, 0.90, -3.835, -3.82, 1.24, 1.36)                        # direccionales traseras
    mbox(bm, 0.72, 0.92, 2.48, 2.62, 2.82, 2.915)                          # luces de gálibo del techo
    finish("BUS_Indicators", bm, ["MAT_Indicators"], (0.012, 1))

    # -------- espejos
    bm = new()
    mbox(bm, BW + 0.05, 1.38, 2.42, 2.48, 1.88, 1.94)
    mbox(bm, 1.36, 1.46, 2.36, 2.56, 1.62, 2.18)
    finish("BUS_Mirrors", bm, ["MAT_Bumpers"], (0.025, 2))

    # -------- ruedas (origen en el centro de cada rueda) y pasarruedas
    for nm, sx, ay in (("FL", -1, AXLE_F), ("FR", 1, AXLE_F), ("RL", -1, AXLE_R), ("RR", 1, AXLE_R)):
        bm = new()
        cyl_x(bm, 0, 0, 0, WHEEL_R, WHEEL_W, 20, 0)
        for s in (-1, 1):
            cyl_x(bm, s * (WHEEL_W / 2 + 0.004), 0, 0, 0.29, 0.012, 16, 1)
        cyl_x(bm, sx * (WHEEL_W / 2 + 0.014), 0, 0, 0.09, 0.02, 12, 1)
        finish("BUS_Wheel_" + nm, bm, ["MAT_Tires", "MAT_Rims"], (0.035, 2), loc=(sx * WHEEL_X, ay, WHEEL_R))
    bm = new()
    for s in (-1, 1):
        for ay in (AXLE_F, AXLE_R):
            arch(bm, s, ay)
    finish("BUS_WheelArches", bm, ["MAT_InteriorDark"])

    # -------- techo: escotillas y ventilación
    bm = new()
    box(bm, -0.45, 0.45, 0.90, 1.90, 2.87, 2.94)
    box(bm, -0.45, 0.45, -1.60, -0.60, 2.87, 2.94)
    box(bm, -0.25, 0.25, -3.00, -2.50, 2.88, 2.94)
    finish("BUS_RoofHatches", bm, ["MAT_RoofHatch"], (0.03, 2))

    # -------- letrero frontal, franja de ruta y matrículas
    bm = new()
    box(bm, -0.50, 0.50, 2.73, 2.755, 2.22, 2.46, 0)
    box(bm, -0.50, 0.50, 2.755, 2.77, 2.38, 2.46, 1)
    finish("BUS_RouteSign", bm, ["MAT_BodyWhite", "MAT_RouteColor"], (0.012, 1))

    bm = new()
    box(bm, BW, BW + 0.015, -3.60, 1.50, 0.95, 1.30)                        # costado derecho
    box(bm, BW + 0.04, BW + 0.055, 1.55, 2.62, 0.95, 1.30)                  # sobre la puerta
    box(bm, -BW - 0.015, -BW, -3.60, 2.62, 0.95, 1.30)                      # costado izquierdo
    mbox(bm, HOOD_W, HOOD_W + 0.015, 2.76, 3.70, 0.82, 1.00)                # trompa
    box(bm, -1.00, 1.00, Y_REAR - 0.015, Y_REAR, 1.40, 1.52)                # trasera
    mbox(bm, 0.90, 1.04, -3.55, 2.55, 2.78, 2.855)                          # bordes del techo
    finish("BUS_RouteStripe", bm, ["MAT_RouteColor"], (0.01, 1))

    bm = new()
    box(bm, -0.20, 0.20, 3.93, 3.945, 0.37, 0.56)                           # delantera (genérica, sin caracteres)
    box(bm, -0.20, 0.20, -3.84, -3.82, 0.74, 0.93)                          # trasera
    finish("BUS_Plates", bm, ["MAT_Plates"], (0.008, 1))

    objs = [o for o in col.objects if o is not root]
    return objs


def stats():
    dg = bpy.context.evaluated_depsgraph_get()
    tris, mn, mx = 0, [1e9] * 3, [-1e9] * 3
    per = {}
    for o in col.all_objects:
        if o.type != "MESH":
            continue
        e = o.evaluated_get(dg)
        me = e.to_mesh()
        me.calc_loop_triangles()
        per[o.name] = len(me.loop_triangles)
        tris += len(me.loop_triangles)
        for v in me.vertices:
            w = e.matrix_world @ v.co
            for i in range(3):
                mn[i], mx[i] = min(mn[i], w[i]), max(mx[i], w[i])
        e.to_mesh_clear()
    return tris, per, mn, mx


if __name__ == "__main__" or True:
    build()
