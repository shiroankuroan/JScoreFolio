#!/bin/bash
# Build the 楽譜表示 (gakufu) WebView shell APK with a given single-file HTML app.
#
# Usage:
#   build-apk.sh <path-to-index.html> [output.apk]
# Default output: ./gakufu.apk (next to this script)
#
# Manual pipeline (no Gradle): aapt2 -> javac -> d8 -> zipalign -> apksigner.
# Everything (SDK, JDK, keystore, shell) is expected under this script's directory.
set -euo pipefail

BASE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# --- toolchain ---
# F-Droid's buildserver provides the SDK/JDK itself: honour the environment
# when set, fall back to the bundled copies under this directory otherwise.
export ANDROID_HOME="${ANDROID_HOME:-$BASE/sdk}"
if [ -n "${JAVA_HOME:-}" ]; then
    JAVA_HOME_CANDIDATE="$JAVA_HOME"
else
JAVA_HOME_CANDIDATE="$(ls -d "$BASE"/jdk/jdk-* 2>/dev/null | head -1 || true)"
fi
if [ -z "$JAVA_HOME_CANDIDATE" ]; then
    echo "error: no JDK found under $BASE/jdk/" >&2
    exit 1
fi
export JAVA_HOME="$JAVA_HOME_CANDIDATE"
export PATH="$JAVA_HOME/bin:$PATH"

BUILD_TOOLS="$ANDROID_HOME/build-tools/34.0.0"
ANDROID_JAR="$ANDROID_HOME/platforms/android-34/android.jar"
AAPT2="$BUILD_TOOLS/aapt2"
D8="$BUILD_TOOLS/d8"
ZIPALIGN="$BUILD_TOOLS/zipalign"
APKSIGNER="$BUILD_TOOLS/apksigner"

# --- args ---
HTML="${1:?usage: build-apk.sh <path-to-index.html> [output.apk]}"
OUT="${2:-$BASE/gakufu.apk}"
[ -f "$HTML" ] || { echo "error: HTML file not found: $HTML" >&2; exit 1; }
mkdir -p "$(dirname "$OUT")"

SHELL_DIR="$BASE/shell"
# Fresh clones of the repo keep the shell files directly under android/
# (no shell/ subdir); fall back to the script's own directory then.
[ -d "$SHELL_DIR" ] || SHELL_DIR="$BASE"
WORK="$BASE/build"
KS="$BASE/keystore.jks"
KS_PASS_FILE="$BASE/keystore-password.txt"
if [ "${JSCOREFOLIO_UNSIGNED:-0}" != "1" ]; then
[ -f "$KS" ] || { echo "error: keystore not found: $KS" >&2; exit 1; }
[ -f "$KS_PASS_FILE" ] || { echo "error: keystore password file not found: $KS_PASS_FILE" >&2; exit 1; }
fi

echo "== staging app =="
rm -rf "$WORK"
mkdir -p "$WORK/app/assets/www" "$WORK/compiled" "$WORK/classes" "$WORK/dex"
cp "$SHELL_DIR/AndroidManifest.xml" "$WORK/app/AndroidManifest.xml"
cp -r "$SHELL_DIR/res" "$WORK/app/res"
# Stage any extra shell assets, then replace index.html with the requested build.
if [ -d "$SHELL_DIR/assets" ]; then
    cp -r "$SHELL_DIR/assets/." "$WORK/app/assets/"
fi
cp "$HTML" "$WORK/app/assets/www/index.html"

echo "== aapt2 compile + link =="
"$AAPT2" compile --dir "$WORK/app/res" -o "$WORK/compiled/res.zip"
"$AAPT2" link \
    -o "$WORK/unsigned.apk" \
    -I "$ANDROID_JAR" \
    --manifest "$WORK/app/AndroidManifest.xml" \
    --min-sdk-version 29 \
    --target-sdk-version 34 \
    --version-code "${VERSION_CODE:-1}" \
    --version-name "${VERSION_NAME:-1.0}" \
    -A "$WORK/app/assets" \
    "$WORK/compiled/res.zip"

echo "== javac =="
# -parameters is REQUIRED here: without it, javac 21 writes a null name into
# the MethodParameters attribute for the mandated enclosing-instance parameter
# of anonymous/inner classes (e.g. MainActivity$1, the WebChromeClient), and
# d8 from build-tools 34.0.0 (R8 8.2.2-dev) crashes with an NPE
# ("String.length() ... <parameter1> is null") on such class files.
# With -parameters the parameter gets its real name ("this$0") and d8 is happy.
# shellcheck disable=SC2046
javac -parameters -source 8 -target 8 -bootclasspath "$ANDROID_JAR" -cp "$ANDROID_JAR" \
    -d "$WORK/classes" $(find "$SHELL_DIR/src" -name '*.java')

echo "== d8 =="
# shellcheck disable=SC2046
"$D8" --min-api 29 --lib "$ANDROID_JAR" --output "$WORK/dex" $(find "$WORK/classes" -name '*.class')

echo "== add classes.dex, zipalign, sign =="
"$JAVA_HOME/bin/jar" --update --file "$WORK/unsigned.apk" -C "$WORK/dex" classes.dex
"$ZIPALIGN" -f -p 4 "$WORK/unsigned.apk" "$WORK/aligned.apk"
# NOTE: no separate --key-pass; keytool was given keypass == storepass, and
# apksigner falls back to the keystore password for the key. (Passing the same
# file: source twice fails: apksigner consumes one line per password prompt.)
if [ "${JSCOREFOLIO_UNSIGNED:-0}" = "1" ]; then
    echo "== unsigned build requested: skipping apksigner (F-Droid signs) =="
    cp "$WORK/aligned.apk" "$OUT"
else
"$APKSIGNER" sign \
    --ks "$KS" \
    --ks-key-alias gakufu \
    --ks-pass "file:$KS_PASS_FILE" \
    --out "$OUT" \
    "$WORK/aligned.apk"
fi

echo "== verify =="
if [ "${JSCOREFOLIO_UNSIGNED:-0}" != "1" ]; then
"$APKSIGNER" verify --verbose "$OUT"
else
echo "(unsigned build: apksigner verify skipped)"
fi

echo
echo "built: $OUT"
ls -la "$OUT"
sha256sum "$OUT"
