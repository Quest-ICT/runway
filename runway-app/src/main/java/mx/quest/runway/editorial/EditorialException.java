package mx.quest.runway.editorial;

/**
 * An editorial action that the workflow refuses for a reason the user can act on — not a fault. The {@link #code()}
 * is stable and maps to a localized console message {@code op.review.error.<code>}:
 *
 * <ul>
 *   <li>{@code needs-approval} — the site requires review and the user does not hold {@code APPROVE_PAGES}, so
 *       they cannot publish (they submit for review instead);</li>
 *   <li>{@code not-reviewed} — submitting for review on a site that does not require review;</li>
 *   <li>{@code nothing-to-review} — submitting a published page that has no pending revision;</li>
 *   <li>{@code not-in-review} — approving or sending back something that is not waiting for review;</li>
 *   <li>{@code changed} — the content under review changed after the approver opened it; reload and look again;</li>
 *   <li>{@code note-required} — sending back without saying why;</li>
 *   <li>{@code no-such-page} — the page does not exist.</li>
 * </ul>
 */
public class EditorialException extends RuntimeException {

    private final String code;

    public EditorialException(String code, String message) {
        super(message);
        this.code = code;
    }

    /** The stable reason code. */
    public String code() {
        return code;
    }
}
