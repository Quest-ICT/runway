#!/usr/bin/env bash
# =============================================================================
#  Verify the guestbook example inside a real Runway, end to end.
#
#  Boots a throwaway Runway with database, the chosen provider and runway-demo-database, places the guestbook on a
#  page, and checks: signing, the never-cached entries endpoint (a page answered 304 still shows the new entry),
#  the honeypot, the per-address limit, the console screen and its delete, and the two ways the database can be
#  missing — the database plugin absent (the example is Blocked) and the server unreachable (the page says so).
#
#  Usage:
#    PROVIDER=postgresql DB_HOST=127.0.0.1 DB_PORT=55432 DB_PASSWORD_FILE=/path/pw scripts/verify-against-host.sh
#  Needs JAVA_HOME (a JDK 25). Optional: MVN (default mvn on the PATH), DB_NAME, DB_USER (default runway), HOST_TARGET
#  (default the Runway this folder sits in, else ../runway), HOST_JAR, --keep, --port N.
#  Each run uses a fresh namespace; its schemas stay on the server, so point it at a throwaway one.
# =============================================================================
set -u
HERE="$(cd "$(dirname "$0")/.." && pwd)"
REPO="$(cd "$HERE/.." && pwd)"
# The kernel build: the Runway this plugin folder sits in (the public layout, plugins/ inside the kernel), else a
# runway checkout beside this repository.
if [ -z "${HOST_TARGET:-}" ]; then
  if [ -f "$REPO/../runway-app/pom.xml" ]; then HOST_TARGET="$REPO/../runway-app/target"
  else HOST_TARGET="$REPO/../runway/runway-app/target"; fi
fi
MVN="${MVN:-mvn}"
if [ -z "${JAVA_HOME:-}" ] || [ ! -x "$JAVA_HOME/bin/java" ]; then echo "set JAVA_HOME to a JDK 25"; exit 2; fi
export JAVA_HOME
JAVA="$JAVA_HOME/bin/java"

: "${PROVIDER:?set PROVIDER=postgresql or mariadb}"
: "${DB_HOST:?set DB_HOST}"; : "${DB_PORT:?set DB_PORT}"; : "${DB_PASSWORD_FILE:?set DB_PASSWORD_FILE}"
DB_NAME="${DB_NAME:-runway}"; DB_USER="${DB_USER:-runway}"
DB_PASSWORD="$(cat "$DB_PASSWORD_FILE")"
KEEP="no"; PORT=""
while [ $# -gt 0 ]; do
  case "$1" in
    --keep) KEEP="yes"; shift ;;
    --port) PORT="${2:?}"; shift 2 ;;
    *) echo "unknown argument $1"; exit 2 ;;
  esac
done
free_port() { python3 -I -c 'import socket;s=socket.socket();s.bind(("127.0.0.1",0));print(s.getsockname()[1]);s.close()'; }
[ -n "$PORT" ] || PORT="$(free_port)"
BASE="http://localhost:$PORT"
APP_USER=root; APP_PW=Guestbook-Admin-2026; SITE_USER=owner; SITE_PW=Guestbook-Site-2026
NS="hv$(date +%s | tail -c 9)"

echo "[verify] packaging the database plugins and this example ..."
( cd "$REPO/database" && "$MVN" -o -q -DskipTests install ) || { echo "database build failed"; exit 1; }
( cd "$REPO/database-$PROVIDER" && "$MVN" -o -q -DskipTests package ) || { echo "provider build failed"; exit 1; }
( cd "$HERE" && "$MVN" -o -q -DskipTests package ) || { echo "example build failed"; exit 1; }
if [ -n "${HOST_JAR:-}" ]; then JAR="$HOST_JAR"; else
    HOST_VERSION="$(sed -n 's:.*<version>\(.*\)</version>.*:\1:p' "$HOST_TARGET/../pom.xml" | head -1)"
    JAR="$HOST_TARGET/runway-app-$HOST_VERSION-exec.jar"
fi
[ -f "$JAR" ] || { echo "no host exec jar $JAR"; exit 1; }
echo "[verify] host jar: $JAR; provider $PROVIDER; namespace $NS"

mkdir -p "$HERE/target"
SCRATCH="$(mktemp -d "$HERE/target/guestbook-verify.XXXXXX")"
PID=""
stop_host() { [ -z "$PID" ] || { kill "$PID" 2>/dev/null; wait "$PID" 2>/dev/null; PID=""; }; }
cleanup() { stop_host; [ "$KEEP" = "yes" ] && echo "[verify] kept $SCRATCH" || rm -rf "$SCRATCH"; }
trap cleanup EXIT
wait_for() { for _ in $(seq 1 "$3"); do grep -q "$2" "$1" 2>/dev/null && return 0; sleep 1; done; return 1; }
PASS=0; FAIL=0
ck() { if eval "$2" >/dev/null 2>&1; then echo "  PASS  $1"; PASS=$((PASS+1)); else echo "  FAIL  $1"; FAIL=$((FAIL+1)); fi; }
code() { curl -s -o /dev/null -w '%{http_code}' "$@"; }
token() { grep -o 'name="_csrf" value="[^"]*"' | head -1 | sed 's/.*value="//; s/"$//'; }

