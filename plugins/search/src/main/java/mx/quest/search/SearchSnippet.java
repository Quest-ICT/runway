package mx.quest.search;

import java.util.List;
import java.util.Locale;

/**
 * Builds a hit's snippet honouring the {@link mx.quest.search.spi.SearchHit} safety contract: a short excerpt of
 * the source text, <b>HTML-escaped in full</b>, with the matched query terms — and only those — wrapped in raw
 * {@code <mark>…</mark>}. The surface can then render the result as raw HTML: the highlight shows as emphasis
 * while every character that came from the page is inert, so a crafted title or body can never inject markup.
 *
 * <h2>Why escape-then-mark, in that order</h2>
 * The naive "wrap hits in {@code <mark>} then escape" would escape the {@code <mark>} tags too (defeating the
 * highlight), and "escape then search for the term" would miss hits whose characters were rewritten by escaping.
 * So the builder locates hits on the <em>raw</em> text, escapes each non-hit gap and each hit's text
 * independently, and emits a literal {@code <mark>}/{@code </mark>} only around the already-escaped hit text.
 * The result contains no unescaped source character — the sole raw markup is the {@code <mark>} pair.
 *
 * <p>Matching is case-insensitive and works on whole query terms (the same terms the engine scored on). The
 * excerpt is <b>windowed</b> around the first hit — a slice of at most {@code length} characters starting a
 * little before the earliest match — so the snippet shows the term in context rather than always the document's
 * opening. When no term is present (e.g. the hit matched only in the title) it falls back to a leading slice with
 * no highlight, still fully escaped.
 */
final class SearchSnippet {

    /** How far before the first hit the window may start, so the matched term isn't flush against the left edge. */
    private static final int LEAD_IN = 30;

    private SearchSnippet() {
    }

    /**
     * A safe, highlighted snippet of {@code text} for the lowercase {@code terms}, at most {@code length}
     * characters of source (before escaping) wide.
     *
     * @param text   the raw source text (typically the page body or description); may be blank
     * @param terms  the lowercase query terms to highlight (already tokenized by the engine)
     * @param length the maximum number of source characters to include (clamped to a sane minimum)
     * @return escaped HTML with {@code <mark>} around hits; empty string for blank input
     */
    static String build(String text, List<String> terms, int length) {
        if (text == null || text.isBlank()) {
            return "";
        }
        String source = text.strip();
        int window = Math.max(20, length);
        String lower = source.toLowerCase(Locale.ROOT);

        int firstHit = earliestHit(lower, terms);
        int start = firstHit < 0 ? 0 : Math.max(0, firstHit - LEAD_IN);
        // Nudge the window start off a mid-word position so it doesn't begin with a fragment of a word.
        while (start > 0 && Character.isLetterOrDigit(source.charAt(start - 1)) && start > firstHit - LEAD_IN - 12) {
            start--;
            if (!Character.isLetterOrDigit(source.charAt(start))) {
                break;
            }
        }
        int end = Math.min(source.length(), start + window);

        String slice = source.substring(start, end);
        StringBuilder out = new StringBuilder(slice.length() + 32);
        if (start > 0) {
            out.append("…");
        }
        out.append(highlight(slice, terms));
        if (end < source.length()) {
            out.append("…");
        }
        return out.toString();
    }

    /** The earliest index in {@code lower} at which any of {@code terms} occurs, or {@code -1} if none do. */
    private static int earliestHit(String lower, List<String> terms) {
        int best = -1;
        for (String term : terms) {
            if (term.isEmpty()) {
                continue;
            }
            int i = lower.indexOf(term);
            if (i >= 0 && (best < 0 || i < best)) {
                best = i;
            }
        }
        return best;
    }

    /**
     * Escapes {@code slice} and wraps each occurrence of any term in {@code <mark>}. Walks the slice left to
     * right, at each position taking the longest term that matches there; the gap before a match and the match
     * text are escaped separately, and only the literal {@code <mark>}/{@code </mark>} tags are emitted raw.
     */
    private static String highlight(String slice, List<String> terms) {
        String lower = slice.toLowerCase(Locale.ROOT);
        StringBuilder out = new StringBuilder(slice.length() + 16);
        int i = 0;
        while (i < slice.length()) {
            int matchLen = longestTermAt(lower, i, terms);
            if (matchLen == 0) {
                out.append(escape(slice.charAt(i)));
                i++;
            } else {
                out.append("<mark>").append(escape(slice.substring(i, i + matchLen))).append("</mark>");
                i += matchLen;
            }
        }
        return out.toString();
    }

    /** The length of the longest term that occurs in {@code lower} starting exactly at {@code pos}, else 0. */
    private static int longestTermAt(String lower, int pos, List<String> terms) {
        int best = 0;
        for (String term : terms) {
            int len = term.length();
            if (len > best && lower.startsWith(term, pos)) {
                best = len;
            }
        }
        return best;
    }

    private static String escape(char c) {
        return switch (c) {
            case '&' -> "&amp;";
            case '<' -> "&lt;";
            case '>' -> "&gt;";
            case '"' -> "&quot;";
            case '\'' -> "&#39;";
            default -> String.valueOf(c);
        };
    }

    private static String escape(String s) {
        StringBuilder b = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            b.append(escape(s.charAt(i)));
        }
        return b.toString();
    }
}
