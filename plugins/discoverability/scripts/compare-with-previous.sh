#!/usr/bin/env bash
# Before/after proof for a release: boot ONE throwaway Runway deployment with the PREVIOUS release of this plugin,
# fetch every surface it produces for a fixture site, swap in THIS build (same deployment, same files, same
# mtimes), fetch again, and diff. For an unchanged site the two must be byte-identical except for the changes the
# CHANGELOG documents as fixes.
#
# The fixture (the wizard's default site, made bilingual en + es): Home; About at /about and /es/acerca with an FAQ
# typed in content.schema; a page published in Spanish only; a noindex page; a page canonicalised to About; a page
# whose canonical is its own path written root-relative ("/selfrel"); a draft. GEO on, an Organization, a
# SoftwareApplication on the home page. Each surface is fetched in three set-ups: "configured" (a base URL in
# site.yml), "derived" (no base URL; the host derives https://<host name> — the common production case) and
# "origin" (no base URL and no host name: the site is reached as the platform's catch-all default, and the absolute
# URLs come from the request).
#
# Compared, as bodies: /sitemap.xml, /sitemap-1.xml (status), /robots.txt, /llms.txt, /indexnow-key.txt (status),
# the JSON-LD block of /, /about, /es/acerca and /selfrel; signed in as the owner: the plugin's settings form (the
# CSRF token masked) and the values of its admin fragment and dashboard card.
#
# Usage: PREVIOUS_DIR=/path/to/<previous>/target/discoverability scripts/compare-with-previous.sh [--port N]
#   PREVIOUS_DIR        the previous release's install folder (plugin.yml + jar + static/ templates/ i18n/), built
#                       from its tag; required
#   JAVA_HOME           required: the JDK 25 that builds the plugin and runs the host
#   MVN                 Maven (default: mvn on the PATH)
#   HOST_TARGET         the host build (default: the kernel this plugin sits in, else ../runway)
#   HOST_JAR            the kernel to run both on (default: the host checkout's jar matching its pom version)
#   VERIFY_TMPDIR       where the throwaway deployment goes (default: $TMPDIR or /tmp)
#   VERIFY_LOG_DIR      keep both captures, the diff and the host logs there (evidence)
# Exit status: 0 when the only differences are the expected ones listed in EXPECTED below, 1 otherwise.
# Never runs `mvn install`.
set -uo pipefail

HERE="$(cd "$(dirname "$(readlink -f "$0")")/.." && pwd)"
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
PORT=""
while [ $# -gt 0 ]; do case "$1" in
    --port) PORT="${2:?}"; shift 2 ;;
    *) echo "unknown arg: $1"; exit 2 ;;
esac; done
[ -n "${PREVIOUS_DIR:-}" ] && [ -f "$PREVIOUS_DIR/plugin.yml" ] || { echo "set PREVIOUS_DIR to the previous release's install folder"; exit 2; }

APP_USER=root; APP_PW=Disco-Admin-2026; SITE_USER=owner; SITE_PW=Disco-Site-2026
[ -n "$PORT" ] || PORT="$(python3 -I -c 'import socket;s=socket.socket();s.bind(("127.0.0.1",0));print(s.getsockname()[1]);s.close()')"
BASE="http://localhost:$PORT"
CONFIGURED="https://acme.example"

echo "[compare] building this plugin ..."
( cd "$HERE" && "$MVN" -o -q -DskipTests package ) || { echo "plugin build failed"; exit 1; }
if [ -n "${HOST_JAR:-}" ]; then JAR="$HOST_JAR"; else
    JAR="$HOST_TARGET/runway-app-$(sed -n 's:.*<version>\(.*\)</version>.*:\1:p' "$HOST_TARGET/../pom.xml" | head -1)-exec.jar"; fi
[ -f "$JAR" ] || { echo "no host exec jar $JAR"; exit 1; }
echo "[compare] host jar: $JAR"
echo "[compare] previous: $(grep -m1 '^version' "$PREVIOUS_DIR/plugin.yml")  this: $(grep -m1 '^version' "$HERE/plugin.yml")"

