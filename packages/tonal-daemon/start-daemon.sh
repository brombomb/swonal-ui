#!/system/bin/sh
# Wait for Android boot completion and USB CDC device availability
until [ "$(getprop sys.boot_completed)" -eq 1 ]; do
    sleep 2
done

until [ -c /dev/ttyACM0 ]; do
    sleep 1
done

# Set permissions for execution context
chmod 0666 /dev/ttyACM0

# Path initialization for Termux / embedded Python runtime
export PREFIX="/data/data/com.termux/files/usr"
export LD_LIBRARY_PATH="/data/data/com.termux/files/usr/lib:${LD_LIBRARY_PATH:-}"
export PATH="/data/data/com.termux/files/usr/bin:$PATH"
export PYTHONHOME="/data/data/com.termux/files/usr"

DAEMON_DIR="/data/swonal/packages/tonal-daemon"

cd "$DAEMON_DIR" || exit 1
nohup python3 -m src.main > /data/swonal/daemon.log 2>&1 &
