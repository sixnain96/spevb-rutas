"""Uso: python e2e/rendimiento/perfil_jfr.py archivo.jfr   (se graba con: jcmd <pid> JFR.start settings=profile filename=x.jfr  y  jcmd <pid> JFR.dump)
Resume un .jfr: tiempo (muestras) por método propio, de forma inclusiva (quien llama) y por función hoja."""
import re, subprocess, sys, collections
import os, shutil
jfr = shutil.which("jfr") or os.path.join(os.environ.get("JAVA_HOME", ""), "bin", "jfr")
archivo = sys.argv[1]
evento = sys.argv[2] if len(sys.argv) > 2 else "jdk.ExecutionSample"
salida = subprocess.run([jfr, "print", "--events", evento, "--stack-depth", "40", archivo], capture_output=True, text=True, encoding="utf-8", errors="replace").stdout
bloques = salida.split(evento + " {")[1:]
inclusivo = collections.Counter(); hoja = collections.Counter(); total = 0
for b in bloques:
    marcos = re.findall(r"^\s+([\w.$<>]+)\(.*?\)\s+line:", b, re.M)
    if not marcos: continue
    # solo hilos que atienden /api/viajes (cualquier marco de ViajeService)
    if not any("ViajeService" in m or "ViajeController" in m for m in marcos): continue
    total += 1
    hoja[marcos[0]] += 1
    vistos = set()
    for m in marcos:
        if m.startswith("co.edu.usta") and m not in vistos:
            vistos.add(m); inclusivo[m] += 1
print("muestras en /api/viajes:", total)
print("\n== inclusivo (código propio) ==")
for m, c in inclusivo.most_common(22): print(f"{100*c/total:5.1f}%  {m}")
print("\n== función hoja (donde se gasta el CPU) ==")
for m, c in hoja.most_common(14): print(f"{100*c/total:5.1f}%  {m}")
