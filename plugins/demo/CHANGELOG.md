# Changelog

All notable changes are recorded here. Versions follow [Semantic Versioning](https://semver.org/).

## [2.1.1] — 2026-10-29

### Security

- **The feedback form asks the site's CAPTCHA.** The public "leave a message" form (`POST /demo/feedback`) had a
  honeypot, a length cap and a per-address rate limit, but never asked the challenge a site manager can turn on in
  the site's protection settings, so a site with a CAPTCHA configured still took messages from scripts. The form now
  shows the site's challenge, and the route keeps no message without it solved (`?demo-feedback=captcha`). A site
  with no CAPTCHA configured sees no change.

### Upgrade

Replace the plugin folder with this release's zip and restart. No setting or stored file changes.

## [2.1.0] — 2026-10-21

First public release of `runway-demo`, the reference ("kitchen-sink") plugin: it exercises every extension axis and
every service of the Runway SPI, and is meant to be copied as the start of a new plugin. Requires SPI `1.0.0`
(Runway `1.0.0` or later). Licensed under the Apache License 2.0 (`LICENSE`, `NOTICE`).
