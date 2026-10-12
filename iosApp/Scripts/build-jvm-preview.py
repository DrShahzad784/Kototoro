#!/usr/bin/env python3
"""Build an arm64 iOS preview in ignored build directories; no global installation or Git operations."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import tarfile
import zipfile

APP = Path(__file__).resolve().parent.parent
ROOT = APP.parent
PINS = json.loads((APP / "Scripts/jvm-preview-pins.json").read_text())
WORK = APP / "build/jvm-work"
OUTPUT = APP / "build/jvm"
TOOLS = APP / "build/tools"
WORK.mkdir(parents=True, exist_ok=True)
OUTPUT.mkdir(parents=True, exist_ok=True)


def run(arguments, cwd=None, env=None):
    print("+", " ".join(str(arg) for arg in arguments), flush=True)
    subprocess.run([str(arg) for arg in arguments], cwd=cwd, env=env, check=True)


def download(name):
    url, expected = PINS["downloads"][name]
    path = WORK / name
    if not path.exists():
        temporary = path.with_suffix(path.suffix + ".download")
        run(["curl", "--fail", "--location", "--retry", "3", "--output", temporary, url])
        temporary.replace(path)
    actual = hashlib.sha256(path.read_bytes()).hexdigest()
    if actual != expected:
        raise RuntimeError("SHA-256 mismatch: " + str(path))
    return path


def extract(archive):
    with tarfile.open(archive) as source:
        members = source.getmembers()
        for member in members:
            target = (WORK / member.name).resolve()
            if not target.is_relative_to(WORK):
                raise RuntimeError("Unsafe archive path: " + member.name)
            if member.issym() or member.islnk():
                link = (target.parent / member.linkname).resolve() if member.issym() else (WORK / member.linkname).resolve()
                if not link.is_relative_to(WORK):
                    raise RuntimeError("Unsafe archive link: " + member.name)
        source.extractall(WORK)
        return WORK / members[0].name.split("/")[0]


def build_tool(name, archive_name, env):
    if (TOOLS / "bin" / name).exists():
        return
    source = extract(download(archive_name))
    run(["./configure", "--prefix=" + str(TOOLS)], cwd=source, env=env)
    run(["make", "-j4"], cwd=source, env=env)
    run(["make", "install"], cwd=source, env=env)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--platform", choices=["iphonesimulator", "iphoneos"], default="iphonesimulator")
    parser.add_argument("--profile", type=Path, help="Reuse an already staged runtime's Java libraries and sources")
    arguments = parser.parse_args()
    simulator = arguments.platform == "iphonesimulator"
    output = OUTPUT if simulator else APP / "build/jvm-device"
    output.mkdir(parents=True, exist_ok=True)
    profile = arguments.profile.resolve() if arguments.profile else None
    if profile:
        for required in ["java_bundle/lib/modules", "lib", "sources/config.json"]:
            if not (profile / required).exists():
                raise RuntimeError("Missing runtime profile artifact: " + str(profile / required))
    if os.uname().sysname != "Darwin" or os.uname().machine != "arm64":
        raise RuntimeError("An Apple Silicon Mac with Xcode is required")
    java = os.environ.get("KOTOTORO_JVM_BOOT_JDK") or subprocess.check_output(
        ["/usr/libexec/java_home", "-v", "24"], text=True).strip()
    version = subprocess.run([str(Path(java) / "bin/java"), "-version"], capture_output=True, text=True, check=True)
    if 'version "24.' not in version.stderr:
        raise RuntimeError("KOTOTORO_JVM_BOOT_JDK must point to JDK 24")
    env = dict(os.environ, JAVA_HOME=java, PATH=str(TOOLS / "bin") + ":" + java + "/bin:" + os.environ["PATH"])
    build_tool("m4", "m4.tar.xz", env)
    build_tool("autoconf", "autoconf.tar.xz", dict(env, M4=str(TOOLS / "bin/m4")))

    # Keep each patched source tree, including licenses, for incremental builds and inspection.
    upstream_archive = download("upstream.tar.gz")
    upstream = WORK / ("TachiyomiAzIOS-" + PINS["upstreamRevision"])
    if not upstream.exists():
        extract(upstream_archive)
    mobile_archive = download("mobile.tar.gz")
    mobile = WORK / ("mobile-" + PINS["openjdkRevision"])
    patch_script = download("simulator-patch.sh").read_text()
    stamp = mobile / ".kototoro-simulator-patched"
    patch_version = PINS["upstreamRevision"] + ":" + PINS["simulatorPatchRevision"]
    if not stamp.exists():
        if mobile.exists():
            raise RuntimeError("Unstamped source tree exists; inspect it before rebuilding: " + str(mobile))
        extract(mobile_archive)
        run(["patch", "-p1", "--forward", "-i", upstream / "Scripts/patches/openjdk-mobile-ios-runtime.patch"], cwd=mobile)
        # Apply only the two verified Python patch blocks. Do not run the upstream reset/clean/repackaging script.
        for marker, target in [("PY", "src/hotspot/os/bsd/os_bsd.cpp"),
                               ("WXPY", "src/hotspot/os_cpu/bsd_zero/os_bsd_zero.cpp")]:
            code = patch_script.split("<<'" + marker + "'\n", 1)[1].split("\n" + marker + "\n", 1)[0]
            subprocess.run(["python3", "-", str(mobile / target)], input=code, text=True, check=True)
        keeper = WORK / "symbol_keeper-patched.cpp"
        shutil.copy2(download("symbol_keeper.cpp"), keeper)
        run(["bash", upstream / "Scripts/add-openjdk-static-lookup.sh", keeper, upstream / "iOS/JVM/OpenJDK.exports"])
        shutil.copy2(keeper, mobile / "src/hotspot/os/bsd/symbol_keeper.cpp")
        stamp.write_text(patch_version)
    if stamp.read_text() != patch_version:
        raise RuntimeError("Source patch revision differs from the current pins")

    support = WORK / ("libffi" if simulator else "libffi-device")
    with zipfile.ZipFile(download("libffi.zip" if simulator else "libffi-device.zip")) as archive:
        archive.extractall(support)
    ffi = WORK / ("libffi-arm64.a" if simulator else "libffi-device-arm64.a")
    if simulator:
        run(["xcrun", "lipo", "-thin", "arm64", support / "libffi.a", "-output", ffi])
    else:
        shutil.copy2(support / "libffi.a", ffi)
    sdk = subprocess.check_output(["xcrun", "--sdk", arguments.platform, "--show-sdk-path"], text=True).strip()
    mac_sdk = subprocess.check_output(["xcrun", "--sdk", "macosx", "--show-sdk-path"], text=True).strip()
    conf = "iossim-aarch64-zero-release" if simulator else "ios-aarch64-zero-release"
    flags = ("-target arm64-apple-ios17.0-simulator -mios-simulator-version-min=17.0" if simulator else
             "-target arm64-apple-ios17.0 -miphoneos-version-min=17.0")
    run(["bash", "configure", "--with-jobs=4", "--with-conf-name=" + conf, "--disable-warnings-as-errors",
         "--openjdk-target=aarch64-macos-ios", "--with-sysroot=" + sdk,
         "--with-libffi-include=" + str(support / "include/ffi"), "--with-libffi-lib=" + str(support),
         "--with-extra-cflags=" + flags, "--with-extra-cxxflags=" + flags, "--with-extra-ldflags=" + flags,
         "--with-cups-include=" + mac_sdk + "/usr/include"], cwd=mobile, env=env)
    run(["make", "LOG=info", "CONF=" + conf, "static-libs-image"], cwd=mobile, env=env)
    build = mobile / "build" / conf
    libraries = build / "images/static-libs/lib"
    run(["libtool", "-static", "-o", output / "libkototoro-jvm.a", libraries / "zero/libjvm.a", ffi] +
        [libraries / name for name in ["libverify.a", "libjava.a", "libzip.a", "libnet.a", "libnio.a", "libjimage.a"]])
    shutil.copytree(build / "jdk/include", output / "include", dirs_exist_ok=True)
    shutil.copytree(build / "jdk/include/ios", output / "include", dirs_exist_ok=True)

    if profile:
        for name in ["java_bundle", "lib", "sources"]:
            shutil.copytree(profile / name, output / name, dirs_exist_ok=True)
        if (profile / "boot-shims.jar").exists():
            shutil.copy2(profile / "boot-shims.jar", output / "boot-shims.jar")
        print(arguments.platform + " JVM preview ready:", output, flush=True)
        return

    # The pinned release has the matching Java 26 module image. Copy its module data and legal notices only.
    with zipfile.ZipFile(download("runtime.ipa")) as archive:
        for name in archive.namelist():
            if "/java_bundle/" not in name or name.endswith("/"):
                continue
            suffix = name.split("/java_bundle/", 1)[1]
            target = output / "java_bundle" / suffix
            if not target.resolve().is_relative_to(output):
                raise RuntimeError("Unsafe runtime path")
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(archive.read(name))
    run([ROOT / "gradlew", ":source-host:installDist", ":source-host:exportEmbeddedFixture", "--no-daemon"], cwd=ROOT, env=env)
    shutil.copytree(ROOT / "source-host/build/install/source-host/lib", output / "lib", dirs_exist_ok=True)
    fixture = ROOT / "source-host/build/embedded-fixture"
    shutil.copy2(fixture / "fixture-api.jar", output / "lib/fixture-api.jar")
    (output / "sources").mkdir(exist_ok=True)
    for name in ["runtime.jar", "config.json"]:
        shutil.copy2(fixture / name, output / "sources" / name)
    (output / "sources/preview.json").write_text(json.dumps({"label": "独立测试源，仅用于运行时验证"}, ensure_ascii=False))
    print(arguments.platform + " JVM preview ready:", output, flush=True)


if __name__ == "__main__":
    main()
