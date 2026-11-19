#!/usr/bin/env bash
set -euo pipefail

# Swonal All-in-One Tonal Device Installer
# Installs: Native Swonal Kiosk APK, Termux (Android 5/6), optional Bromite 108 APK, librespot, and tonal-daemon (assumes Magisk is already installed)
# Usage:
#   ./scripts/install-device.sh [--target 192.168.1.50:5555] [--server-url http://192.168.1.100:3000] [--apk-dir packages/device-assets] [--kiosk-apk path/to/swonal-kiosk.apk] [--allow-debug] [--allow-downgrade] [--use-bromite]

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
APK_DIR="${ROOT_DIR}/packages/device-assets"
KIOSK_APK=""
ADB_TARGET=""
SERVER_URL="http://192.168.1.100:3000"
USE_BROMITE=0
ALLOW_DEBUG=0
ALLOW_DOWNGRADE=0
SKIP_APKS=0
SKIP_LIBRESPOT=0
SKIP_DAEMON=0

require_arg() {
  if [[ $# -lt 2 || -z "${2:-}" ]]; then
    echo "Error: Option $1 requires a value." >&2
    exit 1
  fi
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --target)
      require_arg "$@"
      ADB_TARGET="$2"
      shift 2
      ;;
    --server-url)
      require_arg "$@"
      SERVER_URL="$2"
      shift 2
      ;;
    --apk-dir)
      require_arg "$@"
      APK_DIR="$2"
      shift 2
      ;;
    --kiosk-apk)
      require_arg "$@"
      KIOSK_APK="$2"
      shift 2
      ;;
    --allow-debug)
      ALLOW_DEBUG=1
      shift
      ;;
    --allow-downgrade)
      ALLOW_DOWNGRADE=1
      shift
      ;;
    --use-bromite)
      USE_BROMITE=1
      shift
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

