#!/bin/bash
# Builds and signs the OAID Provider LSPosed module APK without Gradle.
# Requires: an Android SDK (build-tools + a platform android.jar) and a JDK 11+.
set -euo pipefail
cd "$(dirname "$0")"

# --- locate Android SDK ---------------------------------------------------
SDK="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$HOME/Library/Android/sdk}}"
[ -d "$SDK" ] || { echo "ERROR: Android SDK not found. Set ANDROID_SDK_ROOT."; exit 1; }
BT="$(ls -d "$SDK"/build-tools/* 2>/dev/null | sort -V | tail -1)"
[ -n "$BT" ] || { echo "ERROR: no build-tools under $SDK/build-tools"; exit 1; }
AJ="$SDK/platforms/android-34/android.jar"
[ -f "$AJ" ] || AJ="$(ls "$SDK"/platforms/android-*/android.jar 2>/dev/null | sort -V | tail -1)"
[ -f "$AJ" ] || { echo "ERROR: no platform android.jar found"; exit 1; }

# --- locate JDK -----------------------------------------------------------
if [ -n "${JAVA_HOME:-}" ]; then
  JAVAC="$JAVA_HOME/bin/javac"; JAR="$JAVA_HOME/bin/jar"; KEYTOOL="$JAVA_HOME/bin/keytool"
else
  JAVAC=javac; JAR=jar; KEYTOOL=keytool
fi

echo "SDK=$SDK"; echo "build-tools=$(basename "$BT")"; echo "android.jar=$AJ"

# Persistent debug keystore (kept OUTSIDE build/, which is wiped each run, so
# repeated builds keep the same signing key -> `adb install -r` keeps working).
KS="${KEYSTORE:-debug.keystore}"

# Optional: override the supplied OAID via env, e.g. `OAID=xxxx-... ./build.sh`
SRC="src/com/oaidfix/OaidModule.java"
if [ -n "${OAID:-}" ]; then
  echo "Injecting OAID=$OAID"
  perl -0pi -e 's/(private static final String OAID = ")[^"]*(")/${1}'"$OAID"'${2}/' "$SRC"
fi

rm -rf build && mkdir -p build/classes build/stubs build/dex build/stage/assets

echo ">> compile Xposed stubs (compile-only, NOT packaged)"
"$JAVAC" --release 11 -nowarn -d build/stubs $(find stubs -name '*.java')
"$JAR" cf build/xposed-stubs.jar -C build/stubs .

echo ">> compile module"
"$JAVAC" --release 11 -nowarn -cp "$AJ:build/xposed-stubs.jar" -d build/classes $(find src -name '*.java')

echo ">> d8 -> classes.dex"
"$BT/d8" --min-api 26 --lib "$AJ" --lib build/xposed-stubs.jar --output build/dex $(find build/classes -name '*.class')

echo ">> aapt2 link"
"$BT/aapt2" link -I "$AJ" --manifest AndroidManifest.xml --min-sdk-version 26 --target-sdk-version 34 -o build/base.apk

echo ">> package dex + assets"
cp build/dex/classes.dex build/classes.dex
cp assets/xposed_init build/stage/assets/xposed_init
( cd build && zip -q base.apk classes.dex )
( cd build/stage && zip -q -r ../base.apk assets )

echo ">> align + sign"
"$BT/zipalign" -f 4 build/base.apk build/aligned.apk
if [ ! -f "$KS" ]; then
  echo ">> generating debug keystore: $KS"
  "$KEYTOOL" -genkeypair -keystore "$KS" -storepass android -keypass android \
    -alias oaid -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=OAID Provider" >/dev/null 2>&1
fi
"$BT/apksigner" sign --ks "$KS" --ks-pass pass:android --key-pass pass:android \
  --out oaid-provider.apk build/aligned.apk
"$BT/apksigner" verify oaid-provider.apk >/dev/null && echo "OK -> $(pwd)/oaid-provider.apk"
