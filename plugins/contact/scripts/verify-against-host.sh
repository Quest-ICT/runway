#!/usr/bin/env bash
# Build this plugin, drop it into a throwaway Runway deployment, boot the PACKAGED host in ACTIVE mode, and prove
# the contact contract end to end (SPI 1.0.0):
#   - it loads with ZERO host-linkage and bean-name/surface warnings;
#   - an anonymous visitor's form POST (no session, no CSRF token) to the anonymous route is stored and 303s back
#     with only an outcome flag; GET submission is gone; honeypot / invalid / non-form bodies are refused;
#   - the two operator actions are CSRF-protected POSTs: a GET (a cross-site link or <img>) changes nothing even
#     for a signed-in owner, a POST without the console's CSRF token is refused 403, and an anonymous caller holding
#     a token is still refused by the permission check (404);
#   - signed in as the site owner, the starter page installs (POST + the token from the settings page) through
#     SitePages on an Advisory, an Enforced and a Reviewed site (each outcome reported), and a second install
#     changes nothing;
#   - the SMTP password saved through the settings form is stored ENC(...) and reaches a local fake relay in clear
#     (PluginSettings.secret), both for a submission and for the operator's SMTP test, whose body `to` override
#     arrives with the CSRF token as a form field or a header;
#   - a FULL backup carries submissions.tsv and a TRANSFER copy leaves it out and lists it in its manifest;
#   - with the protection plugin installed (from the host checkout, when built) and a CAPTCHA configured on one
#     site, a POST without the CAPTCHA token is refused and nothing is stored.
# The deployment holds four sites copied from the wizard's default one before the ACTIVE boot: default (Advisory),
# enforced, reviewed, captcha — addressed by Host name through curl --resolve. Verify ONLY against the packaged jar
# — never spring-boot:run (its restart classloader loads ZERO plugins).
#
# Usage: scripts/verify-against-host.sh [--keep] [--port N]
#   JAVA_HOME=/path/to/jdk-25                     required: the JDK that builds the plugin and runs the host
#   MVN=/path/to/mvn                              Maven (default: mvn on the PATH)
#   HOST_TARGET=/path/to/runway/runway-app/target the host build (default: the kernel this plugin sits in, else ../runway)
#   HOST_JAR=/path/to/runway-app-<ver>-exec.jar  the kernel to verify against (default: the host checkout's
#                                                 runway-app/target jar MATCHING its pom version)
#   PROTECTION_DIR=/dir                           the protection plugin's source folder (plugin.yml, i18n/, templates/,
#                                                 target/runway-plugin-protection.jar); default: the host checkout's
#                                                 runway-plugin-protection/.
#                                                 Set it to "" to skip the CAPTCHA checks.
#   VERIFY_TMPDIR=/dir                            where the throwaway deployment goes (default: $TMPDIR or /tmp)
#   VERIFY_LOG_DIR=/dir                           keep copies of the host logs, the fake relay's log and the two
#                                                 backup manifests there (evidence)
# The script never runs `mvn install` (nothing is written to ~/.m2): it packages this plugin offline against
# whatever runway-spi is already in the local repository.
set -uo pipefail

HERE="$(cd "$(dirname "$(readlink -f "$0")")/.." && pwd)"
# JAVA_HOME must name a JDK 25. MVN (default: `mvn` on the PATH), HOST_TARGET and PROTECTION_DIR (defaults: see
# above) can be set in the environment.
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
PROTECTION_DIR="${PROTECTION_DIR-$HOST_TARGET/../../runway-plugin-protection}"

KEEP="no"; PORT=""
while [ $# -gt 0 ]; do case "$1" in
    --keep) KEEP="yes"; shift ;;
    --port) PORT="${2:?}"; shift 2 ;;
    *) echo "unknown arg: $1"; exit 2 ;;
esac; done

APP_USER=root; APP_PW=Contact-Admin-2026; SITE_USER=owner; SITE_PW=Contact-Site-2026
SECRET_KEY=contact-verify-master-key-2026; RELAY_USER=relay-user; RELAY_PW='Relay-Secret-42'
free_port() { python3 -I -c 'import socket;s=socket.socket();s.bind(("127.0.0.1",0));print(s.getsockname()[1]);s.close()'; }
[ -n "$PORT" ] || PORT="$(free_port)"
SMTP_PORT="$(free_port)"
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

