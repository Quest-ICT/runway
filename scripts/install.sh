#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# Runway — installer / upgrader (Linux, root).
#
# Idempotent. Lays the code under a VERSIONED directory and flips a "current"
# symlink to it, while all editable state (external/, logs/, backups/) lives in a
# version-INDEPENDENT data dir symlinked into the release — so an upgrade never
# clobbers configuration, sites, logs, or backups, and a rollback is a symlink flip.
#
#   <prefix>/runway-<version>/   the release (bin/, scripts/, seed/), external+logs+backups symlinked
#   <prefix>/runway              -> runway-<version>   "current" (RUNWAY_HOME)
#   <prefix>/runway-data/        external/  logs/  backups/   (survives upgrades)
#
# It creates the service user, installs/adjusts the systemd unit (pointing
# JAVA_HOME at a real JDK/JRE 25+ on the host), relabels SELinux contexts so the
# service can exec from the install dir on enforcing RHEL-family hosts, optionally
# opens the app port in the host firewall, and (re)starts the service.
#
# Runway is filesystem/YAML-backed — there is NO database to create. The shell
# installer lays down files + the systemd service and (interactively, on a TTY)
# asks about install location, service user, TLS termination and the firewall; the
# app-superadmin + first site are then created in the app's own web setup wizard.
#
#   sudo ./scripts/install.sh                 # interactive (prompts on a TTY)
#   sudo ./scripts/install.sh -y              # unattended, all defaults (plain HTTP behind a proxy)
#   sudo ./scripts/install.sh --tls-keystore /etc/ssl/runway.p12 --tls-password s3cr3t --tls-redirect -y
#   sudo ./scripts/install.sh --port 8080 --open-firewall
#   sudo ./scripts/install.sh --dry-run       # print actions, change nothing
# ---------------------------------------------------------------------------
set -euo pipefail

APP="runway"
PREFIX="/opt"
APP_USER="runway"
PORT=""                 # blank => whatever the app/config defaults to
JAVA_HOME_ARG=""
OPEN_FIREWALL="no"
FIREWALL_PORTS=""
NO_FIREWALL="no"
INTERACTIVE="auto"
DRY_RUN="no"
HEAP_MAX=""                # --heap SIZE: JVM -Xmx, written to a drop-in so it survives upgrades (blank => leave as-is)
MEMORY_MAX=""              # --memory-max SIZE: systemd MemoryMax cgroup backstop, ditto
# TLS termination (default: OFF — plain HTTP behind a reverse proxy). Local TLS points at an existing
# PKCS12 keystore; the installer writes runway.tls.* into the external application.yml (the first-run web
# wizard then merges its superadmin pin without discarding it).
TLS_ENABLED="no"
TLS_KEYSTORE=""            # the PKCS12 path the operator gives (may be relative to where they run the installer)
TLS_KEYSTORE_DEST=""       # absolute path the keystore is COPIED to inside the deployment (what the config points at)
TLS_PASSWORD=""
TLS_REDIRECT="no"
NO_TLS="no"
# Secret-encryption key (runway.security.secret-key): required for storing ANY secret setting (SMTP / CAPTCHA /
# plugin passwords). Persisted as a root-only systemd drop-in env var. Blank => auto-generate one on first
# install (so a secret store never silently fails); an existing drop-in is NEVER overwritten (changing the key
# makes previously-encrypted secrets undecryptable). Pass --secret-key to reuse a known key (e.g. a migration).
SECRET_KEY=""
SECRET_DROPIN="/etc/systemd/system/${APP}.service.d/30-secret-key.conf"
# Operator memory tuning goes in DROP-INS (not the installer-rewritten main unit), so a custom heap / cgroup cap
# survives every upgrade. Written only when --heap / --memory-max is passed; otherwise left exactly as they are.
MEMORY_DROPIN="/etc/systemd/system/${APP}.service.d/10-memory.conf"
HEAP_DROPIN="/etc/systemd/system/${APP}.service.d/20-heap.conf"

log()  { printf '[install] %s\n' "$*"; }
warn() { printf '[install] WARNING: %s\n' "$*" >&2; }
die()  { printf '[install] ERROR: %s\n' "$*" >&2; exit 1; }

# Run a privileged/side-effecting command, or just echo it under --dry-run.
run() {
    if [ "$DRY_RUN" = "yes" ]; then printf '  + %s\n' "$*"; else "$@"; fi
}

prompt_yesno() {  # var "question" default(yes/no)
    local __var="$1" __q="$2" __def="${3:-no}" __ans
    [ "$INTERACTIVE" = "yes" ] || { printf -v "$__var" '%s' "$__def"; return; }
    read -r -p "[install] ${__q} [$( [ "$__def" = yes ] && echo 'Y/n' || echo 'y/N')]: " __ans || true
    case "${__ans:-$__def}" in y|Y|yes|YES) printf -v "$__var" 'yes' ;; *) printf -v "$__var" 'no' ;; esac
}

prompt_value() {  # var "question" default
    local __var="$1" __q="$2" __def="${3:-}" __ans
    [ "$INTERACTIVE" = "yes" ] || { printf -v "$__var" '%s' "$__def"; return; }
    read -r -p "[install] ${__q}${__def:+ [$__def]}: " __ans || true
    printf -v "$__var" '%s' "${__ans:-$__def}"
}

prompt_secret() {  # var "question"  (never echoed)
    local __var="$1" __q="$2" __ans
    [ "$INTERACTIVE" = "yes" ] || return
    read -r -s -p "[install] ${__q}: " __ans || true; echo
    printf -v "$__var" '%s' "$__ans"
}

