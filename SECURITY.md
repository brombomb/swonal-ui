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
- **Set a Passcode:** Configure a passcode in Kiosk **Settings → Security** (or `SWONAL_AUTH_PASSCODE` in `.env` on the optional sync server) so all LAN `/companion` and `/ws` requests from other devices require authentication.
- **Protect `tonal-daemon` (`:8765`):** Keep the Tonal on a trusted LAN/VLAN and optionally configure `DAEMON_API_TOKEN` so non-loopback requests require a shared bearer token.
- **Use HTTPS for Remote Access:** Terminate TLS at your reverse proxy or tunnel when accessing Swonal or the optional sync server remotely.

## Standalone On-Device & Sync Security Architecture
- **Encrypted Credentials at Rest:** BYOK AI keys, Spotify Keymaster OAuth tokens, Home Assistant tokens, and Nostr `nsec` private keys stored on the Tonal tablet in `swonal_local.db` are encrypted using **Android Keystore AES-256-GCM** (`SecretCipher`, hardware/OS-backed key storage on API 23+).
- **Embedded `:3000` LAN Server Hardening:**
  - Loopback (`127.0.0.1` / `::1`) is trusted for the on-device GeckoView Kiosk web panels; non-loopback LAN clients enforce PBKDF2-HMAC-SHA256 passcode verification with `HttpOnly; SameSite=Lax` session cookies and constant-time Bearer token comparison.
  - Strict canonical path-traversal guards protect `/media/*` and bundled static web assets (blocking `..`, backslashes, null bytes, and out-of-root symlinks).
  - Request body caps (`2 MB` JSON routes, `25 MB` JSON backup restore) and strict LAN/loopback CORS origin whitelisting protect embedded memory on Android 6.0.
  - Intent extra overrides (`MainActivity.handleLaunchIntent`) are disabled in Release builds (`!BuildConfig.DEBUG`) and strictly validate `http://`/`https://` URLs.
- **Motor Safety Isolation:** Phone `/companion` (`/ws`) and optional Home Server (`/ws/sync`) disconnects never turn off Kiosk motor weight while the active Kiosk UI heartbeat is alive, and remote WebSocket clients cannot engage resistance when the Kiosk UI heartbeat is inactive.
