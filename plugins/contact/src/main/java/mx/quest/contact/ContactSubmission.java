package mx.quest.contact;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * One stored contact-form submission. Immutable; fields are already sanitized (no tabs/newlines) by
 * {@link ContactStore} so the on-disk TSV format stays unambiguous.
 *
 * @param epochMillis when it was received
 * @param name        the submitter's name
 * @param email       the submitter's email
 * @param message     the message (newlines collapsed to spaces on storage)
 */
public record ContactSubmission(long epochMillis, String name, String email, String message) {

    private static final DateTimeFormatter WHEN =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());

    /** A {@code yyyy-MM-dd HH:mm} timestamp for display. */
    public String time() {
        return WHEN.format(Instant.ofEpochMilli(epochMillis));
    }

    /** A short excerpt of the message for a listing cell. */
    public String excerpt() {
        String m = message == null ? "" : message.strip();
        return m.length() <= 80 ? m : m.substring(0, 80).stripTrailing() + "…";
    }
}
