#!/usr/bin/env bash
set -euo pipefail

# Swonal Tonal Screen Mirroring via scrcpy
# Automatically handles device selection, port 5038 isolation, reconnects, and Android 6.0 audio compatibility

MODE="tonal" # tonal (wi-fi), usb, or custom
EXTRA_ARGS=()

while [[ $# -gt 0 ]]; do
  case "$1" in
    --usb|-u)
      MODE="usb"
      shift
      ;;
    --wifi|-w|--tonal)
      MODE="tonal"
      shift
      ;;
    --target|-t)
      TONAL_IP="$2"
      shift 2
      ;;
    *)
      EXTRA_ARGS+=("$1")
      shift
      ;;
  esac
done

if ! command -v scrcpy >/dev/null 2>&1; then
  echo "Error: 'scrcpy' is not installed or not in PATH." >&2
  exit 1
fi

# If isolated on 5038 or default, ensure ADB keys and port are defined
export ANDROID_ADB_SERVER_PORT="${ANDROID_ADB_SERVER_PORT:-5038}"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"

if [[ -f "${ROOT_DIR}/tonal_keys/adbkey" ]]; then
  export ADB_VENDOR_KEYS="${ADB_VENDOR_KEYS:-${ROOT_DIR}/tonal_keys/adbkey}"
fi

# Auto-detect existing Wi-Fi ADB target (:5555) if TONAL_IP is not set via env or --target
if [[ -z "${TONAL_IP:-}" ]]; then
  TONAL_IP="$(adb devices | awk 'NR>1 && $1 ~ /:5555$/ {print $1; exit}')"
fi
if [[ -z "${TONAL_IP:-}" && "${MODE}" == "tonal" ]]; then
  echo "Error: No Tonal Wi-Fi ADB target specified. Set TONAL_IP=<IP>:5555 or pass --target <IP>:5555." >&2
  exit 1
fi

# Check device list
DEVICES=($(adb devices | awk 'NR>1 && $2=="device" {print $1}'))
OFFLINE_DEVICES=($(adb devices | awk 'NR>1 && $2=="offline" {print $1}'))

# If Tonal is offline or missing, disconnect any conflicting default-port (5037) ADB socket and reconnect on 5038
if [[ " ${OFFLINE_DEVICES[*]:-} " =~ " ${TONAL_IP} " ]] || [[ ! " ${DEVICES[*]:-} " =~ " ${TONAL_IP} " && "${MODE}" == "tonal" ]]; then
  echo "==> Ensuring Tonal Wi-Fi ADB connection (${TONAL_IP})..."
  if [[ "${ANDROID_ADB_SERVER_PORT}" != "5037" ]]; then
    ANDROID_ADB_SERVER_PORT=5037 adb disconnect "${TONAL_IP}" >/dev/null 2>&1 || true
  fi
  adb disconnect "${TONAL_IP}" >/dev/null 2>&1 || true
  sleep 1
  adb connect "${TONAL_IP}" || true
  sleep 1
  DEVICES=($(adb devices | awk 'NR>1 && $2=="device" {print $1}'))
  if [[ ! " ${DEVICES[*]:-} " =~ " ${TONAL_IP} " ]]; then
    TONAL_HOST="${TONAL_IP%%:*}"
    echo "==> Requesting adbd reset via tonal-daemon (http://${TONAL_HOST}:8765/api/adb/reset) and reloading ADB server..."
    curl -s --max-time 4 -X POST "http://${TONAL_HOST}:8765/api/adb/reset" >/dev/null 2>&1 || true
    sleep 1
    if [[ "${ANDROID_ADB_SERVER_PORT}" != "5037" ]]; then
      ANDROID_ADB_SERVER_PORT=5037 adb kill-server >/dev/null 2>&1 || true
    fi
    adb kill-server >/dev/null 2>&1 || true
    adb start-server >/dev/null 2>&1
    adb connect "${TONAL_IP}" || true
    sleep 1
    DEVICES=($(adb devices | awk 'NR>1 && $2=="device" {print $1}'))
  fi
fi

if [[ ${#DEVICES[@]} -eq 0 ]]; then
  echo "Error: No authorized ADB devices found on port ${ANDROID_ADB_SERVER_PORT}." >&2
  echo "Ensure the Tonal tablet is powered on and connected via Wi-Fi: adb connect ${TONAL_IP}" >&2
  exit 1
fi

TARGET=""
if [[ "${MODE}" == "usb" ]]; then
  for dev in "${DEVICES[@]}"; do
    if [[ ! "${dev}" =~ :5555$ ]]; then
      TARGET="${dev}"
      break
    fi
  done
  if [[ -z "${TARGET}" ]]; then
    echo "Warning: No USB device detected. Falling back to Tonal Wi-Fi..."
    TARGET="${TONAL_IP}"
  fi
else
  # Default: target Tonal strictly (do not fall back to unrelated USB devices like Facebook Portal)
  if [[ " ${DEVICES[*]} " =~ " ${TONAL_IP} " ]]; then
    TARGET="${TONAL_IP}"
  else
    echo "Error: Tonal (${TONAL_IP}) is not online in ADB (current online devices: ${DEVICES[*]:-none})." >&2
    exit 1
  fi
fi

echo "==> Mirroring ${TARGET} (Tonal Trainer) via scrcpy..."
exec scrcpy -s "${TARGET}" --no-audio --max-fps 30 -m 1080 --video-bit-rate 4M "${EXTRA_ARGS[@]+"${EXTRA_ARGS[@]}"}"
