# Swonal Native Android Kiosk — Implementation Plan

Target audience: **High-Level Agent Coordinator**  
Goal: Replace the browser-based Tonal V1 wall display with a lightweight, 60fps native Android kiosk app (`packages/android-kiosk`) while keeping `packages/server-app` as the backend brain and `packages/tonal-daemon` as the hardware bridge.

---

## 1. Tech Stack & Constraints (Android 6.0 / API 23)

### Target Device Baseline
- **Device**: Tonal V1 (`arm64-v8a`), Android 6.0 Marshmallow (**API 23**), portrait `1080x1920`, rooted with Magisk v30.
- **System WebView**: `com.android.webview` v44.0.2403.119 (do **not** rely on System WebView for modern React/Vite UIs).
- **Existing Backend**:
  - `packages/server-app` (`:3000`): SQLite/Prisma DB, REST (`/api/*`), WebSocket (`/ws`), Spotify hub, AI Coach, MCP server, and `/companion` web UI.
  - `packages/tonal-daemon` (`:8765`): Python 3.11+ FastAPI/WebSocket bridge to `/dev/ttyACM0` (`swolectl`) and BLE accessories.

### Chosen Stack
- **Language & Build**: **Kotlin** + Android Gradle Plugin (`minSdk = 23`, `targetSdk = 34`, `compileSdk = 34`).
- **UI Toolkit**: **Classic Android Views + XML/ViewBinding + Custom `Canvas` Views** (not React Native, and not Jetpack Compose):
  - Lowest RAM & APK footprint (~5–10 MB vs 176 MB Bromite) on 2018-era MediaTek hardware.
  - Zero JS bridge/GC pauses; deterministic 60fps `Choreographer` + hardware-accelerated `RenderThread`.
  - Custom `View` subclasses (`LoadDialView`, `CableTravelView`, `SymmetrySliderView`, `VelocityGaugeView`) use `Canvas.drawArc`, `drawLine`, `drawRoundRect`, and `Path` directly.
- **Networking & Serialization**:
  - **OkHttp 4.x** (REST `/api/*` + `/ws` WebSocket client with auto-reconnect).
  - **kotlinx.serialization** (or **Moshi**) for strict typed DTOs matching `packages/server-app` WebSocket/REST payloads.
- **Concurrency & State**:
  - **Kotlin Coroutines + `StateFlow` / `SharedFlow`** (`Dispatchers.IO` for socket/REST, `Dispatchers.Main` for UI).
  - Separate high-frequency telemetry `StateFlow` (30Hz `left_cable` / `right_cable` / `applied_resistance_lb`) from discrete workout/session `StateFlow` so 30Hz updates only invalidate the custom `Canvas` views.
- **Media, Audio & Images**:
  - **Media3 ExoPlayer** (`minSdk 23` compatible) or native `VideoView` for Form Demo `.mp4`/`.webm` clips + **Coil** for images/album art.
  - **Android `SoundPool`** for zero-latency rep/set/PR audio cues.
- **Launcher Integration**:
  - Declare `android.intent.category.HOME` + `DEFAULT` in `AndroidManifest.xml` (optional kiosk home mode) with an app-switcher drawer to launch Termux, Settings, or stock Tonal (`com.tonal.apps.machineclient2.beta`).

---

## 1A. Visual Design Template (Porting Existing HTML/CSS/TSX)

Use the existing web UI as the **authoritative visual design spec** (`packages/server-app/src/client/src/index.css`, `KioskView.tsx`, `KioskCableBar.tsx`, `MusicPickerDialog.tsx`):

- **Color & Surface Tokens (`index.css`)**:
  - **Dark Mode**: `#06090e` obsidian canvas (`--bg-canvas`) with subtle `36dp` `+` crosshair background (`rgba(255,255,255,0.045)`), 3-tier surface hierarchy (`--bg-surface` vertical slate gradient `rgba(16,23,38,0.88)` -> `rgba(10,15,26,0.92)`, `--bg-secondary-bar` for the Music Dock, `--bg-elevated` for pills), and top-lit gradient borders (`rgba(255,255,255,0.12)` top fading to `rgba(255,255,255,0.025)` bottom).
  - **Light Mode**: `#f4f6f9` canvas, `#ffffff` elevated cards with soft diffused shadows, `36dp` faint hairline grid (`rgba(15,23,42,0.032)`), crisp slate button borders (`rgba(15,23,42,0.16)`), and darkened accent text (`--accent-ink`: 52% user accent mixed with `#0f172a`) so neon accents remain readable on light/gray surfaces.
- **Typography & Iconography**:
  - Sans-serif (`Inter` / Roboto) for labels and action buttons; `JetBrains Mono` / monospace with `fontFeatureSettings = "tnum"` (`tabular-nums`) for all live weights, reps, velocities, and ROM counters.
  - Export/convert the `lucide-react` icons used in `KioskView.tsx` as Android VectorDrawables with uniform `1.75dp` stroke width.
