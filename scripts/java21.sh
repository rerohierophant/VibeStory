#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
if [[ "$(uname -s)" == Darwin ]]; then
  export JAVA_HOME="$(/usr/libexec/java_home -v 21)"
fi
export PATH="${JAVA_HOME:?Set JAVA_HOME to a JDK 21 installation}/bin:$PATH"
exec ./gradlew "$@"
