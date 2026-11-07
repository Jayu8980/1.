#!/usr/bin/env bash
# Build a signed APK without Gradle or the Google SDK manager, using the
# Debian/Ubuntu packaged Android build tools:
#
#   sudo apt-get install aapt apksigner zipalign openjdk-17-jdk
#
# Downloads android.jar (API 34), the ONNX Runtime AAR and Google's D8 dexer
# (r8lib.jar from the official R8 release bucket) on first run.
# Output: build/piano2midi.apk
#
# Prefer the regular Gradle build (./gradlew assembleRelease, or Android Studio)
# when you have the Android SDK; this script exists for environments without it.
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
APP="$HERE/app/src/main"
OUT="$HERE/build/local"
CACHE="${CACHE_DIR:-$HERE/build/cache}"
ORT_VERSION="${ORT_VERSION:-1.23.2}"
MIN_SDK=26
TARGET_SDK=34
VERSION_CODE="${VERSION_CODE:-12}"
VERSION_NAME="${VERSION_NAME:-2.1}"
PKG=com.hft.piano2midi
# VARIANT=full builds "Piano2MIDI Full": MuScriptor in fp32 from MS_MODEL (an exported
# muscriptor.onnx, export_muscriptor_onnx.py --fp32), separate application id so both install.
VARIANT="${VARIANT:-standard}"
APP_ID=$PKG
APK_OUT="$HERE/build/piano2midi.apk"
if [ "$VARIANT" = full ]; then
  APP_ID=$PKG.full
  APK_OUT="$HERE/build/piano2midi-full.apk"
  if [ ! -f "${MS_MODEL:-}" ]; then echo "VARIANT=full needs MS_MODEL=/path/to/fp32/muscriptor.onnx" >&2; exit 1; fi
fi
ABIS="${ABIS:-arm64-v8a armeabi-v7a x86_64}"
R8_VERSION="${R8_VERSION:-8.5.35}"

BT=/usr/lib/android-sdk/build-tools/debian
AAPT2="${AAPT2:-$BT/aapt2}"
ZIPALIGN="${ZIPALIGN:-$(command -v zipalign || echo $BT/zipalign)}"
APKSIGNER="${APKSIGNER:-$(command -v apksigner || echo $BT/apksigner)}"

mkdir -p "$CACHE"
ANDROID_JAR="${ANDROID_JAR:-$CACHE/android-$TARGET_SDK.jar}"
if [ ! -f "$ANDROID_JAR" ]; then
  echo "== downloading android.jar (API $TARGET_SDK)"
  curl -fsSL -o "$ANDROID_JAR" "https://raw.githubusercontent.com/Reginer/aosp-android-jar/main/android-$TARGET_SDK/android.jar"
fi
ORT_AAR="$CACHE/onnxruntime-android-$ORT_VERSION.aar"
if [ ! -f "$ORT_AAR" ]; then
  echo "== downloading ONNX Runtime $ORT_VERSION"
  curl -fsSL -o "$ORT_AAR" "https://repo1.maven.org/maven2/com/microsoft/onnxruntime/onnxruntime-android/$ORT_VERSION/onnxruntime-android-$ORT_VERSION.aar"
fi
R8_JAR="${R8_JAR:-$CACHE/r8lib-$R8_VERSION.jar}"
if [ ! -f "$R8_JAR" ]; then
  echo "== downloading D8/R8 $R8_VERSION"
  curl -fsSL -o "$R8_JAR" "https://storage.googleapis.com/r8-releases/raw/$R8_VERSION/r8lib.jar"
fi
if [ ! -f "$APP/assets/hft_transformer.onnx" ]; then
  echo "missing $APP/assets/hft_transformer.onnx - run tools/export_onnx.py first" >&2
  exit 1
fi

rm -rf "$OUT"
mkdir -p "$OUT"/{res,gen,classes,ort,apk}
unzip -q -o "$ORT_AAR" classes.jar 'jni/*' -d "$OUT/ort"

echo "== assets (bundled models)"
MS="$HERE/../models/muscriptor-medium"
if ! ls "$MS"/muscriptor.onnx.?? >/dev/null 2>&1; then
  echo "missing $MS/muscriptor.onnx.NN - run tools/muscriptor/export_muscriptor_onnx.py first" >&2
  exit 1
