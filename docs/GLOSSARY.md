# Runway — Glossary

> **Audience:** everyone.
> **What this is:** the shared vocabulary used across Runway's documentation, defined once
> here so the other documents can use the terms without redefining them.

> **Doc status — growing.** The core terms below are stable and defined now; entries are added
> and sharpened as the platform is built.

---

**Kernel** — Runway's core: a multi-tenant web server and plugin host. It provides the
essential machinery (HTTP serving, the request pipeline, multi-tenancy, the rendering engine,
the security framework, the plugin host, first-run setup) and ships **no domain features** of
its own.

**Plugin** — an installable folder that adds a feature to the platform. It carries its own
code and presentation assets and plugs into the kernel through declared extension points. See
[PLUGIN-DEVELOPMENT.md](PLUGIN-DEVELOPMENT.md).

**SPI (Service Provider Interface)** — the thin, dependency-free set of interfaces and
immutable data records that plugins (and the kernel's own built-in features) implement. It is
released **binary API**: it evolves only by adding, so older extensions keep working.

**Capability-driven** — the kernel decides what to do by inspecting the **capabilities a
component declares** (data), never by checking which specific component it is. A new component
lights up across the platform without any change to the kernel.

**Realm** — an isolated identity domain with its own users, login, and access rules. Runway
has two: the **application realm** (operators who run the platform) and the **tenant realm**
(users who manage an individual site). They never cross.

**Application administrator / superadmin** — an operator in the application realm who runs the
platform itself. The application superadmin is the protected top account that always exists
and cannot be removed, disabled, or demoted.

**Site (tenancy)** — one independently hosted website with its own data, presentation, users,
and configuration, fully isolated from every other site and from the platform's own
configuration.

**Site manager / site-superadmin** — a tenant-realm user who manages a site. The
site-superadmin is the protected top account of a site.

**Master account** — the protected top account of a realm or a site (the application
superadmin; a site-superadmin). It is immutable from inside its own realm and sits above the
normal permission checks.

**Wildcard admin** — a built-in full-access role within a realm. Holding it passes any access
check except operations reserved to the master.

**Permission** — a named, grantable capability declared by the kernel or a plugin (tagged with
its realm). Permissions are assigned to roles and/or directly to users; they are never created
by end users.

**Role** — a named bundle of permissions. Roles are arranged in a per-site/per-realm
**hierarchy**, and access is **additive** — a user gets the union of their roles (and
everything beneath them) plus any direct grants. There are no deny/negative grants.

**`mayAccess`** — the single, central, fail-closed access check every guarded action routes
through: master → wildcard admin (except master-only operations) → an explicit permission →
otherwise denied. An unknown permission is denied.

**Page-as-entry** — the rendering model for public sites: a page is a **complete, normal HTML
document** that is itself the render root; the platform pulls the site's chrome and live data
into it when serving. See [SITE-MANAGER-GUIDE.md](SITE-MANAGER-GUIDE.md).

**Master-as-shell** — the rendering model for the kernel-owned operator UI: a single shell
template owns the page frame and each screen's content is wrapped into it.

**Master (template)** — a site's shared layout/chrome that pages pull their header, footer,
and other shared regions from. (Distinct from a *master account*.)

**Seam** — a named region in a master that a page (or the platform) fills or replaces — for
example the header, footer, or content slot.

**Decoupled-logic templating** — the rule that **no template language appears in any HTML
file**; the dynamic-binding logic lives in a separate paired file, keeping authored HTML 100%
browser-renderable.

**Content widget** — a plugin-provided piece of page content placed via a simple `data-widget`
anchor in the markup; the platform replaces the anchor at render time.

**Head injection** — the platform adding technical `<head>` markup (e.g. SEO/GEO tags) to every
response at the response edge, without requiring any code in a site's templates.

**Filter band** — one of the fixed, ordered phases the request pipeline runs in. A plugin can
place a filter **within** a band but can never reorder the bands or get ahead of core
protection.

**Surface posture** — the per-surface declaration of whether it should be indexed, listed in
the sitemap, and cached, with safe defaults until a surface opts in. See
[PLUGIN-DEVELOPMENT.md](PLUGIN-DEVELOPMENT.md).

**Essential plugin** — a plugin on the kernel's internal security-floor allowlist. If one is
missing or broken, sites serve a maintenance page while the application realm stays reachable
to fix it. A feature plugin cannot promote itself to essential.

**External folder** — the editable directory tree outside the application jar holding
configuration, templates, static assets, translations, sites, plugins, and logs. See
[ADMINISTRATION.md](ADMINISTRATION.md).

**Editorial level** — a site's setting for how strictly publishing is governed, chosen on Site config:
**Advisory** (the default — quality findings are shown, nothing is blocked), **Enforced** (an error-level
quality finding refuses a publish) or **Reviewed** (enforced, and publishing needs a user with *Approve
pages*; everyone else submits for review). See [SITE-MANAGER-GUIDE.md](SITE-MANAGER-GUIDE.md) §10.

**Pending revision** — on a *Reviewed* site, an edit to a page that is already live, saved by someone who
cannot publish. It is kept beside the page until an approver approves it (it replaces the live page) or sends
it back; until then visitors, search and the sitemap see only the live page.

**Site insights** — a site's own report of its most-visited pages and the addresses that most often answered
"not found" (404), counted from its access log; paths and counts only, never a visitor's address.

**Audit journal** — the record of who did what: the application realm's activity log and each site's audit
log. Lines carry the time with its UTC offset, the actor and the action, never a password or a setting's
value. See [ADMINISTRATION.md](ADMINISTRATION.md) §14.

**Transfer copy** — a download of a site for handing it to someone else: the site's content and presentation
without its users, secrets or hosts. Distinct from a **backup**, which is complete so that it can restore the
site exactly. See [ADMINISTRATION.md](ADMINISTRATION.md) §13.

**Layout marker** — `external/layout.yml`, the file that records which version of the data-folder layout is in
use. A Runway build that finds a newer layout than it understands refuses to start rather than write into it.
See [CONFIGURATION-AND-DATA-STABILITY.md](CONFIGURATION-AND-DATA-STABILITY.md).

**Format version** — the `formatVersion` a backup archive records in its `manifest.yml`. Restore accepts the
same or an older format and refuses a newer one, before touching the live site.
