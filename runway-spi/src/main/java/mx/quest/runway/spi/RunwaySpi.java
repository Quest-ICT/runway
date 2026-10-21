package mx.quest.runway.spi;

/**
 * Marker and version anchor for the Runway extension API.
 *
 * <p>{@link #VERSION} is the SPI contract version an extension compiles against. The SPI is
 * <b>binary API</b>: it evolves only by <i>adding</i> {@code default} methods to interfaces and
 * keeping old-arity constructors on model records, so an extension built against an earlier
 * version keeps loading on a later one. Bump {@link #VERSION} on each additive evolution; a
 * value that no longer parses as {@code MAJOR.MINOR.PATCH} is a mistake (see the compatibility
 * rules in {@code docs/SPI-STABILITY.md}).
 *
 * <p>A plugin declares the level it needs with {@code requiresSpi: ">=X.Y.Z"} in its
 * {@code plugin.yml}, and the loader compares that floor against exactly this value — not against the
 * kernel's release version, which moves for reasons that have nothing to do with the extension
 * contract. A plugin whose floor is not met is blocked at startup with an operator-readable
 * reason, and the kernel still boots its other plugins. The protection runs one way only: a
 * kernel older than the one that introduced the key <b>ignores</b> it, because descriptors
 * parse with unknown properties ignored on purpose (forward/backward compatibility). So the
 * floor guards upgrades from {@code 0.5.0-beta10} onward, not the upgrade that introduces it.
 * That is also why the evolution log on {@link #VERSION} has to stay accurate: it is what an
 * author reads to pick a floor.
 */
public final class RunwaySpi {

    private RunwaySpi() {}

