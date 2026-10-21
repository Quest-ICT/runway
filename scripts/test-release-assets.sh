#!/usr/bin/env bash
# test-release-assets.sh — tests for the release's file set: make-spi-assets.sh (the plugin author's SPI jars and
# consumer poms), make-release-aliases.sh (the aliases and SHA256SUMS over everything) and publish-release.sh's
# refusals and --dry-run. Runs offline, without credentials, in a scratch directory:
# `bash scripts/test-release-assets.sh [scratch-parent]`. The dist module runs it in its test phase next to
# test-release-notes.sh (skipped with -DskipTests). Exit status 0 = all passed.
#
# publish-release.sh runs from a COPY of the scripts in a fake reactor tree (it finds runway-dist/target and
# CHANGELOG.md beside its own directory), with `git` and `curl` replaced on PATH by stubs that log and fail, so
# no case can reach the real tag, credential store or Gitea — and the log proves which cases tried.
set -euo pipefail

HERE="$(dirname "$(readlink -f "$0")")"
# Release tooling is maintainer-only: a checkout without scripts/publish-release.sh (the public source tree) has
# nothing for this test to check, so it passes with a note instead of failing the build.
if [ ! -f "$HERE/publish-release.sh" ]; then
    echo "release-assets: skipped (scripts/publish-release.sh is not in this checkout)"
    exit 0
fi
PARENT="${1:-${TMPDIR:-/tmp}}"
mkdir -p "$PARENT"
ROOT="$(mktemp -d "$PARENT/release-assets-test.XXXXXX")"
trap 'rm -rf "$ROOT"' EXIT

FAILS=0
PASSES=0
fail() { printf 'FAIL: %s\n' "$*" >&2; FAILS=$((FAILS + 1)); }
pass() { PASSES=$((PASSES + 1)); }
check() { if eval "$2"; then pass; else fail "$1"; fi; }

V=1.2.0-rc1
T="$ROOT/tree"
DIST="$T/runway-dist/target"
mkdir -p "$T/scripts" "$DIST" "$T/runway-spi/target" "$T/runway-spi-testkit/target" "$ROOT/bin"
for s in publish-release.sh release-notes.sh make-release-aliases.sh make-spi-assets.sh; do
    cp "$HERE/$s" "$T/scripts/$s"
done
# The REAL module poms: the consumer pom is derived from them, so the test follows them as they change.
cp "$HERE/../runway-spi/pom.xml" "$T/runway-spi/pom.xml"
cp "$HERE/../runway-spi-testkit/pom.xml" "$T/runway-spi-testkit/pom.xml"
for m in runway-spi runway-spi-testkit; do
    printf 'jar %s\n' "$m" > "$T/$m/target/$m-$V.jar"
    printf 'sources %s\n' "$m" > "$T/$m/target/$m-$V-sources.jar"
done
cat > "$T/CHANGELOG.md" <<EOF
# Changelog

## [Unreleased]

## [$V] — 2026-10-18

### Upgrading — read this first

Nothing to do.

## [1.1.0] — 2026-10-01

### Added

- no upgrade part
EOF

# Stubs: any git or curl call is logged and fails (a publish that gets past the local checks stops at the tag).
for tool in git curl; do
    printf '#!/usr/bin/env bash\necho "%s $*" >> "%s"\nexit 1\n' "$tool" "$ROOT/net.log" > "$ROOT/bin/$tool"
    chmod +x "$ROOT/bin/$tool"
done
: > "$ROOT/net.log"
pub() { local r=0; PATH="$ROOT/bin:$PATH" bash "$T/scripts/publish-release.sh" "$@" > "$ROOT/pub.out" 2> "$ROOT/pub.err" || r=$?; echo "$r"; }
spi_files() { local m; for m in runway-spi runway-spi-testkit; do printf '%s\n' "$m-$V.jar" "$m-$V-sources.jar" "$m-$V.pom"; done; }

