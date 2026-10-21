package mx.quest.runway.spi.model;

import java.util.Objects;

/**
 * One advisory result from a {@link mx.quest.runway.spi.PageQualityCheck} — a single
 * observation about a page. A finding never mutates anything; it is surfaced to the author
 * and, only where a site opts into enforcement, may gate publishing.
 *
 * @param severity the advisory level (never {@code null})
 * @param code     a stable, machine-readable identifier for the kind of finding, for i18n and
 *                 deduplication (for example {@code "title.too-long"}; never {@code null})
 * @param message  a human-readable description ({@code null} normalized to empty); typically a
 *                 resolved message, since the host owns i18n
 */
public record Finding(Severity severity, String code, String message) {

    public Finding {
        Objects.requireNonNull(severity, "severity");
        Objects.requireNonNull(code, "code");
        message = (message == null) ? "" : message;
    }

    /** An {@link Severity#INFO} finding. */
    public static Finding info(String code, String message) {
        return new Finding(Severity.INFO, code, message);
    }

    /** A {@link Severity#WARNING} finding. */
    public static Finding warning(String code, String message) {
        return new Finding(Severity.WARNING, code, message);
    }

    /** A {@link Severity#ERROR} finding. */
    public static Finding error(String code, String message) {
        return new Finding(Severity.ERROR, code, message);
    }
}
