#!/usr/bin/env bash
# Build this plugin, drop it into a throwaway Runway deployment, boot the PACKAGED host in ACTIVE mode, and prove
# this release's contract end to end:
#   - it loads with ZERO host-linkage and bean-name/surface warnings, and its app-level settings bind;
#   - the JSON API serves results anonymously; there is no plugin-owned /search page until one is installed;
#   - the three operator actions are CSRF-protected POSTs: a GET (a cross-site link or <img>) changes nothing even
#     for a signed-in owner, a POST without the console's CSRF token is refused 403, and an anonymous caller holding
#     a token is still refused by the permission check (404);
#   - signed in as the site owner: the rebuild-index and install-page buttons are POST forms carrying the token;
#     the starter page installs through SitePages on an Advisory, an Enforced and a Reviewed site (each outcome
#     reported), renders results inline, and a second install changes nothing;
#   - the `exclude` setting reaches the index on the save (through the kernel's change event), without a reindex;
#   - searches with no results: off by default, recorded once turned on (email-like terms never), written to the
#     plugin's own per-site file; a FULL backup carries no-results.tsv and a TRANSFER copy leaves it out and lists it
#     in its manifest; the clear POST is CSRF-checked;
#   - the API's rate limit keys on the kernel-gated client IP: made-up X-Forwarded-For headers do not evade it;
#   - the result highlighter: result links (page and API) carry #rw-hl=<words>, every page carries the loader
#     script stamped with the nonce its CSP allows, and the loader, highlight.js and highlight.css are served;
#   - the installed results page is never among its own results (2.1.1).
# The deployment holds three sites copied from the wizard's default one before the ACTIVE boot: default (Advisory),
# enforced, reviewed — addressed by Host name through curl --resolve. Verify ONLY against the packaged jar — never
# spring-boot:run (its restart classloader loads ZERO plugins).
#
# Usage: scripts/verify-against-host.sh [--keep] [--port N]
#   JAVA_HOME=/path/to/jdk-25                     required: the JDK that builds the plugin and runs the host
#   MVN=/path/to/mvn                              Maven (default: mvn on the PATH)
#   HOST_TARGET=/path/to/runway/runway-app/target the host build (default: the kernel this plugin sits in, else ../runway)
#   HOST_JAR=/path/to/runway-app-<ver>-exec.jar  the kernel to verify against (default: the host checkout's
#                                                 runway-app/target jar MATCHING its pom version)
#   VERIFY_TMPDIR=/dir                            where the throwaway deployment goes (default: $TMPDIR or /tmp)
#   VERIFY_LOG_DIR=/dir                           keep copies of the host logs, the install answers and the two
#                                                 backup manifests there (evidence)
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

APP_USER=root; APP_PW=Search-Admin-2026; SITE_USER=owner; SITE_PW=Search-Site-2026
PER_MINUTE=25
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

