#!/usr/bin/env python3
"""Test Kototoro's iOS JNI operations against real Apple frameworks on macOS."""
import os
from pathlib import Path
import subprocess

APP = Path(__file__).resolve().parent.parent
ROOT = APP.parent


def main():
    java = Path(os.environ.get("KOTOTORO_JVM_BOOT_JDK") or subprocess.check_output(
        ["/usr/libexec/java_home", "-v", "24"], text=True).strip())
    library = APP / "build/libkototoro-native-tests.dylib"
    library.parent.mkdir(parents=True, exist_ok=True)
    subprocess.run(["clang++", "-std=c++17", "-fobjc-arc", "-dynamiclib", "-DKTR_HAS_JVM=1",
                    "-DKTR_NATIVE_TEST_LIBRARY=1", "-Wall", "-Wextra", "-Werror", "-I" + str(java / "include"),
                    "-I" + str(java / "include/darwin"), str(APP / "Native/KototoroNative.mm"),
                    "-framework", "Foundation", "-framework", "CoreGraphics", "-framework", "CoreText",
                    "-framework", "ImageIO", "-framework", "JavaScriptCore", "-o", str(library)], check=True)
    subprocess.run([str(ROOT / "gradlew"), ":mihon-ios-shims:test", ":mihon-ios-compat:test",
                    "-PwithMihonIosCompat", "-PmihonIosCompatibilityDirectory=" + str(APP / "build/mihon-ios-api"),
                    "-PnativeIosLibrary=" + str(library), "--console=plain", "--no-daemon"],
                   cwd=ROOT, env=dict(os.environ, JAVA_HOME=str(java)), check=True)


if __name__ == "__main__":
    main()
