#!/usr/bin/env bash
# TradeLens engine on Linux/macOS. Usage: ./run_linux.sh [--local] [port]
set -euo pipefail
cd "$(dirname "$0")"
BIND=0.0.0.0; [ "${1:-}" = "--local" ] && { BIND=127.0.0.1; shift; }
PORT="${1:-8000}"
[ -d .venv ] || { python3 -m venv .venv && .venv/bin/pip install -q -r requirements.txt; }
if [ -z "${TRADELENS_API_TOKEN:-}" ]; then
  [ -f .token ] || .venv/bin/python -m tradelens token > .token
  export TRADELENS_API_TOKEN="$(cat .token)"
fi
IP=$(hostname -I 2>/dev/null | awk '{print $1}' || ipconfig getifaddr en0 2>/dev/null || echo "<this-machine-ip>")
echo "================ TradeLens engine ================"
echo " URL   : http://${IP}:${PORT}   (Settings > Engine in the app; same Wi-Fi)"
echo " Token : ${TRADELENS_API_TOKEN}"
echo " Check : open http://${IP}:${PORT}/health in the phone's browser"
echo "=================================================="
exec .venv/bin/python -m tradelens serve --host "$BIND" --port "$PORT"
