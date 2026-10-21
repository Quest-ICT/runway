# Runway Contact plugin — design & reference

> The reference for a plugin that **accepts input from anonymous visitors**, **stores per-tenant
> personal data**, and **sends email** within the kernel's constraints (CSRF, servlet-free endpoints, tenant
> isolation, encrypted secrets) — using **only the Runway SPI** (version 2.1.0, SPI 1.0.0). Grounded in the Runway
> kernel as built.

## 1. What it does

- **Form widget** — a `ContentWidget` (`key = "contact"`) that renders the shared `ContactForm` inline wherever a
  page carries `<div id="…" data-widget="contact/…"></div>`. Honors the per-tenant `enabled` flag. The plugin owns
  **no visible page**: the form lives on a normal, editable site page. The widget is **query-aware** — the submit
  handler redirects back with `?contact=sent|invalid|captcha|queued`, and the widget re-renders that outcome
  (thank-you, or the form with an error) from the flag, so the page it sits on stays an ordinary, cacheable page.
- **`POST /contact/submit`** — an `EndpointContributor` route declared **anonymous** (`Route.anonymousPost`, §2)
  that reads the fields from the url-encoded **body only**, runs the honeypot + (when configured) the CAPTCHA
  check, and for a valid message **stores it AND emails it** over the site's own SMTP relay, then answers
  **`303 See Other` back to the page the form lives on** (the same-origin `return` field, else the `Referer`) with
  only the outcome flag. It renders nothing itself. Any other method on this path is a 404.
- **`POST /contact/install-page`** — an ordinary (CSRF-checked, §2a) route, operator-gated (`contact|settings`,
  checked with `AccessCheck`; fails **closed** with a 404 otherwise): installs a ready-made, editable tenant page carrying the form widget (slug
  `contact`, path `/contact`) through **`SitePages.install`**. The kernel never overwrites and follows the site's
  editorial level; the plain-text answer names the outcome (§1c). Once installed it is an ordinary site page
  (listed under Pages; restyle / move / rename it), so the operator, not the plugin, owns the URL and chrome.
- **`POST /contact/smtp-test`** — an operator-only diagnostic (same CSRF check and gate) that sends a test message
  over the site's relay to verify the SMTP configuration — to the configured recipient, or to an optional `to` field
  of the form body (never the query string).
- **Console** — a dashboard card (submission count) and an admin fragment with a **"Contact page"** card (install
  the ready-made page with a button, or see its live path when already installed — read fresh with
  `SitePages.pathOf`, so it follows a move and counts a draft or a page in review), the **SMTP readiness**
  ("Configured / Not configured" badge + a "Send a test email" button) and a list of
  **recent submissions** (name, email, message excerpt, time), gated by the `settings` control point.

## 1a. Belt-and-suspenders: store AND send

A valid submission is **always stored first** (`ContactStore`, tenant TSV), **then** emailed (`ContactMailer`).
The store is unconditional, so a message is **never lost** even if SMTP is down:

- **Send OK** → stored + emailed; the visitor sees the thank-you.
- **Send FAILED** (a configured relay rejected/unreachable) → stored; the failure is logged and the visitor is
  redirected with `?contact=queued`, which the widget renders as the thank-you plus an unobtrusive "message
  received; email delivery is delayed" note. The relay internals
  are never exposed to the visitor.
- **NOT_CONFIGURED** (blank host/recipient) → stored silently, no note — a site may legitimately choose to only
  store messages and read them in the console.
- **PROVIDER_MISSING** (Jakarta Mail off the classloader) → stored; reported. The kernel bundles Jakarta Mail as
  a core capability, so this is effectively unreachable, but `ContactMailer` still guards the `LinkageError` at
  the send seam so the request can never crash.

## 1b. Email over the kernel's Jakarta Mail

The plugin compiles `jakarta.mail-api` as **`provided`** — the **kernel** bundles the Jakarta Mail + Angus stack
as a core capability (for its own platform mailer), and the plugin classloader reaches it parent-first. This
plugin **never bundles Jakarta Mail itself** (that would be a second, shadowing copy on the classloader). It
therefore needs **no `requires:`** gate.

- The send seam is the `MailTransport` interface (`JakartaMailTransport` delegates to `jakarta.mail.Transport` in
  production; mocked in tests so no socket is opened).
- `ContactMailer.build(...)` constructs a `MimeMessage` from `SmtpSettings`: From = the site's `fromAddress` (or
  the recipient when blank), To = the site's `toAddress`, Reply-To = the visitor's email (so a reply reaches
  them), a site-prefixed Subject, and a plain-text body. The `Session` always uses the `smtp` protocol
  (implicit TLS rides `mail.smtp.ssl.enable`, STARTTLS `mail.smtp.starttls.enable`) and a static `Authenticator`
  when `smtpAuth` is on.

