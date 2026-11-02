#!/usr/bin/env bash
# Dedicated local test Redis. Never starts/stops the user's default service.
set -euo pipefail
cd "$(dirname "$0")/.."
redis_port="${TEST_REDIS_PORT:-6389}"
redis_root="${TEST_REDIS_ROOT:-$PWD/data/verification/redis-local}"
if [[ ! "$redis_port" =~ ^[0-9]+$ || "$redis_port" == 6379 ]]; then echo 'Use an isolated Redis port.' >&2; exit 2; fi
mkdir -p "$redis_root"
exec "${REDIS_SERVER:-redis-server}" --bind 127.0.0.1 --port "$redis_port" --protected-mode yes \
  --dir "$redis_root" --save '' --appendonly no --maxmemory-policy noeviction
