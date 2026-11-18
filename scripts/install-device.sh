#!/usr/bin/env bash
set -euo pipefail

# Swonal All-in-One Tonal Device Installer
# Installs: Termux (Android 5/6), Bromite 108 / Firefox 68 ESR APK, librespot, and tonal-daemon (assumes Magisk is already installed)
# Usage:
#   ./scripts/install-device.sh [--target 192.168.1.50:5555] [--server-url https://swonal.brombomb.com] [--apk-dir packages/device-assets] [--browser bromite|firefox]

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
APK_DIR="${ROOT_DIR}/packages/device-assets"
ADB_TARGET=""
SERVER_URL="http://localhost:3000"
SKIP_APKS=0
SKIP_LIBRESPOT=0
SKIP_DAEMON=0
BROWSER="bromite"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --target)
      ADB_TARGET="${2:-}"
      shift 2
      ;;
    --server-url)
      SERVER_URL="${2:-}"
      shift 2
      ;;
    --apk-dir)
      APK_DIR="${2:-}"
      shift 2
      ;;
    --skip-apks)
      SKIP_APKS=1
      shift
      ;;
    --skip-librespot)
      SKIP_LIBRESPOT=1
      shift
      ;;
    --skip-daemon)
      SKIP_DAEMON=1
      shift
      ;;
    --browser)
      BROWSER="${2:-bromite}"
      shift 2
      ;;
    -h|--help)
      sed -n '3,10p' "$0"
      exit 0
      ;;
    *)
      echo "Unknown option: $1" >&2
      exit 1
      ;;
  esac
done

if ! command -v adb >/dev/null 2>&1; then
  echo "Error: 'adb' is not installed or not in PATH. Install Android platform-tools first." >&2
  exit 1
fi

ADB=(adb)
if [[ -n "${ADB_TARGET}" ]]; then
  echo "==> Connecting to ADB target ${ADB_TARGET}..."
  adb connect "${ADB_TARGET}"
  ADB=(adb -s "${ADB_TARGET}")
fi

echo "==> Waiting for Tonal ADB device..."
"${ADB[@]}" wait-for-device
DEVICE_ABI="$("${ADB[@]}" shell getprop ro.product.cpu.abi | tr -d '\r')"
echo "==> Detected device ABI: ${DEVICE_ABI}"

mkdir -p "${APK_DIR}"

download_if_missing() {
  local dest="$1"
  local url="$2"
  local label="$3"
  if [[ -f "${dest}" ]]; then
    echo "  [cached] ${label}: ${dest}"
    return 0
  fi
  echo "  [download] ${label} from ${url}..."
  if ! curl -fSL --retry 2 --connect-timeout 15 -o "${dest}.tmp" "${url}"; then
    rm -f "${dest}.tmp"
    echo "  [warn] Failed to download ${label} from ${url}."
    echo "         Place the APK manually at ${dest} and re-run."
    return 1
  fi
  mv "${dest}.tmp" "${dest}"
}

if [[ "${SKIP_APKS}" -eq 0 ]]; then
  echo "==> Step 1/4: Downloading & installing APKs (Termux Android 5/6, Standalone Browser)..."

  TERMUX_APK="${APK_DIR}/termux-android5-v0.119.0-beta.3.apk"
  FIREFOX_APK="${APK_DIR}/fennec-68.11.0.en-US.android-aarch64.apk"
  BROMITE_APK="${APK_DIR}/bromite-108-arm64.apk"

  download_if_missing "${TERMUX_APK}" \
    "https://github.com/termux/termux-app/releases/download/v0.119.0-beta.3/termux-app_v0.119.0-beta.3%2Bapt-android-5-github-debug_universal.apk" \
    "Termux (Android 5/6 Universal)" || true

  BROWSER_APK=""
  if [[ "${BROWSER}" == "bromite" ]]; then
    if [[ "${DEVICE_ABI}" == arm64* ]]; then
      BROMITE_URL="https://github.com/bromite/bromite/releases/download/108.0.5359.156/arm64_ChromePublic.apk"
    else
      BROMITE_APK="${APK_DIR}/bromite-108-arm.apk"
      BROMITE_URL="https://github.com/bromite/bromite/releases/download/108.0.5359.156/arm_ChromePublic.apk"
    fi
    if download_if_missing "${BROMITE_APK}" "${BROMITE_URL}" "Bromite 108 (bundled Chromium)"; then
      BROWSER_APK="${BROMITE_APK}"
    fi
  fi

  if [[ -z "${BROWSER_APK}" ]]; then
    download_if_missing "${FIREFOX_APK}" \
      "https://archive.mozilla.org/pub/mobile/releases/68.11.0/android-aarch64/en-US/fennec-68.11.0.en-US.android-aarch64.apk" \
      "Firefox 68.11.0 ESR (bundled Gecko)" || true
    if [[ -f "${FIREFOX_APK}" ]]; then
      BROWSER_APK="${FIREFOX_APK}"
      BROWSER="firefox"
    fi
  fi

  for apk in "${TERMUX_APK}" "${BROWSER_APK}"; do
    if [[ -n "${apk}" && -f "${apk}" ]]; then
      echo "  [install] ${apk}"
      "${ADB[@]}" install -r -d "${apk}" || echo "  [warn] adb install returned non-zero for ${apk} (may already be installed)"
    fi
  done
fi

