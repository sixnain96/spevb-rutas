"""Escanea un SBOM CycloneDX (target/bom.json) contra la API oficial de OSV (https://api.osv.dev), sin Docker ni instalar nada.

Uso:  python -I scripts/osv_scan.py [target/bom.json]
Consulta cada componente (por su purl) con /v1/querybatch: la API devuelve solo las vulnerabilidades que afectan a ESA versión.
Sale con código 1 si hay hallazgos y 2 si no se pudo consultar (sin red): nunca da por limpio algo que no pudo comprobar.
Equivalente nativo: `osv-scanner --sbom=target/bom.json` si tienes osv-scanner instalado.
"""
import json
import pathlib
import sys
import urllib.error
import urllib.request

API = 'https://api.osv.dev/v1'


def post(ruta, cuerpo):
    req = urllib.request.Request(API + ruta, data=json.dumps(cuerpo).encode(), headers={'Content-Type': 'application/json'})
    with urllib.request.urlopen(req, timeout=60) as r:
        return json.load(r)


def main():
    ruta = pathlib.Path(sys.argv[1] if len(sys.argv) > 1 else 'target/bom.json')
    bom = json.loads(ruta.read_text(encoding='utf-8'))
    comps = [c for c in bom.get('components', []) if c.get('purl')]
    print(f'SBOM {bom.get("bomFormat")} {bom.get("specVersion")}: {len(comps)} componentes con purl')
    try:
        hallazgos = {}
        for i in range(0, len(comps), 100):
            lote = comps[i:i + 100]
            res = post('/querybatch', {'queries': [{'package': {'purl': c['purl']}} for c in lote]})
            for c, r in zip(lote, res.get('results', [])):
                ids = [v['id'] for v in r.get('vulns', [])]
                if ids:
                    hallazgos[f"{c['name']} {c['version']}"] = ids
    except (urllib.error.URLError, TimeoutError) as e:
        print('NO SE PUDO CONSULTAR OSV:', e)
        sys.exit(2)
    for nombre, ids in sorted(hallazgos.items()):
        print('VULNERABLE', nombre, ids)
    clave = {c['name']: c['version'] for c in comps}
    for n in ('tomcat-embed-core', 'tomcat-embed-el', 'tomcat-embed-websocket', 'jackson-core', 'jackson-databind'):
        print(f'  {n} {clave.get(n, "?")}:', 'CON hallazgos' if f'{n} {clave.get(n)}' in hallazgos else 'sin hallazgos conocidos')
    print(f'Resultado: {len(hallazgos)} componente(s) con vulnerabilidades conocidas de {len(comps)}.')
    sys.exit(1 if hallazgos else 0)


if __name__ == '__main__':
    main()
