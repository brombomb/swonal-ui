---
id: tonal-daemon-safety
paths:
  - "packages/tonal-daemon/**/*.py"
---

This daemon drives physical Tonal V1 motorized resistance hardware on rooted Termux (**Python 3.8**).

- **Strict Python 3.8 compatibility:** Flag `X | Y` union syntax without `from __future__ import annotations`, `match`/`case`, and builtin generic subscripts (`list[...]`, `dict[...]`) evaluated at runtime.
- **Hardware safety invariants:** Enforce resistance bounds (`5.0..200.0 lb`), heartbeat watchdog, fail-safe `disengage_weight()` on errors/disconnects/transitions, and no uncommanded weight engagement.
- **Physical button safety:** Verify arm buttons can never toggle weight and that handle/accessory safety logic is preserved.
- **Tests:** Verify motor or serial protocol changes include unit tests in `packages/tonal-daemon/tests/`.