fi
cp -r "$APP/assets" "$OUT/assets"
if [ "$VARIANT" = full ]; then
  cp "$MS_MODEL" "$OUT/assets/muscriptor.onnx"
else
  cat "$MS"/muscriptor.onnx.?? > "$OUT/assets/muscriptor.onnx"
fi
cp "$MS/ms_frontend.bin" "$OUT/assets/ms_frontend.bin"

echo "== manifest"
sed -e "s#<manifest xmlns:android=\"http://schemas.android.com/apk/res/android\">#<manifest xmlns:android=\"http://schemas.android.com/apk/res/android\" package=\"$PKG\" android:versionCode=\"$VERSION_CODE\" android:versionName=\"$VERSION_NAME\">\n    <uses-sdk android:minSdkVersion=\"$MIN_SDK\" android:targetSdkVersion=\"$TARGET_SDK\" />#" \
  "$APP/AndroidManifest.xml" | sed -e "s#android:authorities=\"$PKG.files\"#android:authorities=\"$APP_ID.files\"#" > "$OUT/AndroidManifest.xml"

echo "== resources (aapt2)"
"$AAPT2" compile --dir "$APP/res" -o "$OUT/res/res.zip"
OVERLAY=()
if [ "$VARIANT" = full ]; then
  "$AAPT2" compile --dir "$HERE/variants/full/res" -o "$OUT/res/full.zip"
  OVERLAY=(-R "$OUT/res/full.zip" --rename-manifest-package "$APP_ID")
fi
"$AAPT2" link -o "$OUT/base.apk" -I "$ANDROID_JAR" --manifest "$OUT/AndroidManifest.xml" \
  --min-sdk-version $MIN_SDK --target-sdk-version $TARGET_SDK \
  --java "$OUT/gen" -A "$OUT/assets" -0 onnx -0 sf2 -0 bin --auto-add-overlay "$OUT/res/res.zip" "${OVERLAY[@]}"

echo "== javac"
find "$APP/java" "$OUT/gen" -name '*.java' > "$OUT/sources.txt"
javac -nowarn -Xlint:-options -encoding UTF-8 -source 8 -target 8 \
  -bootclasspath "$ANDROID_JAR" -classpath "$OUT/ort/classes.jar" \
  -d "$OUT/classes" @"$OUT/sources.txt"

echo "== dex (d8, with Java 8 desugaring)"
(cd "$OUT/classes" && zip -q -r "$OUT/app-classes.jar" .)
java -cp "$R8_JAR" com.android.tools.r8.D8 --release --min-api $MIN_SDK --lib "$ANDROID_JAR" \
  --output "$OUT/apk" "$OUT/app-classes.jar" "$OUT/ort/classes.jar"

echo "== package"
cp "$OUT/base.apk" "$OUT/unsigned.apk"
(cd "$OUT/apk" && zip -q -X "$OUT/unsigned.apk" classes.dex)
for abi in $ABIS; do
  mkdir -p "$OUT/apk/lib/$abi"
  cp "$OUT/ort/jni/$abi/"*.so "$OUT/apk/lib/$abi/"
done
(cd "$OUT/apk" && zip -q -X -9 -r "$OUT/unsigned.apk" lib)
"$ZIPALIGN" -f -p 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"

echo "== sign"
KEYSTORE="${KEYSTORE:-$HERE/build/debug.keystore}"
if [ ! -f "$KEYSTORE" ]; then
  keytool -genkeypair -keystore "$KEYSTORE" -storepass android -keypass android -alias androiddebugkey \
    -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Piano2MIDI Debug,O=Android,C=US" >/dev/null 2>&1
fi
"$APKSIGNER" sign --ks "$KEYSTORE" --ks-pass pass:"${KS_PASS:-android}" --key-pass pass:"${KEY_PASS:-android}" \
  --ks-key-alias "${KEY_ALIAS:-androiddebugkey}" --min-sdk-version $MIN_SDK \
  --out "$APK_OUT" "$OUT/aligned.apk"
"$APKSIGNER" verify --min-sdk-version $MIN_SDK "$APK_OUT"
echo "== done: $APK_OUT ($(du -h "$APK_OUT" | cut -f1))"
