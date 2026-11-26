# Vendored `swolectl` (Python 3.8+ Compatible)

This directory contains a pure-Python vendored copy of [`d4l3k/swolectl`](https://github.com/d4l3k/swolectl) patched for **Python 3.8.0** compatibility so `tonal-daemon` can run directly on Tonal V1's Android 6.0 (API 23) tablet using the legacy Termux Android 5/6 Python 3.8 runtime:

- Added `from __future__ import annotations` for postponed type annotation evaluation.
- Removed `slots=True` from `@dataclass` decorators (Python 3.10+ only).
- Added a `StrEnum` compatibility fallback for Python < 3.11.

Because `tonal-daemon` executes from `packages/tonal-daemon` (`python3 -m src.main`), Python resolves `import swolectl` directly from this directory without requiring `git` or Python 3.11+ on the Tonal tablet.
