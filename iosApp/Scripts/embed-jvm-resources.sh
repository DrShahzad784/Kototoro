#!/bin/bash
set -euo pipefail

if [[ "${KOTOTORO_JVM:-0}" != "1" ]]; then
    if [[ -d "$TARGET_BUILD_DIR/$UNLOCALIZED_RESOURCES_FOLDER_PATH/jvm" ]]; then
        echo "This build directory contains optional JVM resources; use a separate DerivedData directory for the default app." >&2
        exit 1
    fi
    exit 0
fi
if [[ "${PLATFORM_NAME:-}" != "iphonesimulator" || "${CURRENT_ARCH:-arm64}" == "x86_64" ]]; then
    echo "The local JVM preview currently supports only the arm64 iOS Simulator." >&2
    exit 1
fi

runtime="${KOTOTORO_JVM_DIRECTORY:-$SRCROOT/build/jvm}"
for required in java_bundle/lib/modules libkototoro-jvm.a include/jni.h lib sources/config.json; do
    if [[ ! -e "$runtime/$required" ]]; then
        echo "Missing JVM preview artifact: $runtime/$required" >&2
        exit 1
    fi
done
destination="$TARGET_BUILD_DIR/$UNLOCALIZED_RESOURCES_FOLDER_PATH/jvm"
mkdir -p "$destination"
for directory in java_bundle lib sources; do
    ditto "$runtime/$directory" "$destination/$directory"
done
if [[ -f "$runtime/boot-shims.jar" ]]; then
    ditto "$runtime/boot-shims.jar" "$destination/boot-shims.jar"
fi
