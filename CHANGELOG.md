# Changelog

Short, human-readable release notes for Swonal UI. Each version section below is automatically published to GitHub Releases and displayed inside the Android Kiosk **App Update** dialog.

## [2.3.3](https://github.com/brombomb/swonal-ui/compare/v2.3.2...v2.3.3) (2026-11-28)


### Bug Fixes

* **server-app,kiosk:** a set with 0 completed reps never becomes a PR ([#18](https://github.com/brombomb/swonal-ui/issues/18)) ([08d8959](https://github.com/brombomb/swonal-ui/commit/08d8959324c4aaf39373bb7da7086107209238fa))

## [2.3.2](https://github.com/brombomb/swonal-ui/compare/v2.3.1...v2.3.2) (2026-11-28)


### Bug Fixes

* **daemon,server-app,kiosk:** real Tonal V1 arm-position scales, allowed position sets, one-arm alignment ([#25](https://github.com/brombomb/swonal-ui/issues/25)) ([c4783ad](https://github.com/brombomb/swonal-ui/commit/c4783ad893f1ea460f989a43c82451ff053305d7))

## [2.3.1](https://github.com/brombomb/swonal-ui/compare/v2.3.0...v2.3.1) (2026-11-28)


### Bug Fixes

* **scripts:** make ci-local.sh run the same checks as CI ([#44](https://github.com/brombomb/swonal-ui/issues/44)) ([7109ad0](https://github.com/brombomb/swonal-ui/commit/7109ad07e48257ced06e566bd8cc131887154df1))

## [2.3.0](https://github.com/brombomb/swonal-ui/compare/v2.2.0...v2.3.0) (2026-11-28)


### New Features

* **android:** optional screen sleep after inactivity ([#26](https://github.com/brombomb/swonal-ui/issues/26)) ([62fa37f](https://github.com/brombomb/swonal-ui/commit/62fa37f0495ef2772cf87dcc4b07fb2492348f29))
* **routines:** add Copy Block to the Kiosk and Companion routine builders ([#27](https://github.com/brombomb/swonal-ui/issues/27)) ([8d5604e](https://github.com/brombomb/swonal-ui/commit/8d5604eb1d0c267eeb6a7400014a8f0b7c990704))


### Bug Fixes

* **android:** only highlight the selected attachment button ([#28](https://github.com/brombomb/swonal-ui/issues/28)) ([60c2c24](https://github.com/brombomb/swonal-ui/commit/60c2c249451d9e480d5b51254e3002bc1a5b5402))

## [2.2.0](https://github.com/brombomb/swonal-ui/compare/v2.1.4...v2.2.0) (2026-11-28)


### New Features

* **android:** add an X close button to the routine builder's Select Exercise picker ([#29](https://github.com/brombomb/swonal-ui/issues/29)) ([0f86f43](https://github.com/brombomb/swonal-ui/commit/0f86f43349d6975a3a9a820be74c2bed55e3fd43))


### Bug Fixes

* **daemon:** arm buttons never toggle weight, both handle buttons do; disengage uncommanded hardware engage ([#20](https://github.com/brombomb/swonal-ui/issues/20)) ([3041789](https://github.com/brombomb/swonal-ui/commit/3041789306d560b6f071bc8099482268b3978d9b))
* **daemon:** start the target weight at the configured minimum, not 45 lb ([#23](https://github.com/brombomb/swonal-ui/issues/23)) ([0758304](https://github.com/brombomb/swonal-ui/commit/075830407e5689c1dd97fc43800d80f57583efbb))

## [2.1.4](https://github.com/brombomb/swonal-ui/compare/v2.1.3...v2.1.4) (2026-11-28)


### Bug Fixes

* **server-app:** save exercise notes on create and update ([95b7060](https://github.com/brombomb/swonal-ui/commit/95b70600fba52cacea33d81f5aa438d6e750954d))

## [2.1.3](https://github.com/brombomb/swonal-ui/compare/v2.1.2...v2.1.3) (2026-11-28)


### Bug Fixes

* **daemon,installer:** load config.yaml on Termux and warn when the motor is LAN-exposed ([4cc67f6](https://github.com/brombomb/swonal-ui/commit/4cc67f69aa340f89221b2db4978651081c4dbd80))
* **server-app:** run the seed script entry only when invoked directly ([36a9ebc](https://github.com/brombomb/swonal-ui/commit/36a9ebca0b1a2fc55a96c82cc120353e1709d301))

## [2.1.2](https://github.com/brombomb/swonal-ui/compare/v2.1.1...v2.1.2) (2026-11-26)


### Bug Fixes

* **installer:** replace /system/etc bind-mount with systemless Magisk DNS module to prevent SELinux Wi-Fi firmware denial ([#13](https://github.com/brombomb/swonal-ui/issues/13)) ([3df0c88](https://github.com/brombomb/swonal-ui/commit/3df0c885db13ac3bfdd13c46998078c5a45a6d10))

## [2.1.1](https://github.com/brombomb/swonal-ui/compare/v2.1.0...v2.1.1) (2026-11-26)


### Bug Fixes

* **server-app:** skip outbound Nostr relay sync in tests and bind ws to SimplePool module ([920188a](https://github.com/brombomb/swonal-ui/commit/920188a96330945ab92b906666cac6bc37679333))

## [2.1.0](https://github.com/brombomb/swonal-ui/compare/v2.0.2...v2.1.0) (2026-11-26)


### New Features

* add Smart Flex resistance mode across daemon, Kiosk, Companion, and MCP ([d9ca6d1](https://github.com/brombomb/swonal-ui/commit/d9ca6d1cb5d93db835b7faf4ef1c2510550bc856))
* **android:** add native History, Application Settings, and Admin screens (zero GeckoView warmup) ([ca636ab](https://github.com/brombomb/swonal-ui/commit/ca636ab6d9151323373e72dac96e83410043cf77))
* **kiosk:** add offline Vosk STT and opt-in on-device HTTPS (:53443) ([c471faf](https://github.com/brombomb/swonal-ui/commit/c471faffa2715374a60225c8b41914b1c8f01327))
* **kiosk:** bundle offline Vosk voice model in APK and make local motor daemon read-only ([50ae603](https://github.com/brombomb/swonal-ui/commit/50ae603be711a358fde1026aec76935ad80c3fd5))
* **kiosk:** convert History, App Settings, and Admin into full-screen native pages ([7e919b6](https://github.com/brombomb/swonal-ui/commit/7e919b62f6da74f67e4fb94f554db33f94e88bac))
* **kiosk:** embed profile prefs, diagnostics, sync, catalog, bug report & release check in full-page settings ([8c798f4](https://github.com/brombomb/swonal-ui/commit/8c798f42ef7f971866c9d68bfe870560b0131e49))
* **mcp:** add Streamable HTTP (2025-03-26) transport at /mcp alongside /mcp/sse ([7e76913](https://github.com/brombomb/swonal-ui/commit/7e76913c9e8870fd299ce6596992a94d065e36eb))


### Bug Fixes

* **ci,kiosk:** deduplicate release Discord webhooks and add GeckoView PromptDelegate for alert/confirm ([6f828d3](https://github.com/brombomb/swonal-ui/commit/6f828d3c811dc9f0cf12f13958792a0b014c01a1))
* **daemon:** allow safe Stock Tonal handoff when weight is off on stale serial FD and ensure overlay permission ([9564993](https://github.com/brombomb/swonal-ui/commit/95649930f78a20b3fa400ab9dbba37fb15ab0e46))
* **kiosk:** auto-normalize sync URL scheme and save SERVER_SYNC before Sync Now / Pull ([568b5ef](https://github.com/brombomb/swonal-ui/commit/568b5ef021397566598b61b189a262421afb2cab))
* **kiosk:** eliminate Light/Dark bottom-corner border artifacts, add fixed-height mode helper text, and keep Voice label static ([f67b5d5](https://github.com/brombomb/swonal-ui/commit/f67b5d54fe4e7bc417b0914252cc912b797a09df))
* **kiosk:** fix Swocial detach crash & immersive nav bar; add MCP info, HA/URL override, passcode lock, catalog search, and History streak/weekly volume ([bddf8e0](https://github.com/brombomb/swonal-ui/commit/bddf8e08736584fb115f6b2c55fad0b367240f1e))
* **kiosk:** green Voice On / muted mic icon when off, and launch Return-to-Swonal overlay on Stock Tonal switch ([1de6d1d](https://github.com/brombomb/swonal-ui/commit/1de6d1d8bc5ee0bc5e3e3f8959a2ee0d2d405416))
* **kiosk:** live-refresh Light/Dark theme canvas in AppSettingsDialog and restore Exercise Media URL editor ([4d160da](https://github.com/brombomb/swonal-ui/commit/4d160da8a5df8b5319a4944390074cf2acc08178))
* **kiosk:** remove API-23 translucent card elevation shadows, reorder mode pills, drop (+25%) badge & 'digital' ([3e06a98](https://github.com/brombomb/swonal-ui/commit/3e06a98c126565ae929dc0ba3dc1b6f8d984bf98))

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
