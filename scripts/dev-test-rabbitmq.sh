#!/usr/bin/env bash
# Disposable, loopback-only broker for the phase 7 protocol / process acceptance.
set -euo pipefail
cd "$(dirname "$0")/.."
broker_root="${TEST_RABBITMQ_ROOT:-$PWD/data/verification/rabbitmq-local}"
broker_port="${TEST_RABBITMQ_PORT:-5679}"
management_port="${TEST_RABBITMQ_MANAGEMENT_PORT:-15679}"
broker_node="${TEST_RABBITMQ_NODE:-vibestory_phase7@localhost}"
server="${RABBITMQ_SERVER:-$(command -v rabbitmq-server || true)}"
if [[ -z "$server" && -x /opt/homebrew/opt/rabbitmq/sbin/rabbitmq-server ]]; then
  server=/opt/homebrew/opt/rabbitmq/sbin/rabbitmq-server
fi
if [[ ! -x "$server" ]]; then echo 'Install RabbitMQ 4.4.0, or set RABBITMQ_SERVER.' >&2; exit 2; fi
if [[ ! "$broker_port" =~ ^[0-9]+$ || ! "$management_port" =~ ^[0-9]+$ || "$broker_port" == 5672 || "$management_port" == 15672 || "$broker_node" != vibestory*@localhost ]]; then
  echo 'Use isolated ports and a vibestory* local node.' >&2; exit 2
fi
mkdir -p "$broker_root"
cat > "$broker_root/rabbitmq.conf" <<CONFIG
listeners.tcp.default = 127.0.0.1:$broker_port
management.tcp.ip = 127.0.0.1
management.tcp.port = $management_port
CONFIG
printf '[rabbitmq_management].\n' > "$broker_root/enabled_plugins"
export RABBITMQ_CONF_ENV_FILE=/dev/null
export RABBITMQ_NODENAME="$broker_node" RABBITMQ_CONFIG_FILE="$broker_root/rabbitmq"
export RABBITMQ_ENABLED_PLUGINS_FILE="$broker_root/enabled_plugins"
export RABBITMQ_MNESIA_BASE="$broker_root/mnesia" RABBITMQ_LOG_BASE="$broker_root/log"
export RABBITMQ_NODE_IP_ADDRESS=127.0.0.1
exec "$server"
