#!/usr/bin/env bash
#
# make-release-aliases.sh — after the dist build has produced the versioned artifacts
#   runway-<version>-dist.zip  and  runway-<version>.bin
# in the target directory, this creates the stable-named "latest" aliases and a checksum
# manifest so a Runway release exposes a permanent, always-newest download.
#
#   1. Stable-named alias copies (byte-identical to the versioned originals):
#          runway-latest-dist.zip     runway-latest.bin
#      Gitea resolves <repo>/releases/download/latest/<name> to the newest release's asset,
#      but ONLY when the asset NAME is stable across releases — the versioned names are not,
#      so these fixed-name aliases give the permanent link. "latest" in the name also makes a
#      downloaded file self-describing.
#
#   2. SHA256SUMS over every shippable artifact (versioned + aliases + the plugin author's SPI
#      jars and poms that make-spi-assets.sh staged). The aliases share the hash of their
#      original; SHA256SUMS itself has a stable name, so it too has a latest link. The SPI
#      files are listed after the four installer lines and get no alias: an author pins an
#      exact kernel version, never "latest" (docs/PLUGIN-DEVELOPMENT.md §3).
#
# Wired into runway-dist's `dist` profile (maven-antrun), so it drops out of
# `mvn -P dist package` automatically — no manual step. Stock tools only (bash, cp, sha256sum).
#
# Usage:  make-release-aliases.sh <target-dir> <version>
set -euo pipefail

TARGET="${1:?usage: make-release-aliases.sh <target-dir> <version>}"
VERSION="${2:?usage: make-release-aliases.sh <target-dir> <version>}"
VERSION="${VERSION#v}"

zip="runway-${VERSION}-dist.zip"
bin="runway-${VERSION}.bin"

cd "$TARGET"
[ -f "$zip" ] || { echo "[aliases] missing $zip in $TARGET" >&2; exit 1; }
[ -f "$bin" ] || { echo "[aliases] missing $bin in $TARGET" >&2; exit 1; }
# The plugin author's artifacts (make-spi-assets.sh). Required: a release without them strands every
# third-party plugin build on that version, and publish-release.sh requires and uploads the same set.
spi=()
for m in runway-spi runway-spi-testkit; do
    for f in "${m}-${VERSION}.jar" "${m}-${VERSION}-sources.jar" "${m}-${VERSION}.pom"; do
        [ -f "$f" ] || { echo "[aliases] missing $f in $TARGET — run make-spi-assets.sh first" >&2; exit 1; }
        spi+=( "$f" )
    done
done

cp -f "$zip" "runway-latest-dist.zip"
cp -f "$bin" "runway-latest.bin"

# Deterministic order: the versioned installer pair, its aliases, then the SPI files.
sha256sum "$zip" "$bin" "runway-latest-dist.zip" "runway-latest.bin" "${spi[@]}" > SHA256SUMS

echo "[aliases] latest aliases + SHA256SUMS written to $TARGET:"
ls -lh runway-*-dist.zip runway-*.bin "${spi[@]}" SHA256SUMS 2>/dev/null | awk '{printf "  %-46s %s\n", $9, $5}'
