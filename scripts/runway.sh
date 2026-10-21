#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# Runway — launcher (Linux).
#
# Modes:
#   ./runway.sh          # detached: a SUPERVISOR backgrounds the JVM, logs to
#                          $RUNWAY_HOME/logs/runway.out and writes a PID file at
#                          $RUNWAY_HOME/runway.pid. Closing the terminal does NOT
#                          stop it. The supervisor RELAUNCHES the JVM whenever it
#                          exits with code 42 (Runway's supervised-relaunch signal
#                          for the first-run wizard and plugin install), and stops
#                          on any other exit.
#   ./runway.sh -f       # foreground: the JVM replaces the shell via exec. Use
#                          with systemd (Type=simple) — the unit's
#                          RestartForceExitStatus=42 provides the relaunch.
#   ./runway.sh stop     # stops the supervisor (which SIGTERMs the JVM).
#
# Before launching, the script does `cd "${RUNWAY_HOME}"` so the relative paths in
# application.yml (./external/..., ./logs) resolve against the deployment root.
#
# Environment variables (overridable via systemd `Environment=`):
#   RUNWAY_HOME  Absolute path to the deployment root (contains external/, bin/
#                or target/, scripts/). Default: parent of this script's dir.
#   RUNWAY_JAR   Absolute path to the executable jar. Default: the single
#                runway-app-*.jar under <RUNWAY_HOME>/bin or /runway-app/target.
#   RUNWAY_OPTS  Extra Spring Boot args (--server.port=, --spring.profiles.active=).
#   JAVA_HOME    JDK/JRE 25+. If unset, `java` from PATH is used.
#   JAVA_OPTS    JVM flags. Default: -Xms256m -Xmx1g.
#
# A ready-to-use systemd unit ships alongside this file as scripts/runway.service.
# ---------------------------------------------------------------------------
set -euo pipefail

RESTART_EXIT_CODE=42   # must match wizard.Restarter.RESTART_EXIT_CODE

# Resolve the script's real path (follow symlinks).
SCRIPT_PATH="$(readlink -f "$0")"
SCRIPT_DIR="$(dirname "$SCRIPT_PATH")"
APP_DIR="$(dirname "$SCRIPT_DIR")"

: "${RUNWAY_HOME:=${APP_DIR}}"
# Resolve the executable jar: the single runway-app-*-exec.jar under bin/ (installed
# layout) or runway-app/target/ (development tree). The `-exec` classifier is what
# spring-boot:repackage attaches (the plain runway-app-*.jar is the thin library jar
# that reactor siblings compile against — NOT runnable), so matching `*-exec.jar`
# both picks the runnable jar and skips the thin one sitting beside it in target/.
# Globbing instead of a hardcoded version means a release build can never ship a
# launcher pointing at a stale jar name.
if [[ -z "${RUNWAY_JAR:-}" ]]; then
    RUNWAY_JAR=""
    for _j in "${APP_DIR}"/bin/runway-app-*-exec.jar "${APP_DIR}"/runway-app/target/runway-app-*-exec.jar; do
        [[ -e "${_j}" ]] || continue
        if [[ -n "${RUNWAY_JAR}" ]]; then
            echo "[runway] Multiple candidate jars under ${APP_DIR} — set RUNWAY_JAR explicitly:" >&2
            echo "       ${RUNWAY_JAR}" >&2
            echo "       ${_j}" >&2
            exit 1
        fi
        RUNWAY_JAR="${_j}"
    done
    if [[ -z "${RUNWAY_JAR}" ]]; then
        RUNWAY_JAR="${APP_DIR}/{bin,runway-app/target}/runway-app-<version>-exec.jar (none found)"
    fi
fi
: "${JAVA_OPTS:=-Xms256m -Xmx1g}"
: "${RUNWAY_OPTS:=}"
# Brotli response compression loads a native library; Hazelcast (opt-in clustering, runway.cluster.enabled)
# likewise touches restricted native calls on a modern JDK. Grant restricted native access so JDK 24+ starts
# without the "loadLibrary called ... in an unnamed module" / restricted-method warnings, and so a future JDK
# that makes native access an error (not a warning) still boots. Appended after any operator-supplied
# JAVA_OPTS, so it is always present.
JAVA_OPTS="${JAVA_OPTS} --enable-native-access=ALL-UNNAMED"

FOREGROUND=0
ACTION="start"
PASSTHRU=()
while [[ $# -gt 0 ]]; do
    case "$1" in
        -f|--foreground) FOREGROUND=1; shift ;;
        stop|--stop)     ACTION="stop"; shift ;;
        -h|--help)
            sed -n '2,/^# ----/p' "$SCRIPT_PATH" | sed 's/^# \{0,1\}//'
            exit 0
            ;;
        --) shift; PASSTHRU+=("$@"); break ;;
        *)  PASSTHRU+=("$1"); shift ;;
    esac
done

PID_FILE="${RUNWAY_HOME}/runway.pid"          # the supervisor (detached mode)
JVM_PID_FILE="${RUNWAY_HOME}/runway.jvm.pid"  # the current JVM under the supervisor
LOG_FILE="${RUNWAY_HOME}/logs/runway.out"

