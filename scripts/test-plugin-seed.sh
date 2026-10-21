#!/usr/bin/env bash
# test-plugin-seed.sh — tests for plugin-seed.sh, the bundled-plugin seed/refresh logic of install.sh.
# Runs without root, in a scratch directory: `bash scripts/test-plugin-seed.sh [scratch-parent]`. The dist
# module runs it in its test phase (skipped with -DskipTests). Exit status 0 = all passed.
#
# Each case asserts on what the operator would actually see — the backup files on disk and the warning text —
# because the defect this guards against was a true-looking warning about edits nobody made.
set -euo pipefail

HERE="$(dirname "$(readlink -f "$0")")"
# shellcheck source=plugin-seed.sh
. "$HERE/plugin-seed.sh"

PARENT="${1:-${TMPDIR:-/tmp}}"
mkdir -p "$PARENT"
ROOT="$(readlink -f "$(mktemp -d "$PARENT/plugin-seed-test.XXXXXX")")"   # absolute: install.sh prints resolved bundle paths
trap 'rm -rf "$ROOT"' EXIT

DRY_RUN="no"
OUT="$ROOT/out.log"
log()  { printf '[install] %s\n' "$*" >> "$OUT"; }
warn() { printf '[install] WARNING: %s\n' "$*" >> "$OUT"; }
run()  { if [ "$DRY_RUN" = "yes" ]; then printf '  + %s\n' "$*" >> "$OUT"; else "$@"; fi; }

FAILS=0
PASSES=0
fail() { printf 'FAIL: %s\n' "$*" >&2; FAILS=$((FAILS + 1)); }
pass() { PASSES=$((PASSES + 1)); }
check() { if eval "$2"; then pass; else fail "$1"; sed 's/^/    | /' "$OUT" >&2; fi; }

# bundle <dir> <version-tag> — a bundle whose protection plugin ships one template and one i18n file whose
# content depends on the tag (so two tags = "we changed the file between releases").
bundle() {
    local b="$1" tag="$2" p="$1/seed/plugins/protection"
    rm -rf "$b"; mkdir -p "$p/templates" "$p/i18n"
    printf 'id: protection\nversion: %s\n' "$tag" > "$p/plugin.yml"
    printf 'jar-%s' "$tag" > "$p/runway-plugin-protection.jar"
    printf '<p>dos %s</p>\n' "$tag" > "$p/templates/app-dos.html"
    printf 'protection.title=Protection %s\n' "$tag" > "$p/i18n/messages.properties"
}
backups() { find "$1/external/plugins/protection" -name '*.operator-backup-*' | wc -l; }
T="templates/app-dos.html"
I="i18n/messages.properties"

# --- 1. A file nobody edited, which we changed upstream → refreshed, no warning, no backup -------------------
D="$ROOT/c1"; : > "$OUT"
bundle "$ROOT/b1" v1; seed_bundled_plugins "$ROOT/b1" "$D"
check "1: first install writes a manifest" '[ -f "$D/external/plugin-manifests/protection.sha256" ]'
check "1: manifest header" 'head -1 "$D/external/plugin-manifests/protection.sha256" | grep -q "^# runway shipped-manifest v1 plugin=protection$"'
bundle "$ROOT/b2" v2; : > "$OUT"; seed_bundled_plugins "$ROOT/b2" "$D"
check "1: no warning for an unedited stale file" '! grep -q WARNING "$OUT"'
check "1: no backup for an unedited stale file" '[ "$(backups "$D")" -eq 0 ]'
check "1: template refreshed" 'grep -q "dos v2" "$D/external/plugins/protection/$T"'
check "1: i18n refreshed" 'grep -q "Protection v2" "$D/external/plugins/protection/$I"'
check "1: the log says why there was no backup" 'grep -q "unchanged since it was installed" "$OUT"'
check "1: manifest now records v2" '[ "$(_ps_manifest_hash "$D/external/plugin-manifests/protection.sha256" "$T")" = "$(_ps_sha "$ROOT/b2/seed/plugins/protection/$T")" ]'

