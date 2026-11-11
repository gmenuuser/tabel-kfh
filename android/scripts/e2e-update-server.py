#!/usr/bin/env python3
"""A stand-in farm server for the self-update's end-to-end check on an emulator (never used in production).

    python android/scripts/e2e-update-server.py <dir-with-tabel-kfh.apk> <versionCode> [port=8765]

Serves the farm's endpoints a debug build needs to sign in (anna / secret, owner) with one employee record, and
`/android/version.json` + `/android/tabel-kfh.apk` (with `Range`) from <dir>. The emulator reaches the host as
10.0.2.2: `adb shell setprop debug.kfh.base_url http://10.0.2.2:8765` before the debug app starts.
"""
import json
import os
import re
import sys
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

DIR = os.path.abspath(sys.argv[1])
CODE = int(sys.argv[2])
NAME = f"1.0.{CODE}"
PORT = int(sys.argv[3]) if len(sys.argv) > 3 else 8765
APK = os.path.join(DIR, "tabel-kfh.apk")
print(f"serving {APK} as versionCode={CODE} versionName={NAME} on port {PORT}", flush=True)

ME = {"userId": "u1", "role": "owner",
      "farm": {"id": "f1", "name": "КФХ Зубахин", "region": "Приаргунский район", "managerName": "Зубахин А. В.", "timezone": "Asia/Chita"}}
EMPLOYEE = {"entity": "employee", "id": "e-e2e-1", "version": 1, "deleted": False,
            "data": {"lastName": "Проверочный", "firstName": "Сотрудник", "patronymic": "", "position": "тракторист",
                     "hireDate": "2026-01-10T00:00:00.000Z", "isArchived": False}}


class H(BaseHTTPRequestHandler):
    def log_message(self, fmt, *args):
        sys.stdout.write("%s %s\n" % (self.command, self.path)); sys.stdout.flush()

    def send_json(self, obj, code=200):
        body = json.dumps(obj, ensure_ascii=False).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_POST(self):
        n = int(self.headers.get("Content-Length") or 0)
        body = self.rfile.read(n).decode("utf-8", "replace")
        if self.path == "/auth/login":
            if '"password":"secret"' in body.replace(" ", ""):
                return self.send_json({"token": "tok-e2e", "role": "owner"})
            return self.send_json({"error": "Неверный логин или пароль"}, 401)
        if self.path == "/sync/push":
            try:
                ops = json.loads(body).get("ops", [])
            except Exception:
                ops = []
            return self.send_json({"results": [{"opId": o.get("opId"), "status": "applied", "version": 2} for o in ops]})
        if self.path == "/auth/logout":
            return self.send_json({"ok": True})
        return self.send_json({"error": "Не найдено"}, 404)

    def do_GET(self):
        p = self.path.split("?")[0]
        if p == "/me":
            return self.send_json(ME)
        if p == "/sync/pull":
            since = int((re.search(r"since=(\d+)", self.path) or [None, "0"])[1])
            changes = [EMPLOYEE] if since < 1 else []
            return self.send_json({"changes": changes, "next": 1, "more": False})
        if p == "/conflicts":
            return self.send_json({"conflicts": []})
        if p in ("/members", "/devices", "/backups"):
            return self.send_json([])
        if p == "/android/version.json":
            return self.send_json({"versionCode": CODE, "versionName": NAME, "url": f"http://10.0.2.2:{PORT}/android/tabel-kfh.apk"})
        if p == "/android/tabel-kfh.apk":
            return self.send_file(APK)
        return self.send_json({"error": "Не найдено"}, 404)

    def send_file(self, path):
        size = os.path.getsize(path)
        start, end = 0, size - 1
        rng = self.headers.get("Range")
        m = re.match(r"bytes=(\d+)-(\d*)", rng or "")
        if m:
            start = int(m.group(1))
            if start >= size:
                self.send_response(416); self.send_header("Content-Range", f"bytes */{size}"); self.end_headers(); return
            self.send_response(206)
            self.send_header("Content-Range", f"bytes {start}-{end}/{size}")
        else:
            self.send_response(200)
        self.send_header("Content-Type", "application/vnd.android.package-archive")
        self.send_header("Accept-Ranges", "bytes")
        self.send_header("Content-Length", str(end - start + 1))
        self.end_headers()
        with open(path, "rb") as f:
            f.seek(start)
            left = end - start + 1
            while left > 0:
                chunk = f.read(min(65536, left))
                if not chunk:
                    break
                self.wfile.write(chunk)
                left -= len(chunk)


ThreadingHTTPServer(("127.0.0.1", PORT), H).serve_forever()
