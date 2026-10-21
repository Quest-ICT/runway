#!/usr/bin/env bash
# Build this plugin, drop it into a throwaway Runway deployment, boot the PACKAGED host in ACTIVE mode, and prove
# the blog's contract end to end against a Runway 1.0.0 kernel (SPI 1.0.0):
#   - it loads with ZERO host-linkage and bean-name/surface findings, and nothing blocks or quarantines it;
#   - entries WRITTEN BY blog 1.3.1 (the test fixture, placed in the site's plugins/blog/entries/ before the boot —
#     one of them without an author) load unchanged: they serve at their URLs (default prefix, localized slug,
#     custom path) inside the tenant's own master chrome, with the byline where there is one and none where there
#     is not; the draft does not serve;
#   - a page linking ${urls['blog:<id>']} gets the entry's URL from the kernel catalog;
#   - with discoverability and search installed (built from this repository's sibling folders into the scratch
#     folder; SKIPPED when they cannot be built): sitemap.xml and llms.txt list the published entries and not the
#     draft, and search finds an entry by a word that is only in its Markdown body (indexableText);
#   - the console screen (@ConsoleScreen) renders inside the operator shell, gated (anonymous → login), and every
#     mutation is a CSRF-checked POST: without the token → 403 and nothing written; a GET on a mutation path
#     changes nothing;
#   - signed in as the site owner: create → the entry serves AT ONCE and is in sitemap and search on the next
#     request (ContentChanges evicts routing, the catalog and search); edit the body → search follows; unpublish →
#     404 and gone from sitemap and search; a reserved custom path is refused at save; a prefix change through the
#     settings form re-keys the URLs at once (onSettingsChanged); delete → the folder is gone;
#   - a FULL backup and a TRANSFER copy both carry the entries (published content, not personal data).
# Verify ONLY against the packaged jar — never spring-boot:run (its restart classloader loads ZERO plugins).
#
# Usage: scripts/verify-against-host.sh [--keep] [--port N]
#   JAVA_HOME=/path/to/jdk-25                     required: the JDK that builds the plugin and runs the host
#   MVN=/path/to/mvn                              Maven (default: mvn on the PATH)
#   HOST_TARGET=/path/to/runway/runway-app/target the host build (default: the kernel this plugin sits in, else ../runway)
#   HOST_JAR=/path/to/runway-app-<ver>-exec.jar  the kernel to verify against (default: the host checkout's
#                                                 runway-app/target jar MATCHING its pom version)
#   COMPANIONS="discoverability search"           sibling plugins to install for the sitemap/llms.txt/search checks
#                                                 (built offline from a COPY in the scratch folder; their own folders
#                                                 are never written). Set it to "" to skip those checks.
#   VERIFY_TMPDIR=/dir                            where the throwaway deployment goes (default: $TMPDIR or /tmp)
#   VERIFY_LOG_DIR=/dir                           keep copies of the host logs and the backup manifests there
# The script never runs `mvn install` (nothing is written to ~/.m2): it packages offline against whatever is already
# in the local repository.
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
COMPANIONS="${COMPANIONS-discoverability search}"

KEEP="no"; PORT=""
while [ $# -gt 0 ]; do case "$1" in
    --keep) KEEP="yes"; shift ;;
    --port) PORT="${2:?}"; shift 2 ;;
    *) echo "unknown arg: $1"; exit 2 ;;
esac; done

APP_USER=root; APP_PW=Blog-Admin-2026; SITE_USER=owner; SITE_PW=Blog-Site-2026
SECRET_KEY=blog-verify-master-key-2026
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

