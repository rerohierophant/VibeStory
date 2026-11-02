#!/usr/bin/env bash
# One free, reproducible closeout. Private environment is never printed.
set -euo pipefail
cd "$(dirname "$0")/.."
if [[ -f .env ]]; then
  set -a
  source .env
  set +a
fi
export DISPATCH_MODE=database APP_ROLE=all ADMISSION_ENABLED=false
export TEXT_PROVIDER=mock IMAGE_PROVIDER=mock VIDEO_PROVIDER=mock MOCK_ONLY=true
export TEXT_API_KEY='' IMAGE_API_KEY='' VIDEO_API_KEY=''
exec python3 scripts/verify-phase6.py
