package mx.quest.runway.spi.model;

/**
 * The advisory level of a {@link Finding} reported by a
 * {@link mx.quest.runway.spi.PageQualityCheck}. Findings are advisory by default — the
 * severity ranks importance for display and optional opt-in publish gating; it never forces
 * an edit or blocks publishing on its own.
 */
public enum Severity {

    /** Informational — a suggestion or neutral observation. */
    INFO,

    /** A likely problem worth attention, but not necessarily blocking. */
    WARNING,

    /** A serious problem; the most likely candidate to gate on when a site opts into enforcement. */
    ERROR
}
