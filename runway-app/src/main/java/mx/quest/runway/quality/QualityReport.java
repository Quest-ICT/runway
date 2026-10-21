package mx.quest.runway.quality;

import java.util.Comparator;
import java.util.List;
import mx.quest.runway.spi.model.Finding;
import mx.quest.runway.spi.model.Severity;

/**
 * The result of running the advisory {@link mx.quest.runway.spi.PageQualityCheck}s over one page draft — the
 * findings grouped for display, most-severe first ({@code ERROR} → {@code WARNING} → {@code INFO}), with the
 * registry's original order preserved <b>within</b> each severity (a stable sort). It is purely a read model:
 * quality is advisory, so a report never blocks a save or edits a page — an empty report simply means nothing
 * was flagged (or no checks are installed).
 *
 * @param findings the findings, ordered most-severe first (never {@code null})
 */
public record QualityReport(List<Finding> findings) {

    public QualityReport {
        findings = List.copyOf(findings);
    }

    /** Groups {@code raw} findings by severity (ERROR, WARNING, INFO), preserving order within each severity. */
    public static QualityReport of(List<Finding> raw) {
        List<Finding> ordered = raw.stream()
                .sorted(Comparator.comparingInt(f -> rank(f.severity())))
                .toList();
        return new QualityReport(ordered);
    }

    /** Whether nothing was flagged. */
    public boolean isEmpty() {
        return findings.isEmpty();
    }

    /** Whether at least one finding was reported (the inverse of {@link #isEmpty()}, for template readability). */
    public boolean hasFindings() {
        return !findings.isEmpty();
    }

    /** The findings of exactly {@code severity}, in report order. */
    public List<Finding> ofSeverity(Severity severity) {
        return findings.stream().filter(f -> f.severity() == severity).toList();
    }

    /** The number of {@code ERROR} findings. */
    public long errors() {
        return count(Severity.ERROR);
    }

    /** The number of {@code WARNING} findings. */
    public long warnings() {
        return count(Severity.WARNING);
    }

    /** The number of {@code INFO} findings. */
    public long infos() {
        return count(Severity.INFO);
    }

    private long count(Severity severity) {
        return findings.stream().filter(f -> f.severity() == severity).count();
    }

    private static int rank(Severity severity) {
        return switch (severity) {
            case ERROR -> 0;
            case WARNING -> 1;
            case INFO -> 2;
        };
    }
}
