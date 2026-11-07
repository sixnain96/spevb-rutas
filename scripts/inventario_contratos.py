"""Inventario de contratos públicos y comparación contra una versión anterior.

Uso:  python -I scripts/inventario_contratos.py <carpeta_version_anterior> [salida.md]

Extrae de ambas carpetas: endpoints HTTP (controladores Spring), DTO (records de dto/), API pública de JavaScript (Spevb.*) y la
lista de archivos con su huella SHA-256. Informa lo AÑADIDO, lo ELIMINADO y lo MODIFICADO. Sale con código 1 si desapareció
algún endpoint, DTO, campo de DTO o miembro público de JavaScript (es decir, si se eliminó una función).
"""
import hashlib
import pathlib
import re
import sys

EXCLUIR = ('target', '.idea', '.git', 'dist', 'node_modules', '.claude')
RAIZ = pathlib.Path(__file__).resolve().parent.parent


def archivos(base):
    out = {}
    for p in sorted(base.rglob('*')):
        if not p.is_file():
            continue
        rel = p.relative_to(base).as_posix()
        partes = rel.split('/')
        if partes[0] in EXCLUIR or partes[0].startswith('target') or p.name == '.env' or p.suffix == '.zip' or p.name.endswith('.log') or partes[:2] == ['data', 'red-peatonal.bin.gz']:
            continue
        out[rel] = hashlib.sha256(p.read_bytes()).hexdigest()
    return out


def endpoints(base):
    res = set()
    carpeta = base / 'src' / 'main' / 'java'
    for f in carpeta.rglob('*Controller.java'):
        t = f.read_text(encoding='utf-8', errors='replace')
        m = re.search(r'@RequestMapping\(\s*"([^"]*)"', t)
        prefijo = m.group(1) if m else ''
        for tipo, args in re.findall(r'@(Get|Post|Put|Delete|Patch)Mapping(?:\(([^)]*)\))?', t):
            v = re.search(r'"([^"]*)"', args or '')
            res.add(f'{tipo.upper()} {prefijo}{v.group(1) if v else ""}')
    return res


def dtos(base):
    res = {}
    for f in (base / 'src' / 'main' / 'java').rglob('dto/*.java'):
        t = f.read_text(encoding='utf-8', errors='replace')
        for nombre, cuerpo in re.findall(r'record\s+(\w+)\s*\(([^)]*)\)', t):
            campos = tuple(re.sub(r'\s+', ' ', c.strip()).split(' ')[-1] for c in cuerpo.split(',') if c.strip())
            res[nombre] = campos
    return res


def js_publico(base):
    res = set()
    for f in (base / 'src' / 'main' / 'resources' / 'static' / 'js').glob('*.js'):
        t = f.read_text(encoding='utf-8', errors='replace')
        for m in re.finditer(r'\b(?:S|Spevb)\.([A-Za-z_]\w*)\s*=', t):
            res.add(f'{f.name}: Spevb.{m.group(1)}')
        for m in re.finditer(r'Object\.assign\((?:window\.Spevb[^,]*),\s*\{([^}]*)\}', t):
            for nombre in re.findall(r'\b([A-Za-z_]\w*)\b(?=\s*[,}:]|\s*$)', m.group(1)):
                res.add(f'{f.name}: Spevb.{nombre}')
        for m in re.finditer(r'\b(?:S|Spevb)\.(\w+)\s*=\s*\{([^;]*?)\};', t, re.S):
            for k in re.findall(r'(?:^|[,{\s])([A-Za-z_]\w*)\s*(?=[,:}(])', m.group(2)):
                res.add(f'{f.name}: Spevb.{m.group(1)}.{k}')
    return res


