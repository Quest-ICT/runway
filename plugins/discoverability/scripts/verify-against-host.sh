#!/usr/bin/env bash
# Build this plugin, drop it into a throwaway Runway deployment, boot the PACKAGED host in ACTIVE mode, and prove the
# discoverability contract end to end (SPI 1.0.0):
#   - it loads with ZERO host-linkage and bean-name/surface findings (a 1.0 kernel blocks on either), and its IndexNow
#     submitter is collected as an SPI event listener;
#   - the public surfaces: JSON-LD on the home page (valid, one @graph), /sitemap.xml with hreflang alternates for a
#     bilingual site, /robots.txt, /llms.txt (GEO on), the help page; for a site with neither a base URL nor a host
#     name (the catch-all default site here) the absolute URLs use the request's trusted origin
#     (http://localhost:<port> — 1.x read the Host header and guessed https) — since 2.1.0 the page JSON-LD too
#     (HeadContext.origin(): absolute @ids, a breadcrumb trail and the GEO SearchAction, none of which 2.0.0 had there);
#   - llms.txt's Last-Modified advances when only geo.summary changes (2.0.0 kept it, so a crawler kept the old copy);
#   - signed in as the site owner: the settings form keeps the 1.6.0 layout (sections, help lines, Advanced,
#     the platform-off notice) and the admin fragment + dashboard card render through the renamed bean
#     (`discoverabilityAdminModel`);
#   - IndexNow, platform switch at its default (OFF): a tenant's opt-in only logs the inert WARN once, the key file
#     is 404 and nothing is sent;
#   - IndexNow, platform switch ON (second boot, local fake endpoint): an UNPUBLISHED page's old URLs are submitted
#     (1.6.0 submitted nothing), a republish submits them again, with the key and keyLocation.
# The flush is a Spring @Scheduled method run by the HOST's scheduler, which the kernel enables unconditionally; the
# second boot keeps automatic backups OFF, so it also proves that.
# Verify ONLY against the packaged jar — never spring-boot:run (its restart classloader loads ZERO plugins).
#
# Usage: scripts/verify-against-host.sh [--keep] [--port N]
#   JAVA_HOME=/path/to/jdk-25                     required: the JDK that builds the plugin and runs the host
#   MVN=/path/to/mvn                              Maven (default: mvn on the PATH)
#   HOST_TARGET=/path/to/runway/runway-app/target the host build (default: the kernel this plugin sits in, else ../runway)
#   HOST_JAR=/path/to/runway-app-<ver>-exec.jar  the kernel to verify against (default: the host checkout's
#                                                 runway-app/target jar MATCHING its pom version)
#   VERIFY_TMPDIR=/dir                            where the throwaway deployment goes (default: $TMPDIR or /tmp)
#   VERIFY_LOG_DIR=/dir                           keep copies of the host logs, the fake endpoint's log and the
#                                                 fetched documents there (evidence)
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

APP_USER=root; APP_PW=Disco-Admin-2026; SITE_USER=owner; SITE_PW=Disco-Site-2026; KEY=disco-verify-key-2026
free_port() { python3 -I -c 'import socket;s=socket.socket();s.bind(("127.0.0.1",0));print(s.getsockname()[1]);s.close()'; }
[ -n "$PORT" ] || PORT="$(free_port)"
FAKE_PORT="$(free_port)"
BASE="http://localhost:$PORT"

echo "[verify] building the plugin ..."
( cd "$HERE" && "$MVN" -o -q -DskipTests package ) || { echo "plugin build failed"; exit 1; }

# The host jar MATCHING the host pom's version — never "the newest file": every reproducible jar carries the same
# fixed timestamp, so `ls -t` cannot tell a leftover jar from the build under test.
if [ -n "${HOST_JAR:-}" ]; then
    JAR="$HOST_JAR"
else
    HOST_VERSION="$(sed -n 's:.*<version>\(.*\)</version>.*:\1:p' "$HOST_TARGET/../pom.xml" | head -1)"
    JAR="$HOST_TARGET/runway-app-$HOST_VERSION-exec.jar"