SCRATCH="$(mktemp -d "${VERIFY_TMPDIR:-${TMPDIR:-/tmp}}/contact-verify.XXXXXX")"
cleanup() {
    [ -z "${VERIFY_LOG_DIR:-}" ] || { mkdir -p "$VERIFY_LOG_DIR" \
        && cp "$SCRATCH"/*.log "$SCRATCH"/*-manifest.yml "$VERIFY_LOG_DIR"/ 2>/dev/null; }
    kill "${SMTP_PID:-0}" 2>/dev/null
    # Wait for the host to exit before deleting: a JVM still shutting down rewrites files under external/ and
    # makes `rm -rf` leave the scratch folder behind.
    [ "$KEEP" = "yes" ] || { kill "${PID:-0}" 2>/dev/null; wait "${PID:-0}" 2>/dev/null; rm -rf "$SCRATCH"; }
}
trap cleanup EXIT
EXT="$SCRATCH/external"
mkdir -p "$EXT/plugins"
cp -r "$HERE/target/contact" "$EXT/plugins/contact"

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

# --- Three more sites, copied from the seeded default one (same owner), before the ACTIVE boot reads them --------
SITES="$EXT/sites"
[ -f "$SITES/default/site.yml" ] || { echo "the wizard did not seed $SITES/default"; exit 1; }
make_site() {   # id editorial-level
    cp -r "$SITES/default" "$SITES/$1"
    sed -i -e "s/^id: .*/id: \"$1\"/" -e "s/^- \"localhost\"/- \"$1.test\"/" -e "s/^editorial: .*/editorial: \"$2\"/" \
        "$SITES/$1/site.yml"
}
make_site enforced enforced
make_site reviewed reviewed
make_site captcha advisory
sed -i 's/^  - "default"$/  - "default"\n  - "enforced"\n  - "reviewed"/' "$EXT/site-users.yml"
# A page carrying the widget anchor on the captcha site: its seeded About page, edited on disk.
sed -i 's|<main id="content">|<main id="content"><div id="contact-form" data-widget="contact/form"></div>|' \
    "$SITES/captcha/templates/about.html"
PROTECTION="no"
if [ -n "$PROTECTION_DIR" ] && [ -f "$PROTECTION_DIR/target/runway-plugin-protection.jar" ]; then
    mkdir -p "$EXT/plugins/protection"
    cp "$PROTECTION_DIR/plugin.yml" "$PROTECTION_DIR/target/runway-plugin-protection.jar" "$EXT/plugins/protection/"
    cp -r "$PROTECTION_DIR/i18n" "$PROTECTION_DIR/templates" "$EXT/plugins/protection/"
    mkdir -p "$SITES/captcha/plugins"
    # A reCAPTCHA v2 configuration for the captcha site only (a hand-written plaintext secret is accepted). A POST
    # without a token is refused before any call to the provider, so no network is needed.
    cat > "$SITES/captcha/plugins/protection.yml" <<'YML'
honeypotEnabled: true
captcha:
  enabled: true
  provider: recaptcha-v2
  siteKey: verify-site-key
  secretKey: verify-secret-key
YML
    PROTECTION="yes"
fi

echo "[verify] booting host (ACTIVE) ..."
boot "$SCRATCH/active.log"
wait_for "$SCRATCH/active.log" "Started RunwayApplication" 90 || { echo "ACTIVE did not start"; tail -20 "$SCRATCH/active.log"; exit 1; }
LOG="$SCRATCH/active.log"
TSV="$SITES/default/plugins/contact/submissions.tsv"
lines() { if [ -f "$1" ]; then grep -c . "$1"; else echo 0; fi; }

echo "== plugin load (SPI 1.0.0 contract) =="
EXPECTED=1; [ "$PROTECTION" = "yes" ] && EXPECTED=2
ck "plugins registered ($EXPECTED), 0 blocked" "grep -qE 'plugins: $EXPECTED registered, 0 blocked' '$LOG'"
ck "not blocked / quarantined"       "! grep -qE \"plugin 'contact' is blocked|plugin-quarantine\" '$LOG'"
# Host linkage and the surface rules, in both kernels' words: SPI 0.6.0 WARNed ("uses host internals", "… allowed until
# Runway 1.0"); from SPI 1.0.0 the kernel BLOCKS and logs "linkage scan: <reason>" / the surface reason — a grep for the
# 0.6.0 wording alone can never fail on a 1.0 kernel.
ck "ZERO host-linkage findings"        "! grep -qE \"uses host internals|plugin 'contact' linkage scan\" '$LOG'"
ck "ZERO bean-name/surface findings"   "! grep -qE \"plugin 'contact'.*(allowed until Runway 1.0|bean name|may not contribute)\" '$LOG'"
ck "no WARN/ERROR names the plugin"  "! grep -E ' (WARN|ERROR) ' '$LOG' | grep -qi 'contact'"
ck "no key collision / degraded"     "! grep -qiE 'collision|duplicate key|degraded' '$LOG'"

echo "== static assets =="
ck "contact.css served"            "curl -s -o /dev/null -w '%{http_code}' '$BASE/__plugin/contact/static/contact.css' | grep -q 200"
ck "help page served"              "curl -s -o /dev/null -w '%{http_code}' '$BASE/__plugin/contact/static/contact-help.html' | grep -q 200"

echo "== anonymous POST /contact/submit (no session, no CSRF token) =="
post() { curl -s -o /dev/null -w '%{http_code} %{redirect_url}' -X POST "$@"; }
R="$(post "$BASE/contact/submit" --data-urlencode 'return=/' --data-urlencode 'name=Ann Visitor' \
    --data-urlencode 'email=ann@example.com' --data-urlencode 'message=Hello from a POST' --data-urlencode 'website=')"
ck "valid POST -> 303 ?contact=sent"         "[ \"\${R%% *}\" = 303 ] && echo '$R' | grep -q 'contact=sent'"
ck "the redirect carries no submitted value" "! echo '$R' | grep -qiE 'ann|example|hello'"
ck "the submission is stored (appended to submissions.tsv)" "grep -q $'\tAnn Visitor\tann@example.com\tHello from a POST' '$TSV'"
ck "a GET submission is gone (404) and stores nothing" \
   "[ \"\$(curl -s -o /dev/null -w '%{http_code}' '$BASE/contact/submit?return=/&name=Get&email=get%40example.com&message=via%20GET&website=')\" = 404 ] && ! grep -q 'get@example.com' '$TSV'"
ck "a filled honeypot fakes success and stores nothing" \
   "post '$BASE/contact/submit' -d 'return=/&name=Bot&email=bot%40example.com&message=spam&website=http://spam' | grep -q 'contact=sent' && ! grep -q 'bot@example.com' '$TSV'"
ck "an invalid email -> ?contact=invalid, not stored" \
   "post '$BASE/contact/submit' -d 'return=/&name=Bad&email=nope&message=hi&website=' | grep -q 'contact=invalid' && ! grep -q $'\tBad\t' '$TSV'"
ck "fields in the query string are ignored -> invalid" \
   "post '$BASE/contact/submit?name=Q&email=q%40example.com&message=query' -d 'return=/' | grep -q 'contact=invalid' && ! grep -q 'q@example.com' '$TSV'"
ck "a multipart body is refused 415 by the kernel" \
   "[ \"\$(curl -s -o /dev/null -w '%{http_code}' -X POST '$BASE/contact/submit' -F 'name=M' -F 'email=m@example.com' -F 'message=multi')\" = 415 ]"
ck "exactly one stored submission so far"     "[ \"\$(lines '$TSV')\" = 1 ]"

ck "submissions.tsv is never served publicly" \
   "( for u in '/static/../plugins/contact/submissions.tsv' '/plugins/contact/submissions.tsv' '/__plugin/contact/static/../../submissions.tsv'; do curl -s --path-as-is '$BASE'\$u | grep -q 'ann@example.com' && exit 1; done; exit 0 )"

echo "== the starter page through SitePages (Advisory, Enforced, Reviewed) =="
token() { grep -o 'name="_csrf" value="[^"]*"' | head -1 | sed 's/.*value="//; s/"$//'; }
# One cookie jar per site host; curl --resolve maps <site>.test to the local host so cookies scope correctly.
site_url() { [ "$1" = default ] && echo "$BASE" || echo "http://$1.test:$PORT"; }
sc() {   # site curl-args...
    local site="$1"; shift
    curl -s -H 'Accept-Language: en' --resolve "$site.test:$PORT:127.0.0.1" \
        -c "$SCRATCH/cookies-$site.txt" -b "$SCRATCH/cookies-$site.txt" "$@"
}
login() {
    local u; u="$(site_url "$1")"
    sc "$1" -o /dev/null -X POST "$u/login" --data-urlencode "username=$SITE_USER" --data-urlencode "password=$SITE_PW" \
        --data-urlencode "_csrf=$(sc "$1" "$u/login" | token)"
}
code() { curl -s -o /dev/null -w '%{http_code}' "$@"; }
SETTINGS="$BASE/__admin/plugins/contact/settings"
no_page() { ! ls "$SITES/$1/templates"/contact.* >/dev/null 2>&1; }

echo "== the operator actions are CSRF-protected POSTs (a link or an <img> changes nothing) =="
ck "anonymous GET of either action -> 404 (no GET route)" \
   "[ \"\$(code '$BASE/contact/install-page')\" = 404 ] && [ \"\$(code '$BASE/contact/smtp-test')\" = 404 ]"
ck "anonymous POST without a CSRF token -> 403 (the tenant chain's CSRF check)" \
   "[ \"\$(code -X POST '$BASE/contact/install-page' -d x=1)\" = 403 ] && [ \"\$(code -X POST '$BASE/contact/smtp-test' -d x=1)\" = 403 ]"
ANON_JAR="$SCRATCH/cookies-anon.txt"
ANON_TOKEN="$(curl -s -c "$ANON_JAR" -b "$ANON_JAR" "$BASE/login" | token)"
ck "anonymous POST WITH a valid session token -> 404 (the permission check still applies)" \
   "[ -n '$ANON_TOKEN' ] && [ \"\$(code -c '$ANON_JAR' -b '$ANON_JAR' -X POST '$BASE/contact/install-page' --data-urlencode '_csrf=$ANON_TOKEN')\" = 404 ]"
ck "…and nothing was installed by any of that" "no_page default"
for site in default enforced reviewed; do login "$site"; done
ck "signed-in owner: GET /contact/install-page -> 404 and no page (a cross-site link installs nothing)" \
   "[ \"\$(sc default -o /dev/null -w '%{http_code}' '$BASE/contact/install-page')\" = 404 ] && no_page default"
ck "signed-in owner: POST without the CSRF token -> 403 and no page" \
   "[ \"\$(sc default -o /dev/null -w '%{http_code}' -X POST '$BASE/contact/install-page' -d x=1)\" = 403 ] && no_page default"
ck "signed-in owner: POST with a forged CSRF token -> 403 and no page" \
   "[ \"\$(sc default -o /dev/null -w '%{http_code}' -X POST '$BASE/contact/install-page' -d _csrf=forged-token)\" = 403 ] && no_page default"
sc default "$SETTINGS" > "$SCRATCH/settings-0.html"
ck "the settings page offers the install as a POST form carrying a non-empty CSRF token" \
   "grep -q 'id=\"cp-install-form\" method=\"post\" action=\"/contact/install-page\"' '$SCRATCH/settings-0.html' && grep -qE 'id=\"cp-csrf\" type=\"hidden\" name=\"_csrf\" value=\"[^\"]+\"' '$SCRATCH/settings-0.html'"
ck "…and the SMTP test as a POST form carrying a non-empty CSRF token" \
   "grep -q 'id=\"cn-smtp-test-form\" method=\"post\" action=\"/contact/smtp-test\"' '$SCRATCH/settings-0.html' && grep -qE 'id=\"cn-csrf\" type=\"hidden\" name=\"_csrf\" value=\"[^\"]+\"' '$SCRATCH/settings-0.html'"
ck "…and no link points at either action" "! grep -qE 'href=\"/contact/(install-page|smtp-test)' '$SCRATCH/settings-0.html'"
install() {   # site -> the install action's answer, POSTed with the token from that site's settings page
    local u; u="$(site_url "$1")"
    sc "$1" -X POST "$u/contact/install-page" --data-urlencode "_csrf=$(sc "$1" "$u/__admin/plugins/contact/settings" | token)"
}
install default > "$SCRATCH/install-default.txt"
ck "Advisory: POST with the token installs and publishes at /contact" "grep -qx 'Contact page installed at /contact' '$SCRATCH/install-default.txt'"
curl -s "$BASE/contact" > "$SCRATCH/contact-page.html"
ck "…the public page renders the form as a POST to the anonymous route" \
   "grep -q 'method=\"post\" action=\"/contact/submit\"' '$SCRATCH/contact-page.html' && ! grep -q 'name=\"_csrf\"' '$SCRATCH/contact-page.html'"
ck "…with the return path of the page" "grep -q 'name=\"return\" value=\"/contact\"' '$SCRATCH/contact-page.html'"
install default > "$SCRATCH/install-again.txt"
ck "a second install changes nothing and says where the page is" "grep -qx 'A contact page already exists at /contact' '$SCRATCH/install-again.txt'"
for site in enforced reviewed; do
    install "$site" > "$SCRATCH/install-$site.txt"
    echo "        $site says: $(head -c 300 "$SCRATCH/install-$site.txt" | tr '\n' ' ')"
done
# Enforced: published when the quality checks pass, else a draft with the blocking findings listed.
ck "Enforced: a documented outcome, never an error" \
   "grep -qE '^(Contact page installed at /contact|The contact page was created as a draft because the quality checks blocked publishing; fix it in the page editor: /contact)' '$SCRATCH/install-enforced.txt'"
# Reviewed: the owner holds APPROVE_PAGES, so the gated publish runs; without it the page would wait for review.
ck "Reviewed (as the owner, who may approve): a documented outcome" \
   "grep -qE '^(Contact page installed at /contact|The contact page was created and is waiting in the review queue: /contact|The contact page was created as a draft)' '$SCRATCH/install-reviewed.txt'"
ck "the installed pages exist on every site" \
   "( for s in default enforced reviewed; do ls \"$SITES/\$s/templates\"/contact.* >/dev/null || exit 1; done )"
sc default "$SETTINGS" > "$SCRATCH/settings-1.html"
ck "the settings page shows the installed page's live path" "grep -q 'id=\"cp-path\"[^>]*>/contact<' '$SCRATCH/settings-1.html'"
ck "…and counts the stored submission" "grep -q 'id=\"cn-count\">1<' '$SCRATCH/settings-1.html'"

echo "== the SMTP password: saved ENC(...), handed to the relay in clear (PluginSettings.secret) =="
# A minimal local SMTP relay that accepts AUTH LOGIN/PLAIN and records the credentials and the message.
cat > "$SCRATCH/fake_smtp.py" <<'PY'
import base64, socket, sys, threading
port, log = int(sys.argv[1]), open(sys.argv[2], "a", buffering=1)
def b64(s):
    return base64.b64decode(s.strip()).decode("utf-8", "replace")
def serve(c):
    f = c.makefile("rwb", buffering=0)
    def say(line): f.write((line + "\r\n").encode())
    say("220 fake ESMTP")
    while True:
        raw = f.readline()
        if not raw: break
        line = raw.decode("utf-8", "replace").rstrip("\r\n"); up = line.upper()
        if up.startswith(("EHLO", "HELO")): say("250-fake"); say("250 AUTH LOGIN PLAIN")
        elif up.startswith("AUTH PLAIN"):
            arg = line[10:].strip()
            if not arg: say("334 "); arg = f.readline().decode()
            parts = b64(arg).split("\0"); log.write("AUTH user=%s pass=%s\n" % (parts[1], parts[2])); say("235 ok")
        elif up.startswith("AUTH LOGIN"):
            arg = line[10:].strip()
            if not arg: say("334 VXNlcm5hbWU6"); arg = f.readline().decode()
            user = b64(arg); say("334 UGFzc3dvcmQ6"); pw = b64(f.readline().decode())
            log.write("AUTH user=%s pass=%s\n" % (user, pw)); say("235 ok")
        elif up.startswith(("MAIL", "RCPT", "RSET", "NOOP")): log.write(line + "\n"); say("250 ok")
        elif up == "DATA":
            say("354 go")
            while True:
                d = f.readline().decode("utf-8", "replace")
                if d in (".\r\n", ".\n", ""): break
                log.write("DATA " + d.rstrip("\r\n") + "\n")
            say("250 queued")
        elif up == "QUIT": say("221 bye"); break
        else: say("250 ok")
    c.close()
s = socket.socket(); s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1); s.bind(("127.0.0.1", port)); s.listen(5)
while True:
    conn, _ = s.accept(); threading.Thread(target=serve, args=(conn,), daemon=True).start()