# Validate SERVER_URL strictly to prevent adb shell injection
if [[ ! "${SERVER_URL}" =~ ^https?://[A-Za-z0-9._:/?=\&%-]+$ ]]; then
  echo "Error: Invalid --server-url '${SERVER_URL}'. Must start with http:// or https:// without shell metacharacters." >&2
  exit 1
fi

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
  local expected_sha256="${4:-}"
  if [[ -f "${dest}" ]]; then
    echo "  [cached] ${label}: ${dest}"
  else
    echo "  [download] ${label} from ${url}..."
    if ! curl -fSL --retry 2 --connect-timeout 15 -o "${dest}.tmp" "${url}"; then
      rm -f "${dest}.tmp"
      echo "  [warn] Failed to download ${label} from ${url}."
      echo "         Place the APK manually at ${dest} and re-run."
      return 1
    fi
    mv "${dest}.tmp" "${dest}"
  fi
  if [[ -n "${expected_sha256}" ]] && command -v sha256sum >/dev/null 2>&1; then
    local actual_sha256
    actual_sha256="$(sha256sum "${dest}" | awk '{print $1}')"
    if [[ "${actual_sha256}" != "${expected_sha256}" ]]; then
      echo "  [error] SHA-256 mismatch for ${dest} (expected ${expected_sha256}, got ${actual_sha256})" >&2
      rm -f "${dest}"
      return 1
    fi
  fi
}

# Auto-discover native swonal-kiosk APK (release by default; debug requires --allow-debug)
if [[ -z "${KIOSK_APK}" ]]; then
  CANDIDATES=(
    "${APK_DIR}/swonal-kiosk.apk"
    "${ROOT_DIR}/packages/android-kiosk/app/build/outputs/apk/release/app-release.apk"
  )
  if [[ "${ALLOW_DEBUG}" -eq 1 ]]; then
    CANDIDATES+=("${ROOT_DIR}/packages/android-kiosk/app/build/outputs/apk/debug/app-debug.apk")
  fi
  for candidate in "${CANDIDATES[@]}"; do
    if [[ -f "${candidate}" ]]; then
      KIOSK_APK="${candidate}"
      break
    fi
  done
fi

INSTALL_FLAGS=(-r)
if [[ "${ALLOW_DOWNGRADE}" -eq 1 ]]; then
  INSTALL_FLAGS+=(-d)
fi

if [[ "${SKIP_APKS}" -eq 0 ]]; then
  echo "==> Step 1/4: Installing APKs (Swonal Native Kiosk, Termux Android 5/6)..."

  TERMUX_APK="${APK_DIR}/termux-android5-v0.119.0-beta.3.apk"
  TERMUX_SHA256="bad6b661128baeb57e4c05dca5cc2a1f18feaee8b594abdbe99ca4e54e7f05d6"
  download_if_missing "${TERMUX_APK}" \
    "https://github.com/termux/termux-app/releases/download/v0.119.0-beta.3/termux-app_v0.119.0-beta.3%2Bapt-android-5-github-debug_universal.apk" \
    "Termux (Android 5/6 Universal)" \
    "${TERMUX_SHA256}" || true

  BROMITE_APK=""
  if [[ "${USE_BROMITE}" -eq 1 || -z "${KIOSK_APK}" ]]; then
    BROMITE_APK="${APK_DIR}/bromite-108-arm64.apk"
    BROMITE_URL="https://github.com/bromite/bromite/releases/download/108.0.5359.156/arm64_ChromePublic.apk"
    BROMITE_SHA256="20585771da0551be5b1fa8dff05b5898ae21c4602a0caba473a67fd3011234cb"
    if [[ "${DEVICE_ABI}" != arm64* ]]; then
      BROMITE_APK="${APK_DIR}/bromite-108-arm.apk"
      BROMITE_URL="https://github.com/bromite/bromite/releases/download/108.0.5359.156/arm_ChromePublic.apk"
      BROMITE_SHA256=""
    fi
    download_if_missing "${BROMITE_APK}" \
      "${BROMITE_URL}" \
      "Bromite 108 (bundled Chromium)" \
      "${BROMITE_SHA256}" || true
  fi

  for apk in "${KIOSK_APK}" "${TERMUX_APK}" "${BROMITE_APK}"; do
    if [[ -n "${apk}" && -f "${apk}" ]]; then
      echo "  [install] ${apk}"
      "${ADB[@]}" install "${INSTALL_FLAGS[@]}" "${apk}" || echo "  [warn] adb install returned non-zero for ${apk} (may already be installed)"
    fi
  done

  # Pre-grant permanent Magisk superuser rights (policy 2 = ALLOW, notification 0 = SILENT)
  echo "  [magisk] Pre-granting permanent root permissions to Swonal Kiosk in Magisk..."
  "${ADB[@]}" shell "su -c '
    KIOSK_UID=\$(dumpsys package com.swonal.kiosk 2>/dev/null | grep userId= | head -n1 | cut -d= -f2 | tr -d \" \r\n\")
    if [ -n \"\$KIOSK_UID\" ]; then
      magisk --sqlite \"INSERT OR REPLACE INTO policies (uid, policy, until, logging, notification) VALUES (\$KIOSK_UID, 2, 0, 1, 0);\" 2>/dev/null || true
    fi
  '"
fi

run_in_termux() {
  local cmd="$1"
  local termux_uid
  termux_uid="$("${ADB[@]}" shell "su -c 'stat -c %u /data/data/com.termux/files 2>/dev/null'" | tr -d '\r\n')"
  if [[ ! "${termux_uid}" =~ ^[0-9]+$ ]]; then
    echo "  [warn] Termux UID not found (open Termux once on device to bootstrap). Refusing to run pkg/pip as root." >&2
    return 1
  fi
  local env_str="PREFIX=/data/data/com.termux/files/usr HOME=/data/data/com.termux/files/home TMPDIR=/data/data/com.termux/files/usr/tmp LD_LIBRARY_PATH=/data/data/com.termux/files/usr/lib PATH=/data/data/com.termux/files/usr/bin:\$PATH"
  "${ADB[@]}" shell "su ${termux_uid} -c '${env_str} ${cmd}'"
}

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
    "${ADB[@]}" shell "su -c 'mkdir -p /data/local/bin /data/local/librespot-cache /data/adb/service.d && mv /data/local/tmp/librespot /data/local/bin/librespot && chown 0:0 /data/local/bin/librespot && chmod 0755 /data/local/bin/librespot'"
    LIBRESPOT_BIN="/data/local/bin/librespot"

    if [[ -d "${APK_DIR}/alsa" ]]; then
      echo "  [push] Pushing ALSA configuration -> /data/local/alsa"
      ALSA_TAR="$(mktemp --suffix=.tar)"
      tar -cf "${ALSA_TAR}" -C "${APK_DIR}" alsa
      "${ADB[@]}" push "${ALSA_TAR}" /data/local/tmp/alsa.tar
      rm -f "${ALSA_TAR}"
      "${ADB[@]}" shell "su -c 'tar -xf /data/local/tmp/alsa.tar -C /data/local/ && rm -f /data/local/tmp/alsa.tar && chown -R 0:0 /data/local/alsa && chmod -R go-w /data/local/alsa'"
    fi
  else
    echo "  [termux] Installing librespot inside Termux (place a static binary at ${LIBRESPOT_LOCAL} to use /data/local/bin instead)..."
    run_in_termux "pkg update -y && pkg install -y librespot" || \
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

# Multicast route required for libmdns Zeroconf discovery
ip route add 224.0.0.0/4 dev wlan0 2>/dev/null || true

# Ensure DNS resolution works for standalone musl binaries
if [ ! -f "/system/etc/resolv.conf" ]; then
  mkdir -p /data/local/etc
  cp -a /system/etc/* /data/local/etc/ 2>/dev/null || true
  DNS1=\$(getprop net.dns1)
  DNS2=\$(getprop net.dns2)
  echo "nameserver \${DNS1:-1.1.1.1}" > /data/local/etc/resolv.conf
  [ -n "\${DNS2}" ] && echo "nameserver \${DNS2}" >> /data/local/etc/resolv.conf
  echo "nameserver 8.8.8.8" >> /data/local/etc/resolv.conf
  echo "127.0.0.1 localhost" > /data/local/etc/hosts
  echo "::1 localhost" >> /data/local/etc/hosts
  chmod 0644 /data/local/etc/resolv.conf /data/local/etc/hosts
  mount -o bind /data/local/etc /system/etc
fi

export PREFIX="/data/data/com.termux/files/usr"
export LD_LIBRARY_PATH="/data/data/com.termux/files/usr/lib:\${LD_LIBRARY_PATH:-}"
export PATH="/data/data/com.termux/files/usr/bin:/data/local/bin:\$PATH"
export ALSA_CONFIG_DIR="/data/local/alsa"
export ALSA_CONFIG_PATH="/data/local/alsa/alsa.conf"
mkdir -p /data/local/librespot-cache
if [ -x "${LIBRESPOT_BIN}" ]; then
  pkill -9 librespot || true
  nohup "${LIBRESPOT_BIN}" -v --name "Tonal Speakers" --bitrate 320 --backend rodio --cache /data/local/librespot-cache --system-cache /data/local/librespot-cache > /data/local/swonal-librespot.log 2>&1 &
fi
EOF
  "${ADB[@]}" push "${BOOT_LIBRESPOT}" /data/local/tmp/99-swonal-librespot.sh
  rm -f "${BOOT_LIBRESPOT}"
  "${ADB[@]}" shell "su -c 'mkdir -p /data/adb/service.d && mv /data/local/tmp/99-swonal-librespot.sh /data/adb/service.d/99-swonal-librespot.sh && chown 0:0 /data/adb/service.d/99-swonal-librespot.sh && chmod 0700 /data/adb/service.d/99-swonal-librespot.sh'"
  "${ADB[@]}" shell "su -c 'sh /data/adb/service.d/99-swonal-librespot.sh'" || true
fi

if [[ "${SKIP_DAEMON}" -eq 0 ]]; then
  echo "==> Step 3/4: Deploying tonal-daemon, hardware isolation & persistent Wireless ADB..."
  "${ADB[@]}" shell "su -c 'mkdir -p /data/swonal/packages/tonal-daemon /data/adb/service.d /data/adb/post-fs-data.d'"

  # Configure persistent Wireless ADB on port 5555
  BOOT_ADBD="$(mktemp)"
  cat > "${BOOT_ADBD}" << 'EOF'
#!/system/bin/sh
# 00-swonal-adbd.sh - Persistent Wireless ADB & Hardware Isolation Watchdog

setprop persist.adb.tcp.port 5555
setprop service.adb.tcp.port 5555
setprop persist.sys.usb.config adb
setprop sys.usb.config adb

stop adbd
sleep 1
start adbd

# Early shutdown and disable of stock MCB service
pm disable com.riptlabs.mcb.service 2>/dev/null || true
am force-stop com.riptlabs.mcb.service 2>/dev/null || true

if [ -c /dev/ttyACM0 ]; then
    chown 0:0 /dev/ttyACM0
    chmod 0600 /dev/ttyACM0
fi

# Background watcher to ensure adbd stays bound once Wi-Fi associates
(
    while [ "$(getprop sys.boot_completed)" != "1" ]; do
        sleep 2
    done

    pm disable com.riptlabs.mcb.service 2>/dev/null || true
    am force-stop com.riptlabs.mcb.service 2>/dev/null || true

    if [ -c /dev/ttyACM0 ]; then
        chown 0:0 /dev/ttyACM0
        chmod 0600 /dev/ttyACM0
    fi

    for i in $(seq 1 60); do
        WLAN_IP=$(ip -4 addr show dev wlan0 2>/dev/null | grep -o 'inet [0-9.]*' | cut -d' ' -f2)
        if [ -n "$WLAN_IP" ]; then
            setprop persist.adb.tcp.port 5555
            setprop service.adb.tcp.port 5555
            stop adbd
            sleep 1
            start adbd
            break
        fi
        sleep 2
    done

    # Add multicast route for Spotify Connect / zeroconf discovery
    ip route add 224.0.0.0/4 dev wlan0 2>/dev/null || true

    # Continuous hardware serial shield
    while true; do
        if [ -c /dev/ttyACM0 ]; then
            chmod 0600 /dev/ttyACM0
            chown 0:0 /dev/ttyACM0
        fi
        pm disable com.riptlabs.mcb.service 2>/dev/null || true
        am force-stop com.riptlabs.mcb.service 2>/dev/null || true
        sleep 10
    done
) &
EOF
  "${ADB[@]}" push "${BOOT_ADBD}" /data/local/tmp/00-swonal-adbd.sh
  rm -f "${BOOT_ADBD}"
  "${ADB[@]}" shell "su -c 'mv /data/local/tmp/00-swonal-adbd.sh /data/adb/service.d/00-swonal-adbd.sh && chown 0:0 /data/adb/service.d/00-swonal-adbd.sh && chmod 0700 /data/adb/service.d/00-swonal-adbd.sh'"

  # Post-FS-Data early property setter
  BOOT_POST_FS="$(mktemp)"
  cat > "${BOOT_POST_FS}" << 'EOF'
#!/system/bin/sh
resetprop persist.adb.tcp.port 5555 2>/dev/null || setprop persist.adb.tcp.port 5555
resetprop service.adb.tcp.port 5555 2>/dev/null || setprop service.adb.tcp.port 5555
resetprop persist.sys.usb.config adb 2>/dev/null || true
resetprop sys.usb.config adb 2>/dev/null || true

if [ -c /dev/ttyACM0 ]; then
    chown 0:0 /dev/ttyACM0
    chmod 0600 /dev/ttyACM0
fi
EOF
  "${ADB[@]}" push "${BOOT_POST_FS}" /data/local/tmp/00-swonal-adbd-postfs.sh
  rm -f "${BOOT_POST_FS}"
  "${ADB[@]}" shell "su -c 'mv /data/local/tmp/00-swonal-adbd-postfs.sh /data/adb/post-fs-data.d/00-swonal-adbd.sh && chown 0:0 /data/adb/post-fs-data.d/00-swonal-adbd.sh && chmod 0700 /data/adb/post-fs-data.d/00-swonal-adbd.sh'"

  # Force stock Tonal off /dev/ttyACM0 immediately
  "${ADB[@]}" shell "su -c '
    pm disable com.riptlabs.mcb.service 2>/dev/null || true
    am force-stop com.riptlabs.mcb.service 2>/dev/null || true
    am force-stop com.tonal.app 2>/dev/null || true
    am force-stop com.tonal.apps.machineclient2.beta 2>/dev/null || true
    if [ -c /dev/ttyACM0 ]; then
      chown 0:0 /dev/ttyACM0
      chmod 0600 /dev/ttyACM0
    fi
  '"

  TMP_TAR="$(mktemp --suffix=.tar)"
  tar -cf "${TMP_TAR}" \
    --exclude='.venv' --exclude='.pytest_cache' --exclude='__pycache__' \
    -C "${ROOT_DIR}/packages/tonal-daemon" .
  "${ADB[@]}" push "${TMP_TAR}" /data/local/tmp/tonal-daemon.tar
  rm -f "${TMP_TAR}"
  "${ADB[@]}" shell "su -c 'tar --no-same-owner -xf /data/local/tmp/tonal-daemon.tar -C /data/swonal/packages/tonal-daemon && rm -f /data/local/tmp/tonal-daemon.tar && chown -R 0:0 /data/swonal/packages/tonal-daemon && chmod -R go-w /data/swonal/packages/tonal-daemon'"
  "${ADB[@]}" push "${ROOT_DIR}/packages/tonal-daemon/start-daemon.sh" /data/local/tmp/98-swonal-daemon.sh
  "${ADB[@]}" shell "su -c 'mv /data/local/tmp/98-swonal-daemon.sh /data/adb/service.d/98-swonal-daemon.sh && chown 0:0 /data/adb/service.d/98-swonal-daemon.sh && chmod 0700 /data/adb/service.d/98-swonal-daemon.sh'"
  echo "  [termux] Installing tonal-daemon & swolectl Python dependencies in Termux..."
  run_in_termux "pkg install -y python git && pip install -e /data/swonal/packages/tonal-daemon" || \
    echo "  [warn] Open Termux once on the Tonal screen to finish bootstrap, then run: pkg install -y python git && pip install -e /data/swonal/packages/tonal-daemon"
fi

if [[ "${USE_BROMITE}" -eq 0 && -n "${KIOSK_APK}" ]]; then
  echo "==> Step 4/4: Launching Swonal Native Kiosk (${SERVER_URL})..."
  "${ADB[@]}" shell "am start -n com.swonal.kiosk/.ui.MainActivity --es server_url '${SERVER_URL}'" || \
    echo "  [info] Launch Swonal Kiosk manually from the Android launcher"
else
  echo "==> Step 4/4: Launching Bromite to ${SERVER_URL}..."
  "${ADB[@]}" shell "am start -a android.intent.action.VIEW -d '${SERVER_URL}' org.bromite.bromite" || \
    echo "  [info] Open Bromite manually and navigate to ${SERVER_URL}"
fi

echo ""
echo "All-in-one device setup complete!"
echo "  - Tonal Gym (librespot) boot script: /data/adb/service.d/99-swonal-librespot.sh"
echo "  - tonal-daemon boot script:          /data/adb/service.d/98-swonal-daemon.sh"
if [[ "${USE_BROMITE}" -eq 0 && -n "${KIOSK_APK}" ]]; then
  echo "  - Native Android Kiosk APK:          ${KIOSK_APK} -> ${SERVER_URL}"
else
  echo "  - Kiosk URL in Bromite:              ${SERVER_URL}"
fi
