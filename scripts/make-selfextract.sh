#!/usr/bin/env bash
#
# Build the single-file self-extracting installer  runway-<version>.bin  from the
# distribution zip produced by the Maven `dist` profile.
#
# Invoked automatically by `mvn -P dist package` (via maven-antrun-plugin), and
# runnable by hand:
#
#     scripts/make-selfextract.sh \
#         --zip  runway-app/target/runway-<version>-dist.zip \
#         --stub scripts/selfextract-stub.sh \
#         --out  runway-app/target/runway-<version>.bin
#
# The .bin is a copy of the stub script with a gzip-compressed tar of the bundle
# directory appended after the __RUNWAY_ARCHIVE__ marker. Only stock tools are
# needed — at build time (bash, unzip, tar, gzip, sed) and at install time (bash,
# tar, gzip, awk, tail); no `makeself` dependency.
#
set -euo pipefail

ZIP=""; STUB=""; OUT=""
while [ $# -gt 0 ]; do
    case "$1" in
        --zip)  ZIP="${2:?}"; shift 2 ;;
        --stub) STUB="${2:?}"; shift 2 ;;
        --out)  OUT="${2:?}"; shift 2 ;;
        *) echo "make-selfextract: unknown arg: $1" >&2; exit 2 ;;
    esac
done

[ -n "$ZIP" ]  || { echo "make-selfextract: --zip is required" >&2; exit 2; }
[ -n "$STUB" ] || { echo "make-selfextract: --stub is required" >&2; exit 2; }
[ -n "$OUT" ]  || { echo "make-selfextract: --out is required" >&2; exit 2; }
[ -f "$ZIP" ]  || { echo "make-selfextract: zip not found: $ZIP" >&2; exit 1; }
[ -f "$STUB" ] || { echo "make-selfextract: stub not found: $STUB" >&2; exit 1; }
command -v unzip >/dev/null || { echo "make-selfextract: 'unzip' is required" >&2; exit 1; }

# Derive the version from the zip name: runway-<version>-dist.zip
base="$(basename "$ZIP")"
version="${base#runway-}"; version="${version%-dist.zip}"
[ -n "$version" ] || { echo "make-selfextract: cannot parse version from $base" >&2; exit 1; }
bundle_dir="runway-${version}"

work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

echo "[make-selfextract] unpacking $ZIP"
unzip -q "$ZIP" -d "$work"
[ -d "$work/$bundle_dir" ] || { echo "make-selfextract: $bundle_dir not found inside zip" >&2; exit 1; }

# Ensure the scripts are executable inside the payload.
chmod 0755 "$work/$bundle_dir/scripts/install.sh" \
           "$work/$bundle_dir/bin/runway.sh" 2>/dev/null || true

echo "[make-selfextract] building payload"
payload="$work/payload.tar.gz"
# Reproducible payload. Three separate sources of drift, all pinned:
#   --mtime      tar records each entry's mtime, which is "now" for anything the build just staged
#   --owner/group + --numeric-owner   the builder's uid/gid would otherwise land in the archive
#   --sort=name  directory read order is filesystem-dependent, so entry ORDER varies between machines
#   gzip -n      gzip writes the source mtime AND name into its own header unless told not to
# Without these a rebuild of identical source produces a different .bin, which is exactly how the
# "the tree holds what we shipped" invariant quietly stopped holding for the plugin artifacts.
#
# The instant is the cut's, and there is ONE copy of it: the reactor pom's project.build.outputTimestamp, which also
# stamps every jar entry. The Maven build passes it in as BUILD_TIMESTAMP; a hand run reads it from the pom. (A second,
# hand-edited default in this script would let a cut that bumped only one of the two still build
# reproducibly — building twice cannot catch it — but ship a jar and a .bin claiming different instants.)
# SOURCE_DATE_EPOCH, the reproducible-builds convention, still wins when set. An instant that cannot be read is an
# error, never a silent fallback: a fallback is how the two copies drifted.
if [ -z "${SOURCE_DATE_EPOCH:-}" ] && [ -z "${BUILD_TIMESTAMP:-}" ]; then
    pom="$(dirname "$(readlink -f "$0")")/../pom.xml"
    BUILD_TIMESTAMP="$(sed -n 's:.*<project.build.outputTimestamp>\(.*\)</project.build.outputTimestamp>.*:\1:p' \
                       "$pom" 2>/dev/null | head -n1)"
fi
if [ -n "${SOURCE_DATE_EPOCH:-}" ]; then
    SOURCE_EPOCH="$SOURCE_DATE_EPOCH"
elif [[ "${BUILD_TIMESTAMP:-}" =~ ^[0-9]+$ ]]; then
    SOURCE_EPOCH="$BUILD_TIMESTAMP"                     # outputTimestamp may also be written as epoch seconds
elif [ -n "${BUILD_TIMESTAMP:-}" ] && SOURCE_EPOCH="$(date -u -d "$BUILD_TIMESTAMP" +%s 2>/dev/null)"; then
    :
else
    echo "make-selfextract: cannot read the build instant ('${BUILD_TIMESTAMP:-}') — set" >&2
    echo "  project.build.outputTimestamp in the reactor pom, or BUILD_TIMESTAMP / SOURCE_DATE_EPOCH" >&2
    exit 1
fi
echo "[make-selfextract] payload timestamp: $(date -u -d "@${SOURCE_EPOCH}" +%Y-%m-%dT%H:%M:%SZ)"
tar --sort=name --mtime="@${SOURCE_EPOCH}" --owner=0 --group=0 --numeric-owner \
    -cf - -C "$work" "$bundle_dir" | gzip -n -9 > "$payload"

echo "[make-selfextract] writing $OUT"
# Stub with the version substituted, then the marker (already in the stub), then
# the binary payload appended.
sed "s/@VERSION@/${version}/g" "$STUB" > "$OUT"
cat "$payload" >> "$OUT"
chmod 0755 "$OUT"

bytes="$(wc -c < "$OUT")"
echo "[make-selfextract] done: $OUT ($(( bytes / 1024 / 1024 )) MB)"
