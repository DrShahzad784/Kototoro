#!/usr/bin/env python3
"""Stage a separate real-APK simulator profile using the pinned Mobile APIs and the shared converter."""
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import zipfile
from importlib.util import module_from_spec, spec_from_file_location

APP = Path(__file__).resolve().parent.parent
ROOT = APP.parent
SOURCE = APP / "build/jvm"
TARGET = APP / "build/jvm-real-api"
APK_URL = "https://github.com/keiyoushi/extensions/releases/download/24bc6c2/tachiyomi-zh.zaimanhua-v1.6.0.apk"
APK_SHA = "f6605e36a7d1453b60814bb6f7340a8f724183b11ee3f95e02d9542753ba81fe"


def main():
    if not (SOURCE / "libkototoro-jvm.a").is_file():
        raise RuntimeError("Run build-jvm-preview.py first")
    spec = spec_from_file_location("jvm_preview", APP / "Scripts/build-jvm-preview.py")
    builder = module_from_spec(spec)
    spec.loader.exec_module(builder)
    compatibility = APP / "build/mihon-ios-api"
    compatibility.mkdir(parents=True, exist_ok=True)
    TARGET.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(builder.download("runtime.ipa")) as archive:
        for name in archive.namelist():
            if "/tachiaz-compat/" in name and name.endswith(".jar"):
                (compatibility / name.rsplit("/", 1)[1]).write_bytes(archive.read(name))
        (TARGET / "boot-shims.jar").write_bytes(archive.read("Payload/Aidoku.app/tachiaz-mobile-shims.jar"))
    apk = APP / "build/jvm-work/zaimanhua.apk"
    if not apk.exists():
        builder.run(["curl", "--fail", "--location", "--retry", "3", "--output", apk, APK_URL])
    if hashlib.sha256(apk.read_bytes()).hexdigest() != APK_SHA:
        raise RuntimeError("Real extension APK SHA-256 mismatch")
    sources = TARGET / "sources"
    sources.mkdir(exist_ok=True)
    jar = sources / "zaimanhua-converted.jar"
    env = dict(os.environ)
    env["JAVA_HOME"] = os.environ.get("KOTOTORO_JVM_BOOT_JDK") or subprocess.check_output(
        ["/usr/libexec/java_home", "-v", "24"], text=True).strip()
    builder.run([ROOT / "gradlew", ":source-host:installDist", ":mihon-ios-compat:stagePreview", ":dex-convert:convertApk",
                 "-PwithMihonIosCompat", "-PmihonIosCompatibilityDirectory=" + str(compatibility),
                 "-PextensionApk=" + str(apk), "-PextensionJar=" + str(jar), "--no-daemon"], cwd=ROOT, env=env)
    for name in ["java_bundle", "include"]:
        shutil.copytree(SOURCE / name, TARGET / name, dirs_exist_ok=True)
    shutil.copy2(SOURCE / "libkototoro-jvm.a", TARGET / "libkototoro-jvm.a")
    lib = TARGET / "lib"
    lib.mkdir(exist_ok=True)
    for index, name in enumerate(["000-tachiaz-mobile-compat-shims.jar", "AndroidCompat-1.0.jar", "server-1.0.jar",
                                  "android-jar-1.0.0.jar"]):
        shutil.copy2(compatibility / name, lib / (str(index).zfill(2) + "-" + name))
    shutil.copy2(SOURCE / "ios-adapter/mihon-ios-compat.jar", lib / "05-mihon-ios-compat.jar")
    for item in (ROOT / "source-host/build/install/source-host/lib").glob("*.jar"):
        if item.name != "fixture-api.jar":
            shutil.copy2(item, lib / ("10-" + item.name))
    for item in compatibility.glob("*.jar"):
        if not item.name.startswith(("000-", "AndroidCompat", "server-", "android-jar", "kotlin-stdlib",
                                     "kotlinx-coroutines-core-jvm", "kotlinx-serialization-core-jvm",
                                     "kotlinx-serialization-json-jvm")):
            shutil.copy2(item, lib / ("20-" + item.name))
    config = {
        "platformClass": "org.skepsun.kototoro.ios.compat.MihonIosPlatform",
        "jars": [{"path": jar.name, "identity": {
            "packageName": "eu.kanade.tachiyomi.extension.zh.zaimanhua", "versionCode": 106000,
            "sha256": hashlib.sha256(jar.read_bytes()).hexdigest()}}],
        "imageDirectory": "images", "preferenceDirectory": "prefs",
    }
    (sources / "config.json").write_text(json.dumps(config))
    (sources / "preview.json").write_text(json.dumps({"label": "再漫画 APK 兼容性验证"}, ensure_ascii=False))
    # Inspectable digest inventory of the generated preview; not a production distribution manifest.
    inventory = {str(item.relative_to(TARGET)): hashlib.sha256(item.read_bytes()).hexdigest()
                 for item in TARGET.rglob("*") if item.is_file() and item.name != "inventory.json"}
    (TARGET / "inventory.json").write_text(json.dumps(inventory, indent=4))
    print("Real APK simulator profile ready:", TARGET)


if __name__ == "__main__":
    main()
