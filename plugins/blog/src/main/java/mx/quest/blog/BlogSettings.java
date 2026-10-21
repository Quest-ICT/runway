package mx.quest.blog;

import mx.quest.runway.spi.model.SettingsView;
import mx.quest.runway.spi.service.ContentCatalogView;
import mx.quest.runway.spi.service.PluginSettings;

/**
 * The blog's effective per-site settings — typed reads over {@link PluginSettings} (live: a console save is visible
 * on the next read; an absent settings file means the descriptor's defaults). Kept as one value object so the
 * source, the console and the templates agree on defaults in exactly one place.
 *
 * @param enabled  whether the blog serves at all on this site
 * @param prefix   the URL prefix entries serve under (normalized: leading {@code /}, no trailing {@code /})
 * @param template the entry body template name within this plugin's own namespace (the operator's pick)
 */
public record BlogSettings(boolean enabled, String prefix, String template) {

    public static final String PLUGIN_ID = "blog";

    static final String DEFAULT_PREFIX = "/blog";
    static final String DEFAULT_TEMPLATE = "entry-classic";
    static final String MINIMAL_TEMPLATE = "entry-minimal";

    /**
     * The site's effective settings (descriptor defaults: enabled, {@code /blog}, {@code entry-classic}).
     *
     * <p>The prefix is checked with {@link ContentCatalogView#servablePath}: an unsafe or reserved prefix
     * ({@code /__x}, {@code /app}, {@code /actuator}, a path with {@code |} or {@code ..}) would make every entry
     * path fail the kernel's fail-closed re-filter and silently vanish from routing and listings, so it falls back
     * to {@code /blog} instead. The question asked is "would the kernel serve an entry under this prefix?", so the
     * probe is a child path of the prefix — that is where entries live.
     *
     * @param settings this plugin's per-site settings
     * @param catalog  the kernel's content surface (for its path rule; no content source is consulted)
     * @param siteId   the site
     * @return the effective settings (never {@code null})
     */
    public static BlogSettings of(PluginSettings settings, ContentCatalogView catalog, String siteId) {
        SettingsView view = settings.site(siteId);
        boolean enabled = view.bool("enabled", true);
        String prefix = normalizePrefix(view.string("prefix", DEFAULT_PREFIX));
        // Fail closed on the template name: it becomes part of a __plugin__/blog/<name> template lookup.
        String template = MINIMAL_TEMPLATE.equals(view.string("template", DEFAULT_TEMPLATE))
                ? MINIMAL_TEMPLATE : DEFAULT_TEMPLATE;
        if (!catalog.servablePath(siteId, join(prefix, "x"))) {
            prefix = DEFAULT_PREFIX;
        }
        return new BlogSettings(enabled, prefix, template);
    }

    /** The full logical body-template name the kernel resolves and namespace-validates. */
    public String bodyTemplate() {
        return "__plugin__/" + PLUGIN_ID + "/" + template;
    }

    /** An entry's public path under this prefix — joined without doubling when the prefix is the root. */
    public String pathFor(String slug) {
        return join(prefix, slug);
    }

    private static String join(String prefix, String slug) {
        return "/".equals(prefix) ? "/" + slug : prefix + "/" + slug;
    }

    private static String normalizePrefix(String raw) {
        String p = raw.trim();
        if (!p.startsWith("/")) {
            p = "/" + p;
        }
        while (p.endsWith("/") && p.length() > 1) {
            p = p.substring(0, p.length() - 1);
        }
        return p;
    }
}
