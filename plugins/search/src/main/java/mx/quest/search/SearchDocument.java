package mx.quest.search;

import java.util.Locale;

/**
 * One indexed page for the built-in engine: the display fields (URL, title, description) plus a plain-text
 * <b>body</b> extracted from the page HTML, alongside pre-lowercased copies of title/description/body so scoring
 * is a cheap {@code contains}/count without re-casing on every query. Immutable and built once per index build.
 *
 * @param slug        the page slug (used for exclusion matching and diagnostics)
 * @param url         the public URL to link to
 * @param title       the display title
 * @param description the display description (a snippet source when the body has no hit)
 * @param body        the extracted plain-text body (the snippet source and the bulk of the searchable text)
 * @param normTitle   {@code title} lowercased for matching
 * @param normDescription {@code description} lowercased for matching
 * @param normBody    {@code body} lowercased for matching
 */
record SearchDocument(String slug, String url, String title, String description, String body,
                      String normTitle, String normDescription, String normBody) {

    /** Builds a document, deriving the normalized (lowercased) match fields from the display fields. */
    static SearchDocument of(String slug, String url, String title, String description, String body) {
        String t = title == null ? "" : title;
        String d = description == null ? "" : description;
        String b = body == null ? "" : body;
        return new SearchDocument(slug, url, t, d, b,
                t.toLowerCase(Locale.ROOT), d.toLowerCase(Locale.ROOT), b.toLowerCase(Locale.ROOT));
    }
}
