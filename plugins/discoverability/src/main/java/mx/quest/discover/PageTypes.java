package mx.quest.discover;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import mx.quest.runway.spi.model.SiteView;

/**
 * How one page is described in structured data: its own type, where that type comes from, and what else its
 * {@code content.schema} entry adds. The one answer both the public page ({@link StructuredDataHead}) and the page
 * editor's line ({@link PageTypeCheck}) use, so the two can never disagree.
 *
 * <h2>The rule</h2>
 * <ul>
 *   <li>The page's {@code content.schema} entry is found by its own path: the exact path, else the longest
 *       {@code *}-prefix. A page behind a language prefix ({@code /es/faq}) is matched only by a key written for that
 *       path ({@code /es/faq}, {@code /es/*}, {@code /*}); {@code /faq} does not reach it, because an entry's text
 *       (questions, names, a breadcrumb parent) is written in one language and would be published on the other
 *       language's page.</li>
 *   <li>The first block whose type describes the page itself ({@link SchemaType#describesPage}) sets the page's
 *       type; otherwise the page has the site's Default page type ({@code jsonld.type}).</li>
 *   <li>Every other recognized block adds an entity (a product, a service, a business, a person, a list).</li>
 *   <li>A block naming an unknown type is ignored, and so is a second block that describes the page (a page has one
 *       type); both are reported in the editor so neither goes unnoticed.</li>
 * </ul>
 */
final class PageTypes {

    /** Where the page's own type came from. */
    enum Source {
        /** The site's Default page type ({@code jsonld.type}). */
        DEFAULT,
        /** A {@code content.schema} block for this page. */
        SCHEMA
    }

    /**
     * One page's description.
     *
     * @param type        the page's own {@code @type}
     * @param source      where {@code type} came from
     * @param pattern     the matching {@code content.schema} pattern, or {@code null} when none matches
     * @param pageBlock   the block that set the type ({@code null} when it is the default)
     * @param added       the blocks that add an entity, in declared order
     * @param unknown     type tokens in the entry that no handler knows (a block with no {@code type} is not listed)
     * @param extraPageTypes page-describing tokens after the first, which are ignored
     */
    record Description(String type, Source source, String pattern, Map<String, Object> pageBlock,
                       List<Map<String, Object>> added, List<String> unknown, List<String> extraPageTypes) {

        /** The type tokens of the added blocks, in order. */
        List<String> addedTypes() {
            List<String> out = new ArrayList<>();
            for (Map<String, Object> block : added) {
                out.add(Schemas.str(block, "type", ""));
            }
            return out;
        }
    }

    private PageTypes() {
    }

