#!/usr/bin/env bash
# build.sh — PixelPlayer-EQ fork build helper
#
# Usage:
#   bash build.sh                  # release build, sign with local.properties keys
#   bash build.sh --easy-build     # debug build, no keys needed — installs directly
#   bash build.sh --help

set -euo pipefail

EASY_BUILD=false
for arg in "$@"; do
  case "$arg" in
    --easy-build) EASY_BUILD=true ;;
    --help|-h)
      echo "Usage: bash build.sh [--easy-build]"
      echo ""
      echo "  (no flags)      Release build signed with keys from local.properties"
      echo "  --easy-build    Debug build — no keystore required, installs via adb if a device is connected"
      exit 0
      ;;
    *) echo "Unknown argument: $arg" >&2; exit 1 ;;
  esac
done

# ── Resolve JAVA_HOME ────────────────────────────────────────────────────────
if [ -z "${JAVA_HOME:-}" ]; then
  if command -v /usr/libexec/java_home &>/dev/null; then
    export JAVA_HOME="$(/usr/libexec/java_home 2>/dev/null)"
  elif command -v java &>/dev/null; then
    export JAVA_HOME="$(java -XshowSettings:property -version 2>&1 | awk -F'= ' '/java.home/{print $2}')"
  fi
fi

# ── Easy build (debug, no keys) ──────────────────────────────────────────────
if $EASY_BUILD; then
  echo "==> Easy build: assembling debug APK (no keystore required)..."
  ./gradlew app:assembleDebug

  APK="app/build/outputs/apk/debug/app-arm64-v8a-debug.apk"
  [ -f "$APK" ] || APK="$(find app/build/outputs/apk/debug -name '*.apk' | head -1)"

  echo ""
  echo "==> Build complete: $APK"

  if adb get-state &>/dev/null 2>&1; then
    echo "==> Device detected — installing..."
    adb install -r "$APK"
    echo "==> Installed. Launch with:"
    echo "    adb shell am start -n com.dev4ones_space_fork.pixelplay_fx/com.theveloper.pixelplay.MainActivity"
  else
    echo "==> No ADB device connected. To install manually:"
    echo "    adb install -r \"$APK\""
  fi
  exit 0
fi

# ── Release build ────────────────────────────────────────────────────────────
LOCAL_PROPS="local.properties"
if [ ! -f "$LOCAL_PROPS" ]; then
  echo "ERROR: local.properties not found." >&2
  echo "Create it with:" >&2
  echo "  STORE_FILE=<path-to.jks>" >&2
  echo "  STORE_PASSWORD=<password>" >&2
  echo "  KEY_ALIAS=<alias>" >&2
  echo "  KEY_PASSWORD=<password>" >&2
  exit 1
fi

# Read signing values from local.properties
read_prop() { grep "^$1=" "$LOCAL_PROPS" | cut -d'=' -f2-; }
STORE_FILE="$(read_prop STORE_FILE)"
STORE_PASS="$(read_prop STORE_PASSWORD)"
KEY_ALIAS="$(read_prop KEY_ALIAS)"
KEY_PASS="$(read_prop KEY_PASSWORD)"

if [ -z "$STORE_FILE" ] || [ -z "$STORE_PASS" ] || [ -z "$KEY_ALIAS" ] || [ -z "$KEY_PASS" ]; then
  echo "ERROR: local.properties is missing one or more signing fields:" >&2
  echo "  STORE_FILE, STORE_PASSWORD, KEY_ALIAS, KEY_PASSWORD" >&2
  exit 1
fi

# Resolve keystore path relative to app/ if it's not absolute
KEYSTORE="$STORE_FILE"
[ -f "$KEYSTORE" ] || KEYSTORE="app/$STORE_FILE"
if [ ! -f "$KEYSTORE" ]; then
  echo "ERROR: Keystore not found at '$STORE_FILE' or 'app/$STORE_FILE'" >&2
  exit 1
fi

echo "==> Building release APK..."
./gradlew app:assembleRelease

APK_DIR="app/build/outputs/apk/release"
echo ""
echo "==> Release APKs:"
for apk in "$APK_DIR"/app-*-release.apk; do
  [ -f "$apk" ] || continue
  echo "    $apk"
done

echo ""
echo "==> Done."
