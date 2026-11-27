# **Tonal V1 Rooting & On-Device Setup Runbook (Zero External Hardware)**

Complete Guide to Rooting Tonal V1 (`tonal-freedom`), Blocking OTA Updates, and Installing the On-Device `swonal-ui` Stack

# **Hardware & High-Force Motor Safety Warnings**

1. **Unplug Power Before Opening:** Always switch off the Tonal's main power breaker and unplug the AC cord before removing the service cover.  
2. **USB VBUS Triggers Motor Initialization:** Even with the Android tablet being flashed, applying 5V USB VBUS to the internal motor controller can cause initialization and sudden arm movement. Keep the Tonal's main AC power **OFF** while flashing the MediaTek board over micro-USB if your board powers up from your laptop's USB port in BROM mode.  
3. **If BROM Needs the Tonal Switched On, Treat the Machine as Live:** On the Tonal V1 units reported so far, the MediaTek board does **not** come up on USB power alone (no USB device appears with AC off, across several cables). BROM is then reached by starting `mtkclient` first and **switching the Tonal on** while it waits, which also powers the motor controller. Before switching on: close and secure the service door with the micro-USB cable routed out (no high voltage exposed), fold both arms in, keep hands and body clear, and keep your laptop outside the cabinet. Switch the Tonal off as soon as each `mtkclient` command finishes.  
4. **Never Lose Your Original Dump:** Do not flash anything until your virgin `boot.bin` and partition backups are verified (exact sizes and SHA-256 checksums, see Phase 1 step 3) and copied off the flashing machine.

# **Prerequisites**

