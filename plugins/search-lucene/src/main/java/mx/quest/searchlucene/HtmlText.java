package mx.quest.searchlucene;

import java.util.regex.Pattern;

/**
 * Reduces a page's raw HTML source to indexable plain text: drops {@code <script>}/{@code <style>} blocks and
 * comments, strips the remaining tags, decodes a handful of common entities, and collapses whitespace. This is
 * a best-effort text extraction for full-text indexing — not a strict HTML parser — so a malformed page still
 * yields reasonable searchable text.
 */
final class HtmlText {

    private static final Pattern SCRIPT_STYLE =
            Pattern.compile("(?is)<(script|style)\\b[^>]*>.*?</\\1>");
    private static final Pattern COMMENT = Pattern.compile("(?s)<!--.*?-->");
    private static final Pattern TAG = Pattern.compile("<[^>]+>");
    private static final Pattern WS = Pattern.compile("\\s+");

    private HtmlText() {
    }

    static String toText(String html) {
        if (html == null || html.isBlank()) {
            return "";
        }
        String s = SCRIPT_STYLE.matcher(html).replaceAll(" ");
        s = COMMENT.matcher(s).replaceAll(" ");
        s = TAG.matcher(s).replaceAll(" ");
        s = s.replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
                .replace("&quot;", "\"").replace("&#39;", "'").replace("&apos;", "'");
        return WS.matcher(s).replaceAll(" ").strip();
    }
}
