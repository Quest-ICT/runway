package mx.quest.discover;

import java.util.List;
import java.util.Map;
import mx.quest.runway.spi.model.HeadContext;

/**
 * A plugin-internal content type that maps a tenant-declared page/section type (e.g. {@code FAQPage},
 * {@code Product}) to schema.org JSON-LD {@code @graph} node(s). This is the extension seam behind item 5's
 * type-aware structured data: {@link StructuredDataHead} holds a registry of these keyed by {@link #key()} and,
 * for a page whose path matches a {@code content.schema} entry, appends {@link #nodes(HeadContext, SchemaContext)}
 * to the graph — with zero type-aware branching in the contributor itself (capability/registry dispatch, per the
 * host's capability-driven-SPI principle).
 *
 * <p>Implementations are pure and stateless: they read the immutable {@link HeadContext} and the matched
 * per-page config carried by {@link SchemaContext}, and return plain node maps that reference the shared
 * {@code #webpage}/{@code #organization} entities by {@code @id}. Content <em>values</em> are tenant-owned (they
 * come from the config); the plugin owns only the typing mechanism.
 */
interface SchemaType {

    /** The type token a tenant declares in {@code content.schema} (e.g. {@code "FAQPage"}); the registry key. */
    String key();

    /**
     * The schema.org node(s) for this typed page, or an empty list when the config has nothing to emit.
     *
     * @param ctx the immutable head context (site, page, locale, canonical)
     * @param sc  the resolved schema context (base, path, shared {@code @id}s, the matched per-page config)
     */
    List<Map<String, Object>> nodes(HeadContext ctx, SchemaContext sc);

    /**
     * Whether a block declaring {@code token} (one of the tokens this type is registered under) describes the page
     * itself — its own {@code #webpage} node's {@code @type} — rather than adding a separate entity. Such a block
     * replaces the site's Default page type for the pages it matches, and the head then calls
     * {@link #describePage} instead of {@link #nodes}. Until 2.2.0 every block added a node, so a page declared
     * {@code Article} carried two Article nodes when the site default was Article as well.
     */
    default boolean describesPage(String token) {
        return false;
    }

    /**
     * For a block that {@linkplain #describesPage describes the page}: adds this type's properties to the page's own
     * node (whose {@code @type} the caller has already set to the declared token) and appends any separate nodes the
     * type needs to {@code graph}.
     *
     * @param page  the page's own node, mutable
     * @param graph the graph being built, for nodes the page refers to by {@code @id}
     */
    default void describePage(Map<String, Object> page, HeadContext ctx, SchemaContext sc,
                              List<Map<String, Object>> graph) {
    }

    /**
     * What a block declaring {@code token} lacks to publish what it is for, or {@code null} when it lacks nothing:
     * an {@code FAQPage} with no complete question, a list with no item that has a {@code url}. Such a block breaks
     * nothing, so the page is still served; the settings form's Per-page types check and the page editor report it,
     * since until 2.2.0 nobody saw it. A key that has a default (a product's name falls back to the page title) is
     * not a gap.
     */
    default Gap gap(String token, Map<String, Object> block) {
        return null;
    }

    /**
     * A gap: the message code and its English text, whose {@code {0}} is the type token.
     *
     * @param code     the message key, resolved in the operator's UI language
     * @param fallback the English text
     */
    record Gap(String code, String fallback) {
    }
}
