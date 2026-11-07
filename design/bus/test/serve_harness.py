"""Sirve src/main/resources/static (solo lectura) en 127.0.0.1:8767 y expone design/bus/test/harness.html como /__harness.html.
Uso:  python design/bus/test/serve_harness.py   (desde la raíz del proyecto)"""
import http.server
import os

ROOT = os.path.dirname(os.path.abspath(__file__))
STATIC = os.path.abspath(os.path.join(ROOT, "..", "..", "..", "src", "main", "resources", "static"))


class H(http.server.SimpleHTTPRequestHandler):
    def translate_path(self, path):
        p = path.split("?", 1)[0]
        if p == "/__harness.html":
            return os.path.join(ROOT, "harness.html")
        return os.path.join(STATIC, p.lstrip("/").replace("/", os.sep))

    def end_headers(self):
        self.send_header("Cache-Control", "no-store")
        super().end_headers()


http.server.ThreadingHTTPServer(("127.0.0.1", 8767), H).serve_forever()
