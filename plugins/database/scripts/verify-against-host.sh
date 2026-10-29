#!/usr/bin/env bash
# =============================================================================
#  Verify the database plugin and both providers inside a real Runway.
#
#  Boots a throwaway Runway (wizard, then ACTIVE) with database, database-postgresql and database-mariadb installed
#  and the database plugin pointed at a server you name, then checks:
#    - all three plugins load: none blocked or quarantined, no linkage or surface findings;
#    - the runtime connects (on its own thread, after startup) and the password appears in no log line;
#    - the application console's database screen answers only after login, and its running-connection test passes;
#    - a site's dashboard shows the database card.
#
#  Usage:
#    PROVIDER=postgresql DB_HOST=127.0.0.1 DB_PORT=55432 DB_PASSWORD_FILE=/path/pw scripts/verify-against-host.sh
#  Needs JAVA_HOME (a JDK 25). Optional: MVN (default mvn on the PATH), DB_NAME (default runway), DB_USER (default
#  runway), HOST_TARGET (default the Runway this folder sits in, else ../runway), HOST_JAR (default that build's
#  runway-app-<version>-exec.jar), --keep (keep the scratch folder), --port N.
#
#  The server needs an account that may create schemas (PostgreSQL) or databases named <namespace>_… (MariaDB).
#  Each run uses a fresh namespace (printed); its schemas stay on the server, so point it at a throwaway one.
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
: "${DB_HOST:?set DB_HOST}"
: "${DB_PORT:?set DB_PORT}"
: "${DB_PASSWORD_FILE:?set DB_PASSWORD_FILE}"
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
APP_USER=root; APP_PW=Database-Admin-2026; SITE_USER=owner; SITE_PW=Database-Site-2026
NS="hv$(date +%s | tail -c 9)"

echo "[verify] packaging database, database-postgresql, database-mariadb ..."
( cd "$HERE" && "$MVN" -o -q -DskipTests install ) || { echo "database build failed"; exit 1; }
( cd "$REPO/database-postgresql" && "$MVN" -o -q -DskipTests package ) || { echo "postgresql build failed"; exit 1; }
( cd "$REPO/database-mariadb" && "$MVN" -o -q -DskipTests package ) || { echo "mariadb build failed"; exit 1; }

if [ -n "${HOST_JAR:-}" ]; then
    JAR="$HOST_JAR"
else
    HOST_VERSION="$(sed -n 's:.*<version>\(.*\)</version>.*:\1:p' "$HOST_TARGET/../pom.xml" | head -1)"
    JAR="$HOST_TARGET/runway-app-$HOST_VERSION-exec.jar"
fi
[ -f "$JAR" ] || { echo "no host exec jar $JAR — build the host (mvn -pl runway-app -am package) or set HOST_JAR"; exit 1; }
echo "[verify] host jar: $JAR; provider $PROVIDER at $DB_HOST:$DB_PORT/$DB_NAME; namespace $NS"

mkdir -p "$HERE/target"
SCRATCH="$(mktemp -d "$HERE/target/database-verify.XXXXXX")"
PID=""
stop_host() { [ -z "$PID" ] || { kill "$PID" 2>/dev/null; wait "$PID" 2>/dev/null; PID=""; }; }
cleanup() { stop_host; [ "$KEEP" = "yes" ] && echo "[verify] kept $SCRATCH" || rm -rf "$SCRATCH"; }
trap cleanup EXIT

wait_for() { for _ in $(seq 1 "$3"); do grep -q "$2" "$1" 2>/dev/null && return 0; sleep 1; done; return 1; }
PASS=0; FAIL=0
ck() { if eval "$2" >/dev/null 2>&1; then echo "  PASS  $1"; PASS=$((PASS+1)); else echo "  FAIL  $1"; FAIL=$((FAIL+1)); fi; }
code() { curl -s -o /dev/null -w '%{http_code}' "$@"; }
token() { grep -o 'name="_csrf" value="[^"]*"' | head -1 | sed 's/.*value="//; s/"$//'; }

EXT="$SCRATCH/external"; LOG="$SCRATCH/active.log"
mkdir -p "$EXT/plugins" "$EXT/plugin-settings"
cp -r "$HERE/target/database" "$EXT/plugins/database"
cp -r "$REPO/database-postgresql/target/database-postgresql" "$EXT/plugins/database-postgresql"
cp -r "$REPO/database-mariadb/target/database-mariadb" "$EXT/plugins/database-mariadb"
boot() { ( cd "$SCRATCH" && exec "$JAVA" -jar "$JAR" --server.port="$PORT" --runway.storage.external="$EXT" ) >"$1" 2>&1 & PID=$!; }

