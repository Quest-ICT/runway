package mx.quest.runway.storage;

/** How an uploaded file's name becomes a stored one, for every console screen that takes uploads. */
public final class UploadNames {

    private UploadNames() {
    }

    /**
     * An upload filename reduced to a safe, URL-clean leaf: directory parts dropped, then every character outside
     * {@code [A-Za-z0-9._-]} replaced with {@code _}. The stored name is then filesystem-safe and URL-safe (no
     * spaces, {@code #}, {@code ?}, {@code %} or non-ASCII to break a link or trip the request firewall).
     *
     * @throws IllegalArgumentException if nothing usable remains (blank, or all dots/underscores)
     */
    public static String safeLeaf(String filename) {
        String leaf = filename == null ? "" : filename.strip();
        int slash = Math.max(leaf.lastIndexOf('/'), leaf.lastIndexOf('\\'));
        if (slash >= 0) {
            leaf = leaf.substring(slash + 1);
        }
        StringBuilder safe = new StringBuilder(leaf.length());
        for (int i = 0; i < leaf.length(); i++) {
            char c = leaf.charAt(i);
            boolean keep = c < 128 && (Character.isLetterOrDigit(c) || c == '.' || c == '-' || c == '_');
            safe.append(keep ? c : '_');
        }
        String name = safe.toString();
        if (name.chars().allMatch(c -> c == '.' || c == '_')) {
            throw new IllegalArgumentException("no usable filename after sanitizing: " + filename);
        }
        return name;
    }
}
