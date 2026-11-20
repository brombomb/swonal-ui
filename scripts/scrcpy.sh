#!/usr/bin/env bash
set -euo pipefail

# Swonal Tonal Screen Mirroring via scrcpy
# Automatically handles device selection (USB or Wi-Fi) and Android 6.0 audio compatibility

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

TONAL_IP="192.168.0.140:5555"

# Detect attached devices
DEVICES=($(adb devices | awk 'NR>1 && $2=="device" {print $1}'))

# If Tonal is not in attached devices, try connecting
if [[ ! " ${DEVICES[*]:-} " =~ " ${TONAL_IP} " ]] && [[ "${MODE}" == "tonal" ]]; then
  echo "==> Connecting to Tonal Wi-Fi ADB (${TONAL_IP})..."
  adb connect "${TONAL_IP}" || true
  sleep 1
  DEVICES=($(adb devices | awk 'NR>1 && $2=="device" {print $1}'))
fi

if [[ ${#DEVICES[@]} -eq 0 ]]; then
  echo "Error: No authorized ADB devices found." >&2
  echo "Ensure the Tonal tablet is powered on and connected via Wi-Fi (adb connect ${TONAL_IP})." >&2
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
  # Default: target Tonal
  if [[ " ${DEVICES[*]} " =~ " ${TONAL_IP} " ]]; then
    TARGET="${TONAL_IP}"
  else
    TARGET="${DEVICES[0]}"
  fi
fi

echo "==> Launching scrcpy on device: ${TARGET} (Tonal Trainer)..."
exec scrcpy -s "${TARGET}" --no-audio --max-fps 30 -m 1080 --video-bit-rate 4M "${EXTRA_ARGS[@]+"${EXTRA_ARGS[@]}"}"
