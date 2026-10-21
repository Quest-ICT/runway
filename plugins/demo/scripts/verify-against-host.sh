#!/usr/bin/env bash
# Build the runway-demo plugin, drop it into a THROWAWAY Runway deployment, boot the PACKAGED host in ACTIVE mode, and
# prove the demo's contract end to end:
#   - it loads with ZERO host-linkage and bean-name/surface warnings, and its PluginLifecycle hook runs;
#   - head injection, the static assets, /demo/hello.txt and /demo/echo.json (lowercase headers, clientIp, origin);
#     no timing header any more (servlet filters are outside the 1.0 plugin contract);
#   - /demo/notes.json is permission-gated (AccessCheck): 403 anonymous, 200 for the signed-in owner;
#   - a GET writes nothing: the owner opening the screen, notes.json and the settings page leaves the site with no
#     notes.yml, though the screen shows the welcome note;
#   - the anonymous feedback POST (no session, no CSRF token) is stored as a note and 303s back with only an outcome;
#     honeypot, blank, query-string fields and a non-form body are refused; a GET submits nothing;
#   - the @ConsoleScreen renders inside the console shell for the owner, is refused to an anonymous caller, and its
#     actions are CSRF-checked POSTs (403 without the token); with the token: a note is added, the starter page is
#     installed through SitePages and serves both widgets, the showcase ContentSource publishes (ContentChanges) and
#     unpublishes, and the SiteEventListener shows each change with its URLs before and after;
#   - saving the per-site settings appends the audit note (onSettingsChanged) and a per-site secret is read back
#     (PluginSettings.secret) without being shown;
#   - a FULL backup carries notes.yml and a TRANSFER copy leaves it out (siteData.personal) and lists it.
# Verify ONLY against the packaged jar — never spring-boot:run (its restart classloader loads ZERO plugins).
#
# Usage: scripts/verify-against-host.sh [--keep] [--port N]
#   JAVA_HOME=/path/to/jdk-25                     required: the JDK that builds the plugin and runs the host
#   MVN=/path/to/mvn                              Maven (default: mvn on the PATH)
#   HOST_TARGET=/path/to/runway/runway-app/target the host build (default: the kernel this plugin sits in, else ../runway)
#   HOST_JAR=/path/to/runway-app-<ver>-exec.jar  the kernel to verify against (default: the host checkout's
#                                                 runway-app/target jar MATCHING its pom version)
#   VERIFY_TMPDIR=/dir                            where the throwaway deployment goes (default: $TMPDIR or /tmp)
#   VERIFY_LOG_DIR=/dir                           keep copies of the host logs, the backup manifests and the fetched
#                                                 pages there (evidence)
# The script never runs `mvn install` (nothing is written to ~/.m2): it packages this plugin offline against whatever
# runway-spi is already in the local repository.
set -uo pipefail

HERE="$(cd "$(dirname "$(readlink -f "$0")")/.." && pwd)"
VER="$(sed -n 's/^version: *//p' "$HERE/plugin.yml")"   # the release under test
# JAVA_HOME must name a JDK 25. MVN (default: `mvn` on the PATH) and HOST_TARGET (default: see above) can be set in the environment.
if [ -z "${JAVA_HOME:-}" ] || [ ! -x "$JAVA_HOME/bin/java" ]; then echo "set JAVA_HOME to a JDK 25"; exit 2; fi
export JAVA_HOME
JAVA="$JAVA_HOME/bin/java"
MVN="${MVN:-mvn}"
# Default host: the kernel at the root of the repository this plugin sits in (plugins/<name>/), else a kernel
# checkout named `runway` beside the plugins checkout.
if [ -z "${HOST_TARGET:-}" ]; then
  if [ -f "$HERE/../../../runway-app/pom.xml" ]; then HOST_TARGET="$HERE/../../../runway-app/target"
  else HOST_TARGET="$HERE/../../runway/runway-app/target"; fi
fi

KEEP="no"; PORT=""
while [ $# -gt 0 ]; do case "$1" in
    --keep) KEEP="yes"; shift ;;
    --port) PORT="${2:?}"; shift 2 ;;
    *) echo "unknown arg: $1"; exit 2 ;;