SCRATCH="$(mktemp -d "${VERIFY_TMPDIR:-${TMPDIR:-/tmp}}/discover-compare.XXXXXX")"
cleanup() {
    [ -z "${VERIFY_LOG_DIR:-}" ] || { mkdir -p "$VERIFY_LOG_DIR" && cp -r "$SCRATCH"/before "$SCRATCH"/after \
        "$SCRATCH"/*.log "$SCRATCH"/*.diff "$VERIFY_LOG_DIR"/ 2>/dev/null; }
    kill "${PID:-0}" 2>/dev/null; wait "${PID:-0}" 2>/dev/null; rm -rf "$SCRATCH"
}
trap cleanup EXIT
EXT="$SCRATCH/external"; S="$EXT/sites/default"
mkdir -p "$EXT/plugins"
wait_for() { for _ in $(seq 1 "$3"); do grep -q "$2" "$1" 2>/dev/null && return 0; sleep 1; done; return 1; }
boot() { ( cd "$SCRATCH" && exec "$JAVA" -jar "$JAR" --server.port="$PORT" --runway.storage.external="$EXT" ) \
    >"$1" 2>&1 & PID=$!; wait_for "$1" "Started RunwayApplication" 90 || { echo "boot failed: $1"; tail -20 "$1"; exit 1; }; }
stop() { kill "$PID" 2>/dev/null; wait "$PID" 2>/dev/null; }

boot "$SCRATCH/wizard.log"
curl -s -o /dev/null -X POST "$BASE/setup" --data-urlencode "username=$APP_USER" --data-urlencode "password=$APP_PW" \
    --data-urlencode "displayName=Root" --data-urlencode "siteAdminUsername=$SITE_USER" --data-urlencode "siteAdminPassword=$SITE_PW"
for _ in $(seq 1 30); do kill -0 "$PID" 2>/dev/null || break; sleep 1; done

# --- the fixture -----------------------------------------------------------------------------------------------
T="$S/templates"
perl -0pi -e 's/\nlocales:\n- "en"\n/\nlocales:\n- "en"\n- "es"\n/' "$S/site.yml"
perl -0pi -e 's/\n  en: About\n/\n  en: About\n  es: Acerca\n/' "$T/about.meta.yml"
printf 'locales: [en, es]\nlocalizedPaths:\n  es: /acerca\n' >> "$T/about.meta.yml"
page() {   # slug meta-yaml
    cp "$T/about.html" "$T/$1.html"; cp "$T/about.th.xml" "$T/$1.th.xml"; printf '%s\n' "$2" > "$T/$1.meta.yml"
}
page solo $'path: /solo\ntemplate: _master\nlocales: [es]\nlocalizedPaths:\n  es: /solo-es\ntitle:\n  es: Sólo en español\ndescription:\n  es: "Una página que sólo existe en español."'
page hidden $'path: /hidden\ntemplate: _master\ntitle:\n  en: Hidden\nnoindex: true'
page copy $'path: /copy\ntemplate: _master\ntitle:\n  en: A copy of About\ncanonical: "'"$CONFIGURED"'/about"'
page selfrel $'path: /selfrel\ntemplate: _master\ntitle:\n  en: Self, root-relative\ndescription:\n  en: "Its canonical is its own path."\ncanonical: /selfrel'
page wip $'path: /wip\ntemplate: _master\ntitle:\n  en: Work in progress\ndraft: true'
mkdir -p "$S/plugins"
cat > "$S/plugins/discoverability.yml" <<'YML'
org.name: Acme Corp
org.logo: /favicon.svg
org.sameas:
  - https://social.example/acme
geo.enabled: true
geo.summary: We build things and write about it.
search.url: /search
software.enabled: true
software.name: Acme App
software.price: "0"
content.schema: |
  /about:
    type: FAQPage
    faq:
      - { q: "Why?", a: "Because." }
YML
touch -d '2026-10-01 12:00:00 UTC' "$T"/* "$S/plugins/discoverability.yml"   # fixed mtimes: <lastmod> is stable

JAR_C="$SCRATCH/cookies.txt"
c() { curl -s -H 'Accept-Language: en' -c "$JAR_C" -b "$JAR_C" "$@"; }
token() { grep -o 'name="_csrf" value="[^"]*"' | head -1 | sed 's/.*value="//; s/"$//'; }
ld() { python3 -I -c 'import re,sys; h=sys.stdin.read(); print("\n".join(re.findall(r"<script type=\"application/ld\+json\">(.*?)</script>", h, re.S)))'; }
capture() {   # dir
    local d="$1"; mkdir -p "$d"
    curl -s "$BASE/sitemap.xml" > "$d/sitemap.xml"
    curl -s -o /dev/null -w '%{http_code}\n' "$BASE/sitemap-1.xml" > "$d/sitemap-1.status"
    curl -s "$BASE/robots.txt" > "$d/robots.txt"
    curl -s "$BASE/llms.txt" > "$d/llms.txt"
    curl -s -o /dev/null -w '%{http_code}\n' "$BASE/indexnow-key.txt" > "$d/indexnow-key.status"
    for p in / /about /es/acerca /selfrel; do curl -s "$BASE$p" | ld > "$d/jsonld$(echo "$p" | tr / _).json"; done
}
capture_console() {   # dir
    local d="$1" t
    rm -f "$JAR_C"; t="$(c "$BASE/login" | token)"
    c -o /dev/null -X POST "$BASE/login" --data-urlencode "username=$SITE_USER" --data-urlencode "password=$SITE_PW" --data-urlencode "_csrf=$t"
    c "$BASE/__admin/plugins/discoverability/settings" > "$d/settings.page"
    python3 -I - "$d/settings.page" > "$d/settings-form.html" <<'PY'
import re, sys
h = open(sys.argv[1], encoding="utf-8").read()
forms = re.findall(r"<form\b.*?</form>", h, re.S)
print(re.sub(r'(name="_csrf" value=")[^"]*', r"\1MASKED", max(forms, key=len) if forms else "NO FORM"))
PY
    c "$BASE/__admin" > "$d/dashboard.page"
    for f in settings dashboard; do grep -oE 'id="d[ac]-[a-z-]+"[^>]*>[^<]*<' "$d/$f.page"; done > "$d/console-values.txt"
    rm -f "$d"/*.page
}
cp "$S/site.yml" "$SCRATCH/site.yml.orig"
setup() {   # configured | derived | origin
    cp "$SCRATCH/site.yml.orig" "$S/site.yml"
    case "$1" in
        configured) perl -pi -e "s#^baseUrl: .*#baseUrl: \"$CONFIGURED\"#" "$S/site.yml" ;;
        origin)     perl -0pi -e 's/\nhosts:\n- "localhost"\n/\nhosts: []\n/' "$S/site.yml" ;;
    esac
}
round() {   # plugin-dir label
    rm -rf "$EXT/plugins/discoverability"; cp -r "$1" "$EXT/plugins/discoverability"
    for mode in configured derived origin; do
        setup "$mode"
        boot "$SCRATCH/$2-$mode.log"; capture "$SCRATCH/$2/$mode"
        [ "$mode" != configured ] || capture_console "$SCRATCH/$2/$mode"
        stop
    done
}
round "$PREVIOUS_DIR" before
round "$HERE/target/discoverability" after

( cd "$SCRATCH" && diff -ru before after ) > "$SCRATCH/before-after.diff"
echo "== differences (before = previous release, after = this build) =="
cat "$SCRATCH/before-after.diff"

# The documented fixes, and nothing else:
#  - llms.txt links the Spanish-only page at its real address;
#  - a root-relative self canonical no longer drops /selfrel from the sitemap;
#  - for a site with no base URL and no host name, the trusted origin (http here) instead of the Host header with a
#    guessed https.
python3 -I - "$SCRATCH" "$BASE" "$CONFIGURED" <<'PY'
import os, re, sys
root, base, configured = sys.argv[1:]
guessed = base.replace("http://", "https://")
problems = []
def read(side, mode, name):
    with open(os.path.join(root, side, mode, name), encoding="utf-8") as f:
        return f.read()
def split(xml):
    items = re.findall(r"  <url>\n.*?  </url>\n", xml, re.S)
    head = xml[:xml.index(items[0])] if items else xml
    tail = xml[xml.rindex(items[-1]) + len(items[-1]):] if items else ""
    return head, items, tail
def loc(item):
    return re.search(r"<loc>(.*?)</loc>", item).group(1)
for mode, origin in (("configured", configured), ("derived", "https://localhost"), ("origin", base)):
    for name in sorted(os.listdir(os.path.join(root, "before", mode))):
        before, after = read("before", mode, name), read("after", mode, name)
        expected = before
        if mode == "origin":
            expected = expected.replace(guessed, base)          # the trusted origin, not a guessed https
        if name == "llms.txt":
            expected = expected.replace("(" + origin + "/solo)", "(" + origin + "/es/solo-es)")   # the Spanish-only page's real address
        if name == "sitemap.xml":
            # The root-relative self canonical: /selfrel's <url> is listed now, in its sorted place.
            head, items, tail = split(expected)
            added = [i for i in split(after)[1] if loc(i) == origin + "/selfrel" and i not in items]
            expected = head + "".join(sorted(items + added, key=loc)) + tail
        if expected != after:
            problems.append(mode + "/" + name)
if problems:
    print("UNEXPECTED differences in: " + ", ".join(problems))
    sys.exit(1)
print("RESULT: identical except the documented fixes (llms.txt link, root-relative self canonical, trusted origin)")
PY
