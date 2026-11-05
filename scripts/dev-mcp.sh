#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
launch_url="${VIBESTORY_MCP_URL:-}"
launch_email="${VIBESTORY_MCP_EMAIL:-}"
launch_password="${VIBESTORY_MCP_PASSWORD:-}"
if [[ -f .env ]]; then
  source .env >&2
fi
if [[ "$(uname -s)" == Darwin ]]; then
  export JAVA_HOME="$(/usr/libexec/java_home -v 21)"
fi
export PATH="${JAVA_HOME:?Set JAVA_HOME to a JDK 21 installation}/bin:$PATH"
export VIBESTORY_MCP_URL="${launch_url:-${VIBESTORY_MCP_URL:-http://127.0.0.1:${PORT:-8080}}}"
export VIBESTORY_MCP_EMAIL="${launch_email:-${VIBESTORY_MCP_EMAIL:-${SEED_EMAIL:-}}}"
export VIBESTORY_MCP_PASSWORD="${launch_password:-${VIBESTORY_MCP_PASSWORD:-${SEED_PASSWORD:-}}}"
unset SEED_PASSWORD DATABASE_PASSWORD TEXT_API_KEY IMAGE_API_KEY VIDEO_API_KEY REDIS_PASSWORD RABBITMQ_PASSWORD
launcher=apps/mcp/build/install/vibestory-mcp/bin/vibestory-mcp
if [[ ! -x "$launcher" ]]; then
  echo '请先运行 scripts/java21.sh :mcp:installDist。' >&2
  exit 1
fi
exec "$launcher"
