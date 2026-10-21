# release-notes.sh — the release body of a Runway version, taken from CHANGELOG.md. Sourced by publish-release.sh;
# also sourced on its own by test-release-notes.sh, which is why it defines functions and runs nothing at load time.
#
# Why this is a gate and not a convenience. The release body IS the upgrade notes an operator reads
# before upgrading. beta10 shipped with none (the body said "See CHANGELOG.md"), so the notes now come from the
# CHANGELOG section keyed by the version. That made a MISSING section impossible, but not a section WITHOUT the
# part an operator acts on: in 0.5.0-beta14 the [Unreleased] section — 47 KB of entries — had no
# "Upgrading — read this first" at all, while the development guide claimed the publish script refused exactly
# that. It did not. Now it does: the section must open with that heading, the way every release since beta10 has.

# The heading every release section must open with (its first "### " heading). Part of the CHANGELOG contract.
RELEASE_NOTES_UPGRADING_HEADING="### Upgrading — read this first"

# Above this many bytes the body risks a database column limit on the Gitea side (a TEXT column is 64 KiB on
# MySQL/MariaDB). The create-release call would then fail before any asset is uploaded — safe, but unpublished.
RELEASE_NOTES_WARN_BYTES=60000

# release_notes_extract <changelog> <version> — print the body of the "## [<version>]" section (heading line
# excluded, surrounding blank lines trimmed); print nothing when there is no such section. The section ends at the
# next "## [" heading or at the end of the file.
release_notes_extract() {
    python3 - "$1" "$2" <<'EXTRACT'
import re, sys
text = open(sys.argv[1], encoding="utf-8").read()
version = sys.argv[2]
m = re.search(r"^## \[" + re.escape(version) + r"\][^\n]*\n(.*?)(?=^## \[|\Z)", text, re.S | re.M)
print(m.group(1).strip() if m else "")
EXTRACT
}

# release_notes_check <notes> <version> — validate an extracted body. Returns 0 when it may be published; otherwise
# prints the reason on stderr and returns 1 (no section) or 2 (no upgrade part, or not first). A body over
# RELEASE_NOTES_WARN_BYTES only warns: whether it fails depends on the server's database, which is not known here.
release_notes_check() {
    local notes="$1" version="$2" first bytes
    if [ -z "$notes" ]; then
        echo "refusing to publish: CHANGELOG.md has no '## [${version}]' section (or it is empty)." >&2
        echo "  The release body IS the upgrade notes — write the section first (flip [Unreleased] to" >&2
        echo "  [${version}]), then re-run. beta10 shipped with no notes because nothing enforced this." >&2
        return 1
    fi
    first="$(printf '%s\n' "$notes" | grep -m1 '^### ' || true)"
    if [ "$first" != "$RELEASE_NOTES_UPGRADING_HEADING" ]; then
        echo "refusing to publish: the '## [${version}]' section does not open with" >&2
        echo "  '${RELEASE_NOTES_UPGRADING_HEADING}' (its first '### ' heading is: '${first:-none}')." >&2
        echo "  Write the operator's steps there — what to run, what to expect, how to roll back — even if the" >&2
        echo "  answer is 'a kernel-only upgrade, nothing else'. It is the part an operator acts on." >&2
        return 2
    fi
    bytes="$(printf '%s' "$notes" | wc -c)"
    if [ "$bytes" -gt "$RELEASE_NOTES_WARN_BYTES" ]; then
        echo "WARNING: the release notes are ${bytes} bytes; a Gitea on MySQL/MariaDB stores at most 65535." >&2
        echo "  If the create-release call fails, shorten the CHANGELOG section and re-run." >&2
    fi
    return 0
}
