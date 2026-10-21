#!/usr/bin/env bash
# test-install-config.sh — tests that install.sh never replaces an existing external/application.yml.
# Runs without root, in a scratch directory: `bash scripts/test-install-config.sh [scratch-parent]`. The dist
# module runs it in its test phase (skipped with -DskipTests). Exit status 0 = all passed.
#
# Why: the installer writes application.yml WHOLE when --port N (N != 8080) or --tls-keystore is given. On a first
# install that is the point; on an existing one the file also holds the setup wizard's application-superadmin pin
# (runway.security.app-master-id) and whatever the operator added (CSP keys, ...), so a re-run with the flags from
# the first install silently dropped the pin and the platform booted back into wizard mode, /setup open to anyone
# (fixed in 1.0.0-rc1). Those flags are now first-install only: refused, before any change, on an existing install.
# The real installer runs in --dry-run (no root needed), so its printed plan and its exit status are the evidence;
# the write function's own guard (the backstop) is exercised directly, non-dry, as the defect was reproduced.
set -euo pipefail

HERE="$(dirname "$(readlink -f "$0")")"
PARENT="${1:-${TMPDIR:-/tmp}}"
mkdir -p "$PARENT"
ROOT="$(readlink -f "$(mktemp -d "$PARENT/install-config-test.XXXXXX")")"
trap 'rm -rf "$ROOT"' EXIT

FAILS=0
PASSES=0
OUT="$ROOT/install.log"
fail() { printf 'FAIL: %s\n' "$*" >&2; FAILS=$((FAILS + 1)); }
pass() { PASSES=$((PASSES + 1)); }
check() { if eval "$2"; then pass; else fail "$1"; sed 's/^/    | /' "$OUT" >&2; fi; }

# A bundle laid out like the dist zip (bin/ + scripts/), and a keystore to point --tls-keystore at.
B="$ROOT/bundle"
mkdir -p "$B/bin" "$B/scripts"
printf 'jar' > "$B/bin/runway-app-9.9.9-exec.jar"
cp "$HERE/install.sh" "$HERE/plugin-seed.sh" "$B/scripts/"
KS="$ROOT/runway.p12"; printf 'not-a-real-keystore' > "$KS"

# install <prefix> args... — the real installer, dry run, never a firewall; records the exit status in RC.
RC=0
install_dry() { local p="$1"; shift; RC=0; bash "$B/scripts/install.sh" --dry-run -y --no-firewall --prefix "$p" "$@" > "$OUT" 2>&1 || RC=$?; }

# An existing install: the file as a running deployment has it — the installer's port, the operator's CSP keys, the wizard's pin.
existing() {
    local p="$1"
    mkdir -p "$p/runway-data/external"
    cat > "$p/runway-data/external/application.yml" <<'YML'
server:
  port: 18921
runway:
  csp:
    mode: enforce
    strict-dynamic: true
    public-strict-dynamic: true
  security:
    app-master-id: app-master
YML
    cp "$p/runway-data/external/application.yml" "$ROOT/expected.yml"
}
unchanged() { cmp -s "$ROOT/expected.yml" "$1/runway-data/external/application.yml"; }

# --- 1. A first install still writes the file the flags ask for ---------------------------------------------
F="$ROOT/fresh"
install_dry "$F" --port 9090
check "1: fresh install with --port exits 0" '[ "$RC" -eq 0 ]'
check "1: ... and plans to write application.yml with the port" 'grep -qF "  + write $F/runway-data/external/application.yml (tls=no port=9090" "$OUT"'
check "1: ... and the plan names the port as before" 'grep -q "plain HTTP on :9090" "$OUT"'
install_dry "$F" --tls-keystore "$KS" --tls-password pw
check "1: fresh install with --tls-keystore exits 0" '[ "$RC" -eq 0 ]'
check "1: ... and plans to write the TLS block" 'grep -qF "  + write $F/runway-data/external/application.yml (tls=yes port=443" "$OUT"'