# --- 1. make-spi-assets.sh ---------------------------------------------------------------------------------------
r=0; bash "$T/scripts/make-spi-assets.sh" "$T" "$DIST" "$V" > "$ROOT/spi.out" 2>&1 || r=$?
check "1: stages the six files" '[ "$r" = 0 ] && for f in $(spi_files); do [ -f "$DIST/$f" ] || exit 1; done'
check "1: jars are copied byte-for-byte" 'cmp -s "$DIST/runway-spi-$V.jar" "$T/runway-spi/target/runway-spi-$V.jar" && cmp -s "$DIST/runway-spi-testkit-$V-sources.jar" "$T/runway-spi-testkit/target/runway-spi-testkit-$V-sources.jar"'
SPOM="$DIST/runway-spi-$V.pom"; KPOM="$DIST/runway-spi-testkit-$V.pom"
check "1: the consumer pom is well-formed XML" 'python3 -c "import sys,xml.etree.ElementTree as E; E.parse(sys.argv[1]); E.parse(sys.argv[2])" "$SPOM" "$KPOM"'
check "1: it has no parent (the reactor parent is not published)" '! grep -q "<parent>" "$SPOM" "$KPOM"'
check "1: it states the coordinates outright" 'grep -q "<groupId>mx.quest</groupId>" "$SPOM" && grep -q "<artifactId>runway-spi</artifactId>" "$SPOM" && grep -q "<version>$V</version>" "$SPOM"'
check "1: test-scope dependencies are dropped (runway-spi has none left)" '! grep -q "<dependencies>\|junit" "$SPOM"'
check "1: the testkit keeps runway-spi at the SAME version" 'python3 - "$KPOM" "$V" <<PY
import sys, xml.etree.ElementTree as E
ns = {"m": "http://maven.apache.org/POM/4.0.0"}
deps = E.parse(sys.argv[1]).getroot().findall("m:dependencies/m:dependency", ns)
got = [(d.find("m:artifactId", ns).text, d.find("m:version", ns).text, d.find("m:scope", ns)) for d in deps]
sys.exit(0 if got == [("runway-spi", sys.argv[2], None)] else 1)
PY'
check "1: no unresolved property is left" '! grep -q "\${" "$SPOM" "$KPOM"'
cp "$SPOM" "$ROOT/spom.1"
bash "$T/scripts/make-spi-assets.sh" "$T" "$DIST" "$V" > /dev/null 2>&1
check "1: a second run writes the same bytes" 'cmp -s "$SPOM" "$ROOT/spom.1"'

# Refusals, each in its own tree copy so the main tree stays intact.
refuse_tree() { rm -rf "$ROOT/r"; cp -r "$T" "$ROOT/r"; rm -f "$ROOT/r/runway-dist/target/"*; }
refuse_tree; rm "$ROOT/r/runway-spi-testkit/target/runway-spi-testkit-$V-sources.jar"
r=0; bash "$ROOT/r/scripts/make-spi-assets.sh" "$ROOT/r" "$ROOT/r/runway-dist/target" "$V" > /dev/null 2> "$ROOT/err" || r=$?
check "1: a missing sources jar is refused" '[ "$r" -ne 0 ] && grep -q "missing .*runway-spi-testkit-$V-sources.jar" "$ROOT/err"'
refuse_tree; sed -i 's|<version>${project.version}</version>|<version>${some.other}</version>|' "$ROOT/r/runway-spi-testkit/pom.xml"
r=0; bash "$ROOT/r/scripts/make-spi-assets.sh" "$ROOT/r" "$ROOT/r/runway-dist/target" "$V" > /dev/null 2> "$ROOT/err" || r=$?
check "1: an unresolvable dependency version is refused, not guessed" '[ "$r" -ne 0 ] && grep -q "cannot resolve version" "$ROOT/err"'
check "1: ...and leaves no pom (nor a partial one) behind" '[ ! -e "$ROOT/r/runway-dist/target/runway-spi-testkit-$V.pom" ] && [ ! -e "$ROOT/r/runway-dist/target/runway-spi-testkit-$V.pom.part" ]'
refuse_tree; python3 - "$ROOT/r/runway-spi/pom.xml" <<'PY'
import sys
p = sys.argv[1]; s = open(p).read()
s = s.replace("<dependencies>", "<dependencies><dependency><groupId>x</groupId><artifactId>managed</artifactId></dependency>", 1)
open(p, "w").write(s)
PY
r=0; bash "$ROOT/r/scripts/make-spi-assets.sh" "$ROOT/r" "$ROOT/r/runway-dist/target" "$V" > /dev/null 2> "$ROOT/err" || r=$?
check "1: a non-test dependency without an explicit version is refused" '[ "$r" -ne 0 ] && grep -q "x:managed has no explicit version" "$ROOT/err"'

# --- 2. make-release-aliases.sh ----------------------------------------------------------------------------------
printf 'zip\n' > "$DIST/runway-$V-dist.zip"
printf 'bin\n' > "$DIST/runway-$V.bin"
r=0; bash "$T/scripts/make-release-aliases.sh" "$DIST" "$V" > /dev/null 2>&1 || r=$?
check "2: writes SHA256SUMS" '[ "$r" = 0 ] && [ -f "$DIST/SHA256SUMS" ]'
check "2: SHA256SUMS lists the installer pair, the aliases, then the six SPI files, in that order" \
    '[ "$(awk "{print \$2}" "$DIST/SHA256SUMS" | tr "\n" " ")" = "runway-$V-dist.zip runway-$V.bin runway-latest-dist.zip runway-latest.bin $(spi_files | tr "\n" " ")" ]'