PY
python3 -I "$SCRATCH/fake_smtp.py" "$SMTP_PORT" "$SCRATCH/smtp.log" & SMTP_PID=$!
sc default -o /dev/null -X POST "$SETTINGS" --data-urlencode "enabled=true" \
    --data-urlencode "smtpHost=127.0.0.1" --data-urlencode "smtpPort=$SMTP_PORT" --data-urlencode "smtpAuth=true" \
    --data-urlencode "smtpUsername=$RELAY_USER" --data-urlencode "smtpPassword=$RELAY_PW" \
    --data-urlencode "fromAddress=site@example.test" --data-urlencode "toAddress=owner@example.test" \
    --data-urlencode "_csrf=$(token < "$SCRATCH/settings-1.html")"
CFG="$SITES/default/plugins/contact.yml"
ck "the password is stored ENC(...), never in clear" "grep -q 'smtpPassword: .*ENC(' '$CFG' && ! grep -q '$RELAY_PW' '$CFG'"
R="$(post "$BASE/contact/submit" --data-urlencode 'return=/contact' --data-urlencode 'name=Bea Mailer' \
    --data-urlencode 'email=bea@example.com' --data-urlencode 'message=Please call me back' --data-urlencode 'website=')"
ck "a submission over the configured relay -> ?contact=sent (not queued)" "echo '$R' | grep -q '/contact?contact=sent'"
ck "…the relay was offered the secret in clear" "grep -qx 'AUTH user=$RELAY_USER pass=$RELAY_PW' '$SCRATCH/smtp.log'"
ck "…and received the visitor's message"        "grep -q 'DATA Please call me back' '$SCRATCH/smtp.log'"
ck "…which is stored as well"                  "grep -q 'bea@example.com' '$TSV'"
ck "signed-in owner: GET /contact/smtp-test?to=… -> 404 and no email (a cross-site link sends nothing)" \
   "[ \"\$(sc default -o /dev/null -w '%{http_code}' '$BASE/contact/smtp-test?to=link@example.test')\" = 404 ] && ! grep -q 'link@example.test' '$SCRATCH/smtp.log'"
