#!/bin/bash
# Builds and signs the OAID self-test app (mimics the MSA SDK + provider paths).
set -euo pipefail
cd "$(dirname "$0")"

SDK="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$HOME/Library/Android/sdk}}"
[ -d "$SDK" ] || { echo "ERROR: Android SDK not found. Set ANDROID_SDK_ROOT."; exit 1; }
BT="$(ls -d "$SDK"/build-tools/* 2>/dev/null | sort -V | tail -1)"
AJ="$SDK/platforms/android-34/android.jar"
[ -f "$AJ" ] || AJ="$(ls "$SDK"/platforms/android-*/android.jar 2>/dev/null | sort -V | tail -1)"
if [ -n "${JAVA_HOME:-}" ]; then JAVAC="$JAVA_HOME/bin/javac"; KEYTOOL="$JAVA_HOME/bin/keytool"; else JAVAC=javac; KEYTOOL=keytool; fi

KS="${KEYSTORE:-debug.keystore}"   # persistent, kept outside the wiped build/ dir
rm -rf build && mkdir -p build/classes build/dex
"$JAVAC" --release 11 -nowarn -cp "$AJ" -d build/classes $(find src -name '*.java')
"$BT/d8" --min-api 26 --lib "$AJ" --output build/dex $(find build/classes -name '*.class')
"$BT/aapt2" link -I "$AJ" --manifest AndroidManifest.xml --min-sdk-version 26 --target-sdk-version 34 -o build/base.apk
cp build/dex/classes.dex build/classes.dex
( cd build && zip -q base.apk classes.dex )
"$BT/zipalign" -f 4 build/base.apk build/aligned.apk
[ -f "$KS" ] || "$KEYTOOL" -genkeypair -keystore "$KS" -storepass android \
  -keypass android -alias t -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=OAID Test" >/dev/null 2>&1
"$BT/apksigner" sign --ks "$KS" --ks-pass pass:android --key-pass pass:android \
  --out oaid-test.apk build/aligned.apk
"$BT/apksigner" verify oaid-test.apk >/dev/null && echo "OK -> $(pwd)/oaid-test.apk"