echo "[verify] booting host (wizard) on :$PORT ..."
boot "$SCRATCH/wizard.log"
wait_for "$SCRATCH/wizard.log" "Started RunwayApplication" 90 || { echo "wizard did not start"; tail -20 "$SCRATCH/wizard.log"; exit 1; }
curl -s -o /dev/null -X POST "$BASE/setup" --data-urlencode "username=$APP_USER" --data-urlencode "password=$APP_PW" \
    --data-urlencode "displayName=Root" --data-urlencode "siteAdminUsername=$SITE_USER" \
    --data-urlencode "siteAdminPassword=$SITE_PW"
for _ in $(seq 1 30); do kill -0 "$PID" 2>/dev/null || break; sleep 1; done
stop_host

# A hand-written plaintext password works like an encrypted one; this file is deleted with the scratch folder.
umask 077
cat > "$EXT/plugin-settings/database.yml" <<EOF
runway:
  plugin:
    database:
      provider: $PROVIDER
      namespace: $NS
      host: "$DB_HOST"
      port: $DB_PORT
      database: "$DB_NAME"
      username: "$DB_USER"
      password: "$DB_PASSWORD"
      tls: disable
EOF
umask 022

echo "[verify] booting host (ACTIVE) ..."
boot "$LOG"
wait_for "$LOG" "Started RunwayApplication" 90 || { echo "ACTIVE did not start"; tail -20 "$LOG"; exit 1; }
wait_for "$LOG" "database: connected to" 30

echo "== plugin load =="
ck "3 plugins registered, 0 blocked"      "grep -qE 'plugins: 3 registered, 0 blocked' '$LOG'"
ck "none blocked or quarantined"          "! grep -qE \"plugin 'database[a-z-]*' is blocked|plugin-quarantine\" '$LOG'"
ck "ZERO host-linkage findings"           "! grep -qE \"plugin 'database[a-z-]*' linkage scan\" '$LOG'"
ck "ZERO bean-name/surface findings"      "! grep -qE \"plugin 'database[a-z-]*'.*(bean name|may not contribute)\" '$LOG'"
ck "no missing class"                      "! grep -qiE 'NoClassDefFound|ClassNotFound' '$LOG'"
ck "HikariCP and the drivers are in lib/" "ls '$EXT/plugins/database/lib/'HikariCP-*.jar '$EXT/plugins/database-postgresql/lib/'postgresql-*.jar '$EXT/plugins/database-mariadb/lib/'mariadb-java-client-*.jar"

echo "== connection =="
ck "connected after startup, on its own thread" "grep -E 'database: connected to' '$LOG' | grep -qv '\\[main\\]'"
ck "the password appears in no log line"   "! grep -qF -- '$DB_PASSWORD' '$LOG' '$SCRATCH/wizard.log'"

echo "== application console =="
ck "the screen needs a login"              "[ \"\$(code '$BASE/__app/database')\" = 302 ]"
ac() { curl -s -H 'Accept-Language: en' -c "$SCRATCH/app.jar" -b "$SCRATCH/app.jar" "$@"; }
ac -o /dev/null -X POST "$BASE/app/login" --data-urlencode "username=$APP_USER" --data-urlencode "password=$APP_PW" \
    --data-urlencode "_csrf=$(ac "$BASE/app/login" | token)"
ac "$BASE/__app/database" > "$SCRATCH/screen.html"
ck "the screen renders for the master"     "grep -q 'Connected.' '$SCRATCH/screen.html' && grep -q '$NS' '$SCRATCH/screen.html'"
ck "the screen holds no password"          "! grep -qF -- '$DB_PASSWORD' '$SCRATCH/screen.html'"
ac -L "$BASE/__app/database/test-running" --data-urlencode "_csrf=$(token < "$SCRATCH/screen.html")" \
    > "$SCRATCH/tested.html"
ck "Test running connection: connected"   "grep -qE 'Connected: (PostgreSQL|MariaDB)' '$SCRATCH/tested.html'"
ck "a POST without the CSRF token is refused" "[ \"\$(code -b '$SCRATCH/app.jar' -X POST '$BASE/__app/database/test-running')\" = 403 ]"

echo "== site console =="
sc() { curl -s -H 'Accept-Language: en' -c "$SCRATCH/site.jar" -b "$SCRATCH/site.jar" "$@"; }
sc -o /dev/null -X POST "$BASE/login" --data-urlencode "username=$SITE_USER" --data-urlencode "password=$SITE_PW" \
    --data-urlencode "_csrf=$(sc "$BASE/login" | token)"
ck "the site dashboard shows the database card" "sc '$BASE/__admin' | grep -q 'keeps no data in the database'"
ck "a site admin cannot open the application screen" "[ \"\$(code -b '$SCRATCH/site.jar' '$BASE/__app/database')\" != 200 ]"

ck "no ERROR line names the database plugin" "! grep -E ' ERROR ' '$LOG' | grep -qiE 'mx\\.quest\\.database|database:'"

echo "[verify] $PASS passed, $FAIL failed (namespace $NS left on the server)"
[ "$FAIL" = 0 ]