SCRATCH="$(mktemp -d "${VERIFY_TMPDIR:-${TMPDIR:-/tmp}}/search-verify.XXXXXX")"
cleanup() {
    [ -z "${VERIFY_LOG_DIR:-}" ] || { mkdir -p "$VERIFY_LOG_DIR" \
        && cp "$SCRATCH"/*.log "$SCRATCH"/*-manifest.yml "$SCRATCH"/install-*.txt "$VERIFY_LOG_DIR"/ 2>/dev/null; }
    # Wait for the host to exit before deleting: a JVM still shutting down rewrites files under external/ (the
    # no-result list is flushed on shutdown) and makes `rm -rf` leave the scratch folder behind.
    [ "$KEEP" = "yes" ] || { kill "${PID:-0}" 2>/dev/null; wait "${PID:-0}" 2>/dev/null; rm -rf "$SCRATCH"; }
}
trap cleanup EXIT
EXT="$SCRATCH/external"
mkdir -p "$EXT/plugins"
cp -r "$HERE/target/search" "$EXT/plugins/search"

wait_for() { for _ in $(seq 1 "$3"); do grep -q "$2" "$1" 2>/dev/null && return 0; sleep 1; done; return 1; }
PASS=0; FAIL=0
ck() { if eval "$2" >/dev/null 2>&1; then echo "  PASS  $1"; PASS=$((PASS+1)); else echo "  FAIL  $1"; FAIL=$((FAIL+1)); fi; }
# The shipped application.yml trusts loopback as a same-host reverse proxy. This deployment trusts only a TEST-NET
# address instead, so curl on loopback is an untrusted peer: a forwarded header it sends must be ignored.
boot() { ( cd "$SCRATCH" && exec "$JAVA" -jar "$JAR" --server.port="$PORT" --runway.storage.external="$EXT" \
    --runway.proxy.trusted-cidrs=192.0.2.1/32 ) >"$1" 2>&1 & PID=$!; }

echo "[verify] booting host (wizard) on :$PORT ..."
boot "$SCRATCH/wizard.log"
wait_for "$SCRATCH/wizard.log" "Started RunwayApplication" 90 || { echo "wizard did not start"; tail -20 "$SCRATCH/wizard.log"; exit 1; }
curl -s -o /dev/null -X POST "$BASE/setup" --data-urlencode "username=$APP_USER" --data-urlencode "password=$APP_PW" \
    --data-urlencode "displayName=Root" --data-urlencode "siteAdminUsername=$SITE_USER" --data-urlencode "siteAdminPassword=$SITE_PW"
for _ in $(seq 1 30); do kill -0 "$PID" 2>/dev/null || break; sleep 1; done

# --- Two more sites, copied from the seeded default one (same owner), before the ACTIVE boot reads them ----------
SITES="$EXT/sites"
[ -f "$SITES/default/site.yml" ] || { echo "the wizard did not seed $SITES/default"; exit 1; }
make_site() {   # id editorial-level
    cp -r "$SITES/default" "$SITES/$1"
    sed -i -e "s/^id: .*/id: \"$1\"/" -e "s/^- \"localhost\"/- \"$1.test\"/" -e "s/^editorial: .*/editorial: \"$2\"/" \
        "$SITES/$1/site.yml"
}
make_site enforced enforced
make_site reviewed reviewed
sed -i 's/^  - "default"$/  - "default"\n  - "enforced"\n  - "reviewed"/' "$EXT/site-users.yml"
# A bilingual page whose visible copy comes ONLY from the site's bundles (decoupled th:text): the template holds an
# empty element. The kernel indexes the page as rendered in each language, and search searches each language's
# own documents. French is a site language too, for the visitor strings the plugin ships in French.
sed -i '/^locales:/a - "es"' "$SITES/default/site.yml"
sed -i '/^locales:/a - "fr"' "$SITES/default/site.yml"
T="$SITES/default/templates"
cat > "$T/faculty.html" <<'HTML'
<!DOCTYPE html><html><head><title>Faculty</title></head>
<body><main id="content"><h1>Faculty</h1><p id="faculty-copy"></p></main></body></html>
HTML
cat > "$T/faculty.th.xml" <<'XML'
<?xml version="1.0"?>
<thlogic>
  <attr sel="#faculty-copy" th:text="#{verify.faculty}"/>
</thlogic>
XML
printf 'path: /faculty\nlocalizedPaths:\n  es: /facultad\nlocales: [en, es]\ntitle:\n  en: Faculty\n  es: Profesores\n' > "$T/faculty.meta.yml"
# A page carrying the search box in French as well (a French site's results page), to prove the plugin's French
# visitor strings are used there rather than English.
cat > "$T/recherche.html" <<'HTML'
<!DOCTYPE html><html><head><title>Recherche</title></head>
<body><main id="content"><div id="fr-search" data-widget="search/box"></div></main></body></html>
HTML
printf 'path: /find\nlocalizedPaths:\n  fr: /recherche\nlocales: [en, fr]\ntitle:\n  en: Find\n  fr: Recherche\n' > "$T/recherche.meta.yml"
mkdir -p "$SITES/default/i18n"
printf '\nverify.faculty=Our students learn from a faculty of researchers.\n' >> "$SITES/default/i18n/messages.properties"
printf '\nverify.faculty=Nuestros alumnos aprenden con una facultad de excelencia.\n' >> "$SITES/default/i18n/messages_es.properties"

# The app-level settings, in the file the plugins screen writes: a low per-minute ceiling the last section floods.
mkdir -p "$EXT/plugin-settings"
cat > "$EXT/plugin-settings/search.yml" <<YML
runway:
  plugin:
    search:
      refresh-minutes: 60
      "rate-limit.per-ip-per-minute": $PER_MINUTE
      "rate-limit.per-ip-per-hour": 100000
YML

echo "[verify] booting host (ACTIVE) ..."
boot "$SCRATCH/active.log"
wait_for "$SCRATCH/active.log" "Started RunwayApplication" 90 || { echo "ACTIVE did not start"; tail -20 "$SCRATCH/active.log"; exit 1; }
LOG="$SCRATCH/active.log"

echo "== plugin load (SPI 0.6.0 contract) =="
ck "plugins registered (1), 0 blocked"  "grep -qE 'plugins: 1 registered, 0 blocked' '$LOG'"
ck "not blocked / quarantined"          "! grep -qE \"plugin 'search' is blocked|plugin-quarantine\" '$LOG'"
# Host linkage and the surface rules, in both kernels' words: SPI 0.6.0 WARNed ("uses host internals", "… allowed until
# Runway 1.0"); from SPI 1.0.0 the kernel BLOCKS and logs "linkage scan: <reason>" / the surface reason — a grep for the
# 0.6.0 wording alone can never fail on a 1.0 kernel.
ck "ZERO host-linkage findings"        "! grep -qE \"uses host internals|plugin 'search' linkage scan\" '$LOG'"
ck "ZERO bean-name/surface findings"   "! grep -qE \"plugin 'search'.*(allowed until Runway 1.0|bean name|may not contribute)\" '$LOG'"
ck "no WARN/ERROR names the plugin"     "! grep -E ' (WARN|ERROR) ' '$LOG' | grep -qiE \"plugin 'search'|mx\\.quest\\.search\""
ck "no key collision / degraded"        "! grep -qiE 'collision|duplicate key|degraded' '$LOG'"

echo "== static assets =="
ck "search.css served"  "curl -s -o /dev/null -w '%{http_code}' '$BASE/__plugin/search/static/search.css' | grep -q 200"
ck "help page served"   "curl -s -o /dev/null -w '%{http_code}' '$BASE/__plugin/search/static/search-help.html' | grep -q 200"
ck "highlight.js served as JavaScript" \
   "curl -s -D - -o /dev/null '$BASE/__plugin/search/static/highlight.js' | grep -qiE '^content-type: (text|application)/javascript'"
ck "highlight.css served"  "curl -s -o /dev/null -w '%{http_code}' '$BASE/__plugin/search/static/highlight.css' | grep -q 200"

PLUGIN_VERSION="$(sed -n 's/^version: //p' "$HERE/plugin.yml")"
echo "== the result highlighter (since 2.1.0) =="
if command -v node >/dev/null 2>&1; then
    ck "the page scripts parse (node --check)" "node --check '$HERE/static/highlight.js' && node --check '$HERE/static/highlight-loader.js'"
    ck "the word pattern folds as the server lowercases (Turkish İ, accents, symbols)" \
       "node '$HERE/src/test/js/highlight-pattern.js' '$HERE/static/highlight.js'"
else
    echo "  SKIP  node not found: the page scripts were not parsed"
fi
curl -s -D "$SCRATCH/home.headers" "$BASE/" > "$SCRATCH/home.html"
ck "every page carries the loader script, stamped with the nonce the kernel's CSP allows" \
   "n=\$(grep -oE '<script [^>]*src=\"/__plugin/search/static/highlight-loader.js[?]v=[0-9.]+\"[^>]*>' '$SCRATCH/home.html' | grep -oE 'nonce=\"[^\"]+\"' | sed -E 's/nonce=\"(.*)\"/\\1/'); [ -n \"\$n\" ] && grep -i '^content-security-policy' '$SCRATCH/home.headers' | grep -q \"nonce-\$n\""
ck "plugin files are cacheable with a content ETag" \
   "curl -s -D - -o /dev/null '$BASE/__plugin/search/static/highlight-loader.js?v=$PLUGIN_VERSION' | grep -qiE '^etag: '"
ck "highlight-loader.js served as JavaScript" \
   "curl -s -D - -o /dev/null '$BASE/__plugin/search/static/highlight-loader.js' | grep -qiE '^content-type: (text|application)/javascript'"

echo "== the public surfaces (anonymous) =="
code() { curl -s -o /dev/null -w '%{http_code}' "$@"; }
ck "no /search page until one is installed (404)" "[ \"\$(code '$BASE/search?q=about')\" = 404 ]"
curl -s "$BASE/search/api?q=about" > "$SCRATCH/api-1.json"
ck "the JSON API serves results from the catalog" \
   "grep -q '\"query\":\"about\"' '$SCRATCH/api-1.json' && grep -q '\"url\":\"/about\"' '$SCRATCH/api-1.json'"
ck "…each result with its matched words and a link carrying them (2.1.0)" \
   "grep -q '\"link\":\"/about#rw-hl=about\"' '$SCRATCH/api-1.json' && grep -q '\"terms\":\\[\"about\"' '$SCRATCH/api-1.json'"
ck "the JSON API answers JSON" \
   "curl -s -D - -o /dev/null '$BASE/search/api?q=about' | grep -qi '^content-type: application/json'"

echo "== every language searches its own text, bundle strings included =="
ck "the default site speaks Spanish too (fixture)" "grep -q '^- \"es\"' '$SITES/default/site.yml'"
ck "the page's copy is rendered from the bundle (fixture)" \
   "curl -s '$BASE/es/facultad' | grep -q 'facultad de excelencia'"
curl -s "$BASE/search/api?q=researchers&lang=en" > "$SCRATCH/api-en.json"
curl -s "$BASE/search/api?q=excelencia&lang=es" > "$SCRATCH/api-es.json"
ck "an English bundle string is searchable in English, at the English address" \
   "grep -q '\"url\":\"/faculty\"' '$SCRATCH/api-en.json'"
ck "a Spanish bundle string is searchable in Spanish, at the Spanish address, with the Spanish title" \
   "grep -q '\"url\":\"/es/facultad\"' '$SCRATCH/api-es.json' && grep -q '\"title\":\"Profesores\"' '$SCRATCH/api-es.json'"
ck "…and its snippet is the Spanish text (JSON writes < as \\u003c)" \
   "grep -qF 'facultad de \u003cmark\u003eexcelencia' '$SCRATCH/api-es.json'"
ck "English does not search the Spanish text" \
   "! curl -s '$BASE/search/api?q=excelencia&lang=en' | grep -q '/faculty'"
ck "Spanish does not search the English text" \
   "! curl -s '$BASE/search/api?q=researchers&lang=es' | grep -q 'facultad'"
ck "a page in the default language only is still found from Spanish (its primary document)" \
   "curl -s '$BASE/search/api?q=about&lang=es' | grep -q '\"url\":\"/about\"'"

echo "== the operator actions are CSRF-protected POSTs (a link or an <img> changes nothing) =="
token() { grep -o 'name="_csrf" value="[^"]*"' | head -1 | sed 's/.*value="//; s/"$//'; }
site_url() { [ "$1" = default ] && echo "$BASE" || echo "http://$1.test:$PORT"; }
sc() {   # site curl-args...  (one cookie jar per site host; --resolve maps <site>.test to the local host)
    local site="$1"; shift
    curl -s -H 'Accept-Language: en' --resolve "$site.test:$PORT:127.0.0.1" \
        -c "$SCRATCH/cookies-$site.txt" -b "$SCRATCH/cookies-$site.txt" "$@"
}
login() {
    local u; u="$(site_url "$1")"
    sc "$1" -o /dev/null -X POST "$u/login" --data-urlencode "username=$SITE_USER" --data-urlencode "password=$SITE_PW" \
        --data-urlencode "_csrf=$(sc "$1" "$u/login" | token)"
}
no_page() { ! ls "$SITES/$1/templates"/search.* >/dev/null 2>&1; }
ck "anonymous GET of reindex / install-page -> 404 (no GET route)" \
   "[ \"\$(code '$BASE/search/reindex')\" = 404 ] && [ \"\$(code '$BASE/search/install-page')\" = 404 ]"
ck "anonymous POST without a CSRF token -> 403 (the tenant chain's CSRF check)" \
   "[ \"\$(code -X POST '$BASE/search/reindex' -d x=1)\" = 403 ] && [ \"\$(code -X POST '$BASE/search/install-page' -d x=1)\" = 403 ] && [ \"\$(code -X POST '$BASE/search/no-results/clear' -d x=1)\" = 403 ]"
ANON_JAR="$SCRATCH/cookies-anon.txt"
ANON_TOKEN="$(curl -s -c "$ANON_JAR" -b "$ANON_JAR" "$BASE/login" | token)"
ck "anonymous POST WITH a valid session token -> 404 (the permission check still applies)" \
   "[ -n '$ANON_TOKEN' ] && [ \"\$(code -c '$ANON_JAR' -b '$ANON_JAR' -X POST '$BASE/search/install-page' --data-urlencode '_csrf=$ANON_TOKEN')\" = 404 ] && [ \"\$(code -c '$ANON_JAR' -b '$ANON_JAR' -X POST '$BASE/search/reindex' --data-urlencode '_csrf=$ANON_TOKEN')\" = 404 ]"
ck "…and nothing was installed by any of that" "no_page default"
for site in default enforced reviewed; do login "$site"; done
ck "signed-in owner: GET /search/install-page -> 404 and no page (a cross-site link installs nothing)" \
   "[ \"\$(sc default -o /dev/null -w '%{http_code}' '$BASE/search/install-page')\" = 404 ] && no_page default"
ck "signed-in owner: GET /search/reindex -> 404" \
   "[ \"\$(sc default -o /dev/null -w '%{http_code}' '$BASE/search/reindex')\" = 404 ]"
ck "signed-in owner: POST without the CSRF token -> 403 and no page" \
   "[ \"\$(sc default -o /dev/null -w '%{http_code}' -X POST '$BASE/search/install-page' -d x=1)\" = 403 ] && no_page default"
ck "signed-in owner: POST with a forged CSRF token -> 403 and no page" \
   "[ \"\$(sc default -o /dev/null -w '%{http_code}' -X POST '$BASE/search/install-page' -d _csrf=forged-token)\" = 403 ] && no_page default"
SETTINGS="$BASE/__admin/plugins/search/settings"
sc default "$SETTINGS" > "$SCRATCH/settings-0.html"
ck "the settings page offers the install as a POST form carrying a non-empty CSRF token" \
   "grep -q 'id=\"sp-install-form\" method=\"post\" action=\"/search/install-page\"' '$SCRATCH/settings-0.html' && grep -qE 'id=\"sp-csrf\" type=\"hidden\" name=\"_csrf\" value=\"[^\"]+\"' '$SCRATCH/settings-0.html'"
ck "…and the index rebuild as a POST form carrying a non-empty CSRF token" \
   "grep -q 'id=\"sr-reindex-form\" method=\"post\" action=\"/search/reindex\"' '$SCRATCH/settings-0.html' && grep -qE 'id=\"sr-csrf\" type=\"hidden\" name=\"_csrf\" value=\"[^\"]+\"' '$SCRATCH/settings-0.html'"
ck "…and no link points at either action" "! grep -qE 'href=\"/search/(install-page|reindex)' '$SCRATCH/settings-0.html'"
ck "…and counts the searchable items (pages and content items)" "grep -qE 'id=\"sa-pages\">[1-9][0-9]*<' '$SCRATCH/settings-0.html'"
sc default -X POST "$BASE/search/reindex" --data-urlencode "_csrf=$(token < "$SCRATCH/settings-0.html")" > "$SCRATCH/reindex.txt"
ck "POST /search/reindex with the page's token rebuilds and reports the count" \
   "grep -qE '^Search index rebuilt\. \([1-9][0-9]*\)$' '$SCRATCH/reindex.txt'"

echo "== the starter page through SitePages (Advisory, Enforced, Reviewed) =="
install() {   # site -> the install action's answer, POSTed with the token from that site's settings page
    local u; u="$(site_url "$1")"
    sc "$1" -X POST "$u/search/install-page" --data-urlencode "_csrf=$(sc "$1" "$u/__admin/plugins/search/settings" | token)"
}
install default > "$SCRATCH/install-default.txt"
ck "Advisory: POST with the token installs and publishes at /search" "grep -qx 'Search page installed at /search' '$SCRATCH/install-default.txt'"
curl -s -D "$SCRATCH/search-page.headers" "$BASE/search?q=about" > "$SCRATCH/search-page.html"
ck "…the installed page renders the box and the results inline" \
   "grep -q 'class=\"rw-search-box\"' '$SCRATCH/search-page.html' && grep -q 'href=\"/about#rw-hl=about\"' '$SCRATCH/search-page.html'"
ck "…and is noindex (a search-results page is the canonical index opt-out)" \
   "grep -qi 'noindex' '$SCRATCH/search-page.html' '$SCRATCH/search-page.headers'"
curl -s -L -D "$SCRATCH/search-page-fr.headers" "$BASE/fr/recherche?q=zqxjvk" > "$SCRATCH/search-page-fr.html"
grep -q 'placeholder="Rechercher…"' "$SCRATCH/search-page-fr.html" || { echo "        [debug] French response:"; grep -iE "^(HTTP|location)" "$SCRATCH/search-page-fr.headers" | sed "s/^/          /"; grep -o -E "(placeholder=\"[^\"]*\"|<html[^>]*>)" "$SCRATCH/search-page-fr.html" | head -3 | sed "s/^/          /"; }
ck "…a French visitor sees the plugin's French strings (no English fallback)" \
   "grep -q 'placeholder=\"Rechercher…\"' '$SCRATCH/search-page-fr.html' && ! grep -q 'placeholder=\"Search…\"' '$SCRATCH/search-page-fr.html'"
ck "…and is never among its own results, though its title matches the query (2.1.1)" \
   "grep -qiE '<title[^>]*>[^<]*search' '$SCRATCH/search-page.html' && ! curl -s '$BASE/search/api?q=search' | grep -q '\"url\":\"/search\"'"
install default > "$SCRATCH/install-again.txt"
ck "a second install changes nothing and says where the page is" "grep -qx 'A search page already exists at /search' '$SCRATCH/install-again.txt'"
for site in enforced reviewed; do
    install "$site" > "$SCRATCH/install-$site.txt"
    echo "        $site says: $(head -c 300 "$SCRATCH/install-$site.txt" | tr '\n' ' ')"
done
ck "Enforced: a documented outcome, never an error" \
   "grep -qE '^(Search page installed at /search|The search page was created as a draft because the quality checks blocked publishing; fix it in the page editor: /search)' '$SCRATCH/install-enforced.txt'"
ck "Reviewed (as the owner, who may approve): a documented outcome" \
   "grep -qE '^(Search page installed at /search|The search page was created and is waiting in the review queue: /search|The search page was created as a draft)' '$SCRATCH/install-reviewed.txt'"
ck "the installed pages exist on every site" \
   "( for s in default enforced reviewed; do ls \"$SITES/\$s/templates\"/search.* >/dev/null || exit 1; done )"
sc default "$SETTINGS" > "$SCRATCH/settings-1.html"
ck "the settings page shows the installed page's live path" "grep -q 'id=\"sp-path\"[^>]*>/search<' '$SCRATCH/settings-1.html'"

echo "== settings: the exclude list reaches the index on the save; no-result terms are opt-in =="
NR_FILE="$SITES/default/plugins/search/no-results.tsv"
curl -s -o /dev/null "$BASE/search/api?q=zebra%20crossing%20xyzzy"
ck "off by default: the card says so and a no-result search is not recorded" \
   "grep -q 'id=\"nr-off\"' '$SCRATCH/settings-1.html' && ! sc default '$SETTINGS' | grep -q 'zebra crossing xyzzy'"
sc default -o /dev/null -X POST "$SETTINGS" --data-urlencode "enabled=true" --data-urlencode "exclude=about" \
    --data-urlencode "recordNoResults=true" --data-urlencode "_csrf=$(token < "$SCRATCH/settings-1.html")"
ck "the settings save is stored (exclude + recording on)" \
   "grep -q 'about' '$SITES/default/plugins/search.yml' && grep -q 'recordNoResults: true' '$SITES/default/plugins/search.yml'"
ck "an excluded page leaves the results at once (no reindex)" "! curl -s '$BASE/search/api?q=about' | grep -q '\"url\":\"/about\"'"
curl -s -o /dev/null "$BASE/search/api?q=Zebra%20%20Crossing%20XYZZY"
curl -s -o /dev/null "$BASE/search/api?q=someone%40example.com"
sc default "$SETTINGS" > "$SCRATCH/settings-2.html"
ck "on: the normalized no-result term is listed"  "grep -q '>zebra crossing xyzzy<' '$SCRATCH/settings-2.html'"
ck "on: an email-like term is never kept"         "! grep -q 'someone@example.com' '$SCRATCH/settings-2.html'"
echo "        waiting for the background flush of the no-result list (every 30 s) ..."
for _ in $(seq 1 45); do [ -s "$NR_FILE" ] && break; sleep 1; done
ck "the list is written to the plugin's own per-site file" "grep -q '^zebra crossing xyzzy' '$NR_FILE'"

echo "== backups: a full backup keeps no-results.tsv, a transfer copy leaves it out =="
sc default -o "$SCRATCH/full.zip" "$BASE/__admin/backups/download"
sc default -o "$SCRATCH/transfer.zip" "$BASE/__admin/backups/download-transfer"
zipq() {   # zip mode -> prints what was asked
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
ck "the full backup carries plugins/search/no-results.tsv with the terms" \
   "zipq '$SCRATCH/full.zip' read plugins/search/no-results.tsv | grep -q 'zebra crossing xyzzy'"
ck "the transfer copy has NO plugins/search/no-results.tsv" "! zipq '$SCRATCH/transfer.zip' has plugins/search/no-results.tsv"
ck "…and its manifest lists the omission" "grep -q 'plugins/search/no-results.tsv' '$SCRATCH/transfer-manifest.yml'"
ck "…while the settings file travels" "zipq '$SCRATCH/transfer.zip' has plugins/search.yml"
ck "the full backup's manifest omits nothing of search" "! grep -q 'plugins/search/no-results.tsv' '$SCRATCH/full-manifest.yml'"

echo "== clearing the no-result list (CSRF-checked POST) =="
ck "clear without the CSRF token is refused (403)" \
   "[ \"\$(sc default -o /dev/null -w '%{http_code}' -X POST '$BASE/search/no-results/clear')\" = 403 ]"
ck "a GET does not clear (404)" "[ \"\$(sc default -o /dev/null -w '%{http_code}' '$BASE/search/no-results/clear')\" = 404 ]"
ck "clear with the page's token redirects back to the settings (303)" \
   "[ \"\$(sc default -o /dev/null -w '%{http_code}' -X POST '$BASE/search/no-results/clear' --data-urlencode \"_csrf=\$(token < '$SCRATCH/settings-2.html')\")\" = 303 ]"
ck "the list and the file are gone" "! sc default '$SETTINGS' | grep -q 'zebra crossing xyzzy' && [ ! -e '$NR_FILE' ]"

echo "== a Messages save reaches the index at once (no reindex) =="
login default
MSG_TOKEN="$(sc default "$BASE/__admin/messages" | token)"
ck "the Messages save is accepted" \
   "sc default -X POST '$BASE/__admin/messages/apply' -H 'Content-Type: application/json' -H \"X-CSRF-TOKEN: \$MSG_TOKEN\" \
      --data '{\"cells\":[{\"key\":\"verify.faculty\",\"locale\":\"en\",\"value\":\"Our students learn from astronomers.\"}]}' \
      | grep -q '\"saved\":1'"
ck "the new English string is found by the next query" \
   "curl -s '$BASE/search/api?q=astronomers&lang=en' | grep -q '\"url\":\"/faculty\"'"
ck "…and the old one is gone" "! curl -s '$BASE/search/api?q=researchers&lang=en' | grep -q '/faculty'"

echo "== the API's rate limit keys on the kernel-gated client IP =="
# Every request below comes from ::1 (an untrusted peer here) and carries a different made-up X-Forwarded-For. The
# kernel's trusted-proxy gate ignores the header, so all of them share the ::1 key and the ceiling applies; a request
# from 127.0.0.1 is another client with its own budget.
V6="http://[::1]:$PORT"; V4="http://127.0.0.1:$PORT"
LIMITED=0
for i in $(seq 1 $((PER_MINUTE + 5))); do
    c="$(curl -s -o /dev/null -w '%{http_code}' -H "Host: localhost:$PORT" \
        -H "X-Forwarded-For: 10.9.$((i / 250)).$((i % 250))" "$V6/search/api?q=rate$i")"
    [ "$c" = 429 ] && LIMITED=$((LIMITED + 1))
done
ck "made-up X-Forwarded-For headers do not evade the per-minute ceiling of $PER_MINUTE ($LIMITED of $((PER_MINUTE + 5)) answered 429)" \
   "[ $LIMITED -ge 5 ]"
ck "…the 429 is the API's JSON answer" \
   "curl -s -H 'Host: localhost:$PORT' '$V6/search/api?q=again' | grep -qx '{\"error\":\"rate_limited\"}'"
ck "another client address has its own budget (200)" \
   "[ \"\$(curl -s -o /dev/null -w '%{http_code}' -H 'Host: localhost:$PORT' '$V4/search/api?q=other')\" = 200 ]"
ck "a blank query is not counted (still 200 at the ceiling)" \
   "[ \"\$(curl -s -o /dev/null -w '%{http_code}' -H 'Host: localhost:$PORT' '$V6/search/api?q=')\" = 200 ]"

ck "still no WARN/ERROR names the plugin after the run" "! grep -E ' (WARN|ERROR) ' '$LOG' | grep -qiE \"plugin 'search'|mx\\.quest\\.search\""

echo; echo "RESULT: $PASS passed, $FAIL failed"
if [ "$KEEP" = "yes" ]; then echo "[verify] kept at $BASE (scratch $SCRATCH; kill $PID to stop)"; wait "$PID"; fi
exit "$FAIL"
