#!/bin/sh
# dbquery — Unix/macOS launcher for the dbquery fat-JAR
#
# Usage: dbquery [flags]
#   --host, --port, --database, --user, --password, --query, ...
#   (or set DB_HOST, DB_PORT, DB_NAME, DB_USER, DB_PASSWORD, DB_QUERY env vars)
#
# Requirements: Java 11+ on PATH

set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"

# Try to find dbquery.jar next to this script
if [ -f "$SCRIPT_DIR/dbquery.jar" ]; then
    JAR_PATH="$SCRIPT_DIR/dbquery.jar"
elif [ -f "$SCRIPT_DIR/target/dbquery.jar" ]; then
    JAR_PATH="$SCRIPT_DIR/target/dbquery.jar"
else
    echo "[ERROR] dbquery.jar not found next to the dbquery script" >&2
    echo "[ERROR] Expected: $SCRIPT_DIR/dbquery.jar" >&2
    exit 1
fi

exec java -jar "$JAR_PATH" "$@"
