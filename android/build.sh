#!/bin/bash
# Build MorrowindTouch.apk with only the Android SDK command-line tools (no Gradle).
# Needs: ANDROID_SDK (default ~/Android/Sdk) with platforms;android-35 and build-tools;35.0.0, a JDK 17,
# and a keystore: KEYSTORE=path KS_PASS=... ./build.sh
set -e
cd "$(dirname "$0")"
SDK=${ANDROID_SDK:-$HOME/Android/Sdk}; BT=$SDK/build-tools/35.0.0; AJ=$SDK/platforms/android-35/android.jar
rm -rf build && mkdir -p build/classes build/dex
$BT/aapt2 compile --dir res -o build/res.zip
$BT/aapt2 link -o build/base.apk -I $AJ --manifest AndroidManifest.xml build/res.zip --java build/gen --auto-add-overlay
javac -source 8 -target 8 -bootclasspath $AJ -classpath $BT/core-lambda-stubs.jar -Xlint:-options -encoding UTF-8 \
    -d build/classes $(find src build/gen -name "*.java")
$BT/d8 --min-api 26 --lib $AJ --output build/dex $(find build/classes -name "*.class")
cp build/base.apk build/unsigned.apk; (cd build/dex && zip -q ../unsigned.apk classes.dex)
$BT/zipalign -f -p 4 build/unsigned.apk build/aligned.apk
$BT/apksigner sign --ks "$KEYSTORE" --ks-pass "pass:$KS_PASS" --out build/MorrowindTouch.apk build/aligned.apk
echo "built android/build/MorrowindTouch.apk"