* **A Linux or macOS Laptop** (Ubuntu/Debian works cleanly with `mtkclient` and `udev` rules).  
* **Micro-USB Data Cable** (verify it passes data, not just power) \+ **Phillips screwdriver / hex key** for the Tonal top/back service access panel.  
* **Files downloaded to your laptop:**  
  * `mtkclient` repository  
  * `sedwardsnowden-hub/tonal-freedom` repository scripts  
  * **Magisk v27.0 APK** (`Magisk-v27.0.apk`)  
  * **Termux APK** (Android 5/6 legacy compatible build — bundled in `packages/device-assets/` and auto-installed by `scripts/install-device.sh`)  
  * **Swonal Native Kiosk APK** (`swonal-kiosk.apk` from [GitHub Releases](https://github.com/brombomb/swonal-ui/releases) — includes built-in GeckoView browser engine & `:3000` embedded Companion server; Firefox/Bromite are no longer required)

# **Phase 1: Dump Partitions & Root with `mtkclient` (`tonal-freedom`)**

## **1\. Power Down & Expose the MediaTek Service Port**

* Turn off the Tonal power switch and unplug the AC power cable from the wall.  
* Remove the service access cover to expose the main Android logic board. Locate the **micro-USB port** on the MediaTek board (do **not** unplug the internal USB serial cable going to the motor controller yet, or if you do, label its port).  
* Plug the micro-USB cable into the board and route it out of the cabinet so the door closes without pinching it. If your board needs the Tonal switched on for BROM (Safety Warning 3), close and secure the service door before plugging the AC cord back in, and leave the power switch off for now.

## **2\. Install `mtkclient` on Your Laptop**

```
git clone https://github.com/bkerler/mtkclient.git
cd mtkclient
pip install -r requirements.txt
```

## **3\. Enter BROM Mode & Back Up Your Stock Partitions**

* Start `mtkclient` in read-all/backup mode on your laptop so it waits for the device:

```
mkdir -p ~/tonal-backup
python3 mtk.py rl ~/tonal-backup
```

* Plug the micro-USB cable from your laptop into the Tonal's MediaTek board (following the `tonal-freedom` BROM pin/button sequence if your board revision doesn't auto-enter BROM on USB plug).  
* **If nothing happens on USB power alone** (common on Tonal V1): with `mtkclient` still waiting, **switch the Tonal on** (Safety Warning 3). `mtkclient` catches BROM within a few seconds (on Linux, `lsusb` shows `0e8d:2000 MediaTek MT65xx Preloader`) and the Tonal's screen stays dark. If no device appears, switch it off, wait about 10 seconds and switch it on again. `device not accepting address, error -71` in `dmesg` means a poor USB connection: reseat the cable or try another USB port. Switch the Tonal off when the command finishes: every `mtkclient` command needs its own *start command → switch on → switch off* cycle.  
* Wait for the full partition dump to finish.  
* **Verification:** Run `ls -lh ~/tonal-backup/boot.bin` and verify `boot.bin` exists, is non-zero (typically 16MB), and copy the entire `~/tonal-backup` folder to a second safe drive before continuing.

## **4\. Patch `boot.bin` with Magisk v27.0**

* Copy `~/tonal-backup/boot.bin` to a working copy (`cp ~/tonal-backup/boot.bin ~/boot-to-patch.img`) — never touch your original backup.  
* Patch `boot-to-patch.img` using the `tonal-freedom` patching routine or on an Android device with Magisk v27.0 installed ("Select and Patch a File"), which outputs `magisk_patched.img`.  
* Re-connect in BROM mode (the same way as for the backup) and flash the patched boot image to the `boot` partition:

```
python3 mtk.py w boot ~/magisk_patched.img
```

## **5\. Boot Up, Unlock Kiosk Mode & Enable Persistent ADB**

* Unplug your laptop's micro-USB cable from the Tonal board, plug the Tonal AC cord back in, and flip the power switch on. (If your board needed the Tonal switched on for BROM: switch it off after the flash, then back on with no `mtkclient` command waiting, and it boots normally.)  
* Once booted, reconnect your laptop via USB and run the `tonal-freedom` post-root unlock script (or via `adb shell`) to install the **Magisk v27.0 APK**, strip Tonal's device-owner kiosk lock, and enable **ADB over Wi-Fi** on port `5555`:

```
adb install Magisk-v27.0.apk
adb shell "su -c 'setprop service.adb.tcp.port 5555 && stop adbd && start adbd'"
```

* **Verification:** Disconnect the USB cable and run `adb connect <TONAL_LAN_IP>:5555` followed by `adb shell "su -c id"`. Confirm it returns `uid=0(root)`.

# **Phase 2: Freeze Tonal OTA Updates & Verify Motor Firmware**

## **1\. Disable Tonal's Automatic OTA Updater**

To prevent Tonal from silently overwriting your patched `boot` partition or flashing an unverified motor-controller firmware over Wi-Fi, disable the updater packages via root shell:

```
adb shell "su -c 'pm list packages | grep -i tonal'"
# Disable the OTA/updater service packages while leaving the main com.tonal.app intact for Stock Mode:
adb shell "su -c 'pm disable com.tonal.ota'"
```

(Optional Defense-in-Depth): Add a router/DNS block rule (or block outbound WAN traffic for the Tonal's MAC address while allowing LAN access to your home server and Spotify) so the Tonal can still talk to your local server and Spotify without ever reaching Tonal's OTA servers.

## **2\. Verify `/dev/ttyACM0` & Motor Firmware `5.2.18.0`**

Before installing the daemon, confirm the serial node exists and stop the stock Tonal app temporarily to inspect the motor controller announcement:

```
adb shell "su -c 'ls -l /dev/ttyACM0'"
```

* **Verification:** Confirm `/dev/ttyACM0` is present (`crw-rw---- 1 system system` or `root`).

# **Phase 3: Automated On-Device Swonal Installation (Recommended)**

Once Wi-Fi ADB (`:5555`) is active from Phase 1/2, you do **not** need a separate home server or manual Magisk scripts. Run the automated installer from your laptop:

```bash
./scripts/install-device.sh --target <TONAL_LAN_IP>:5555 --kiosk-apk /path/to/swonal-kiosk-vX.Y.Z.apk
```

*(Alternatively, copy the downloaded APK to `packages/device-assets/swonal-kiosk.apk` for automatic discovery without `--kiosk-apk`.)*

This automatically installs **Termux**, **Swonal Native Kiosk APK** (Standalone-First mode with embedded SQLite, `:3000` LAN Companion Web UI, and GeckoView ESR), **`librespot-aarch64`** (`99-swonal-librespot.sh`), and **`tonal-daemon`** (`98-swonal-daemon.sh`). Optionally pass `--server-url http://<SERVER_IP>:3000` only if pairing an external sync server.

---

# **Reference: Manual On-Device Installation (`swolectl` + `librespot` + `tonal-daemon`)**

*(Use the manual steps below only if debugging or customizing the Termux/Magisk setup without `install-device.sh`.)*

## **1\. Sideload Termux (Android 6 Environment)**

```
adb install packages/device-assets/termux-android5-v0.119.0-beta.3.apk
adb install path/to/swonal-kiosk.apk
```

## **2\. Install Python & `swolectl` Inside Termux on the Tonal**

Open an ADB root shell into the Termux environment (or via SSH into Termux) and set up `tonal-daemon`:

```
# Inside Termux on the Tonal:
pkg update && pkg install python rust git libffi openssl -y
pip install git+https://github.com/d4l3k/swolectl.git fastapi uvicorn websockets bleak pyyaml
```

## **3\. Run the Read-Only `swolectl` Hardware Verification Test**

First, stop the stock Tonal app so it releases `/dev/ttyACM0`, then run `swolectl` in **receive-only mode** for 5 seconds to verify your motor controller firmware is `5.2.18.0`:

```
su -c "am force-stop com.tonal.app"
su -c "/data/data/com.termux/files/usr/bin/swolectl --port /dev/ttyACM0 --seconds 5"
```

* **Verification:** Confirm you see incoming `0x1000` device announcement frames reporting board `501-0100_rev004` and firmware **`5.2.18.0`**.

## **4\. Sniff Your Smart Handle MAC Addresses (30-Second Setup)**

Run the handle scanner from `tonal-daemon` and click each Smart Handle button once:h  
su \-c "/data/data/com.termux/files/usr/bin/python3 /data/local/swonal-daemon/src/ble\_handles.py \--scan-handles"

````

Copy the reported MAC addresses and GATT characteristic UUID into `/data/local/swonal-daemon/config.yaml`.

### 5. Install `librespot` for Native Spotify Audio on Tonal Speakers (Optional)
Cross-compile or pull the static `armv7-unknown-linux-gnueabihf` (or `aarch64`) `librespot` binary, push it to `/data/local/bin/librespot`, and chmod `+x`:

```bash
adb push librespot /data/local/tmp/
adb shell "su -c 'mv /data/local/tmp/librespot /data/local/bin/librespot && chmod 755 /data/local/bin/librespot'"
````

# **Phase 4: Auto-Start Everything on Boot via Magisk `service.d`**

## **1\. Create the Magisk Boot Script**

Using `adb shell`, create `/data/adb/service.d/99-swonal.sh` using `vim`:

```
adb shell
su
vim /data/adb/service.d/99-swonal.sh
```

Paste the following script:h  
\#\!/system/bin/sh

# **Wait for Android boot completion**

while \[ "\$(getprop sys.boot\_completed)" \!= "1" \]; do  
sleep 2  
done

# **1\. Keep Wi-Fi ADB enabled on port 5555**

setprop service.adb.tcp.port 5555  
stop adbd && start adbd

# **2\. Set USB autosuspend for the motor controller when port is closed**

for dev in /sys/bus/usb/devices/\*; do  
if \[ \-f "\$dev/idVendor" \] && \[ "\$(cat \$dev/idVendor)" \= "1cbe" \]; then  
echo "auto" \> "\$dev/power/control"  
fi  
done

# **3\. Start librespot Spotify Connect receiver in background**

/data/local/bin/librespot \--name "Tonal Gym" \--bitrate 320 \--backend rodio \> /data/local/swonal-librespot.log 2\>&1 &

# **4\. Stop stock Tonal app by default so swonal-daemon can claim /dev/ttyACM0**

am force-stop com.tonal.app  
sleep 1

# **5\. Start swonal-ui hardware daemon on :8765**

export PATH="/data/data/com.termux/files/usr/bin:\$PATH"  
export LD\_LIBRARY\_PATH="/data/data/com.termux/files/usr/lib"  
cd /data/local/swonal-daemon  
python3 \-m uvicorn src.main:app \--host 0.0.0.0 \--port 8765 \> /data/local/swonal-daemon.log 2\>&1 &

````

### 2. Make the Script Executable
```bash
chmod 755 /data/adb/service.d/99-swonal.sh
````

* **Verification:** Reboot the Tonal (`adb reboot`), wait 60 seconds, and visit `http://<TONAL_LAN_IP>:8765/health` from your laptop. Confirm it returns `{"status": "ok", "serial": "connected", "firmware": "5.2.18.0"}` and that `"Tonal Gym"` appears in your Spotify Connect device list.

