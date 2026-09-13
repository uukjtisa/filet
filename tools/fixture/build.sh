#!/usr/bin/env bash
# Builds the M6 fixture APK: a few-kilobyte, installable app whose dex holds one editable
# string constant. Kept out of the Gradle build on purpose - a second application module
# would be built on every `assemble`, and this is only needed when the fixture changes.
#
# Output: app/src/androidTest/assets/fixture.apk (checked in, so the test needs no toolchain).
set -euo pipefail

SDK="${ANDROID_SDK_ROOT:-D:/Android-SDK}"
BT="$SDK/build-tools/35.0.0"
JAR="$SDK/platforms/android-34/android.jar"
JAVA_HOME="${JAVA_HOME:-D:/Program Files/Android-Studio/jbr}"
HERE="$(cd "$(dirname "$0")" && pwd)"
OUT="$HERE/build"
DEST="$HERE/../../app/src/androidTest/assets/fixture.apk"

rm -rf "$OUT"; mkdir -p "$OUT/classes" "$OUT/dex"

"$JAVA_HOME/bin/javac" -source 8 -target 8 -nowarn -bootclasspath "$JAR" \
    -d "$OUT/classes" $(find "$HERE/src" -name '*.java')

"$BT/d8.bat" --min-api 26 --release --lib "$JAR" --output "$OUT/dex" \
    $(find "$OUT/classes" -name '*.class')

"$BT/aapt2.exe" link --manifest "$HERE/AndroidManifest.xml" -I "$JAR" \
    --min-sdk-version 26 --target-sdk-version 34 -o "$OUT/base.apk"

# The dex is added to the linked resource APK rather than built in, because aapt2 links
# resources only - it has no idea what a dex is.
(cd "$OUT/dex" && "$JAVA_HOME/bin/jar" uf "$OUT/base.apk" classes.dex)

"$BT/zipalign.exe" -f 4 "$OUT/base.apk" "$OUT/aligned.apk"
"$BT/apksigner.bat" sign --ks "$HOME/.android/debug.keystore" \
    --ks-pass pass:android --key-pass pass:android --ks-key-alias androiddebugkey \
    --out "$OUT/fixture.apk" "$OUT/aligned.apk"

mkdir -p "$(dirname "$DEST")"
cp "$OUT/fixture.apk" "$DEST"
echo "fixture.apk $(stat -c %s "$DEST") bytes -> $DEST"