ck "signed-in owner: POST /contact/smtp-test without the CSRF token -> 403 and no email" \
   "[ \"\$(sc default -o /dev/null -w '%{http_code}' -X POST '$BASE/contact/smtp-test' --data-urlencode 'to=forged@example.test')\" = 403 ] && ! grep -q 'forged@example.test' '$SCRATCH/smtp.log'"
# The console's button: a form POST carrying only the token -> the test goes to the configured recipient.
sc default -X POST "$BASE/contact/smtp-test" --data-urlencode "_csrf=$(sc default "$SETTINGS" | token)" \
    > "$SCRATCH/smtp-test.txt"
ck "the operator's SMTP test (the console form: POST + token) sends over the same relay to the configured recipient" \
   "grep -qx 'Test message sent.' '$SCRATCH/smtp-test.txt' && [ \"\$(grep -c 'RCPT TO:<owner@example.test>' '$SCRATCH/smtp.log')\" = 2 ]"
# The optional `to` override is read from the form BODY, never the query string. A browser form sends the CSRF
# token as the `_csrf` FIELD next to it, so that is the case that must work: the kernel keeps the url-encoded body
# for the route after its CSRF check, so form() carries the field whether the token came as the field or in the
# X-CSRF-TOKEN header. Both ways are checked.
sc default -X POST "$BASE/contact/smtp-test" --data-urlencode "_csrf=$(sc default "$SETTINGS" | token)" \
    --data-urlencode "to=field@example.test" > "$SCRATCH/smtp-test-to-field.txt"