EXT="$SCRATCH/external"; SITES="$EXT/sites"
mkdir -p "$EXT/plugins" "$EXT/plugin-settings"
cp -r "$REPO/database/target/database" "$EXT/plugins/database"
cp -r "$REPO/database-$PROVIDER/target/database-$PROVIDER" "$EXT/plugins/database-$PROVIDER"
cp -r "$HERE/target/runway-demo-database" "$EXT/plugins/runway-demo-database"
boot() { ( cd "$SCRATCH" && exec "$JAVA" -jar "$JAR" --server.port="$PORT" --runway.storage.external="$EXT" ) >"$1" 2>&1 & PID=$!; }
settings() {   # port. Since database 2.0.0 the server is the provider plugin's setting; the namespace stays.
    umask 077
    printf 'runway:\n  plugin:\n    database:\n      namespace: %s\n' "$NS" > "$EXT/plugin-settings/database.yml"
    printf 'runway:\n  plugin:\n    database-%s:\n      host: "%s"\n      port: %s\n      database: "%s"\n      username: "%s"\n      password: "%s"\n      tls: disable\n' \
        "$PROVIDER" "$DB_HOST" "$1" "$DB_NAME" "$DB_USER" "$DB_PASSWORD" > "$EXT/plugin-settings/database-$PROVIDER.yml"
    umask 022
}

echo "[verify] booting host (wizard) on :$PORT ..."
boot "$SCRATCH/wizard.log"
wait_for "$SCRATCH/wizard.log" "Started RunwayApplication" 90 || { echo "wizard did not start"; exit 1; }
curl -s -o /dev/null -X POST "$BASE/setup" --data-urlencode "username=$APP_USER" --data-urlencode "password=$APP_PW" \
    --data-urlencode "displayName=Root" --data-urlencode "siteAdminUsername=$SITE_USER" \
    --data-urlencode "siteAdminPassword=$SITE_PW"
for _ in $(seq 1 30); do kill -0 "$PID" 2>/dev/null || break; sleep 1; done
stop_host
T="$SITES/default/templates"
[ -f "$T/about.th.xml" ] || { echo "the wizard did not seed the default site"; exit 1; }
printf 'path: /guests\ntemplate: _master\ntitle:\n  en: "Guests"\ndescription:\n  en: "Sign our guestbook."\n' > "$T/guests.meta.yml"
printf '<!DOCTYPE html>\n<html lang="en">\n<head><title>Guests</title></head>\n<body>\n<header id="site-header"></header>\n<main id="content">\n<h1>Guests</h1>\n<div id="guestbook" data-widget="guestbook/main"></div>\n</main>\n<footer id="site-footer"></footer>\n</body>\n</html>\n' > "$T/guests.html"
cp "$T/about.th.xml" "$T/guests.th.xml"
settings "$DB_PORT"

LOG="$SCRATCH/active.log"
echo "[verify] booting host (ACTIVE) ..."
boot "$LOG"
wait_for "$LOG" "Started RunwayApplication" 90 || { echo "ACTIVE did not start"; tail -20 "$LOG"; exit 1; }
wait_for "$LOG" "database: connected to" 30

echo "== loading =="
ck "3 plugins registered, 0 blocked" "grep -qE 'plugins: 3 registered, 0 blocked' '$LOG'"
ck "no linkage or surface findings"  "! grep -qE \"plugin '(runway-demo-database|database[a-z-]*)' (linkage scan|is blocked)\" '$LOG'"

