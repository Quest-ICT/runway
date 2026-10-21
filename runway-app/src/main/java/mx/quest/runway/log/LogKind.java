package mx.quest.runway.log;

/**
 * The two kinds of per-site log Runway keeps. Each maps to a day-file prefix under {@code sites/<id>/logs/}
 * ({@code access-YYYY-MM-DD.log}, {@code audit-YYYY-MM-DD.log}).
 */
public enum LogKind {

    /** Public-traffic requests (one line per served public request). */
    ACCESS("access"),

    /** Security-relevant actions on the site (logins, content changes). */
    AUDIT("audit");

    private final String prefix;

    LogKind(String prefix) {
        this.prefix = prefix;
    }

    /** The day-file name prefix for this kind (e.g. {@code access}). */
    public String prefix() {
        return prefix;
    }
}