    /**
     * The path of the page a {@link mx.quest.runway.spi.model.PageView} shows, relative to the site base, the way the
     * head derives it from the canonical URL.
     */
    static String pathOf(mx.quest.runway.spi.model.PageView page) {
        String base = page.site().baseUrl();
        base = (base != null && !base.isBlank()) ? base.trim() : "";
        if (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return StructuredDataHead.pathOf(base, page.canonical());
    }

    /** The site's Default page type as the head uses it: {@code Article} when so configured, else {@code WebPage}. */
    static String defaultType(Map<String, Object> cfg) {
        return "Article".equals(SiteConfig.str(cfg, "jsonld.type", "WebPage")) ? "Article" : "WebPage";
    }

    /**
     * Describes the page at {@code path}.
     *
     * @param cfg   the site's discoverability settings
     * @param path  the page's path relative to the site base, starting with {@code /}
     * @param types the type registry, by token
     */
    static Description describe(Map<String, Object> cfg, String path, Map<String, SchemaType> types) {
        Map<String, Object> patterns = PageSchemaConfig.parse(SiteConfig.str(cfg, "content.schema", ""));
        String pattern = PageSchemaConfig.matchingPattern(patterns, path);
        Map<String, Object> pageBlock = null;
        String pageType = null;
        List<Map<String, Object>> added = new ArrayList<>();
        List<String> unknown = new ArrayList<>();
        List<String> extraPageTypes = new ArrayList<>();
        if (pattern != null) {
            for (Map<String, Object> block : PageSchemaConfig.blocks(patterns.get(pattern))) {
                String token = Schemas.str(block, "type", "");
                if (token.isBlank()) {
                    continue; // a block with no type only carries shared fields (a breadcrumb parent)
                }
                SchemaType handler = types.get(token);
                if (handler == null) {
                    unknown.add(token);
                } else if (handler.describesPage(token)) {
                    if (pageBlock == null) {
                        pageBlock = block;
                        pageType = token;
                    } else {
                        extraPageTypes.add(token);
                    }
                } else {
                    added.add(block);
                }
            }
        }
        return pageBlock != null
                ? new Description(pageType, Source.SCHEMA, pattern, pageBlock, added, unknown, extraPageTypes)
                : new Description(defaultType(cfg), Source.DEFAULT, pattern, null, added, unknown, extraPageTypes);
    }

    /** The matching {@code content.schema} entry for {@code path}, or {@code null}. */
    static Object entry(Map<String, Object> cfg, String path) {
        Map<String, Object> patterns = PageSchemaConfig.parse(SiteConfig.str(cfg, "content.schema", ""));
        String pattern = PageSchemaConfig.matchingPattern(patterns, path);
        return pattern == null ? null : patterns.get(pattern);
    }

    /**
     * The default language's entry that does not reach a translated page.
     *
     * @param pattern    the pattern matching the same path in the default language ({@code /faq})
     * @param suggestion the key that would describe this page ({@code /es/faq}; {@code /es/blog/*} for {@code /blog/*})
     */
    record OtherLanguageEntry(String pattern, String suggestion) {
    }

    /**
     * For a page behind a non-default language prefix ({@code /es/faq}): the pattern that matches the same path in
     * the default language ({@code /faq}) but not this page; {@code null} when there is none, or when this page's own
     * match is at least as specific (the same {@code /*}, or {@code /es/*} beside {@code /*}). Only the page editor
     * uses it, to say that the other language's entry does not reach this page and which key would.
     */
    static OtherLanguageEntry otherLanguageEntry(Map<String, Object> cfg, String path, SiteView site) {
        String prefix = path == null || site == null ? null : languagePrefix(path, site);
        if (prefix == null) {
            return null;
        }
        String stripped = path.length() <= prefix.length() + 1 ? "/" : path.substring(prefix.length());
        Map<String, Object> patterns = PageSchemaConfig.parse(SiteConfig.str(cfg, "content.schema", ""));
        String base = PageSchemaConfig.matchingPattern(patterns, stripped);
        String own = PageSchemaConfig.matchingPattern(patterns, path);
        if (base == null || (own != null && specificity(own, path) >= specificity(base, stripped))) {
            return null; // nothing to say, or this page's own match is at least as specific (/es/*, /*)
        }
        return new OtherLanguageEntry(base, base.equals(stripped) ? path : prefix + base);
    }

    /** How specific a matching pattern is: an exact key above any prefix, a longer prefix above a shorter one. */
    private static int specificity(String pattern, String path) {
        return pattern.equals(path) ? Integer.MAX_VALUE : pattern.length();
    }

    /**
     * The leading {@code /<language>} of {@code path} when that language is one of the site's languages other than
     * the default ({@code /es} for {@code /es/faq} and for {@code /es}); {@code null} otherwise.
     */
    static String languagePrefix(String path, SiteView site) {
        for (Locale locale : site.locales()) {
            if (locale.equals(site.defaultLocale())) {
                continue; // the default language's pages are unprefixed
            }
            String prefix = "/" + locale.getLanguage();
            if (path.equals(prefix) || path.startsWith(prefix + "/")) {
                return prefix;
            }
        }
        return null;
    }
}
