package mx.quest.runway.head;

import java.util.ArrayList;
import java.util.List;
import mx.quest.runway.plugin.ContributionGuard;
import mx.quest.runway.spi.HeadContributor;
import mx.quest.runway.spi.model.HeadContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.annotation.AnnotationAwareOrderComparator;
import org.springframework.stereotype.Component;

/**
 * Collects every {@link HeadContributor} bean — core and plugin alike — orders them deterministically
 * ({@link org.springframework.core.annotation.Order @Order} / {@link org.springframework.core.Ordered}),
 * and renders their concatenated {@code <head>} markup for a request. This is the kernel half of the
 * edge head-injection seam: contributors arrive as ordinary beans (so a plugin contributes one exactly
 * as the core would), and {@link HeadInjectionFilter} splices their output before {@code </head>}.
 *
 * <p><b>Key collisions</b>: two <b>kernel</b> contributors sharing a key, or a kernel contributor
 * with a blank key, fail startup — a kernel defect must never be papered over. A <b>plugin</b> contributor that
 * collides (with the kernel, or with an earlier plugin in load order) or has a blank key is refused: an ERROR
 * names both sides, the plugin is marked degraded on the plugins screen, and its other contributions still load.
 *
 * <p><b>Failure containment</b>: a contributor whose {@code render} or {@code headInputs} throws contributes
 * nothing for that call, and the result says so ({@link Rendered#degraded()}) so the page is served
 * uncacheable.
 *
 * <p>When no contributors are present the registry is {@linkplain #isEmpty() empty} and
 * {@link #render(HeadContext)} returns {@code ""}; the filter then skips buffering entirely, so the
 * whole feature costs nothing until a contributor (typically a SEO/GEO plugin) is installed.
 *
 * <p>Thread-safe and immutable after construction.
 */
@Component
public class HeadContributorRegistry {

    private final List<HeadContributor> contributors;
    private final ContributionGuard guard;

    /**
     * Unit-test form: every contributor counts as kernel code, so a collision fails fast.
     *
     * @param contributors every {@link HeadContributor} bean Spring discovered (possibly empty)
     * @throws IllegalStateException if a contributor has a blank key or two share a key
     */
    public HeadContributorRegistry(List<HeadContributor> contributors) {
        this(contributors, ContributionGuard.standalone());
    }

    /**
     * @param contributors every {@link HeadContributor} bean Spring discovered (possibly empty)
     * @param guard        the plugin-contribution containment policy
     * @throws IllegalStateException if a <b>kernel</b> contributor has a blank key or two kernel contributors
     *                               share one
     */
    @Autowired
    public HeadContributorRegistry(List<HeadContributor> contributors, ContributionGuard guard) {
        List<HeadContributor> ordered = new ArrayList<>(contributors);
        AnnotationAwareOrderComparator.sort(ordered);
        this.guard = guard;
        this.contributors = List.copyOf(guard.index("HeadContributor", ordered, HeadContributor::key).values());
    }

    /** Whether no contributors are registered (so {@link #render(HeadContext)} can only return {@code ""}). */
    public boolean isEmpty() {
        return contributors.isEmpty();
    }

    /** The outcome of one head render: the markup, and whether any contributor's output was substituted. */
    public record Rendered(String markup, boolean degraded) {
    }

    /**
     * Concatenates the non-blank output of every contributor for {@code context}, in registry order,
     * separated by newlines. A contributor returning {@code null}/blank simply contributes nothing; one that
     * throws contributes nothing and the result is {@linkplain Rendered#degraded() degraded}.
     *
     * @param context the immutable, host-materialized head context (never {@code null})
     * @return the markup to splice before {@code </head>} ({@code ""} when nothing contributes) and the flag
     */
    public Rendered renderChecked(HeadContext context) {
        StringBuilder out = new StringBuilder();
        boolean degraded = false;
        for (HeadContributor contributor : contributors) {
            String[] markup = {null};
            if (!guard.run(contributor, ContributionGuard.Axis.HEAD, contributor.key(),
                    () -> markup[0] = contributor.render(context))) {
                degraded = true;
                continue;
            }
            if (markup[0] != null && !markup[0].isBlank()) {
                if (out.length() > 0) {
                    out.append('\n');
                }
                out.append(markup[0].strip());
            }
        }
        return new Rendered(out.toString(), degraded);
    }

    /** As {@link #renderChecked}, markup only. */
    public String render(HeadContext context) {
        return renderChecked(context).markup();
    }

    /**
     * Every site-relative input the registered contributors declare for {@code siteId}, de-duplicated and in a
     * <b>stable</b> order.
     *
     * <p>Sorted rather than left in registration order: the caller folds these into a page's ETag, and bean
     * registration order can differ between two nodes serving the same site (and between a restart and the
     * one before it). An order-dependent digest would make identical content revalidate as changed — the
     * quiet kind of cache bug that only shows up under a load balancer.
     *
     * <p>A contributor whose {@code headInputs} throws (or breaks the contract by returning {@code null})
     * contributes no inputs; the guard marks the current request degraded, so the page whose ETag would have
     * folded them is served uncacheable instead.
     *
     * @param siteId the site being rendered
     * @return distinct, sorted, site-relative paths (never {@code null}; empty when nothing is declared)
     */
    public List<String> declaredInputs(String siteId) {
        return contributors.stream()
                .flatMap(contributor -> guard.callNonNull(contributor, ContributionGuard.Axis.HEAD_INPUTS,
                        contributor.key(), () -> contributor.headInputs(siteId), List::<String>of).stream())
                .filter(path -> path != null && !path.isBlank())
                .distinct()
                .sorted()
                .toList();
    }
}
