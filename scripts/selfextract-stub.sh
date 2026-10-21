#!/usr/bin/env bash
#
# Runway @VERSION@ — self-extracting Linux installer.
#
# This file is a shell script with a gzip-compressed tar archive appended after
# the __RUNWAY_ARCHIVE__ marker line. Running it unpacks the bundle to a temporary
# directory and launches scripts/install.sh; any arguments pass straight through
# to the installer.
#
#   sudo ./runway-@VERSION@.bin              # install / upgrade (see --help)
#   sudo ./runway-@VERSION@.bin --port 8080 --open-firewall
#   ./runway-@VERSION@.bin --extract DIR     # just unpack the bundle into DIR
#   ./runway-@VERSION@.bin --help            # installer help
#
set -euo pipefail

RUNWAY_VERSION="@VERSION@"
SELF="$(readlink -f "$0")"

# First marker line; the archive starts on the next line.
archive_start() {
    awk '/^__RUNWAY_ARCHIVE__$/ { print NR + 1; exit 0 }' "$SELF"
}

extract_to() {
    local dest="$1" start
    start="$(archive_start)"
    [ -n "$start" ] || { echo "runway installer: corrupt archive (no marker)" >&2; exit 1; }
    mkdir -p "$dest"
    tail -n +"$start" "$SELF" | tar xzf - -C "$dest"
}

# --extract DIR: unpack only (no install). Handy for inspection or staging.
if [ "${1:-}" = "--extract" ]; then
    DEST="${2:?--extract needs a target directory}"
    extract_to "$DEST"
    echo "runway ${RUNWAY_VERSION} bundle extracted to: ${DEST}/runway-${RUNWAY_VERSION}"
    echo "Install with: sudo ${DEST}/runway-${RUNWAY_VERSION}/scripts/install.sh [options]"
    exit 0
fi

WORK="$(mktemp -d "${TMPDIR:-/tmp}/runway-install.XXXXXX")"
cleanup() { rm -rf "$WORK"; }
trap cleanup EXIT

extract_to "$WORK"
INSTALL="$WORK/runway-${RUNWAY_VERSION}/scripts/install.sh"
[ -f "$INSTALL" ] || { echo "runway installer: install.sh missing from bundle" >&2; exit 1; }
chmod +x "$INSTALL"
bash "$INSTALL" "$@"
exit $?

# Everything below the next line is the gzip-compressed tar payload.
__RUNWAY_ARCHIVE__
