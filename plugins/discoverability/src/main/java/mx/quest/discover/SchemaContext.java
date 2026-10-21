package mx.quest.discover;

import java.util.Locale;
import java.util.Map;

/**
 * The immutable inputs a {@link SchemaType} needs beyond the {@link mx.quest.runway.spi.model.HeadContext}: the
 * resolved site {@code base} and this page's {@code path}/{@code canonical}, whether it is a homepage, whether
 * the site declares an Organization, the shared {@code @id}s (so a typed node cross-references the
 * {@code WebPage}/{@code Organization} by reference rather than redefining them), the current {@code locale},
 * and the matched per-page config subtree parsed from {@code content.schema}.
 *
 * @param base           the site base URL (no trailing slash)
 * @param canonical      this page's absolute canonical URL
 * @param path           this page's path relative to base (starts with {@code /})
 * @param home           whether this page is a homepage
 * @param hasOrg         whether the site has a configured Organization (so {@code publisher}/{@code brand} refs apply)
 * @param websiteId      the {@code @id} of the graph's {@code WebSite} node
 * @param webpageId      the {@code @id} of the graph's {@code WebPage} node (for {@code mainEntityOfPage}/{@code isPartOf})
 * @param organizationId the {@code @id} of the graph's {@code Organization} node ({@code null}/unused when absent)
 * @param pageConfig     the matched {@code content.schema} entry (its {@code type} + type-specific fields)
 * @param locale         the resolved request locale
 */
record SchemaContext(String base, String canonical, String path, boolean home, boolean hasOrg,
                     String websiteId, String webpageId, String organizationId,
                     Map<String, Object> pageConfig, Locale locale) {
}
