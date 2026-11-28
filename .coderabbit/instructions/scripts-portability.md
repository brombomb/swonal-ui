---
id: scripts-portability
paths:
  - "scripts/**/*.sh"
---

- Check Bash/Termux portability, proper variable quoting, and safe error handling (`set -e` / explicit checks).
- Ensure scripts never destructively overwrite user `.env` or `swonal.db`.
