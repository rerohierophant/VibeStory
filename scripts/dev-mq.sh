#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
role="${1:-api}"
if [[ "$role" != api && "$role" != worker ]]; then
  echo 'Usage: scripts/dev-mq.sh api|worker [Spring arguments...]' >&2
  exit 2
fi
shift || true
if [[ -f .env ]]; then
  set -a
  source .env
  set +a
fi
# Phase 7 local development follows the authorized Mock-only acceptance boundary.
export TEXT_PROVIDER=mock IMAGE_PROVIDER=mock VIDEO_PROVIDER=mock MOCK_ONLY=true
export TEXT_API_KEY='' IMAGE_API_KEY='' VIDEO_API_KEY=''
export DISPATCH_MODE=rabbit APP_ROLE="$role"
export STORAGE_ROOT="${STORAGE_ROOT:-$PWD/data/storage}"
# Multiple workers do not expose HTTP. The Spring security bean still initializes normally.
if [[ "$role" == worker ]]; then export PORT=0; fi
exec scripts/java21.sh :api:bootRun "$@"
