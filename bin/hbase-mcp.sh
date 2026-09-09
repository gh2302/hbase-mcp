#!/usr/bin/env bash
# Launches the HBase MCP server on stdio.
#
# HBase 2.6 supports Java 17 at most, so prefer an explicit JAVA_HOME_17 if one is set.
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
jar="$here/../target/hbase-mcp.jar"

if [[ -n "${JAVA_HOME_17:-}" ]]; then
  java="$JAVA_HOME_17/bin/java"
elif [[ -n "${JAVA_HOME:-}" ]]; then
  java="$JAVA_HOME/bin/java"
else
  java="java"
fi

if [[ ! -f "$jar" ]]; then
  echo "hbase-mcp: $jar not found. Run 'mvn package' first." >&2
  exit 1
fi

# Never write anything to stdout here: it is the JSON-RPC channel.
exec "$java" -jar "$jar" "$@"
