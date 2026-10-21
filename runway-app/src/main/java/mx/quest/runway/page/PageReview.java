package mx.quest.runway.page;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonValue;
import java.time.Instant;
import java.util.Locale;
import java.util.Objects;

/**
 * The editorial-review state of one page, stored beside its pending work in
 * {@code templates/.pending/<slug>/review.yml} (see {@link PendingRevisionStore}). It deliberately does
 * <b>not</b> live in the page's own {@code .meta.yml}: a {@link Page} is constructed by plugins and read by every
 * routing path, and review state is neither — keeping it out of the record means every existing page file and
 * every plugin-built {@code Page} stays exactly as it was.
 *
 * <p>What is under review depends on the page: for a <b>draft</b> it is the page's own files (a first
 * publication); for a <b>published</b> page it is the pending revision stored next to this file.
 *
 * @param state       {@link State#SUBMITTED} (waiting in the review queue) or {@link State#RETURNED} (sent back to
 *                    the editor with a note; not in the queue)
 * @param submittedBy who submitted it (user name; never {@code null}, blank when unknown)
 * @param submittedAt when it was submitted ({@code null} when unknown)
 * @param note        the approver's note when {@link State#RETURNED}, else {@code null}
 * @param returnedBy  who sent it back, or {@code null}
 * @param returnedAt  when it was sent back, or {@code null}
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PageReview(State state, String submittedBy, Instant submittedAt, String note, String returnedBy,
                         Instant returnedAt) {

    /** Upper bound on a stored note, so a pasted essay cannot bloat the file or the editor banner. */
    public static final int MAX_NOTE = 2000;

    public PageReview {
        Objects.requireNonNull(state, "state");
        submittedBy = submittedBy == null ? "" : submittedBy;
        note = (note == null || note.isBlank()) ? null : clip(note.strip());
    }

    /** A fresh submission by {@code who}, now. */
    public static PageReview submitted(String who, Instant at) {
        return new PageReview(State.SUBMITTED, who, at, null, null, null);
    }

    /** This review sent back by {@code who} with {@code note}, keeping who submitted it. */
    public PageReview returned(String who, Instant at, String note) {
        return new PageReview(State.RETURNED, submittedBy, submittedAt, note, who, at);
    }

    /** Whether it is waiting in the review queue. */
    public boolean isSubmitted() {
        return state == State.SUBMITTED;
    }

    /** Whether it was sent back to the editor. */
    public boolean isReturned() {
        return state == State.RETURNED;
    }

    private static String clip(String note) {
        return note.length() <= MAX_NOTE ? note : note.substring(0, MAX_NOTE);
    }

    /** The two review states; stored lower-case. */
    public enum State {
        SUBMITTED, RETURNED;

        @JsonValue
        public String key() {
            return name().toLowerCase(Locale.ROOT);
        }

        /** Parses the stored key; an unknown value reads as {@link #RETURNED} (out of the queue, nothing lost). */
        @JsonCreator
        public static State fromKey(String key) {
            return "submitted".equalsIgnoreCase(key == null ? "" : key.trim()) ? SUBMITTED : RETURNED;
        }
    }
}
