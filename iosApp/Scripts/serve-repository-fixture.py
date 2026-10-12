#!/usr/bin/env python3
"""Serve the pinned, unconverted APK for the device-side installation UI regression.

Run stage-mihon-preview.py first. This server only binds to loopback and writes generated files under build.
"""
import json
from functools import partial
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
import shutil
from zipfile import ZipFile

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
    # The pinned APK's 192px launcher icon; serve the actual artwork for icon-loading UI coverage.
    with ZipFile(apk) as archive:
        icon = archive.read("res/o-.png")
    icons = root / "icon"
    icons.mkdir(exist_ok=True)
    (icons / "eu.kanade.tachiyomi.extension.zh.zaimanhua.png").write_bytes(icon)
    # Catalog-only fixtures exercise native grouping/search without downloading or loading a fake extension.
    ui = root / "ui"
    ui.mkdir(exist_ok=True)
    (ui / "icon").mkdir(exist_ok=True)
    (ui / "icon/fixture.reader.multi.png").write_bytes(icon)
    entries = [
        {"name": "书源组合", "pkg": "fixture.reader.multi", "version": "1.2.0", "code": 12,
         "apk": "unused.apk", "nsfw": 0,
         "sources": [{"id": "1", "name": "中文漫画", "lang": "zh"},
                     {"id": "2", "name": "English Comics", "lang": "en"}]},
        {"name": "漫画花园", "pkg": "fixture.reader.zh", "version": "1.0.0", "code": 10,
         "apk": "unused.apk", "nsfw": 0, "sources": [{"id": "3", "name": "漫画花园", "lang": "zh"}]},
        {"name": "Comic Library", "pkg": "fixture.reader.en", "version": "2.0.0", "code": 20,
         "apk": "unused.apk", "nsfw": 0, "sources": [{"id": "4", "name": "Comic Library", "lang": "en"}]},
        {"name": "成人来源样本", "pkg": "fixture.reader.adult", "version": "1.0.0", "code": 10,
         "apk": "unused.apk", "nsfw": 1, "sources": [{"id": "5", "name": "成人来源样本", "lang": "zh"}]},
    ]
    (ui / "index.min.json").write_text(json.dumps(entries, ensure_ascii=False), encoding="utf-8")
    header = {"meta": {"name": "SwiftUI 体验仓库", "shortName": "UI", "website": "http://127.0.0.1:8766/ui/"},
              "index_v2": "index.min.json"}
    (ui / "repo.json").write_text(json.dumps(header, ensure_ascii=False), encoding="utf-8")
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