# --- 2. An operator-edited file → backed up and warned ------------------------------------------------------
D="$ROOT/c2"; : > "$OUT"
bundle "$ROOT/b1" v1; seed_bundled_plugins "$ROOT/b1" "$D"
printf '<p>my own dos page</p>\n' > "$D/external/plugins/protection/$T"
bundle "$ROOT/b2" v2; : > "$OUT"; seed_bundled_plugins "$ROOT/b2" "$D"
check "2: exactly one backup (the edited file only)" '[ "$(backups "$D")" -eq 1 ]'
check "2: the backup holds the operator edit" 'grep -q "my own dos page" "$D"/external/plugins/protection/templates/app-dos.html.operator-backup-*'
check "2: warning names the edit" 'grep -q "WARNING: plugin .protection.: $T was edited after it was installed" "$OUT"'
check "2: the unedited i18n file is not warned about" '! grep -q "WARNING.*$I" "$OUT"'
check "2: edited file still refreshed to v2" 'grep -q "dos v2" "$D/external/plugins/protection/$T"'

# --- 3. No manifest (an install from before this change) → warn for every differing file, write a manifest --
D="$ROOT/c3"; : > "$OUT"
bundle "$ROOT/b1" v1; seed_bundled_plugins "$ROOT/b1" "$D"
rm -f "$D/external/plugin-manifests/protection.sha256"
bundle "$ROOT/b2" v2; : > "$OUT"; seed_bundled_plugins "$ROOT/b2" "$D"
check "3: both differing files backed up" '[ "$(backups "$D")" -eq 2 ]'
check "3: warning admits it cannot tell" '[ "$(grep -c "has no record of what was installed before" "$OUT")" -eq 2 ]'
check "3: a manifest is written for next time" '[ -f "$D/external/plugin-manifests/protection.sha256" ]'
bundle "$ROOT/b3" v3; : > "$OUT"; seed_bundled_plugins "$ROOT/b3" "$D"
check "3: the NEXT upgrade is clean" '! grep -q WARNING "$OUT" && [ "$(backups "$D")" -eq 2 ]'

# --- 4. The manifest survives the plugin folder being replaced ---------------------------------------------
D="$ROOT/c4"; : > "$OUT"
bundle "$ROOT/b1" v1; seed_bundled_plugins "$ROOT/b1" "$D"
check "4: the manifest lives outside the plugin folder" '[ ! -e "$D/external/plugins/protection/plugin-manifests" ] && ! find "$D/external/plugins" -name "*.sha256" | grep -q .'
rm -rf "$D/external/plugins/protection"
cp -a "$ROOT/b1/seed/plugins/protection" "$D/external/plugins/protection"
check "4: still there after the folder is replaced" '[ -n "$(_ps_manifest_hash "$D/external/plugin-manifests/protection.sha256" "$T")" ]'

# --- 5. Identical files are silent; a file new in this release is installed; dry run writes nothing ---------
D="$ROOT/c5"; : > "$OUT"
bundle "$ROOT/b1" v1; seed_bundled_plugins "$ROOT/b1" "$D"
: > "$OUT"; seed_bundled_plugins "$ROOT/b1" "$D"
check "5: re-running the same release says nothing about files" '! grep -q "app-dos\|messages.properties" "$OUT"'
bundle "$ROOT/b2" v2; printf 'x=1\n' > "$ROOT/b2/seed/plugins/protection/i18n/messages_fr.properties"
cp -a "$D" "$ROOT/c5-before"
DRY_RUN="yes"; : > "$OUT"; seed_bundled_plugins "$ROOT/b2" "$D"; DRY_RUN="no"
check "5: dry run changes nothing on disk" 'diff -r "$ROOT/c5-before" "$D" > /dev/null'
: > "$OUT"; seed_bundled_plugins "$ROOT/b2" "$D"
check "5: a new file is installed without a warning" '[ -f "$D/external/plugins/protection/i18n/messages_fr.properties" ] && ! grep -q WARNING "$OUT"'
check "5: a file the operator never had is in the new manifest" '[ -n "$(_ps_manifest_hash "$D/external/plugin-manifests/protection.sha256" "i18n/messages_fr.properties")" ]'

