# Writing a Runway plugin — moved

The authoring guide is maintained in **one place**: [`../../docs/PLUGIN-AUTHORING-GUIDE.md`](../../docs/PLUGIN-AUTHORING-GUIDE.md)
at `plugins/docs/`.

This file used to hold a copy of it. The copy had drifted (it still described host-coupled seams — `RunwayPlugin`,
`FilterContribution`, `SitePluginConfigService` — that the SPI service tier replaced), and a stale copy beside the
reference plugin is worse than none. For how the demo itself uses every service, read
[`DEMO-PLUGIN.md`](DEMO-PLUGIN.md).
