#!/usr/bin/env bash
# Run all GitHub Actions CI checks locally in a clean-room environment
# without touching your local .env or prisma/data/swonal.db.
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

echo "=== 0/3 Validating GitHub Workflows & Shell Scripts ==="
if ! python3 -c "import yaml" 2>/dev/null; then
  echo "ERROR: PyYAML is required for the workflow check (python3 -m pip install pyyaml)." >&2
  exit 1
fi
python3 - <<'PY'
import glob, re, sys, yaml

for path in sorted(glob.glob(".github/workflows/*.yml") + [".github/labeler.yml"]):
    with open(path, encoding="utf-8") as f:
        text = f.read()
    yaml.safe_load(text)
    if path.startswith(".github/workflows/"):
        for idx, line in enumerate(text.splitlines(), 1):
            if re.search(r"^\s*if:\s*.*\bsecrets\.", line):
                print(f"ERROR: {path}:{idx}: GitHub Actions does not allow 'secrets.*' inside 'if:' expressions.")
                sys.exit(1)
    print(f"  OK: {path}")
PY
bash -n scripts/*.sh
echo "  OK: bash -n scripts/*.sh"
if command -v shellcheck >/dev/null 2>&1; then
  shellcheck -S error scripts/*.sh
  echo "  OK: shellcheck -S error scripts/*.sh"
else
  echo "  (shellcheck not installed locally; skipping, CI runs it)"
fi

echo ""
echo "=== 1/3 Server App (Clean-Room SQLite DB, Typecheck, Build & Tests) ==="
TMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TMP_DIR"' EXIT
TMP_DB="$TMP_DIR/ci-test.db"

(
  cd "$ROOT_DIR/packages/server-app"
  # Isolate from local .env and local swonal.db to match GitHub Actions runner
  export DATABASE_URL="file:$TMP_DB"
  export SWONAL_SKIP_DOTENV=1
  unset SPOTIFY_CLIENT_ID SPOTIFY_CLIENT_SECRET SPOTIFY_REDIRECT_URI PUBLIC_URL COMPANION_BASE_URL
  npx prisma generate >/dev/null
  npx prisma db push --skip-generate >/dev/null
  npx tsx prisma/seed.ts >/dev/null
  npm run typecheck
  npm run build
  npm test
)

echo ""
echo "=== 2/3 Tonal Daemon (Lint & Safety Tests) ==="
(
  cd "$ROOT_DIR/packages/tonal-daemon"
  if [ -x ".venv/bin/ruff" ]; then
    .venv/bin/ruff check --target-version py38 --select E9,F63,F7,F82 src tests
  elif command -v ruff >/dev/null 2>&1; then
    ruff check --target-version py38 --select E9,F63,F7,F82 src tests
  else
    echo "  (ruff not installed locally; skipping lint step)"
  fi
  python3 - <<'PY'
import ast, glob

files = sorted(glob.glob("src/**/*.py", recursive=True) + glob.glob("tests/**/*.py", recursive=True))
for path in files:
    with open(path, encoding="utf-8") as f:
        ast.parse(f.read(), filename=path, feature_version=(3, 8))
print(f"  OK: Python 3.8 AST check passed ({len(files)} files)")
PY
  if [ -x ".venv/bin/pytest" ]; then
    .venv/bin/pytest -v tests
  else
    pytest -v tests
  fi
)

echo ""
echo "=== 3/3 Android Kiosk (Unit Tests & Debug Build) ==="
(
  cd "$ROOT_DIR/packages/android-kiosk"
  ./gradlew :app:testDebugUnitTest :app:assembleDebug --console=plain
)

echo ""
echo "✅ All local CI checks passed!"
