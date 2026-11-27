#!/bin/sh
set -eu

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
exec java -jar "$SCRIPT_DIR/tools/cnkd-plugin-tool.jar" "$@"
