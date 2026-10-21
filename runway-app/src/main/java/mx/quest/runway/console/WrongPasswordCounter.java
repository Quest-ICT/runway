package mx.quest.runway.console;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.stereotype.Component;

/**
 * Counts the wrong current passwords typed on the Account screens, per session and account ({@link AccountSupport}).
 * A guess <b>reserves</b> its number before its password is hashed, atomically, so a burst of simultaneous guesses
 * cannot all read the same count: guesses past {@link AccountSupport#MAX_WRONG_PASSWORDS} are refused before any
 * hashing, however many arrive at once.
 *
 * <h2>Why not a session attribute</h2>
 * Until 1.0.0 the count was a session attribute, read and written back by each request: two requests at the same
 * moment read the same number, and a burst of eight was answered eight times. Locking the session object does not
 * fix that everywhere: with clustered sessions each request works on its own copy of the session, saved when it
 * ends. This counter lives on the node, where an atomic increment is one step. With clustering on and requests of
 * one session spread over several nodes (no sticky sessions), each node counts its own; the per-IP gate still
 * applies on every node.
 *
 * <p>Bounded and self-cleaning: an entry is dropped by a right password or a spent session, and otherwise expires
 * after {@link #IDLE} without a guess; at most {@link #MAX_ENTRIES} are kept.
 */
@Component
public class WrongPasswordCounter {

    /** How long a count is kept after its last guess: longer than any console session stays idle. */
    static final Duration IDLE = Duration.ofHours(12);
    /** The most counts kept at once; each needs a signed-in session that guessed wrong. */
    static final long MAX_ENTRIES = 100_000;

    private final Cache<String, AtomicInteger> counts =
            Caffeine.newBuilder().maximumSize(MAX_ENTRIES).expireAfterAccess(IDLE).build();

    /**
     * Reserves the next guess for {@code key} and returns its number: 1 for the first guess since the last reset.
     * Called before the password is checked, so the number counts the guesses still being checked too.
     */
    public int reserve(String key) {
        return counts.get(key, unused -> new AtomicInteger()).incrementAndGet();
    }

    /** Clears {@code key}'s count: the right password was typed, or the session's sign-in was dropped. */
    public void reset(String key) {
        counts.invalidate(key);
    }
}
