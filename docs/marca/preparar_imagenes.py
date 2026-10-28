"""Prepara los recursos de marca a partir de los originales de /branding-originales (que NO se modifican).
Los originales traen el fondo de cuadritos «horneado» en los píxeles (sin transparencia real): aquí se separa la tinta del fondo
calculando el alfa a partir del brillo (tinta oscura sobre fondo claro, o tinta blanca sobre fondo oscuro), se conservan los bordes
suavizados, se recorta el margen y se optimiza el peso. Uso:  python docs/marca/preparar_imagenes.py   (requiere Pillow y numpy)."""
import os
from collections import deque
import numpy as np
from PIL import Image, ImageDraw

VERDE = (23, 102, 80)      # #176650: el verde de la marca del proyecto (--brand)
RAIZ = os.path.abspath(os.path.join(os.path.dirname(__file__), '..', '..'))
ORIG = os.path.join(RAIZ, 'branding-originales')
STATIC = os.path.join(RAIZ, 'src', 'main', 'resources', 'static')
MARCA = os.path.join(STATIC, 'img', 'marca')
ICONOS = os.path.join(STATIC, 'icons')
os.makedirs(MARCA, exist_ok=True)

def lum(a): return a[..., :3] @ np.array([0.299, 0.587, 0.114])

def tinta_sobre_claro(ruta, color, fondo=228.0, tinta=40.0):
    """Línea oscura sobre fondo claro con cuadritos → PNG con alfa. El color de la tinta es fijo (el del dibujo)."""
    a = np.asarray(Image.open(ruta).convert('RGB')).astype(float)
    alfa = np.clip((fondo - lum(a)) / (fondo - tinta), 0, 1)
    alfa[alfa < 0.04] = 0
    return _rgba(alfa, color)

def tinta_blanca_sobre_oscuro(ruta, fondo=110.0, tinta=250.0):
    a = np.asarray(Image.open(ruta).convert('RGB')).astype(float)
    alfa = np.clip((lum(a) - fondo) / (tinta - fondo), 0, 1)
    alfa[alfa < 0.06] = 0
    return _rgba(alfa, (255, 255, 255))

def _rgba(alfa, color):
    h, w = alfa.shape
    out = np.zeros((h, w, 4), np.uint8)
    out[..., 0], out[..., 1], out[..., 2] = color
    out[..., 3] = (alfa * 255).round().astype(np.uint8)
    return Image.fromarray(out, 'RGBA')

def recortar(im, margen=0.03):
    caja = im.getchannel('A').point(lambda v: 255 if v > 8 else 0).getbbox()
    if not caja: return im
    x0, y0, x1, y1 = caja
    m = int(max(x1 - x0, y1 - y0) * margen)
    return im.crop((max(0, x0 - m), max(0, y0 - m), min(im.width, x1 + m), min(im.height, y1 + m)))

def ajustar_alto(im, alto):
    alto = min(alto, im.height)          # nunca se agranda un original
    return im.resize((max(1, round(im.width * alto / im.height)), alto), Image.LANCZOS)

