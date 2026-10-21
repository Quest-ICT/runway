# Writing a Runway plugin — moved

The authoring guide is maintained in **one place**: [`../../docs/PLUGIN-AUTHORING-GUIDE.md`](../../docs/PLUGIN-AUTHORING-GUIDE.md)
at `plugins/docs/`.

This file used to hold a copy of it. The copy had drifted (it predated the SPI service tier and still told a plugin
to inject kernel classes such as `SitePluginConfigService`, `ContentCatalog` or `SiteStore`), and a stale copy is
worse than none. For how this plugin uses the SPI services instead, read
[`DISCOVERABILITY-PLUGIN.md`](DISCOVERABILITY-PLUGIN.md) §2.
