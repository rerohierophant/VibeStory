#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
if [[ -f .env ]]; then set -a; source .env; set +a; fi
export TEXT_PROVIDER=mock IMAGE_PROVIDER=mock VIDEO_PROVIDER=mock MOCK_ONLY=true
export TEXT_API_KEY='' IMAGE_API_KEY='' VIDEO_API_KEY=''
export DISPATCH_MODE=database APP_ROLE=all ADMISSION_ENABLED=false
: "${TEST_REDIS_PORT:?Start scripts/dev-test-redis.sh on an isolated port}"
: "${TEST_RABBITMQ_PORT:?Use an isolated RabbitMQ broker port}"
: "${TEST_RABBITMQ_MANAGEMENT_PORT:?Set the isolated management port}"
scripts/java21.sh :api:redisTest :api:bootJar
# The process acceptance owns another Redis process; only its own process is stopped/restarted.
exec python3 scripts/verify-phase8.py