def guardar(im, nombre, carpeta=MARCA):
    ruta = os.path.join(carpeta, nombre)
    im.save(ruta, optimize=True)
    print('  ', os.path.relpath(ruta, RAIZ), im.size, os.path.getsize(ruta) // 1024, 'KB')

def icono_app(ruta):
    """Cabeza blanca en cuadro (originalmente azul, ahora verde de la marca) con esquinas redondeadas: el exterior (cuadritos) pasa a transparente y se conserva el borde suave."""
    rgb = Image.open(ruta).convert('RGB'); a = np.asarray(rgb).astype(float); l = lum(a)
    azul = (a[..., 2] - a[..., 0] > 35) & (l < 140)
    ys, xs = np.where(azul)
    x0, y0, x1, y1 = xs.min(), ys.min(), xs.max() + 1, ys.max() + 1
    # exterior = lo que se alcanza desde el borde sin cruzar el azul (la cabeza blanca queda encerrada, es parte del icono)
    barrera = Image.fromarray((azul * 255).astype(np.uint8), 'L').filter(__import__('PIL.ImageFilter', fromlist=['x']).MaxFilter(3))
    marca = Image.new('L', rgb.size, 0); marca.paste(barrera)
    relleno = marca.copy(); ImageDraw.floodfill(relleno, (0, 0), 128)
    exterior = np.asarray(relleno) == 128
    dentro = ~exterior
    # borde suave: alfa por brillo solo en el anillo de 3 px alrededor
    from PIL import ImageFilter
    dil = np.asarray(Image.fromarray((dentro * 255).astype(np.uint8)).filter(ImageFilter.MaxFilter(7))) > 0
    ero = np.asarray(Image.fromarray((dentro * 255).astype(np.uint8)).filter(ImageFilter.MinFilter(7))) > 0
    alfa = np.where(ero, 1.0, np.where(dil, np.clip((215 - l) / (215 - 40), 0, 1), 0.0))
    out = np.zeros(a.shape[:2] + (4,), np.uint8)
    # El original es azul marino con la cabeza blanca: se conserva la forma y se cambia el azul por el verde de la marca
    # (cuánto «blanco» tiene cada píxel decide su mezcla entre verde y blanco; el borde suave se mantiene).
    blanco = np.clip((l - 38.0) / (255.0 - 38.0), 0, 1)[..., None]
    verde = np.array(VERDE, float)
    recolor = verde + (255.0 - verde) * blanco
    out[..., :3] = np.where(ero[..., None], recolor, verde)[..., :3].round().astype(np.uint8)
    out[..., 3] = (alfa * 255).round().astype(np.uint8)
    im = Image.fromarray(out, 'RGBA').crop((x0 - 4, y0 - 4, x1 + 4, y1 + 4))
    lado = max(im.size); lienzo = Image.new('RGBA', (lado, lado), (0, 0, 0, 0)); lienzo.paste(im, ((lado - im.width) // 2, (lado - im.height) // 2))
    return lienzo

def sangrado(icono, lado, escala=1.0):
    """Cuadrado verde a sangre (sin esquinas transparentes) con el icono encima: para iOS (apple-touch) y para iconos «maskable»."""
    fondo = Image.new('RGBA', (lado, lado), VERDE + (255,))
    lleno = Image.new('RGBA', icono.size, VERDE + (255,)); lleno.alpha_composite(icono)
    t = round(lado * escala); lleno = lleno.resize((t, t), Image.LANCZOS)
    fondo.alpha_composite(lleno, ((lado - t) // 2, (lado - t) // 2))
    return fondo.convert('RGB')

if __name__ == '__main__':
    print('Mascota y símbolo (tinta verde de la marca, transparencia real):')
    mascota = recortar(tinta_sobre_claro(os.path.join(ORIG, 'mascota-sin-texto.webp'), VERDE))
    guardar(ajustar_alto(mascota, 560), 'sevb-mascota.png')
    cabeza = recortar(tinta_sobre_claro(os.path.join(ORIG, 'simbolo-cabeza-azul.webp'), VERDE))
    guardar(ajustar_alto(cabeza, 256), 'sevb-cabeza.png')
    guardar(ajustar_alto(cabeza, 96), 'sevb-cabeza-96.png')
    print('Logo horizontal blanco (para superficies oscuras):')
    blanco = recortar(tinta_blanca_sobre_oscuro(os.path.join(ORIG, 'logo-horizontal-blanco-SEVB.webp')))
    guardar(ajustar_alto(blanco, 280), 'sevb-horizontal-blanco.png')
    print('Icono de la aplicación:')
    ic = icono_app(os.path.join(ORIG, 'icono-app-cabeza-blanca-cuadro-azul.png'))
    guardar(ic.resize((512, 512), Image.LANCZOS), 'sevb-icono-app.png')
    guardar(ic.resize((128, 128), Image.LANCZOS), 'sevb-icono-app-128.png')
    guardar(ic.resize((192, 192), Image.LANCZOS), 'icon-192.png', ICONOS)
    guardar(ic.resize((512, 512), Image.LANCZOS), 'icon-512.png', ICONOS)
    guardar(sangrado(ic, 512, 0.80), 'icon-maskable-512.png', ICONOS)
    guardar(sangrado(ic, 180, 1.0), 'apple-touch-icon.png', ICONOS)
    f32 = ic.resize((32, 32), Image.LANCZOS); f48 = ic.resize((48, 48), Image.LANCZOS); f16 = ic.resize((16, 16), Image.LANCZOS)
    guardar(f32, 'favicon-32.png', ICONOS); guardar(f48, 'favicon-48.png', ICONOS)
    ico = os.path.join(STATIC, 'favicon.ico'); ic.resize((256, 256), Image.LANCZOS).save(ico, sizes=[(16, 16), (32, 32), (48, 48)])
    print('  ', os.path.relpath(ico, RAIZ), os.path.getsize(ico) // 1024, 'KB')