# --- 6. Every upgrade refreshes an existing essential plugin — no flag ---------------------------------------
# An upgrade that left the old protection in place would take the platform down: on SPI 1.0.0 an old
# descriptor (no requiresSpi) is blocked, and a blocked essential plugin makes every tenant page answer 503. So the
# old copy here is exactly that shape — no requiresSpi, and hand-disabled — and the upgrade must replace it.
D="$ROOT/c6"; P="$D/external/plugins/protection"; : > "$OUT"
bundle "$ROOT/b1" v1; seed_bundled_plugins "$ROOT/b1" "$D"
printf 'enabled: false\n' >> "$P/plugin.yml"
mkdir -p "$D/external/plugin-settings"; printf 'rate-limit:\n  capacity: 7\n' > "$D/external/plugin-settings/protection.yml"
cp "$D/external/plugin-settings/protection.yml" "$ROOT/c6-settings"
bundle "$ROOT/b2" v2; printf 'requiresSpi: ">=1.0.0"\n' >> "$ROOT/b2/seed/plugins/protection/plugin.yml"
: > "$OUT"; seed_bundled_plugins "$ROOT/b2" "$D"
check "6: the new descriptor (with its requiresSpi floor) is installed" 'grep -q "version: v2" "$P/plugin.yml" && grep -q "^requiresSpi: \">=1.0.0\"$" "$P/plugin.yml"'
check "6: the new jar is installed" '[ "$(cat "$P/runway-plugin-protection.jar")" = "jar-v2" ]'
check "6: a hand-set enabled: false does not survive (a disabled essential plugin fails closed)" '! grep -q "enabled: false" "$P/plugin.yml"'
check "6: ... but is kept, with the old descriptor, in plugin.yml.bak" 'grep -q "version: v1" "$P/plugin.yml.bak" && grep -q "enabled: false" "$P/plugin.yml.bak"'
check "6: templates and i18n are refreshed too" 'grep -q "dos v2" "$P/$T" && grep -q "Protection v2" "$P/$I"'
check "6: the operator is told the plugin is refreshed" 'grep -q "refreshing bundled plugin .protection. from this release" "$OUT"'
check "6: the operator is told where the previous descriptor and jar are" 'grep -q "plugin.yml replaced (the previous one kept as plugin.yml.bak" "$OUT" && grep -q "runway-plugin-protection.jar replaced (the previous one kept as runway-plugin-protection.jar.bak" "$OUT"'
check "6: no warning (nothing was edited)" '! grep -q WARNING "$OUT"'
check "6: no 'nothing changed' line on a real upgrade" '! grep -q "nothing changed" "$OUT"'
check "6: settings (outside the folder) are untouched" 'cmp -s "$ROOT/c6-settings" "$D/external/plugin-settings/protection.yml"'
check "6: the old 'leaving it as-is' path is gone" '! grep -q "leaving it as-is" "$OUT"'

# --- 7. The previous jar AND descriptor are kept for a rollback, and a re-run never overwrites them -----------
# A rollback is a symlink flip back to the older kernel, which then meets the refreshed plugin folder. The new jar
# may link SPI types that kernel lacks (an older kernel then dies on NoClassDefFoundError), and the new
# descriptor carries a requiresSpi floor that refuses the old jar, so the rollback restores BOTH .bak files.
D="$ROOT/c7"; P="$D/external/plugins/protection"; : > "$OUT"
bundle "$ROOT/b1" v1; seed_bundled_plugins "$ROOT/b1" "$D"
check "7: a fresh install leaves no .bak" '! find "$P" -name "*.bak" | grep -q .'
bundle "$ROOT/b2" v2; : > "$OUT"; seed_bundled_plugins "$ROOT/b2" "$D"
check "7: the previous descriptor is kept as plugin.yml.bak" 'grep -q "version: v1" "$P/plugin.yml.bak"'
check "7: the previous jar is kept as .jar.bak" '[ "$(cat "$P/runway-plugin-protection.jar.bak")" = "jar-v1" ]'
check "7: the new descriptor and jar are installed" 'grep -q "version: v2" "$P/plugin.yml" && [ "$(cat "$P/runway-plugin-protection.jar")" = "jar-v2" ]'
: > "$OUT"; seed_bundled_plugins "$ROOT/b2" "$D"
check "7: re-running the same release keeps the PREVIOUS release in plugin.yml.bak" 'grep -q "version: v1" "$P/plugin.yml.bak"'
check "7: re-running the same release keeps the PREVIOUS release in .jar.bak" '[ "$(cat "$P/runway-plugin-protection.jar.bak")" = "jar-v1" ]'
cp -f "$P/plugin.yml.bak" "$P/plugin.yml"; cp -f "$P/runway-plugin-protection.jar.bak" "$P/runway-plugin-protection.jar"
check "7: restoring the two .bak files gives back the previous release" 'grep -q "version: v1" "$P/plugin.yml" && [ "$(cat "$P/runway-plugin-protection.jar")" = "jar-v1" ]'
bundle "$ROOT/b3" v3; cp -a "$D" "$ROOT/c7-before"
DRY_RUN="yes"; : > "$OUT"; seed_bundled_plugins "$ROOT/b3" "$D"; DRY_RUN="no"
check "7: dry run announces the descriptor backup and writes nothing" 'grep -qF "cp -f $P/plugin.yml $P/plugin.yml.bak" "$OUT" && diff -r "$ROOT/c7-before" "$D" > /dev/null'

