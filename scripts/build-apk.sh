#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
chmod +x ./gradlew
./gradlew assembleRelease --no-daemon --stacktrace
test -s app/build/outputs/apk/release/app-release-unsigned.apk
echo "unsigned APK: app/build/outputs/apk/release/app-release-unsigned.apk"
