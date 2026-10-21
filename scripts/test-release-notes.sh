#!/usr/bin/env bash
# test-release-notes.sh — tests for release-notes.sh, the release-body extraction and refusal of publish-release.sh.
# Runs offline, in a scratch directory: `bash scripts/test-release-notes.sh [scratch-parent]`. The dist module runs
# it in its test phase next to test-plugin-seed.sh (skipped with -DskipTests). Exit status 0 = all passed.
#
# The defect it guards against: the development guide said publishing refused a release section with no
# "Upgrading — read this first" part, and the script only refused a MISSING section — so a release's notes
# could ship without any operator steps.
set -euo pipefail

HERE="$(dirname "$(readlink -f "$0")")"
# Release tooling is maintainer-only: a checkout without scripts/publish-release.sh (the public source tree) has
# nothing for this test to check, so it passes with a note instead of failing the build.
if [ ! -f "$HERE/publish-release.sh" ]; then
    echo "release-notes: skipped (scripts/publish-release.sh is not in this checkout)"
    exit 0
fi
# shellcheck source=release-notes.sh
. "$HERE/release-notes.sh"

PARENT="${1:-${TMPDIR:-/tmp}}"
mkdir -p "$PARENT"
ROOT="$(mktemp -d "$PARENT/release-notes-test.XXXXXX")"
trap 'rm -rf "$ROOT"' EXIT

FAILS=0
PASSES=0
fail() { printf 'FAIL: %s\n' "$*" >&2; FAILS=$((FAILS + 1)); }
pass() { PASSES=$((PASSES + 1)); }
check() { if eval "$2"; then pass; else fail "$1"; fi; }

# rc <notes> <version> — release_notes_check's exit status, its stderr kept in $ROOT/err.
rc() { local r=0; release_notes_check "$1" "$2" 2> "$ROOT/err" || r=$?; echo "$r"; }

CL="$ROOT/CHANGELOG.md"
cat > "$CL" <<'EOF'
# Changelog

## [Unreleased]

### Added

- something new

## [1.2.0] — 2026-10-18

_A summary line._

### Upgrading — read this first

Run the installer with --update-essential-plugins.

### Fixed

- a fix

## [1.1.0] — 2026-10-01

### Fixed

- a fix with no upgrade part

### Upgrading — read this first

Too late: not the first heading.

## [1.0.0-beta1] — 2026-09-01

### Added

- the first beta, without an upgrade part

## [1.0.0-beta12] — 2026-09-20

### Upgrading — read this first

Nothing to do.

## [0.9.0] — 2026-08-01

### Upgrading — read this first

The last section of the file.
EOF

# --- 1. Extraction ----------------------------------------------------------------------------------------------
N="$(release_notes_extract "$CL" 1.2.0)"
check "1: the section body starts after the heading line" '[ "$(printf "%s\n" "$N" | head -1)" = "_A summary line._" ]'
check "1: the section stops at the next version" '! printf "%s" "$N" | grep -q "no upgrade part"'
check "1: the section keeps its own sub-sections" 'printf "%s" "$N" | grep -q "^- a fix$"'
check "1: an unknown version extracts nothing" '[ -z "$(release_notes_extract "$CL" 3.0.0)" ]'
check "1: the last section of the file extracts (no following heading)" 'release_notes_extract "$CL" 0.9.0 | grep -q "The last section of the file."'
check "1: 1.0.0-beta1 does not match 1.0.0-beta12" '[ "$(release_notes_extract "$CL" 1.0.0-beta1 | tail -1)" = "- the first beta, without an upgrade part" ]'
check "1: dots are literal (1x2x0 is not 1.2.0)" '[ -z "$(release_notes_extract "$CL" 1x2x0)" ]'

# --- 2. Refusals ------------------------------------------------------------------------------------------------
check "2: a missing section is refused with status 1" '[ "$(rc "" 3.0.0)" = 1 ] && grep -q "has no .## \[3.0.0\]. section" "$ROOT/err"'
check "2: a section without the upgrade part is refused with status 2" '[ "$(rc "$(release_notes_extract "$CL" Unreleased)" Unreleased)" = 2 ] && grep -q "does not open with" "$ROOT/err"'
check "2: the refusal names the first heading it found" 'grep -q "is: .### Added." "$ROOT/err"'
check "2: an upgrade part that is not FIRST is refused" '[ "$(rc "$(release_notes_extract "$CL" 1.1.0)" 1.1.0)" = 2 ]'
check "2: the next section's upgrade part is not borrowed" '[ "$(rc "$(release_notes_extract "$CL" 1.0.0-beta1)" 1.0.0-beta1)" = 2 ]'
check "2: a near-miss heading is refused" '[ "$(rc "$(printf "### Upgrading\n\nx\n")" 9.9.9)" = 2 ]'
check "2: a heading only in body text does not count" '[ "$(rc "$(printf "### Added\n\nsee ### Upgrading — read this first\n")" 9.9.9)" = 2 ]'

# --- 3. Accepted ------------------------------------------------------------------------------------------------
check "3: a section that opens with the upgrade part passes" '[ "$(rc "$(release_notes_extract "$CL" 1.2.0)" 1.2.0)" = 0 ] && [ ! -s "$ROOT/err" ]'
check "3: a section with only the upgrade part passes" '[ "$(rc "$(release_notes_extract "$CL" 1.0.0-beta12)" 1.0.0-beta12)" = 0 ]'
BIG="$(printf '### Upgrading — read this first\n\n'; head -c 61000 /dev/zero | tr '\0' 'x')"
check "3: an oversized body passes with a warning" '[ "$(rc "$BIG" 9.9.9)" = 0 ] && grep -q "WARNING: the release notes are" "$ROOT/err"'

# --- 4. The real CHANGELOG: the published releases since the rule existed pass, an older one does not --------
REAL="$HERE/../CHANGELOG.md"
for v in 0.5.0-beta10 0.5.0-beta11 0.5.0-beta12 0.5.0-beta13; do
    check "4: published $v passes" '[ "$(rc "$(release_notes_extract "$REAL" "$v")" "$v")" = 0 ]'
done
check "4: 0.5.0-beta9 (before the rule) is refused" '[ "$(rc "$(release_notes_extract "$REAL" 0.5.0-beta9)" 0.5.0-beta9)" = 2 ]'

# --- 5. publish-release.sh is wired to the check, and refuses before the tag, the credential or the network -----
# It needs runway-dist/target to exist (the dist module's test phase has created it). 0.5.0-beta9's artifacts are
# never in target/, so even a broken script stops at "missing artifact" — nothing can be published from here.
if [ -d "$HERE/../runway-dist/target" ]; then
    r=0; bash "$HERE/publish-release.sh" 0.5.0-beta9 > "$ROOT/pub.out" 2> "$ROOT/pub.err" || r=$?
    check "5: publish-release.sh refuses a section without the upgrade part" '[ "$r" -ne 0 ] && grep -q "does not open with" "$ROOT/pub.err"'
    check "5: ...before looking for artifacts, the tag or a credential" '! grep -q "missing artifact\|is not on origin\|no stored credential" "$ROOT/pub.err"'
else
    fail "5: runway-dist/target does not exist — run this from the dist module's test phase (mvn test)"
fi

printf 'release-notes: %d passed, %d failed\n' "$PASSES" "$FAILS"
[ "$FAILS" -eq 0 ]