# --- 8. A re-run with nothing new writes no .bak and no warning, and says so --------------------------------
# Refreshing on every run is only safe if a run that changes nothing is silent: a .bak written by an idempotent
# re-run would overwrite the rollback copy, and a warning would cry wolf.
D="$ROOT/c8"; P="$D/external/plugins/protection"; : > "$OUT"
bundle "$ROOT/b1" v1; seed_bundled_plugins "$ROOT/b1" "$D"
: > "$OUT"; seed_bundled_plugins "$ROOT/b1" "$D"
check "8: a re-run of the install's own release writes no .bak" '! find "$P" -name "*.bak" | grep -q .'
check "8: ... and no operator backup" '[ "$(backups "$D")" -eq 0 ]'
check "8: ... and no warning" '! grep -q WARNING "$OUT"'
check "8: ... and replaces nothing" '! grep -q "replaced" "$OUT"'
check "8: ... and says nothing changed" 'grep -q "plugin .protection. was already this release.s — nothing changed" "$OUT"'
bundle "$ROOT/b2" v2; seed_bundled_plugins "$ROOT/b2" "$D"
( cd "$P" && find . -name "*.bak" -exec sha256sum {} + | sort ) > "$ROOT/c8-bak-before"
: > "$OUT"; seed_bundled_plugins "$ROOT/b2" "$D"
( cd "$P" && find . -name "*.bak" -exec sha256sum {} + | sort ) > "$ROOT/c8-bak-after"
check "8: after an upgrade, a re-run keeps the same .bak files, byte for byte" '[ -s "$ROOT/c8-bak-before" ] && cmp -s "$ROOT/c8-bak-before" "$ROOT/c8-bak-after"'
check "8: after an upgrade, a re-run warns about nothing and says nothing changed" '! grep -q WARNING "$OUT" && grep -q "nothing changed" "$OUT"'

# --- 9. install.sh: the refresh needs no flag, and --update-essential-plugins is still accepted -------------
# Runs the real installer in --dry-run (no root needed) from a bundle laid out like the dist zip, over a data dir
# that already holds an older protection: the upgrade case. Dry run writes nothing, so the output is the evidence.
B="$ROOT/b9"; PREFIX9="$ROOT/opt9"; D="$PREFIX9/runway-data"; P="$D/external/plugins/protection"
bundle "$ROOT/b1" v1; seed_bundled_plugins "$ROOT/b1" "$D"
bundle "$B" v2; mkdir -p "$B/bin" "$B/scripts"
printf 'jar' > "$B/bin/runway-app-9.9.9-exec.jar"
cp "$HERE/install.sh" "$HERE/plugin-seed.sh" "$B/scripts/"
cp -a "$D" "$ROOT/c9-before"
INSTALL_OUT="$ROOT/install.log"
install_dry() { bash "$B/scripts/install.sh" --dry-run -y --no-tls --no-firewall --prefix "$PREFIX9" "$@" > "$INSTALL_OUT" 2>&1; }
if install_dry --update-essential-plugins; then pass; else fail "9: install.sh --update-essential-plugins exits 0"; sed 's/^/    | /' "$INSTALL_OUT" >&2; fi
check "9: the flag is accepted and says it has nothing to do" 'grep -q "update-essential-plugins: nothing to do" "$INSTALL_OUT"'
check "9: with the flag, the upgrade refreshes protection" 'grep -qF "cp -f $P/plugin.yml $P/plugin.yml.bak" "$INSTALL_OUT" && grep -q "refreshing bundled plugin .protection." "$INSTALL_OUT"'
if install_dry; then pass; else fail "9: install.sh without the flag exits 0"; sed 's/^/    | /' "$INSTALL_OUT" >&2; fi
check "9: WITHOUT the flag, the upgrade refreshes protection just the same" 'grep -qF "cp -f $B/seed/plugins/protection/plugin.yml $P/plugin.yml" "$INSTALL_OUT" && grep -qF "cp -f $P/runway-plugin-protection.jar $P/runway-plugin-protection.jar.bak" "$INSTALL_OUT"'
check "9: without the flag there is no flag notice" '! grep -q "update-essential-plugins" "$INSTALL_OUT"'
check "9: the plan names the plugin refresh" 'grep -q "plugins      : bundled (essential) plugins seeded if missing, else refreshed" "$INSTALL_OUT"'
check "9: dry run left the data dir as it was" 'diff -r "$ROOT/c9-before" "$D" > /dev/null'
bash "$B/scripts/install.sh" --help > "$INSTALL_OUT" 2>&1 || true
check "9: --help no longer offers the flag as an opt-in" 'grep -q "Accepted and ignored" "$INSTALL_OUT" && grep -q "ALWAYS refreshed" "$INSTALL_OUT" && ! grep -q "plugins are never touched on upgrade" "$INSTALL_OUT"'

