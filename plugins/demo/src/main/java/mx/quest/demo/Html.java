package mx.quest.demo;

/**
 * Minimal HTML-text escaping. Every SPI that returns raw HTML (head markup, widget fragments) OWNS its
 * escaping — the host splices the output unescaped. Sufficient for element-text and double-quoted-attribute
 * contexts, which is all this demo emits.
 */
final class Html {

    private Html() {
    }

    static String escape(String s) {
        if (s == null || s.isEmpty()) {
            return "";
        }
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }
}