### Per-site SMTP settings (tenant-owned, isolated)

All relay config is per-tenant `siteSettings` over `sites/<id>/plugins/contact.yml`, so **a site sends with its
OWN relay only** — never another tenant's:

| key | type | notes |
|---|---|---|
| `smtpHost` | string | blank ⇒ not configured ⇒ mail skipped (stored only) |
| `smtpPort` | int (1..65535, default 25) | |
| `smtpAuth` | boolean (default false) | when on, uses username/password |
| `smtpUsername` | string | |
| `smtpPassword` | **secret** | stored `ENC(...)`-encrypted; the config file is `chmod 600`; **bound** to `smtpHost`, `smtpPort`, `smtpUsername`, `smtpStarttls`, `smtpSsl` (below) |
| `smtpStarttls` | boolean (default false) | STARTTLS upgrade on a plain connection |
| `smtpSsl` | boolean (default false) | implicit-TLS (SMTPS) |
| `fromAddress` | string | envelope From; blank ⇒ falls back to the recipient |
| `toAddress` | string | recipient; blank ⇒ not configured ⇒ mail skipped |

Every value is read from the `SettingsView` that `PluginSettings.site(siteId)` returns (the descriptor defaults
overlaid by what the operator saved). The **secret** password is stored `ENC(...)` by the platform; the plugin
reads it in clear with **`PluginSettings.secret(siteId, "smtpPassword")`** at send time — the kernel decrypts it
(a hand-written plaintext value is returned as is) and answers empty when it is unset or cannot be decrypted (the
relay then rejects the login and the submission is reported "delivery delayed", still stored). The password is
never taken from the settings view (the kernel leaves secret keys out of it, and a test holds the plugin to that).
The plaintext lives only in the transient `SmtpSettings` value handed to Jakarta Mail — never logged
(`SmtpSettings.toString()` masks it) and never stored by this plugin.

**The password is bound to the relay login (`secretBoundTo`).** The descriptor declares
`secretBoundTo: [smtpHost, smtpPort, smtpUsername, smtpStarttls, smtpSsl]` on `smtpPassword` (the TLS switches
too: turning TLS off toward the same host would otherwise send the stored password in clear). A settings save that
changes any of those five **without retyping the password** makes the kernel drop the stored password (the field is left blank, so the
next send fails the login and the submission is stored as "delivery delayed" until the password is entered again).
Why: the settings form never shows the stored password, but whoever may edit the settings could otherwise point the
site's saved credentials at a server of their own — change the host, keep the password, press **Send a test
email** — and read the password off the wire. Binding it means a stored password is only ever offered to the relay
login it was typed for. Retyping the password in the same save keeps it (the new value is stored). Changing any
other setting (`enabled`, the thank-you message, sender, recipient, `smtpAuth`) leaves it alone. The binding is
enforced by the kernel, which also tells the operator on the settings page when a save dropped the password.

## 1c. The starter page and the site's editorial level

`SitePages.install` creates the page as a draft and then applies the site's level; the endpoint reports each
`InstallResult` outcome in the operator's console language (`Messages.console`):

| Outcome | When | The answer (EN) |
|---|---|---|
| `PUBLISHED` | Advisory; Enforced or Reviewed with the checks passing (on Reviewed, for someone who may approve pages) | `Contact page installed at /contact` |
| `PENDING_REVIEW` | Reviewed, and the operator may not approve pages | `The contact page was created and is waiting in the review queue: /contact` |
| `DRAFT_BLOCKED` | the quality checks blocked publishing | `The contact page was created as a draft because the quality checks blocked publishing; fix it in the page editor: /contact` + one `- code: message` line per blocking finding |
| `ALREADY_EXISTS` | a page with the slug `contact` exists, in any state | `A contact page already exists at <its current path>` |
| a constant added in a later SPI | — | `The contact page install finished: <OUTCOME> <path>` |

A refused install (another page serves `/contact`, a write failure) answers `500` "Could not install the contact
page." and leaves no draft behind (the kernel removes it).

## 2. POST on an anonymous route (the CSRF constraint, solved)

A public form on a cacheable page cannot carry the tenant chain's **session-based CSRF token**. The easy way
around that — a form that submits by **GET** — would put a visitor's name, email and message in their browser
history, in `Referer` headers and in proxy logs. The SPI has a contract for exactly this case, and the plugin uses
it:

- The endpoint declares **`Route.anonymousPost("/contact/submit", posture)`**. The kernel exempts **exactly that
  method and path** from CSRF, on a site's host only, and in exchange takes away what a forged cross-site
  submission could use: the handler runs with **no principal** (`AccessCheck.may` is `false` even for a signed-in
  administrator), it is handed only `application/x-www-form-urlencoded` bodies (anything else is answered `415`),
  and every other route keeps its CSRF check.
