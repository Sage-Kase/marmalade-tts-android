#!/usr/bin/env bash
# Build the release gate's probe APK (app.marmalade.tts.smokeprobe) straight
# from the SDK's build tools: no Gradle, so it never touches the app's
# reproducible build. Signed with the local debug key; never distributed.
#
#   scripts/release-smoke/build-probe.sh      → scripts/release-smoke/build/probe.apk
set -euo pipefail
here=$(cd "$(dirname "$0")" && pwd)
sdk=${ANDROID_HOME:-$HOME/Android/Sdk}
api=35
bt=$(ls -d "$sdk"/build-tools/* | sort -V | tail -1)
jar=$sdk/platforms/android-$api/android.jar
ks=${PROBE_KEYSTORE:-$HOME/.android/debug.keystore}
out=$here/build
[[ -f $jar ]] || { echo "missing $jar (install platforms;android-$api)" >&2; exit 1; }

rm -rf "$out"; mkdir -p "$out/classes"
"$bt/aapt2" link -o "$out/base.apk" -I "$jar" --manifest "$here/probe/AndroidManifest.xml" \
    --min-sdk-version 28 --target-sdk-version $api
javac -nowarn --release 11 -cp "$jar" -d "$out/classes" $(find "$here/probe/src" -name '*.java')
"$bt/d8" --release --min-api 28 --lib "$jar" --output "$out" $(find "$out/classes" -name '*.class')
(cd "$out" && cp base.apk unsigned.apk && zip -q unsigned.apk classes.dex)
"$bt/zipalign" -f 4 "$out/unsigned.apk" "$out/aligned.apk"
"$bt/apksigner" sign --ks "$ks" --ks-pass pass:android --key-pass pass:android \
    --ks-key-alias androiddebugkey --out "$out/probe.apk" "$out/aligned.apk"
rm -rf "$out/classes" "$out"/base.apk "$out"/unsigned.apk "$out"/aligned.apk "$out"/classes.dex "$out"/probe.apk.idsig
echo "$out/probe.apk"
