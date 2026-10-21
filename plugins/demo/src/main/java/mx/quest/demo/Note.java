package mx.quest.demo;

/**
 * One per-site "sticky note". Immutable; serialized to {@code notes.yml} by {@link DemoNotesStore}.
 *
 * @param id        stable note id (a timestamp-derived token)
 * @param text      the note body (plain text; escaped wherever it is rendered)
 * @param createdAt ISO-8601 creation instant
 * @param origin    who wrote it: {@value #ORIGIN_CONSOLE}, {@value #ORIGIN_VISITOR} or {@value #ORIGIN_SYSTEM}
 *                  ({@code null} in a file written before the field existed reads as {@value #ORIGIN_SYSTEM})
 */
public record Note(String id, String text, String createdAt, String origin) {

    /** Written by an operator on the demo's console screen. */
    public static final String ORIGIN_CONSOLE = "console";
    /** Sent by a visitor through the public feedback form (the anonymous POST route). */
    public static final String ORIGIN_VISITOR = "visitor";
    /** Written by the plugin itself (the welcome note, the settings-saved audit note). */
    public static final String ORIGIN_SYSTEM = "system";

    public Note {
        text = text == null ? "" : text;
        origin = origin == null || origin.isBlank() ? ORIGIN_SYSTEM : origin;
    }
}