ck "…and honours a 'to' override in the body with the token as a form field (as a browser form sends it)" \
   "grep -qx 'Test message sent.' '$SCRATCH/smtp-test-to-field.txt' && grep -q 'RCPT TO:<field@example.test>' '$SCRATCH/smtp.log'"
sc default -X POST "$BASE/contact/smtp-test" -H "X-CSRF-TOKEN: $(sc default "$SETTINGS" | token)" \
    --data-urlencode "to=header@example.test" > "$SCRATCH/smtp-test-to-header.txt"
ck "…and with the token in the X-CSRF-TOKEN header" \
   "grep -qx 'Test message sent.' '$SCRATCH/smtp-test-to-header.txt' && grep -q 'RCPT TO:<header@example.test>' '$SCRATCH/smtp.log'"
sc default -X POST "$BASE/contact/smtp-test?to=query@example.test" \
    --data-urlencode "_csrf=$(sc default "$SETTINGS" | token)" > "$SCRATCH/smtp-test-to-query.txt"
ck "…but ignores a 'to' in the query string (the test goes to the configured recipient)" \
   "grep -qx 'Test message sent.' '$SCRATCH/smtp-test-to-query.txt' && ! grep -q 'query@example.test' '$SCRATCH/smtp.log' && [ \"\$(grep -c 'RCPT TO:<owner@example.test>' '$SCRATCH/smtp.log')\" = 3 ]"