    /**
     * The SPI contract version <b>this code was compiled against</b>. It is a compile-time constant, so the Java
     * compiler copies its value into every class that reads it: in a plugin, {@code RunwaySpi.VERSION} is the
     * version the plugin was <i>built</i> against, not the one it is running on. Use {@link #runtimeVersion()} for
     * the running kernel's SPI version. The loader compares a plugin's {@code requiresSpi} floor against the
     * running version.
     *
     * <p>Evolution log (one line per SPI version from 0.4.0 on — it is what an author reads to pick a floor):
     * <ul>
     *   <li><b>0.4.0</b> — the service tier begins: {@link mx.quest.runway.spi.service.PluginSettings}
     *       with {@link mx.quest.runway.spi.model.SettingsView}, {@link mx.quest.runway.spi.model.PluginInfo},
     *       {@link mx.quest.runway.spi.service.PluginLifecycle}, and {@link #runtimeVersion()}. <b>Removed</b>
     *       {@code model.Permission} (it moved host-side; no plugin used it). Javadoc contracts: {@code Route} (exact
     *       paths, tenant realm only), {@code WidgetView} (frozen config), and a "Limits &amp; failure" paragraph
     *       on every axis.</li>
     *   <li><b>0.5.0</b> — site-facing services: {@link mx.quest.runway.spi.service.PluginSiteFiles} with
     *       {@link mx.quest.runway.spi.model.FileEntry} (the plugin's own per-site subtree),
     *       {@link mx.quest.runway.spi.service.Sites}, {@link mx.quest.runway.spi.service.RequestContext},
     *       {@link mx.quest.runway.spi.service.AccessCheck}, {@link mx.quest.runway.spi.service.Messages},
     *       {@link mx.quest.runway.spi.service.SitePages} with {@link mx.quest.runway.spi.model.StarterPage} and
     *       {@link mx.quest.runway.spi.model.InstallResult}. {@link mx.quest.runway.spi.model.RouteRequest} gains
     *       {@code form}, {@code clientIp}, {@code origin} and {@code param(name)}; its {@code query} is the URL
     *       query only, its {@code locale} is negotiated against the site's locales, and header names are
     *       lowercase. {@link mx.quest.runway.spi.model.Route} gains {@code anonymous} (CSRF-exempt public form
     *       routes). The {@code siteData.personal} descriptor key (left out of transfer copies).</li>
     *   <li><b>0.6.0</b> — the content and event tier:
     *       {@link mx.quest.runway.spi.service.ContentCatalogView} with {@link mx.quest.runway.spi.model.CatalogEntry}
     *       and {@link mx.quest.runway.spi.model.LocalizedUrl} (the published content surface with host-computed
     *       URLs), {@link mx.quest.runway.spi.service.ContentChanges} (announce a source's change; replaces publishing
     *       the host's {@code ContentChangedEvent}), {@link mx.quest.runway.spi.service.SiteEventListener} with
     *       {@link mx.quest.runway.spi.model.SiteLifecycleEvent}, {@link mx.quest.runway.spi.model.SettingsChange}
     *       and {@link mx.quest.runway.spi.model.CatalogChange} (phased, isolated, after the kernel's caches and
     *       outside every host lock; change events carry the item's URLs before and after, and a {@code local}
     *       flag), and {@link mx.quest.runway.spi.service.ConsoleScreen} (a plugin screen inside the console
     *       shell without host classes). {@link ContentSource}'s body-template render model is now documented
     *       contract.</li>
     *   <li><b>1.0.0</b> (1.0.0-rc1) — <b>the contract is frozen</b>: from here the SPI evolves only additively
     *       within the 1.x line (see {@code docs/SPI-STABILITY.md}). One addition:
     *       {@link mx.quest.runway.spi.model.HeadContext#origin()} (the trusted origin, so a site with no configured
     *       base URL still gets absolute URLs in its JSON-LD). One behaviour fix:
     *       {@link mx.quest.runway.spi.model.CatalogEntry#xDefaultPath()} and
     *       {@link mx.quest.runway.spi.model.CatalogEntry#pathFor} never name an address the item is not served at.
     *       The loader now <b>requires</b> a {@code requiresSpi} floor of at least {@code 0.4.0} on every plugin
     *       with a {@code mainClass} (an older or missing floor marks a plugin built for the pre-1.0 contract, which
     *       is blocked), and blocks host linkage and Spring-surface violations instead of warning. A {@code 0.4.0}
     *       – {@code 0.6.0} floor is a 1.x floor: the service tier those versions introduced is the 1.0
     *       contract.</li>
     * </ul>
     *
     * <p>Before 0.4.0: 0.3.4 added {@link HeadContributor#headInputs(String)} (a {@code default} method returning an empty list:
     * the site-relative paths a contributor reads, so a page's {@code ETag} can cover head markup that is
     * spliced in at the response edge, after the validator has been computed). It also carries two additions
     * that landed <i>after</i> the 0.3.3 bump and so were never published under it —
     * {@link mx.quest.runway.spi.model.PageMeta#nosnippet()} (ask engines not to quote a page while it stays
     * indexed) and {@link mx.quest.runway.spi.model.Alternate#hreflang()} (the one BCP 47 formatter the head
     * and the sitemap now share, so the two surfaces cannot drift apart on a regional locale). A plugin using
     * either must declare {@code >=0.3.4}: kernels {@code 0.5.0-beta7} through {@code -beta9} report 0.3.3 and
     * do not have them. 0.3.3 added {@link mx.quest.runway.spi.model.PageMeta#provenance()} and the
     * {@link mx.quest.runway.spi.model.Provenance} record it returns, plus {@code author} and
     * {@code publishedAt} components (and a {@code provenance()} accessor) on
     * {@link mx.quest.runway.spi.model.ContentRef} — editorial authorship and publication date; before this
     * the boundary carried neither, so no {@link HeadContributor} could emit a byline or a date at all — all
     * with old-arity constructors kept. 0.3.2 added {@link ContentSource#indexableText} (a {@code default}
     * method gated by the {@code content.indexable-text} capability — the search integration's read side).
     * 0.3.1 added a {@code sourceKey} component to
     * {@link mx.quest.runway.spi.model.PageMeta} (identifies the serving content source; {@code null} = kernel
     * page) and a {@code path} component to {@link mx.quest.runway.spi.model.WidgetView} (the public request
     * path — the correct way for a widget to reference "the current page's URL"), both with old-arity
     * constructors kept. 0.3.0 added the content-source axis — {@link ContentSource} plus the
     * {@link mx.quest.runway.spi.model.ContentRef} and {@link mx.quest.runway.spi.model.ContentBody} boundary
     * records. 0.2.1 added {@link CaptchaService} and
     * {@link SiteLoginProtectionControl} (new interfaces) and a {@code query} component to
     * {@link mx.quest.runway.spi.model.WidgetView} (with the old-arity constructor kept). All additive and
     * binary-compatible (no existing member changed).
     */
    public static final String VERSION = "1.0.0";

    /**
     * The SPI version of the <b>running</b> kernel — unlike {@link #VERSION}, which the compiler inlines into the
     * caller and therefore reports the version a plugin was built against. A plugin that wants to adapt to (or
     * report) the kernel it runs on calls this.
     *
     * @return the running {@code runway-spi}'s version, plain {@code MAJOR.MINOR.PATCH}
     * @since SPI 0.4.0
     */
    public static String runtimeVersion() {
        return VERSION;
    }
}
