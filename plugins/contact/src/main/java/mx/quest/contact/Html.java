package mx.quest.contact;

/**
 * Minimal HTML escaping for the values the contact plugin interpolates into its form and its stored/echoed
 * submissions. The plugin owns escaping of its own output so a crafted name/email/message can never inject
 * markup into the form page, the thank-you page, or the admin listing.
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
