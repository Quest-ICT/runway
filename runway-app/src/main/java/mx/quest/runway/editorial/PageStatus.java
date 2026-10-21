package mx.quest.runway.editorial;

import mx.quest.runway.page.PageReview;

/**
 * Where a page stands in the editorial workflow — derived, never stored: from the page's own draft flag plus its
 * pending work ({@link mx.quest.runway.page.PendingRevisionStore}).
 *
 * @param published   whether the live page is published
 * @param review      its review state, or {@code null} when it is not under review
 * @param hasRevision whether a pending revision of the (published) page exists
 */
public record PageStatus(boolean published, PageReview review, boolean hasRevision) {

    /**
     * A stable key for the console's badge and i18n ({@code op.pages.status.<key>}):
     * {@code draft}, {@code in-review}, {@code returned} (a draft sent back), {@code published},
     * {@code published-pending} (a revision waiting for approval), {@code published-returned} (a revision sent
     * back to its editor).
     */
    public String key() {
        boolean submitted = review != null && review.isSubmitted();
        boolean returned = review != null && review.isReturned();
        if (!published) {
            return submitted ? "in-review" : (returned ? "returned" : "draft");
        }
        if (hasRevision) {
            return returned ? "published-returned" : "published-pending";
        }
        return "published";
    }

    /** Whether something about this page is waiting in the review queue. */
    public boolean awaitingReview() {
        return review != null && review.isSubmitted();
    }
}
