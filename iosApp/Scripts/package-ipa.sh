#!/bin/bash
# Build the default (no embedded JVM) Release app for devices and package it as an unsigned IPA.
# Usage: iosApp/Scripts/package-ipa.sh [version] [build-number]
# Output: iosApp/build/release-ios/Kototoro-<version>-ios-unsigned.ipa and its .sha256.
set -euo pipefail

root="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$root"
project=iosApp/Kototoro.xcodeproj

version="${1:-$(xcodebuild -project "$project" -target Kototoro -configuration Release -showBuildSettings |
    awk '$1 == "MARKETING_VERSION" { print $3; exit }')}"
build_number="${2:-$(git rev-list --count HEAD)}"
if ! [[ "$version" =~ ^[0-9]+(\.[0-9]+){0,2}$ ]] || ! [[ "$build_number" =~ ^[0-9]+$ ]]; then
    echo "Invalid version '$version' or build number '$build_number'." >&2
    exit 1
fi

# A separate DerivedData keeps optional JVM preview resources out of the release bundle.
archive=iosApp/build/Kototoro-Release.xcarchive
rm -rf "$archive"
xcodebuild -project "$project" -scheme Kototoro \
    -configuration Release -sdk iphoneos -destination 'generic/platform=iOS' \
    -derivedDataPath iosApp/build/ReleaseIPA -archivePath "$archive" \
    CODE_SIGNING_ALLOWED=NO CODE_SIGNING_REQUIRED=NO CODE_SIGN_IDENTITY="" \
    KOTOTORO_JVM=0 MARKETING_VERSION="$version" CURRENT_PROJECT_VERSION="$build_number" \
    archive

app="$archive/Products/Applications/Kototoro.app"
if [[ -e "$app/jvm" ]]; then
    echo "Release app unexpectedly contains JVM preview resources." >&2
    exit 1
fi

output=iosApp/build/release-ios
asset="Kototoro-$version-ios-unsigned"
stage="$(mktemp -d)"
trap 'rm -rf "$stage"' EXIT
mkdir -p "$stage/Payload" "$output"
ditto "$app" "$stage/Payload/Kototoro.app"
rm -f "$output/$asset.ipa" "$output/$asset.sha256"
(cd "$stage" && zip -qry -X "$root/$output/$asset.ipa" Payload)
(cd "$output" && shasum -a 256 "$asset.ipa" > "$asset.sha256")
ls -l "$output/$asset.ipa" "$output/$asset.sha256"