# Escape a value for a double-quoted YAML scalar (backslash + quote only — enough for a keystore password).
yaml_escape() { printf '%s' "$1" | sed 's/\\/\\\\/g; s/"/\\"/g'; }

# Writes the external application.yml the operator's choices imply (server.port when non-default, and the
# Copy the operator's PKCS12 keystore INTO the deployment's data dir and record its absolute path in
# TLS_KEYSTORE_DEST. The service resolves runway.tls.keystore relative to its own working directory (the
# deployment root), NOT to where the operator ran the installer — so a bare "runway.p12" would never be found.
# Copying it (owned by the service user, mode 600 — it holds a private key) makes the keystore travel with the
# deployment's version-independent data dir and gives the config a stable, absolute path.
stage_keystore() {
    [ "$TLS_ENABLED" = "yes" ] || return 0
    local src base
    base="$(basename "$TLS_KEYSTORE")"
    TLS_KEYSTORE_DEST="$DATA/external/tls/$base"
    # Resolve a relative source against the invocation dir (the .bin stub does not cd, so pwd is where it ran).
    src="$TLS_KEYSTORE"; case "$src" in /*) : ;; *) src="$(pwd)/$src" ;; esac
    if [ "$DRY_RUN" = "yes" ]; then
        printf '  + copy keystore %s -> %s\n' "$src" "$TLS_KEYSTORE_DEST"
        return 0
    fi
    mkdir -p "$DATA/external/tls"
    if [ -e "$src" ]; then
        install -o "$APP_USER" -g "$APP_USER" -m 600 "$src" "$TLS_KEYSTORE_DEST"
        log "copied keystore into the deployment: $TLS_KEYSTORE_DEST"
    else
        warn "keystore not found at $src — drop your PKCS12 at $TLS_KEYSTORE_DEST before starting the service (the config already points there)"
    fi
}

# runway.tls.* block for local termination). chmod 600 + owned by the service user (it holds a secret).
# Skipped entirely when nothing needs writing (the common proxy/default case — the web wizard creates it).
# FIRST INSTALL ONLY: it writes the whole file, so over an existing one it would drop the setup wizard's
# application-superadmin pin (the platform reopens /setup to anyone) and every key the operator added. The
# argument check refuses --port/--tls-keystore on an existing install before anything runs; this guard is the
# backstop, so no future caller can reach the overwrite either.
write_app_config() {
    [ "$WRITE_CONFIG" = "yes" ] || return 0
    local dest="$DATA/external/application.yml"
    [ -e "$dest" ] && die "refusing to replace the existing $dest (it holds the setup wizard's superadmin pin and your settings) — edit server.port / runway.tls.* in it instead"
    if [ "$DRY_RUN" = "yes" ]; then
        printf '  + write %s (tls=%s port=%s redirect=%s)\n' "$dest" "$TLS_ENABLED" "${PORT:-default}" "$TLS_REDIRECT"
        return 0
    fi
    log "writing $dest (server.port / runway.tls.*)"
    local tmp; tmp="$(mktemp)"; chmod 600 "$tmp"
    {
        echo "# Written by the Runway installer. The first-run web wizard MERGES its application-superadmin"
        echo "# pin into this file, preserving these settings. Edit freely; restart to apply."
        if [ "$WRITE_PORT" = "yes" ]; then
            printf 'server:\n  port: %s\n' "$PORT"
        fi
        if [ "$TLS_ENABLED" = "yes" ]; then
            echo "runway:"
            echo "  tls:"
            echo "    enabled: true"
            printf '    keystore: %s\n' "$TLS_KEYSTORE_DEST"
            printf '    keystore-password: "%s"\n' "$(yaml_escape "$TLS_PASSWORD")"
            echo "    key-alias: runway"
            if [ "$TLS_REDIRECT" = "yes" ]; then
                echo "    redirect-http:"
                echo "      enabled: true"
                printf '      acme-challenges-dir: %s/external/acme-challenges\n' "$DATA"
            fi
        fi
    } > "$tmp"
    install -o "$APP_USER" -g "$APP_USER" -m 600 "$tmp" "$dest"
    rm -f "$tmp"
}

# A high-entropy random key (base64 of 48 bytes = 64 chars). Used when no --secret-key is given; the app
# SHA-256's it into the AES-256 key, so any long random string works.
gen_secret_key() {
    if command -v openssl >/dev/null 2>&1; then
        openssl rand -base64 48 | tr -d '\n'
    else
        head -c 48 /dev/urandom | base64 | tr -d '\n'
    fi
}

# Persist the secret-encryption key as a root-only systemd drop-in env var (RUNWAY_SECURITY_SECRET_KEY, which
# Spring binds to runway.security.secret-key). Written ONCE, on first install: an existing drop-in is kept so a
# re-run/upgrade never rotates the key (which would make already-stored ENC(...) secrets undecryptable). When no
# key is supplied, one is generated — so even an unattended `-y` install always ends up able to store secrets.
write_secret_key_dropin() {
    if [ -f "$SECRET_DROPIN" ] && grep -q 'RUNWAY_SECURITY_SECRET_KEY' "$SECRET_DROPIN" 2>/dev/null; then
        log "secret-encryption key already set ($SECRET_DROPIN) — keeping it (never rotated by the installer)"
        return 0
    fi
    [ -n "$SECRET_KEY" ] || SECRET_KEY="$(gen_secret_key)"
    if [ -z "$SECRET_KEY" ]; then
        warn "could not generate a secret key (no openssl or /dev/urandom) — set runway.security.secret-key yourself, or stored secrets (SMTP/CAPTCHA) will not save"
        return 0
    fi
    if [ "$DRY_RUN" = "yes" ]; then
        printf '  + write %s (Environment=RUNWAY_SECURITY_SECRET_KEY=<key>, mode 600)\n' "$SECRET_DROPIN"
        return 0
    fi
    log "writing secret-encryption key drop-in -> $SECRET_DROPIN (value not shown)"
    mkdir -p "$(dirname "$SECRET_DROPIN")"
    local tmp; tmp="$(mktemp)"; chmod 600 "$tmp"
    {
        echo "[Service]"
        echo "# Secret-encryption key for ENC(...) settings (SMTP / CAPTCHA / plugin passwords), written by the"
        echo "# Runway installer. BACK IT UP with your deployment secrets: changing or losing this key makes all"
        echo "# previously-encrypted values undecryptable. Root-readable only (chmod 600)."
        printf 'Environment=RUNWAY_SECURITY_SECRET_KEY=%s\n' "$SECRET_KEY"
    } > "$tmp"
    install -o root -g root -m 600 "$tmp" "$SECRET_DROPIN"
    rm -f "$tmp"
}

# Write the memory-tuning drop-ins (10-memory.conf / 20-heap.conf) when --memory-max / --heap were passed. They
# live under runway.service.d/ (operator-owned; the installer never removes them), so a custom cgroup cap / heap
# SURVIVES every upgrade — unlike an -Xmx hand-edited into the installer-rewritten main unit. Passing neither leaves
# any existing tuning drop-ins exactly as they are.
write_tuning_dropins() {
    if [ -n "$MEMORY_MAX" ]; then
        log "writing memory-cap drop-in -> $MEMORY_DROPIN (MemoryMax=$MEMORY_MAX)"
        if [ "$DRY_RUN" = "yes" ]; then
            printf '  + write %s (MemoryAccounting=yes, MemoryMax=%s)\n' "$MEMORY_DROPIN" "$MEMORY_MAX"
        else
            mkdir -p "$(dirname "$MEMORY_DROPIN")"
            {
                echo "[Service]"
                echo "# systemd cgroup memory backstop, written by the Runway installer (--memory-max). A DROP-IN so"
                echo "# it survives upgrades (the installer rewrites the main unit, never this). Keep MemoryMax >="
                echo "# -Xmx + headroom (~max(0.5G, 25% of -Xmx)) or the cgroup OOM-kills a JVM inside its own heap."
                echo "MemoryAccounting=yes"
                printf 'MemoryMax=%s\n' "$MEMORY_MAX"
            } > "$MEMORY_DROPIN"
        fi
    fi
    if [ -n "$HEAP_MAX" ]; then
        log "writing heap drop-in -> $HEAP_DROPIN (-Xmx$HEAP_MAX)"
        if [ "$DRY_RUN" = "yes" ]; then
            printf '  + write %s (Environment="JAVA_OPTS=-Xms256m -Xmx%s")\n' "$HEAP_DROPIN" "$HEAP_MAX"
        else
            mkdir -p "$(dirname "$HEAP_DROPIN")"
            {
                echo "[Service]"
                echo "# JVM heap, written by the Runway installer (--heap). A DROP-IN Environment= overrides the main"
                echo "# unit's JAVA_OPTS (last assignment wins) and survives upgrades — a custom -Xmx in the main unit"
                echo "# would be reverted on the next install. Add other JVM flags (GC, etc.) here too if needed."
                printf 'Environment="JAVA_OPTS=-Xms256m -Xmx%s"\n' "$HEAP_MAX"
            } > "$HEAP_DROPIN"
        fi
    fi
}

java_major() {  # <java-home> -> major version, or empty
    local jh="$1" out
    [ -x "$jh/bin/java" ] || return 0
    out="$("$jh/bin/java" -version 2>&1 | head -1)"
    echo "$out" | sed -nE 's/.*version "([0-9]+).*/\1/p'
}

