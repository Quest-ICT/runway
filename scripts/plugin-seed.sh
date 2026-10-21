# plugin-seed.sh — seeding and refreshing the bundled (essential) plugins. Sourced by install.sh; also sourced
# on its own by test-plugin-seed.sh, which is why it defines functions and runs nothing at load time.
#
# The question this file exists to answer correctly: when an upgrade finds that a plugin's template or i18n
# file differs from the one being installed, WHO changed it? Comparing the installed copy with the incoming
# one cannot say — a difference means only "not the new version". An installer that guesses
# "the operator", backs the file up and says so is wrong every time the change was ours: hashing the
# files before two upgrades proved the "operator edits" were the previous shipped versions.
#
# So the installer now remembers what it laid down. A SHIPPED-MANIFEST per plugin records the SHA-256 of
# every template/i18n file as installed, and lives OUTSIDE the plugin folder (external/plugin-manifests/), so
# it survives the folder being replaced. A differing file then falls into one of three cases:
#
#   matches the manifest   → nobody touched it since we installed it; it is just stale → refresh, no backup
#   differs from manifest  → the operator edited it                    → back it up, warn, refresh
#   no manifest entry      → installed by a release that kept no record → back it up, warn, refresh
#
# The third case fails SAFE rather than quiet: guessing "unmodified" would silently destroy a real edit. It
# fires once — the refresh writes a manifest, so the next upgrade is exact.
#
# Manifest format (part of the on-disk contract): a header line, then sha256sum(1) lines.
#   # runway shipped-manifest v1 plugin=<id>
#   <64 hex>  templates/app-dos.html

PLUGIN_MANIFEST_HEADER_PREFIX="# runway shipped-manifest v1"

# _ps_sha <file> — the file's SHA-256, hex only.
_ps_sha() { sha256sum "$1" | cut -d' ' -f1; }

# _ps_manifest_hash <manifest> <relpath> — the recorded hash for relpath, or nothing.
_ps_manifest_hash() {
    [ -f "$1" ] || return 0
    awk -v p="$2" 'substr($0,1,1) != "#" && NF >= 2 && substr($0, 67) == p { print $1; exit }' "$1"
}

