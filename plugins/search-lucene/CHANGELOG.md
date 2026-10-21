# Changelog

All notable changes are recorded here. Versions follow [Semantic Versioning](https://semver.org/).

## [3.3.0] — 2026-10-21

First public release of `search-lucene`: an Apache Lucene engine for the `search` plugin — one index per site and
language, language analyzers (stemming), tiered ranking over BM25, fuzzy matching and highlighting. Requires SPI `1.0.0` (Runway `1.0.0` or later),
`search >= 2.2.0` and `lucene-provider == 10.4.0`. Licensed under the GNU AGPL v3.0 with an additional permission for
plugins (`LICENSE`, `LICENSE-EXCEPTION`).
