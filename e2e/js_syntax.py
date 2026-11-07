"""Comprueba la sintaxis de TODOS los JavaScript del proyecto (frontend, service worker y harnesses) con el motor del navegador.

Uso: python e2e/js_syntax.py        (no necesita la aplicación en marcha; usa Edge/Chrome sin cabeza)
Los archivos con marcador de Maven se leen ya filtrados desde target/classes cuando existen.
"""
import os
import pathlib
import sys

from playwright.sync_api import sync_playwright

RAIZ = pathlib.Path(__file__).resolve().parent.parent
EXCLUIR = ('target', '.idea', 'node_modules', '.git', 'dist')


def archivos():
    for p in sorted(RAIZ.rglob('*.js')):
        rel = p.relative_to(RAIZ).parts
        if rel[0] in EXCLUIR or rel[0].startswith('target') or 'vendor' in rel:
            continue
        yield p


def navegador(p):
    chrome = os.environ.get('CHROME_PATH')
    if chrome:
        return p.chromium.launch(executable_path=chrome, args=['--no-sandbox'])
    for canal in ('msedge', 'chrome'):
        try:
            return p.chromium.launch(channel=canal)
        except Exception:
            continue
    return p.chromium.launch()


def main():
    lista = list(archivos())
    malos = []
    with sync_playwright() as p:
        nav = navegador(p)
        page = nav.new_page()
        page.set_content('<html></html>')
        for f in lista:
            fuente = f.read_text(encoding='utf-8')
            error = page.evaluate("""(src) => { try { new Function(src); return null; } catch (e) { return String(e); } }""", fuente)
            if error:
                malos.append((f.relative_to(RAIZ).as_posix(), error))
        nav.close()
    for nombre, error in malos:
        print('FALLA ', nombre, '|', error)
    print(f'{len(lista)} archivos JavaScript revisados; con error de sintaxis: {len(malos)}')
    return 1 if malos else 0


if __name__ == '__main__':
    sys.exit(main())