- The form (`ContactForm`) is `method="post"` with that encoding. The handler reads the fields **from the body
  only** (`RouteRequest.form()`), so a field put in a query string is ignored and personal data has no reason to
  travel in a URL; the `303` back to the page carries only `?contact=<outcome>`.
- **The abuse guards stay in front of the store:** a hidden **honeypot** field (`website`; a submission that fills
  it is silently dropped — the bot sees a fake success, nothing is stored or sent), the site's **CAPTCHA** when one
  is configured (`CaptchaService.verify(site, token, req.clientIp())` — the client IP after the kernel's
  trusted-proxy gate; the token is read from the body too), and the platform's **per-address rate limit** (the
  protection plugin's edge guard, which sees every request before any endpoint).

## 2a. The operator actions are CSRF-checked POSTs

Installing the starter page creates a page, and the SMTP test sends an email. Were they `GET` links, a signed-in
site manager who followed a link on another web site — or merely loaded a page with an
`<img src="https://their-site/contact/install-page">` — would perform the action without meaning to (the browser
sends the session cookie with the request; the permission check passes because it *is* the manager). The SMTP test
would be worse: a `?to=` choosing the recipient would let such a link make the server email an arbitrary address.

Both are therefore **ordinary `POST` routes** — deliberately **not** `Route.anonymousPost`, which would switch the
CSRF check off. The kernel's tenant chain demands the console session's CSRF token on them:

- the admin fragment renders each action as a `<form method="post">` with a hidden `_csrf` field bound to the
  request's token (`th:attr="name=${_csrf.parameterName},value=${_csrf.token}"`, the same pattern as the search
  plugin's "Clear list"); the answer opens in a new tab;
- a `POST` without a valid token is refused `403` by the kernel (the "session expired" page) before the plugin runs;
- a `GET` (a link, an `<img>`) matches no route, so the kernel answers `404` and nothing changes; the handler also
  refuses any non-`POST` method itself, as a second line;
- the `contact|settings` permission check still runs after the token check (an anonymous visitor who holds a valid
  session token gets `404`).

**The body reaches the plugin whole.** The SMTP test's optional `to` override is read from the form body only,
never the query string. The kernel keeps the url-encoded body for the route after its CSRF check, so `form()`
carries every field whether the token travels as the `_csrf` form field — as a browser form sends it — or in the
`X-CSRF-TOKEN` header. The console's SMTP-test button sends no `to`, so it always mails the configured recipient;
the override is for a scripted call.

## 3. Storage (tenant-owned, backup-safe)

Submissions go through the kernel's **`PluginSiteFiles`** (this plugin's per-site folder, bound to the plugin by
its jar; the kernel confines every path to `sites/<id>/plugins/contact/`, real path included) to
`sites/<id>/plugins/contact/submissions.tsv` (an existing file is read and appended in place, so stored
submissions survive an upgrade of the plugin):

- **Outside** the served `static/` tree — never publicly downloadable (the verify asserts a traversal attempt on
  it does not return 200).
