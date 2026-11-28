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
* Wait for the full partition dump to finish. **It is slow:** about 30 GB, mostly `userdata` (~25 GiB), at roughly 3 MB/s, so plan for 2.5–3 hours. To get the small partitions safe first, you can dump everything except `userdata` (`python3 mtk.py rl ~/tonal-backup --skip userdata`, ~4 GB, ~25 min), then `userdata` on its own straight to an external drive (`python3 mtk.py r userdata /media/<drive>/userdata.bin`).  
* **Verification:**  
  1. Run `ls -lh ~/tonal-backup/boot.bin` and verify `boot.bin` exists and is 16MB. Better, save the partition table (`python3 mtk.py printgpt > ~/tonal-backup-gpt.txt`) and check that **every** file's size equals its partition's `Length` exactly.  
  2. Record checksums (`cd ~/tonal-backup && sha256sum -- *.bin > SHA256SUMS`), copy the entire `~/tonal-backup` folder (plus `userdata.bin`) to a second safe drive, and run `sha256sum -c SHA256SUMS` on the copy before continuing.  
  3. Prove reads are repeatable: read `boot` once more to a different path (`python3 mtk.py r boot ~/boot-check.bin`) and confirm its SHA-256 matches `boot.bin`.  
* **`userdata` is encrypted** (Android full-disk encryption), so `userdata.bin` looks like random data with no filesystem signature. That is expected. Its key lives in the crypto footer in the `metadata` partition: keep `metadata.bin` with it, and only ever restore the two together, to the same Tonal.  
* **Optional:** the eMMC boot areas (MediaTek preloader) are not part of `rl`. Read them with `mkdir -p ~/tonal-boot-areas` and then `python3 mtk.py rf ~/tonal-boot-areas/boot1.bin --parttype boot1` (and the same with `boot2`). Keep them, but **never write them back**.

## **4\. Patch `boot.bin` with Magisk v27.0**

* Copy `~/tonal-backup/boot.bin` to a working copy (`cp ~/tonal-backup/boot.bin ~/boot-to-patch.img`) — never touch your original backup.  
* Patch `boot-to-patch.img` using the `tonal-freedom` patching routine or on an Android device with Magisk v27.0 installed ("Select and Patch a File"), which outputs `magisk_patched.img`.  
* **Patching on a Raspberry Pi 5:** its default kernel uses 16 KB memory pages and Magisk's tools crash (`Segmentation fault ./magiskboot unpack`). Add `kernel=kernel8.img` under `[all]` in `/boot/firmware/config.txt`, reboot, and check `getconf PAGESIZE` prints `4096` before patching.  
* Re-connect in BROM mode (the same way as for the backup) and flash the patched boot image to the `boot` partition:

```
python3 mtk.py w boot ~/magisk_patched.img
```

* **If the patched boot fails** (boot loop or black screen), restore your original the same way: enter BROM and run `python3 mtk.py w boot ~/tonal-backup/boot.bin`. `w` looks `boot` up in the partition table and writes only within that partition; BROM lives in the chip's ROM, so a failed or interrupted write can simply be repeated. Consider rehearsing this once **before** flashing the patch: write your original `boot.bin` back (identical bytes), read it back with `python3 mtk.py r boot ~/boot-check.bin`, and confirm the SHA-256 still matches. That proves the restore path works on your unit.  
* **Do not run `python3 mtk.py wl ~/tonal-backup` on the backup folder as-is.** `wl` writes each file to the partition named after it, and the folder also holds `gpt.bin` / `gpt_backup.bin` (written at address 0, over the partition table) and other non-partition files. For a multi-partition restore, copy only the images you mean to restore into a new folder, and never include the boot areas.

## **5\. Boot Up, Unlock Kiosk Mode & Enable Persistent ADB**

