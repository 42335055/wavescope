#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
BUILD="$ROOT/build"
SDK_JAR="${SDK_JAR:-$ROOT/sdk/android.jar}"
BT="${BT:-/usr/lib/android-sdk/build-tools/29.0.3}"
AAPT2="$BT/aapt2"
ZIPALIGN="$BT/zipalign"
APKSIGNER="$BT/apksigner"
R8="${R8:-$ROOT/tools/r8.jar}"
KEYSTORE="${KEYSTORE:-$ROOT/wavescope-release.jks}"
KS_PASS="${KS_PASS:?请先设置 KS_PASS 环境变量}"
KEY_ALIAS="wavescope"

if [ ! -f "$SDK_JAR" ]; then
  echo "Missing $SDK_JAR" >&2
  exit 1
fi
if [ ! -f "$R8" ]; then
  echo "Missing $R8" >&2
  exit 1
fi

rm -rf "$BUILD"
mkdir -p "$BUILD/res" "$BUILD/gen" "$BUILD/classes" "$BUILD/dex"

echo "[1/6] Compiling resources"
"$AAPT2" compile --dir "$ROOT/res" -o "$BUILD/res/resources.zip"

echo "[2/6] Linking resources and manifest"
"$AAPT2" link \
  -o "$BUILD/app-unsigned.apk" \
  -I "$SDK_JAR" \
  --manifest "$ROOT/AndroidManifest.xml" \
  --java "$BUILD/gen" \
  --auto-add-overlay \
  --min-sdk-version 23 \
  --target-sdk-version 33 \
  --version-code 77 \
  --version-name "1.1" \
  "$BUILD/res/resources.zip"

echo "[3/6] Compiling Java"
find "$ROOT/src" "$BUILD/gen" -name '*.java' -print > "$BUILD/sources.txt"
javac -encoding UTF-8 --release 8 \
  -classpath "$SDK_JAR" \
  -d "$BUILD/classes" \
  @"$BUILD/sources.txt"

echo "[4/6] Dexing"
find "$BUILD/classes" -name '*.class' -print > "$BUILD/classes.txt"
java -cp "$R8" com.android.tools.r8.D8 \
  --release \
  --min-api 23 \
  --lib "$SDK_JAR" \
  --output "$BUILD/dex" \
  @"$BUILD/classes.txt"
(cd "$BUILD/dex" && zip -q -j "$BUILD/app-unsigned.apk" classes.dex)

echo "[5/6] Aligning"
"$ZIPALIGN" -f 4 "$BUILD/app-unsigned.apk" "$BUILD/app-aligned.apk"

if [ ! -f "$KEYSTORE" ]; then
  echo "[6/6] Creating signing key and signing"
  keytool -genkeypair -noprompt \
    -keystore "$KEYSTORE" \
    -alias "$KEY_ALIAS" \
    -keyalg RSA -keysize 3072 -validity 10000 \
    -storepass "$KS_PASS" -keypass "$KS_PASS" \
    -dname "CN=WaveScope, O=DSH, C=CN" >/dev/null 2>&1
else
  echo "[6/6] Signing"
fi
"$APKSIGNER" sign \
  --ks "$KEYSTORE" \
  --ks-key-alias "$KEY_ALIAS" \
  --ks-pass "pass:$KS_PASS" \
  --key-pass "pass:$KS_PASS" \
  --min-sdk-version 23 \
  --out "$BUILD/WaveScope-1.0.28.apk" \
  "$BUILD/app-aligned.apk"

"$APKSIGNER" verify --verbose "$BUILD/WaveScope-1.0.28.apk" > "$BUILD/verify.txt"
"$AAPT2" dump badging "$BUILD/WaveScope-1.0.28.apk" > "$BUILD/badging.txt" 2>&1 || true
ls -lh "$BUILD/WaveScope-1.0.28.apk"
echo "Built: $BUILD/WaveScope-1.0.28.apk"

# Export source alongside the APK.
SOURCE_README="$ROOT/SOURCE_README.txt"
SOURCE_ZIP="$BUILD/WaveScope-1.0.28-source.zip"
rm -f "$SOURCE_ZIP"
(
  cd "$ROOT"
  zip -q -r "$SOURCE_ZIP" src res AndroidManifest.xml build.sh
  if [ -f "$SOURCE_README" ]; then
    zip -q -j "$SOURCE_ZIP" "$SOURCE_README"
  fi
)
ls -lh "$SOURCE_ZIP"
echo "Source: $SOURCE_ZIP"

JAVA_ZIP="$BUILD/WaveScope-1.0.28-java.zip"
rm -f "$JAVA_ZIP"
(
  cd "$ROOT"
  zip -q -r "$JAVA_ZIP" src -i '*.java'
  if [ -f "$ROOT/JAVA_README.txt" ]; then
    zip -q -j "$JAVA_ZIP" "$ROOT/JAVA_README.txt"
  fi
)
ls -lh "$JAVA_ZIP"
echo "Java: $JAVA_ZIP"

DEST="/sdcard/Download/DSH-Go"
if [ -d "$DEST" ]; then
  cp "$BUILD/WaveScope-1.0.28.apk" "$DEST/WaveScope-1.0.28.apk"
  cp "$SOURCE_ZIP" "$DEST/$(basename "$SOURCE_ZIP")"
  cp "$JAVA_ZIP" "$DEST/$(basename "$JAVA_ZIP")"
  echo "Published APK, source and Java sources to $DEST"
fi
