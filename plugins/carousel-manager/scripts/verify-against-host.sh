#!/usr/bin/env bash
# Build this plugin, drop it into a throwaway Runway deployment, boot the PACKAGED host in ACTIVE mode, and
# smoke-test it end to end: it loads with ZERO host-linkage and bean-name/surface findings (the SPI 1.0.0
# contract), serves its static assets, and — signed in as the site owner — a slide set saved on its settings
# page shows on the console card and renders on a page carrying a data-widget anchor (the anchor is planted in
# the seeded About page before the ACTIVE boot). Verify ONLY against the packaged jar — never spring-boot:run
# (its restart classloader loads ZERO plugins).
#
# Usage: scripts/verify-against-host.sh [--keep] [--port N]
#   JAVA_HOME=/path/to/jdk-25                     required: the JDK that builds the plugin and runs the host
#   MVN=/path/to/mvn                              Maven (default: mvn on the PATH)
#   HOST_TARGET=/path/to/runway/runway-app/target the host build (default: the kernel this plugin sits in, else ../runway)
#   HOST_JAR=/path/to/runway-app-<ver>-exec.jar  the kernel to verify against (default: the HOST_TARGET jar
#                                                 MATCHING the host pom's version)
#   VERIFY_TMPDIR=/dir                            where the throwaway deployment goes (default: $TMPDIR or /tmp)
#   VERIFY_LOG_DIR=/dir                           keep copies of the host's wizard/ACTIVE logs there (evidence)
# The script never runs `mvn install` (nothing is written to ~/.m2): it packages this plugin offline against
# whatever runway-spi is already in the local repository.
set -uo pipefail

HERE="$(cd "$(dirname "$(readlink -f "$0")")/.." && pwd)"
# JAVA_HOME must name a JDK 25. MVN (default: `mvn` on the PATH) and HOST_TARGET (default: see above) can be set in the environment.
if [ -z "${JAVA_HOME:-}" ] || [ ! -x "$JAVA_HOME/bin/java" ]; then echo "set JAVA_HOME to a JDK 25"; exit 2; fi
export JAVA_HOME
JAVA="$JAVA_HOME/bin/java"
MVN="${MVN:-mvn}"
# Default host: the kernel at the root of the repository this plugin sits in (plugins/<name>/), else a kernel
# checkout named `runway` beside the plugins checkout.
if [ -z "${HOST_TARGET:-}" ]; then
  if [ -f "$HERE/../../runway-app/pom.xml" ]; then HOST_TARGET="$HERE/../../runway-app/target"
  else HOST_TARGET="$HERE/../../runway/runway-app/target"; fi
fi

KEEP="no"; PORT=""
while [ $# -gt 0 ]; do case "$1" in
    --keep) KEEP="yes"; shift ;;
    --port) PORT="${2:?}"; shift 2 ;;
    *) echo "unknown arg: $1"; exit 2 ;;
esac; done

APP_USER=root; APP_PW=Carousel-Admin-2026; SITE_USER=owner; SITE_PW=Carousel-Site-2026
[ -n "$PORT" ] || PORT="$(python3 -c 'import socket;s=socket.socket();s.bind(("",0));print(s.getsockname()[1]);s.close()')"
BASE="http://localhost:$PORT"

echo "[verify] building the plugin ..."
( cd "$HERE" && "$MVN" -o -q -DskipTests package ) || { echo "plugin build failed"; exit 1; }

# The host jar MATCHING the host pom's version — never "the newest file": every reproducible jar carries the same
# fixed timestamp, so `ls -t` cannot tell a leftover release jar from the SNAPSHOT under test.
if [ -n "${HOST_JAR:-}" ]; then
    JAR="$HOST_JAR"
else
    HOST_VERSION="$(sed -n 's:.*<version>\(.*\)</version>.*:\1:p' "$HOST_TARGET/../pom.xml" | head -1)"
    JAR="$HOST_TARGET/runway-app-$HOST_VERSION-exec.jar"
fi
[ -f "$JAR" ] || { echo "no host exec jar $JAR — build the host (mvn -pl runway-app package) or set HOST_JAR"; exit 1; }
echo "[verify] host jar: $JAR"

