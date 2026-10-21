# Changelog

All notable changes are recorded here. Versions follow [Semantic Versioning](https://semver.org/).

## [2.3.1] — 2026-10-21

### Added

- **French for visitors.** `messages_fr.properties` carries the strings visitors see — the search box (placeholder
  and button), the results heading, the result count, "no results" and the rate-limit notice — so a French page
  shows them in French instead of falling back to English. The console's strings stay in English and Spanish, the
  languages the console offers. A site's own `messages_fr.properties` still overrides any of them.

### Upgrade

Replace the plugin folder with this release's zip and restart. No setting or stored file changes; search-lucene
3.3.0 works with it unchanged.

## [2.3.0] — 2026-10-21

First public release of `search`: on-site search with a search-box widget that renders results on an editable
page, a JSON API, highlighted
matches and a no-results log; each language searches its own text. Publishes its own SPI (`SearchCorpus`,
`SiteSearchEngine`) so an engine plugin such as `search-lucene` can replace the built-in engine. Requires SPI
`1.0.0` (Runway `1.0.0` or later). Licensed under the GNU AGPL v3.0 with an additional permission for plugins
(`LICENSE`, `LICENSE-EXCEPTION`).
