# Changelog

All notable changes are recorded here. Versions follow [Semantic Versioning](https://semver.org/).

## [2.3.0] — 2026-10-21

First public release of `search`: on-site search with a search-box widget that renders results on an editable
page, a JSON API, highlighted
matches and a no-results log; each language searches its own text. Publishes its own SPI (`SearchCorpus`,
`SiteSearchEngine`) so an engine plugin such as `search-lucene` can replace the built-in engine. Requires SPI
`1.0.0` (Runway `1.0.0` or later). Licensed under the GNU AGPL v3.0 with an additional permission for plugins
(`LICENSE`, `LICENSE-EXCEPTION`).
