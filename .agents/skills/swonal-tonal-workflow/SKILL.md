---
name: swonal-tonal-workflow
description: >-
  Project-specific engineering workflow, hardware safety invariants, Android 6.0 (API 23) compatibility rules,
  and local CI/release verification procedures for the Swonal (swonal-ui) Tonal V1 monorepo.
  Use when modifying packages/android-kiosk, packages/server-app, packages/tonal-daemon, or scripts/install-device.sh.
---

# Swonal Tonal V1 Engineering Workflow

Follow these project-specific invariants whenever modifying `swonal-ui`.

## 1. Architecture & Untouchable Local Files
- **Standalone-First:** `packages/android-kiosk` is the primary offline-capable runtime (on-device SQLite via `BundledSQLiteDriver`, embedded `:3000` NanoWSD server + bundled Vite SPA, Spotify PKCE, local AI Coach, Nostr Swocial, HA webhooks). `packages/server-app` is an optional power-user sync / 24/7 MCP / MQTT / rotating-backup server.
- **Never touch local runtime files:** Never modify, overwrite, stage, or delete `.env`, `packages/server-app/.env`, or `packages/server-app/prisma/data/swonal.db`.
- **Clean-room tests only:** Run Prisma/Vitest against isolated temporary SQLite databases (handled automatically by `./scripts/ci-local.sh`).

## 2. Physical Hardware Safety Boundaries
- Motor disengage (`WEIGHT OFF`), safety cutouts, and heartbeat/inactivity watchdogs stay strictly local on `android-kiosk` ↔ `tonal-daemon` (`127.0.0.1:8765`, `<5ms`).
- Never let remote `/companion` WebSocket disconnects cut active Kiosk resistance (the legacy single-browser watchdog in `packages/server-app/src/server/index.ts` must remain opt-in behind `SWONAL_LEGACY_WS_WATCHDOG=1`).
- Remote `/ws` `set_weight` must never modify resistance while `weight_enabled == true`, and AI Coach HTTP responses must clamp deltas to `±20 lb` and never auto-engage motor weight.

## 3. Android 6.0 (API 23) & R8 Constraints (`packages/android-kiosk`)
Tonal V1 runs **Android 6.0 Marshmallow (`minSdk = 23`, MediaTek MT8173)** with `isMinifyEnabled = true` on release builds:
- **Avoid API 24+ / API 26+ stdlib APIs:**
  - Do NOT use `java.time.*` (use `IsoTime` in `CompatUtil.kt`).
  - Do NOT use `java.util.Base64` (use `Base64Compat` in `CompatUtil.kt`).
  - Do NOT use `ConcurrentHashMap.newKeySet()` (API 24+ — use `Collections.newSetFromMap(ConcurrentHashMap())`).
  - Do NOT use `SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")` (API 26+ — use `CryptoCompat.derivePbkdf2` over `Mac("HmacSHA256")`).
- **R8 / ProGuard:** Whenever adding `@Serializable` DTOs in a new Kotlin package, add serializer keep rules in `packages/android-kiosk/app/proguard-rules.pro` and verify with `:app:assembleRelease`.
- **Network / SSRF:** Route external HTTP integrations through `UrlSecurity.sanitizeExternalUrl` + `UrlSecurity.createSafeDns` with redirects disabled; allow RFC1918 LAN IPs and `*.local` for Home Assistant / LAN music, and allow loopback only for Ollama.

## 4. Verification Commands
Run before committing or pushing:
1. Full local CI (workflows, clean-room server-app typecheck/build/Vitest, tonal-daemon pytest, android-kiosk JUnit 5 + debug APK):
   ```bash
   ./scripts/ci-local.sh
   ```
2. Release R8 build check (whenever touching Android code, serialization, or ProGuard rules):
   ```bash
   (cd packages/android-kiosk && ./gradlew :app:assembleRelease --console=plain)
   ```
3. Shell syntax check (whenever touching installer scripts):
   ```bash
   bash -n scripts/install-device.sh
   ```