SCRATCH="$(mktemp -d "${VERIFY_TMPDIR:-${TMPDIR:-/tmp}}/blog-verify.XXXXXX")"
cleanup() {
    [ -z "${VERIFY_LOG_DIR:-}" ] || { mkdir -p "$VERIFY_LOG_DIR" \
        && cp "$SCRATCH"/*.log "$SCRATCH"/*-manifest.yml "$VERIFY_LOG_DIR"/ 2>/dev/null; }
    # Wait for the host to exit before deleting: a JVM still shutting down rewrites files under external/ and
    # makes `rm -rf` leave the scratch folder behind.
    [ "$KEEP" = "yes" ] || { kill "${PID:-0}" 2>/dev/null; wait "${PID:-0}" 2>/dev/null; rm -rf "$SCRATCH"; }
}
trap cleanup EXIT
EXT="$SCRATCH/external"
mkdir -p "$EXT/plugins"
cp -r "$HERE/target/blog" "$EXT/plugins/blog"

# --- Companion plugins, built from a COPY (their own folders stay untouched) ---------------------------------------
INSTALLED=""
for id in $COMPANIONS; do
    src="$HERE/../$id"
    [ -f "$src/pom.xml" ] || { echo "[verify] companion $id: no source at $src"; continue; }
    mkdir -p "$SCRATCH/build"
    ( cd "$src" && tar --exclude=./target -cf - . ) | ( mkdir -p "$SCRATCH/build/$id" && cd "$SCRATCH/build/$id" && tar -xf - )
    if ( cd "$SCRATCH/build/$id" && "$MVN" -o -q -DskipTests package >"$SCRATCH/build-$id.txt" 2>&1 ) \
            && [ -d "$SCRATCH/build/$id/target/$id" ]; then
        cp -r "$SCRATCH/build/$id/target/$id" "$EXT/plugins/$id"
        INSTALLED="$INSTALLED $id"
        echo "[verify] companion $id $(sed -n 's/^version: *//p' "$EXT/plugins/$id/plugin.yml") installed"
    else
        echo "[verify] companion $id could not be built (see build-$id.txt) — its checks are skipped"
    fi
done
has() { case " $INSTALLED " in *" $1 "*) return 0 ;; esac; return 1; }

wait_for() { for _ in $(seq 1 "$3"); do grep -q "$2" "$1" 2>/dev/null && return 0; sleep 1; done; return 1; }
PASS=0; FAIL=0; SKIP=0
ck() { if eval "$2" >/dev/null 2>&1; then echo "  PASS  $1"; PASS=$((PASS+1)); else echo "  FAIL  $1"; FAIL=$((FAIL+1)); fi; }
skip() { echo "  SKIP  $1"; SKIP=$((SKIP+1)); }
boot() { ( cd "$SCRATCH" && exec "$JAVA" -jar "$JAR" --server.port="$PORT" --runway.storage.external="$EXT" \
    --runway.security.secret-key="$SECRET_KEY" ) >"$1" 2>&1 & PID=$!; }

echo "[verify] booting host (wizard) on :$PORT ..."
boot "$SCRATCH/wizard.log"
wait_for "$SCRATCH/wizard.log" "Started RunwayApplication" 90 || { echo "wizard did not start"; tail -20 "$SCRATCH/wizard.log"; exit 1; }
curl -s -o /dev/null -X POST "$BASE/setup" --data-urlencode "username=$APP_USER" --data-urlencode "password=$APP_PW" \
    --data-urlencode "displayName=Root" --data-urlencode "siteAdminUsername=$SITE_USER" --data-urlencode "siteAdminPassword=$SITE_PW"
for _ in $(seq 1 30); do kill -0 "$PID" 2>/dev/null || break; sleep 1; done

# --- The default site, prepared on disk before the ACTIVE boot reads it ---------------------------------------------
SITE="$EXT/sites/default"
[ -f "$SITE/site.yml" ] || { echo "the wizard did not seed $SITE"; exit 1; }
# Two languages (English default, Spanish), so the localized slug and the locale ladder are exercised.
python3 -I - "$SITE/site.yml" <<'PY'
import re, sys
p = sys.argv[1]; s = open(p, encoding="utf-8").read()
s = re.sub(r'(?m)^locales:\n(?:- .*\n)*', 'locales:\n- "en"\n- "es"\n', s)
open(p, "w", encoding="utf-8").write(s)
PY
grep -q '^- "es"' "$SITE/site.yml" || { echo "could not add Spanish to the site:"; cat "$SITE/site.yml"; exit 1; }
# Entries exactly as blog 1.3.1 wrote them (the unit tests' fixture, produced by the 1.3.1 store).
mkdir -p "$SITE/plugins/blog"
cp -r "$HERE/src/test/resources/fixtures/blog-1.3.1/entries" "$SITE/plugins/blog/entries"
# A page whose body links an entry through the kernel's URL map: the seeded About page, edited on disk.
sed -i 's|<main id="content">|<main id="content"><a id="to-blog" href="#">blog</a>|' "$SITE/templates/about.html"
sed -i 's|</thlogic>|<attr sel="#to-blog" th:href="${urls['"'"'blog:hello-world'"'"']}"/></thlogic>|' \
    "$SITE/templates/about.th.xml"
# llms.txt is part of discoverability's opt-in GEO area (off by default): switch it on for this site.
has discoverability && printf '"geo.enabled": true\n' > "$SITE/plugins/discoverability.yml"

echo "[verify] booting host (ACTIVE) ..."
boot "$SCRATCH/active.log"
wait_for "$SCRATCH/active.log" "Started RunwayApplication" 90 || { echo "ACTIVE did not start"; tail -20 "$SCRATCH/active.log"; exit 1; }
LOG="$SCRATCH/active.log"
ENTRIES="$SITE/plugins/blog/entries"
code() { curl -s -o /dev/null -w '%{http_code}' -H 'Accept-Language: en' "$@"; }
get() { curl -s -H 'Accept-Language: en' "$@"; }

echo "== plugin load (SPI 1.0.0 contract) =="
EXPECTED=$((1 + $(echo $INSTALLED | wc -w)))
ck "plugins registered ($EXPECTED), 0 blocked"   "grep -qE 'plugins: $EXPECTED registered, 0 blocked' '$LOG'"
ck "blog not blocked / quarantined"             "! grep -qE \"plugin 'blog' is blocked|plugin-quarantine/blog\" '$LOG'"
# Host linkage and the surface rules: the kernel logs "plugin 'blog' linkage scan: <reason>" before "is blocked" (the
# check above), and a surface problem names the offending bean ("bean name …", "… may not contribute"). The badge
# wording of the plugins screen ("uses host internals") is matched too.
ck "ZERO host-linkage findings for blog"        "! grep -qE \"plugin 'blog' (uses host internals|linkage scan)\" '$LOG'"
ck "ZERO bean-name/surface findings for blog"   "! grep -qE \"plugin 'blog'.*(bean name|may not contribute)\" '$LOG'"
ck "no WARN/ERROR from or about the blog"       "! grep -E ' (WARN|ERROR) ' '$LOG' | grep -qiE \"mx\\.quest\\.blog|'blog'|blog entr\""
ck "no key collision / degraded"                "! grep -qiE 'collision|duplicate key|degraded' '$LOG'"

# The application console's plugins screen: blog's row shows the version, LOADED, and no health note at all — no
# host-linkage badge, no contained failure, no warning.
APP_JAR_C="$SCRATCH/cookies-app.txt"
ac() { curl -s -H 'Accept-Language: en' -c "$APP_JAR_C" -b "$APP_JAR_C" "$@"; }
ac -o /dev/null -X POST "$BASE/app/login" --data-urlencode "username=$APP_USER" --data-urlencode "password=$APP_PW" \
    --data-urlencode "_csrf=$(ac "$BASE/app/login" | grep -o 'name="_csrf" value="[^"]*"' | head -1 | sed 's/.*value="//; s/"$//')"
ac "$BASE/__app/plugins" > "$SCRATCH/plugins-screen.html"
row() {   # plugin-id -> its <tr> on the plugins screen
    python3 -I - "$SCRATCH/plugins-screen.html" "$1" <<'PY2'
import re, sys
html = open(sys.argv[1], encoding="utf-8").read()
for tr in re.findall(r"(?s)<tr\b.*?</tr>", html):
    if re.search(r'class="c-id">%s<' % re.escape(sys.argv[2]), tr):
        print(tr); break
PY2
}
row blog > "$SCRATCH/plugins-row-blog.html"
BLOG_VERSION="$(sed -n 's/^version: *//p' "$HERE/plugin.yml")"
ck "plugins screen: blog $BLOG_VERSION, Loaded" \
   "grep -q 'class=\"c-version\">$BLOG_VERSION<' '$SCRATCH/plugins-row-blog.html' && grep -q 'bg-green-lt' '$SCRATCH/plugins-row-blog.html'"
ck "…with no host-linkage badge, no failure count and no health notes" \
   "[ -s '$SCRATCH/plugins-row-blog.html' ] && ! grep -qE 'c-host|c-failures|c-notes|c-warning|c-refused' '$SCRATCH/plugins-row-blog.html'"
# There used to be a positive control here: "an unmigrated companion (discoverability) does show the badge". Every
# companion is SPI-only now, so it failed for the wrong reason and was dropped. A real control
# needs a deliberately host-linked fixture jar; until then the badge check above has none.

echo "== entries written by blog 1.3.1 serve unchanged =="
get "$BASE/blog/hello-world" > "$SCRATCH/hello.html"
ck "/blog/hello-world -> 200"                         "[ \"\$(code '$BASE/blog/hello-world')\" = 200 ]"
ck "…inside the tenant's master chrome (site header + footer)" \
   "grep -q 'id=\"site-header\"' '$SCRATCH/hello.html' && grep -q 'id=\"site-footer\"' '$SCRATCH/hello.html' && ! grep -q 'from the master at render' '$SCRATCH/hello.html'"
ck "…with its title, its rendered Markdown and its byline" \
   "grep -q 'Hello world' '$SCRATCH/hello.html' && grep -q '<strong>bold</strong>' '$SCRATCH/hello.html' && grep -q 'By Jane Doe' '$SCRATCH/hello.html' && grep -q 'Published on 2026-07-17' '$SCRATCH/hello.html'"
get "$BASE/es/blog/hola-mundo" > "$SCRATCH/hola.html"
ck "/es/blog/hola-mundo (the localized slug) -> 200 in Spanish" \
   "[ \"\$(code '$BASE/es/blog/hola-mundo')\" = 200 ] && grep -q 'Hola mundo' '$SCRATCH/hola.html' && grep -q 'Publicado el' '$SCRATCH/hola.html'"
get "$BASE/blog/no-author" > "$SCRATCH/no-author.html"
ck "the entry 1.3.1 wrote WITHOUT an author serves, with a date and no byline" \
   "[ \"\$(code '$BASE/blog/no-author')\" = 200 ] && grep -q 'Unattributed' '$SCRATCH/no-author.html' && grep -q 'Published on 2026-08-01' '$SCRATCH/no-author.html' && ! grep -q 'id=\"entry-author\"' '$SCRATCH/no-author.html'"
ck "the custom path /announcing-v2 serves the launch entry" \
   "[ \"\$(code '$BASE/announcing-v2')\" = 200 ] && get '$BASE/announcing-v2' | grep -q 'By Richard Roe'"
ck "the draft does not serve (404)"                   "[ \"\$(code '$BASE/blog/draft-post')\" = 404 ]"
ck "a page's \${urls['blog:hello-world']} is the entry's URL (kernel catalog)" \
   "get '$BASE/about' | grep -q 'id=\"to-blog\" href=\"/blog/hello-world\"'"
ck "an entry carries an ETag (its updatedAt) and revalidates 304" \
   "E=\$(curl -s -D - -o /dev/null '$BASE/blog/hello-world' | sed -n 's/^[Ee][Tt]ag: *//p' | tr -d '\r'); [ -n \"\$E\" ] && [ \"\$(code -H \"If-None-Match: \$E\" '$BASE/blog/hello-world')\" = 304 ]"

sitemap() { get "$BASE/sitemap.xml"; }
search() { get "$BASE/search/api?q=$1"; }
echo "== sitemap.xml / llms.txt / search through the kernel catalog =="
if has discoverability; then
    sitemap > "$SCRATCH/sitemap-0.xml"
    ck "sitemap.xml lists the published entries (default + localized URLs, custom path)" \
       "grep -q '/blog/hello-world</loc>' '$SCRATCH/sitemap-0.xml' && grep -q '/es/blog/hola-mundo' '$SCRATCH/sitemap-0.xml' && grep -q '/announcing-v2</loc>' '$SCRATCH/sitemap-0.xml' && grep -q '/blog/no-author</loc>' '$SCRATCH/sitemap-0.xml'"
    ck "…and not the draft"            "! grep -q 'draft-post' '$SCRATCH/sitemap-0.xml'"
    ck "llms.txt lists an entry"       "get '$BASE/llms.txt' | grep -q '/blog/hello-world'"
else
    skip "sitemap.xml / llms.txt (discoverability not installed)"
fi
if has search; then
    ck "search finds an entry by a word only in its Markdown body (indexableText)" \
       "search words | grep -q '/blog/hello-world'"
    ck "…and not the draft's body"     "! search 'Not+yet' | grep -q 'draft-post'"
else
    skip "search (search not installed)"
fi

echo "== the console screen: @ConsoleScreen, gated, CSRF-checked POSTs =="
token() { grep -o 'name="_csrf" value="[^"]*"' | head -1 | sed 's/.*value="//; s/"$//'; }
JAR_C="$SCRATCH/cookies.txt"
sc() { curl -s -H 'Accept-Language: en' -c "$JAR_C" -b "$JAR_C" "$@"; }
ck "anonymous GET /__admin/blog does not render the screen (sign-in first)" \
   "R=\$(curl -s -o /dev/null -w '%{http_code} %{redirect_url}' '$BASE/__admin/blog'); echo \"\$R\" | grep -qE '^(302|303) .*/login|^40[13]'"
ck "anonymous POST create without a token -> 403, nothing written" \
   "[ \"\$(code -X POST '$BASE/__admin/blog/create' -d 'id=anon-post&title_en=x&published=on')\" = 403 ] && [ ! -e '$ENTRIES/anon-post' ]"
sc -o /dev/null -X POST "$BASE/login" --data-urlencode "username=$SITE_USER" --data-urlencode "password=$SITE_PW" \
    --data-urlencode "_csrf=$(sc "$BASE/login" | token)"
sc "$BASE/__admin/blog" > "$SCRATCH/console-list.html"
ck "signed in: the list renders INSIDE the operator shell" \
   "grep -q 'id=\"topbar\"' '$SCRATCH/console-list.html' && grep -q 'id=\"op-main\"' '$SCRATCH/console-list.html' && grep -q 'id=\"bl-heading\"' '$SCRATCH/console-list.html'"
ck "…with the 1.3.1 entries, the draft badge and the plugin's pretitle" \
   "grep -q '>hello-world<' '$SCRATCH/console-list.html' && grep -q '>draft-post<' '$SCRATCH/console-list.html' && grep -q '>Draft<' '$SCRATCH/console-list.html' && grep -q 'id=\"bl-pretitle\">Site<' '$SCRATCH/console-list.html'"
ck "…and the shell's menu carries the Blog entry" "grep -q 'href=\"/__admin/blog\"' '$SCRATCH/console-list.html'"
ck "every mutating form is a POST carrying the console's CSRF token" \
   "! grep -qE 'href=\"/__admin/blog/[^\"]*/(publish|delete)\"' '$SCRATCH/console-list.html' && grep -qE 'method=\"post\" action=\"/__admin/blog/hello-world/publish\"' '$SCRATCH/console-list.html' && grep -qE 'name=\"_csrf\" value=\"[^\"]+\"' '$SCRATCH/console-list.html'"
TOKEN="$(token < "$SCRATCH/console-list.html")"
ck "signed in, POST without the token -> 403 and the entry stays published" \
   "[ \"\$(sc -o /dev/null -w '%{http_code}' -X POST '$BASE/__admin/blog/hello-world/publish')\" = 403 ] && grep -q 'published: true' '$ENTRIES/hello-world/entry.yml'"
ck "signed in, GET on the publish path changes nothing" \
   "sc -o /dev/null '$BASE/__admin/blog/hello-world/publish'; grep -q 'published: true' '$ENTRIES/hello-world/entry.yml'"
ck "the editor of an entry 1.3.1 wrote shows its fields" \
   "sc '$BASE/__admin/blog/no-author/edit' | grep -q 'value=\"Unattributed\"'"

echo "== create / edit / unpublish / delete: every change is visible on the next request =="
[ -n "$(has discoverability && echo y)" ] && sitemap > /dev/null   # warm the catalog cache before the change
[ -n "$(has search && echo y)" ] && search warmup > /dev/null       # …and the search index
R="$(sc -o /dev/null -w '%{http_code} %{redirect_url}' -X POST "$BASE/__admin/blog/create" \
    --data-urlencode "_csrf=$TOKEN" --data-urlencode 'id=fresh-post' --data-urlencode 'title_en=Fresh post' \
    --data-urlencode 'title_es=Entrada nueva' --data-urlencode 'slug_es=entrada-nueva' \
    --data-urlencode 'body_en=Zanzibar is the first word of this body.' --data-urlencode 'locales_en=on' \
    --data-urlencode 'locales_es=on' --data-urlencode 'published=on' --data-urlencode 'publishedOn=2026-10-18')"
ck "create -> redirect to the editor"          "echo '$R' | grep -qE '^302 .*/__admin/blog/fresh-post/edit'"
ck "…written into the entries folder"          "[ -f '$ENTRIES/fresh-post/entry.yml' ] && [ -f '$ENTRIES/fresh-post/body-en.md' ]"
ck "…and it serves AT ONCE (routing evicted)"  "get '$BASE/blog/fresh-post' | grep -q 'Zanzibar'"
ck "…in Spanish at its localized slug"         "[ \"\$(code '$BASE/es/blog/entrada-nueva')\" = 200 ]"
if has discoverability; then ck "…and is in sitemap.xml on the next request" "sitemap | grep -q '/blog/fresh-post</loc>'"; fi
if has search; then ck "…and search finds it on the next request" "search zanzibar | grep -q '/blog/fresh-post'"; fi
sc -o /dev/null -X POST "$BASE/__admin/blog/fresh-post" --data-urlencode "_csrf=$TOKEN" \
    --data-urlencode 'title_en=Fresh post' --data-urlencode 'body_en=Quokka replaced the old word.' \
    --data-urlencode 'locales_en=on' --data-urlencode 'published=on' --data-urlencode 'publishedOn=2026-10-18'
ck "edit the body -> the page shows the new body" "get '$BASE/blog/fresh-post' | grep -q 'Quokka'"
if has search; then
    ck "…search finds the new word"            "search quokka | grep -q '/blog/fresh-post'"
    ck "…and no longer the old one"            "! search zanzibar | grep -q '/blog/fresh-post'"
fi
ck "a reserved custom path is refused at save, nothing written" \
   "sc -X POST '$BASE/__admin/blog/create' --data-urlencode '_csrf=$TOKEN' -d 'id=bad-path&title_en=x&customPath=/app/x&published=on' | grep -q 'customPath' && [ ! -e '$ENTRIES/bad-path' ]"
sc -o /dev/null -X POST "$BASE/__admin/blog/fresh-post/publish" --data-urlencode "_csrf=$TOKEN"
ck "unpublish -> 404 at once"                    "[ \"\$(code '$BASE/blog/fresh-post')\" = 404 ]"
if has discoverability; then ck "…and gone from sitemap.xml" "! sitemap | grep -q 'fresh-post'"; fi
if has search; then ck "…and from search"          "! search quokka | grep -q 'fresh-post'"; fi

echo "== a prefix change through the settings form re-keys every URL at once =="
SETTINGS="$BASE/__admin/plugins/blog/settings"
sc -o /dev/null -X POST "$SETTINGS" --data-urlencode "_csrf=$(sc "$SETTINGS" | token)" \
    --data-urlencode 'enabled=true' --data-urlencode 'prefix=/news' --data-urlencode 'template=entry-minimal'
ck "the settings were stored"                    "grep -q 'prefix: \"/news\"' '$SITE/plugins/blog.yml'"
ck "/news/hello-world -> 200 with the minimal template (no byline)" \
   "[ \"\$(code '$BASE/news/hello-world')\" = 200 ] && ! get '$BASE/news/hello-world' | grep -q 'By Jane Doe'"
ck "/blog/hello-world -> 404 (the old URL is gone)" "[ \"\$(code '$BASE/blog/hello-world')\" = 404 ]"
ck "the custom path is unaffected"                "[ \"\$(code '$BASE/announcing-v2')\" = 200 ]"
if has discoverability; then ck "sitemap.xml follows the new prefix" "sitemap | grep -q '/news/hello-world</loc>'"; fi
sc -o /dev/null -X POST "$SETTINGS" --data-urlencode "_csrf=$(sc "$SETTINGS" | token)" \
    --data-urlencode 'enabled=true' --data-urlencode 'prefix=/app' --data-urlencode 'template=entry-classic'
ck "a reserved prefix (/app) falls back to /blog instead of hiding every entry" \
   "[ \"\$(code '$BASE/blog/hello-world')\" = 200 ]"

echo "== backups carry the blog (published content, not personal data) =="
sc -o "$SCRATCH/full.zip" "$BASE/__admin/backups/download"
sc -o "$SCRATCH/transfer.zip" "$BASE/__admin/backups/download-transfer"
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
ck "the full backup carries the entries" "zipq '$SCRATCH/full.zip' has plugins/blog/entries/hello-world/entry.yml"
ck "the transfer copy carries them too"  "zipq '$SCRATCH/transfer.zip' has plugins/blog/entries/no-author/body-en.md"
ck "…and its manifest omits nothing of the blog" "! grep -q 'plugins/blog' '$SCRATCH/transfer-manifest.yml'"

echo "== delete =="
sc -o /dev/null -X POST "$BASE/__admin/blog/fresh-post/delete" --data-urlencode "_csrf=$TOKEN"
ck "delete removes the entry's folder"   "[ ! -e '$ENTRIES/fresh-post' ]"
ck "…and the other entries are intact"   "[ -f '$ENTRIES/hello-world/entry.yml' ] && [ -f '$ENTRIES/no-author/entry.yml' ]"

ck "no ERROR in the host log after all of it" "! grep -q ' ERROR ' '$LOG'"

echo; echo "RESULT: $PASS passed, $FAIL failed, $SKIP skipped"
if [ "$KEEP" = "yes" ]; then echo "[verify] kept at $BASE (scratch $SCRATCH; kill $PID to stop)"; wait "$PID"; fi
exit "$FAIL"
