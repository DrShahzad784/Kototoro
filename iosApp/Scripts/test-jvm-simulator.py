#!/usr/bin/env python3
"""Run the C/JNI smoke fixture on the real simulator's Zero VM, without UI or network dependencies."""
import argparse
from pathlib import Path
import subprocess

APP = Path(__file__).resolve().parent.parent
ROOT = APP.parent


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--device", default="booted")
    parser.add_argument("--shims", action="store_true", help="Test the real-extension profile's Apple native operations")
    args = parser.parse_args()
    runtime = APP / ("build/jvm-real-api" if args.shims else "build/jvm")
    fixture = ROOT / "source-host/build/embedded-fixture"
    executable = APP / ("build/ios-shims-simulator-smoke" if args.shims else "build/jvm-simulator-smoke")
    compile_command = ["xcrun", "--sdk", "iphonesimulator", "clang++", "-std=c++17",
                    "-target", "arm64-apple-ios17.0-simulator", "-DKTR_HAS_JVM=1", "-Wall", "-Wextra", "-Werror",
                    "-Wl,-no_exported_symbols",
                    "-Wno-deprecated-declarations", "-I" + str(APP / "Native/include"),
                    "-I" + str(runtime / "include"), str(APP / "Native/KototoroJVM.cpp"),
                    str(APP / ("Native/tests/IOSShimsSmoke.cpp" if args.shims else "Native/tests/JVMBridgeSmoke.cpp")), str(runtime / "libkototoro-jvm.a"),
                    "-lz", "-liconv", "-framework", "Foundation", "-framework", "Security", "-framework", "SystemConfiguration",
                    "-o", str(executable)]
    if args.shims:
        compile_command += ["-fobjc-arc", "-DKTR_HAS_NATIVE_SHIMS=1", str(APP / "Native/KototoroNative.mm"),
                            "-framework", "ImageIO", "-framework", "CoreGraphics", "-framework", "CoreText",
                            "-framework", "JavaScriptCore"]
    subprocess.run(compile_command, check=True)
    # Apple Silicon ld already signs the executable; retain a valid signature.
    verification = subprocess.run(["codesign", "--verify", str(executable)], capture_output=True)
    if verification.returncode != 0:
        subprocess.run(["codesign", "--force", "--sign", "-", str(executable)], check=True)
    classpath = ":".join(str(p) for p in sorted((runtime / "lib").glob("*.jar")))
    result = subprocess.run(["xcrun", "simctl", "spawn", args.device, str(executable),
                             str(runtime / "java_bundle"), classpath, str(fixture / "config.json"), str(fixture)],
                            text=True, capture_output=True, timeout=30)
    print(result.stdout, end="")
    print(result.stderr, end="")
    expected = "KOTOTORO_IOS_SHIMS_OK" if args.shims else "KOTOTORO_JNI_SMOKE_OK"
    if result.returncode != 0 or expected not in result.stdout:
        raise SystemExit("Simulator JVM smoke failed")


if __name__ == "__main__":
    main()
