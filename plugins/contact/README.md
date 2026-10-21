# Runway Contact plugin

A **contact form** for [Runway](../README.md): an embeddable form content widget that **POSTs** to an anonymous
`/contact/submit` route, which **both emails** each submission over the site's own SMTP relay **and stores** it as
tenant-owned data (read in the console) — belt-and-suspenders, so a message is never lost even if SMTP is down.
CAPTCHA (when the site has one) and honeypot anti-spam.

- **Plugin id:** `contact` — install folder `external/plugins/contact/` · **version 2.1.0**
- **Java package:** `mx.quest.contact` · **SPIs:** `ContentWidget` (`contact`) + `EndpointContributor`
  (`POST /contact/submit` anonymous; `POST /contact/install-page` and `POST /contact/smtp-test`, CSRF-checked)
- **Links only the Runway SPI** (`requiresSpi: ">=1.0.0"`): `PluginSettings` (settings + the SMTP password secret),
  `PluginSiteFiles` (submissions), `Messages`, `AccessCheck`, `SitePages` (the starter page), `CaptchaService`.
  **Needs Runway 1.0.0 or later** (SPI `1.0.0`) — a kernel with an older SPI blocks this release at startup with
  a reason. See [`CHANGELOG.md`](CHANGELOG.md).
- **No plugin dependency:** Jakarta Mail is part of the **core** Runway application (the kernel bundles it for
  its own platform mailer), so `contact` compiles it as `provided` and resolves the kernel's copy at runtime.

## Use it

1. Install `contact`, then restart the host.
2. Enable the plugin, set your thank-you message, and fill in the **SMTP relay** (host, port, auth,
   username/password, STARTTLS/SSL, from/to) on the Contact settings page — the password is stored encrypted.
3. Verify the relay with **Send a test email** on the settings page (operator-only).
4. Put a form on a page: install the ready-made **Contact page** from the settings (it follows the site's
   publishing rules and says what happened), or add `<div id="contact" data-widget="contact/form"></div>` to any
   page.
5. Read submissions on the Contact settings page (newest first) — a copy is kept even when email delivery fails.

> A site with a **blank** SMTP host/recipient simply stores messages (no email) — email is optional. A site sends
> with its **own** relay only (tenant isolation).

> **Privacy.** The form POSTs, so a visitor's fields never appear in a URL, browser history or proxy log. The
> stored submissions are declared **personal data** (`siteData.personal: [submissions.tsv]`): a site's **transfer
> copy** leaves them out (and lists the omission in its manifest); a **full backup** keeps them. See
> [`docs/CONTACT-PLUGIN.md`](docs/CONTACT-PLUGIN.md).

## Build and verify

```bash
export JAVA_HOME=/path/to/jdk-25
mvn verify                  # 75 tests (send seam mocked — no socket)
mvn -DskipTests package     # jar + target/contact/ + the .zip
HOST_JAR=/path/to/runway-app-<ver>-exec.jar VERIFY_TMPDIR=/somewhere scripts/verify-against-host.sh
```

The first build downloads its Maven dependencies, and needs the `runway-spi` jars installed in `~/.m2` as the
[authoring guide §2](../docs/PLUGIN-AUTHORING-GUIDE.md#2-prerequisites) describes.

The verify boots the **packaged** host with this plugin (and, when built, the protection plugin from the host
checkout), on four sites (Advisory, Enforced, Reviewed, and one with a CAPTCHA), and checks 59 things (54 without
the protection plugin): zero
host-linkage and surface warnings; an anonymous POST with no session stored and answered `303` with only an
outcome flag; GET, honeypot, invalid, query-string and multipart submissions refused; the two operator actions
CSRF-checked (a GET changes nothing, a POST without the console token is `403`); the starter page installed
on every editorial level and never twice; the SMTP password stored `ENC(...)` and handed in clear to a local fake
relay; a full backup with the submissions and a transfer copy without them; a POST without the CAPTCHA token
refused.

## License

GNU Affero General Public License v3.0 with an additional permission for plugins (`LICENSE`, `LICENSE-EXCEPTION`),
copyright Quest ICT SAS de CV. The permission means that a plugin which uses only the published SPI may carry any
license.
