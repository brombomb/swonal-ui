---
id: android-kiosk-api23-safety
paths:
  - "packages/android-kiosk/**/*.kt"
---

Target device is a Tonal V1 tablet running **Android 6.0 Marshmallow (`minSdk = 23`)** with R8 minification enabled.

- **API 23 compatibility:** Flag any API 24+ calls (`java.time.*`, `java.util.Base64`, `List.of`, `Map.of`, `Collection.removeIf`, `String.repeat`, etc.); prefer `IsoTime` and `Base64Compat`.
- **R8 / ProGuard safety:** Verify `kotlinx.serialization` `@Serializable` models remain in `com.swonal.kiosk.*` packages covered by `proguard-rules.pro`.
- **Workout safety invariants:** Always disengage resistance before switching exercises, workouts, or attachments; keep `BAR` tilt-cutout and emergency `WEIGHT OFF` intact.
- **Data-model parity:** Check API/schema parity with `packages/server-app` when shared payloads or SQLite schemas change.
