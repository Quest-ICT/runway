package mx.quest.demo;

import java.util.List;

/**
 * The on-disk shape of {@code notes.yml} — a wrapper around the note list so the format can grow new top-level keys
 * without breaking older readers (the store reads with unknown keys ignored).
 *
 * @param notes the site's notes, oldest first; never {@code null}
 */
public record NotesFile(List<Note> notes) {

    public NotesFile {
        notes = (notes == null) ? List.of() : List.copyOf(notes);
    }

    public static NotesFile empty() {
        return new NotesFile(List.of());
    }
}
