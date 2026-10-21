/**
 * Multi-tenancy core: resolving an incoming request to a site (tenant), the per-site folder and
 * lifecycle, and the bridge from the stored {@link mx.quest.runway.site.Site} model to the
 * immutable {@link mx.quest.runway.spi.model.SiteView} the SPI boundary uses.
 *
 * <ul>
 *   <li>{@link mx.quest.runway.site.Site} / {@link mx.quest.runway.site.SiteStore} — the per-site
 *       record (one {@code site.yml} under {@code sites/<id>/}) and its typed store.</li>
 *   <li>{@link mx.quest.runway.site.SiteResolver} + {@link mx.quest.runway.site.SiteContextFilter}
 *       — host → site resolution, bound per request early in the chain (the TENANT band, before
 *       any security), so every downstream decision knows its tenant.</li>
 *   <li>{@link mx.quest.runway.site.SuspendedSiteFilter} — short-circuits a suspended site.</li>
 *   <li>{@link mx.quest.runway.site.SiteLifecycleService} — create / suspend / resume / delete and
 *       the default-site seed, emitting {@link mx.quest.runway.site.SiteChangedEvent} so caches and
 *       the resolver index stay fresh.</li>
 * </ul>
 *
 * <p>Strict isolation is the rule: a site's data lives only under its own folder, resolved
 * traversal-safely; a tenant can never reach another tenant or the application's configuration.
 * Tenant-superadmin provisioning (security realm), presentation seeding (templating), and
 * backup/restore are built in their own later steps.
 */
package mx.quest.runway.site;