def main():
    if len(sys.argv) < 2:
        raise SystemExit(__doc__)
    antes = pathlib.Path(sys.argv[1]).resolve()
    salida = pathlib.Path(sys.argv[2]) if len(sys.argv) > 2 else None
    ahora = RAIZ
    L = []
    fa, fn = archivos(antes), archivos(ahora)
    ea, en = endpoints(antes), endpoints(ahora)
    da, dn = dtos(antes), dtos(ahora)
    ja, jn = js_publico(antes), js_publico(ahora)
    eliminado = []

    L.append('# Inventario de contratos públicos y cambios\n')
    L.append(f'Comparación de `{antes.name}` (entrega anterior) contra la versión actual. Generado por `scripts/inventario_contratos.py`.\n')
    L.append(f'## Endpoints HTTP ({len(en)} actuales; {len(ea)} antes)\n')
    L.append('```\n' + '\n'.join(sorted(en)) + '\n```\n')
    L.append(f'- **Eliminados:** {sorted(ea - en) or "ninguno"}\n- **Añadidos:** {sorted(en - ea) or "ninguno"}\n')
    eliminado += [f'endpoint {e}' for e in sorted(ea - en)]

    L.append(f'## DTO ({len(dn)} actuales; {len(da)} antes)\n')
    for n in sorted(dn):
        L.append(f'- `{n}`: {", ".join(dn[n])}')
    quitados = [n for n in da if n not in dn]
    perdidos = {n: [c for c in da[n] if c not in dn[n]] for n in da if n in dn and [c for c in da[n] if c not in dn[n]]}
    nuevos = {n: [c for c in dn[n] if c not in da[n]] for n in dn if n in da and [c for c in dn[n] if c not in da[n]]}
    L.append(f'\n- **DTO eliminados:** {quitados or "ninguno"}\n- **Campos eliminados:** {perdidos or "ninguno"}\n- **Campos añadidos:** {nuevos or "ninguno"}\n- **DTO nuevos:** {sorted(set(dn) - set(da)) or "ninguno"}\n')
    eliminado += [f'DTO {n}' for n in quitados] + [f'campo {n}.{c}' for n, cs in perdidos.items() for c in cs]

    L.append(f'## API pública de JavaScript (`Spevb.*`; {len(jn)} miembros actuales, {len(ja)} antes)\n')
    L.append(f'- **Eliminados:** {sorted(ja - jn) or "ninguno"}\n- **Añadidos:** {sorted(jn - ja) or "ninguno"}\n')
    eliminado += [f'JS {j}' for j in sorted(ja - jn)]

    añadidos = sorted(set(fn) - set(fa))
    quit_ = sorted(set(fa) - set(fn))
    modif = sorted(r for r in set(fa) & set(fn) if fa[r] != fn[r])
    L.append('## Archivos\n')
    L.append(f'Añadidos {len(añadidos)} · modificados {len(modif)} · eliminados {len(quit_)} (se excluyen `target/`, `.idea/`, `.claude/`, `.env`, ZIP y logs).\n')
    L.append('### Modificados\n' + '\n'.join(f'- `{r}`' for r in modif) + '\n')
    L.append('### Añadidos\n' + '\n'.join(f'- `{r}`' for r in añadidos) + '\n')
    L.append('### Eliminados\n' + ('\n'.join(f'- `{r}`' for r in quit_) if quit_ else '- ninguno') + '\n')
    L.append('## Veredicto\n')
    L.append('**Ninguna función pública fue eliminada** (endpoints, DTO, campos de DTO y API de JavaScript conservados).' if not eliminado
             else '**ATENCIÓN: faltan contratos:** ' + '; '.join(eliminado))
    texto = '\n'.join(L) + '\n'
    if salida:
        salida.write_text(texto, encoding='utf-8', newline='\n')
        print('escrito', salida)
    print(f'endpoints {len(ea)}->{len(en)} | DTO {len(da)}->{len(dn)} | JS {len(ja)}->{len(jn)} | archivos +{len(añadidos)} ~{len(modif)} -{len(quit_)}')
    print('contratos eliminados:', eliminado or 'ninguno')
    sys.exit(1 if eliminado else 0)


if __name__ == '__main__':
    main()
