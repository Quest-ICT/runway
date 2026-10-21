package mx.quest.runway.log;

import java.time.LocalDate;
import java.util.Locale;

/**
 * A filter over an audit journal. Every criterion is optional ({@code null}/blank = no constraint).
 *
 * @param from   first day included (by the day-file's date), or {@code null} for the start of the retention window
 * @param to     last day included (by the day-file's date), or {@code null} for today
 * @param actor  case-insensitive substring the actor must contain, or blank
 * @param action case-insensitive substring the action key must contain (so {@code page.} matches every page
 *               action), or blank
 */
public record AuditQuery(LocalDate from, LocalDate to, String actor, String action) {

    /** No constraint at all — the whole retention window. */
    public static final AuditQuery ALL = new AuditQuery(null, null, null, null);

    public AuditQuery {
        actor = normalize(actor);
        action = normalize(action);
    }

    /** Whether a day-file dated {@code day} can hold matching lines. */
    boolean coversDay(LocalDate day) {
        return (from == null || !day.isBefore(from)) && (to == null || !day.isAfter(to));
    }

    /** Whether a parsed entry passes the actor/action criteria (the date is decided per file). */
    boolean matches(AuditEntry entry) {
        return contains(entry.actor(), actor) && contains(entry.action(), action);
    }

    private static boolean contains(String value, String needle) {
        return needle == null || (value != null && value.toLowerCase(Locale.ROOT).contains(needle));
    }

    private static String normalize(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.strip().toLowerCase(Locale.ROOT);
    }
}
