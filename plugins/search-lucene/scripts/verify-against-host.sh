#!/usr/bin/env bash
# Prove this release's contract end to end on the PACKAGED Runway host, together with the two plugins it
# requires — the current search (publishes SiteSearchEngine, SearchCorpus and SearchHit.terms) and the code-less
# lucene-provider (the Lucene binaries) — and, when BASELINE_REF is set, prove that it ranks exactly as the baseline:
#
#   1. BASELINE (only when BASELINE_REF is set): search 2.0.0 + search-lucene 3.0.0 (built from git, BASELINE_REF) +
#      lucene-provider on the same host jar, a fixture site (pages written for the tiers, stemming, fuzzy and the
#      exclusion list, a stored ranking knob), and a fixed set of queries answered by the JSON API.
#   2. CURRENT: the current search + this release + lucene-provider, the same fixture and queries, with the index on
#      DISK this time (the app-level settings bind). Every answer — order, URLs, titles, snippets, scores — must be
#      byte-identical to the baseline's (the link and terms fields 3.0.0 never had are set aside for the comparison;
#      they are checked on their own: each hit carries the words it matched, as the page writes them).
#   3. On the current deployment: the three plugins load with ZERO host-linkage and bean-name/surface warnings, the
#      console card and admin fragment render (bean @searchLuceneAdminModel), a settings save of the search plugin's
#      exclude list and of this plugin's own knobs reach the next query without a reindex, and the operator's reindex
#      POST rebuilds this index and reports its size.
#
# Only the search plugin is installed to ~/.m2 (it is this plugin's compile-time dependency, as in 2.x); everything
# else is PACKAGED offline. Formerly: this plugin compiled against the
# search jar its sibling folder just built. Verify ONLY against the packaged jar — never spring-boot:run (its restart
# classloader loads ZERO plugins).
#
# Usage: scripts/verify-against-host.sh [--keep] [--port N]
#   JAVA_HOME=/path/to/jdk-25                     required: the JDK that builds the plugin and runs the host
#   MVN=/path/to/mvn                              Maven (default: mvn on the PATH)
#   HOST_TARGET=/path/to/runway/runway-app/target the host build (default: the kernel this plugin sits in, else ../runway)
#   HOST_JAR=/path/to/runway-app-<ver>-exec.jar  the kernel to verify against (default: the host checkout's
#                                                 runway-app/target jar MATCHING its pom version)
#   VERIFY_TMPDIR=/dir                            where the throwaway deployments go (default: this plugin's target/)
#   VERIFY_LOG_DIR=/dir                           keep the host logs, the query answers and the diff there (evidence)
#   BASELINE_REF=<git ref>                        the commit carrying search 2.0.0 + search-lucene 3.0.0 (default:
#                                                 empty, which skips the before/after comparison with a note);
#                                                 SKIP_BASELINE=1 skips it too
set -uo pipefail

HERE="$(cd "$(dirname "$(readlink -f "$0")")/.." && pwd)"
VER="$(sed -n 's/^version: *//p' "$HERE/plugin.yml")"   # the release under test
REPO="$(cd "$HERE/.." && pwd)"
SEARCH="$REPO/search"
LUCENE_PROVIDER="$REPO/lucene-provider"
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
BASELINE_REF="${BASELINE_REF:-}"
if [ -z "$BASELINE_REF" ] && [ "${SKIP_BASELINE:-0}" != 1 ]; then
    echo "[verify] BASELINE_REF is not set: skipping the comparison with search 2.0.0 + search-lucene 3.0.0" \
         "(set BASELINE_REF to a commit carrying those two versions to run it)"
    SKIP_BASELINE=1
fi
M2="${HOME}/.m2/repository/mx/quest"

KEEP="no"; PORT=""
while [ $# -gt 0 ]; do case "$1" in
    --keep) KEEP="yes"; shift ;;
    --port) PORT="${2:?}"; shift 2 ;;
    *) echo "unknown arg: $1"; exit 2 ;;
esac; done

APP_USER=root; APP_PW=Lucene-Admin-2026; SITE_USER=owner; SITE_PW=Lucene-Site-2026
free_port() { python3 -I -c 'import socket;s=socket.socket();s.bind(("127.0.0.1",0));print(s.getsockname()[1]);s.close()'; }
[ -n "$PORT" ] || PORT="$(free_port)"
BASE="http://localhost:$PORT"

m2_before="$(ls -d "$M2"/runway-plugin-search-lucene/"$VER" 2>/dev/null)"