# Pick a JDK/JRE >= 25: the --java-home arg, then $JAVA_HOME, then common install
# dirs (Zulu, Temurin, generic openjdk). A JRE is fine — Runway does not compile.
detect_java_home() {
    local cand
    for cand in "$JAVA_HOME_ARG" "${JAVA_HOME:-}"; do
        [ -n "$cand" ] || continue
        [ "$(java_major "$cand")" -ge 25 ] 2>/dev/null && { echo "$cand"; return; }
    done
    for cand in /usr/lib/jvm/*zulu*25* /usr/lib/jvm/*temurin*25* /usr/lib/jvm/*-25-* \
                /usr/lib/jvm/java-25-* /usr/lib/jvm/jdk-25* /usr/lib/jvm/jre-25*; do
        [ -d "$cand" ] || continue
        [ "$(java_major "$cand")" -ge 25 ] 2>/dev/null && { echo "$cand"; return; }
    done
    echo ""
}

# The TCP ports the install should open in the host firewall: --firewall-ports if
# given, else the app port (--port) if set, else nothing (behind a proxy the app
# port is internal and the firewall is the operator's call).
firewall_target_ports() {
    if [ -n "$FIREWALL_PORTS" ]; then echo "${FIREWALL_PORTS//,/ }"; return; fi
    local ports=""
    [ -n "$PORT" ] && ports="$PORT"
    # local TLS with the HTTP->HTTPS redirect also needs port 80 (also serves Let's Encrypt HTTP-01)
    [ "$TLS_ENABLED" = "yes" ] && [ "$TLS_REDIRECT" = "yes" ] && ports="${ports:+$ports }80"
    echo "$ports"
}

# Open ports in whatever firewall actually manages this box. firewalld and an
# ALREADY-active ufw are driven directly; anything else is left untouched (we
# never enable ufw — that can drop the SSH session). Best-effort: warns, never dies.
open_firewall() {
    local ports; ports="$(firewall_target_ports)"
    [ -n "$ports" ] || { warn "no firewall ports to open (set --port or --firewall-ports)"; return 0; }
    if command -v firewall-cmd >/dev/null 2>&1 && firewall-cmd --state >/dev/null 2>&1; then
        log "opening firewalld ports: $ports"
        for p in $ports; do run firewall-cmd --permanent --add-port="${p}/tcp" || warn "firewalld: could not add ${p}/tcp"; done
        run firewall-cmd --reload || warn "firewalld: reload failed"; return 0
    fi
    if command -v ufw >/dev/null 2>&1 && ufw status 2>/dev/null | grep -qiE '^Status:[[:space:]]*active'; then
        log "opening ufw ports: $ports"
        for p in $ports; do run ufw allow "${p}/tcp" || warn "ufw: could not allow ${p}/tcp"; done; return 0
    fi
    if command -v ufw >/dev/null 2>&1; then
        warn "ufw is installed but inactive — not enabling it (could drop your SSH session)."
        for p in $ports; do warn "  open manually: ufw allow ${p}/tcp"; done
    else
        warn "no firewalld/ufw manager active — leaving raw nftables/iptables untouched; open ($ports)/tcp yourself if needed."
    fi
}

usage() {
    sed -n '2,/^# ----/p' "$(readlink -f "$0")" | sed 's/^# \{0,1\}//'
    cat <<EOF

Options:
  --prefix DIR          Install root (default: $PREFIX).
  --user NAME           Service user to create/use (default: $APP_USER).
  --port N              App listen port (written to application.yml when non-default; 443 with TLS, else 8080).
                        First install only, like --tls-keystore: on an existing install (external/application.yml
                        present) both are refused — they would replace that file; edit server.port / runway.tls.*.
  --tls-keystore PATH   Terminate TLS here with this PKCS12 (.p12) keystore (implies local TLS).
  --tls-password PASS   Keystore password (else prompted; unattended installs must pass it).
  --tls-redirect        Also add an HTTP->HTTPS redirect + ACME webroot on port 80.
  --no-tls              Skip the TLS prompt (stay plain HTTP behind a proxy).
  --secret-key KEY      Master key for encrypting stored secrets (SMTP/CAPTCHA/plugin passwords). Persisted
                        as a root-only systemd drop-in. Blank => auto-generate on first install. An existing
                        key is kept (never overwritten). Back it up: losing it makes ENC(...) secrets unreadable.
  --java-home DIR       JDK/JRE 25+ to run under (else auto-detected).
  --open-firewall       Open the app port in the host firewall (best-effort).
  --firewall-ports LIST Comma-separated TCP ports to open (implies --open-firewall).
  --no-firewall         Never touch the firewall (wins over --open-firewall).
  -y, --yes             Non-interactive: accept all defaults.
  --heap SIZE           Set the JVM heap (-Xmx), e.g. 2g / 1536m. Written to an upgrade-surviving drop-in
                        (runway.service.d/20-heap.conf) — NOT the installer-rewritten main unit — so a custom heap
                        is never reverted on the next upgrade. Omit to leave the heap as-is.
  --memory-max SIZE     Set a systemd MemoryMax cgroup backstop, e.g. 3g / 1945M (drop-in 10-memory.conf). Keep it
                        >= the heap + headroom (~max(0.5G, 25% of -Xmx)). Omit to leave it as-is.
  --update-essential-plugins
                        Accepted and ignored, so existing runbooks keep working: every
                        upgrade does what it used to ask for (below).
  --dry-run            Print every privileged action instead of doing it.
  -h, --help            This help.

Bundled (essential) plugins on an upgrade: the protection plugin under external/plugins/protection is
ALWAYS refreshed from the new bundle — jar, plugin.yml, lib/, templates/ and i18n/ — because it moves in
lockstep with the kernel: an older copy beside a newer kernel is refused, and a refused essential plugin
makes every tenant page answer 503. Nothing you customized is lost: the previous jar and plugin.yml are
kept as <name>.bak (what a rollback restores — Administration §15; written only when the file changes, so
a re-run keeps them), and a template or i18n file you EDITED since it was installed is copied to
<name>.operator-backup-<timestamp> with a warning naming it. A file nobody edited is refreshed with no
backup: the install records what it laid down in external/plugin-manifests/<id>.sha256, which is how it
tells the two apart. Settings live outside the folder (external/plugin-settings/) and are never touched.
The bundle's copy also stays in the release, <prefix>/runway/seed/plugins/<id>/: if the plugin folder is
ever not the running release's (the fail-closed log line says so), copy it back from there and restart.
EOF
}

while [ $# -gt 0 ]; do
    case "$1" in
        --prefix)          PREFIX="${2:?}"; shift 2 ;;
        --user)            APP_USER="${2:?}"; shift 2 ;;
        --port)            PORT="${2:?}"; shift 2 ;;
        --java-home)       JAVA_HOME_ARG="${2:?}"; shift 2 ;;
        --open-firewall)   OPEN_FIREWALL="yes"; shift ;;
        --firewall-ports)  FIREWALL_PORTS="${2:?}"; OPEN_FIREWALL="yes"; shift 2 ;;
        --no-firewall)     NO_FIREWALL="yes"; shift ;;
        --tls-keystore)    TLS_KEYSTORE="${2:?}"; TLS_ENABLED="yes"; shift 2 ;;
        --tls-password)    TLS_PASSWORD="${2:?}"; shift 2 ;;
        --tls-redirect)    TLS_REDIRECT="yes"; shift ;;
        --no-tls)          NO_TLS="yes"; shift ;;
        --secret-key)      SECRET_KEY="${2:?}"; shift 2 ;;
        -y|--yes|--non-interactive) INTERACTIVE="no"; shift ;;
        --update-essential-plugins)
            # Kept so older runbooks still run; the refresh it used to opt into is now every upgrade's.
            log "--update-essential-plugins: nothing to do — every upgrade refreshes the bundled plugins now"
            shift ;;
        --heap)            HEAP_MAX="${2:?}"; shift 2 ;;
        --memory-max)      MEMORY_MAX="${2:?}"; shift 2 ;;
        --dry-run)         DRY_RUN="yes"; shift ;;
        -h|--help)         usage; exit 0 ;;
        *) die "unknown argument: $1 (see --help)" ;;
    esac
done

PORT_FLAG="$PORT"   # what --port said (the default is filled in below), so a refusal names only flags actually given

# --no-firewall wins over --open-firewall regardless of order.
[ "$NO_FIREWALL" = "yes" ] && OPEN_FIREWALL="no"

# Validate memory sizes (a number optionally followed by K/M/G — systemd + JVM both accept these).
for __sz in "$HEAP_MAX" "$MEMORY_MAX"; do
    case "$__sz" in
        "") : ;;
        *[!0-9kmgKMG]*|*[kmgKMG]*[kmgKMG]*|[!0-9]*) die "invalid memory size: '$__sz' (use e.g. 2g, 1536m, 3G)" ;;
    esac
done
# A heap without a matching cap is a footgun: if an existing/!new MemoryMax is below -Xmx it OOM-kills the JVM.
if [ -n "$HEAP_MAX" ] && [ -z "$MEMORY_MAX" ] && [ -f "$MEMORY_DROPIN" ]; then
    warn "set --heap $HEAP_MAX but not --memory-max; ensure $MEMORY_DROPIN's MemoryMax >= -Xmx + headroom (see ADMINISTRATION.md §15)"
fi
# Decide interactivity: a TTY on stdin => prompt, unless -y forced it off.
[ "$INTERACTIVE" = "auto" ] && { [ -t 0 ] && INTERACTIVE="yes" || INTERACTIVE="no"; }

[ "$(id -u)" -eq 0 ] || [ "$DRY_RUN" = "yes" ] || die "run as root (sudo) — or use --dry-run"

# --- interactive setup (only on a TTY, unless -y) — flags supplied become the prompt defaults ---------
prompt_value PREFIX   "Install location (prefix)" "$PREFIX"
prompt_value APP_USER "Service user" "$APP_USER"

# An existing install keeps its listen port and TLS in external/application.yml, beside the setup wizard's
# application-superadmin pin and whatever else the operator put there. The installer can only WRITE that file
# whole (merging YAML from shell would be a guess about the operator's formatting), so the port/TLS choices are
# first-install only: on an existing install they are not asked, and the flags are refused below.
APP_CONFIG="$PREFIX/$APP-data/external/application.yml"
HAVE_APP_CONFIG="no"; [ -e "$APP_CONFIG" ] && HAVE_APP_CONFIG="yes"

# TLS termination. A given --tls-keystore already implies local TLS; otherwise ask (unless --no-tls / -y).
if [ "$INTERACTIVE" = "yes" ] && [ -z "$TLS_KEYSTORE" ] && [ "$NO_TLS" != "yes" ] && [ "$HAVE_APP_CONFIG" = "no" ]; then
    prompt_yesno TLS_ENABLED "Terminate TLS in Runway itself? (No = plain HTTP behind a reverse proxy)" "no"
fi
if [ "$TLS_ENABLED" = "yes" ]; then
    [ -n "$TLS_KEYSTORE" ] || prompt_value  TLS_KEYSTORE "Path to the PKCS12 keystore (.p12)" ""
    [ -n "$TLS_PASSWORD" ] || prompt_secret TLS_PASSWORD "Keystore password"
    [ "$INTERACTIVE" = "yes" ] && prompt_yesno TLS_REDIRECT \
        "Also add an HTTP->HTTPS redirect on port 80 (serves Let's Encrypt HTTP-01)?" "$TLS_REDIRECT"
fi

# App listen port: default HTTPS 443 for local TLS, else HTTP 8080 (prompted on a TTY).
# (Not asked on an existing install: its port is whatever its application.yml says.)
if [ -z "$PORT" ]; then
    { [ "$TLS_ENABLED" = "yes" ] && PORT="443" || PORT="8080"; }
    [ "$HAVE_APP_CONFIG" = "yes" ] || \
        prompt_value PORT "$([ "$TLS_ENABLED" = yes ] && echo 'HTTPS port' || echo 'HTTP port')" "$PORT"
fi

# Secret-encryption key: prompt only on a FRESH install (no existing key drop-in); blank => auto-generate below.
if [ "$INTERACTIVE" = "yes" ] && [ -z "$SECRET_KEY" ] && ! [ -f "$SECRET_DROPIN" ]; then
    prompt_value SECRET_KEY "Secret key for stored secrets (SMTP/CAPTCHA passwords; leave empty to auto-generate)" ""
fi

# Validate TLS inputs, and decide what (if anything) to write to application.yml.
if [ "$TLS_ENABLED" = "yes" ]; then
    [ -n "$TLS_KEYSTORE" ] || die "local TLS requested but no keystore given (--tls-keystore PATH)"
    [ -e "$TLS_KEYSTORE" ] || warn "keystore not found at $TLS_KEYSTORE — the installer copies it into the deployment, so it should exist here now (see ADMINISTRATION §7.1 for PEM→PKCS12)"
    [ -n "$TLS_PASSWORD" ] || warn "no keystore password given — TLS start fails fast on a blank password"
fi
WRITE_PORT="no"; [ -n "$PORT" ] && [ "$PORT" != "8080" ] && WRITE_PORT="yes"
WRITE_CONFIG="no"; { [ "$TLS_ENABLED" = "yes" ] || [ "$WRITE_PORT" = "yes" ]; } && WRITE_CONFIG="yes"
# Refuse — before anything is changed — a first-install option that would replace an existing application.yml.
# Refusal rather than a merge: this script has no YAML parser, and a line-based edit of a file the operator and the
# wizard have both written (comments, an existing server:/runway: map, other keys under them) can produce a
# duplicate key, which stops the start just as surely. Editing two keys by hand, with the manual, cannot.
if [ "$WRITE_CONFIG" = "yes" ] && [ "$HAVE_APP_CONFIG" = "yes" ]; then
    __flags=""; __keys=""; __what="is a first-install option"
    [ "$WRITE_PORT" = "yes" ] && __keys="server.port"
    [ -n "$PORT_FLAG" ] && [ "$WRITE_PORT" = "yes" ] && __flags="--port $PORT"
    if [ "$TLS_ENABLED" = "yes" ]; then
        [ -n "$__flags" ] && __what="are first-install options"
        __flags="${__flags:+$__flags and }--tls-keystore"
        __keys="${__keys:+$__keys and }runway.tls.* (enabled, keystore, keystore-password, key-alias, redirect-http)"
    fi
    die "$APP_CONFIG already exists, so this is an existing install: $__flags $__what and would replace that file, losing the setup wizard's superadmin pin (the platform would reopen /setup) and every other setting in it. Nothing was changed. Re-run without it — an upgrade needs only -y — and to change the listen port or TLS, edit $__keys in that file and restart (ADMINISTRATION §7.1; put a new keystore under $PREFIX/$APP-data/external/tls/)."
fi

# Locate the bundle this script ships in: scripts/ is a sibling of bin/.
BUNDLE="$(dirname "$(dirname "$(readlink -f "$0")")")"
JAR_SRC="$(ls "$BUNDLE"/bin/runway-app-*.jar 2>/dev/null | head -1 || true)"
[ -n "$JAR_SRC" ] || die "no runway-app-*.jar under $BUNDLE/bin"
# The bundled jar is the executable one — carrying spring-boot's `exec` classifier
# (runway-app-<version>-exec.jar). Strip both the artifact prefix and that classifier so the
# release dir is runway-<version>, not runway-<version>-exec.
VERSION="$(basename "$JAR_SRC" .jar)"; VERSION="${VERSION#runway-app-}"; VERSION="${VERSION%-exec}"
[ -n "$VERSION" ] || die "could not determine version from $JAR_SRC"

DEST="$PREFIX/$APP-$VERSION"     # the versioned release
LINK="$PREFIX/$APP"              # the "current" symlink == RUNWAY_HOME
DATA="$PREFIX/$APP-data"         # version-independent editable state

JAVA_HOME_USE="$(detect_java_home)"
[ -n "$JAVA_HOME_USE" ] || warn "no JDK/JRE 25+ found — set --java-home or fix the systemd unit's JAVA_HOME afterward."

# Firewall prompt (interactive only, and only when not forced off).
if [ "$NO_FIREWALL" != "yes" ] && [ "$INTERACTIVE" = "yes" ] && [ "$OPEN_FIREWALL" != "yes" ] && [ -n "$(firewall_target_ports)" ]; then
    prompt_yesno OPEN_FIREWALL "Open port(s) $(firewall_target_ports | tr ' ' ,)/tcp in the host firewall" "no"
fi

log "Runway ${VERSION}"
log "  release      : $DEST"
log "  current link : $LINK"
log "  data dir     : $DATA"
log "  service user : $APP_USER"
log "  java home    : ${JAVA_HOME_USE:-<unset — fix the unit>}"
if [ "$HAVE_APP_CONFIG" = "yes" ]; then
    log "  port / tls   : as configured in $APP_CONFIG (kept — the installer never rewrites it)"
elif [ "$TLS_ENABLED" = "yes" ]; then
    log "  tls          : terminate HERE — HTTPS on :$PORT (keystore $TLS_KEYSTORE → copied to $DATA/external/tls/)$([ "$TLS_REDIRECT" = yes ] && echo ', HTTP->HTTPS redirect on :80')"
else
    log "  tls          : none here — plain HTTP on :$PORT (terminate at a reverse proxy)"
fi
log "  firewall     : $([ "$OPEN_FIREWALL" = yes ] && echo "open $(firewall_target_ports | tr ' ' ,)/tcp" || echo 'leave unchanged')"
log "  plugins      : bundled (essential) plugins seeded if missing, else refreshed from this release (previous jar + plugin.yml kept as *.bak)"
[ -d "$BUNDLE/seed" ] && log "  repair copy  : the bundled plugins also kept in the release, $LINK/seed/plugins/ (Administration §12)"
if [ -f "$SECRET_DROPIN" ]; then
    log "  secret key   : already configured (kept as-is)"
elif [ -n "$SECRET_KEY" ]; then
    log "  secret key   : provided (stored in a root-only drop-in)"
else
    log "  secret key   : auto-generate (stored in a root-only drop-in)"
fi
[ "$DRY_RUN" = "yes" ] && log "(dry run — no changes will be made)"

# --- service user ------------------------------------------------------------
if id "$APP_USER" >/dev/null 2>&1; then
    log "service user '$APP_USER' already exists"
else
    log "creating service user '$APP_USER'"
    run useradd --system --home-dir "$LINK" --shell /usr/sbin/nologin "$APP_USER" \
        || run useradd --system --home-dir "$LINK" --shell /sbin/nologin "$APP_USER"
fi

# --- data dir (version-independent editable state) ---------------------------
run mkdir -p "$DATA/external" "$DATA/logs" "$DATA/backups"

# --- external application.yml (server.port / self-managed TLS), when chosen ---
[ "$TLS_ENABLED" = "yes" ] && [ "$TLS_REDIRECT" = "yes" ] && run mkdir -p "$DATA/external/acme-challenges"
stage_keystore     # copy the PKCS12 into $DATA/external/tls and set TLS_KEYSTORE_DEST (before writing the config)
write_app_config   # no-op unless TLS or a non-default port was chosen; chmod 600, owned by the service user

# --- seed / update bundled plugins --------------------------------------------
# The bundle ships essential/bundled plugins under seed/plugins/<id>/. On FIRST install each is copied into the
# operator-owned external/plugins/<id>. On a re-run (upgrade) each existing folder is REFRESHED — code, descriptor,
# templates and i18n — because an essential plugin moves in lockstep with the kernel (an old copy is refused and the
# platform fails closed). The previous jar + plugin.yml stay as *.bak for a rollback, and a shipped-manifest
# (external/plugin-manifests/<id>.sha256) tells a file the operator edited (backed up, warned) from one that is
# merely stale. Settings live outside the folder and are never touched.
# The logic lives in plugin-seed.sh, beside this script, so it can be tested without root.
# shellcheck source=plugin-seed.sh
. "$(dirname "$(readlink -f "$0")")/plugin-seed.sh"
seed_bundled_plugins "$BUNDLE" "$DATA"

# --- release dir -------------------------------------------------------------
run mkdir -p "$DEST/bin" "$DEST/scripts"
run cp -f "$JAR_SRC" "$DEST/bin/"
run cp -f "$BUNDLE/bin/runway.sh" "$DEST/bin/runway.sh"
run cp -f "$BUNDLE"/scripts/* "$DEST/scripts/" 2>/dev/null || true
run chmod 0755 "$DEST/bin/runway.sh" "$DEST/scripts/install.sh"
# The license, the plugin permission and the third-party notices travel with each installed release, so the
# installed system carries its terms (the jars carry them too, under META-INF/). Skipped when the installer runs from
# an installed release, whose bundle IS $DEST.
if [ "$(readlink -m "$BUNDLE")" != "$(readlink -m "$DEST")" ]; then
    for f in LICENSE LICENSE-EXCEPTION THIRD-PARTY-NOTICES; do
        if [ -f "$BUNDLE/$f" ]; then run cp -f "$BUNDLE/$f" "$DEST/$f"; fi
    done
fi
# The bundled plugins stay in the release too (seed/plugins/<id>/), so repairing the essential plugin needs no .bin:
# when external/plugins/<id> is not the running release's copy (a hand-copied kernel jar, a hand edit), the kernel's
# fail-closed message says to copy $LINK/seed/plugins/<id>/ over it. After a rollback the symlink names the older
# release, whose seed/ is the older plugin — the same files as the *.bak pair the upgrade kept. Replaced whole, so a
# file this bundle no longer ships does not linger; owned and relabeled with the rest of the release below, and
# read-only to the service (ProtectSystem=strict, ReadWritePaths is the data dir only). Skipped when the installer
# runs from an installed release (its bundle IS $DEST — removing seed/ would delete the source).
if [ -d "$BUNDLE/seed" ] && [ "$(readlink -m "$BUNDLE")" != "$(readlink -m "$DEST")" ]; then
    log "keeping the bundled plugins in the release: $DEST/seed/ (the repair copy — Administration §12)"
    run rm -rf "$DEST/seed"
    run cp -R "$BUNDLE/seed" "$DEST/seed"
fi
# Editable state is symlinked in, so it survives upgrades. Per-site backup archives
# are a version-independent data dir too (the app writes them to the deployment-root
# backups/ — StorageLayout's default), so they must NOT sit in the versioned release.
run ln -sfn "$DATA/external" "$DEST/external"
run ln -sfn "$DATA/logs" "$DEST/logs"
run ln -sfn "$DATA/backups" "$DEST/backups"

# --- flip the "current" symlink ----------------------------------------------
run ln -sfn "$DEST" "$LINK"

# --- ownership ---------------------------------------------------------------
run chown -R "$APP_USER:$APP_USER" "$DATA"
run chown -h "$APP_USER:$APP_USER" "$LINK"
run chown -R "$APP_USER:$APP_USER" "$DEST"

# --- SELinux relabel (REQUIRED on enforcing RHEL-family; no-op elsewhere) -----
# The bundle unpacked into a user-temp dir, so cp -a-style copies can carry
# user_tmp_t; without a relabel systemd refuses to exec the launcher (203/EXEC).
if command -v restorecon >/dev/null 2>&1; then
    log "relabeling SELinux contexts (restorecon)"
    run restorecon -RF "$DEST" "$DATA" || warn "restorecon failed (continuing)"
fi

# --- systemd unit ------------------------------------------------------------
# A privileged port (<1024) bound by the non-root service user needs CAP_NET_BIND_SERVICE: 443 for local TLS,
# 80 for the HTTP->HTTPS redirect. The unit template ships that capability commented; enable it only when a
# privileged port is actually in play (least privilege — the common 8080-behind-proxy case gets nothing extra).
NEEDS_NET_BIND="no"
case "$PORT" in ''|*[!0-9]*) : ;; *) [ "$PORT" -lt 1024 ] && NEEDS_NET_BIND="yes" ;; esac
[ "$TLS_REDIRECT" = "yes" ] && NEEDS_NET_BIND="yes"
UNIT_SRC="$BUNDLE/scripts/runway.service"
UNIT_DST="${RUNWAY_UNIT_DST:-/etc/systemd/system/runway.service}"   # the override exists for the installer tests
# An upgrade cannot tell which port the existing application.yml configures (no YAML parser here: PORT above is only
# the default), and the unit is rewritten from the template, which ships the capability commented. So an install that
# binds 443 or 80 would lose it on every upgrade and fail to start. Keep what the current unit already grants: it is
# what that server has been running with (an operator who uncommented it by hand gets it kept too).
NET_BIND_KEPT="no"
if [ "$NEEDS_NET_BIND" = "no" ] && [ -f "$UNIT_DST" ] \
        && grep -Eq '^[[:space:]]*AmbientCapabilities=.*CAP_NET_BIND_SERVICE' "$UNIT_DST"; then
    NEEDS_NET_BIND="yes"; NET_BIND_KEPT="yes"
fi
if [ -f "$UNIT_SRC" ]; then
    log "installing systemd unit -> $UNIT_DST"
    if [ "$DRY_RUN" = "yes" ]; then
        printf '  + install unit with RUNWAY_HOME=%s JAVA_HOME=%s User=%s ReadWritePaths=%s net-bind-cap=%s\n' \
            "$LINK" "${JAVA_HOME_USE:-<unset>}" "$APP_USER" "$DATA" "$NEEDS_NET_BIND"
    else
        sed -E \
            -e "s#^Environment=RUNWAY_HOME=.*#Environment=RUNWAY_HOME=${LINK}#" \
            -e "s#^Environment=JAVA_HOME=.*#Environment=JAVA_HOME=${JAVA_HOME_USE:-/usr/lib/jvm/java-25-openjdk}#" \
            -e "s#^ExecStart=.*#ExecStart=${LINK}/bin/runway.sh -f#" \
            -e "s#^User=.*#User=${APP_USER}#" \
            -e "s#^Group=.*#Group=${APP_USER}#" \
            -e "s#^ReadWritePaths=.*#ReadWritePaths=${DATA}#" \
            "$UNIT_SRC" > "$UNIT_DST"
        # Uncomment the capability the template ships commented, but only when binding a privileged port.
        if [ "$NEEDS_NET_BIND" = "yes" ]; then
            sed -i -E 's/^#[[:space:]]*(AmbientCapabilities=CAP_NET_BIND_SERVICE)/\1/; s/^#[[:space:]]*(CapabilityBoundingSet=CAP_NET_BIND_SERVICE)/\1/' "$UNIT_DST"
            if [ "$NET_BIND_KEPT" = "yes" ]; then
                log "kept CAP_NET_BIND_SERVICE: the unit being replaced granted it (a privileged port is configured)"
            else
                log "enabled CAP_NET_BIND_SERVICE (binding privileged port ${PORT}$([ "$TLS_REDIRECT" = yes ] && echo ' and 80'))"
            fi
        fi
    fi
    write_secret_key_dropin   # first install: generate/persist the secret-encryption key (kept as-is on upgrade)
    write_tuning_dropins      # --heap / --memory-max: operator memory tuning, in upgrade-surviving drop-ins
    run systemctl daemon-reload
    run systemctl enable runway.service || warn "could not enable runway.service"
else
    warn "no runway.service in the bundle — skipping systemd install"
fi

# --- firewall (opt-in, best-effort) ------------------------------------------
[ "$OPEN_FIREWALL" = "yes" ] && open_firewall

# --- start / restart ---------------------------------------------------------
log "starting the service"
run systemctl restart runway.service || warn "could not start runway.service — check: journalctl -u runway -e"

log "done. Runway ${VERSION} is installed at ${LINK}."
log "  status : systemctl status runway"
log "  logs   : journalctl -u runway -f   (and ${DATA}/logs/)"
if [ "$TLS_ENABLED" = "yes" ]; then
    log "  next   : browse to https://<host>:$PORT and complete the first-run setup wizard (it keeps your TLS config)."
    [ -e "$TLS_KEYSTORE" ] || log "  note   : put the keystore at $TLS_KEYSTORE (owned/readable by '$APP_USER') before it will serve TLS."
else
    log "  next   : browse to the app (behind your proxy) and complete the first-run setup wizard."
fi
