package mx.quest.carousel;

/**
 * Minimal HTML escaping for the values the carousel widget interpolates (image URLs, captions, links from the
 * per-tenant config). The plugin owns escaping of its own output so a hand-entered caption or URL can never
 * inject markup into a rendered page.
 */
final class Html {

    private Html() {
    }

    static String escape(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;");
    }
}
