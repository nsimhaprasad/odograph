#!/usr/bin/env python3
"""Serves the APK over GET and collects crash reports over POST.

Bring-up tool: the box has no ADB and therefore no logcat, so the app posts its own
uncaught-exception traces here instead.
"""
import datetime, http.server, os, socketserver, sys

PORT = 8000
ROOT = os.path.dirname(os.path.abspath(__file__))
REPORTS = os.path.join(ROOT, "reports")
os.makedirs(REPORTS, exist_ok=True)


class Handler(http.server.SimpleHTTPRequestHandler):
    def __init__(self, *a, **kw):
        super().__init__(*a, directory=ROOT, **kw)

    def do_POST(self):
        length = int(self.headers.get("Content-Length", 0))
        body = self.rfile.read(length).decode("utf-8", "replace")
        stamp = datetime.datetime.now().strftime("%Y%m%d-%H%M%S-%f")
        name = "crash" if self.path.rstrip("/").endswith("crash") else "trail"
        path = os.path.join(REPORTS, f"{name}-{stamp}.txt")
        with open(path, "w") as f:
            f.write(body)
        print(f"\n===== {name.upper()} RECEIVED {stamp} =====", flush=True)
        print(body[:4000], flush=True)
        print("===== end =====\n", flush=True)
        self.send_response(200)
        self.send_header("Content-Length", "2")
        self.end_headers()
        self.wfile.write(b"ok")

    def log_message(self, fmt, *args):
        sys.stderr.write("%s - %s\n" % (self.address_string(), fmt % args))


socketserver.TCPServer.allow_reuse_address = True
with socketserver.TCPServer(("0.0.0.0", PORT), Handler) as httpd:
    print(f"collector on :{PORT}, reports -> {REPORTS}", flush=True)
    httpd.serve_forever()
