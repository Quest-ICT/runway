package mx.quest.search;

import java.util.regex.Pattern;

/**
 * Reduces a page's raw HTML source to searchable plain text so the built-in engine can index a page's
 * <b>body</b>, not just its metadata. It removes {@code <script>}/{@code <style>} blocks and HTML comments
 * (whose contents are not visible prose), strips the remaining tags, decodes the handful of entities a page body
 * commonly carries, and collapses runs of whitespace to single spaces.
 *
 * <p>This is deliberately a best-effort extractor, not a conforming HTML parser: a malformed or exotic page must
 * still yield <em>some</em> reasonable text rather than throw, because indexing must never fail on a single bad
 * page. The output is plain text with no markup — the snippet builder is what later re-introduces the safe
 * {@code <mark>} highlight over an escaped slice of it.
 */
final class HtmlText {

    /** {@code <script>…</script>} / {@code <style>…</style>} — content is code/CSS, never indexable prose. */
    private static final Pattern SCRIPT_STYLE = Pattern.compile("(?is)<(script|style)\\b[^>]*>.*?</\\1>");
    private static final Pattern COMMENT = Pattern.compile("(?s)<!--.*?-->");
    private static final Pattern TAG = Pattern.compile("<[^>]+>");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    private HtmlText() {
    }

    /** The visible text of {@code html} (never {@code null}; empty for {@code null}/blank input). */
    static String toText(String html) {
        if (html == null || html.isBlank()) {
            return "";
        }
        String text = SCRIPT_STYLE.matcher(html).replaceAll(" ");
        text = COMMENT.matcher(text).replaceAll(" ");
        text = TAG.matcher(text).replaceAll(" ");
        // Decode the common named/numeric entities a body carries; &amp; is decoded LAST so a literal
        // "&amp;lt;" round-trips to "&lt;" rather than being over-decoded to "<".
        text = text.replace("&nbsp;", " ")
                .replace("&lt;", "<").replace("&gt;", ">")
                .replace("&quot;", "\"").replace("&#39;", "'").replace("&apos;", "'")
                .replace("&amp;", "&");
        return WHITESPACE.matcher(text).replaceAll(" ").strip();
    }
}