# --- 2. The reproduction: an upgrade re-run with --port N over an existing file is refused -----------
E="$ROOT/existing"; existing "$E"
install_dry "$E" --port 9090
check "2: --port 9090 on an existing install fails" '[ "$RC" -ne 0 ]'
check "2: ... saying the file exists and why that matters" 'grep -qF "$E/runway-data/external/application.yml already exists, so this is an existing install" "$OUT" && grep -q "superadmin pin" "$OUT"'
check "2: ... naming the flag and the key to edit instead" 'grep -q -- "--port 9090 is a first-install option" "$OUT" && grep -q "edit server.port in that file" "$OUT"'
check "2: ... before any action is planned (nothing ran)" '! grep -q "^  + " "$OUT"'
check "2: ... and says nothing was changed" 'grep -q "Nothing was changed" "$OUT"'
check "2: the file is byte-identical (pin kept)" 'unchanged "$E"'

# --- 3. --tls-keystore on an existing install: refused, naming runway.tls.* -----------------------------------
install_dry "$E" --tls-keystore "$KS" --tls-password pw --tls-redirect
check "3: --tls-keystore on an existing install fails" '[ "$RC" -ne 0 ]'
check "3: ... naming only the flag given (not the implied port) and the runway.tls keys" 'grep -q -- ": --tls-keystore is a first-install option" "$OUT" && grep -q "runway.tls.\* (enabled, keystore, keystore-password, key-alias, redirect-http) in that file" "$OUT"'
check "3: ... and the keystore is not copied either" '! grep -q "copy keystore" "$OUT" && [ ! -e "$E/runway-data/external/tls" ]'
check "3: the file is byte-identical" 'unchanged "$E"'

# --- 4. Both flags at once: both named ------------------------------------------------------------------------
install_dry "$E" --port 9443 --tls-keystore "$KS" --tls-password pw
check "4: both flags: refused" '[ "$RC" -ne 0 ]'
check "4: ... naming both flags and both keys" 'grep -q -- "--port 9443 and --tls-keystore are first-install options" "$OUT" && grep -q "edit server.port and runway.tls" "$OUT"'

# --- 5. What an upgrade is meant to be run with still works, and says the file is kept ------------------------
install_dry "$E"
check "5: a plain -y upgrade over an existing install exits 0" '[ "$RC" -eq 0 ]'
check "5: ... plans no write of application.yml" '! grep -q "write .*application.yml" "$OUT"'
check "5: ... and says the port/TLS come from that file, kept" 'grep -qF "port / tls   : as configured in $E/runway-data/external/application.yml (kept" "$OUT"'
install_dry "$E" --port 8080 --open-firewall
check "5: --port 8080 (the default, never written) is still accepted on an existing install" '[ "$RC" -eq 0 ] && ! grep -q "write .*application.yml" "$OUT"'
install_dry "$E" --firewall-ports 80,443
check "5: --firewall-ports (ADMINISTRATION §7.1 suggests a re-run with it) is still accepted" '[ "$RC" -eq 0 ]'
check "5: the file is byte-identical after all of it" 'unchanged "$E"'

# --- 6. An existing install whose file is a symlink (an operator keeping config elsewhere) is existing too -----
L="$ROOT/linked"; mkdir -p "$L/runway-data/external" "$ROOT/elsewhere"
cp "$ROOT/expected.yml" "$ROOT/elsewhere/app.yml"; ln -s "$ROOT/elsewhere/app.yml" "$L/runway-data/external/application.yml"
install_dry "$L" --port 9090
check "6: a symlinked application.yml is an existing install: refused" '[ "$RC" -ne 0 ] && grep -q "first-install option" "$OUT"'