esac; done

APP_USER=root; APP_PW=Demo-Admin-2026; SITE_USER=owner; SITE_PW=Demo-Site-2026
SECRET_KEY=demo-verify-master-key-2026
free_port() { python3 -I -c 'import socket;s=socket.socket();s.bind(("127.0.0.1",0));print(s.getsockname()[1]);s.close()'; }
[ -n "$PORT" ] || PORT="$(free_port)"
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

mkdir -p "${VERIFY_TMPDIR:-${TMPDIR:-/tmp}}"
SCRATCH="$(mktemp -d "${VERIFY_TMPDIR:-${TMPDIR:-/tmp}}/runway-demo-verify.XXXXXX")"
cleanup() {
    [ -z "${VERIFY_LOG_DIR:-}" ] || { mkdir -p "$VERIFY_LOG_DIR" \
        && cp "$SCRATCH"/*.log "$SCRATCH"/*-manifest.yml "$SCRATCH"/*.html "$SCRATCH"/*.json "$VERIFY_LOG_DIR"/ 2>/dev/null; }
    # Wait for the host to exit before deleting: a JVM still shutting down rewrites files under external/ and makes
    # `rm -rf` leave the scratch folder behind.
    [ "$KEEP" = "yes" ] || { kill "${PID:-0}" 2>/dev/null; wait "${PID:-0}" 2>/dev/null; rm -rf "$SCRATCH"; }
}
trap cleanup EXIT
EXT="$SCRATCH/external"
mkdir -p "$EXT/plugins"
cp -r "$HERE/target/runway-demo" "$EXT/plugins/runway-demo"

wait_for() { for _ in $(seq 1 "$3"); do grep -q "$2" "$1" 2>/dev/null && return 0; sleep 1; done; return 1; }
PASS=0; FAIL=0
ck() { if eval "$2" >/dev/null 2>&1; then echo "  PASS  $1"; PASS=$((PASS+1)); else echo "  FAIL  $1"; FAIL=$((FAIL+1)); fi; }
boot() { ( cd "$SCRATCH" && exec "$JAVA" -jar "$JAR" --server.port="$PORT" --runway.storage.external="$EXT" \
    --runway.security.secret-key="$SECRET_KEY" ) >"$1" 2>&1 & PID=$!; }

echo "[verify] booting host (wizard) on :$PORT ..."
boot "$SCRATCH/wizard.log"
wait_for "$SCRATCH/wizard.log" "Started RunwayApplication" 90 || { echo "wizard did not start"; tail -20 "$SCRATCH/wizard.log"; exit 1; }
curl -s -o /dev/null -X POST "$BASE/setup" --data-urlencode "username=$APP_USER" --data-urlencode "password=$APP_PW" \
    --data-urlencode "displayName=Root" --data-urlencode "siteAdminUsername=$SITE_USER" --data-urlencode "siteAdminPassword=$SITE_PW"
for _ in $(seq 1 30); do kill -0 "$PID" 2>/dev/null || break; sleep 1; done

echo "[verify] booting host (ACTIVE) ..."
boot "$SCRATCH/active.log"
wait_for "$SCRATCH/active.log" "Started RunwayApplication" 90 || { echo "ACTIVE did not start"; tail -20 "$SCRATCH/active.log"; exit 1; }
LOG="$SCRATCH/active.log"
SITES="$EXT/sites"
NOTES="$SITES/default/plugins/runway-demo/notes.yml"
code() { curl -s -o /dev/null -w '%{http_code}' "$@"; }

echo "== plugin load (SPI 0.6.0 contract) =="
ck "plugins registered (1), 0 blocked"   "grep -qE 'plugins: 1 registered, 0 blocked' '$LOG'"
ck "not blocked / quarantined"            "! grep -qE \"plugin 'runway-demo' is blocked|plugin-quarantine\" '$LOG'"
ck "ZERO host-linkage warnings"           "! grep -q 'uses host internals' '$LOG'"
ck "ZERO bean-name/surface warnings"      "! grep -qE \"plugin 'runway-demo': .*allowed until Runway 1.0\" '$LOG'"
ck "no WARN/ERROR names the plugin"       "! grep -E ' (WARN|ERROR) ' '$LOG' | grep -qiE 'runway-demo|mx.quest.demo'"
ck "no key collision / degraded"          "! grep -qiE 'collision|duplicate key|degraded' '$LOG'"
# The running SPI is the host's (0.6.0 on beta14, 1.0.0 on rc1): accept any, as the plugin itself reports both.
ck "PluginLifecycle.started ran with PluginInfo" \
   "grep -qE 'runway-demo $VER started — install dir .*runway-demo; built against SPI [0-9]+\\.[0-9]+\\.[0-9]+, running on SPI [0-9]+\\.[0-9]+\\.[0-9]+' '$LOG'"

echo "== public surfaces =="
ck "static css, script and help page served" \
   "[ \"\$(code '$BASE/__plugin/runway-demo/static/css/demo.css')\" = 200 ] && [ \"\$(code '$BASE/__plugin/runway-demo/static/js/demo-analytics.js')\" = 200 ] && [ \"\$(code '$BASE/__plugin/runway-demo/static/notes-help.html')\" = 200 ]"
curl -s -D "$SCRATCH/home-headers.txt" "$BASE/" > "$SCRATCH/home.html"
ck "head injection on / (generator meta + stylesheet)" \
   "grep -q 'name=\"generator\" content=\"Runway Demo\"' '$SCRATCH/home.html' && grep -q '/__plugin/runway-demo/static/css/demo.css' '$SCRATCH/home.html'"
ck "no X-Demo-Plugin header (the servlet filter is gone)" "! grep -qi '^X-Demo-Plugin' '$SCRATCH/home-headers.txt'"
ck "GET /demo/hello.txt = 200, localized greeting" \
   "curl -s '$BASE/demo/hello.txt' | grep -q '^Hello from the runway-demo plugin!'"
curl -s -H 'X-Test: Yes' "$BASE/demo/echo.json?q=1" > "$SCRATCH/echo.json"
ck "echo.json: lowercase header names, gated clientIp, origin, query" \
   "grep -q '\"x-test\":\"Yes\"' '$SCRATCH/echo.json' && grep -qE '\"clientIp\":\"(127.0.0.1|\\[0:0:0:0:0:0:0:1\\])\"' '$SCRATCH/echo.json' && grep -q '\"origin\":\"http://localhost:$PORT\"' '$SCRATCH/echo.json' && grep -q '\"param.q\":\"1\"' '$SCRATCH/echo.json'"
ck "GET /demo/notes.json anonymous = 403 (AccessCheck)" "[ \"\$(code '$BASE/demo/notes.json')\" = 403 ]"

echo "== a GET writes nothing: the welcome note is shown, not stored =="
JAR_C="$SCRATCH/cookies.txt"
sc() { curl -s -H 'Accept-Language: en' -c "$JAR_C" -b "$JAR_C" "$@"; }
token() { grep -o 'name="_csrf" value="[^"]*"' | head -1 | sed 's/.*value="//; s/"$//'; }
sc -o /dev/null -X POST "$BASE/login" --data-urlencode "username=$SITE_USER" --data-urlencode "password=$SITE_PW" \
    --data-urlencode "_csrf=$(sc "$BASE/login" | token)"
sc "$BASE/__admin/runway-demo" > "$SCRATCH/screen-first.html"
sc -o /dev/null "$BASE/demo/notes.json"
sc -o /dev/null "$BASE/__admin/plugins/runway-demo/settings"
ck "the owner's screen shows the welcome note" "grep -q 'this note is data the runway-demo plugin' '$SCRATCH/screen-first.html'"
ck "…yet the screen, notes.json and the settings page wrote no notes.yml" "[ ! -e '$NOTES' ]"

echo "== the anonymous feedback POST (no session, no CSRF token) =="
post() { curl -s -o /dev/null -w '%{http_code} %{redirect_url}' -X POST "$@"; }
R="$(post "$BASE/demo/feedback" --data-urlencode 'return=/demo' --data-urlencode 'message=Hello from a visitor' \
    --data-urlencode 'website=')"
ck "valid POST -> 303 back with ?demo-feedback=thanks" "[ \"\${R%% *}\" = 303 ] && echo '$R' | grep -q '/demo?demo-feedback=thanks'"
ck "the redirect carries no submitted value" "! echo '$R' | grep -qi 'visitor'"
ck "stored in sites/default/plugins/runway-demo/notes.yml as a visitor note" \
   "grep -q 'Hello from a visitor' '$NOTES' && grep -q 'origin: \"\\?visitor' '$NOTES'"
ck "a GET submits nothing (404)" "[ \"\$(code '$BASE/demo/feedback?message=via-get')\" = 404 ] && ! grep -q 'via-get' '$NOTES'"
ck "a filled honeypot fakes success and stores nothing" \
   "post '$BASE/demo/feedback' -d 'message=bot-spam&website=http://spam' | grep -q 'demo-feedback=thanks' && ! grep -q 'bot-spam' '$NOTES'"
ck "a blank message -> ?demo-feedback=invalid" "post '$BASE/demo/feedback' -d 'message=%20' | grep -q 'demo-feedback=invalid'"
ck "fields in the query string are ignored" \
   "post '$BASE/demo/feedback?message=in-the-url' -d 'x=1' | grep -q 'demo-feedback=invalid' && ! grep -q 'in-the-url' '$NOTES'"
ck "a multipart body is refused 415 by the kernel" \
   "[ \"\$(curl -s -o /dev/null -w '%{http_code}' -X POST '$BASE/demo/feedback' -F 'message=multi')\" = 415 ]"
ck "notes.yml is never served publicly" \
   "( for u in '/plugins/runway-demo/notes.yml' '/__plugin/runway-demo/static/../../notes.yml'; do curl -s --path-as-is '$BASE'\$u | grep -q 'Hello from a visitor' && exit 1; done; exit 0 )"

echo "== the @ConsoleScreen and its CSRF-checked actions =="
ck "anonymous GET /__admin/runway-demo is refused (sign-in required)" \
   "[ \"\$(code '$BASE/__admin/runway-demo')\" != 200 ]"
ck "anonymous POST without a token -> 403" "[ \"\$(code -X POST '$BASE/__admin/runway-demo/notes' -d text=x)\" = 403 ]"
# The owner signed in above (the GET-writes-nothing checks); the session is in $JAR_C.
SCREEN="$BASE/__admin/runway-demo"
sc "$SCREEN" > "$SCRATCH/screen-0.html"
ck "the owner sees the screen inside the console shell" \
   "grep -q 'id=\"op-main\"' '$SCRATCH/screen-0.html' && grep -q 'id=\"rd-heading\"' '$SCRATCH/screen-0.html' && grep -q '<title id=\"op-title\">Runway demo · ' '$SCRATCH/screen-0.html'"
ck "…showing the plugin version and both SPI versions" \
   "grep -q 'id=\"rd-version\">$VER<' '$SCRATCH/screen-0.html' && grep -qE 'id=\"rd-running\">[0-9]+\\.[0-9]+\\.[0-9]+<' '$SCRATCH/screen-0.html'"
ck "…and its actions as POST forms carrying a non-empty CSRF token" \
   "grep -q 'method=\"post\" action=\"/__admin/runway-demo/install-page\"' '$SCRATCH/screen-0.html' && grep -qE 'class=\"rd-csrf\" type=\"hidden\" name=\"_csrf\" value=\"[^\"]+\"' '$SCRATCH/screen-0.html'"
ck "the owner's POST without the token -> 403, nothing added" \
   "[ \"\$(sc -o /dev/null -w '%{http_code}' -X POST '$SCREEN/notes' -d text=forged)\" = 403 ] && ! grep -q forged '$NOTES'"
ck "a GET on an action path changes nothing" \
   "[ \"\$(sc -o /dev/null -w '%{http_code}' '$SCREEN/install-page')\" != 302 ] && [ ! -e '$SITES/default/templates/runway-demo.html' ]"
act() {   # path [curl args...] -> follows the redirect, prints the screen
    local p="$1"; shift
    # No -X POST: with -L it would also turn the redirect's follow-up GET into a POST. --data-urlencode makes it a POST.
    sc -L "$SCREEN$p" --data-urlencode "_csrf=$(sc "$SCREEN" | token)" "$@"
}
act /notes --data-urlencode 'text=An operator note' > "$SCRATCH/screen-note.html"
ck "add a note: flash, listed, stored as a console note" \
   "grep -q 'Note added.' '$SCRATCH/screen-note.html' && grep -q 'An operator note' '$SCRATCH/screen-note.html' && grep -q 'An operator note' '$NOTES'"
ck "the owner reads /demo/notes.json (200)" "[ \"\$(sc -o /dev/null -w '%{http_code}' '$BASE/demo/notes.json')\" = 200 ]"
act /install-page > "$SCRATCH/screen-page.html"
ck "install the starter page through SitePages (Advisory site -> published)" \
   "grep -q 'Demo page published at /demo.' '$SCRATCH/screen-page.html'"
curl -s "$BASE/demo" > "$SCRATCH/demo-page.html"
ck "/demo serves both widgets inside the site's chrome" \
   "grep -q 'rw-demo-notice' '$SCRATCH/demo-page.html' && grep -q 'action=\"/demo/feedback\"' '$SCRATCH/demo-page.html' && grep -q 'name=\"return\" value=\"/demo\"' '$SCRATCH/demo-page.html'"
ck "…and shows a feedback outcome from the query flag" \
   "curl -s '$BASE/demo?demo-feedback=thanks' | grep -q 'Thank you'"
act /install-page > "$SCRATCH/screen-page2.html"
ck "a second install changes nothing" "grep -q 'A demo page already exists at /demo; nothing changed.' '$SCRATCH/screen-page2.html'"
ck "the path check: /demo is owned by the published page runway-demo" \
   "sc '$SCREEN?path=/demo' | grep -q 'owned by the published page runway-demo'"
ck "the path check: /__admin is reserved" "sc '$SCREEN?path=/__admin' | grep -q 'reserved: no content may live here'"

echo "== the showcase ContentSource, ContentChanges and the SiteEventListener =="
ck "nothing at /demo/showcase before publishing" "[ \"\$(code '$BASE/demo/showcase')\" = 404 ]"
act /showcase --data-urlencode 'text=Showcase <live> text' > "$SCRATCH/screen-show.html"
ck "publish: flash and live block" \
   "grep -q 'Showcase published at /demo/showcase.' '$SCRATCH/screen-show.html' && grep -q 'Showcase &lt;live&gt; text' '$SCRATCH/screen-show.html'"
curl -s "$BASE/demo/showcase" > "$SCRATCH/showcase.html"
ck "/demo/showcase serves the escaped text inside the site's chrome" \
   "grep -q '<h1 id=\"showcase-title\">Showcase</h1>' '$SCRATCH/showcase.html' && grep -q 'Showcase &lt;live&gt; text' '$SCRATCH/showcase.html'"
ck "the content listing shows the source's entry" "grep -q '<td class=\"rd-cat-source\">runway-demo</td>' '$SCRATCH/screen-show.html'"
ck "the listener saw the CONTENT change with its URL after" \
   "grep -A4 '<td class=\"rd-act-item\">runway-demo:showcase</td>' '$SCRATCH/screen-show.html' | grep -q '<td class=\"rd-act-after\">/demo/showcase</td>'"
act /showcase/unpublish > "$SCRATCH/screen-unshow.html"
ck "unpublish: flash, and /demo/showcase is gone at once" \
   "grep -q 'Showcase unpublished.' '$SCRATCH/screen-unshow.html' && [ \"\$(code '$BASE/demo/showcase')\" = 404 ]"
ck "the listener saw the removal with the URL before (previousUrls)" \
   "grep -A3 '<td class=\"rd-act-item\">runway-demo:showcase</td>' '$SCRATCH/screen-unshow.html' | head -4 | grep -q '<td class=\"rd-act-before\">/demo/showcase</td>'"

echo "== per-site settings: the audit note (onSettingsChanged) and a secret (PluginSettings.secret) =="
SETTINGS="$BASE/__admin/plugins/runway-demo/settings"
BEFORE="$(grep -c 'Per-site settings saved at' "$NOTES" || true)"
sc -o /dev/null -X POST "$SETTINGS" --data-urlencode "notice.enabled=true" --data-urlencode "notice.style=warning" \
    --data-urlencode "notice.max-chars=280" --data-urlencode "feedback.enabled=true" \
    --data-urlencode "quality.title-warn-ratio=1.0" --data-urlencode "analytics.site-token=verify-token-42" \
    --data-urlencode "_csrf=$(sc "$SETTINGS" | token)"
CFG="$SITES/default/plugins/runway-demo.yml"
ck "the save appended one audit note" "[ \"\$(grep -c 'Per-site settings saved at' '$NOTES')\" = \$(( $BEFORE + 1 )) ]"
ck "the secret is stored ENC(...), never in clear" "grep -q 'analytics.site-token: .*ENC(' '$CFG' && ! grep -q verify-token-42 '$CFG'"
sc "$SETTINGS" > "$SCRATCH/settings.html"
ck "the admin fragment reads it back as configured, without the value" \
   "grep -q 'Per-site analytics token configured: true' '$SCRATCH/settings.html' && ! grep -q verify-token-42 '$SCRATCH/settings.html'"
ck "…and links the screen for a user who may open it (@authz.may)" "grep -q 'id=\"rd-admin-console\"' '$SCRATCH/settings.html'"
ck "the saved style applies at once" "curl -s '$BASE/demo' | grep -q 'rw-demo-notice-warning'"

echo "== backups: a full backup keeps notes.yml, a transfer copy leaves it out =="
sc -o "$SCRATCH/full.zip" "$BASE/__admin/backups/download"
sc -o "$SCRATCH/transfer.zip" "$BASE/__admin/backups/download-transfer"
zipq() {   # zip mode [suffix] -> exit status / prints
    python3 -I - "$@" <<'PY'
import sys, zipfile
z = zipfile.ZipFile(sys.argv[1]); mode = sys.argv[2]
names = z.namelist()
if mode == "has":
    sys.exit(0 if any(n.endswith(sys.argv[3]) for n in names) else 1)
if mode == "read":
    n = next(n for n in names if n.endswith(sys.argv[3])); sys.stdout.write(z.read(n).decode("utf-8"))
PY
}
zipq "$SCRATCH/full.zip" read manifest.yml > "$SCRATCH/full-manifest.yml" 2>/dev/null
zipq "$SCRATCH/transfer.zip" read manifest.yml > "$SCRATCH/transfer-manifest.yml" 2>/dev/null
ck "the full backup carries plugins/runway-demo/notes.yml with the notes" \
   "zipq '$SCRATCH/full.zip' read plugins/runway-demo/notes.yml | grep -q 'Hello from a visitor'"
ck "the transfer copy has NO plugins/runway-demo/notes.yml" "! zipq '$SCRATCH/transfer.zip' has plugins/runway-demo/notes.yml"
ck "…and its manifest lists the omission" "grep -q 'plugins/runway-demo/notes.yml' '$SCRATCH/transfer-manifest.yml'"
ck "…while the settings file travels with the secret blanked" \
   "zipq '$SCRATCH/transfer.zip' has plugins/runway-demo.yml && ! zipq '$SCRATCH/transfer.zip' read plugins/runway-demo.yml | grep -q 'ENC('"
ck "the full backup's manifest omits nothing of the demo" "! grep -q 'plugins/runway-demo/notes.yml' '$SCRATCH/full-manifest.yml'"

echo "== nothing went wrong along the way =="
ck "no WARN/ERROR names the plugin after the run" "! grep -E ' (WARN|ERROR) ' '$LOG' | grep -qiE 'runway-demo|mx.quest.demo'"
ck "no contribution failure was counted" "! grep -qiE 'contribution .*fail|listener .*threw' '$LOG'"

echo; echo "RESULT: $PASS passed, $FAIL failed"
if [ "$KEEP" = "yes" ]; then echo "[verify] kept at $BASE (scratch $SCRATCH; kill $PID to stop)"; wait "$PID"; fi
exit "$FAIL"