check "2: every listed hash verifies" '(cd "$DIST" && sha256sum --quiet -c SHA256SUMS)'
check "2: the SPI files get no latest alias" '! ls "$DIST" | grep -q "runway-spi.*latest\|latest.*spi"'
refuse_tree; cp "$DIST/runway-$V-dist.zip" "$DIST/runway-$V.bin" "$ROOT/r/runway-dist/target/"
r=0; bash "$ROOT/r/scripts/make-release-aliases.sh" "$ROOT/r/runway-dist/target" "$V" > /dev/null 2> "$ROOT/err" || r=$?
check "2: refuses when the SPI files were not staged" '[ "$r" -ne 0 ] && grep -q "missing runway-spi-$V.jar" "$ROOT/err" && [ ! -e "$ROOT/r/runway-dist/target/SHA256SUMS" ]'

# --- 3. publish-release.sh --dry-run -----------------------------------------------------------------------------
: > "$ROOT/net.log"
r="$(pub --dry-run "$V")"
check "3: a dry run of a complete set succeeds" '[ "$r" = 0 ]'
check "3: it says nothing is published" 'grep -q "DRY RUN — nothing is created, changed or uploaded" "$ROOT/pub.out"'
check "3: it names the release, non-prerelease" 'grep -q "tag=v$V  name=\"Runway $V\"  prerelease=false" "$ROOT/pub.out"'
check "3: it lists all 11 assets, the SPI files included" 'grep -q "would upload 11 assets" "$ROOT/pub.out" && for f in $(spi_files) SHA256SUMS runway-latest.bin; do grep -q "^  $f " "$ROOT/pub.out" || exit 1; done'
check "3: ...without touching git, curl or the network" '[ ! -s "$ROOT/net.log" ]'
r="$(pub -n)"
check "3: -n works and the version is detected from SHA256SUMS (the SPI lines do not confuse it)" '[ "$r" = 0 ] && grep -q "tag=v$V " "$ROOT/pub.out"'
r="$(pub --dry-run --bogus)"
check "3: an unknown option is refused (status 2)" '[ "$r" = 2 ] && grep -q "unknown option: --bogus" "$ROOT/pub.err"'
r="$(pub --dry-run "$V" 9.9.9)"
check "3: two versions are refused (status 2)" '[ "$r" = 2 ]'

# --- 4. publish-release.sh refusals (dry run or not, all before the tag, the credential or the network) ----------
mv "$DIST/runway-spi-testkit-$V.pom" "$ROOT/held.pom"
r="$(pub --dry-run "$V")"
check "4: a missing SPI file is refused" '[ "$r" = 1 ] && grep -q "missing artifact: .*runway-spi-testkit-$V.pom" "$ROOT/pub.err"'
mv "$ROOT/held.pom" "$DIST/runway-spi-testkit-$V.pom"
cp "$DIST/runway-spi-$V.jar" "$ROOT/held.jar"; printf 'rebuilt after the manifest\n' > "$DIST/runway-spi-$V.jar"
r="$(pub --dry-run "$V")"
check "4: a file whose hash no longer matches SHA256SUMS is refused" '[ "$r" = 1 ] && grep -q "SHA256SUMS does not match" "$ROOT/pub.err" && grep -q "runway-spi-$V.jar" "$ROOT/pub.err"'
cp "$ROOT/held.jar" "$DIST/runway-spi-$V.jar"
cp "$DIST/SHA256SUMS" "$ROOT/held.sums"; grep -v "runway-spi-$V-sources.jar" "$ROOT/held.sums" > "$DIST/SHA256SUMS"
r="$(pub --dry-run "$V")"
check "4: an asset SHA256SUMS does not list is refused" '[ "$r" = 1 ] && grep -q "SHA256SUMS does not list runway-spi-$V-sources.jar" "$ROOT/pub.err"'
cp "$ROOT/held.sums" "$DIST/SHA256SUMS"
r="$(pub --dry-run 1.1.0)"
check "4: a version whose CHANGELOG section lacks the upgrade part is refused" '[ "$r" -ne 0 ] && grep -q "does not open with" "$ROOT/pub.err"'
r="$(pub --dry-run 1.3.0-SNAPSHOT)"
check "4: a SNAPSHOT is refused" '[ "$r" = 1 ] && grep -q "refusing to publish a SNAPSHOT" "$ROOT/pub.err"'
check "4: no refusal touched git or curl" '[ ! -s "$ROOT/net.log" ]'

# --- 5. Without --dry-run, the complete set goes on to the tag check (stubbed git fails) and stops there ----------
r="$(pub "$V")"
check "5: the real path stops at the tag (stub git: not on origin)" '[ "$r" = 1 ] && grep -q "tag v$V is not on origin" "$ROOT/pub.err"'
check "5: ...after asking git for the tag, never curl" 'grep -q "^git ls-remote" "$ROOT/net.log" && ! grep -q "^curl" "$ROOT/net.log"'
check "5: ...and it did not print a dry-run plan" '! grep -q "DRY RUN" "$ROOT/pub.out"'

printf 'release-assets: %d passed, %d failed\n' "$PASSES" "$FAILS"
[ "$FAILS" -eq 0 ]
