# Changelog

Short, human-readable release notes for Swonal UI. Each version section below is automatically published to GitHub Releases and displayed inside the Android Kiosk **App Update** dialog.

## [2.0.2](https://github.com/brombomb/swonal-ui/compare/v2.0.1...v2.0.2) (2026-11-26)


### Bug Fixes

* **kiosk,scripts:** avoid Android 6 FileProvider fallback in updater, harden Magisk grant, and fix scrcpy auto-detect ([84f55b7](https://github.com/brombomb/swonal-ui/commit/84f55b7b9328a0bbb738ee88efd1706b685f6a97))
* **kiosk:** sync daemonConnected when local daemon connects to eliminate startup OFFLINE race ([45b2c8b](https://github.com/brombomb/swonal-ui/commit/45b2c8b24ec704c8c8e4ade74bcbcdbfc75dff15))

## [2.0.1](https://github.com/brombomb/swonal-ui/compare/v2.0.0...v2.0.1) (2026-11-26)


### Bug Fixes

* **daemon,installer:** support Termux Android 6 Python 3.8 and vendor patched swolectl ([#5](https://github.com/brombomb/swonal-ui/issues/5)) ([fb4c999](https://github.com/brombomb/swonal-ui/commit/fb4c999380903adb87fcf9d56456d584ec0a0414))

## [1.2.0](https://github.com/brombomb/swonal-ui/compare/v1.1.1...v1.2.0) (2026-11-23)


### New Features

* **android:** add in-app auto-updater with human-readable changelog ([#1](https://github.com/brombomb/swonal-ui/issues/1)) ([2bcca57](https://github.com/brombomb/swonal-ui/commit/2bcca574faf6eec8a97aa02ab3a693fd0bbaffff))

## [v1.2.0] - 2026-11-23
- **In-App Auto-Updater**: Automatically checks for new Swonal releases on startup and installs updates silently with a 1-tap root installer (blocked while weight is engaged for safety).
- **Human-Readable What's New**: View a concise changelog right on the Tonal screen before installing an update, or check anytime from Settings / Diagnostics.

## [v1.1.1] - 2026-11-22
- **Generic First-Run Defaults**: Removed personal host defaults from pre-built APKs and installer scripts for smooth first-time setup.
- **Hardened Docker Compose**: Closed unused ingress ports and bound mock daemon to loopback.
- **Safety & Security Docs**: Added motor safety warning, bug report template, and security reporting policy.

## [v1.1.0] - 2026-11-22
- **Routine Schedules**: Assign days of the week (`S M T W T F S`) to saved routines and launch today's workout in 1 tap from the main screen.
- **Free Lift Randomizer**: Tap the new dice (`🎲`) button in the Free Lift Catalog to pick a random movement.
- **Live Arm Sensors & Smart Handle Buttons**: Accurate Tonal V1 Height (`H 1–12`), Rotation (`R 1–3`), and Angle (`A 1–5`) tracking with color-coded Target / Left / Right rows, plus Smart Handle button weight toggle (`0x2003`).
- **Enhanced Motor Safety**: Automatic weight shutoff when switching workouts, on app disconnect, or after 45 seconds of cable inactivity, plus serialized motor commands and security hardening.
- **Compact Bottom Bar**: Hid the Spotify player for now while keeping the Tonal device volume slider front and center.

## [v1.0.0] - 2026-11-20
- Initial public release of `swonal-ui` for rooted Tonal V1 hardware.
