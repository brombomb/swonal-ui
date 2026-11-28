# Contributing to Swonal

Thank you for helping improve Swonal! Before starting larger features, please check open Issues and PRs (and leave a note on an Issue if you are picking it up) so contributors avoid duplicating work.

## Repository Layout

| Package | Stack | Target Runtime |
| --- | --- | --- |
| [`packages/tonal-daemon`](packages/tonal-daemon) | Python + FastAPI + `swolectl` | Rooted Tonal V1 Termux **Python 3.8** |
| [`packages/server-app`](packages/server-app) | Node 22 + Express + Prisma SQLite + React SPA | Embedded web assets + optional sync server |
| [`packages/android-kiosk`](packages/android-kiosk) | Kotlin + Bundled SQLite + NanoWSD | Android 6.0 Marshmallow (**API 23 minSdk**) + R8 |
| [`scripts/`](scripts) | POSIX/Bash | `install-device.sh`, `ci-local.sh` |

## Hardware Safety & Compatibility Rules

1. **Motor & Safety Invariants (`tonal-daemon` & `android-kiosk`):**
   - Never bypass resistance bounds (`5.0..200.0 lb`), heartbeat watchdog, bar-tilt cutout (`BAR` active accessory), or emergency `WEIGHT OFF`.
   - Always disengage resistance before switching exercises, workouts, or active attachments.
2. **Android 6.0 (API 23) Compatibility (`packages/android-kiosk`):**
   - Tonal V1 tablets run Android 6.0 (`minSdk = 23`). Do **not** use `java.time.*`, `java.util.Base64`, `List.of`/`Map.of`, `Collection.removeIf`, or `String.repeat`. Reuse `IsoTime` and `Base64Compat`.
   - Keep `kotlinx.serialization` `@Serializable` models in `com.swonal.kiosk.*` packages covered by `proguard-rules.pro`.
3. **Termux Python 3.8 Compatibility (`packages/tonal-daemon`):**
   - Do **not** use Python 3.10+ syntax (such as `X | Y` union type hints without `from __future__ import annotations`, `match`/`case`, etc.).

## Local Verification

Run the clean-room local CI suite before opening a PR (isolates SQLite and never touches `.env` or `packages/server-app/prisma/data/swonal.db`):

```bash
./scripts/ci-local.sh
```

If you changed Android UI/serialization code and have the Android SDK installed, also verify the release R8 build:

```bash
cd packages/android-kiosk
./gradlew :app:testDebugUnitTest :app:assembleRelease --console=plain
```

If you do not have an Android SDK or physical Tonal V1 hardware locally, mention that clearly in your PR description — GitHub Actions (`CI Gate`) runs only the suites needed for your changed paths.

## Commit & PR Conventions

- Use [Conventional Commits](https://www.conventionalcommits.org/) (`feat:`, `fix:`, `docs:`, `chore:`) so Release Please can generate changelogs cleanly.
- Open small, focused PRs against `main`. On GitHub, **CI Gate** automatically runs the checks matching your changed packages (`tonal-daemon`, `server-app`, `android-kiosk`, `scripts`, or quick YAML validation for docs-only PRs).
