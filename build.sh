#!/usr/bin/env bash
# Lightweight APK build without Gradle (no internet needed).
# Requirements: Android SDK (platform 35 + build-tools 35.0.0) and JDK 17+.
#
# Release signing (optional) - set these, or create keystore.properties:
#   VIDEOX_KEYSTORE_PATH, VIDEOX_KEYSTORE_PASSWORD, VIDEOX_KEY_ALIAS, VIDEOX_KEY_PASSWORD
# Without them the APK is signed with a local debug key (fine for testing only).
set -euo pipefail

: "${ANDROID_SDK_ROOT:=${ANDROID_HOME:-}}"
: "${ANDROID_SDK_ROOT:?Set ANDROID_SDK_ROOT to your Android SDK path}"
: "${JAVA_HOME:?Set JAVA_HOME to a JDK 17+ path}"

project_dir="$(cd "$(dirname "$0")" && pwd)"
build_tools="$ANDROID_SDK_ROOT/build-tools/${BUILD_TOOLS_VERSION:-35.0.0}"
android_jar="$ANDROID_SDK_ROOT/platforms/android-35/android.jar"
for tool in aapt2 d8 zipalign apksigner; do
  [[ -x "$build_tools/$tool" ]] || { echo "Missing $build_tools/$tool" >&2; exit 1; }
done
[[ -f "$android_jar" ]] || { echo "Missing $android_jar (install platform android-35)" >&2; exit 1; }

# Read package/version from app/build.gradle so there is one source of truth.
gradle_file="$project_dir/app/build.gradle"
app_id="$(sed -n "s/^[[:space:]]*applicationId '\(.*\)'/\1/p" "$gradle_file" | head -1)"
version_code="$(sed -n 's/^[[:space:]]*versionCode \([0-9]*\)/\1/p' "$gradle_file" | head -1)"
version_name="$(sed -n "s/^[[:space:]]*versionName '\(.*\)'/\1/p" "$gradle_file" | head -1)"

# Optional keystore.properties (same file Gradle uses).
props="$project_dir/keystore.properties"
prop() { [[ -f "$props" ]] && sed -n "s/^$1=//p" "$props" | head -1 | tr -d '\r' || true; }
keystore="${VIDEOX_KEYSTORE_PATH:-$(prop storeFile)}"
store_pass="${VIDEOX_KEYSTORE_PASSWORD:-$(prop storePassword)}"
key_alias="${VIDEOX_KEY_ALIAS:-$(prop keyAlias)}"
key_pass="${VIDEOX_KEY_PASSWORD:-$(prop keyPassword)}"
[[ -n "$keystore" && "$keystore" != /* ]] && keystore="$project_dir/$keystore"

mkdir -p "$project_dir/build"
run_dir="$(mktemp -d "$project_dir/build/run-XXXXXX")"
trap 'rm -rf "$run_dir"' EXIT
dist_dir="$project_dir/dist"
libs="$project_dir/app/libs"
lame_dir="$run_dir/lame"; ffmpeg_dir="$run_dir/ffmpeg"
smart_common="$libs/smart-exception-common-0.2.1.jar"
smart_java="$libs/smart-exception-java-0.2.1.jar"

mkdir -p "$run_dir/gen" "$run_dir/classes" "$run_dir/dex" "$lame_dir" "$ffmpeg_dir" "$run_dir/native/lib" "$dist_dir"
unzip -q "$libs/TAndroidLame-1.1.aar" -d "$lame_dir"
unzip -q "$libs/ffmpeg-kit-full-8.1.7.aar" -d "$ffmpeg_dir"

# The source manifest has no package attribute (AGP 8 uses `namespace`) and uses
# ${applicationId}; aapt2 needs both resolved.
manifest="$run_dir/AndroidManifest.xml"
sed -e "s|<manifest xmlns:android=\"http://schemas.android.com/apk/res/android\"|<manifest xmlns:android=\"http://schemas.android.com/apk/res/android\" package=\"$app_id\"|" \
    -e "s|\${applicationId}|$app_id|g" "$project_dir/app/src/main/AndroidManifest.xml" > "$manifest"

"$build_tools/aapt2" compile --dir "$project_dir/app/src/main/res" -o "$run_dir/compiled.zip"
"$build_tools/aapt2" link -I "$android_jar" --manifest "$manifest" \
  --java "$run_dir/gen" --min-sdk-version 26 --target-sdk-version 35 \
  --version-code "$version_code" --version-name "$version_name" -o "$run_dir/unsigned.apk" "$run_dir/compiled.zip"

cp_libs="$android_jar:$lame_dir/classes.jar:$ffmpeg_dir/classes.jar:$smart_common:$smart_java"
"$JAVA_HOME/bin/javac" --release 17 -encoding UTF-8 -classpath "$cp_libs" -d "$run_dir/classes" \
  $(find "$project_dir/app/src/main/java" "$run_dir/gen" -name '*.java')
"$build_tools/d8" --lib "$android_jar" --min-api 26 --release --output "$run_dir/dex" \
  $(find "$run_dir/classes" -name '*.class') "$lame_dir/classes.jar" "$ffmpeg_dir/classes.jar" "$smart_common" "$smart_java"
(cd "$run_dir/dex" && zip -q -j "$run_dir/unsigned.apk" classes.dex)
cp -R "$lame_dir/jni/." "$run_dir/native/lib/"
cp -R "$ffmpeg_dir/jni/." "$run_dir/native/lib/"
(cd "$run_dir/native" && zip -q -0 -r "$run_dir/unsigned.apk" lib)
"$build_tools/zipalign" -P 16 -f 4 "$run_dir/unsigned.apk" "$run_dir/aligned.apk"

if [[ -n "$keystore" && -f "$keystore" && -n "$store_pass" ]]; then
  out="$dist_dir/VideoX-Compressor-Pro-v$version_name.apk"
  # Passwords go through the environment, so they never appear in `ps` output.
  VX_STORE_PASS="$store_pass" VX_KEY_PASS="${key_pass:-$store_pass}" \
    "$build_tools/apksigner" sign --ks "$keystore" --ks-pass env:VX_STORE_PASS \
    --key-pass env:VX_KEY_PASS --ks-key-alias "${key_alias:-videox}" \
    --out "$out" "$run_dir/aligned.apk"
else
  echo "No release keystore configured - signing with a local debug key (testing only)." >&2
  debug_ks="$project_dir/build/debug.keystore"
  if [[ ! -f "$debug_ks" ]]; then
    "$JAVA_HOME/bin/keytool" -genkeypair -keystore "$debug_ks" -storepass android -keypass android \
      -alias androiddebugkey -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Android Debug,O=Android,C=US"
  fi
  out="$dist_dir/VideoX-Compressor-Pro-v$version_name-debugsigned.apk"
  "$build_tools/apksigner" sign --ks "$debug_ks" --ks-pass pass:android --key-pass pass:android \
    --ks-key-alias androiddebugkey --out "$out" "$run_dir/aligned.apk"
fi

"$build_tools/apksigner" verify --print-certs "$out"
echo "$out"
