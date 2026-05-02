#!/usr/bin/env bash
set -euo pipefail

KEYSTORE="${KEYSTORE:-$HOME/.android/pixelplayer-release.jks}"
KEY_ALIAS="${KEY_ALIAS:-pixelplayer}"
STORE_PASS="${STORE_PASS:-android}"
KEY_PASS="${KEY_PASS:-android}"
BUILD_TYPE="${BUILD_TYPE:-release}"

APKSIGNER="$(ls "$HOME/Library/Android/sdk/build-tools"/*/apksigner 2>/dev/null | sort -V | tail -1)"
APK_OUT="app/build/outputs/apk/$BUILD_TYPE"

if [ -z "$APKSIGNER" ]; then
  echo "ERROR: apksigner not found — install Android build-tools via SDK Manager" >&2
  exit 1
fi

export JAVA_HOME="${JAVA_HOME:-$(/usr/libexec/java_home 2>/dev/null)}"

# Generate keystore if missing
if [ ! -f "$KEYSTORE" ]; then
  mkdir -p "$(dirname "$KEYSTORE")"
  echo "Generating keystore at $KEYSTORE ..."
  keytool -genkeypair -v \
    -keystore "$KEYSTORE" \
    -alias "$KEY_ALIAS" \
    -keyalg RSA -keysize 2048 -validity 10000 \
    -storepass "$STORE_PASS" -keypass "$KEY_PASS" \
    -dname "CN=PixelPlayer,O=Debug,C=US"
fi

echo "Building $BUILD_TYPE APK..."
./gradlew "app:assemble$(tr '[:lower:]' '[:upper:]' <<< "${BUILD_TYPE:0:1}")${BUILD_TYPE:1}"

echo ""
echo "Signing APKs..."
for apk in "$APK_OUT"/app-*-"$BUILD_TYPE".apk; do
  [ -f "$apk" ] || continue
  out="${apk/-$BUILD_TYPE.apk/-$BUILD_TYPE-signed.apk}"
  "$APKSIGNER" sign \
    --ks "$KEYSTORE" --ks-key-alias "$KEY_ALIAS" \
    --ks-pass "pass:$STORE_PASS" --key-pass "pass:$KEY_PASS" \
    --out "$out" "$apk" 2>/dev/null
  echo "  $out"
done

echo ""
echo "Done."
