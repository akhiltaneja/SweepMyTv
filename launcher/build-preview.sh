#!/usr/bin/env bash
# Builds and checks the local preview. This script never connects to or changes a TV.
set -euo pipefail
launcher_dir="$(cd "$(dirname "$0")" && pwd)"
workspace_dir="$(cd "$launcher_dir/.." && pwd)"
export JAVA_HOME="${JAVA_HOME:-$workspace_dir/tools/jdk17/Contents/Home}"
export GRADLE_USER_HOME="${GRADLE_USER_HOME:-$workspace_dir/tools/gradle-cache}"
if [ ! -x "$JAVA_HOME/bin/java" ]; then
  echo "JDK 17 is required; set JAVA_HOME to its installation." >&2
  exit 1
fi
if [ ! -f "$workspace_dir/tools/android-sdk/platforms/android-35/android.jar" ]; then
  echo "Android SDK platform 35 and build-tools 35.0.0 are required in tools/android-sdk." >&2
  exit 1
fi
printf 'sdk.dir=%s\n' "$workspace_dir/tools/android-sdk" > "$launcher_dir/local.properties"
mkdir -p "$workspace_dir/tools/signing"
if [ ! -f "$workspace_dir/tools/signing/sweep-debug.keystore" ]; then
  "$JAVA_HOME/bin/keytool" -genkeypair \
    -keystore "$workspace_dir/tools/signing/sweep-debug.keystore" \
    -storepass android -keypass android -alias androiddebugkey \
    -keyalg RSA -keysize 2048 -validity 10000 \
    -dname "CN=Sweep TV Preview, O=Local Development, C=IN"
fi
python3 -m unittest discover -s "$launcher_dir/tests" -p "test_upstream_gate.py"
python3 "$launcher_dir/check-upstream.py" --report "$launcher_dir/build/upstream-verification.json"
cd "$launcher_dir"
exec ./gradlew --no-daemon testDebugUnitTest assembleRelease lintRelease