SCRATCH="$(mktemp -d "${VERIFY_TMPDIR:-${TMPDIR:-/tmp}}/carousel-verify.XXXXXX")"
cleanup() {
    [ -z "${VERIFY_LOG_DIR:-}" ] || { mkdir -p "$VERIFY_LOG_DIR" && cp "$SCRATCH"/*.log "$VERIFY_LOG_DIR"/ 2>/dev/null; }
    # Wait for the host to exit before deleting: a JVM still shutting down rewrites files under external/ and
    # makes `rm -rf` leave the scratch folder behind.
    [ "$KEEP" = "yes" ] || { kill "${PID:-0}" 2>/dev/null; wait "${PID:-0}" 2>/dev/null; rm -rf "$SCRATCH"; }
}
trap cleanup EXIT
mkdir -p "$SCRATCH/external/plugins"
cp -r "$HERE/target/carousel-manager" "$SCRATCH/external/plugins/carousel-manager"

wait_for() { for _ in $(seq 1 "$3"); do grep -q "$2" "$1" 2>/dev/null && return 0; sleep 1; done; return 1; }
PASS=0; FAIL=0
ck() { if eval "$2" >/dev/null 2>&1; then echo "  PASS  $1"; PASS=$((PASS+1)); else echo "  FAIL  $1"; FAIL=$((FAIL+1)); fi; }
boot() { ( cd "$SCRATCH" && exec "$JAVA" -jar "$JAR" --server.port="$PORT" --runway.storage.external="$SCRATCH/external" ) >"$1" 2>&1 & PID=$!; }

echo "[verify] booting host (wizard) on :$PORT ..."
boot "$SCRATCH/wizard.log"
wait_for "$SCRATCH/wizard.log" "Started RunwayApplication" 90 || { echo "wizard did not start"; tail -20 "$SCRATCH/wizard.log"; exit 1; }
curl -s -o /dev/null -X POST "$BASE/setup" --data-urlencode "username=$APP_USER" --data-urlencode "password=$APP_PW" \
    --data-urlencode "displayName=Root" --data-urlencode "siteAdminUsername=$SITE_USER" --data-urlencode "siteAdminPassword=$SITE_PW"
for _ in $(seq 1 30); do kill -0 "$PID" 2>/dev/null || break; sleep 1; done

# A page carrying the widget anchor: the seeded About page, edited on disk before the ACTIVE boot reads it.
ABOUT="$SCRATCH/external/sites/default/templates/about.html"
[ -f "$ABOUT" ] || { echo "the wizard did not seed $ABOUT"; exit 1; }
sed -i 's|<main id="content">|<main id="content"><div id="hero" data-widget="carousel/hero"></div>|' "$ABOUT"

echo "[verify] booting host (ACTIVE) ..."
boot "$SCRATCH/active.log"
wait_for "$SCRATCH/active.log" "Started RunwayApplication" 90 || { echo "ACTIVE did not start"; tail -20 "$SCRATCH/active.log"; exit 1; }

LOG="$SCRATCH/active.log"
echo "== plugin load (SPI 1.0.0 contract) =="
ck "plugin registered, 0 blocked"  "grep -qE 'plugins: 1 registered, 0 blocked' '$LOG'"
ck "not blocked / quarantined"     "! grep -qE \"plugin 'carousel-manager' is blocked|plugin-quarantine\" '$LOG'"
# Host linkage and the surface rules: the kernel BLOCKS such a plugin and logs "linkage scan: <reason>" / the surface
# reason; the patterns also match the warning wording ("uses host internals", "… allowed until Runway 1.0"), so
# either form fails the check.
ck "ZERO host-linkage findings"        "! grep -qE \"uses host internals|plugin 'carousel-manager' linkage scan\" '$LOG'"
ck "ZERO bean-name/surface findings"   "! grep -qE \"plugin 'carousel-manager'.*(allowed until Runway 1.0|bean name|may not contribute)\" '$LOG'"
ck "no WARN/ERROR names the plugin" "! grep -E ' (WARN|ERROR) ' '$LOG' | grep -q 'carousel'"
ck "no key collision / degraded"   "! grep -qiE 'collision|duplicate key|degraded' '$LOG'"

echo "== static assets =="
ck "carousel.css served"           "curl -s -o /dev/null -w '%{http_code}' '$BASE/__plugin/carousel-manager/static/carousel.css' | grep -q 200"
ck "carousel.js served"            "curl -s -o /dev/null -w '%{http_code}' '$BASE/__plugin/carousel-manager/static/carousel.js' | grep -q 200"
ck "help page served"              "curl -s -o /dev/null -w '%{http_code}' '$BASE/__plugin/carousel-manager/static/carousel-help.html' | grep -q 200"

echo "== settings → console card → widget (PluginSettings in the real host) =="
JAR_C="$SCRATCH/cookies.txt"
SETTINGS="$BASE/__admin/plugins/carousel-manager/settings"
c() { curl -s -H 'Accept-Language: en' -c "$JAR_C" -b "$JAR_C" "$@"; }
token() { grep -o 'name="_csrf" value="[^"]*"' | head -1 | sed 's/.*value="//; s/"$//'; }
ck "unconfigured: the anchor renders nothing" "! curl -s '$BASE/about' | grep -q 'rw-carousel'"
LOGIN_TOKEN="$(c "$BASE/login" | token)"
c -o /dev/null -X POST "$BASE/login" --data-urlencode "username=$SITE_USER" --data-urlencode "password=$SITE_PW" \
    --data-urlencode "_csrf=$LOGIN_TOKEN"
c "$SETTINGS" > "$SCRATCH/settings-1.html"
ck "settings page renders the status card (0 slides)" "grep -q 'id=\"ca-slides\">0<' '$SCRATCH/settings-1.html'"
c -o /dev/null -X POST "$SETTINGS" \
    --data-urlencode $'slides=/img/one.jpg | Fast, fresh | /menu\n/img/two.jpg | Second' \
    --data-urlencode "intervalSeconds=7" --data-urlencode "showCaptions=true" --data-urlencode "showControls=true" \
    --data-urlencode "_csrf=$(token < "$SCRATCH/settings-1.html")"
c "$SETTINGS" > "$SCRATCH/settings-2.html"
ck "after save: the status card counts 2 slides" "grep -q 'id=\"ca-slides\">2<' '$SCRATCH/settings-2.html'"
c "$BASE/__admin" > "$SCRATCH/dashboard.html"
ck "the dashboard card counts 2 slides"  "grep -q 'id=\"cc-slides\">2<' '$SCRATCH/dashboard.html'"
curl -s "$BASE/about" > "$SCRATCH/about.html"
ck "the widget renders both slides"      "grep -q 'src=\"/img/one.jpg\"' '$SCRATCH/about.html' && grep -q 'src=\"/img/two.jpg\"' '$SCRATCH/about.html'"
ck "…with the comma caption intact"      "grep -q '>Fast, fresh</figcaption>' '$SCRATCH/about.html'"
ck "…and the saved interval"             "grep -q 'data-interval=\"7\"' '$SCRATCH/about.html'"

echo; echo "RESULT: $PASS passed, $FAIL failed"
if [ "$KEEP" = "yes" ]; then echo "[verify] kept at $BASE (scratch $SCRATCH; kill $PID to stop)"; wait "$PID"; fi
exit "$FAIL"