if [[ "${ACTION}" == "stop" ]]; then
    stopped=0
    # Stop the supervisor first so it does not relaunch; its trap SIGTERMs the JVM.
    if [[ -f "${PID_FILE}" ]]; then
        SUP="$(cat "${PID_FILE}" 2>/dev/null || true)"
        if [[ -n "${SUP}" ]] && kill -0 "${SUP}" 2>/dev/null; then
            echo "[runway] Stopping supervisor PID ${SUP}…"; kill -TERM "${SUP}"; stopped=1
        fi
    fi
    if [[ -f "${JVM_PID_FILE}" ]]; then
        JVM="$(cat "${JVM_PID_FILE}" 2>/dev/null || true)"
        if [[ -n "${JVM}" ]] && kill -0 "${JVM}" 2>/dev/null; then
            kill -TERM "${JVM}" 2>/dev/null || true; stopped=1
        fi
    fi
    rm -f "${PID_FILE}" "${JVM_PID_FILE}"
    [[ "${stopped}" -eq 1 ]] || echo "[runway] Nothing running (no live PID files)." >&2
    exit 0
fi

if [[ -n "${JAVA_HOME:-}" ]]; then
    JAVA_BIN="${JAVA_HOME}/bin/java"
else
    JAVA_BIN="$(command -v java || true)"
fi
if [[ -z "${JAVA_BIN}" || ! -x "${JAVA_BIN}" ]]; then
    echo "[runway] java not found. Set JAVA_HOME or put java on PATH." >&2
    exit 1
fi
if [[ ! -f "${RUNWAY_JAR}" ]]; then
    echo "[runway] Jar not found: ${RUNWAY_JAR}" >&2
    echo "       Build first:  mvn -DskipTests package" >&2
    exit 1
fi
if [[ ! -d "${RUNWAY_HOME}" ]]; then
    echo "[runway] RUNWAY_HOME is not a directory: ${RUNWAY_HOME}" >&2
    exit 1
fi

mkdir -p "${RUNWAY_HOME}/logs"

# application.yml relies on relative paths (./external/..., ./logs); cwd must be
# RUNWAY_HOME for Spring to resolve them.
cd "${RUNWAY_HOME}"

echo "[runway] RUNWAY_HOME=${RUNWAY_HOME}"
echo "[runway] RUNWAY_JAR=${RUNWAY_JAR}"
echo "[runway] JAVA=${JAVA_BIN}"

if [[ "${FOREGROUND}" -eq 1 ]]; then
    # `exec` replaces the shell with the JVM: systemd gets the right PID and
    # SIGTERM reaches Java directly. The unit's RestartForceExitStatus=42 does the
    # supervised relaunch (a foreground launcher must not loop under a supervisor).
    # shellcheck disable=SC2086
    exec "${JAVA_BIN}" ${JAVA_OPTS} -jar "${RUNWAY_JAR}" ${RUNWAY_OPTS} "${PASSTHRU[@]}"
fi

# Detached: refuse to double-start.
if [[ -f "${PID_FILE}" ]]; then
    OLD_PID="$(cat "${PID_FILE}" 2>/dev/null || true)"
    if [[ -n "${OLD_PID}" ]] && kill -0 "${OLD_PID}" 2>/dev/null; then
        echo "[runway] An instance is already running (supervisor PID ${OLD_PID})." >&2
        echo "       Stop it first:  $0 stop" >&2
        exit 1
    fi
    rm -f "${PID_FILE}" "${JVM_PID_FILE}"
fi

# The supervisor: relaunch the JVM on exit 42, stop on anything else. On SIGTERM
# (from `stop`) it forwards the signal to the JVM and exits without relaunching.
(
    _jvm=0
    trap '[[ ${_jvm} -ne 0 ]] && kill -TERM "${_jvm}" 2>/dev/null; rm -f "${JVM_PID_FILE}" "${PID_FILE}"; exit 143' TERM INT
    while true; do
        # shellcheck disable=SC2086
        "${JAVA_BIN}" ${JAVA_OPTS} -jar "${RUNWAY_JAR}" ${RUNWAY_OPTS} "${PASSTHRU[@]}" </dev/null >>"${LOG_FILE}" 2>&1 &
        _jvm=$!
        echo "${_jvm}" > "${JVM_PID_FILE}"
        wait "${_jvm}"; _code=$?
        if [[ ${_code} -ne ${RESTART_EXIT_CODE} ]]; then
            break
        fi
        echo "[runway] $(date '+%F %T') relaunch requested (exit ${RESTART_EXIT_CODE}) — restarting…" >>"${LOG_FILE}"
    done
    rm -f "${JVM_PID_FILE}" "${PID_FILE}"
) &
SUP_PID=$!
disown "${SUP_PID}" 2>/dev/null || true
echo "${SUP_PID}" > "${PID_FILE}"

echo "[runway] Started in background (supervisor PID=${SUP_PID}, relaunch-on-${RESTART_EXIT_CODE})"
echo "[runway] Log: ${LOG_FILE}"
echo "[runway] Stop with: $0 stop"