- **Component Geometry & Layout Parity**:
  - **Exercise Header Card**: Left stack (routine/step pill + muscle group, untruncated movement title, set/rep target) + right-aligned `316dp`-equivalent fixed-width action column (`Routines & Exercises`, `ARMS ALIGNED/ADJUST` sync badge + info popover, `HANDLES | BAR | ROPE` 3-column grid).
  - **Docked Stepper & Form Demo Row**: Fixed-height row (`144dp` / `h-36` equivalent) so toggling Form Demo causes zero vertical layout jump.
  - **Load Dial & Cable Bars**: `620px`-scaled `LoadDialView` with 60 concentric load-cell ticks, continuous radial accent spotlight (`25%` idle, `95%` engaged), and flanking `CableTravelView` bars with `2dp` uniform inset, matching `29dp` corner radius, flush `0%` bottom puck, and fixed-width velocity (`in/s` for `LB`, `cm/s` for `KG`) + `ROM` readouts.
  - **Telemetry & Set Control Deck**: Recessed `SymmetrySliderView` (center notch tick behind puck at `25%` opacity) and `VelocityGaugeView` (5-segment cyan -> amber -> red track), above `Completed Reps`, `Complete & Log Set`, `Up Next`, Dynamic Modes, and the `secondary-bar` Music Dock.

---

## 2. Phased Migration Path

### Phase 0 — Project Scaffold & Core Transport (`packages/android-kiosk`)
- **Deliverables**:
  - Gradle module `packages/android-kiosk` (`minSdk = 23`, `compileSdk = 34`, `abiFilters "arm64-v8a"`).
  - `SwonalRepository`:
    - Configurable server URL (default `http://192.168.1.100:3000` or LAN `http://<LAN_IP>:3000`).
    - Initial bootstrap via `GET /api/state` and `GET /api/daemon/health`.
    - Persistent `OkHttp` `/ws` WebSocket listener parsing `telemetry`, `session_state`, `daemon_status`, `workout_queued`, `safety_event`, `music_updated`, and `users_updated` / `templates_updated`.
  - Unit tests (`JUnit5` / `MockWebServer`) verifying telemetry JSON parsing and canonical `LB` <-> `KG` unit conversion (`1 lb = 0.45359237 kg`, velocity `cm/s` <-> `in/s` via `/ 2.54`).
- **Exit Criteria**: App connects to `server-app` `/ws`, logs live 30Hz frames, and passes unit tests.

### Phase 1 — Live Workout Screen (60fps Hot Path)
- **Deliverables**:
  - **Top Status Bar**: Active user profile switcher (accent color theming), Partner Work-In toggle, Quick Preferences popover, `TONAL CDC 30Hz` / `MOCK 30Hz` / `DAEMON WAIT` status pill, and Diagnostics trigger.
  - **Exercise Header Card**: Routine/step pill, exercise title, set/rep target, `Routines & Exercises` button, `ARMS ALIGNED/ADJUST` sync badge + info popover, and `HANDLES | BAR | ROPE` selector.
  - **Docked Stepper & Form Demo Bar**: `Prev` / `Next` steppers with fixed container height (`no layout jump`) and Form Demo video/image preview.
  - **Custom `Canvas` Load Stage**:
    - `LoadDialView`: 60 radial load-cell ticks, accent progress arc, ambient radial glow, tap-to-toggle `WEIGHT ON/OFF`, tap-weight-number exact-entry dialog, and `-5 / -1 / +1 / +5` (`-2.5 / -0.5 / +0.5 / +2.5` in KG) steppers.
    - `CableTravelView` (Left & Right): Flush-bottom `2px`-inset pill track, matching-radius traveling puck (`0%`–`100%` without clipping), and fixed-width `tabular-nums` load / velocity / ROM readouts.
  - **Telemetry & Set Control Deck**:
    - `SymmetrySliderView` (with center notch tick) and `VelocityGaugeView` (live velocity + `% Velocity Drop` + `Enable Spotter` alert).
    - `Completed Reps`, `Complete & Log Set`, Dynamic Modes (`Standard`, `Spotter`, `Chains`, `Eccentric`, `Burnout`), and Rest Timer overlay.
  - **Music Dock**: Track/artist display, play/pause/prev/next, custom volume slider, and chime mute toggle.
- **Exit Criteria**: Full workout session runs end-to-end on Tonal V1 (`<tonal-ip>:5555`) at 60fps with zero browser dependency.

### Phase 2 — Pickers, Modals & Launcher Mode
- **Deliverables**:
  - Native `DialogFragment` / `BottomSheetDialogFragment` for:
    - Routines & Exercises library (filter by user/muscle/accessory, start routine or free lift).
    - Spotify Playlists & Devices picker.
    - Profile switcher & creation, QR Companion link, AI Coach voice/text prompt, and Hardware/Mock diagnostics.
  - Optional `CATEGORY_HOME` launcher support + App Drawer button (Termux, Magisk, Android Settings, Stock Tonal) and `scripts/install-device.sh` integration (`--apk-dir` / `adb install`).
- **Exit Criteria**: All interactive kiosk modals work natively without a browser; installer script deploys `swonal-kiosk.apk` cleanly.

### Phase 3 — Local Fail-Safe Fast Path (`tonal-daemon` Direct Control)
- **Deliverables**:
  - Add direct local fallback client targeting `http://127.0.0.1:8765` (`tonal-daemon`) for safety-critical actions (`WEIGHT OFF` / emergency disengage / weight toggle) when `server-app` is unreachable or experiencing Wi-Fi latency, while reconciling state when `/ws` reconnects.
- **Exit Criteria**: Disconnecting LAN/Wi-Fi still allows immediate `WEIGHT OFF` from the Tonal screen in <5ms.

### Phase 4 — Low-Frequency Screens & Parity Verification
- **Deliverables**:
  - Keep `/history`, `/settings`, `/admin`, and `/companion` on `server-app` (either rendered in an on-demand browser/activity or ported to native RecyclerView/MPAndroidChart screens if desired).
  - End-to-end verification on `<tonal-ip>:5555`: live 30Hz pull test, PR celebration + `SoundPool` chime, Spotify dock playback, and `adb logcat` check for zero ANRs/crashes.