echo "[verify] packaging search (the plugin this one requires and compiles against) ..."
( cd "$SEARCH" && "$MVN" -o -q -DskipTests install ) || { echo "search build/install failed"; exit 1; }
echo "[verify] packaging lucene-provider ..."
( cd "$LUCENE_PROVIDER" && "$MVN" -o -q -DskipTests package ) || { echo "lucene-provider build failed"; exit 1; }
echo "[verify] packaging this plugin ..."
( cd "$HERE" && "$MVN" -o -q -DskipTests package ) || { echo "search-lucene build failed"; exit 1; }

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

mkdir -p "${VERIFY_TMPDIR:-$HERE/target}"
SCRATCH="$(mktemp -d "${VERIFY_TMPDIR:-$HERE/target}/lucene-verify.XXXXXX")"
PID=""
stop_host() { [ -z "$PID" ] || { kill "$PID" 2>/dev/null; wait "$PID" 2>/dev/null; PID=""; }; }
cleanup() {
    if [ -n "${VERIFY_LOG_DIR:-}" ]; then
        mkdir -p "$VERIFY_LOG_DIR"
        for run in before after; do
            [ -d "$SCRATCH/$run" ] || continue
            mkdir -p "$VERIFY_LOG_DIR/$run"
            cp "$SCRATCH/$run"/*.log "$VERIFY_LOG_DIR/$run/" 2>/dev/null
            [ -d "$SCRATCH/$run/answers" ] && cp -r "$SCRATCH/$run/answers" "$VERIFY_LOG_DIR/$run/"
        done
        cp "$SCRATCH"/*.txt "$VERIFY_LOG_DIR/" 2>/dev/null
    fi
    # Wait for the host to exit before deleting: a JVM still shutting down rewrites files under external/.
    [ "$KEEP" = "yes" ] || { stop_host; rm -rf "$SCRATCH"; }
}
trap cleanup EXIT

wait_for() { for _ in $(seq 1 "$3"); do grep -q "$2" "$1" 2>/dev/null && return 0; sleep 1; done; return 1; }
PASS=0; FAIL=0
ck() { if eval "$2" >/dev/null 2>&1; then echo "  PASS  $1"; PASS=$((PASS+1)); else echo "  FAIL  $1"; FAIL=$((FAIL+1)); fi; }
code() { curl -s -o /dev/null -w '%{http_code}' "$@"; }

# --- The fixture: pages written for the tiers, stemming, fuzzy, highlighting and the exclusion list ------------------
page() {   # sites-dir slug title description body-html
    local t="$1/default/templates"
    printf 'path: /%s\ntemplate: _master\ntitle:\n  en: "%s"\ndescription:\n  en: "%s"\n' "$2" "$3" "$4" > "$t/$2.meta.yml"
    printf '<!DOCTYPE html>\n<html lang="en">\n<head><title>%s</title></head>\n<body>\n<header id="site-header"></header>\n<main id="content">\n%s\n</main>\n<footer id="site-footer"></footer>\n</body>\n</html>\n' \
        "$3" "$5" > "$t/$2.html"
    cp "$t/about.th.xml" "$t/$2.th.xml"
}
fixture() {   # sites-dir
    page "$1" guide "Runway Deployment Guide" "How to deploy Runway in production." \
        "<p>This guide covers the deployment of runway on a single server. Read the runway deployment guide first.</p>"
    page "$1" manual "Manual" "" "<p>The cloud deployment guide explains everything about the cloud.</p>"
    page "$1" notes "Notes" "Miscellaneous" \
        "<p>cloud infrastructure. a long unrelated paragraph about many topics. deployment pipelines. yet another sentence entirely. a helpful guide to nothing.</p>"
    page "$1" athletics "Athletics" "Sports club" "<p>They run every morning and they ran yesterday.</p>"
    page "$1" handbook "Lucene Handbook" "Search internals" "<p>All about lucene, analyzers and &lt;mark&gt; tags.</p>"
    page "$1" pricing "Pricing" "Plans for runway hosting" "<p>Runway hosting plans start small. Deployment support included.</p>"
    page "$1" privacy "Privacy" "Runway privacy policy" "<p>runway runway privacy deployment guide</p>"
    # A bilingual page whose copy comes ONLY from the site's bundles (decoupled th:text; the template's element is
    # empty) -- the kernel indexes it as rendered per language, and this plugin keeps one index per
    # language. Its words appear in none of QUERIES, so the 3.0.0 comparison is unaffected.
    sed -i '/^locales:/a - "es"' "$1/default/site.yml"
    local t="$1/default/templates"
    printf 'path: /faculty\nlocalizedPaths:\n  es: /facultad\nlocales: [en, es]\ntemplate: _master\ntitle:\n  en: Faculty\n  es: Profesores\n' > "$t/faculty.meta.yml"
    printf '<!DOCTYPE html>\n<html lang="en">\n<head><title>Faculty</title></head>\n<body>\n<header id="site-header"></header>\n<main id="content"><p id="faculty-copy"></p></main>\n<footer id="site-footer"></footer>\n</body>\n</html>\n' > "$t/faculty.html"
    sed 's#</thlogic>#  <attr sel="\#faculty-copy" th:text="\#{verify.faculty}"/>\n</thlogic>#' "$t/about.th.xml" > "$t/faculty.th.xml"
    mkdir -p "$1/default/i18n"
    printf '\nverify.faculty=Our students learn from researchers.\n' >> "$1/default/i18n/messages.properties"
    printf '\nverify.faculty=Nuestros alumnos aprenden con una facultad de excelencia.\n' >> "$1/default/i18n/messages_es.properties"
    mkdir -p "$1/default/plugins"
    printf 'exclude:\n  - privacy\n' > "$1/default/plugins/search.yml"
    printf 'titleBoost: 9.5\n' > "$1/default/plugins/search-lucene.yml"
}
QUERIES=("runway" "deployment" "cloud%20deployment%20guide" "runway%20deployment%20guide" "running" "lucane"
         "privacy" "hosting%20plans" "mark" "about" "home" "xyzzy%20nothing")
ask_all() {   # out-dir
    mkdir -p "$1"
    local i=0
    for q in "${QUERIES[@]}"; do
        curl -s "$BASE/search/api?q=$q" > "$1/$(printf '%02d' "$i")-${q//%20/_}.json"
        i=$((i + 1))
    done
}

# --- One throwaway deployment: wizard, fixture, ACTIVE boot ----------------------------------------------------------
boot() {   # log-file   (uses DEPLOY_DIR and EXT)
    ( cd "$DEPLOY_DIR" && exec "$JAVA" -jar "$JAR" --server.port="$PORT" --runway.storage.external="$EXT" ) >"$1" 2>&1 &
    PID=$!
}
deploy() {   # run-name search-folder lucene-folder [app-level search-lucene.yml]
    DEPLOY_DIR="$SCRATCH/$1"; EXT="$DEPLOY_DIR/external"; SITES="$EXT/sites"; LOG="$DEPLOY_DIR/active.log"
    mkdir -p "$EXT/plugins" "$EXT/plugin-settings"
    cp -r "$2" "$EXT/plugins/search"
    cp -r "$LUCENE_PROVIDER/target/lucene-provider" "$EXT/plugins/lucene-provider"
    cp -r "$3" "$EXT/plugins/search-lucene"
    echo "[verify] $1: booting host (wizard) on :$PORT ..."
    boot "$DEPLOY_DIR/wizard.log"
    wait_for "$DEPLOY_DIR/wizard.log" "Started RunwayApplication" 90 \
        || { echo "wizard did not start"; tail -20 "$DEPLOY_DIR/wizard.log"; exit 1; }
    curl -s -o /dev/null -X POST "$BASE/setup" --data-urlencode "username=$APP_USER" --data-urlencode "password=$APP_PW" \
        --data-urlencode "displayName=Root" --data-urlencode "siteAdminUsername=$SITE_USER" \
        --data-urlencode "siteAdminPassword=$SITE_PW"
    for _ in $(seq 1 30); do kill -0 "$PID" 2>/dev/null || break; sleep 1; done
    stop_host
    [ -f "$SITES/default/templates/about.th.xml" ] || { echo "the wizard did not seed $SITES/default"; exit 1; }
    fixture "$SITES"
    # A generous query ceiling: the comparison asks the same questions on both deployments.
    printf 'runway:\n  plugin:\n    search:\n      "rate-limit.per-ip-per-minute": 100000\n      "rate-limit.per-ip-per-hour": 100000\n' \
        > "$EXT/plugin-settings/search.yml"
    [ -z "${4:-}" ] || printf '%s\n' "$4" > "$EXT/plugin-settings/search-lucene.yml"
    echo "[verify] $1: booting host (ACTIVE) ..."
    boot "$LOG"
    wait_for "$LOG" "Started RunwayApplication" 90 || { echo "ACTIVE did not start"; tail -20 "$LOG"; exit 1; }
}

# --- 1. The baseline: search 2.0.0 + search-lucene 3.0.0 -------------------------------------------------------------
if [ "${SKIP_BASELINE:-0}" != 1 ]; then
    echo "[verify] building the baseline (search + search-lucene at $BASELINE_REF) offline ..."
    SRC="$SCRATCH/baseline-src"
    mkdir -p "$SRC"
    git -C "$REPO" archive "$BASELINE_REF" search search-lucene | tar -x -C "$SRC" \
        || { echo "cannot read $BASELINE_REF from git"; exit 1; }
    grep -q '^version: 3.0.0' "$SRC/search-lucene/plugin.yml" && grep -q '^version: 2.0.0' "$SRC/search/plugin.yml" \
        || { echo "$BASELINE_REF does not carry search 2.0.0 + search-lucene 3.0.0"; exit 1; }
    ( cd "$SRC/search" && "$MVN" -o -q -DskipTests package ) >"$SCRATCH/baseline-build.txt" 2>&1 \
        && ( cd "$SRC/search-lucene" && "$MVN" -o -q -DskipTests package ) >>"$SCRATCH/baseline-build.txt" 2>&1 \
        || { echo "baseline build failed (see baseline-build.txt)"; tail -20 "$SCRATCH/baseline-build.txt"; exit 1; }
    deploy before "$SRC/search/target/search" "$SRC/search-lucene/target/search-lucene"
    ck "baseline: 3 plugins registered, 0 blocked" "grep -qE 'plugins: 3 registered, 0 blocked' '$LOG'"
    ck "baseline: the fixture pages serve" "[ \"\$(code '$BASE/guide')\" = 200 ] && [ \"\$(code '$BASE/handbook')\" = 200 ]"
    ask_all "$SCRATCH/before/answers"
    grep -E ' WARN ' "$LOG" | grep -E "plugin 'search-lucene'|uses host internals" > "$SCRATCH/baseline-warnings.txt"
    echo "        (search-lucene 3.0.0 drew $(grep -c "search-lucene" "$SCRATCH/baseline-warnings.txt") warning line(s) naming it)"
    stop_host
fi

# --- 2. The release under test: the current search + this release, index on disk -----------------------------------
INDEX_ROOT="$SCRATCH/after/lucene-index"
deploy after "$SEARCH/target/search" "$HERE/target/search-lucene" \
    "$(printf 'runway:\n  plugin:\n    search-lucene:\n      storage: disk\n      indexRoot: "%s"' "$INDEX_ROOT")"

echo "== plugin load (this release's contract) =="
ck "3 plugins registered, 0 blocked"     "grep -qE 'plugins: 3 registered, 0 blocked' '$LOG'"
ck "search-lucene not blocked / quarantined" "! grep -qE \"plugin 'search-lucene' is blocked|plugin-quarantine\" '$LOG'"
# Host linkage and the surface rules, in both kernels' words: SPI 0.6.0 WARNed ("uses host internals", "… allowed until
# Runway 1.0"); from SPI 1.0.0 the kernel BLOCKS and logs "linkage scan: <reason>" / the surface reason — a grep for the
# 0.6.0 wording alone can never fail on a 1.0 kernel.
ck "ZERO host-linkage findings"        "! grep -qE \"uses host internals|plugin '(search-lucene|search)' linkage scan\" '$LOG'"
ck "ZERO bean-name/surface findings"   "! grep -qE \"plugin '(search-lucene|search)'.*(allowed until Runway 1.0|bean name|may not contribute)\" '$LOG'"
ck "no WARN/ERROR names the plugin"      "! grep -E ' (WARN|ERROR) ' '$LOG' | grep -qiE \"search-lucene|mx\\.quest\\.searchlucene\""
ck "no collision / degraded / missing class" "! grep -qiE 'collision|duplicate key|degraded|NoClassDefFound|ClassNotFound' '$LOG'"
ck "Lucene is NOT bundled in search-lucene" "[ ! -d '$EXT/plugins/search-lucene/lib' ]"
ck "the fixture pages serve"             "[ \"\$(code '$BASE/guide')\" = 200 ] && [ \"\$(code '$BASE/handbook')\" = 200 ]"
ck "no plugin-owned /search page (404)"  "[ \"\$(code '$BASE/search?q=runway')\" = 404 ]"

echo "== ranking: the same answers as 3.0.0 =="
ask_all "$SCRATCH/after/answers"
ck "the Lucene engine serves (a 1-edit typo finds the handbook)" "grep -q '\"url\":\"/handbook\"' '$SCRATCH/after/answers/05-lucane.json'"
ck "the search plugin's exclusion applies through SearchCorpus (no /privacy for 'runway')" \
   "grep -q '\"url\":\"/guide\"' '$SCRATCH/after/answers/00-runway.json' && ! grep -q '/privacy' '$SCRATCH/after/answers/00-runway.json'"
ck "each hit carries the words it matched and a link marked with them" \
   "grep -qE '\"link\":\"/[^\"]*#rw-hl=[^\"]+\",\"terms\":\\[\"[^\"]+' '$SCRATCH/after/answers/04-running.json'"
ck "…as the page writes them, at least every word its snippet marks (running: run, and ran within one edit)" \
   "python3 -I -c 'import json,re,sys; r=json.load(open(sys.argv[1]))[\"results\"]; ok=all(h[\"terms\"] and set(m.lower() for m in re.findall(\"<mark>(.*?)</mark>\", h[\"snippet\"])) <= set(h[\"terms\"]) for h in r); sys.exit(0 if r and ok else 1)' '$SCRATCH/after/answers/04-running.json'"
if [ "${SKIP_BASELINE:-0}" != 1 ]; then
    # 3.0.0 predates link/terms (search 2.1.0): compare everything else, byte for byte.
    mkdir -p "$SCRATCH/after/compared"
    for f in "$SCRATCH"/after/answers/*.json; do
        python3 -I -c 'import re,sys; s=open(sys.argv[1],encoding="utf-8").read(); sys.stdout.write(re.sub(r",\"link\":\"(?:[^\"\\\\]|\\\\.)*\",\"terms\":\[(?:\"(?:[^\"\\\\]|\\\\.)*\",?)*\]", "", s))' \
            "$f" > "$SCRATCH/after/compared/$(basename "$f")"
    done
    diff -r "$SCRATCH/before/answers" "$SCRATCH/after/compared" > "$SCRATCH/ranking-diff.txt"
    ck "every answer (order, URLs, titles, snippets, scores) is byte-identical to 3.0.0's (${#QUERIES[@]} queries)" \
       "[ ! -s '$SCRATCH/ranking-diff.txt' ] && [ \$(ls '$SCRATCH/after/answers' | wc -l) = ${#QUERIES[@]} ]"
    ck "…and the answers are not trivially empty" "[ \$(cat '$SCRATCH'/after/answers/*.json | grep -o '\"url\"' | wc -l) -ge 15 ]"
fi

echo "== one index per language, bundle strings included =="
ck "the page's Spanish copy is rendered from the bundle (fixture)" "curl -s '$BASE/es/facultad' | grep -q 'facultad de excelencia'"
curl -s "$BASE/search/api?q=excelencia&lang=es" > "$SCRATCH/after/es.json"
ck "a Spanish bundle string is found in Spanish, at the Spanish address, with the Spanish title" \
   "grep -q '\"url\":\"/es/facultad\"' '$SCRATCH/after/es.json' && grep -q '\"title\":\"Profesores\"' '$SCRATCH/after/es.json'"
ck "the Spanish analyzer stems ('facultades' finds 'facultad')" \
   "curl -s '$BASE/search/api?q=facultades&lang=es' | grep -q '\"url\":\"/es/facultad\"'"
ck "the English analyzer stems ('researcher' finds 'researchers')" \
   "curl -s '$BASE/search/api?q=researcher&lang=en' | grep -q '\"url\":\"/faculty\"'"
ck "English does not search the Spanish text" "! curl -s '$BASE/search/api?q=excelencia&lang=en' | grep -q '/faculty'"
ck "a page in English only is still found from Spanish" \
   "curl -s '$BASE/search/api?q=lucene&lang=es' | grep -q '\"url\":\"/handbook\"'"

echo "== storage: the app-level settings bind (disk) =="
ck "the site's index lives on disk under indexRoot/default, one folder per language" \
   "[ -d '$INDEX_ROOT/default/en' ] && [ -d '$INDEX_ROOT/default/es' ] && ls '$INDEX_ROOT/default/es' | grep -q ."

echo "== the console (signed in as the site owner) =="
token() { grep -o 'name="_csrf" value="[^"]*"' | head -1 | sed 's/.*value="//; s/"$//'; }
sc() { curl -s -H 'Accept-Language: en' -c "$SCRATCH/cookies.jar" -b "$SCRATCH/cookies.jar" "$@"; }
sc -o /dev/null -X POST "$BASE/login" --data-urlencode "username=$SITE_USER" --data-urlencode "password=$SITE_PW" \
    --data-urlencode "_csrf=$(sc "$BASE/login" | token)"
LS="$BASE/__admin/plugins/search-lucene/settings"
sc "$LS" > "$SCRATCH/lucene-settings-0.html"
DOCS="$(sed -n 's:.*id="la-pages"[^>]*>\([0-9]*\)<.*:\1:p' "$SCRATCH/lucene-settings-0.html" | head -1)"
ck "the admin fragment renders (bean @searchLuceneAdminModel): engine search/lucene" \
   "grep -q 'id=\"la-engine\"[^>]*>search/lucene<' '$SCRATCH/lucene-settings-0.html'"
ck "…the indexed-item count (${DOCS:-none})" "[ -n '$DOCS' ] && [ '$DOCS' -ge 8 ]"
ck "…the storage mode (disk)"            "grep -q 'id=\"la-storage\"[^>]*>disk<' '$SCRATCH/lucene-settings-0.html'"
ck "…and the stored title boost (9.5)"   "grep -q 'id=\"la-boost\"[^>]*>9.5<' '$SCRATCH/lucene-settings-0.html'"
sc -L "$BASE/__admin" > "$SCRATCH/dashboard.html"
ck "the dashboard card renders the count" "grep -q 'id=\"lc-pages\"[^>]*>$DOCS<' '$SCRATCH/dashboard.html'"

echo "== settings saves reach the next query, without a reindex =="
SS="$BASE/__admin/plugins/search/settings"
sc -o /dev/null -X POST "$SS" --data-urlencode "enabled=true" --data-urlencode "exclude=guide" \
    --data-urlencode "_csrf=$(sc "$SS" | token)"
curl -s "$BASE/search/api?q=runway%20deployment%20guide" > "$SCRATCH/after-exclude.json"
ck "search's exclude list saved (guide in, privacy out of the list)" \
   "grep -q 'guide' '$SITES/default/plugins/search.yml' && ! grep -q 'privacy' '$SITES/default/plugins/search.yml'"
ck "…the newly excluded page leaves this index at once" "! grep -q '\"url\":\"/guide\"' '$SCRATCH/after-exclude.json'"
ck "…and the no-longer-excluded page joins it"        "grep -q '\"url\":\"/privacy\"' '$SCRATCH/after-exclude.json'"
sc -o /dev/null -X POST "$LS" --data-urlencode "fuzzy=false" --data-urlencode "titleBoost=9.5" \
    --data-urlencode "_csrf=$(token < "$SCRATCH/lucene-settings-0.html")"
ck "this plugin's knobs saved (fuzzy off)" "grep -q 'fuzzy: false' '$SITES/default/plugins/search-lucene.yml'"
ck "…the typo no longer matches on the next query" "curl -s '$BASE/search/api?q=lucane' | grep -q '\"count\":0'"
ck "…while the exact term still does"             "curl -s '$BASE/search/api?q=lucene' | grep -q '\"url\":\"/handbook\"'"

echo "== the operator's reindex rebuilds this index =="
sc "$SS" > "$SCRATCH/search-settings.html"
sc -X POST "$BASE/search/reindex" --data-urlencode "_csrf=$(token < "$SCRATCH/search-settings.html")" > "$SCRATCH/reindex.txt"
ck "POST /search/reindex reports this engine's fresh count (${DOCS:-none})" "grep -qx 'Search index rebuilt\\. ($DOCS)' '$SCRATCH/reindex.txt'"
ck "a GET does not reindex (404)" "[ \"\$(sc -o /dev/null -w '%{http_code}' '$BASE/search/reindex')\" = 404 ]"

echo "== the build wrote nothing to the local Maven repository =="
m2_after="$(ls -d "$M2"/runway-plugin-search-lucene/"$VER" 2>/dev/null)"
ck "search-lucene itself is never installed to ~/.m2 (only its compile dependency, search, is)" "[ -z '$m2_after' ] || [ '$m2_after' = '$m2_before' ]"

ck "still no WARN/ERROR names the plugin after the run" "! grep -E ' (WARN|ERROR) ' '$LOG' | grep -qiE \"search-lucene|mx\\.quest\\.searchlucene\""

echo; echo "RESULT: $PASS passed, $FAIL failed"
if [ "$KEEP" = "yes" ]; then echo "[verify] kept at $BASE (scratch $SCRATCH; kill $PID to stop)"; wait "$PID"; fi
exit "$FAIL"
