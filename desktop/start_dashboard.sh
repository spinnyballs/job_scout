#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

if command -v python3 >/dev/null 2>&1; then
    PY_CMD="python3"
elif command -v python >/dev/null 2>&1; then
    PY_CMD="python"
else
    echo ""
    echo "[!] Python 3.10+ was not found. Install Python 3 from https://www.python.org/downloads/"
    echo ""
    exit 1
fi

exec "$PY_CMD" "$SCRIPT_DIR/launch.py" "$@"
