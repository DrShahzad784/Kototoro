#!/usr/bin/env python3
"""Exercise the same C/JNI transport against a macOS JVM before the simulator UI smoke test."""
import os
from pathlib import Path
import subprocess

APP = Path(__file__).resolve().parent.parent
ROOT = APP.parent


def main():
    java = os.environ.get("KOTOTORO_JVM_BOOT_JDK") or subprocess.check_output(
        ["/usr/libexec/java_home", "-v", "24"], text=True).strip()
    subprocess.run([str(ROOT / "gradlew"), ":source-host:installDist", ":source-host:exportEmbeddedFixture",
                    "--no-daemon"], cwd=ROOT, env=dict(os.environ, JAVA_HOME=java), check=True)
    executable = APP / "build/jni-smoke"
    subprocess.run(["clang++", "-std=c++17", "-DKTR_HAS_JVM=1", "-Wall", "-Wextra", "-Werror",
                    "-Wno-deprecated-declarations", "-I" + str(APP / "Native/include"),
                    "-I" + java + "/include", "-I" + java + "/include/darwin",
                    str(APP / "Native/KototoroJVM.cpp"), str(APP / "Native/tests/JVMBridgeSmoke.cpp"),
                    "-L" + java + "/lib/server", "-Wl,-rpath," + java + "/lib/server", "-ljvm", "-o", str(executable)],
                   cwd=ROOT, check=True)
    fixture = ROOT / "source-host/build/embedded-fixture"
    classpath = ":".join(str(item) for item in sorted((ROOT / "source-host/build/install/source-host/lib").glob("*.jar")))
    classpath += ":" + str(fixture / "fixture-api.jar")
    subprocess.run([str(executable), java, classpath, str(fixture / "config.json"), str(fixture)], check=True)


if __name__ == "__main__":
    main()
