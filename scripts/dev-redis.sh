#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
role="${1:-api}"
if [[ "$role" != api && "$role" != worker ]]; then echo 'Usage: scripts/dev-redis.sh api|worker [Gradle arguments...]' >&2; exit 2; fi
shift || true
if [[ -f .env ]]; then set -a; source .env; set +a; fi
export TEXT_PROVIDER=mock IMAGE_PROVIDER=mock VIDEO_PROVIDER=mock MOCK_ONLY=true
export TEXT_API_KEY='' IMAGE_API_KEY='' VIDEO_API_KEY=''
export DISPATCH_MODE=rabbit APP_ROLE="$role" ADMISSION_ENABLED=true
export STORAGE_ROOT="${STORAGE_ROOT:-$PWD/data/storage}"
if [[ "$role" == worker ]]; then export PORT=0; fi
exec scripts/java21.sh :api:bootRun "$@"