- **Inside** the tenant folder — so a **full backup** carries it automatically; no `BackupParticipant` needed.
- **Declared personal data** in `plugin.yml` (`siteData: personal: [submissions.tsv]`): a **transfer copy** of the
  site (the console's "Download a transfer copy") leaves it out and lists `plugins/contact/submissions.tsv` under
  `omitted:` in the archive's `manifest.yml`. A restored transfer copy starts with no submissions, which the store
  reads as empty.
- One **sanitized** TSV line per submission (`epochMillis \t name \t email \t message`; tabs/newlines stripped so
  the format is unambiguous, fields length-capped at 4,000 characters), bounded to the most recent
  `MAX_SUBMISSIONS` (500), which keeps the file far below the kernel's 16 MiB write cap. Each kernel write is
  atomic; the append (a read-modify-write) is serialized per site by the store. An append that cannot read the
  existing file fails rather than rewriting it from nothing; the console count and list then show nothing (logged).

Everywhere the plugin echoes a value (form re-render, thank-you, admin listing) it HTML-escapes it (`Html.escape`).

## 4. Inventory

| Piece | Type | File |
|---|---|---|
| `mainClass` `@Configuration` | `ContactConfig` | wires widget + endpoint + store + mailer + admin model from SPI services; every bean name starts with `contact` |
| Form widget | `ContactWidget` (`ContentWidget` `contact`, query-aware) | inline form + outcome re-render |
| Endpoint | `ContactEndpoint` (`EndpointContributor`: anonymous `POST /contact/submit`; CSRF-checked `POST /contact/install-page` and `POST /contact/smtp-test`) | submit+store+send+303, starter page via `SitePages`, gated test |
| Shared form markup | `ContactForm` | the `<form>` the widget renders |
| Storage | `ContactStore` + `ContactSubmission` | append/read tenant TSV |
| Mail send | `ContactMailer` + `MailTransport`/`JakartaMailTransport` + `SmtpSettings` + `MailSendResult` | build MIME + send over the site relay |
| Console model | `ContactAdminModel` (+ `SitePages.pathOf`) + `templates/` | contact-page install card + dashboard card + readiness + submissions table |
| Front-end | `static/contact.css`, `static/contact-help.html` | self-contained |
| i18n (EN/ES) | `i18n/` | form + console + SMTP labels/results |

## 5. What it links (SPI 1.0.0)

`pom.xml` depends on `runway-spi` only (`runway-spi-testkit` for tests); **`runway-app` is not a dependency in any
scope**, so a host import does not compile, and `ContactConfigTest` scans the compiled classes' constant pools for
any `mx.quest.runway` reference outside `mx.quest.runway.spi` — the kernel's own load-time scan finds nothing.

| Need | SPI service |
|---|---|
| the site's settings | `PluginSettings.site(site)` → `SettingsView` |
| the SMTP password in clear, at send time | `PluginSettings.secret(site, "smtpPassword")` |
| the stored submissions | `PluginSiteFiles` (`submissions.tsv`) |
| visitor text / operator answers | `Messages.site` / `Messages.console` |
| the operator gate | `AccessCheck` (the `authz` bean) |
| the starter page | `SitePages.install` / `SitePages.pathOf` |
| the site's CAPTCHA | `CaptchaService.verify(site, token, req.clientIp())` |
| the submitted fields | `RouteRequest.form()` of an anonymous POST |

`requiresSpi: ">=1.0.0"`: a kernel with an older SPI blocks the plugin before wiring, with a reason.

## 6. Verify

`scripts/verify-against-host.sh` (honours `JAVA_HOME` (required), `MVN`, `HOST_TARGET`, `HOST_JAR`, `VERIFY_TMPDIR`,
`VERIFY_LOG_DIR`, `PROTECTION_DIR`; never
runs `mvn install`) boots the **packaged** host in ACTIVE mode with four sites — the wizard's default (Advisory)
plus an Enforced, a Reviewed and a CAPTCHA site copied from it — and, when it is built, the protection plugin from
the host checkout. It checks: the plugin loads with zero host-linkage and bean-name/surface warnings; an anonymous
form POST with no session or token is stored and answered `303 ?contact=sent` with no submitted value in the
`Location`; a GET submission is a 404, a honeypot fakes success, an invalid email, fields only in the query string
and a multipart body are refused, and nothing of those is stored; the operator actions are CSRF-checked POSTs
(a `GET` of either is a `404` and changes nothing even for the signed-in owner, a `POST` without or with a forged
token is a `403` with no page created and no email sent, an anonymous `POST` holding a valid session token is a
`404`, and the settings page renders both as `POST` forms carrying a non-empty token and links to neither); the
starter page installs (`POST` with the settings page's token) on all three editorial levels and a second install
changes nothing; the settings page shows the page's path and the count; the SMTP
password saved through the settings form is stored `ENC(...)` and is offered in clear to a local fake relay (a
small Python SMTP listener) for a submission and for the SMTP test (the console form to the configured
recipient; a body `to` override with the token as a form field, as a browser form sends it, and again with it in
the `X-CSRF-TOKEN` header; a `to` in the query string is ignored); a full backup carries `submissions.tsv` and a
transfer copy does not, its manifest listing the omission; on the CAPTCHA site a POST without (or with a blank)
token is answered `?contact=captcha` and nothing is stored; the static assets serve and the submissions are never
served publicly.

Unit tests (75) cover the store over the testkit's `TempDirSiteFiles` (`ContactStoreTest`, incl. an existing file
read in place and the unreadable-file rule), the widget rendering incl. the POST form, outcome flags + escaping
(`ContactWidgetTest`), the endpoint incl. POST-only and body-only acceptance, the CAPTCHA with the gated client
IP, store-then-send ordering, redirect outcomes, return-path safety, every install outcome, the gated SMTP test, POST-only operator actions (a GET installs and
sends nothing even for a permitted operator; the SMTP recipient is read from the body only) (`ContactEndpointTest`),
the admin fragment's POST forms carrying the CSRF token, no link to an action, unique ids
(`ContactAdminTemplateTest`), the send path incl. the MIME build against a mock transport and the password taken only
from the secret (`ContactMailerTest`), and the plugin's contract with the host (`ContactConfigTest`: wiring from
SPI services, bean-name prefix, template bean references, the floor, version, personal-data and secret
declarations, EN/ES key parity, the constant-pool scan).

> **Send tests open no socket.** The send seam (`MailTransport`) is mocked, so `ContactMailerTest` exercises the
> full `MimeMessage` build and the store/send/report contract without touching the network.
