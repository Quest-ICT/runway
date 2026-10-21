#!/usr/bin/env bash
#
# install-into.sh — copy the assembled discoverability plugin into a Runway deployment's external tree.
# The host must be RESTARTED afterwards for the plugin to load (restart-to-install; no hot reload).
#
# Usage:  scripts/install-into.sh <runway-external-dir>
#   e.g.  scripts/install-into.sh /opt/runway-data/external
set -euo pipefail

EXTERNAL="${1:?usage: install-into.sh <runway-external-dir>}"
PROJECT_DIR="$(cd "$(dirname "$(readlink -f "$0")")/.." && pwd)"
STAGE="$PROJECT_DIR/target/discoverability"

[ -d "$STAGE" ] || { echo "ERROR: $STAGE not built — run 'mvn package' first"; exit 1; }
[ -d "$EXTERNAL" ] || { echo "ERROR: not a directory: $EXTERNAL"; exit 1; }

mkdir -p "$EXTERNAL/plugins"
rm -rf "$EXTERNAL/plugins/discoverability"
cp -a "$STAGE" "$EXTERNAL/plugins/discoverability"
echo "installed discoverability → $EXTERNAL/plugins/discoverability"
echo "RESTART the Runway host for it to load (plugins load only in ACTIVE mode)."
