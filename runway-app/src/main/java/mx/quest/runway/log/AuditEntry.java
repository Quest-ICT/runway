package mx.quest.runway.log;

import java.time.OffsetDateTime;

/**
 * One parsed line of an audit journal — the application activity log ({@link AppActivityLog}) or a site's audit
 * log ({@link SiteLogService}). Both share the line shape {@code time \t actor \t action \t detail}.
 *
 * @param time       when it happened. For a line written with an offset (the current format) this is exactly
 *                   what was written; for an older offset-less line it is the written wall-clock time interpreted in
 *                   the server's <em>current</em> zone — see {@code offsetKnown}
 * @param offsetKnown {@code true} when the line carried its own UTC offset; {@code false} for a legacy line whose
 *                   offset was inferred (ambiguous across a DST change or a time-zone move, which is why the format
 *                   changed)
 * @param actor      who did it (a username, or a system marker)
 * @param action     the stable dotted action key (e.g. {@code site.created}); never localized on disk
 * @param detail     free-form context; may be empty
 */
public record AuditEntry(OffsetDateTime time, boolean offsetKnown, String actor, String action, String detail) {
}
