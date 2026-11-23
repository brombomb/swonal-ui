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
- **Set a Passcode:** Configure `SWONAL_AUTH_PASSCODE` in `.env` (or in Settings) if `server-app` is reachable beyond your trusted LAN.
- **Protect `tonal-daemon` (`:8765`):** Keep the Tonal on a trusted LAN/VLAN and optionally configure `DAEMON_API_TOKEN` so non-loopback requests require a shared bearer token.
- **Use HTTPS for Remote Access:** Terminate TLS at your reverse proxy or tunnel when accessing Swonal remotely.
