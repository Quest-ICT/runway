package mx.quest.runway.quality;

import java.util.ArrayList;
import java.util.List;
import mx.quest.runway.plugin.ContributionGuard;
import mx.quest.runway.spi.PageQualityCheck;
import mx.quest.runway.spi.model.Finding;
import mx.quest.runway.spi.model.PageView;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.annotation.AnnotationAwareOrderComparator;
import org.springframework.stereotype.Component;

/**
 * Collects every {@link PageQualityCheck} bean — core and plugin alike — and runs them against a page,
 * aggregating their advisory {@link Finding}s. A blank or duplicate {@code key()} among kernel checks fails
 * startup; a plugin's colliding or blank-keyed check is refused and its plugin marked degraded.
 * A check that throws is replaced by one {@code WARNING} finding, {@value #CHECK_FAILED}, which never blocks
 * a publish. A check reports, never edits; the host decides what to do with the findings. Thread-safe and
 * immutable after construction.
 */
@Component
public class PageQualityCheckRegistry {

    /** The code of the finding that stands in for a check that threw. */
    public static final String CHECK_FAILED = "check.failed";

    private final List<PageQualityCheck> checks;
    private final ContributionGuard guard;

    /** Unit-test form: every check counts as kernel code, so a collision fails fast. */
    public PageQualityCheckRegistry(List<PageQualityCheck> checks) {
        this(checks, ContributionGuard.standalone());
    }

    /**
     * @param checks every {@link PageQualityCheck} bean Spring discovered (possibly empty)
     * @param guard  the plugin-contribution containment policy
     * @throws IllegalStateException if a <b>kernel</b> check has a blank key or two kernel checks share one
     */
    @Autowired
    public PageQualityCheckRegistry(List<PageQualityCheck> checks, ContributionGuard guard) {
        List<PageQualityCheck> ordered = new ArrayList<>(checks);
        AnnotationAwareOrderComparator.sort(ordered);
        this.guard = guard;
        this.checks = List.copyOf(guard.index("PageQualityCheck", ordered, PageQualityCheck::key).values());
    }

    /** Whether no checks are registered. */
    public boolean isEmpty() {
        return checks.isEmpty();
    }

    /** Runs every check against {@code page} and returns all findings, in registry order. */
    public List<Finding> inspect(PageView page) {
        List<Finding> findings = new ArrayList<>();
        for (PageQualityCheck check : checks) {
            // Contained: a throwing check reports one WARNING in its place — never an ERROR, so a broken check can never
            // block a publish on an Enforced or Reviewed site. A null return stays "nothing to report" (the host's
            // documented tolerance).
            List<Finding> reported = guard.call(check, ContributionGuard.Axis.QUALITY, check.key(),
                    () -> check.inspect(page),
                    () -> List.of(Finding.warning(CHECK_FAILED, "the quality check '" + check.key()
                            + "' failed to run, so its result is missing")));
            if (reported != null) {
                for (Finding finding : reported) {
                    if (finding != null) {
                        findings.add(finding);
                    }
                }
            }
        }
        return List.copyOf(findings);
    }
}
