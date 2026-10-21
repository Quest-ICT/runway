package mx.quest.discover;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import mx.quest.runway.spi.service.ContentCatalogView;
import mx.quest.runway.spi.service.Messages;
import mx.quest.runway.spi.service.PluginSettings;

/**
 * Backs the tenant <b>dashboard card</b> and the <b>admin fragment</b> rendered below the plugin's per-tenant
 * settings form. The templates call it as {@code ${@discoverabilityAdminModel.<method>(tenantSiteId)}} (the bean
 * name carries the plugin id, as every plugin bean must) to show the plugin's live state for the current site
 * (which surfaces are on, how many items the sitemap draws from).
 *
 * <p>Read-only. A missing or malformed site id (the fragments are only rendered for a tenant, so neither happens in
 * practice) answers the defaults and a count of {@code 0} rather than failing the console page.
 */
public class DiscoverAdminModel {

    private final PluginSettings settings;
    private final ContentCatalogView catalog;
    private final Map<String, SchemaType> types;
    private final Messages messages;

    public DiscoverAdminModel(PluginSettings settings, ContentCatalogView catalog) {
        this(settings, catalog, DiscoverabilityConfig.schemaTypes(), null);
    }

    DiscoverAdminModel(PluginSettings settings, ContentCatalogView catalog, Map<String, SchemaType> types,
                       Messages messages) {
        this.settings = settings;
        this.catalog = catalog;
        this.types = types;
        this.messages = messages;
    }

    /**
     * What is wrong with the site's Per-page types ({@code content.schema}), one line each, in the operator's UI
     * language; empty when nothing is. Shown below the settings form after every save.
     *
     * <h2>Why</h2>
     * A mistake there never breaks a page: unreadable YAML turns typing off, and an unknown type or a misplaced
     * {@code *} is skipped. That is the right behaviour for visitors and the wrong one for the person who typed it,
     * who until 2.2.0 had no way to see it. A block that applies but publishes nothing of its own (an
     * {@code FAQPage} with no complete question: {@link SchemaType#gap}) is listed too; an ignored second page type
     * is reported once, not also for its gaps.
     */
    public List<String> schemaProblems(String siteId) {
        String text = SiteConfig.str(cfg(siteId), "content.schema", "");
        List<String> out = new ArrayList<>();
        String syntax = PageSchemaConfig.syntaxProblem(text);
        if (syntax != null) {
            out.add(text("discover.schema.syntax",
                    "Per-page types cannot be read, so no page gets a per-page type: {0}", syntax));
            return out;
        }
        for (String pattern : PageSchemaConfig.ignoredPatterns(text)) {
            out.add(text("discover.schema.notBlock",
                    "{0}: the value must be a type block (type: ...) or a list of them; ignored.", pattern));
        }
        Map<String, Object> patterns = PageSchemaConfig.parse(text);
        for (Map.Entry<String, Object> entry : patterns.entrySet()) {
            String pattern = entry.getKey();
            if (!pattern.startsWith("/")) {
                out.add(text("discover.schema.slash", "{0}: a pattern starts with / and matches page paths.",
                        pattern));
            }
            int star = pattern.indexOf('*');
            if (star >= 0 && star < pattern.length() - 1) {
                out.add(text("discover.schema.star",
                        "{0}: * works only at the end of a pattern; here it is matched as a character.", pattern));
            }
            int pageTypes = 0;
            for (Map<String, Object> block : PageSchemaConfig.blocks(entry.getValue())) {
                String token = Schemas.str(block, "type", "");
                if (token.isBlank()) {
                    continue;
                }
                SchemaType handler = types.get(token);
                if (handler == null) {
                    out.add(text("discover.schema.unknown",
                            "{0}: {1} is not a type this plugin knows; ignored.", pattern, token));
                } else if (handler.describesPage(token) && ++pageTypes > 1) {
                    if (pageTypes == 2) {
                        out.add(text("discover.schema.twoPageTypes",
                                "{0}: more than one page type; only the first applies.", pattern));
                    }
                } else {
                    SchemaType.Gap gap = handler.gap(token, block);
                    if (gap != null) {
                        out.add(pattern + ": " + text(gap.code(), gap.fallback(), token));
                    }
                }
            }
        }
        return out;
    }

    private String text(String code, String fallback, Object... args) {
        return messages == null ? java.text.MessageFormat.format(fallback, args)
                : messages.console(code, fallback, args);
    }

    public boolean jsonldEnabled(String siteId) {
        return SiteConfig.bool(cfg(siteId), "jsonld.enabled", true);
    }

    public String jsonldType(String siteId) {
        return PageTypes.defaultType(cfg(siteId));
    }

    public boolean sitemapEnabled(String siteId) {
        return SiteConfig.bool(cfg(siteId), "sitemap.enabled", true);
    }

    public boolean geoEnabled(String siteId) {
        return SiteConfig.bool(cfg(siteId), "geo.enabled", false);
    }

    public boolean orgConfigured(String siteId) {
        return !SiteConfig.str(cfg(siteId), "org.name", "").isBlank();
    }

    /**
     * The number of published content items the sitemap draws from — kernel pages AND plugin content alike, through
     * the same host listing the sitemap itself serves from (so this count is always truthful). Items marked
     * {@code noindex} are included, as before: the count is of the published surface.
     */
    public long pageCount(String siteId) {
        if (siteId == null) {
            return 0L;
        }
        try {
            return catalog.entries(siteId).size();
        } catch (IllegalArgumentException malformedId) {
            return 0L;
        }
    }

    private Map<String, Object> cfg(String siteId) {
        if (siteId == null) {
            return Map.of();
        }
        try {
            return settings.site(siteId).values();
        } catch (IllegalArgumentException malformedId) {
            return Map.of();
        }
    }
}
