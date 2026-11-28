---
id: server-app-parity
paths:
  - "packages/server-app/**/*.ts"
  - "packages/server-app/**/*.tsx"
---

- Verify input validation, error handling, and Prisma SQLite query safety on REST and WebSocket endpoints.
- Check payload and schema parity with `packages/android-kiosk` (`EmbeddedSwonalServer` / `SwonalDatabase`).
- Ensure tests use isolated temporary SQLite databases and never touch `.env` or `packages/server-app/prisma/data/swonal.db`.