echo "== backups: a full backup keeps submissions.tsv, a transfer copy leaves it out =="
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
ck "the full backup carries plugins/contact/submissions.tsv" "zipq '$SCRATCH/full.zip' has plugins/contact/submissions.tsv"
ck "…with the submissions in it" "zipq '$SCRATCH/full.zip' read plugins/contact/submissions.tsv | grep -q 'ann@example.com'"
ck "the transfer copy has NO plugins/contact/submissions.tsv" "! zipq '$SCRATCH/transfer.zip' has plugins/contact/submissions.tsv"
ck "…and its manifest lists the omission" "grep -q 'plugins/contact/submissions.tsv' '$SCRATCH/transfer-manifest.yml'"
ck "…while the settings file travels (secret blanked)" \
   "zipq '$SCRATCH/transfer.zip' has plugins/contact.yml && ! zipq '$SCRATCH/transfer.zip' read plugins/contact.yml | grep -q 'ENC('"
ck "the full backup's manifest omits nothing of contact" "! grep -q 'plugins/contact/submissions.tsv' '$SCRATCH/full-manifest.yml'"

echo "== CAPTCHA still enforced on the anonymous POST (protection plugin, one site) =="
if [ "$PROTECTION" = "yes" ]; then
    CTSV="$SITES/captcha/plugins/contact/submissions.tsv"
    CURL_C="http://captcha.test:$PORT"
    curl -s --resolve "captcha.test:$PORT:127.0.0.1" "$CURL_C/about" > "$SCRATCH/captcha-about.html"
    ck "the form carries the site's CAPTCHA challenge" \
       "grep -q 'data-sitekey=\"verify-site-key\"' '$SCRATCH/captcha-about.html' && grep -q 'method=\"post\" action=\"/contact/submit\"' '$SCRATCH/captcha-about.html'"
    ck "a POST without the CAPTCHA token -> ?contact=captcha" \
       "post --resolve 'captcha.test:$PORT:127.0.0.1' '$CURL_C/contact/submit' -d 'return=/about&name=Cy&email=cy%40example.com&message=no%20token&website=' | grep -q '/about?contact=captcha'"
    ck "a POST with a blank CAPTCHA token -> ?contact=captcha" \
       "post --resolve 'captcha.test:$PORT:127.0.0.1' '$CURL_C/contact/submit' -d 'return=/about&name=Cy&email=cy%40example.com&message=blank&website=&g-recaptcha-response=' | grep -q 'contact=captcha'"
    ck "a filled honeypot on that site still fakes success" \
       "post --resolve 'captcha.test:$PORT:127.0.0.1' '$CURL_C/contact/submit' -d 'return=/about&name=Bot&email=b%40example.com&message=x&website=http://spam' | grep -q 'contact=sent'"
    ck "nothing was stored for the CAPTCHA site" "[ \"\$(lines '$CTSV')\" = 0 ]"
else
    skip "CAPTCHA checks (no built protection plugin at '$PROTECTION_DIR')"
fi

echo; echo "RESULT: $PASS passed, $FAIL failed, $SKIP skipped"
if [ "$KEEP" = "yes" ]; then echo "[verify] kept at $BASE (scratch $SCRATCH; kill $PID to stop)"; wait "$PID"; fi
exit "$FAIL"
