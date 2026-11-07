"""Servidor de pruebas: sirve src/main/resources/static (lo que hay en disco, sin compilar) en 127.0.0.1:8768 y reenvía /api/* a la app
que ya esté corriendo en 127.0.0.1:8080. Solo para probar cambios de frontend; no forma parte de la aplicación.
Uso (desde la raíz del proyecto):  python design/tools/serve_static_proxy.py"""
import http.server
import os
import urllib.request

ROOT = os.path.dirname(os.path.abspath(__file__))
STATIC = os.path.abspath(os.path.join(ROOT, "..", "..", "src", "main", "resources", "static"))
API = os.environ.get("SEVB_API", "http://127.0.0.1:8080")      # app a la que se reenvía /api (SEVB_API para cambiarla)


class H(http.server.SimpleHTTPRequestHandler):
    def translate_path(self, path):
        p = path.split("?", 1)[0]
        if p == "/__gps_harness.html":                      # arnés del GPS (design/gps/)
            return os.path.join(ROOT, "..", "gps", "gps_harness.html")
        return os.path.join(STATIC, p.lstrip("/").replace("/", os.sep))

    def end_headers(self):
        self.send_header("Cache-Control", "no-store")
        super().end_headers()

    def _proxy(self):
        n = int(self.headers.get("Content-Length") or 0)
        body = self.rfile.read(n) if n else None
        req = urllib.request.Request(API + self.path, data=body, method=self.command,
                                     headers={k: v for k, v in self.headers.items() if k.lower() in ("content-type", "accept")})
        try:
            r = urllib.request.urlopen(req, timeout=60)
        except urllib.error.HTTPError as e:
            r = e
        data = r.read()
        self.send_response(r.status if hasattr(r, "status") else r.code)
        for k in ("Content-Type",):
            if r.headers.get(k):
                self.send_header(k, r.headers[k])
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def do_GET(self):
        self._proxy() if self.path.startswith("/api/") else super().do_GET()

    def do_POST(self):
        self._proxy()


http.server.ThreadingHTTPServer(("127.0.0.1", 8768), H).serve_forever()
