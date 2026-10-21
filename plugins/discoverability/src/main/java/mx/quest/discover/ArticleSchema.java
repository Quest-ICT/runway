package mx.quest.discover;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import mx.quest.runway.spi.model.HeadContext;

/**
 * Emits a schema.org {@code Article} (or {@code BlogPosting} — the subtype the entry declares as its
 * {@code type}) from a post's {@code content.schema} entry: {@code headline} (falls back to the page title),
 * description, image, {@code author} (a {@code Person}), {@code datePublished}/{@code dateModified},
 * {@code publisher}→Organization. The declared type <b>is</b> the page's own type (since 2.2.0): the properties go on
 * the page's {@code #webpage} node, which takes the declared {@code @type}, instead of a second node beside it.
 *
 * <h2>Where author and dates come from, and why the order matters</h2>
 * Each of {@code author}, {@code datePublished} and {@code dateModified} is taken from <b>the page's own
 * provenance first</b> ({@link mx.quest.runway.spi.model.PageMeta#provenance()}), and only then from the
 * hand-written {@code content.schema} block.
 *
 * <p>That order is the whole point of the change. {@code content.schema} matches by exact path or {@code *}
 * prefix, so a blog is typed by a single {@code /blog/*} block shared by <b>every</b> entry. When these fields
 * could only come from there, a site had exactly two honest options: omit dates and authorship entirely, or
 * assert <i>one</i> date and <i>one</i> author identically on every post — a statement that is false of nearly
 * every page it appears on. Asserting content that is not true of the page is precisely what structured-data
 * policy prohibits, so the configuration-only design pushed a conscientious operator toward a violation.
 *
 * <p>Per-item provenance removes the dilemma: a source that knows its own publication date (a blog entry does)
 * now supplies it, and the shared block is left to cover only what is genuinely shared. The config path is kept
 * as a fallback so existing sites keep working unchanged, and so a source that has no provenance can still be
 * annotated by hand.
 */
final class ArticleSchema implements SchemaType {

    @Override
    public String key() {
        return "Article";
    }

    /** Every token is a page type: an article (or blog post, or news article) is what the page is. */
    @Override
    public boolean describesPage(String token) {
        return true;
    }

    /** Nothing as a separate node: the article is the page itself ({@link #describePage}). */
    @Override
    public List<Map<String, Object>> nodes(HeadContext ctx, SchemaContext sc) {
        return List.of();
    }

    /**
     * Adds the article's properties to the page's own node: {@code headline} in place of {@code name}, and
     * author, dates and image. Without a headline (no page title and none configured) the page keeps its name.
     */
    @Override
    public void describePage(Map<String, Object> page, HeadContext ctx, SchemaContext sc,
                             List<Map<String, Object>> graph) {
        Map<String, Object> fields = fields(ctx, sc);
        if (fields.isEmpty()) {
            return;
        }
        page.remove("name");
        page.putAll(fields);
    }

    /** The article's own properties, or an empty map when it has no headline. */
    private static Map<String, Object> fields(HeadContext ctx, SchemaContext sc) {
        Map<String, Object> cfg = sc.pageConfig();
        String headline = Schemas.str(cfg, "headline", ctx.page().title());
        if (headline == null || headline.isBlank()) {
            return Map.of(); // no headline — leave the page node as it is
        }
        Map<String, Object> article = new LinkedHashMap<>();
        Ld.put(article, "headline", headline);
        Ld.put(article, "description", Schemas.str(cfg, "description", ctx.page().description()));
        Ld.putList(article, "image", Schemas.absolute(Schemas.strings(cfg, "image"), sc.base()));
        // Per-item provenance WINS over the shared content.schema block — see the class contract.
        mx.quest.runway.spi.model.Provenance prov = ctx.page().provenance();
        Ld.put(article, "datePublished", isoDate(prov.publishedAt(), Schemas.str(cfg, "datePublished")));
        Ld.put(article, "dateModified", isoDate(prov.updatedAt(), Schemas.str(cfg, "dateModified")));
        Ld.put(article, "inLanguage", ctx.locale().toLanguageTag());
        String author = prov.author() != null ? prov.author() : Schemas.str(cfg, "author");
        if (author != null) {
            Map<String, Object> person = new LinkedHashMap<>();
            person.put("@type", "Person");
            person.put("name", author);
            article.put("author", person);
        }
        if (sc.hasOrg()) {
            Ld.ref(article, "publisher", sc.organizationId());
        }
        return article;
    }

    /**
     * The item's own instant as a schema.org date, else the configured fallback.
     *
     * <p>Rendered as {@code yyyy-MM-dd} (UTC) rather than a full timestamp: schema.org accepts either, and a
     * publication date is an editorial fact at day granularity. Emitting a second-precision instant would imply
     * a precision the platform does not actually have — {@code dateModified} in particular is derived from a
     * file mtime, which moves for reasons that are not editorial.
     */
    private static String isoDate(java.time.Instant instant, String fallback) {
        if (instant == null) {
            return fallback;
        }
        return java.time.format.DateTimeFormatter.ISO_LOCAL_DATE
                .format(instant.atZone(java.time.ZoneOffset.UTC));
    }
}