# --- 10. install.sh keeps the bundle's seed/ in the release, the repair copy the kernel's message points at ------
# A refused essential plugin's fix is the running release's copy; were that copy only inside the .bin, the operator
# would have to find and extract it. The installer therefore lays seed/ into <prefix>/runway-<version>/, replaced
# whole (no stale file, no seed/seed nesting on a re-run), before the release is chowned and relabeled.
# Reuses case 9's bundle and prefix; dry run, so the printed plan is the evidence.
DEST9="$PREFIX9/runway-9.9.9"
if install_dry; then pass; else fail "10: install.sh exits 0"; sed 's/^/    | /' "$INSTALL_OUT" >&2; fi
check "10: the plan names the repair copy under the current link" 'grep -qF "repair copy  : the bundled plugins also kept in the release, $PREFIX9/runway/seed/plugins/" "$INSTALL_OUT"'
check "10: seed/ is copied into the release" 'grep -qxF "  + cp -R $B/seed $DEST9/seed" "$INSTALL_OUT"'
check "10: ... after the old one is removed (replaced whole, never nested as seed/seed)" '[ "$(grep -nxF "  + rm -rf $DEST9/seed" "$INSTALL_OUT" | cut -d: -f1)" -lt "$(grep -nxF "  + cp -R $B/seed $DEST9/seed" "$INSTALL_OUT" | cut -d: -f1)" ]'
check "10: ... before the release is chowned (same ownership as bin/ and scripts/)" '[ "$(grep -nxF "  + cp -R $B/seed $DEST9/seed" "$INSTALL_OUT" | cut -d: -f1)" -lt "$(grep -nxF "  + chown -R runway:runway $DEST9" "$INSTALL_OUT" | cut -d: -f1)" ]'
if command -v restorecon > /dev/null 2>&1; then
    check "10: ... and before the SELinux relabel of the release" '[ "$(grep -nxF "  + cp -R $B/seed $DEST9/seed" "$INSTALL_OUT" | cut -d: -f1)" -lt "$(grep -nF "  + restorecon -RF $DEST9 " "$INSTALL_OUT" | cut -d: -f1)" ]'
fi
check "10: the plugin is still seeded into the data dir as well" 'grep -q "refreshing bundled plugin .protection." "$INSTALL_OUT"'
check "10: dry run left the prefix without a release dir" '[ ! -e "$DEST9" ]'
# The installer re-run from an installed release: its bundle IS the release dir, so seed/ must not be removed.
mkdir -p "$DEST9"; cp -a "$B/bin" "$B/scripts" "$B/seed" "$DEST9/"
if bash "$DEST9/scripts/install.sh" --dry-run -y --no-tls --no-firewall --prefix "$PREFIX9" > "$INSTALL_OUT" 2>&1; then pass
else fail "10: install.sh run from the release exits 0"; sed 's/^/    | /' "$INSTALL_OUT" >&2; fi
check "10: run from the release itself, seed/ is neither removed nor copied onto itself" '! grep -q "rm -rf $DEST9/seed" "$INSTALL_OUT" && ! grep -q "cp -R .*/seed $DEST9/seed" "$INSTALL_OUT"'
rm -rf "$DEST9"
# A bundle without seed/ (a hand-made one): nothing to keep, and the plan does not promise a repair copy.
mv "$B/seed" "$ROOT/b9-seed"
if install_dry; then pass; else fail "10: install.sh without seed/ exits 0"; sed 's/^/    | /' "$INSTALL_OUT" >&2; fi
check "10: no seed/ in the bundle: no copy, no repair-copy line" '! grep -q "repair copy" "$INSTALL_OUT" && ! grep -q "$DEST9/seed" "$INSTALL_OUT"'
mv "$ROOT/b9-seed" "$B/seed"
bash "$B/scripts/install.sh" --help > "$INSTALL_OUT" 2>&1 || true
check "10: --help says where the repair copy is" 'grep -q "The bundle.s copy also stays in the release, <prefix>/runway/seed/plugins/<id>/" "$INSTALL_OUT"'

printf 'plugin-seed: %d passed, %d failed\n' "$PASSES" "$FAILS"
[ "$FAILS" -eq 0 ]
