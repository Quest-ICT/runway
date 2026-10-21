package mx.quest.search;

/**
 * Minimal HTML escaping for the plugin's own generated markup (the search box widget and the results page).
 * The plugin owns escaping of every value it interpolates — page titles, snippets, the user's query — so a
 * crafted query or page title can never inject markup into the results page.
 */
final class Html {

    private Html() {
    }

    /** Escapes text for an element body or a double-quoted attribute value. */
    static String escape(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;");
    }
}
