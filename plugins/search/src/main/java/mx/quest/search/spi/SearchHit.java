package mx.quest.search.spi;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * One ranked result from a {@link SiteSearchEngine}: the absolute (or site-relative) URL to link to, the title
 * to show, a short {@link #snippet() snippet} of context, and a score used only for ordering (higher = better).
 *
 * <h2>The {@code snippet} safety contract (frozen semantics — read before rendering)</h2>
 * The snippet is <b>pre-escaped, highlight-bearing HTML</b>, NOT plain text: every character that comes from the
 * page's own content ({@code < > & " '}) is already HTML-escaped by the engine, and the <b>only</b> raw markup
 * an engine may leave in it is the pair {@code <mark>…</mark>} wrapping the matched query terms. Both shipped
 * engines honour this: the built-in engine and {@code search-lucene} each escape the source passage first and
 * only then splice in {@code <mark>} around the hits. The consequence is that the search surface (the results
 * page and the search-box widget) MUST render the snippet as <b>raw HTML</b> (Thymeleaf {@code th:utext} or a
 * direct string append), so the highlight shows as emphasis rather than literal {@code &lt;mark&gt;} text. This
 * is XSS-safe precisely because the escaping happened at the engine and nothing but {@code <mark>} survives it —
 * a crafted page title or body cannot forge a tag. An engine that produces no highlight simply returns the
 * escaped passage with no {@code <mark>}; an empty snippet is allowed. A NEW engine implementing this SPI is
 * bound by the same contract: escape everything, wrap only hits in {@code <mark>}.
 *
 *
 * <h2>The {@code terms} component (since 2.1.0)</h2>
 * The words the engine actually matched in this item, as they are written there: for a stemming engine the
 * surface forms (the query {@code maestría} finding {@code maestrías}), for the built-in engine the whole words
 * that contain a query term. The search surface hands them to the target page, which highlights them, so they
 * are plain text, never markup: the surface escapes them like any other value. Lowercase, distinct, at most
 * {@value #MAX_TERMS} of at most {@value #MAX_TERM_LENGTH} characters each; blank entries are dropped. An engine
 * built before 2.1.0 calls the four-argument constructor and its hits carry no terms, which only means the target
 * page highlights nothing.
 *
 * @param url     the link target for the hit (the search surface renders it as an {@code <a href>})
 * @param title   the hit's display title (the surface escapes it before rendering)
 * @param snippet a short, HTML-escaped, {@code <mark>}-highlighted excerpt (may be empty); the surface renders
 *                it as raw HTML per the safety contract above — do NOT re-escape it
 * @param score   the relevance score, used to sort hits best-first; not shown to the user
 * @param terms   the matched words as written in the item (never {@code null}; may be empty), see above
 */
public record SearchHit(String url, String title, String snippet, double score, List<String> terms) {

    /** The most matched words a hit carries; more would only lengthen the link. */
    public static final int MAX_TERMS = 20;

    /** The longest matched word kept; a longer "word" is a token such as a hash, not something to highlight. */
    public static final int MAX_TERM_LENGTH = 64;

    public SearchHit {
        Objects.requireNonNull(url, "url");
        title = (title == null) ? "" : title;
        snippet = (snippet == null) ? "" : snippet;
        terms = normalize(terms);
    }

    /** The 2.0.0 form, before {@link #terms()}: a hit with no matched words. (Kept so older engines still link.) */
    public SearchHit(String url, String title, String snippet, double score) {
        this(url, title, snippet, score, List.of());
    }

    private static List<String> normalize(List<String> raw) {
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<String> kept = new LinkedHashSet<>();
        for (String term : raw) {
            if (term == null) {
                continue;
            }
            String t = term.strip().toLowerCase(Locale.ROOT);
            if (!t.isEmpty() && t.length() <= MAX_TERM_LENGTH) {
                kept.add(t);
                if (kept.size() == MAX_TERMS) {
                    break;
                }
            }
        }
        return List.copyOf(kept);
    }
}
