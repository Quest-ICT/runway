package mx.quest.runway.log;

import java.util.List;

/**
 * One window of matching audit entries, newest first.
 *
 * @param rows      the matching entries in this window (at most the requested limit), newest first
 * @param offset    how many matching entries precede this window
 * @param hasMore   whether at least one further matching entry exists after this window (within the scan budget)
 * @param truncated whether the scan stopped at its budget (lines scanned, bytes read, or a day-file larger than the
 *                  per-file cap) before reaching the oldest data in range — older matches may exist that this
 *                  request did not look at; the caller should say so and suggest a narrower date range
 * @param scanned   how many journal lines were examined (for the "narrow your search" hint)
 */
public record AuditPage(List<AuditEntry> rows, int offset, boolean hasMore, boolean truncated, long scanned) {

    public AuditPage {
        rows = List.copyOf(rows);
    }

    /** An empty result (no journal, or nothing in range). */
    public static AuditPage empty(int offset) {
        return new AuditPage(List.of(), offset, false, false, 0);
    }
}