* If you used the AC-on flow (Safety Warning 3), **flip the power switch off and unplug the AC cord before opening the service door.** Unplug your laptop's micro-USB cable from the Tonal board, close and secure the service door, then plug the Tonal AC cord back in and flip the power switch on. With no `mtkclient` command waiting, the Tonal boots normally.  
* Once booted, reconnect your laptop via USB and run the `tonal-freedom` post-root unlock script (or via `adb shell`) to install the **Magisk v27.0 APK**, strip Tonal's device-owner kiosk lock, and enable **ADB over Wi-Fi** on port `5555` (commands below).  
* **If the Tonal still boots into the normal Tonal app:** on at least one Tonal V1 (Android 6.0 user build) the patch's `bypass_kiosk` service never starts (`dmesg` shows `cannot setexeccon('u:r:su:s0')`), so there is no kiosk bypass and no automatic ADB key. Magisk itself is installed; finish by hand:
  1. Switch the Tonal off and on once more. On the second boot Android shows **"Allow USB debugging?"** for your computer: tick **Always allow from this computer** and tap OK.
  2. `adb install Magisk-v27.0.apk`, open Magisk on the Tonal and accept its "additional setup" (it reboots).
  3. Run `adb shell su -c id` and grant the prompt on the Tonal. If the prompt ignores taps, the Tonal app's overlay is triggering Magisk's tapjacking protection: turn off *Magisk → Settings → Superuser → Tapjacking protection*, grant, then turn it back on. If `su` is refused instantly, an earlier timed-out prompt saved a *deny*: switch **Shell** on in Magisk's Superuser tab.
* **Approve your computer's ADB key over USB before switching to Wi-Fi.** Restarting `adbd` (which `adb tcpip 5555` also does) can leave even already-approved computers stuck as `unauthorized` until the next reboot. With the key approved and saved, restart `adbd` as root from a small script started with `nohup`, so the restart isn't cut off when `stop adbd` ends your shell (Magisk can end background work when the `su` session closes; if a plain `stop adbd && start adbd` is cut off after `stop`, ADB stays off until a reboot):

```
adb install Magisk-v27.0.apk
printf 'setprop service.adb.tcp.port 5555\nstop adbd\nsleep 2\nstart adbd\n' > wifi-adb.sh
adb push wifi-adb.sh /data/local/tmp/wifi-adb.sh
adb shell "su -c 'nohup sh /data/local/tmp/wifi-adb.sh >/dev/null 2>&1 &'"
```

* **Verification:** Disconnect the USB cable and run `adb connect <TONAL_LAN_IP>:5555` followed by `adb shell "su -c id"`. Confirm it returns `uid=0(root)`.
* **Why Wi-Fi ADB doesn't survive a reboot by itself:** the V1's `/data` is encrypted (`ro.crypto.state=encrypted`), and `adbd` starts before it is unlocked. Early in each boot `adbd` therefore sees neither your saved keys (you may see the "Allow USB debugging?" prompt again) nor `persist.*` properties, so `setprop persist.adb.tcp.port 5555` has no effect. Restarting `adbd` once boot has completed fixes both; the Phase 3 installer's boot script does exactly that.

# **Phase 2: Freeze Tonal OTA Updates & Verify Motor Firmware**

## **1\. Disable Tonal's Automatic OTA Updater**

To prevent Tonal from silently overwriting your patched `boot` partition or flashing an unverified motor-controller firmware over Wi-Fi, disable the updater packages via root shell:

```
adb shell "su -c 'pm list packages | grep -iE \"tonal|innocomm|riptlabs\"'"
```

There is no `com.tonal.ota` on a Tonal V1. What updates a V1, and what else runs as a system app:

| Package | What it does | Suggested |
|---|---|---|
| `com.innocomm.otaupdate` | **System OTA** ("AOSPUpdater"): downloads `/cache/update.zip` and installs it through recovery, which would replace your patched `boot` | disable |
| `com.tonal.apkupdater` | Updates Tonal's apps | disable |
| `com.tonal.fieldservice` | Remote support; holds `RECOVERY` and `INSTALL_PACKAGES`, so it can install updates too | disable |
| `com.tonal.mcb.client.firmwareclient` | Motor-board firmware updater | disable once you've read your board's firmware (step 2) |
| `com.tonal.apps.machineclient2.beta` | The stock Tonal app | keep for Stock Mode |
| `com.riptlabs.mcb.service` | Stock motor service (owns `/dev/ttyACM0`) | the daemon takes it over in Phase 3 |
| `com.tonal.tonallauncher` | Launcher and **Device Owner** with the `wipe-data` policy: it can factory-reset the Tonal, which erases `/data` (Magisk app, boot scripts, Swonal's database) | keep; back up Swonal data off the device |

```
adb shell "su -c 'pm disable com.innocomm.otaupdate; pm disable com.tonal.apkupdater; pm disable com.tonal.fieldservice'"
adb shell "su -c 'pm list packages -d'"          # confirm
# Undo any of them: pm enable <package>
```

The stock Tonal app still opens with these disabled.

(Optional Defense-in-Depth): Add a router/DNS block rule (or block outbound WAN traffic for the Tonal's MAC address while allowing LAN access to your home server and Spotify) so the Tonal can still talk to your local server and Spotify without ever reaching Tonal's OTA servers. If your router can't do per-device blocking, the same can be done on the Tonal itself: an `iptables`/`ip6tables` chain hooked into Android's (empty, OEM-reserved) `oem_out` chain that allows your LAN subnet and multicast and rejects everything else, applied from a Magisk `service.d` script. Use `iptables -w`: Magisk runs `service.d` while `netd` is still building its chains, and without `-w` rules can silently fail to apply. Note that blocking the internet also blocks Spotify, Swocial (Nostr) and the one-time voice-model download.

## **2\. Verify `/dev/ttyACM0` & Motor Firmware `5.2.18.0`**

Before installing the daemon, confirm the serial node exists and stop the stock Tonal app temporarily to inspect the motor controller announcement:

```
adb shell "su -c 'ls -l /dev/ttyACM0'"
```

* **Verification:** Confirm `/dev/ttyACM0` is present (`crw-rw---- 1 system system` or `root`).

You can read your motor board and firmware without touching the motor link:

```
adb shell "cat /sys/bus/usb/devices/*/product"      # e.g. 500-0104_004 = part 500-0104, revision 004
adb shell "su -c 'ls /data/data/com.riptlabs.mcb.service/app_tonal-clientlogs-stream-firehose-cache/KinesisRecorder/'"
```

The stock motor service logs `MCB Device: <part>_rev<rev>  FW: <version>` at every start into that cache (base64-encoded JSON lines). It also contains your Tonal username and device serial numbers, so don't paste it anywhere public. Listening on `/dev/ttyACM0` instead only shows the firmware while the board is *unpaired*: the version announcement stops once the stock service (or the daemon) has paired with it. Reported so far: board `500-0104_rev004` with firmware `5.2.18.0` on two V1s.

# **Phase 3: Automated On-Device Swonal Installation (Recommended)**

Once Wi-Fi ADB (`:5555`) is active from Phase 1/2, you do **not** need a separate home server or manual Magisk scripts. Run the automated installer from your laptop:

```bash
./scripts/install-device.sh --target <TONAL_LAN_IP>:5555 --kiosk-apk /path/to/swonal-kiosk-vX.Y.Z.apk
```

*(Alternatively, copy the downloaded APK to `packages/device-assets/swonal-kiosk.apk` for automatic discovery without `--kiosk-apk`.)*

This automatically installs **Termux**, **Swonal Native Kiosk APK** (Standalone-First mode with embedded SQLite, `:53013` LAN Companion Web UI, and GeckoView ESR), **`librespot-aarch64`** (`99-swonal-librespot.sh`), and **`tonal-daemon`** (`98-swonal-daemon.sh`). Optionally pass `--server-url http://<SERVER_IP>:3000` only if pairing an external sync server.

The installer also grants the Swonal Kiosk **permanent root in Magisk** (no prompt). The kiosk uses it to hand the motor between Swonal and the stock app and for its in-app updater. On every boot the kiosk hands the motor to the daemon only if the daemon is installed and **Application Settings → Admin & System → Take over motor on boot** is on (the default); otherwise it leaves stock Tonal alone and shows a notice. To go back to stock later, see *Kiosk Boot Hook* in [SECURITY.md](SECURITY.md) (`pm enable com.riptlabs.mcb.service`, then reboot). Termux doesn't need a tap on the screen to finish its first-run setup: `adb shell am start -n com.termux/.app.TermuxActivity` starts it remotely.

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