echo "== the page and signing =="
curl -s -D "$SCRATCH/page.h" "$BASE/guests" -o "$SCRATCH/page.html"
ETAG="$(grep -i '^etag:' "$SCRATCH/page.h" | head -1 | sed 's/^[^:]*: *//; s/\r$//')"
ck "the page serves the form and an empty list shell" "grep -q 'action=\"/guestbook/sign\"' '$SCRATCH/page.html' && grep -q 'data-src=\"/guestbook/entries.json\"' '$SCRATCH/page.html'"
ck "the page has an ETag" "[ -n '$ETAG' ]"
ck "the list starts empty" "curl -s '$BASE/guestbook/entries.json' | grep -q '\"entries\":\\[\\]'"
sign() { curl -s -o /dev/null -w '%{http_code} %{redirect_url}' "$BASE/guestbook/sign" --data-urlencode "return=/guests" "$@"; }
ck "signing answers 303 back to the page with thanks" "[ \"\$(sign --data-urlencode 'name=Ana' --data-urlencode 'message=Hola desde la prueba')\" = '303 $BASE/guests?guestbook=thanks' ]"
curl -s -D "$SCRATCH/entries.h" "$BASE/guestbook/entries.json" -o "$SCRATCH/entries.json"
ck "the entry is listed at once" "grep -q 'Hola desde la prueba' '$SCRATCH/entries.json'"
ck "the entries endpoint is never cached" "grep -qi '^cache-control: no-store' '$SCRATCH/entries.h'"
ck "the page itself may still answer 304 (its shell did not change)" "[ \"\$(code -H 'If-None-Match: $ETAG' '$BASE/guests')\" = 304 ]"
ck "…while the entries endpoint answers 200 with the new entry even with If-None-Match" "[ \"\$(code -H 'If-None-Match: $ETAG' '$BASE/guestbook/entries.json')\" = 200 ] && curl -s -H 'If-None-Match: $ETAG' '$BASE/guestbook/entries.json' | grep -q 'Hola desde'"
ck "the honeypot pretends success and keeps nothing" "[ \"\$(sign --data-urlencode 'name=Bot' --data-urlencode 'message=spam' --data-urlencode 'website=x')\" = '303 $BASE/guests?guestbook=thanks' ] && ! curl -s '$BASE/guestbook/entries.json' | grep -q spam"
ck "an over-long name is refused" "sign --data-urlencode \"name=\$(printf 'n%.0s' \$(seq 1 81))\" --data-urlencode 'message=x' | grep -q 'guestbook=invalid'"
for i in 2 3 4 5; do sign --data-urlencode "name=Visitor $i" --data-urlencode "message=Entry $i" > /dev/null; done
ck "the sixth entry in a minute from one address is limited" "sign --data-urlencode 'name=Six' --data-urlencode 'message=six' | grep -q 'guestbook=limited'"
ck "a CSRF-less anonymous post is the only one allowed (the console's is refused)" "[ \"\$(code -X POST '$BASE/__admin/runway-demo-database/delete' -d id=1)\" != 303 ]"

echo "== the site console =="
sc() { curl -s -H 'Accept-Language: en' -c "$SCRATCH/site.jar" -b "$SCRATCH/site.jar" "$@"; }
sc -o /dev/null -X POST "$BASE/login" --data-urlencode "username=$SITE_USER" --data-urlencode "password=$SITE_PW" \
    --data-urlencode "_csrf=$(sc "$BASE/login" | token)"
sc "$BASE/__admin/runway-demo-database" > "$SCRATCH/console.html"
ck "the console lists the entries" "grep -q 'Hola desde la prueba' '$SCRATCH/console.html' && grep -q 'Visitor 5' '$SCRATCH/console.html'"
ID="$(grep -o 'name="id" value="[0-9]*"' "$SCRATCH/console.html" | head -1 | grep -o '[0-9]*')"
sc -L "$BASE/__admin/runway-demo-database/delete" --data-urlencode "id=$ID" \
    --data-urlencode "_csrf=$(token < "$SCRATCH/console.html")" > "$SCRATCH/deleted.html"
ck "deleting from the console works" "grep -q 'Entry deleted' '$SCRATCH/deleted.html' && ! curl -s '$BASE/guestbook/entries.json' | grep -q 'Visitor 5'"
ck "the dashboard shows the site now has database data" "sc '$BASE/__admin' | grep -q 'keeps plugin data in the database'"
ck "no ERROR line names the plugins" "! grep -E ' ERROR ' '$LOG' | grep -qiE 'demodatabase|mx\\.quest\\.database'"
ck "the password appears in no log line" "! grep -qF -- '$DB_PASSWORD' '$LOG'"
stop_host

echo "== the server unreachable =="
settings 1
LOG2="$SCRATCH/unreachable.log"
boot "$LOG2"
wait_for "$LOG2" "Started RunwayApplication" 90 || { echo "did not start"; exit 1; }
ck "Runway starts anyway" "[ \"\$(code '$BASE/guests')\" = 200 ]"
ck "the entries endpoint says unavailable (503)" "[ \"\$(code '$BASE/guestbook/entries.json')\" = 503 ]"
ck "signing says unavailable" "sign --data-urlencode 'name=Ana' --data-urlencode 'message=x' | grep -q 'guestbook=unavailable'"
stop_host

echo "== the database plugin absent =="
rm -rf "$EXT/plugins/database" "$EXT/plugins/database-$PROVIDER"
LOG3="$SCRATCH/absent.log"
boot "$LOG3"
wait_for "$LOG3" "Started RunwayApplication" 90 || { echo "did not start"; exit 1; }
ck "the example is blocked with the requires reason" "grep -E 'runway-demo-database' '$LOG3' | grep -qiE 'requires|database'"
ck "the page still serves (the widget is simply absent)" "[ \"\$(code '$BASE/guests')\" = 200 ]"
ck "the example's routes are gone" "[ \"\$(code '$BASE/guestbook/entries.json')\" = 404 ]"

echo "[verify] $PASS passed, $FAIL failed (namespace $NS left on the server)"
[ "$FAIL" = 0 ]