fi
[ -f "$JAR" ] || { echo "no host exec jar $JAR — build the host (mvn -pl runway-app package) or set HOST_JAR"; exit 1; }
echo "[verify] host jar: $JAR"

SCRATCH="$(mktemp -d "${VERIFY_TMPDIR:-${TMPDIR:-/tmp}}/discover-verify.XXXXXX")"
cleanup() {
    [ -z "${VERIFY_LOG_DIR:-}" ] || { mkdir -p "$VERIFY_LOG_DIR" \
        && cp "$SCRATCH"/*.log "$SCRATCH"/*.txt "$SCRATCH"/*.xml "$SCRATCH"/*.html "$VERIFY_LOG_DIR"/ 2>/dev/null; }
    kill "${FAKE_PID:-0}" 2>/dev/null
    # Wait for the host to exit before deleting: a JVM still shutting down rewrites files under external/ and
    # makes `rm -rf` leave the scratch folder behind.
    [ "$KEEP" = "yes" ] || { kill "${PID:-0}" 2>/dev/null; wait "${PID:-0}" 2>/dev/null; rm -rf "$SCRATCH"; }
}
trap cleanup EXIT
EXT="$SCRATCH/external"
mkdir -p "$EXT/plugins"
cp -r "$HERE/target/discoverability" "$EXT/plugins/discoverability"

# Accurate JSON-LD check: exactly one <script type=application/ld+json> block (one @graph), VALID JSON, with no raw
# angle brackets (a real </script> breakout would truncate the block or leave a bare '<'/'>').
cat > "$SCRATCH/ldcheck.py" <<'PY'
import re, json, sys
html = open(sys.argv[1], encoding='utf-8', errors='replace').read()
blocks = re.findall(r'<script type="application/ld\+json">(.*?)</script>', html, re.S)
if len(blocks) != 1:
    sys.exit(1)
for b in blocks:
    if '<' in b or '>' in b:
        sys.exit(1)
    json.loads(b)
sys.exit(0)
PY
# Re-submits the settings form exactly as the browser would (every field with its current value; a checkbox only when
# checked), with KEY=VALUE overrides — prints the urlencoded body. A partial POST would switch unchecked flags off.
cat > "$SCRATCH/resave.py" <<'PY'
import html.parser, sys, urllib.parse
class F(html.parser.HTMLParser):
    def __init__(self):
        super().__init__(); self.inform = False; self.fields = []; self.select = None; self.area = None
    def handle_starttag(self, tag, attrs):
        a = dict(attrs)
        if tag == 'form' and a.get('id') == 'st-form': self.inform = True
        if not self.inform: return
        if tag == 'input' and a.get('name'):
            t = (a.get('type') or 'text').lower()
            if t in ('checkbox', 'radio'):
                if 'checked' in a: self.fields.append([a['name'], a.get('value') or 'on'])
            elif t not in ('submit', 'button', 'file'):
                self.fields.append([a['name'], a.get('value') or ''])
        elif tag == 'select' and a.get('name'): self.select = [a['name'], None]
        elif tag == 'option' and self.select is not None and ('selected' in a or self.select[1] is None):
            if 'selected' in a or self.select[1] is None: self.select[1] = a.get('value', '')
        elif tag == 'textarea' and a.get('name'): self.area = [a['name'], '']
    def handle_data(self, d):
        if self.area is not None: self.area[1] += d
    def handle_endtag(self, tag):
        if tag == 'form' and self.inform: self.inform = False
        if tag == 'select' and self.select is not None: self.fields.append([self.select[0], self.select[1] or '']); self.select = None
        if tag == 'textarea' and self.area is not None: self.fields.append(self.area); self.area = None
f = F(); f.feed(open(sys.argv[1], encoding='utf-8').read())
over = dict(kv.split('=', 1) for kv in sys.argv[2:])
print(urllib.parse.urlencode([(k, over.get(k, v)) for k, v in f.fields]))
PY
# A local IndexNow endpoint: records every POST body, answers 200.
cat > "$SCRATCH/fake_indexnow.py" <<'PY'
import http.server, sys
out = sys.argv[2]
class H(http.server.BaseHTTPRequestHandler):
    def do_POST(self):
        body = self.rfile.read(int(self.headers.get('Content-Length', 0))).decode('utf-8', 'replace')
        with open(out, 'a') as f:
            f.write(body + '\n')
        self.send_response(200); self.end_headers()
    def log_message(self, *a):
        pass
http.server.HTTPServer(('127.0.0.1', int(sys.argv[1])), H).serve_forever()
PY

wait_for() { for _ in $(seq 1 "$3"); do grep -q "$2" "$1" 2>/dev/null && return 0; sleep 1; done; return 1; }
PASS=0; FAIL=0
ck() { if eval "$2" >/dev/null 2>&1; then echo "  PASS  $1"; PASS=$((PASS+1)); else echo "  FAIL  $1"; FAIL=$((FAIL+1)); fi; }
# The host reads spring.config.import: optional:file:external/application.yml RELATIVE to cwd, so run with cwd=root.
boot() { local log="$1"; shift; ( cd "$SCRATCH" && exec "$JAVA" -jar "$JAR" --server.port="$PORT" \
    --runway.storage.external="$EXT" "$@" ) >"$log" 2>&1 & PID=$!; }
stop() { kill "$PID" 2>/dev/null; wait "$PID" 2>/dev/null; }
code() { curl -s -o /dev/null -w '%{http_code}' "$@"; }

echo "[verify] booting host (wizard) on :$PORT ..."
boot "$SCRATCH/wizard.log"
wait_for "$SCRATCH/wizard.log" "Started RunwayApplication" 90 || { echo "wizard did not start"; tail -20 "$SCRATCH/wizard.log"; exit 1; }
curl -s -o /dev/null -X POST "$BASE/setup" --data-urlencode "username=$APP_USER" --data-urlencode "password=$APP_PW" \
    --data-urlencode "displayName=Root" --data-urlencode "siteAdminUsername=$SITE_USER" --data-urlencode "siteAdminPassword=$SITE_PW"
for _ in $(seq 1 30); do kill -0 "$PID" 2>/dev/null || break; sleep 1; done

# --- The fixture: the wizard's default site made bilingual (en + es; About also at /es/acerca), GEO on, an
#     Organization, and the tenant's IndexNow opt-in with a key. Boot A: NO base URL and NO host name — the site is
#     reached as the platform's catch-all default — so the host has no base to offer and the origin fallback runs.
#     (A site with a host name gets https://<host> from the host as its base, exactly as under 1.x.)
S="$EXT/sites/default"
[ -f "$S/site.yml" ] || { echo "the wizard did not seed $S"; exit 1; }
perl -0pi -e 's/\nhosts:\n- "localhost"\n/\nhosts: []\n/' "$S/site.yml"
perl -0pi -e 's/\nlocales:\n- "en"\n/\nlocales:\n- "en"\n- "es"\n/' "$S/site.yml"
perl -0pi -e 's/\n  en: About\n/\n  en: About\n  es: Acerca\n/' "$S/templates/about.meta.yml"
printf 'locales: [en, es]\nlocalizedPaths:\n  es: /acerca\n' >> "$S/templates/about.meta.yml"
mkdir -p "$S/plugins"
cat > "$S/plugins/discoverability.yml" <<YML
org.name: Acme Corp
org.sameas:
  - https://social.example/acme
geo.enabled: true
geo.summary: We build things and write about it.
search.url: /search
indexnow.enabled: true
indexnow.key: $KEY
YML

echo "[verify] booting host (ACTIVE, IndexNow platform switch at its default: OFF) ..."
boot "$SCRATCH/active.log"
wait_for "$SCRATCH/active.log" "Started RunwayApplication" 90 || { echo "ACTIVE did not start"; tail -20 "$SCRATCH/active.log"; exit 1; }
LOG="$SCRATCH/active.log"

echo "== plugin load (SPI 1.0.0 contract) =="
ck "plugin registered (1 registered, 0 blocked)" "grep -qE 'plugins: 1 registered, 0 blocked' '$LOG'"
ck "not blocked / quarantined"       "! grep -qE \"plugin 'discoverability' is blocked|plugin-quarantine\" '$LOG'"
# The kernel's linkage and surface findings in every wording it has logged them ("linkage scan: <reason>", "uses
# host internals", …): a grep for one wording alone could never fail on a kernel that logs another.
LINKAGE="plugin 'discoverability' (uses host internals|linkage scan)|uses host internals"
SURFACE="plugin 'discoverability'.*(allowed until Runway 1.0|bean name|may not contribute)"
ck "ZERO host-linkage findings"      "! grep -qE \"$LINKAGE\" '$LOG'"
ck "ZERO bean-name/surface findings" "! grep -qE \"$SURFACE\" '$LOG'"
ck "IndexNow collected as an SPI listener (1 consumer)" "grep -q 'SPI event listeners: 0 source-phase, 1 consumer-phase' '$LOG'"
ck "no startup WARN/ERROR names the plugin" "! sed -n '1,/Started RunwayApplication/p' '$LOG' | grep -E ' (WARN|ERROR) ' | grep -qi 'discover'"
ck "no key collision / degraded"     "! grep -qiE 'collision|duplicate key|degraded' '$LOG'"

echo "== SEO+ : structured data on the public home page =="
curl -s "$BASE/" > "$SCRATCH/home.html"
ck "JSON-LD script present"        "grep -q 'application/ld+json' '$SCRATCH/home.html'"
ck "WebSite + WebPage emitted"     "grep -q '\"@type\":\"WebSite\"' '$SCRATCH/home.html' && grep -q '\"@type\":\"WebPage\"' '$SCRATCH/home.html'"
ck "Organization from config"      "grep -q '\"@type\":\"Organization\"' '$SCRATCH/home.html' && grep -q 'Acme Corp' '$SCRATCH/home.html'"
ck "ONE JSON-LD block, valid, no raw <>" "python3 -I '$SCRATCH/ldcheck.py' '$SCRATCH/home.html'"
# No base URL and no host name: the graph is anchored on HeadContext.origin() (2.1.0) — 2.0.0 emitted "/#website".
ck "host-less JSON-LD is absolute at the trusted origin" "grep -q '\"@id\":\"$BASE/#website\"' '$SCRATCH/home.html' && grep -q '\"@id\":\"$BASE/#webpage\"' '$SCRATCH/home.html' && ! grep -q '\"@id\":\"/' '$SCRATCH/home.html'"
ck "…and carries the GEO SearchAction (none in 2.0.0)" "grep -q '\"urlTemplate\":\"$BASE/search?q={search_term_string}\"' '$SCRATCH/home.html'"
curl -s "$BASE/about" > "$SCRATCH/about.html"
ck "…a deep page gains its absolute breadcrumb trail" "grep -q '\"@type\":\"BreadcrumbList\"' '$SCRATCH/about.html' && grep -q '\"item\":\"$BASE/about\"' '$SCRATCH/about.html' && python3 -I '$SCRATCH/ldcheck.py' '$SCRATCH/about.html'"

echo "== SEO+ : /sitemap.xml over the host's listing (no base URL, no host name: the trusted origin) =="
ck "sitemap -> 200 application/xml" "curl -s -D - -o '$SCRATCH/sitemap.xml' '$BASE/sitemap.xml' | grep -qi '^content-type: application/xml'"
ck "lists the pages at the request's origin (http, port kept)" "grep -q '<loc>$BASE/about</loc>' '$SCRATCH/sitemap.xml' && grep -q '<loc>$BASE/</loc>' '$SCRATCH/sitemap.xml'"
ck "never a guessed https://"     "! grep -q 'https://localhost' '$SCRATCH/sitemap.xml'"
ck "the Spanish address and its hreflang alternates" "grep -q '<loc>$BASE/es/acerca</loc>' '$SCRATCH/sitemap.xml' && grep -q 'hreflang=\"es\" href=\"$BASE/es/acerca\"' '$SCRATCH/sitemap.xml' && grep -q 'hreflang=\"x-default\" href=\"$BASE/about\"' '$SCRATCH/sitemap.xml'"
ck "every item carries a <lastmod>" "[ \"\$(grep -c '<lastmod>' '$SCRATCH/sitemap.xml')\" = \"\$(grep -c '<loc>' '$SCRATCH/sitemap.xml')\" ]"
LM="$(curl -s -D - -o /dev/null "$BASE/sitemap.xml" | sed -n 's/^[Ll]ast-[Mm]odified: //p' | tr -d '\r')"
ck "conditional GET: 304 for an unchanged sitemap" "[ -n '$LM' ] && [ \"\$(code -H 'If-Modified-Since: $LM' '$BASE/sitemap.xml')\" = 304 ]"
ck "a small site's shard path is 404" "[ \"\$(code '$BASE/sitemap-1.xml')\" = 404 ]"

echo "== /robots.txt and GEO /llms.txt =="
curl -s "$BASE/robots.txt" > "$SCRATCH/robots.txt"
ck "robots.txt: general group + AI group + Sitemap line at the origin" "grep -q '^User-agent: \\*' '$SCRATCH/robots.txt' && grep -q '^User-agent: GPTBot' '$SCRATCH/robots.txt' && grep -qx 'Sitemap: $BASE/sitemap.xml' '$SCRATCH/robots.txt'"
curl -s -o "$SCRATCH/llms.txt" "$BASE/llms.txt"
ck "llms.txt served (H1 + summary)" "head -1 '$SCRATCH/llms.txt' | grep -q '^# ' && grep -q 'We build things' '$SCRATCH/llms.txt'"
ck "llms.txt lists the pages at the origin" "grep -q '## Pages' '$SCRATCH/llms.txt' && grep -q '($BASE/about)' '$SCRATCH/llms.txt'"
LLM_LM="$(curl -s -D - -o /dev/null "$BASE/llms.txt" | sed -n 's/^[Ll]ast-[Mm]odified: //p' | tr -d '\r')"
ck "llms.txt: 304 while nothing changed" "[ -n '$LLM_LM' ] && [ \"\$(code -H 'If-Modified-Since: $LLM_LM' '$BASE/llms.txt')\" = 304 ]"
ck "GEO answer-schema (Speakable)" "grep -q 'SpeakableSpecification' '$SCRATCH/home.html'"
ck "help page served"              "[ \"\$(code '$BASE/__plugin/discoverability/static/discoverability-help.html')\" = 200 ]"

echo "== console: settings form layout (1.6.0), admin fragment and dashboard card (renamed bean) =="
JAR_C="$SCRATCH/cookies.txt"
c() { curl -s -H 'Accept-Language: en' -c "$JAR_C" -b "$JAR_C" "$@"; }
token() { grep -o 'name="_csrf" value="[^"]*"' | head -1 | sed 's/.*value="//; s/"$//'; }
login_site() { rm -f "$JAR_C"; local t; t="$(c "$BASE/login" | token)"; c -o /dev/null -X POST "$BASE/login" \
    --data-urlencode "username=$SITE_USER" --data-urlencode "password=$SITE_PW" --data-urlencode "_csrf=$t"; }
page_action() { local t; t="$(c "$BASE/__admin/pages" | token)"; c -o /dev/null -w '%{http_code}' -X POST \
    "$BASE/__admin/pages/$1/$2" --data-urlencode "_csrf=$t"; }
login_site
c "$BASE/__admin/plugins/discoverability/settings" > "$SCRATCH/settings.html"
ck "settings page with the six sections" "grep -q 'Structured data for search engines' '$SCRATCH/settings.html' && grep -q 'Notify search engines of changes (IndexNow)' '$SCRATCH/settings.html'"
ck "…help lines and an Advanced part" "grep -q 'Google does not' '$SCRATCH/settings.html' && grep -qi 'advanced' '$SCRATCH/settings.html'"
ck "admin fragment rendered by discoverabilityAdminModel (GEO on, page count)" "grep -q 'id=\"da-geo\">on<' '$SCRATCH/settings.html' && grep -qE 'id=\"da-pages\">[1-9][0-9]*<' '$SCRATCH/settings.html'"
ck "…and its Per-page types check (2.2.0)" "grep -q 'Per-page types check' '$SCRATCH/settings.html'"
c "$BASE/__admin/pages/about/edit" > "$SCRATCH/editor.html"
ck "the page editor says what the page is described as (2.2.0)" "grep -q 'Structured data: this page is a' '$SCRATCH/editor.html'"
c "$BASE/__admin" > "$SCRATCH/dashboard.html"
ck "dashboard card rendered (page count, GEO on)" "grep -qE 'id=\"dc-pages\">[1-9][0-9]*<' '$SCRATCH/dashboard.html' && grep -q 'id=\"dc-geo\">on<' '$SCRATCH/dashboard.html'"
ck "no template error in the log" "! grep -qiE 'TemplateProcessingException|SpelEvaluationException' '$LOG'"

echo "== llms.txt's validator folds the summary (2.1.0) =="
# Save the form unchanged except geo.summary (the console path: the kernel caches per-site settings and evicts on save,
# so a hand edit of the YAML would not be seen). 2.0.0 kept Last-Modified, so a crawler kept the old summary.
sleep 2   # HTTP dates are whole seconds: the advance must land in a later second than the validator it replaces
python3 -I "$SCRATCH/resave.py" "$SCRATCH/settings.html" "geo.summary=We build better things now." > "$SCRATCH/resave.txt"
RS="$(c -o /dev/null -w '%{http_code}' -X POST "$BASE/__admin/plugins/discoverability/settings" \
    -H 'Content-Type: application/x-www-form-urlencoded' --data-binary "@$SCRATCH/resave.txt")"
ck "settings saved through the console (summary only)" "[ '$RS' = 302 ] || [ '$RS' = 303 ]"
ck "…and nothing else changed in the stored file" "grep -q '^geo.enabled: true' '$S/plugins/discoverability.yml' && grep -q 'Acme Corp' '$S/plugins/discoverability.yml' && grep -q 'We build better things now.' '$S/plugins/discoverability.yml'"
ck "llms.txt: the old validator now gets a 200 with the new summary" \
   "[ \"\$(code -H 'If-Modified-Since: $LLM_LM' '$BASE/llms.txt')\" = 200 ] && curl -s '$BASE/llms.txt' | grep -q '^> We build better things now.'"
LLM_LM2="$(curl -s -D - -o /dev/null "$BASE/llms.txt" | sed -n 's/^[Ll]ast-[Mm]odified: //p' | tr -d '\r')"
ck "…and its new validator revalidates to 304" "[ -n '$LLM_LM2' ] && [ '$LLM_LM2' != '$LLM_LM' ] && [ \"\$(code -H 'If-Modified-Since: $LLM_LM2' '$BASE/llms.txt')\" = 304 ]"

echo "== IndexNow, platform switch OFF (the default): inert =="
ck "key file is 404"               "[ \"\$(code '$BASE/indexnow-key.txt')\" = 404 ]"
CODE="$(page_action about disable)"
ck "unpublish via the console (redirect)" "[ '$CODE' = 302 ] || [ '$CODE' = 303 ]"
ck "the inert WARN, once, naming the site" "[ \"\$(grep -c \"site 'default' has IndexNow enabled, but the platform has not permitted it\" '$LOG')\" = 1 ]"
ck "nothing attempted"             "! grep -q 'IndexNow submission' '$LOG'"
page_action about enable >/dev/null
stop

echo "[verify] booting host (ACTIVE, IndexNow ON, fake endpoint :$FAKE_PORT, 2 s batches, base URL configured) ..."
perl -pi -e "s#^baseUrl: .*#baseUrl: \"$BASE\"#" "$S/site.yml"
python3 -I "$SCRATCH/fake_indexnow.py" "$FAKE_PORT" "$SCRATCH/indexnow-posts.txt" & FAKE_PID=$!
boot "$SCRATCH/active2.log" --runway.plugin.discoverability.indexnow.enabled=true \
    --runway.plugin.discoverability.indexnow.endpoint="http://127.0.0.1:$FAKE_PORT/indexnow" \
    --runway.plugin.discoverability.indexnow.batch-seconds=2
wait_for "$SCRATCH/active2.log" "Started RunwayApplication" 90 || { echo "second ACTIVE boot failed"; tail -20 "$SCRATCH/active2.log"; exit 1; }
LOG2="$SCRATCH/active2.log"
echo "== IndexNow, platform switch ON =="
ck "still 1 registered, 0 blocked, zero findings" "grep -q 'plugins: 1 registered, 0 blocked' '$LOG2' && ! grep -qE \"$LINKAGE\" '$LOG2' && ! grep -qE \"$SURFACE\" '$LOG2'"
ck "app-level settings bound: key file served" "curl -s '$BASE/indexnow-key.txt' | grep -qx '$KEY'"
# With a base URL the graph is anchored on that base (never the request origin), with the GEO SearchAction.
curl -s "$BASE/" > "$SCRATCH/home-based.html"
ck "GEO SearchAction on the anchored graph" "grep -q '\"potentialAction\":{\"@type\":\"SearchAction\"' '$SCRATCH/home-based.html' && grep -q '\"@id\":\"$BASE/#website\"' '$SCRATCH/home-based.html' && python3 -I '$SCRATCH/ldcheck.py' '$SCRATCH/home-based.html'"
curl -s -o /dev/null "$BASE/sitemap.xml"   # the host lists the site once, so a change can report previous URLs
login_site
page_action about disable >/dev/null
for _ in $(seq 1 15); do [ -s "$SCRATCH/indexnow-posts.txt" ] && break; sleep 1; done
echo "    fake endpoint received after the unpublish:"; sed 's/^/      /' "$SCRATCH/indexnow-posts.txt" 2>/dev/null
ck "an UNPUBLISHED page's old URLs are submitted (1.6.0 sent nothing)" \
   "grep -qF '\"urlList\":[\"$BASE/about\",\"$BASE/es/acerca\"]' '$SCRATCH/indexnow-posts.txt'"
ck "…with the key and the fixed keyLocation" "grep -qF '\"key\":\"$KEY\",\"keyLocation\":\"$BASE/indexnow-key.txt\"' '$SCRATCH/indexnow-posts.txt'"
cp "$SCRATCH/indexnow-posts.txt" "$SCRATCH/indexnow-posts-unpublish.txt"
: > "$SCRATCH/indexnow-posts.txt"
page_action about enable >/dev/null
for _ in $(seq 1 15); do [ -s "$SCRATCH/indexnow-posts.txt" ] && break; sleep 1; done
sleep 3   # one more batch period: a second submission for the same change would land now
echo "    fake endpoint received after the republish:"; sed 's/^/      /' "$SCRATCH/indexnow-posts.txt" 2>/dev/null
ck "a republish submits the page's URLs again" "grep -qF '\"urlList\":[\"$BASE/about\",\"$BASE/es/acerca\"]' '$SCRATCH/indexnow-posts.txt'"
ck "one submission per batch (not one per URL)" "[ \"\$(grep -c urlList '$SCRATCH/indexnow-posts.txt')\" = 1 ]"
ck "no inert WARN while permitted" "! grep -q 'has not permitted it' '$LOG2'"
ck "no WARN/ERROR names the plugin" "! grep -E ' (WARN|ERROR) ' '$LOG2' | grep -qi 'discover'"

echo; echo "RESULT: $PASS passed, $FAIL failed"
if [ "$KEEP" = "yes" ]; then echo "[verify] kept at $BASE (scratch $SCRATCH; kill $PID to stop)"; wait "$PID"; fi
exit "$FAIL"
