#!/usr/bin/env bash
# Linux/macOS twin of build.ps1: builds android/out/k2.apk from k2.html + fonts/ + src/ + res/ with plain SDK tools (no Gradle).
#
#   android/build.sh            release: not debuggable, signed with the release key
#   android/build.sh --debug    debuggable, signed with the debug key, one-cell receiver exported (adb testing)
#   add --install to push the result to the connected phone with adb
#
# Release key: ~/.android/sprocontrol-release.jks, password in sprocontrol-release.pass next to it.
# Unlike build.ps1 this never creates a key: updates must be signed with the existing one, so copy it here.
# SDK: $ANDROID_HOME (default ~/Android/Sdk) with build-tools 35.0.0 and platforms/android-35; Java 11+ on PATH or in $JAVA_HOME.
set -euo pipefail
debug=0; install=0
for a in "$@"; do case "$a" in --debug) debug=1;; --install) install=1;; *) echo "unknown option $a" >&2; exit 2;; esac; done

sdk="${ANDROID_HOME:-$HOME/Android/Sdk}"
bt="$sdk/build-tools/35.0.0"
jar="$sdk/platforms/android-35/android.jar"
[ -n "${JAVA_HOME:-}" ] && export PATH="$JAVA_HOME/bin:$PATH"

here="$(cd "$(dirname "$0")" && pwd)"
out="$here/out"
rm -rf "$out"; mkdir -p "$out/assets/fonts" "$out/classes"
cp "$here/../k2.html" "$out/assets/k2.html"
cp "$here/../fonts/"* "$out/assets/fonts/"

"$bt/aapt2" compile --dir "$here/res" -o "$out/res.zip"
manifest="$here/AndroidManifest.xml"
link=(link --manifest "$manifest" -I "$jar" "$out/res.zip" --java "$out/gen" -o "$out/unsigned.apk")
if [ $debug = 1 ]; then
  # Debug builds export the one-button widget's receiver so a shell broadcast can stand in for a tap; never in a release.
  manifest="$out/AndroidManifest.debug.xml"
  sed 's/android:name=".K2OneWidget" android:exported="false"/android:name=".K2OneWidget" android:exported="true"/' "$here/AndroidManifest.xml" > "$manifest"
  link=(link --manifest "$manifest" -I "$jar" "$out/res.zip" --java "$out/gen" -o "$out/unsigned.apk" --debug-mode)
fi
"$bt/aapt2" "${link[@]}"
find "$here/src" "$out/gen" -name '*.java' > "$out/sources.txt"
javac -encoding UTF-8 -source 11 -target 11 -Xlint:-options -cp "$jar" -d "$out/classes" @"$out/sources.txt"
find "$out/classes" -name '*.class' > "$out/classes.txt"
"$bt/d8" --release --min-api 31 --lib "$jar" --output "$out" @"$out/classes.txt"

(cd "$out" && jar uf unsigned.apk classes.dex assets)
"$bt/zipalign" -f -p 4 "$out/unsigned.apk" "$out/aligned.apk"

if [ $debug = 1 ]; then
  ks="$HOME/.android/debug.keystore"; pass=android
  [ -f "$ks" ] || keytool -genkeypair -keystore "$ks" -alias androiddebugkey -keyalg RSA -keysize 2048 -validity 10000 \
    -storepass android -keypass android -dname "CN=Android Debug,O=Android,C=US" 2>/dev/null
else
  ks="$HOME/.android/sprocontrol-release.jks"; passFile="$HOME/.android/sprocontrol-release.pass"
  if [ ! -f "$ks" ] || [ ! -f "$passFile" ]; then
    echo "release key missing: copy sprocontrol-release.jks and sprocontrol-release.pass into ~/.android/" >&2; exit 1
  fi
  pass="$(tr -d '\r\n' < "$passFile")"
fi
"$bt/apksigner" sign --ks "$ks" --ks-pass "pass:$pass" --key-pass "pass:$pass" --out "$out/k2.apk" "$out/aligned.apk"
rm -f "$out/unsigned.apk" "$out/aligned.apk" "$out/sources.txt" "$out/classes.txt"
echo "built $out/k2.apk ($([ $debug = 1 ] && echo debug || echo release))"
echo "sha256 $(sha256sum "$out/k2.apk" | cut -d' ' -f1)"

if [ $install = 1 ]; then adb install -r "$out/k2.apk"; adb shell am start -n com.sprocontrol.k2/.MainActivity; fi
