#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
if [[ -f .env ]]; then
  set -a
  source .env
  set +a
fi
export TEXT_PROVIDER=mock IMAGE_PROVIDER=mock VIDEO_PROVIDER=mock MOCK_ONLY=true
export TEXT_API_KEY='' IMAGE_API_KEY='' VIDEO_API_KEY=''
export DISPATCH_MODE=database APP_ROLE=all ADMISSION_ENABLED=false
: "${TEST_RABBITMQ_PORT:?Use an isolated test broker port}"
: "${TEST_RABBITMQ_MANAGEMENT_PORT:?Set the isolated management port}"
: "${TEST_RABBITMQ_NODE:?Set the disposable local vibestory* node name}"
scripts/java21.sh :api:rabbitTest :api:bootJar
exec python3 scripts/verify-phase7.py
