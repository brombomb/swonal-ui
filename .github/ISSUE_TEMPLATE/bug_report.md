---
name: Bug Report
about: Report a hardware, safety, UI, or server bug in swonal-ui
title: "[Bug]: "
labels: bug
---

## Summary
A clear description of what happened vs. what you expected.

## Safety Impact
- [ ] Does this involve unexpected weight engagement, failed disengage, or motor safety?

## Environment
- **swonal-ui Version:** (e.g. `v1.1.1`)
- **Tonal Hardware:** V1 (firmware version if known, e.g. `5.2.18.0`)
- **Server Host:** (Docker / bare-metal Node.js, OS)
- **Kiosk / Client:** (Native Android Kiosk APK / Web Companion)

## Steps to Reproduce
1.
2.
3.

## Diagnostics / Logs
If applicable, please attach:
- Output of `curl http://<TONAL_IP>:8765/api/calibration`
- Recent lines from `/data/swonal/daemon.log`
- `docker logs --tail 50 swonal-server`
