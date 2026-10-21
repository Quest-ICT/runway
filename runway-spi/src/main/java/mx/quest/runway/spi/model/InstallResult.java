package mx.quest.runway.spi.model;

import java.util.List;
import java.util.Objects;

/**
 * What {@link mx.quest.runway.spi.service.SitePages#install} did. Immutable.
 *
 * @param outcome  what happened (never {@code null})
 * @param path     the page's path: the new page's, or the existing page's for {@link Outcome#ALREADY_EXISTS}
 *                 (never {@code null})
 * @param blocking the quality findings that kept the page a draft, for {@link Outcome#DRAFT_BLOCKED}; empty
 *                 otherwise (copied, never {@code null})
 * @since SPI 0.5.0
 */
public record InstallResult(Outcome outcome, String path, List<Finding> blocking) {

    public InstallResult {
        Objects.requireNonNull(outcome, "outcome");
        Objects.requireNonNull(path, "path");
        blocking = blocking == null ? List.of() : List.copyOf(blocking);
    }

    /**
     * The four install outcomes. This enum may gain constants in a MINOR release: a {@code switch} over it needs a
     * {@code default} branch.
     */
    public enum Outcome {
        /** The page was created and is live. */
        PUBLISHED,
        /** The page was created as a draft and submitted to the site's review queue (a Reviewed site). */
        PENDING_REVIEW,
        /** The page was created but stays a draft: the site's quality checks blocked publishing it. */
        DRAFT_BLOCKED,
        /** A page with the slug already exists; nothing was changed. */
        ALREADY_EXISTS
    }
}
