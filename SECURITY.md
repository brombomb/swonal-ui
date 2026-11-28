# Security & Motor Safety Policy

`swonal-ui` directly controls motorized resistance hardware. We take both **physical motor safety** and **network security** seriously.

## Reporting a Vulnerability or Safety Bug

Please **do not** open a public issue for exploitable security vulnerabilities or critical motor safety bugs. Instead:

1. Use [GitHub Private Vulnerability Reporting](https://github.com/brombomb/swonal-ui/security/advisories/new) on this repository, **or**
2. Reach out directly to the maintainer in the [Swonal Discord](https://discord.gg/hApx3nkryr).

Please include:
- Affected component (`tonal-daemon`, `server-app`, or `android-kiosk`)
- Steps to reproduce or proof of concept
- Any relevant logs (scrubbed of tokens/passcodes)

## Hardening Recommendations for Self-Hosters
- **Set a Passcode:** Configure a passcode in the kiosk under **Application Settings → Network & TLS → LAN Companion Security Lock** (or `SWONAL_AUTH_PASSCODE` in `.env` on the optional sync server) so all LAN `/companion` and `/ws` requests from other devices require authentication.
- **Protect `tonal-daemon` (`:8765`):** Keep the Tonal on a trusted LAN/VLAN and optionally configure `DAEMON_API_TOKEN` so non-loopback requests require a shared bearer token.
- **Use HTTPS for Remote Access:** Terminate TLS at your reverse proxy or tunnel when accessing Swonal or the optional sync server remotely.

## Kiosk Boot Hook (Wi-Fi ADB & Motor Handoff)
Swonal Kiosk runs a few commands as root on every boot (`BootReceiver`). Two of them are settings under **Application Settings → Admin & System → Boot & Motor Handoff**, both on by default, and take effect at the next reboot:

- **Keep Wi-Fi ADB enabled on boot:** sets `persist.adb.tcp.port` / `service.adb.tcp.port` to `5555` and restarts `adbd`, so ADB listens on your Wi-Fi. On the V1s checked so far `ro.adb.secure=1`, so a computer still needs an authorized ADB key (`/data/misc/adb/adb_keys`) to connect; keep the Tonal on a trusted network. Turning the setting **off** only stops the kiosk from re-enabling ADB; it never turns ADB off, so a setting change can't lock you out of recovery. `persist.adb.tcp.port` survives reboots and the daemon's boot scripts (`00-swonal-adbd.sh`, `98-swonal-daemon.sh`) also set it, so port 5555 usually stays open until you clear it yourself (have USB ADB working first).
- **Take over motor on boot:** when **on** and the Swonal daemon is installed (the kiosk checks with a root shell for `/data/adb/service.d/98-swonal-daemon.sh`, `/data/swonal/packages/tonal-daemon/src/main.py`, or the runbook's manual `/data/adb/service.d/99-swonal.sh` / `/data/local/swonal-daemon/src/main.py`), the kiosk disables the stock motor service (`pm disable com.riptlabs.mcb.service`), stops it, and makes `/dev/ttyACM0` `root:root 0600` so only the daemon can open the motor port. If no daemon is found, or the root shell fails or does not answer within 30 s, stock Tonal is left alone and the kiosk shows a notice. When **off**, the kiosk runs `pm enable com.riptlabs.mcb.service` at boot and leaves `/dev/ttyACM0` alone. While the daemon is installed its own boot scripts still claim the motor, so to go back to stock remove the daemon too.

**Going back to stock Tonal by hand:** `pm disable` persists across reboots, including after you uninstall the kiosk. Re-enable the stock motor service and reboot:

```bash
adb shell "su -c 'pm enable com.riptlabs.mcb.service'"
adb reboot
```

Also remove the daemon's Magisk boot scripts (`/data/adb/service.d/00-swonal-adbd.sh`, `98-swonal-daemon.sh`, and `/data/adb/post-fs-data.d/00-swonal-adbd.sh`), or they will take the motor again on the next boot.

## Standalone On-Device & Sync Security Architecture
- **Encrypted Credentials at Rest:** BYOK AI keys, Spotify Keymaster OAuth tokens, Home Assistant tokens, and Nostr `nsec` private keys stored on the Tonal tablet in `swonal_local.db` are encrypted using **Android Keystore AES-256-GCM** (`SecretCipher`, hardware/OS-backed key storage on API 23+).
- **Embedded `:53013` HTTP & Optional `:53443` HTTPS LAN Server Hardening:**
  - Loopback (`127.0.0.1` / `::1`) is trusted for on-device clients; non-loopback LAN clients enforce PBKDF2-HMAC-SHA256 passcode verification with `HttpOnly; SameSite=Lax` session cookies and constant-time Bearer token comparison.
  - Optional on-device HTTPS (`:53443`, toggled in the kiosk under **Application Settings → Network & TLS → Enable On-Device HTTPS**) generates a device-local RSA-2048 Root CA and LAN-SAN leaf certificate (`LocalTlsCertManager`) stored in app-private storage, with the public Root CA downloadable at `GET /api/tls/ca.crt` so mobile browsers can trust HTTPS Companion sessions without a reverse proxy.
  - Strict canonical path-traversal guards protect `/media/*` and bundled static web assets (blocking `..`, backslashes, null bytes, and out-of-root symlinks).
  - Request body caps (`2 MB` JSON routes, `25 MB` JSON backup restore) and strict LAN/loopback CORS origin whitelisting protect embedded memory on Android 6.0.
  - Intent extra overrides (`MainActivity.handleLaunchIntent`) are disabled in Release builds (`!BuildConfig.DEBUG`) and strictly validate `http://`/`https://` URLs.
- **Motor Safety Isolation:** Phone `/companion` (`/ws`) and optional Home Server (`/ws/sync`) disconnects never turn off Kiosk motor weight while the active Kiosk UI heartbeat is alive, and remote WebSocket clients cannot engage resistance when the Kiosk UI heartbeat is inactive.
- **PII-Scrubbed Bug Reports:** Optional in-app Discord bug reports (`Menu → Send Bug Report` / `Diagnostics`) scrub Bearer/API/OAuth/Nostr tokens, cookies, Discord webhook URLs, emails, MAC addresses, non-loopback IPv4 addresses, and local profile names via `BugReportSanitizer` before upload, and only accept `https://discord.com/api/webhooks/...` endpoints (injected optionally at release build time via `SWONAL_BUG_REPORT_WEBHOOK` or entered encrypted per-device).
