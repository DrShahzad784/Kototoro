#!/usr/bin/env python3
"""Serve the pinned, unconverted APK for the device-side installation UI regression.

Run stage-mihon-preview.py first. This server only binds to loopback and writes generated files under build.
"""
import json
from functools import partial
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
import shutil

APP = Path(__file__).resolve().parent.parent


def main():
    apk = APP / "build/jvm-work/zaimanhua.apk"
    if not apk.is_file():
        raise SystemExit("Run iosApp/Scripts/stage-mihon-preview.py first")
    root = APP / "build/product-repository"
    (root / "apk").mkdir(parents=True, exist_ok=True)
    shutil.copy2(apk, root / "apk/zaimanhua.apk")
    index = [{
        "name": "Mihon: 再漫画", "pkg": "eu.kanade.tachiyomi.extension.zh.zaimanhua",
        "version": "1.6.0", "code": 106000, "apk": "zaimanhua.apk", "nsfw": 0,
        "sources": [{"id": "524579092615598717", "name": "再漫画", "lang": "zh"}],
    }]
    (root / "index.min.json").write_text(json.dumps(index, ensure_ascii=False), encoding="utf-8")
    server = ThreadingHTTPServer(("127.0.0.1", 8766), partial(SimpleHTTPRequestHandler, directory=str(root)))
    print("Repository fixture: http://127.0.0.1:8766/index.min.json", flush=True)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()


if __name__ == "__main__":
    main()
