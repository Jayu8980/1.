#!/usr/bin/env bash
# TradeLens engine on Linux/macOS: creates .venv on first run and starts the API on 127.0.0.1:8000
set -euo pipefail
cd "$(dirname "$0")"
[ -d .venv ] || { python3 -m venv .venv && .venv/bin/pip install -q -r requirements.txt; }
if [ -z "${TRADELENS_API_TOKEN:-}" ]; then
  [ -f .token ] || .venv/bin/python -m tradelens token > .token
  export TRADELENS_API_TOKEN="$(cat .token)"
  echo "API token (paste into the app): $TRADELENS_API_TOKEN"
fi
exec .venv/bin/python -m tradelens serve --host 127.0.0.1 --port 8000