if [[ "${SKIP_LIBRESPOT}" -eq 0 ]]; then
  echo "==> Step 2/4: Installing librespot Spotify Connect receiver..."
  LIBRESPOT_LOCAL=""
  if [[ "${DEVICE_ABI}" == arm64* ]]; then
    LIBRESPOT_LOCAL="${APK_DIR}/librespot-aarch64"
  else
    LIBRESPOT_LOCAL="${APK_DIR}/librespot-armv7"
  fi

  if [[ -f "${LIBRESPOT_LOCAL}" ]]; then
    echo "  [push] Pushing local binary ${LIBRESPOT_LOCAL} -> /data/local/bin/librespot"
    "${ADB[@]}" push "${LIBRESPOT_LOCAL}" /data/local/tmp/librespot
    "${ADB[@]}" shell "su -c 'mkdir -p /data/local/bin /data/adb/service.d && mv /data/local/tmp/librespot /data/local/bin/librespot && chmod 755 /data/local/bin/librespot'"
    LIBRESPOT_BIN="/data/local/bin/librespot"
  else
    echo "  [termux] Installing librespot inside Termux (place a static binary at ${LIBRESPOT_LOCAL} to use /data/local/bin instead)..."
    "${ADB[@]}" shell "su -c 'PATH=/data/data/com.termux/files/usr/bin:\$PATH pkg update -y && pkg install -y librespot'" || \
      echo "  [warn] Open Termux once on the Tonal screen to finish bootstrap, then run: pkg install -y librespot"
    LIBRESPOT_BIN="/data/data/com.termux/files/usr/bin/librespot"
  fi

  echo "  [boot] Installing Magisk boot service /data/adb/service.d/99-swonal-librespot.sh"
  BOOT_LIBRESPOT="$(mktemp)"
  cat > "${BOOT_LIBRESPOT}" <<EOF
#!/system/bin/sh
until [ "\$(getprop sys.boot_completed)" -eq 1 ]; do
  sleep 2
done
export PATH="/data/data/com.termux/files/usr/bin:/data/local/bin:\$PATH"
if [ -x "${LIBRESPOT_BIN}" ]; then
  nohup "${LIBRESPOT_BIN}" --name "Tonal Gym" --bitrate 320 --backend rodio > /data/local/swonal-librespot.log 2>&1 &
fi
EOF
  "${ADB[@]}" push "${BOOT_LIBRESPOT}" /data/local/tmp/99-swonal-librespot.sh
  rm -f "${BOOT_LIBRESPOT}"
  "${ADB[@]}" shell "su -c 'mkdir -p /data/adb/service.d && mv /data/local/tmp/99-swonal-librespot.sh /data/adb/service.d/99-swonal-librespot.sh && chmod 755 /data/adb/service.d/99-swonal-librespot.sh'"
  "${ADB[@]}" shell "su -c 'sh /data/adb/service.d/99-swonal-librespot.sh'" || true
fi

if [[ "${SKIP_DAEMON}" -eq 0 ]]; then
  echo "==> Step 3/4: Deploying tonal-daemon & boot service..."
  "${ADB[@]}" shell "su -c 'mkdir -p /data/swonal/packages/tonal-daemon /data/adb/service.d'"
  TMP_TAR="$(mktemp --suffix=.tar.gz)"
  tar -czf "${TMP_TAR}" \
    --exclude='.venv' --exclude='.pytest_cache' --exclude='__pycache__' \
    -C "${ROOT_DIR}/packages/tonal-daemon" .
  "${ADB[@]}" push "${TMP_TAR}" /data/local/tmp/tonal-daemon.tar.gz
  rm -f "${TMP_TAR}"
  "${ADB[@]}" shell "su -c 'tar -xzf /data/local/tmp/tonal-daemon.tar.gz -C /data/swonal/packages/tonal-daemon && rm -f /data/local/tmp/tonal-daemon.tar.gz'"
  "${ADB[@]}" push "${ROOT_DIR}/packages/tonal-daemon/start-daemon.sh" /data/local/tmp/98-swonal-daemon.sh
  "${ADB[@]}" shell "su -c 'mv /data/local/tmp/98-swonal-daemon.sh /data/adb/service.d/98-swonal-daemon.sh && chmod 755 /data/adb/service.d/98-swonal-daemon.sh'"
  echo "  [termux] Installing tonal-daemon & swolectl Python dependencies in Termux..."
  "${ADB[@]}" shell "su -c 'PATH=/data/data/com.termux/files/usr/bin:\$PATH pkg install -y python git && pip install -e /data/swonal/packages/tonal-daemon'" || \
    echo "  [warn] Open Termux once on the Tonal screen to finish bootstrap, then run: pkg install -y python git && pip install -e /data/swonal/packages/tonal-daemon"
fi

if [[ "${BROWSER}" == "bromite" ]]; then
  BROWSER_PKG="org.bromite.bromite"
  BROWSER_LABEL="Bromite"
else
  BROWSER_PKG="org.mozilla.firefox"
  BROWSER_LABEL="Firefox"
fi

echo "==> Step 4/4: Launching ${BROWSER_LABEL} to ${SERVER_URL}..."
"${ADB[@]}" shell "am start -a android.intent.action.VIEW -d '${SERVER_URL}' ${BROWSER_PKG}" || \
  echo "  [info] Open ${BROWSER_LABEL} manually and navigate to ${SERVER_URL}"

echo ""
echo "All-in-one device setup complete!"
echo "  - Tonal Gym (librespot) boot script: /data/adb/service.d/99-swonal-librespot.sh"
echo "  - tonal-daemon boot script:          /data/adb/service.d/98-swonal-daemon.sh"
echo "  - Kiosk URL in ${BROWSER_LABEL}:     ${SERVER_URL}"
