# swonal-ui Architectural Decisions Log

## 1. Port Assignments
- **`tonal-daemon`:** Runs FastAPI + WebSocket hub on port `8765` (`0.0.0.0:8765`), exposing `/ws`, `/health`, `/api/mode/*`, and `/api/mock/*`.
- **`server-app`:** Runs Express + WebSocket hub + MCP SSE (`/mcp/sse`) + Vite-built static client on port `3000` (`0.0.0.0:3000`). During local Vite dev mode, Vite runs on `:5173` and proxies `/api`, `/mcp`, and `/ws` to `:3000`.

## 2. Database & Prisma Pinning
- `prisma` and `@prisma/client` are pinned to `^5.22.0` to maintain direct compatibility with the `datasource db` `schema.prisma` specification without Prisma 6/7 config migration breaks.
- `prisma/schema.prisma` reads `url = env("DATABASE_URL")`. Local development defaults to `file:./data/swonal.db` (configured via `packages/server-app/.env`), while Docker uses `file:/data/swonal.db` mounted on the persistent `swonal-db-data` volume.
- All weight records (`SetLog.weightLb`, `PersonalRecord.maxWeightLb`, `PersonalRecord.oneRepMaxEst`, `WorkoutTemplate.blocksJson[].items[].targetWeightLb`) are stored canonically in pounds (`lb`). Imperial/Metric (`LB`/`KG`) conversions happen at the UI and MCP presentation/ingestion boundary (`1 lb = 0.45359237 kg`).

## 3. `swolectl` Hardware Boundary & Mock Engine
- Inside `packages/tonal-daemon/src/motor_bridge.py`, `from swolectl import Controller, SafetyPolicy` is imported lazily inside `MotorBridge.initialize()` only when `mock_mode` is `False` (and only if an injected `controller` isn't already supplied for tests). This allows `--mock` mode (`MOCK_TONAL=true` or `--mock`) and `pytest` unit tests to execute without requiring physical Tonal hardware or `swolectl` installation on development machines.
- Note on Python environment: System `python3` is Python 3.14.4. `packages/tonal-daemon/.venv` is created with `requires-python >= 3.11` satisfied.

## 4. Phase 1.5 Bar Tilt Safety Cutout
- `_check_bar_tilt_safety` triggers only when `bar_tilt_cutoff_enabled == True`, `weight_enabled == True`, `active_accessory == "BAR"`, and `abs(left_pos - right_pos) > bar_tilt_threshold_delta` (default `35.0`) continuously for at least `bar_tilt_dwell_ms` (default `250 ms`). Upon triggering, it disables resistance immediately and broadcasts `{"type": "safety_event", "reason": "BAR_TILT_CUTOUT", "delta": ...}` over WebSocket.

## 5. MCP SDK + Zod TypeScript Compilation & Server Bundling
- `@modelcontextprotocol/sdk` v1.32.2 paired with `zod@3.25.76` exposes dual `zod/v3` + `zod/v4` compatibility union generics on `McpServer.registerTool`, which caused exponential TypeScript type instantiation (OOM at 4GB) when passing nested Zod schemas directly. Wrapping registration inside `registerZodTool<S extends z.ZodObject<z.ZodRawShape>>` in `src/server/mcp/mcpServer.ts` preserves full runtime Zod parsing and handler parameter typing while keeping `tsc --noEmit` under 2 seconds.
- `build:server` uses `esbuild` (`--bundle --platform=node --target=node22 --packages=external`) to emit `dist/server/index.js` and `dist/server/stdio.js` cleanly in <100ms while `npm run typecheck` (`tsc --noEmit`) validates all TypeScript sources across `src/server` and `src/client`.

## 6. Standalone-First Architecture & Embedded Android Server (`:3000`)
- **`com.swonal.kiosk` runs 100% standalone by default (`SyncMode.STANDALONE`):**
  - Persistence uses `androidx.sqlite:sqlite-bundled` (`SQLite 3.46+`) in `/data/data/com.swonal.kiosk/databases/swonal_local.db` instead of Android 6.0's legacy SQLite 3.8 system library, seeded deterministically from bundled `assets/seed/seed_catalog.json` (50 exercises + 9 bundled SVG form diagrams).
  - Sensitive credentials (API keys, HA tokens, Spotify OAuth tokens, Nostr `nsec` keys) are encrypted at rest via `SecretCipher` (Android Keystore AES-GCM on API 23+, randomized key file fallback on JVM tests).
  - Music (`LocalSpotifyController` with Keymaster S256 PKCE + `:8765` librespot provisioning + `LocalLanMusicProviders`), AI Voice Coach (`LocalAiCoachEngine`), Home Assistant webhooks (`LocalHomeAssistantClient`), and Nostr Swocial (`NostrCrypto` pure-Kotlin BIP-340 Schnorr + NIP-19) execute directly on the tablet using `SwonalTlsConfig` modern CAs.
  - `EmbeddedSwonalServer` (`NanoWSD` on port `3000`) serves the Vite-compiled React SPA bundled in `assets/web/`, `/api/*` REST routes, `/ws` LAN updates, and path-traversal-guarded `/media/*` directly from the Tonal over Wi-Fi. Disconnecting a phone Companion `/ws` client never disengages motor resistance while the Kiosk UI heartbeat is alive.
- **Optional Home / Cloud Sync Server (`SyncMode.SERVER_SYNC`):**
  - Power users can optionally pair `server-app` for 24/7 always-on MCP workout queuing (`AppSetting.pending_queued_workout` delivered via `/ws/sync` and `/api/sync/pull`), HA MQTT autodiscovery, and automated daily rotating `VACUUM INTO` backups (`backupService.ts`).
  - Sync uses idempotent UUID `SetLog` ingestion, `MAX(oneRepMaxEst)` / `MAX(maxWeightLb)` PR envelopes, name-based exercise ID remapping, and soft-delete `deletedAt` tombstones, while falling back seamlessly offline whenever the external server is unreachable.
