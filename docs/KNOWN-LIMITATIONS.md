# Known limitations — the state of the code now

> **What this is:** an honest, current list of what Runway does **not** do, or does only partially, in the
> code as it stands (release `1.0.0`; the exact version is in the [CHANGELOG](../CHANGELOG.md)). The plugin
> contract is frozen at SPI `1.0.0` and enforced at load time ([SPI-STABILITY.md](SPI-STABILITY.md));
> configuration, the data folder and backups are governed by
> [CONFIGURATION-AND-DATA-STABILITY.md](CONFIGURATION-AND-DATA-STABILITY.md), which binds from `1.0.0`. The data
> folder and backup archives carry the version markers that promise is built on (`external/layout.yml`, a
> backup's `formatVersion`). Read the CHANGELOG's upgrade notes and take a backup before upgrading.

This file is part of the release and is kept truthful: an item leaves the list in the same change that
closes it. Items marked **after 1.0** are decided: they are not part of the `1.0.0` scope.

## Platform

- **Clustering is single-writer.** Multi-node works and is opt-in (`runway.cluster.enabled=true`): HTTP
  sessions live in a Hazelcast `IMap` and cache-invalidation events are relayed over a Hazelcast topic, so
  instances behind a load balancer share sessions and evict in step. The default is **off** (no Hazelcast
  member, the container's own `HttpSession`). Not built (**after 1.0**): **read-only edge/replica nodes**
  and a **distributed write lock**. The filesystem/YAML stores serialize writes per site within one JVM only,
  so two nodes writing the same site are last-writer-wins at file granularity: keep authoring on one node.
- **Clearing the template cache acts on one node.** *Platform settings → Clear template cache* empties the
  cache of the node that answered the request. On a cluster, a site **reload** evicts that site's templates
  on every node (its change event is relayed); a cluster-wide clear-all needs a relay message type that does
  not exist yet (**after 1.0**).
- **Each node needs the same public-page nonce key.** `external/csp-nonce.key` is created per node; copy it
  to every node so a `304` from one node matches a page cached from another (Administration §8.4). With
  different keys each node has its own ETags: correct, just fewer `304`s.
- **Linux-only installer.** The self-extracting `.bin` targets Linux with systemd. A Windows installer is
  **after 1.0**.
- **Backups go to folders, not to object storage.** Per-site backups, retention, the opt-in schedule and the
  best-effort **off-host mirror folder** (`runway.backups.offsite-dir`) ship. Pushing backups to S3/GCS
  directly is **after 1.0**, as a plugin; until then point the mirror at a mounted share or sync it with a
  sidecar.

## Security

- **The public-page CSP is no stronger than the host allowlist against injected markup — and no weaker.**
  Public pages carry a nonce derived from the page version plus `'strict-dynamic'`, and the response edge
  stamps that nonce only on the scripts the `'self'` + host-allowlist policy would have run (same-origin or
  allowlisted `src`; never an inline script). An injected inline script or foreign-host script therefore stays
  blocked in `enforce` mode, but an injected `<script src>` pointing at the site itself or at an allowlisted
  host runs — as it did under the allowlist policy — and, through `'strict-dynamic'`, may load further scripts
  from any host. Template escaping is the defence against injection itself. What the nonce closes is the
  host-allowlist bypass for parser-inserted scripts, and it blocks scripts written at runtime
  (`document.write`).
- **Some responses keep the host-allowlist policy.** Responses without a page version (error pages, plugin
  endpoints, the maintenance page), a page larger than the 8 MiB rewrite buffer, and a page whose markup
  already contains its own nonce (copied in through an input the page version does not fold — e.g. into an
  `<iframe srcdoc>`) or a foreign preload hint the edge cannot remove (below) carry `'self'` plus the host
  allowlist, without a nonce.
- **Inline-SVG scripts do not run under `enforce`.** The stamp needs a `src` attribute and an empty element:
  an SVG `<script href>` names its file in `href` (which an HTML `<script>` ignores, running its text), and an
  SVG `<script src>` runs its text, so neither can be stamped safely without building the document tree. Load
  the code with a normal `<script src>` in the page. For the same reason a `<script src>` placed inside an SVG
  `<title>` or `<style>` is left unstamped: the edge reads those as text (as HTML does), while inside `<svg>` the
  browser does not — under `enforce` such a script is blocked rather than guessed at.
- **Preload hints to a host outside the policy are removed from public pages.** Under `'strict-dynamic'` a browser
  fetches a `<link rel="modulepreload">` or `<link rel="prefetch">` from any host (measured in Chrome 155), so the
  edge removes such a link — and a `preload` with a script-class `as` — unless its URL is on the site's origin or a
  host the policy names. A hint written inside an SVG `<title>`/`<style>` (or another element the edge reads as
  text), or inside an `<iframe srcdoc>`, cannot be removed safely; that response then falls back to the
  host-allowlist policy without a nonce and with `no-store` (the fetch is blocked; the page loses shared caching
  until the hint is gone). A page that merely *shows* such a tag written literally — unescaped inside a
  `<textarea>`, or in an inline script's string — falls back the same way, because the edge builds no document
  tree and cannot tell which element the browser will make of it; a sample escaped as text (`&lt;link …&gt;`, what
  a code block normally holds) is not affected. The same fallback covers markup the edge cannot read with certainty
  the way the browser will: after an inline `<svg>`, `<math>`, `<select>` or `<template>`, a
  raw-text element (`<style>`, `<title>`, `<script>` …) or `<![CDATA[` whose two possible readings disagree, a
  `<noscript>` that reads differently with scripting on and off, or a `<base>` there. Well-formed pages never reach
  it; the edge follows every tokenizer state that does not depend on the document tree (bogus comments, DOCTYPE,
  end-tag attributes, the script escape states), checked against Chrome 155 on a corpus of crafted pages.
- **`preconnect`, `dns-prefetch` and `prerender` hints reach any host, under any policy.** CSP has no directive for
  the first two, and Chrome's prerender (NoState prefetch) contacted a foreign host under the host-allowlist policy
  too (measured in Chrome 155). Injected markup can therefore make a visitor's browser open a connection to (never
  run code from) a host of its choosing; this is unchanged by the public nonce, and template escaping is the
  defence.
- **A site's own scripts can act as that site's signed-in console users.** A site's public pages and its console
  (`/__admin`) share one origin, and CSP cannot separate two documents on one origin: a script on a public page can
  call the console with the session of a site user signed in in the same browser. And whoever can put a `.js` file
  on a site (*Configure site*, or template editing) can run any script on its public pages, because under
  `'strict-dynamic'` a same-origin script may load others from any host. The operator console is kept off site host
  names for this reason (Administration §8.6); separating a site's console onto a host name of its own is a design
  for after 1.0.
- **CSP is report-only out of the box.** `runway.csp.mode: enforce` is the operator's switch after reviewing
  the violation log (Administration §8.4).

## Console and content

- **Accessibility: structural baseline only.** Every console screen has accessible names, `lang`,
  landmarks, a skip link, `aria-current` on the active kernel menu entry and table header scopes, and a test
  keeps them there. Not done yet: a **colour-contrast audit**, **live regions** for status messages that
  appear without a page load, and `aria-current` on **plugin-contributed menu entries** (the kernel's own
  entries carry it; a plugin's entry is marked active by nothing). The password show/hide toggle stays out
  of the tab order on purpose (its name and state are exposed).
- **The page editor's live preview follows the code, not the metadata.** Unsaved edits in *Page HTML* and
  *Decoupled logic* render as you type; unsaved title, path or master changes show only after **Save**, and
  the popped-out preview window always shows the saved page. If a live render fails (no answer within 15
  seconds), the panel returns to the saved page and stops following edits until the editor is reloaded.
- **A site's storage limit covers its uploads, not every file it has.** The limit on Sites (off by default)
  counts and gates what is under the site's `static/` folder: the Static files and Documents uploads. Pages,
  fragments, pending revisions and messages (each save bounded by the 16 MiB form limit, not in number), plugin
  files (`sites/<id>/plugins/`, 16 MiB per write) and the site's own logs and statistics are neither counted nor
  refused, and nothing bounds the **number** of files, so many tiny files can exhaust a filesystem's inodes before
  its bytes. On a server whose sites are not all yours, also put the data folder on its own filesystem or mount.
  The check holds a lock on one node only: on a cluster, two nodes uploading to one site at the same moment can
  both pass it (authoring belongs on one node anyway, see *Clustering*).
- **No per-site bandwidth chart for the operator.** `/__app/bandwidth` lists every site's totals; the
  per-site chart is on the site's own console only.
- **Site insights count crawlers.** The tenant **Insights** screen (top 404s, most-visited pages) reads the
  per-site access log, which records no user agent, so a crawler's requests count like a visitor's (the
  screen says so). A busy site's tallies can be approximate: each keeps at most 5,000 distinct paths and a
  scan stops after two million lines, and the screen flags both.
- **Audit export is capped.** The journal viewers' CSV download carries at most 10,000 rows per request
  (a capped download carries `X-Runway-Audit-Incomplete: true`); narrow the date range for more.
- **Six path advisories are not quality checks.** The page editor's checks on page paths stay in the editor
  rather than in the `PageQualityCheck` set, because the checks' view of a page does not carry the raw
  per-language paths; they are advisory and never block publishing on an *Enforced* or *Reviewed* site.

## Plugins

- **Only the essential protection plugin ships in the installer.** It is seeded into
  `external/plugins/protection/` on a fresh install and refreshed on every upgrade (it is lockstep with the
  kernel; the previous jar and descriptor are kept as `*.bak`, edited templates/i18n are backed up). Every
  other plugin (such as search and search-lucene) is a deliberate operator install; there is no plugin
  marketplace or auto-update path.
- **Plugins built for the pre-1.0 plugin contract do not load.** The plugin contract is frozen at SPI `1.0.0`
  ([SPI-STABILITY.md](SPI-STABILITY.md)). A plugin with a `mainClass` that declares no `requiresSpi` floor of at
  least `0.4.0` — or any plugin that links Runway's internal classes — shows **Blocked** on **Plugins** with the
  reason; the platform runs without it until you install the plugin's release made for Runway 1.0
  (Administration §11).
- **A plugin cannot add servlet filters, security chains or CSP hosts.** These are outside the 1.0 contract (they
  reach every request); only the essential protection plugin uses them. A plugin whose script loads from or frames
  a third-party host needs the operator to add it to `runway.csp.extra-hosts`.
- **A multipart body reaches a plugin route raw.** A plugin route receives a `multipart/form-data` body as bytes
  and parses it itself, and takes its CSRF token in the `X-CSRF-TOKEN` header (no first-party plugin accepts
  uploads).
- **Billboard and News plugins are not built** (**after 1.0**, as plugin releases).
- **The search plugin's rate limit is a courtesy limiter, not a defence.** It keys on the client address after
  the kernel's trusted-proxy gate; the kernel's own edge guard is the abuse floor.
- **A search index rebuilds by rendering every page once per language.** A page is indexed as rendered in
  each language it exists in (bundle strings included), so the first query after a change renders the site's
  pages to rebuild the index — slower than later queries on a
  large multilingual site. A change no event announces (a template edited on disk) reaches the index after the
  search plugin's freshness window or the search settings' **Rebuild index** button.

## By design — not limitations

- **No database.** The kernel is filesystem/YAML-backed on purpose; there is no SQL layer to
  configure. A deployment that genuinely needs one adds it as a **datasource-provider plugin** (the
  first-run wizard therefore provisions the app superadmin + first site, never a DB).
- **Central seed changes do not back-propagate to existing tenants.** Each tenant owns its copy of the
  seeded master/templates after creation (the self-containment law); improving the seed affects only
  *new* tenants. This is the deliberate price of complete tenant isolation and portable backups.
- **No public draft-preview link.** A draft is previewed inside the console only; there is
  no shareable URL that shows unpublished content to someone without a console account.