# --- 7. The backstop: write_app_config itself refuses an existing file, even when called non-dry ---------------
# The function body is taken from the installer as shipped, exactly as it was extracted to reproduce the
# overwrite: WRITE_CONFIG=yes WRITE_PORT=yes PORT=9090 over the existing file, DRY_RUN=no.
X="$ROOT/backstop"; existing "$X"
{
    sed -n '/^log()  {/p; /^warn() {/p; /^die()  {/p; /^yaml_escape() {/p' "$B/scripts/install.sh"
    sed -n '/^write_app_config() {/,/^}/p' "$B/scripts/install.sh"
} > "$ROOT/fns.sh"
check "7: the functions were found in install.sh" 'grep -q "^write_app_config() {" "$ROOT/fns.sh" && grep -q "^die()" "$ROOT/fns.sh"'
RC=0
( set -e; . "$ROOT/fns.sh"; DRY_RUN=no WRITE_CONFIG=yes WRITE_PORT=yes PORT=9090 TLS_ENABLED=no TLS_REDIRECT=no \
    APP_USER="$(id -un)" DATA="$X/runway-data"; write_app_config ) > "$OUT" 2>&1 || RC=$?
check "7: write_app_config over an existing file fails" '[ "$RC" -ne 0 ]'
check "7: ... with its own refusal (not a later install(1) error)" 'grep -q "refusing to replace the existing $X/runway-data/external/application.yml" "$OUT" && ! grep -q "^\[install\] writing" "$OUT"'
check "7: ... and the file is byte-identical (the defect left only server.port: 9090)" 'unchanged "$X"'
RC=0
( set -e; . "$ROOT/fns.sh"; DRY_RUN=yes WRITE_CONFIG=yes WRITE_PORT=yes PORT=9090 TLS_ENABLED=no TLS_REDIRECT=no \
    APP_USER="$(id -un)" DATA="$X/runway-data"; write_app_config ) > "$OUT" 2>&1 || RC=$?
check "7: ... in a dry run too" '[ "$RC" -ne 0 ] && grep -q "refusing to replace" "$OUT"'

# --- 8. --help says the flags are first-install only ---------------------------------------------------------
bash "$B/scripts/install.sh" --help > "$OUT" 2>&1 || true
check "8: --help marks --port/--tls-keystore first-install only" 'grep -q "First install only, like --tls-keystore" "$OUT"'

# --- 9. An upgrade keeps CAP_NET_BIND_SERVICE when the unit it replaces granted it ---------------------------
# The installer cannot read the configured port on an existing install (no YAML parser), so it plans 8080; before
# this, an install terminating TLS on 443 (or redirecting on 80) lost the capability on every upgrade and could no
# longer bind its port. An install on 8080 behind HAProxy (capability commented) must keep getting nothing extra.
cp "$HERE/runway.service" "$B/scripts/"
U="$ROOT/upgrade"; existing "$U"
sed -E 's/^#[[:space:]]*(AmbientCapabilities=CAP_NET_BIND_SERVICE)/\1/; s/^#[[:space:]]*(CapabilityBoundingSet=CAP_NET_BIND_SERVICE)/\1/' \
    "$HERE/runway.service" > "$ROOT/granting.service"
RUNWAY_UNIT_DST="$ROOT/granting.service" install_dry "$U"
check "9: an upgrade over a unit granting the capability keeps it" '[ "$RC" -eq 0 ] && grep -q "net-bind-cap=yes" "$OUT"'
cp "$HERE/runway.service" "$ROOT/plain.service"
RUNWAY_UNIT_DST="$ROOT/plain.service" install_dry "$U"
check "9: ... and over a unit without it (8080 behind a proxy) adds nothing" '[ "$RC" -eq 0 ] && grep -q "net-bind-cap=no" "$OUT"'
grep -q '^#[[:space:]]*AmbientCapabilities=CAP_NET_BIND_SERVICE' "$HERE/runway.service" && pass \
    || fail "9: the unit template ships the capability commented (the check above depends on it)"

printf 'install-config: %d passed, %d failed\n' "$PASSES" "$FAILS"
[ "$FAILS" -eq 0 ]
