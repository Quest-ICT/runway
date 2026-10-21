package mx.quest.carousel;

/**
 * One carousel slide, parsed from a per-tenant {@code slides} list entry of the form
 * {@code imageUrl | caption | linkUrl} (caption and link optional). Immutable; all fields already trimmed and
 * HTML-escaped at render time by the widget.
 *
 * @param imageUrl the slide image URL (required — a slide with no image is dropped)
 * @param caption  the optional caption text ({@code ""} if none)
 * @param link     the optional link the slide navigates to when clicked ({@code ""} if none)
 */
record Slide(String imageUrl, String caption, String link) {

    /** Parses one {@code imageUrl | caption | linkUrl} entry; returns {@code null} if it has no usable image. */
    static Slide parse(String entry) {
        if (entry == null || entry.isBlank()) {
            return null;
        }
        String[] parts = entry.split("\\|", 3);
        String image = parts[0].strip();
        if (image.isEmpty()) {
            return null;
        }
        String caption = parts.length > 1 ? parts[1].strip() : "";
        String link = parts.length > 2 ? parts[2].strip() : "";
        return new Slide(image, caption, link);
    }
}
