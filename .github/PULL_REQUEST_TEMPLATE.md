## Summary

<!-- Describe what this PR changes and link any related Issue (e.g. Fixes #123). -->

## Package Scope

- [ ] `packages/tonal-daemon` (Python 3.8 on Termux)
- [ ] `packages/server-app` (Node 22 / Prisma / React)
- [ ] `packages/android-kiosk` (Android 6.0 / API 23 / R8)
- [ ] `scripts/` or Docs

## Hardware & Safety Checklist

- [ ] Does not weaken motor safety invariants (`MIN_WEIGHT_LB=5.0`, `MAX_WEIGHT_LB=200.0`, bar-tilt cutout, heartbeat watchdog, `WEIGHT OFF` fail-safe, or resistance disengage before switching workouts/accessories).
- [ ] Android code uses only API 23-compatible APIs (no `java.time.*`, `java.util.Base64`, or API 24+ collection helpers).
- [ ] Python daemon code remains syntax-compatible with Termux Python 3.8.

## Testing

- [ ] Ran `./scripts/ci-local.sh` (or relevant package test suite + workflow check)
- [ ] Tested on real Tonal V1 hardware or `--mock` simulator (note below if Android SDK / hardware was not available locally)