# _ps_write_manifest <plugin-src-dir> <manifest> <id> — record every shipped template/i18n file. Written to a
# temp file and renamed, so an interrupted install never leaves a half manifest (which would read as
# "no entry" → the safe warning, but still better avoided).
_ps_write_manifest() {
    local src="$1" manifest="$2" id="$3" sub f
    if [ "${DRY_RUN:-no}" = "yes" ]; then
        printf '  + write shipped-manifest %s\n' "$manifest"
        return 0
    fi
    mkdir -p "$(dirname "$manifest")"
    {
        printf '%s plugin=%s\n' "$PLUGIN_MANIFEST_HEADER_PREFIX" "$id"
        for sub in templates i18n; do
            [ -d "$src/$sub" ] || continue
            for f in "$src/$sub"/*; do
                [ -f "$f" ] || continue
                printf '%s  %s\n' "$(_ps_sha "$f")" "$sub/$(basename "$f")"
            done
        done
    } > "$manifest.tmp"
    mv -f "$manifest.tmp" "$manifest"
}

# seed_bundled_plugins <bundle-dir> <data-dir>
# Seeds every bundled plugin a fresh install lacks, and REFRESHES every one an upgrade finds — always; there is no
# opt-in any more. The bundled plugins are the essential ones (today: protection), and an essential plugin is
# lockstep with the kernel: it is the one plugin allowed to link kernel internals, and its descriptor names the
# kernel's SPI level. An old copy beside a new kernel is refused — the essential plugin blocked puts the platform in
# fail-closed mode, every tenant page 503 — or crashes on a missing class. An upgrade that left
# the old copy in place would, with SPI 1.0.0's mandatory floor, mean a 503 on every site, so the refresh is part
# of every upgrade. What makes
# "always" safe is what the refresh keeps:
#   - the previous jar and plugin.yml as <name>.bak (written only when the file changes) — the rollback pair;
#   - a template/i18n file the operator edited, as <name>.operator-backup-<stamp>, with a warning naming it;
#   - settings, which live outside the plugin folder (external/plugin-settings/) and are never touched.
# A hand-set `enabled: false` in plugin.yml is not kept: an essential plugin that does not load fails closed anyway,
# so the refresh can only turn a 503 back into a protected site. The kept plugin.yml.bak holds the edit.
# Uses install.sh's log/warn/run (and DRY_RUN) when present; test-plugin-seed.sh provides its own.
seed_bundled_plugins() {
    local bundle="$1" data="$2"
    local pdir pid pdst manifest f jn sub src fn dstf rel recorded current bak stamp changed
    [ -d "$bundle/seed/plugins" ] || return 0
    stamp="$(date -u +%Y%m%d-%H%M%SZ)"
    for pdir in "$bundle"/seed/plugins/*/; do
        [ -d "$pdir" ] || continue
        pdir="${pdir%/}"
        pid="$(basename "$pdir")"
        pdst="$data/external/plugins/$pid"
        manifest="$data/external/plugin-manifests/$pid.sha256"
        if [ ! -e "$pdst" ]; then
            log "seeding bundled plugin '$pid'"
            run mkdir -p "$data/external/plugins"
            run cp -a "$pdir" "$pdst"
            _ps_write_manifest "$pdir" "$manifest" "$pid"
        else
            log "refreshing bundled plugin '$pid' from this release (it moves with the kernel; files you edited are backed up)"
            changed=no
            # The previous jar AND descriptor are kept as <name>.bak: they are what a ROLLBACK needs. The refreshed
            # folder outlives the kernel it came with (external/ is version-independent), so flipping /opt/runway
            # back to an older release puts that kernel in front of the new jar — which may link SPI types the old
            # kernel lacks. Restoring both .bak files
            # is the rollback step. The descriptor matters as much as the jar: it carries the requiresSpi floor, so
            # the new descriptor beside the old jar would get the old jar refused.
            # Backed up only when the file actually changes, so re-running the same installer (it is idempotent)
            # never overwrites the previous release's copy with the current one.
            for f in "$pdir"/plugin.yml "$pdir"/*.jar; do
                [ -f "$f" ] || continue
                jn="$(basename "$f")"
                if [ -f "$pdst/$jn" ] && cmp -s "$f" "$pdst/$jn"; then
                    continue                                     # already this release's file
                fi
                changed=yes
                if [ -f "$pdst/$jn" ]; then
                    log "plugin '$pid': $jn replaced (the previous one kept as $jn.bak, for a rollback)"
                    run cp -f "$pdst/$jn" "$pdst/$jn.bak"
                fi
                run cp -f "$f" "$pdst/$jn"
            done
            [ -d "$pdir/lib" ] && { run mkdir -p "$pdst/lib"; run cp -a "$pdir/lib/." "$pdst/lib/"; }
            # Templates and i18n too: an essential plugin's SCREENS ship in these files, so skipping them would
            # mean a release that adds a panel never reaches an upgraded install — no error, just a
            # feature that is not there.
            for sub in templates i18n; do
                [ -d "$pdir/$sub" ] || continue
                run mkdir -p "$pdst/$sub"
                for src in "$pdir/$sub"/*; do
                    [ -f "$src" ] || continue
                    fn="$(basename "$src")"
                    rel="$sub/$fn"
                    dstf="$pdst/$rel"
                    if [ ! -f "$dstf" ]; then
                        changed=yes
                        run cp -f "$src" "$dstf"                 # new in this release
                        continue
                    fi
                    cmp -s "$src" "$dstf" && continue            # already current — nothing to say
                    changed=yes
                    current="$(_ps_sha "$dstf")"
                    recorded="$(_ps_manifest_hash "$manifest" "$rel")"
                    if [ -n "$recorded" ] && [ "$recorded" = "$current" ]; then
                        log "plugin '$pid': $rel updated (unchanged since it was installed — no backup needed)"
                    else
                        bak="$dstf.operator-backup-$stamp"
                        if [ -n "$recorded" ]; then
                            warn "plugin '$pid': $rel was edited after it was installed — your copy saved as $(basename "$bak")"
                        else
                            warn "plugin '$pid': $rel differs from the new version, and this install has no record of what was installed before (it predates the shipped-manifest), so it cannot tell your edit from our update — your copy saved as $(basename "$bak") as a precaution"
                        fi
                        run cp -f "$dstf" "$bak"
                    fi
                    run cp -f "$src" "$dstf"
                done
            done
            # Every shipped file is now installed as shipped, so the manifest is simply the shipped set.
            _ps_write_manifest "$pdir" "$manifest" "$pid"
            [ "$changed" = "no" ] && log "plugin '$pid' was already this release's — nothing changed"
        fi
    done
    return 0
}
