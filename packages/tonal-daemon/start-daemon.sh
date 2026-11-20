#!/system/bin/sh
# Ensure persistent Wireless ADB on port 5555 without dropping active session
setprop persist.adb.tcp.port 5555
setprop persist.sys.usb.config adb
setprop sys.usb.config adb
if [ "$(getprop service.adb.tcp.port)" != "5555" ]; then
    setprop service.adb.tcp.port 5555
    stop adbd
    sleep 1
    start adbd
fi

# Early suppression of stock Tonal MCB motor service before it can touch /dev/ttyACM0
pm disable com.riptlabs.mcb.service 2>/dev/null || true
am force-stop com.riptlabs.mcb.service 2>/dev/null || true
am force-stop com.tonal.app 2>/dev/null || true
am force-stop com.tonal.apps.machineclient2.beta 2>/dev/null || true

# Restrict motor controller serial device strictly to root (UID 0)
if [ -c /dev/ttyACM0 ]; then
    chown 0:0 /dev/ttyACM0
    chmod 0600 /dev/ttyACM0
fi

# Wait for Android boot completion
while [ "$(getprop sys.boot_completed)" != "1" ]; do
    sleep 2
done

# Secondary enforcement after boot completion
pm disable com.riptlabs.mcb.service 2>/dev/null || true
am force-stop com.riptlabs.mcb.service 2>/dev/null || true
am force-stop com.tonal.apps.machineclient2.beta 2>/dev/null || true

if [ -c /dev/ttyACM0 ]; then
    chown 0:0 /dev/ttyACM0
    chmod 0600 /dev/ttyACM0
fi

# Refresh Wi-Fi ADB after network is fully up if needed
if [ "$(getprop service.adb.tcp.port)" != "5555" ]; then
    setprop service.adb.tcp.port 5555
    stop adbd
    sleep 1
    start adbd
fi

# Path initialization for Termux / embedded Python runtime
export HOME="/data/data/com.termux/files/home"
export PREFIX="/data/data/com.termux/files/usr"
export LD_LIBRARY_PATH="/data/data/com.termux/files/usr/lib:${LD_LIBRARY_PATH:-}"
export PATH="/data/data/com.termux/files/usr/bin:/data/local/bin:$PATH"
export PYTHONHOME="/data/data/com.termux/files/usr"

DAEMON_DIR="/data/swonal/packages/tonal-daemon"

if [ -d "$DAEMON_DIR" ]; then
    cd "$DAEMON_DIR" || exit 1
    pkill -f "src.main" || true
    # Rotate log if it exceeds 5 MB to prevent device flash exhaustion
    if [ -f /data/swonal/daemon.log ] && [ "$(wc -c < /data/swonal/daemon.log 2>/dev/null || echo 0)" -gt 5242880 ]; then
        tail -n 2000 /data/swonal/daemon.log > /data/swonal/daemon.log.tmp 2>/dev/null && mv /data/swonal/daemon.log.tmp /data/swonal/daemon.log 2>/dev/null
    fi
    nohup python3 -m src.main >> /data/swonal/daemon.log 2>&1 </dev/null &
fi
